package qa.system;

import com.google.gson.*;
import com.remrin.rules.CarpetRuleCatalog;
import com.remrin.server.*;
import com.remrin.server.config.MachineConfig;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Test-only fixture/coordination server. All product operations still go through real product packets. */
public final class SystemQa implements ModInitializer {
    private static final Gson GSON = new Gson();
    private final Map<String, Boolean> checkpoints = new LinkedHashMap<>();
    private final Map<String, Integer> joins = new LinkedHashMap<>();
    private final Map<String, JsonObject> reports = new LinkedHashMap<>();
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private boolean fixture, closed;
    private int finishedAt = -1, checks;
    private long previousTick = System.nanoTime();

    @Override public void onInitialize() {
        if (System.getProperty("system.outputRoot") == null || !Files.isRegularFile(QaTrace.output().resolve(".system-test-isolated"))) {
            throw new IllegalStateException("System QA requires an isolated runner directory");
        }
        PayloadTypeRegistry.serverboundPlay().register(QaProtocol.Request.TYPE, QaProtocol.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QaProtocol.Response.TYPE, QaProtocol.Response.CODEC);
        QaTrace.log("process.start", Map.of("java", System.getProperty("java.version"), "seed", Long.getLong("system.seed", 132212L),
            "environment", FabricLoader.getInstance().getEnvironmentType().name()));
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                require("dedicated server entrypoint", server.isDedicatedServer());
                require("product commands registered on first load", server.getCommands().getDispatcher().getRoot().getChild("machineadmin") != null
                    && server.getCommands().getDispatcher().getRoot().getChild("cgtest") != null);
                var origins = FabricLoader.getInstance().getModContainer("command-gui").orElseThrow().getOrigin().getPaths();
                require("assembled product JAR loaded", origins.stream().allMatch(p -> p.toString().endsWith(".jar")));
                QaTrace.writeJson(QaTrace.output().resolve("ready.json"), Map.of("ready", true, "port", boundPort(server),
                    "productOrigins", origins.stream().map(Path::toString).toList()));
                QaTrace.log("server.ready", Map.of("port", boundPort(server)));
            } catch (Exception failure) {
                QaTrace.log("server.failure", failure.toString()); finish(server, false); server.halt(false);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            var player = listener.getPlayer(); String name = player.getGameProfile().name();
            joins.merge(name, 1, Integer::sum);
            if (name.equals("ActorA")) setPermission(server, player, 4);
            else if (name.equals("ActorB")) setPermission(server, player, 0);
            QaTrace.log("server.join", Map.of("name", name, "uuid", player.getUUID().toString(), "joinCount", joins.get(name)));
            MachineManager.broadcastSync(server.getPlayerList());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> QaTrace.log("server.disconnect", Map.of(
            "name", listener.getPlayer().getGameProfile().name(), "tick", server.getTickCount())));
        ServerPlayNetworking.registerGlobalReceiver(QaProtocol.Request.TYPE, (payload, ctx) -> {
            String role = ctx.player().getGameProfile().name();
            var response = new JsonObject(); String op = "invalid";
            try {
                if (!role.equals("ActorA") && !role.equals("ActorB")) throw new IllegalArgumentException("Unknown test actor");
                var request = JsonParser.parseString(payload.json()).getAsJsonObject(); op = request.get("op").getAsString();
                switch (op) {
                    case "checkpoint" -> checkpoints.put(role + "." + request.get("name").getAsString(), true);
                    case "snapshot", "status" -> { }
                    case "fixture" -> prepareFixture(ctx.server());
                    case "setPermission" -> {
                        onlyCoordinator(role);
                        var player = ctx.server().getPlayerList().getPlayerByName(request.get("role").getAsString());
                        if (player == null) throw new IllegalArgumentException("Actor is offline");
                        int level = request.get("level").getAsInt();
                        if (level < 0 || level > 4) throw new IllegalArgumentException("Permission level out of range");
                        setPermission(ctx.server(), player, level);
                        MachineManager.broadcastSync(ctx.server().getPlayerList());
                    }
                    case "setEditor" -> {
                        onlyCoordinator(role);
                        if (request.get("enabled").getAsBoolean()) MachineConfig.addEditorWhitelist(request.get("role").getAsString());
                        else MachineConfig.removeEditorWhitelist(request.get("role").getAsString());
                        MachineManager.broadcastSync(ctx.server().getPlayerList());
                    }
                    case "power" -> {
                        onlyCoordinator(role);
                        var pos = new BlockPos(request.get("x").getAsInt(), request.get("y").getAsInt(), request.get("z").getAsInt());
                        if (pos.getY() != 80 || pos.getZ() != 0 || !List.of(0, 2, 4).contains(pos.getX())) throw new IllegalArgumentException("Outside fixture");
                        ctx.server().overworld().setBlockAndUpdate(pos, Blocks.LEVER.defaultBlockState()
                            .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                            .setValue(BlockStateProperties.POWERED, request.get("powered").getAsBoolean()));
                        MachineManager.broadcastSync(ctx.server().getPlayerList());
                    }
                    case "finish" -> {
                        if (reports.putIfAbsent(role, request.deepCopy()) != null) throw new IllegalArgumentException("Duplicate finish");
                        if (reports.size() == 2) finishedAt = ctx.server().getTickCount();
                    }
                    default -> throw new IllegalArgumentException("Unknown QA operation " + op);
                }
                var data = snapshot(ctx.server());
                if (request.has("includeRules") && request.get("includeRules").getAsBoolean()) {
                    var values = new JsonObject();
                    for (var rule : CarpetRuleCatalog.read()) if (List.of("flippinCactus", "pushLimit").contains(rule.name())) values.addProperty(rule.name(), rule.value());
                    data.add("ruleValues", values);
                }
                response.addProperty("ok", true); response.add("data", data);
            } catch (Exception failure) {
                response.addProperty("ok", false); response.addProperty("error", failure.toString());
            }
            response.addProperty("op", op);
            QaTrace.log("server.rpc", Map.of("role", role, "id", payload.id(), "op", op, "ok", response.get("ok").getAsBoolean()));
            ServerPlayNetworking.send(ctx.player(), new QaProtocol.Response(payload.id(), response.toString()));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (closed) return;
            long now = System.nanoTime(), elapsed = now - previousTick; previousTick = now;
            if (server.getTickCount() % 20 == 0) QaTrace.log("server.sample", Map.of("tick", server.getTickCount(), "intervalNanos", elapsed,
                "players", server.getPlayerCount(), "machines", MachineConfig.getMachines().size(), "running", MachineScheduler.hasRunning(),
                "usedHeap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            boolean stopRequested = Files.exists(QaTrace.output().resolve("stop.request"));
            boolean actorsLeft = server.getPlayerList().getPlayers().stream().noneMatch(p -> p.getGameProfile().name().startsWith("Actor"));
            if (stopRequested || finishedAt >= 0 && (actorsLeft || server.getTickCount() - finishedAt > 60)) {
                finish(server, !stopRequested && reports.size() == 2); server.halt(false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (!closed) finish(server, false); });
    }

    private static void onlyCoordinator(String role) { if (!role.equals("ActorA")) throw new IllegalArgumentException("Fixture changes require ActorA"); }
    private static void setPermission(MinecraftServer server, ServerPlayer player, int level) {
        var profile = new NameAndId(player.getGameProfile());
        if (level == 0) server.getPlayerList().deop(profile);
        else server.getPlayerList().op(profile, Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level))), Optional.empty());
        server.getPlayerList().sendPlayerPermissionLevel(player);
    }
    private void prepareFixture(MinecraftServer server) {
        if (fixture) return;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "forceload add 0 0 15 15");
        for (int x : List.of(0, 2, 4)) {
            var pos = new BlockPos(x, 80, 0); server.overworld().getChunkAt(pos);
            server.overworld().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            server.overworld().setBlockAndUpdate(pos, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        }
        for (String name : List.of("SystemBot", "SystemMode", "SystemBatch", "SystemTimed")) server.services().nameToIdCache().add(NameAndId.createOffline(name));
        fixture = true; QaTrace.log("server.fixture", "three floor levers at y=80, separate test-world only");
    }
    private JsonObject snapshot(MinecraftServer server) {
        var result = new JsonObject(); result.add("checkpoints", GSON.toJsonTree(checkpoints));
        result.add("online", GSON.toJsonTree(server.getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().name()).toList()));
        result.add("joins", GSON.toJsonTree(joins)); result.addProperty("tick", server.getTickCount());
        result.add("editorWhitelist", GSON.toJsonTree(MachineConfig.getEditorWhitelist()));
        result.add("executionTrace", GSON.toJsonTree(MachineScheduler.dumpExecTrace()));
        var failures = new JsonArray();
        for (var report : reports.entrySet()) if (!report.getValue().has("passed") || !report.getValue().get("passed").getAsBoolean()) failures.add(report.getKey());
        result.add("peerFailures", failures);
        var machines = new JsonArray();
        for (var machine : MachineConfig.getMachines()) {
            var data = GSON.toJsonTree(machine).getAsJsonObject();
            data.addProperty("running", MachineScheduler.isRunning(machine.id));
            data.addProperty("detected", MachineDetector.evaluate(machine, server).name().toLowerCase(Locale.ROOT));
            var modes = new JsonObject();
            for (var mode : machine.modes) {
                var state = new JsonObject(); state.addProperty("running", MachineScheduler.isModeRunning(machine.id, mode.id));
                state.addProperty("processing", MachineScheduler.isModeProcessRunning(machine.id, mode.id));
                state.addProperty("detected", MachineDetector.evaluateDetection(mode.detection, server, true).state().name().toLowerCase(Locale.ROOT));
                modes.add(mode.id, state);
            }
            data.add("modeStates", modes); machines.add(data);
        }
        result.add("machines", machines); return result;
    }
    private void require(String name, boolean pass) {
        checks++;
        cases.add(Map.of("group", "dedicated-server", "name", name, "status", pass ? "PASS" : "FAIL", "error", pass ? "" : name));
        QaTrace.log("server.assert", Map.of("name", name, "passed", pass));
    }
    private void finish(MinecraftServer server, boolean complete) {
        if (closed) return; closed = true;
        require("both actors completed", reports.size() == 2);
        for (String role : List.of("ActorA", "ActorB")) require(role + " reports success", reports.containsKey(role)
            && reports.get(role).has("passed") && reports.get(role).get("passed").getAsBoolean());
        require("both real players joined", joins.containsKey("ActorA") && joins.containsKey("ActorB"));
        require("both real players reconnected", joins.getOrDefault("ActorA", 0) > 1 && joins.getOrDefault("ActorB", 0) > 1);
        require("TCP transport used both directions", QaTrace.count("tcp:receive") > 0 && QaTrace.count("tcp:send") > 0);
        require("machine actions received on TCP", QaTrace.count("receive:command-gui-server:action") > 0);
        require("machine responses sent on TCP", QaTrace.count("send:command-gui-server:machines") > 0);
        require("rule query and apply received on TCP", QaTrace.count("receive:command-gui:rules-query") > 0 && QaTrace.count("receive:command-gui:rules-apply") > 0);
        require("real machine bot joined", joins.containsKey("SystemBot"));
        require("real mode bot joined", joins.containsKey("SystemMode"));
        require("trace writer healthy", QaTrace.failure().isEmpty());
        boolean passed = complete && cases.stream().allMatch(c -> c.get("status").equals("PASS"));
        try { QaTrace.writeJson(QaTrace.output().resolve("report.json"), Map.of("schemaVersion", 1, "passed", passed, "complete", complete,
            "assertions", checks, "cases", cases, "joins", joins, "packetCounts", QaTrace.counts(), "clients", reports)); }
        catch (Exception error) { error.printStackTrace(); }
        QaTrace.log("server.finished", Map.of("passed", passed, "complete", complete));
    }
    private static int boundPort(MinecraftServer server) throws ReflectiveOperationException {
        var listener = server.getConnection(); var field = listener.getClass().getDeclaredField("channels"); field.setAccessible(true);
        for (Object item : (List<?>)field.get(listener)) {
            var channel = ((io.netty.channel.ChannelFuture)item).channel();
            if (channel.localAddress() instanceof InetSocketAddress socket) return socket.getPort();
        }
        throw new IllegalStateException("No TCP listener");
    }
}

package qa;

import com.google.gson.GsonBuilder;
import com.remrin.client.gui.*;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import com.remrin.server.MachineMod;
import com.remrin.server.MachineScheduler;
import com.remrin.server.net.MachinePayloads;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Test-only mod. Creates its own worlds; never reads a user's game directory. */
public final class EndToEndQa implements ClientModInitializer {
    private static final String ID = "e2e-machine";
    private static final BlockPos DETECTOR = new BlockPos(0, 80, 0);
    private final boolean carpet = FabricLoader.getInstance().isModLoaded("carpet");
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private int stage, ticks, checks;
    private long revision;
    private boolean finished;
    private long stageStarted = System.nanoTime();
    private CompletableFuture<Void> serverWork;
    private MinecraftServer previousServer;
    private RuleData originalRule;
    private CommandGUIScreen panel;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finished) return;
            try {
                if (++ticks > 1800) throw new AssertionError("Timeout at stage " + stage + ", screen=" + mc.gui.screen());
                if (mc.gui.overlay() != null) return;
                step(mc);
            } catch (Throwable error) {
                error.printStackTrace();
                cases.add(Map.of("name", "stage-" + stage, "status", "FAIL", "error", error.toString()));
                finish(mc, false);
            }
        });
    }

    private void step(Minecraft mc) throws Exception {
        switch (stage) {
            case 0 -> {
                if (!(mc.gui.screen() instanceof TitleScreen) || ticks < 25) return;
                mc.options.pauseOnLostFocus = false;
                mc.options.guiScale().set(2);
                ClientRegressionChecks.run(mc);
                checks += (int)fieldStatic(ClientRegressionChecks.class, "checks");
                createWorld(mc, "E2E-A");
                next("client initialized without authentication");
            }
            case 1 -> {
                if (mc.player == null || mc.getSingleplayerServer() == null) return;
                server(mc, s -> {
                    s.getWorldData().setAllowCommands(true);
                    s.getPlayerList().op(new NameAndId(s.getPlayerList().getPlayers().getFirst().getGameProfile()));
                    s.overworld().setBlockAndUpdate(DETECTOR.below(), Blocks.STONE.defaultBlockState());
                    s.overworld().setBlockAndUpdate(DETECTOR, Blocks.LEVER.defaultBlockState()
                        .setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
                    s.services().nameToIdCache().add(NameAndId.createOffline("E2E_Bot"));
                });
                next("fresh integrated world created");
            }
            case 2 -> {
                if (!serverDone() || !ClientPlayNetworking.canSend(MachinePayloads.ActionPayload.TYPE)) return;
                MachineNetworkManager.sendRequestSync();
                next("Fabric payload handshake negotiated");
            }
            case 3 -> {
                if (!MachineNetworkManager.isServerSupported() || !MachineNetworkManager.canConfig()) return;
                require(CommandHelper.validateCommandFormat("/tp {x} {y} {z}") == null, "legacy coordinates validate against the real command tree");
                require(CommandHelper.validateCommandFormat("/execute as @s run say hi") == null, "real redirected command validates");
                require(CommandHelper.validateCommandFormat("/setblock") != null, "real incomplete command rejected");
                require(MachineNetworkManager.getMachine(ID) == null, "new test profile has no old machine");
                MachineNetworkManager.sendAdd(machine());
                next("machine add sent through live connection");
            }
            case 4 -> {
                var m = MachineNetworkManager.getMachine(ID);
                if (m == null) return;
                require(m.name.equals("E2E机器"), "Chinese name survived packet and server storage");
                require("off".equals(m.detected), "actual world detector initially off");
                revision = m.revision;
                MachineNetworkManager.sendEditSession(ID, true);
                next("machine synchronized from server");
            }
            case 5 -> {
                var m = MachineNetworkManager.getMachine(ID);
                if (m.editingBy == null || m.editingBy.isEmpty()) return;
                var edit = copy(m); edit.description = "网络保存 $ 与中文";
                MachineNetworkManager.sendEdit(edit, m.revision);
                next("edit lock granted over network");
            }
            case 6 -> {
                var m = MachineNetworkManager.getMachine(ID);
                if (m.revision <= revision) return;
                require(m.description.equals("网络保存 $ 与中文"), "edit persisted and acknowledged");
                revision = m.revision;
                MachineNetworkManager.sendEditSession(ID, true);
                var stale = copy(m); stale.name = "STALE MUST NOT SAVE";
                MachineNetworkManager.sendEdit(stale, revision - 1);
                MachineNetworkManager.sendEditSession(ID, false);
                next("stale revision submitted for rejection");
            }
            case 7 -> {
                if (ticks < 20 || !MachineNetworkManager.getMachine(ID).editingBy.isEmpty()) return;
                require(MachineNetworkManager.getMachine(ID).name.equals("E2E机器"), "stale edit rejected");
                require(MachineNetworkManager.getMachine(ID).revision == revision, "rejected edit did not bump revision");
                ClientPlayNetworking.send(new MachinePayloads.ActionPayload("{broken"));
                ClientPlayNetworking.send(new MachinePayloads.ActionPayload("{\"type\":\"unknown-e2e\"}"));
                MachineNetworkManager.sendRequestSync();
                next("invalid and unknown requests submitted");
            }
            case 8 -> {
                if (ticks < 20) return;
                require(mc.player != null && MachineNetworkManager.getMachine(ID) != null, "bad requests do not disconnect client");
                panel = new CommandGUIScreen(); mc.gui.setScreen(panel);
                var tabs = (TabNavigationBar)field(panel, "tabNavigationBar");
                int index = -1;
                for (int i = 0; i < tabs.getTabs().size(); i++) if (tabs.getTabs().get(i) instanceof MachineSwitchTab) index = i;
                require(index >= 0, "machine tab available"); tabs.selectTab(index, false); panel.tick();
                next("real machine GUI opened");
            }
            case 9 -> {
                if (ticks < 10) return;
                click(mc, "E2E机器");
                require(MachineNetworkManager.getMachine(ID).detected.equals("off"), "selection alone does not execute");
                click(mc, "确认执行（1）");
                Screenshot.grab(mc.gameDirectory, "e2e-machine.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
                next("GUI selection and confirmation clicked");
            }
            case 10 -> {
                var m = MachineNetworkManager.getMachine(ID);
                if (carpet) {
                    if (!"on".equals(m.detected) || m.running) return;
                    require(true, "GUI click executed actual Carpet spawn and detector command");
                    MachineNetworkManager.sendRequestFakeStates();
                } else {
                    if (ticks < 30) return;
                    require(!m.running && "off".equals(m.detected), "missing Carpet aborts boot cleanly");
                }
                next(carpet ? "machine boot completed" : "optional Carpet absence handled");
            }
            case 11 -> {
                if (carpet && !MachineNetworkManager.isServerFakePlayer("E2E_Bot")) return;
                if (carpet) require(MachineNetworkManager.isFakePlayerStatesSupported(), "real fake player state round trip");
                CarpetRuleClient.refresh();
                next("rule query sent through live connection");
            }
            case 12 -> {
                if (CarpetRuleClient.busy()) return;
                if (carpet) {
                    originalRule = CarpetRuleClient.rules().stream().filter(r -> r.name().equals("flippinCactus")).findFirst().orElseThrow();
                    CarpetRuleClient.stage(originalRule, "set", originalRule.value().equals("true") ? "false" : "true");
                    mc.gui.setScreen(new CarpetRuleConfirmScreen(panel));
                    click(mc, "确认执行 1 项");
                } else require(CarpetRuleClient.status().contains("未安装 Carpet"), "no-Carpet query explains optional dependency");
                next("rule batch confirmation submitted");
            }
            case 13 -> {
                if (carpet) {
                    if (CarpetRuleClient.busy() || !CarpetRuleClient.pending.isEmpty()) return;
                    var updated = CarpetRuleClient.rules().stream().filter(r -> r.key().equals(originalRule.key())).findFirst().orElseThrow();
                    if (updated.value().equals(originalRule.value())) return;
                    require(true, "rule changed on server and refreshed in client");
                    CarpetRuleClient.stage(updated, "set", originalRule.value()); CarpetRuleClient.apply();
                }
                next("rule change acknowledged");
            }
            case 14 -> {
                if (carpet && (CarpetRuleClient.busy() || !CarpetRuleClient.pending.isEmpty())) return;
                mc.gui.setScreen(null);
                MachineNetworkManager.sendToggle(ID);
                next("shutdown requested");
            }
            case 15 -> {
                var m = MachineNetworkManager.getMachine(ID);
                if (ticks < 20 || m.running || !"off".equals(m.detected)) return;
                require(true, "machine remains off after shutdown or missing dependency rejection");
                server(mc, s -> s.getWorldData().setAllowCommands(false));
                next("integrated-world cheats revoked");
            }
            case 16 -> {
                if (!serverDone() || ticks < 15) return;
                require(!CarpetRuleClient.canView(), "revoked cheats hide privileged rule controls");
                require(CarpetRuleClient.rules().isEmpty(), "revocation clears cached rules");
                server(mc, s -> s.getWorldData().setAllowCommands(true));
                next("permission revocation reflected by client");
            }
            case 17 -> {
                if (!serverDone()) return;
                TimedTaskManager.addSpawnTask("E2E_LATE", 1, 0, 0);
                ChainedCommandExecutor.executeMulti(null, List.of("say E2E now", "say E2E MUST NOT CROSS WORLD"), 12000);
                require(TimedTaskManager.getTask("E2E_LATE") != null, "delayed task queued");
                previousServer = mc.getSingleplayerServer();
                mc.disconnect(new TitleScreen(), false);
                next("client disconnected with queued tasks");
            }
            case 18 -> {
                if (mc.getSingleplayerServer() != null || !(mc.gui.screen() instanceof TitleScreen)) return;
                require(TimedTaskManager.getAllTasks().isEmpty(), "disconnect clears timed tasks");
                require(((List<?>)fieldStatic(ChainedCommandExecutor.class, "delayedQueue")).isEmpty(), "disconnect clears command chain");
                require(!MachineNetworkManager.isServerSupported() && MachineNetworkManager.getMachines().isEmpty(), "disconnect clears client machine state");
                require(MachineMod.getCurrentServer() == null && !MachineScheduler.hasRunning(), "stopped integrated server releases runtime state");
                createWorld(mc, "E2E-B");
                next("disconnect cleanup verified");
            }
            case 19 -> {
                if (mc.player == null || mc.getSingleplayerServer() == null) return;
                require(mc.getSingleplayerServer() != previousServer, "second world has a new integrated server");
                MachineNetworkManager.sendRequestSync();
                next("second world connected in same JVM");
            }
            case 20 -> {
                if (!MachineNetworkManager.isServerSupported() || ticks < 20) return;
                // Machine configuration is intentionally profile-global, while runtime/world state is session-local.
                require(MachineNetworkManager.getMachine(ID) != null, "saved profile configuration reloaded");
                require(!MachineNetworkManager.getMachine(ID).running, "old runtime not replayed in new world");
                require(TimedTaskManager.getAllTasks().isEmpty(), "no timed task leaked into second world");
                MachineNetworkManager.sendDelete(ID);
                next("cross-world lifecycle verified");
            }
            case 21 -> {
                if (MachineNetworkManager.getMachine(ID) != null) return;
                next("machine deletion acknowledged over live connection");
                finish(mc, true);
            }
            default -> throw new AssertionError("Unexpected stage " + stage);
        }
    }

    private static void createWorld(Minecraft mc, String name) {
        mc.createWorldOpenFlows().createFreshLevel(name,
            new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
            new WorldOptions(132212L, false, false), WorldPresets::createTestWorldDimensions, new TitleScreen());
    }
    private static MachineModels.MachineData machine() {
        var m = new MachineModels.MachineData(); m.id = ID; m.name = "E2E机器"; m.bots.add("E2E_Bot"); m.switchInterval = 0;
        m.onTimeline.steps.add(step("player {bot} spawn at 4 81 4", "setblock 0 80 0 minecraft:lever[face=floor,powered=true]"));
        m.offTimeline.steps.add(step("setblock 0 80 0 minecraft:lever[face=floor,powered=false]"));
        m.detection = new MachineModels.DetectionData(); m.detection.enabled = true; m.detection.y = 80;
        m.detection.blockId = "minecraft:lever"; m.detection.property = "powered";
        m.detection.onValues.add("powered=true"); m.detection.offValues.add("powered=false");
        m.detection.ignoreValues.addAll(List.of("face=floor", "facing=north"));
        return m;
    }
    private static MachineModels.Step step(String... commands) { var step = new MachineModels.Step(); step.commands.addAll(List.of(commands)); return step; }
    private static MachineModels.MachineData copy(MachineModels.MachineData m) { var g = new GsonBuilder().create(); return g.fromJson(g.toJson(m), MachineModels.MachineData.class); }
    private void server(Minecraft mc, java.util.function.Consumer<MinecraftServer> action) {
        var s = mc.getSingleplayerServer(); serverWork = CompletableFuture.runAsync(() -> action.accept(s), s::execute);
    }
    private boolean serverDone() { if (!serverWork.isDone()) return false; serverWork.join(); return true; }
    private void click(Minecraft mc, String text) {
        var button = mc.gui.screen().children().stream().filter(w -> w instanceof Button).map(w -> (Button)w)
            .filter(b -> b.active && b.visible && b.getMessage().getString().contains(text)).findFirst()
            .orElseThrow(() -> new AssertionError("Button not found: " + text + "; screen=" + mc.gui.screen()));
        // Route through Screen's actual hit testing and widget event dispatch.
        var event = new net.minecraft.client.input.MouseButtonEvent(button.getX() + button.getWidth() / 2.0,
            button.getY() + button.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(0, 0));
        require(mc.gui.screen().mouseClicked(event, false), "screen consumed click: " + text);
        mc.gui.screen().mouseReleased(event);
    }
    private static Object field(Object o, String name) throws Exception { var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static Object fieldStatic(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private void require(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    private void next(String name) {
        cases.add(Map.of("name", name, "status", "PASS", "milliseconds", (System.nanoTime() - stageStarted) / 1_000_000));
        System.out.println("COMMAND_E2E_PASS " + stage + " " + name);
        stage++; ticks = 0; stageStarted = System.nanoTime();
    }
    private void finish(Minecraft mc, boolean passed) {
        finished = true;
        try {
            var result = Map.of("passed", passed, "complete", passed, "carpet", carpet, "assertions", checks, "cases", cases);
            Files.writeString(mc.gameDirectory.toPath().resolve("e2e-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception error) { error.printStackTrace(); }
        System.out.println(passed ? "COMMAND_E2E_COMPLETE" : "COMMAND_E2E_FAILED");
        mc.stop();
    }
}

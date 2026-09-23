package qa.system;

import com.google.gson.*;
import com.remrin.client.gui.*;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import com.remrin.rules.RulePayloads;
import com.remrin.server.net.MachinePayloads;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Dedicated-server, two-real-client journey. No product receiver is replaced or mocked. */
public final class ClientJourney implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ID = "system-machine", NAME = "System Machine";
    private final String role = System.getProperty("system.role", "ActorA");
    private final boolean actorA = role.equals("ActorA");
    private final boolean carpet = FabricLoader.getInstance().isModLoaded("carpet");
    private final long seed = Long.getLong("system.seed", 132212L);
    private final List<Stage> stages = new ArrayList<>();
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private final Map<Integer, CompletableFuture<JsonObject>> requests = new HashMap<>();
    private JsonObject snapshot = new JsonObject();
    private CompletableFuture<JsonObject> lastRpc;
    private int index, ticks, assertions, requestId, observedSync = -1, observedFake = -1, observedRule = -1;
    private long revision;
    private boolean entered, finishing, complete;
    private int stopTicks;
    private volatile int disconnectEvents;
    private int disconnectBaseline;
    private long stageStarted = System.nanoTime(), lastPoll;
    private CommandGUIScreen panel;
    private MachineEditorScreen editor;
    private RuleData originalRule;
    private String queryResult;

    @Override public void onInitializeClient() {
        QaTrace.log("client.init", Map.of("role", role, "seed", seed, "carpet", carpet));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            disconnectEvents++;
            QaTrace.log("transport.disconnect-event", Map.of("count", disconnectEvents));
        });
        ClientPlayNetworking.registerGlobalReceiver(QaProtocol.Response.TYPE, (packet, context) -> {
            JsonObject reply = JsonParser.parseString(packet.json()).getAsJsonObject();
            QaTrace.log("client.rpc.receive", Map.of("id", packet.id(), "reply", reply));
            if (reply.has("data") && reply.get("data").isJsonObject()) snapshot = reply.getAsJsonObject("data");
            CompletableFuture<JsonObject> waiting = requests.remove(packet.id());
            if (waiting != null) waiting.complete(reply);
        });
        commonConnection();
        if (actorA) actorA(); else actorB();
        step("observability/full-packet-trace-available", () -> {
            require(QaTrace.failure().isEmpty(), "structured event log has no write failure");
            require(QaTrace.count("tcp:send") > 0 && QaTrace.count("tcp:receive") > 0, "test instrumentation observed bidirectional real TCP mod payloads");
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finishing) { if (++stopTicks >= 30) mc.stop(); return; }
            try {
                observe();
                if (System.nanoTime() - stageStarted > 120_000_000_000L)
                    throw new AssertionError("Stage timeout: " + currentName() + "; screen=" + mc.gui.screen() + "; snapshot=" + snapshot);
                if (snapshot.has("peerFailures") && snapshot.getAsJsonArray("peerFailures").size() > 0)
                    throw new AssertionError("Another actor failed: " + snapshot.get("peerFailures"));
                if (mc.gui.overlay() != null) return;
                ticks++;
                Stage stage = stages.get(index);
                if (!entered) { entered = true; QaTrace.log("stage.start", Map.of("index", index, "name", stage.name)); stage.enter.run(); }
                if (stage.done.test()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("group", stage.name.split("/", 2)[0]); result.put("name", stage.name); result.put("status", "PASS");
                    result.put("milliseconds", (System.nanoTime() - stageStarted) / 1_000_000L);
                    cases.add(result); QaTrace.log("stage.pass", result);
                    System.out.println("SYSTEM_QA_PASS " + role + " " + index + " " + stage.name);
                    if (++index == stages.size()) { finish(mc, true, null); return; }
                    entered = false; ticks = 0; stageStarted = System.nanoTime();
                }
            } catch (Throwable failure) { failure.printStackTrace(); finish(mc, false, failure); }
        });
    }

    private void commonConnection() {
        step("transport/title-ready", () -> {}, () -> mc().gui.screen() instanceof TitleScreen && ticks >= 20);
        step("transport/connect-real-tcp", () -> { mc().options.pauseOnLostFocus = false; mc().options.guiScale().set(2); connect(); },
            () -> mc().player != null && mc().getConnection() != null && mc().gui.screen() == null && ClientPlayNetworking.canSend(QaProtocol.Request.TYPE));
        step("transport/fabric-handshake", () -> MachineNetworkManager.sendRequestSync(), () -> {
            if (!MachineNetworkManager.isServerSupported() || MachineNetworkManager.canConfig() != actorA) return false;
            require(mc().getSingleplayerServer() == null, "client is connected to a distinct dedicated server process");
            require(ClientPlayNetworking.canSend(MachinePayloads.ActionPayload.TYPE), "machine Fabric payload negotiated");
            require(mc().player.getGameProfile().name().equals(role), "offline identity matches assigned actor");
            require(MachineNetworkManager.canConfig() == actorA, "initial OP/non-OP privilege matches server");
            return true;
        });
        checkpoint("joined");
        barrier("transport/both-actors-joined", "ActorA.joined", "ActorB.joined");
    }

    private void actorA() {
        rpcStage("fixture/detectors-and-offline-profiles", "fixture");
        step("gui/add-screen-invalid-input", () -> {
            panel = new CommandGUIScreen(); mc().gui.setScreen(panel);
            editor = new MachineEditorScreen(panel, null); mc().gui.setScreen(editor);
            clickKey("screen.command-gui.save");
            require(mc().gui.screen() == editor, "empty GUI form stays open");
            require(!((String) field(editor, "errorMessage")).isBlank(), "invalid GUI form shows a validation error");
        });
        step("gui/populate-machine-form", () -> {
            setText(editor, "nameField", NAME); setText(editor, "descriptionField", "真实TCP $ 中文 🚦");
            setText(editor, "botsField", "SystemBot, SystemMode"); setText(editor, "categoryField", "System QA");
            setText(editor, "intervalField", "1"); setText(editor, "permissionField", "2");
            MachineModels.MachineData draft = editor.getMachine();
            draft.onTimeline = timeline(0, "player {bot} spawn at 6 81 6", "setblock 0 80 0 minecraft:lever[face=floor,powered=true]");
            draft.offTimeline = timeline(0, "setblock 0 80 0 minecraft:lever[face=floor,powered=false]");
            draft.detection = detector(0);
            MachineModels.ModeData mode = new MachineModels.ModeData(); mode.id = "system-mode"; mode.name = "System Mode";
            mode.onTimeline = timeline(1, "player {bot} spawn at 8 81 8", "setblock 2 80 0 minecraft:lever[face=floor,powered=true]");
            mode.offTimeline = timeline(1, "setblock 2 80 0 minecraft:lever[face=floor,powered=false]"); mode.detection = detector(2);
            draft.modes.add(mode);
            QaTrace.log("gui.form.fixture", draft);
        }, () -> ticks >= 5);
        step("gui/save-machine-form", () -> {
            screenshot("machine-add"); clickKey("screen.command-gui.save");
            require(mc().gui.screen() == panel, "valid GUI save returns to parent");
        }, () -> machine() != null);
        step("network/add-acknowledged", () -> {
            require(machine().description.equals("真实TCP $ 中文 🚦"), "Unicode and special text survived real network and storage");
            require("off".equals(machine().detected), "server initialized real lever detector off");
            revision = machine().revision;
        });
        checkpoint("created");
        barrier("permissions/nonop-attempts-complete", "ActorB.unauthorized");
        rpcStage("permissions/ordered-server-fence", "snapshot");
        step("permissions/unauthorized-did-not-mutate", () -> {
            require(machine().revision == revision, "non-OP requests cannot modify revision");
            require(machine().name.equals(NAME) && "off".equals(machine().detected), "non-OP edit and toggle were rejected");
            require(MachineNetworkManager.getMachine("unauthorized-machine") == null, "non-OP add was rejected");
            require(serverMachine(ID).get("revision").getAsLong() == revision && serverMachine(ID).get("name").getAsString().equals(NAME), "independent server snapshot confirms unauthorized edit did not commit");
            require(serverMachine("unauthorized-machine") == null, "independent server snapshot confirms unauthorized add did not commit");
            command("machineadmin add ActorB"); MachineNetworkManager.sendEditSession(ID, true);
        }, () -> "ActorA".equals(machine().editingBy));
        checkpoint("locked");
        barrier("concurrency/peer-lock-conflict", "ActorB.conflict");
        rpcStage("concurrency/lock-fence", "snapshot");
        step("concurrency/lock-owner-preserved", () -> {
            require("ActorA".equals(machine().editingBy), "second player cannot steal another editor's lock");
            require(machine().revision == revision, "competing editor cannot save through lock");
            editor = new MachineEditorScreen(panel, machine()); mc().gui.setScreen(editor);
            setText(editor, "descriptionField", "GUI edited over TCP 中文"); clickKey("screen.command-gui.save");
        }, () -> machine().revision > revision);
        step("gui/edit-save-acknowledged", () -> {
            require(machine().description.equals("GUI edited over TCP 中文"), "GUI edit text persisted");
            require(machine().editingBy.isEmpty(), "successful save released editing lock"); revision = machine().revision;
            MachineNetworkManager.sendEditSession(ID, true);
        }, () -> "ActorA".equals(machine().editingBy));
        step("boundary/boolean-type-confusion-request", () -> ClientPlayNetworking.send(new MachinePayloads.ActionPayload("{\"type\":\"editSession\",\"machineId\":\"" + ID + "\",\"open\":\"false\"}")));
        rpcStage("boundary/boolean-type-confusion-fence", "snapshot");
        step("boundary/string-false-cannot-release-lock", () -> require("ActorA".equals(machine().editingBy), "string-valued false is rejected instead of releasing a real editing lock"));
        step("concurrency/stale-revision-request", () -> {
            var stale = copy(machine()); stale.name = "STALE MUST NOT SAVE";
            MachineNetworkManager.sendEdit(stale, revision - 1); MachineNetworkManager.sendEditSession(ID, false);
        });
        rpcStage("concurrency/stale-request-fence", "snapshot");
        step("concurrency/stale-revision-rejected", () -> {
            require(machine().revision == revision && NAME.equals(machine().name), "stale revision cannot overwrite committed GUI edit");
            require(serverMachine(ID).get("revision").getAsLong() == revision && serverMachine(ID).get("name").getAsString().equals(NAME), "independent server state confirms stale edit rejection");
        });
        checkpoint("released");
        barrier("permissions/whitelisted-editor-saved", "ActorB.editor-saved");
        step("permissions/nonop-policy-fields-preserved", () -> {
            require(machine().permissionLevel == 2, "editor whitelist cannot reduce machine permission level");
            require(machine().bannedPlayers.isEmpty(), "editor whitelist cannot alter banned players");
            require(machine().description.equals("ActorB allowed editor"), "whitelisted editor can save allowed content");
        });
        barrier("lifecycle/peer-disconnect-reconnect", "ActorB.reconnected");
        step("lifecycle/peer-lock-cleared", () -> require(machine().editingBy.isEmpty(), "TCP disconnect released peer edit lock"));
        fuzzStages();
        detectionStages();
        machineStages();
        ruleStages();
        step("permissions/revoke-editor-command", () -> command("machineadmin remove ActorB"));
        checkpoint("revoked");
        barrier("permissions/revocation-observed", "ActorB.revoked");
        step("lifecycle/queue-work-and-hold-lock", () -> {
            mc().gui.setScreen(null); MachineNetworkManager.sendEditSession(ID, true);
            TimedTaskManager.addSpawnTask("SystemLate", 1, 0, 0);
            ChainedCommandExecutor.executeMulti(null, List.of("say SYSTEM_QA now", "say SYSTEM_QA MUST_NOT_LEAK"), 12000);
            require(TimedTaskManager.getTask("SystemLate") != null, "delayed task queued before disconnect");
        }, () -> "ActorA".equals(machine().editingBy));
        disconnectReconnectStages("owner");
        step("lifecycle/reconnected-server-persistence", () -> {
            require(machine() != null && machine().editingBy.isEmpty(), "saved machine survives TCP reconnect and its old lock is released");
            require(!machine().running, "completed runtime did not restart on reconnect");
            MachineNetworkManager.sendDelete(ID);
        }, () -> machine() == null);
        checkpoint("deleted");
        barrier("network/delete-replicated-to-peer", "ActorB.delete-observed");
    }

    private void actorB() {
        barrier("permissions/wait-for-created-machine", "ActorA.created");
        step("permissions/ordinary-player-raw-attempts", () -> {
            require(!MachineNetworkManager.canEdit() && !MachineNetworkManager.canConfig(), "ordinary player has no editor/config privilege");
            require(!CarpetRuleClient.canView(), "ordinary player cannot view privileged Carpet rule GUI");
            require(machine() != null, "ordinary player received machine snapshot"); revision = machine().revision;
            var illegal = copy(machine()); illegal.id = "unauthorized-machine"; MachineNetworkManager.sendAdd(illegal);
            illegal.id = ID; illegal.name = "UNAUTHORIZED";
            MachineNetworkManager.sendEditSession(ID, true); MachineNetworkManager.sendEdit(illegal, revision);
            MachineNetworkManager.sendDelete(ID); MachineNetworkManager.sendToggle(ID);
            MachineNetworkManager.sendSetModes(ID, List.of("system-mode"));
            ClientPlayNetworking.send(new RulePayloads.Query(999001));
            ClientPlayNetworking.send(new RulePayloads.Apply(999002, "[]"));
        });
        rpcStage("permissions/ordinary-player-fence", "snapshot");
        step("permissions/ordinary-player-no-change", () -> {
            require(machine() != null && machine().revision == revision && machine().name.equals(NAME), "unauthorized wire requests leave machine unchanged");
            require("off".equals(machine().detected), "unauthorized toggle did not execute");
            require(serverMachine(ID).get("detected").getAsString().equals("off") && serverMachine(ID).get("revision").getAsLong() == revision, "server world and configuration independently confirm permission rejection");
        });
        checkpoint("unauthorized");
        barrier("concurrency/owner-acquired-lock", "ActorA.locked");
        step("concurrency/whitelisted-peer-competes", () -> {
            MachineNetworkManager.sendRequestSync();
        }, () -> MachineNetworkManager.canEdit());
        step("concurrency/second-editor-lock-attempt", () -> {
            require(!MachineNetworkManager.canConfig(), "whitelist grants editing but no config privilege");
            require("ActorA".equals(machine().editingBy), "peer sees owner editing lock");
            var edit = copy(machine()); edit.name = "CONFLICT MUST NOT SAVE";
            MachineNetworkManager.sendEditSession(ID, true); MachineNetworkManager.sendEdit(edit, machine().revision);
            MachineNetworkManager.sendEditSession(ID, false);
        });
        rpcStage("concurrency/second-editor-fence", "snapshot");
        step("concurrency/conflict-rejected", () -> {
            require("ActorA".equals(machine().editingBy) && NAME.equals(machine().name), "second editor neither stole nor released owner's lock");
        });
        checkpoint("conflict");
        barrier("permissions/owner-released-lock", "ActorA.released");
        step("permissions/whitelisted-lock-acquired", () -> MachineNetworkManager.sendEditSession(ID, true), () -> "ActorB".equals(machine().editingBy));
        step("permissions/whitelisted-edit-escalation-attempt", () -> {
            revision = machine().revision; var edit = copy(machine()); edit.description = "ActorB allowed editor";
            edit.permissionLevel = 0; edit.bannedPlayers.add("ActorA"); MachineNetworkManager.sendEdit(edit, revision);
        }, () -> machine().revision > revision);
        step("permissions/whitelisted-policy-protected", () -> {
            require(machine().permissionLevel == 2 && machine().bannedPlayers.isEmpty(), "server preserves policy fields against non-OP editor payload");
            require(machine().description.equals("ActorB allowed editor"), "whitelisted content edit succeeds");
        });
        checkpoint("editor-saved");
        step("lifecycle/peer-lock-before-disconnect", () -> MachineNetworkManager.sendEditSession(ID, true), () -> "ActorB".equals(machine().editingBy));
        disconnectReconnectStages("peer");
        step("lifecycle/peer-lock-released-on-disconnect", () -> {
            require(machine().editingBy.isEmpty(), "own abandoned lock is gone after reconnect");
            require(MachineNetworkManager.canEdit() && !MachineNetworkManager.canConfig(), "editor whitelist survives reconnect");
        });
        checkpoint("reconnected");
        barrier("permissions/wait-for-editor-revocation", "ActorA.revoked");
        step("permissions/revocation-reflected-in-client", () -> MachineNetworkManager.sendRequestSync(), () -> {
            if (MachineNetworkManager.canEdit()) return false;
            require(!MachineNetworkManager.canConfig() && !CarpetRuleClient.canView(), "revoked editor has no remaining privileged controls");
            return true;
        });
        checkpoint("revoked");
        barrier("network/wait-for-owner-deletion", "ActorA.deleted");
        step("network/deletion-replicated", () -> require(machine() == null, "machine deletion replicated to second TCP client"));
        checkpoint("delete-observed");
    }

    private void fuzzStages() {
        step("boundary/seeded-malformed-payload-corpus", () -> {
            revision = machine().revision;
            List<String> corpus = new ArrayList<>(List.of("{broken", "null", "[]", "42", "{}", "{\"type\":{}}", "{\"type\":\"add\",\"machine\":null}",
                "{\"type\":\"setModes\",\"machineId\":\"missing\",\"modeIds\":[null,7]}",
                "{\"type\":\"queryBlock\",\"dimension\":\"bad dimension !\"}", "{\"type\":\"edit\",\"machine\":{\"id\":null}}"));
            Random random = new Random(seed);
            for (int i = 0; i < 8; i++) corpus.add("{\"type\":\"unknown-" + random.nextInt() + "\",\"machineId\":\"missing\"}");
            for (String json : corpus) { QaTrace.log("boundary.input", Map.of("json", json)); ClientPlayNetworking.send(new MachinePayloads.ActionPayload(json)); }
            MachineNetworkManager.sendToggle("missing-machine"); MachineNetworkManager.sendDelete("missing-machine");
            MachineNetworkManager.sendEditSession("missing-machine", true); MachineNetworkManager.sendSetModes(ID, List.of("missing-mode"));
            MachineNetworkManager.sendRequestSync();
        });
        rpcStage("boundary/malformed-payload-fence", "snapshot");
        step("boundary/connection-and-state-preserved", () -> {
            require(mc().player != null && mc().getConnection() != null, "malformed payloads do not disconnect client");
            require(machine().revision == revision && NAME.equals(machine().name), "malformed and unknown actions preserve valid machine");
            require(serverMachine(ID).get("revision").getAsLong() == revision && NAME.equals(serverMachine(ID).get("name").getAsString()), "server storage remains unchanged after malformed corpus");
        });
    }

    private void detectionStages() {
        step("detection/query-existing-block", () -> {
            queryResult = null; MachineNetworkManager.setBlockQueryCallback(json -> queryResult = json);
            MachineNetworkManager.sendBlockQuery("minecraft:overworld", 0, 80, 0, 7001L);
        }, () -> queryResult != null);
        step("detection/existing-block-result", () -> {
            var result = JsonParser.parseString(queryResult).getAsJsonObject();
            require(result.get("token").getAsLong() == 7001L, "block query preserves correlation token");
            require(result.get("found").getAsBoolean() && result.get("blockId").getAsString().equals("minecraft:lever"), "block query reads real world lever");
            require(result.getAsJsonObject("current").get("powered").getAsString().equals("false"), "block query reads live block property");
        });
        step("detection/query-absent-dimension", () -> {
            queryResult = null; MachineNetworkManager.setBlockQueryCallback(json -> queryResult = json);
            MachineNetworkManager.sendBlockQuery("system_qa:missing", Integer.MAX_VALUE, -9999, Integer.MIN_VALUE, 7002L);
        }, () -> queryResult != null);
        step("detection/absent-dimension-safe-result", () -> require(!JsonParser.parseString(queryResult).getAsJsonObject().get("found").getAsBoolean(), "nonexistent dimension and extreme coordinates return not found"));
    }

    private void machineStages() {
        step("gui/machine-tab-open", () -> {
            panel = new CommandGUIScreen(); mc().gui.setScreen(panel);
            TabNavigationBar tabs = (TabNavigationBar) field(panel, "tabNavigationBar");
            int selected = -1; for (int i = 0; i < tabs.getTabs().size(); i++) if (tabs.getTabs().get(i) instanceof MachineSwitchTab) selected = i;
            require(selected >= 0, "machine tab present"); tabs.selectTab(selected, false); panel.tick();
        }, () -> ticks >= 5);
        step("gui/machine-select-before-confirmation", () -> {
            clickContains(NAME); require("off".equals(machine().detected), "selecting GUI machine does not execute before confirmation");
        }, () -> ticks >= 5);
        step("gui/machine-toggle-confirmation", () -> {
            screenshot("machine-selected"); clickContains("确认执行（1）");
        }, () -> ticks >= 20 && !machine().running && (!carpet || "on".equals(machine().detected)));
        step("scheduler/real-boot-or-optional-dependency", () -> {
            require((carpet ? "on" : "off").equals(machine().detected), "boot result matches actual Carpet availability");
            require(!machine().running, "boot scheduler returns to idle"); MachineNetworkManager.sendRequestFakeStates();
        }, () -> ticks >= 5 && (!carpet || MachineNetworkManager.isServerFakePlayer("SystemBot")));
        step("fake-player/actual-server-roundtrip", () -> {
            require(MachineNetworkManager.isFakePlayerStatesSupported() == carpet, "fake player capability matches actual server mod");
            if (carpet) require(MachineNetworkManager.isServerFakePlayer("SystemBot"), "actual spawned fake player is in server state");
            mc().gui.setScreen(new MachineModesScreen(panel, machine()));
        }, () -> ticks >= 10);
        step("gui/mode-select-before-confirmation", () -> clickContains("System Mode"), () -> ticks >= 5);
        step("gui/mode-toggle-confirmation", () -> {
            screenshot("mode-selected"); clickKey("screen.command-gui.machine.confirm_modes");
        }, () -> ticks >= 20 && !mode().processing && (!carpet || "on".equals(mode().detected)));
        step("scheduler/mode-start-observed", () -> {
            require((carpet ? "on" : "off").equals(mode().detected), "mode uses actual world detector and reports optional failure");
            if (carpet) require(mode().running, "completed ON mode remains active");
            MachineNetworkManager.sendSetModes(ID, List.of("missing-mode"));
        });
        rpcStage("boundary/unknown-mode-fence", "snapshot");
        step("boundary/unknown-mode-preserves-live-state", () -> {
            require((carpet ? "on" : "off").equals(mode().detected), "unknown mode request cannot change an existing mode's live detector");
            require(serverMachine(ID).getAsJsonObject("modeStates").getAsJsonObject("system-mode").get("detected").getAsString().equals(carpet ? "on" : "off"), "independent server detector confirms unknown mode is a no-op");
            if (carpet) { require(mode().running, "unknown mode leaves active mode running"); MachineNetworkManager.sendSetModes(ID, List.of("system-mode")); }
        }, () -> ticks >= 20 && !mode().processing && "off".equals(mode().detected));
        step("scheduler/mode-stop-observed", () -> {
            require(!mode().running, "OFF mode no longer active"); if (carpet) MachineNetworkManager.sendToggle(ID);
        }, () -> ticks >= 20 && !machine().running && "off".equals(machine().detected));
        step("scheduler/machine-shutdown-observed", () -> require(!machine().running && "off".equals(machine().detected), "machine ends idle with lever off"));
    }

    private void ruleStages() {
        step("rules/query-real-server", () -> CarpetRuleClient.refresh(), () -> ticks >= 5 && !CarpetRuleClient.busy());
        step("rules/query-capability-result", () -> {
            if (carpet) {
                originalRule = CarpetRuleClient.rules().stream().filter(r -> r.name().equals("flippinCactus")).findFirst().orElseThrow();
                require(!CarpetRuleClient.rules().isEmpty(), "real Carpet catalogue received");
                CarpetRuleClient.stage(originalRule, "set", originalRule.value().equals("true") ? "false" : "true");
                mc().gui.setScreen(new CarpetRuleConfirmScreen(panel));
            } else require(CarpetRuleClient.status().contains("未安装 Carpet"), "missing Carpet explicitly reported");
        }, () -> !carpet || ticks >= 5);
        step("rules/gui-confirm-batch", () -> {
            if (carpet) { screenshot("rules-confirm"); clickContains("确认执行 1 项"); }
        }, () -> !carpet || (!CarpetRuleClient.busy() && CarpetRuleClient.pending.isEmpty() && rule() != null && !rule().value().equals(originalRule.value())));
        step("rules/apply-and-refresh-acknowledged", () -> {
            if (carpet) { require(!rule().value().equals(originalRule.value()), "GUI-confirmed rule change reached dedicated server"); CarpetRuleClient.stage(rule(), "set", originalRule.value()); CarpetRuleClient.apply(); }
        }, () -> !carpet || (!CarpetRuleClient.busy() && CarpetRuleClient.pending.isEmpty() && rule().value().equals(originalRule.value())));
        step("rules/original-rule-restored", () -> { if (carpet) require(rule().value().equals(originalRule.value()), "test restored original Carpet rule value"); });
    }

    private void disconnectReconnectStages(String tag) {
        step("lifecycle/" + tag + "-disconnect", () -> {
            disconnectBaseline = disconnectEvents;
            // This is the normal Quit Game path: close TCP before removing the client world.
            // Minecraft.disconnect(Screen, boolean) alone only tears down the world on dedicated connections.
            mc().disconnectFromWorld(Component.literal("System QA controlled reconnect"));
            mc().gui.setScreen(new TitleScreen());
        }, () -> mc().player == null && mc().gui.screen() instanceof TitleScreen && disconnectEvents > disconnectBaseline);
        step("lifecycle/" + tag + "-client-state-reset", () -> {
            require(!MachineNetworkManager.isServerSupported() && MachineNetworkManager.getMachines().isEmpty(), "disconnect resets cached server support and machines");
            require(!MachineNetworkManager.canEdit() && !MachineNetworkManager.canConfig(), "disconnect resets cached permissions");
            require(TimedTaskManager.getAllTasks().isEmpty(), "disconnect clears scheduled fake player tasks");
            require(((List<?>) staticField(ChainedCommandExecutor.class, "delayedQueue")).isEmpty(), "disconnect clears delayed command chains");
            require(CarpetRuleClient.rules().isEmpty() && CarpetRuleClient.pending.isEmpty() && !CarpetRuleClient.busy(), "disconnect resets rule catalogue and pending requests");
            snapshot = new JsonObject();
        }, () -> ticks >= 10);
        step("lifecycle/" + tag + "-reconnect-real-tcp", this::connect, () -> mc().player != null && mc().gui.screen() == null && ClientPlayNetworking.canSend(QaProtocol.Request.TYPE));
        step("lifecycle/" + tag + "-fresh-snapshot", () -> MachineNetworkManager.sendRequestSync(), () -> MachineNetworkManager.isServerSupported() && machine() != null);
    }

    private RuleData rule() { return CarpetRuleClient.rules().stream().filter(r -> r.key().equals(originalRule.key())).findFirst().orElse(null); }
    private JsonObject serverMachine(String id) {
        if (!snapshot.has("machines")) return null;
        for (JsonElement value : snapshot.getAsJsonArray("machines")) {
            JsonObject machine = value.getAsJsonObject(); if (machine.has("id") && machine.get("id").getAsString().equals(id)) return machine;
        }
        return null;
    }
    private static MachineModels.MachineData machine() { return MachineNetworkManager.getMachine(ID); }
    private static MachineModels.ModeData mode() { return machine().modes.stream().filter(m -> m.id.equals("system-mode")).findFirst().orElseThrow(); }
    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static MachineModels.MachineData copy(MachineModels.MachineData source) { return GSON.fromJson(GSON.toJson(source), MachineModels.MachineData.class); }
    private static MachineModels.Timeline timeline(int bot, String... commands) {
        var timeline = new MachineModels.Timeline(); var step = new MachineModels.Step(); step.bot = bot; step.commandDelay = 1; step.commands.addAll(List.of(commands)); timeline.steps.add(step); return timeline;
    }
    private static MachineModels.DetectionData detector(int x) {
        var data = new MachineModels.DetectionData(); data.enabled = true; data.x = x; data.y = 80; data.blockId = "minecraft:lever"; data.property = "powered";
        data.onValues.add("powered=true"); data.offValues.add("powered=false"); data.ignoreValues.addAll(List.of("face=floor", "facing=north")); return data;
    }
    private void connect() {
        String host = System.getProperty("system.host", "127.0.0.1"); int port = Integer.parseInt(System.getProperty("system.port"));
        QaTrace.log("transport.connect", Map.of("host", host, "port", port, "role", role));
        ConnectScreen.startConnecting(new TitleScreen(), mc(), new ServerAddress(host, port), new ServerData("Command GUI isolated QA", host + ":" + port, ServerData.Type.OTHER), false, null);
    }
    private void command(String command) { QaTrace.log("client.command", Map.of("command", command)); mc().getConnection().sendCommand(command); }
    private void rpcStage(String name, String operation) { step(name, () -> rpc(operation, Map.of()), this::rpcDone); }
    private void checkpoint(String name) { step("coordination/checkpoint-" + name, () -> rpc("checkpoint", Map.of("name", name)), this::rpcDone); }
    private void barrier(String name, String... flags) {
        step(name, () -> rpc("snapshot", Map.of()), () -> {
            if (lastRpc != null && lastRpc.isDone()) rpcDone();
            JsonObject checkpoints = snapshot.has("checkpoints") ? snapshot.getAsJsonObject("checkpoints") : new JsonObject();
            if (Arrays.stream(flags).allMatch(checkpoints::has)) return true;
            if ((lastRpc == null || lastRpc.isDone()) && System.nanoTime() - lastPoll > 500_000_000L) rpc("snapshot", Map.of());
            return false;
        });
    }
    private void rpc(String operation, Map<String, Object> arguments) {
        JsonObject request = GSON.toJsonTree(arguments).getAsJsonObject(); request.addProperty("op", operation);
        int id = ++requestId; lastRpc = new CompletableFuture<>(); requests.put(id, lastRpc); lastPoll = System.nanoTime();
        QaTrace.log("client.rpc.send", Map.of("id", id, "request", request)); ClientPlayNetworking.send(new QaProtocol.Request(id, request.toString()));
    }
    private boolean rpcDone() {
        if (!lastRpc.isDone()) return false;
        JsonObject response = lastRpc.join();
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) throw new AssertionError("QA RPC failed: " + response);
        return true;
    }
    private void step(String name, CheckedRun enter) { step(name, enter, () -> true); }
    private void step(String name, CheckedRun enter, CheckedTest done) { stages.add(new Stage(name, enter, done)); }
    private String currentName() { return index < stages.size() ? stages.get(index).name : "complete"; }
    private void observe() {
        if (observedSync != MachineNetworkManager.getSyncVersion()) {
            observedSync = MachineNetworkManager.getSyncVersion(); QaTrace.log("client.machine.snapshot", Map.of("version", observedSync, "canEdit", MachineNetworkManager.canEdit(), "canConfig", MachineNetworkManager.canConfig(), "machines", MachineNetworkManager.getMachines()));
        }
        if (observedFake != MachineNetworkManager.getFakeStatesVersion()) {
            observedFake = MachineNetworkManager.getFakeStatesVersion(); QaTrace.log("client.fake.snapshot", Map.of("version", observedFake, "supported", MachineNetworkManager.isFakePlayerStatesSupported(), "players", MachineNetworkManager.getServerFakePlayers()));
        }
        if (observedRule != CarpetRuleClient.version()) {
            observedRule = CarpetRuleClient.version(); QaTrace.log("client.rules.snapshot", Map.of("version", observedRule, "status", CarpetRuleClient.status(), "busy", CarpetRuleClient.busy(), "rules", CarpetRuleClient.rules(), "pending", CarpetRuleClient.pending));
        }
    }
    private void require(boolean condition, String message) {
        assertions++; QaTrace.log("assertion", Map.of("passed", condition, "message", message, "stage", currentName()));
        if (!condition) throw new AssertionError(message);
    }
    private void clickKey(String key) throws Exception { clickContains(Component.translatable(key).getString()); }
    private void clickContains(String text) throws Exception {
        var screen = mc().gui.screen();
        Button button = screen.children().stream().filter(w -> w instanceof Button).map(w -> (Button) w)
            .filter(w -> w.active && w.visible && w.getMessage().getString().contains(text)).findFirst()
            .orElseThrow(() -> new AssertionError("No active button '" + text + "' in " + screen + ": " + screen.children().stream().filter(w -> w instanceof Button).map(w -> ((Button) w).getMessage().getString()).toList()));
        var event = new net.minecraft.client.input.MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(0, 0));
        QaTrace.log("gui.click", Map.of("screen", screen.getClass().getName(), "text", button.getMessage().getString(), "x", event.x(), "y", event.y()));
        require(screen.mouseClicked(event, false), "screen consumed real click: " + text); screen.mouseReleased(event);
    }
    private static void setText(Object screen, String name, String text) throws Exception { ((EditBox) field(screen, name)).setValue(text); QaTrace.log("gui.edit", Map.of("field", name, "value", text)); }
    private static Object field(Object instance, String name) throws Exception { Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
    private static Object staticField(Class<?> owner, String name) throws Exception { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private void screenshot(String name) {
        String file = String.format(Locale.ROOT, "%02d-%s-%s.png", index, role, name);
        Screenshot.grab(mc().gameDirectory, file, mc().gameRenderer.mainRenderTarget(), 1, ignored -> {});
        QaTrace.log("screenshot", Map.of("path", mc().gameDirectory.toPath().resolve("screenshots").resolve(file).toString()));
    }
    private void finish(Minecraft mc, boolean passed, Throwable failure) {
        if (finishing) return; finishing = true; complete = passed;
        if (failure != null) {
            Map<String, Object> failed = new LinkedHashMap<>(); failed.put("group", currentName().split("/", 2)[0]); failed.put("name", currentName()); failed.put("status", "FAIL");
            failed.put("milliseconds", (System.nanoTime() - stageStarted) / 1_000_000L); failed.put("error", failure.toString()); cases.add(failed);
            QaTrace.log("stage.fail", failed); try { screenshot("FAILURE"); } catch (Throwable ignored) { }
        }
        Map<String, Object> report = new LinkedHashMap<>(); report.put("role", role); report.put("passed", passed); report.put("complete", complete);
        report.put("carpet", carpet); report.put("seed", seed); report.put("assertions", assertions); report.put("cases", cases); report.put("lastServerSnapshot", snapshot);
        report.put("packetCounts", QaTrace.counts()); report.put("traceFailure", QaTrace.failure());
        report.put("coverageNote", "Real TCP and Fabric handlers; GUI widgets receive click events. Timeline/detector fixture content is assigned through the editor model before GUI save. Packet trace is test-only instrumentation.");
        try {
            Path output = Path.of(System.getProperty("system.outputRoot", mc.gameDirectory.getAbsolutePath())); Files.createDirectories(output); Files.writeString(output.resolve("report.json"), GSON.toJson(report));
            if (mc.player != null && ClientPlayNetworking.canSend(QaProtocol.Request.TYPE)) rpc("finish", Map.of("passed", passed, "assertions", assertions, "cases", cases));
        } catch (Throwable error) { error.printStackTrace(); }
        System.out.println(passed ? "SYSTEM_QA_CLIENT_COMPLETE " + role : "SYSTEM_QA_CLIENT_FAILED " + role);
    }
    private record Stage(String name, CheckedRun enter, CheckedTest done) { }
    @FunctionalInterface private interface CheckedRun { void run() throws Exception; }
    @FunctionalInterface private interface CheckedTest { boolean test() throws Exception; }
}

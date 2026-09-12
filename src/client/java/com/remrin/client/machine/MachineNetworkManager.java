package com.remrin.client.machine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.remrin.server.net.MachinePayloads;

public final class MachineNetworkManager {
   private static final Gson GSON = new GsonBuilder().create();
   private static final List<MachineModels.MachineData> machines = new ArrayList<>();
   private static boolean serverSupported = false;
   private static boolean canEdit = false;
   private static boolean canConfig = false;
   private static final Map<String, JsonObject> fakePlayerStates = new HashMap<>();
   private static boolean fakePlayerStatesSupported = false;
   private static int fakeStatesVersion = 0;
   private static int syncVersion = 0;
   private static int structureVersion = 0;
   private static String structureSignature = "";
   private static Consumer<String> blockQueryCallback = null;
   private static final Map<String, MachineNetworkManager.PendingMachineEdit> pendingMachineEdits = new HashMap<>();

   private MachineNetworkManager() {
   }

   public static void init() {
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.SyncPayload.TYPE, MachinePayloads.SyncPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.serverboundPlay().register(MachinePayloads.ActionPayload.TYPE, MachinePayloads.ActionPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.BlockQueryResultPayload.TYPE, MachinePayloads.BlockQueryResultPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.FakePlayerStatesPayload.TYPE, MachinePayloads.FakePlayerStatesPayload.CODEC));
      ClientPlayNetworking.registerGlobalReceiver(MachinePayloads.SyncPayload.TYPE, (payload, context) -> applySync(payload.json()));
      ClientPlayNetworking.registerGlobalReceiver(MachinePayloads.BlockQueryResultPayload.TYPE, (payload, context) -> {
         Consumer<String> callback = blockQueryCallback;
         blockQueryCallback = null;
         if (callback != null) {
            callback.accept(payload.json());
         }
      });
      ClientPlayNetworking.registerGlobalReceiver(
         MachinePayloads.FakePlayerStatesPayload.TYPE, (payload, context) -> applyFakePlayerStates(payload.json())
      );
      ClientPlayConnectionEvents.JOIN.register((Join)(listener, sender, client) -> {
         MachineDebug.log("[Net] JOIN fired -> reset");
         reset();
      });
      ClientPlayConnectionEvents.DISCONNECT.register((Disconnect)(listener, client) -> {
         MachineDebug.log("[Net] DISCONNECT fired -> reset");
         reset();
         blockQueryCallback = null;
      });
   }

   private static void registerPayload(Runnable registration) {
      try {
         registration.run();
      } catch (IllegalArgumentException e) {
      }
   }

   public static void setBlockQueryCallback(Consumer<String> callback) {
      blockQueryCallback = callback;
   }

   public static void sendBlockQuery(String dimension, int x, int y, int z, long token) {
      JsonObject action = new JsonObject();
      action.addProperty("type", "queryBlock");
      action.addProperty("dimension", dimension);
      action.addProperty("x", x);
      action.addProperty("y", y);
      action.addProperty("z", z);
      action.addProperty("token", token);
      sendActionInternal(action.toString());
   }

   private static void applySync(String json) {
      try {
         MachineDebug.log("[Net] sync received, length=" + json.length());
         JsonObject root = JsonParser.parseString(json).getAsJsonObject();
         canEdit = root.has("canEdit") && root.get("canEdit").getAsBoolean();
         canConfig = root.has("canConfig") && root.get("canConfig").getAsBoolean();
         machines.clear();
         if (root.has("machines")) {
            for (JsonElement element : root.getAsJsonArray("machines")) {
               MachineModels.MachineData machine = (MachineModels.MachineData)GSON.fromJson(element, MachineModels.MachineData.class);
               if (machine != null) {
                  machines.add(machine);
               }
            }
         }

         serverSupported = true;
         syncVersion++;
         String signature = computeStructureSignature();
         if (!signature.equals(structureSignature)) {
            structureSignature = signature;
            structureVersion++;
         }
      } catch (Exception var6) {
         MachineDebug.log("[Net] sync FAILED: " + var6);
      }
   }

   private static String computeStructureSignature() {
      StringBuilder sb = new StringBuilder();

      for (MachineModels.MachineData machine : machines) {
         sb.append(machine.id)
            .append('|')
            .append(machine.name)
            .append('|')
            .append(machine.category == null ? "" : machine.category)
            .append('|')
            .append(machine.description)
            .append('|')
            .append(String.join(",", machine.bots))
            .append('|');
         if (machine.detection != null) {
            sb.append(machine.detection.enabled)
               .append(':')
               .append(machine.detection.blockId)
               .append(':')
               .append(machine.detection.property)
               .append(':')
               .append(String.join(",", machine.detection.onValues))
               .append('/')
               .append(String.join(",", machine.detection.offValues))
               .append(':')
               .append(machine.detection.x)
               .append(',')
               .append(machine.detection.y)
               .append(',')
               .append(machine.detection.z);
         }

         sb.append('|');
         if (machine.modes != null) {
            for (MachineModels.ModeData mode : machine.modes) {
               sb.append(mode.id)
                  .append(':')
                  .append(mode.name)
                  .append(':')
                  .append(stepCount(mode.onTimeline))
                  .append('/')
                  .append(stepCount(mode.offTimeline))
                  .append(';');
            }
         }

         sb.append('|').append(stepCount(machine.onTimeline)).append('/').append(stepCount(machine.offTimeline)).append('\n');
      }

      return sb.toString();
   }

   private static int stepCount(MachineModels.Timeline timeline) {
      if (timeline != null && timeline.steps != null) {
         return timeline.steps.size();
      }
      return 0;
   }

   private static void reset() {
      machines.clear();
      pendingMachineEdits.clear();
      serverSupported = false;
      canEdit = false;
      canConfig = false;
      fakePlayerStates.clear();
      fakePlayerStatesSupported = false;
      fakeStatesVersion++;
      syncVersion++;
      structureSignature = "";
      structureVersion++;
   }

   public static void markMachinePending(MachineModels.MachineData machine, int baseRevision) {
      markMachinePending(machine, baseRevision, false);
   }

   public static void markMachinePending(MachineModels.MachineData machine, int baseRevision, boolean modesDirty) {
      if (machine != null && machine.id != null && !machine.id.isEmpty()) {
         boolean isNew = true;

         for (int i = 0; i < machines.size(); i++) {
            if (machine.id.equals(machines.get(i).id)) {
               machines.set(i, machine);
               isNew = false;
               break;
            }
         }

         if (isNew) {
            machines.add(machine);
         }

         pendingMachineEdits.put(machine.id, new MachineNetworkManager.PendingMachineEdit(machine, baseRevision, isNew, modesDirty));
         MachineDebug.log("[Pending] mark id=" + machine.id + " isNew=" + isNew + " modesDirty=" + modesDirty + " pending=" + pendingMachineEdits.size());
         structureVersion++;
         notifyLocalChange();
      }
   }

   public static void notifyLocalChange() {
      syncVersion++;
      structureVersion++;
   }

   public static boolean isMachinePending(String machineId) {
      return pendingMachineEdits.containsKey(machineId);
   }

   public static MachineNetworkManager.PendingMachineEdit getPendingMachineEdit(String machineId) {
      return pendingMachineEdits.get(machineId);
   }

   public static boolean hasPendingMachines() {
      return !pendingMachineEdits.isEmpty();
   }

   public static void uploadPendingMachines() {
      if (!pendingMachineEdits.isEmpty()) {
         for (MachineNetworkManager.PendingMachineEdit edit : pendingMachineEdits.values()) {
            if (edit.isNew()) {
               sendAdd(edit.machine());
            } else {
               sendEdit(edit.machine(), edit.baseRevision());
            }
         }

         pendingMachineEdits.clear();
         notifyLocalChange();
      }
   }

   private static void applyFakePlayerStates(String json) {
      try {
         JsonObject root = JsonParser.parseString(json).getAsJsonObject();
         fakePlayerStatesSupported = root.has("supported") && root.get("supported").getAsBoolean();
         fakePlayerStates.clear();
         if (root.has("players")) {
            JsonObject players = root.getAsJsonObject("players");

            for (Entry<String, JsonElement> entry : players.entrySet()) {
               fakePlayerStates.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
         }

         fakeStatesVersion++;
      } catch (Exception var5) {
      }
   }

   public static void refreshLocalCarpetFakePlayers() {
      if (fakePlayerStatesSupported) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null || !mc.hasSingleplayerServer() || mc.getSingleplayerServer() == null) {
         return;
      }

      try {
         Class<?> fakePlayerClass = Class.forName("carpet.patches.EntityPlayerMPFake");
         fakePlayerStates.clear();
         for (ServerPlayer player : mc.getSingleplayerServer().getPlayerList().getPlayers()) {
            if (fakePlayerClass.isInstance(player)) {
               fakePlayerStates.put(player.getGameProfile().name(), new JsonObject());
            }
         }

         fakePlayerStatesSupported = true;
         fakeStatesVersion++;
      } catch (Exception var4) {
      }
   }

   public static void sendToggle(String machineId) {
      sendAction("toggle", machineId, null);
   }

   public static void sendRequestSync() {
      JsonObject action = new JsonObject();
      action.addProperty("type", "requestSync");
      sendActionInternal(action.toString());
   }

   public static void sendRequestFakeStates() {
      JsonObject action = new JsonObject();
      action.addProperty("type", "fakeStatesRequest");
      sendActionInternal(action.toString());
   }

   public static void sendUnsubscribeFakeStates() {
      JsonObject action = new JsonObject();
      action.addProperty("type", "fakeStatesUnsubscribe");
      sendActionInternal(action.toString());
   }

   public static void sendEditSession(String machineId, boolean open) {
      JsonObject action = new JsonObject();
      action.addProperty("type", "editSession");
      action.addProperty("machineId", machineId);
      action.addProperty("open", open);
      sendActionInternal(action.toString());
   }

   public static void sendSetModes(String machineId, List<String> targetIds) {
      JsonObject action = new JsonObject();
      action.addProperty("type", "setModes");
      action.addProperty("machineId", machineId);
      JsonArray array = new JsonArray();

      for (String modeId : targetIds) {
         array.add(modeId);
      }

      action.add("modeIds", array);
      sendActionInternal(action.toString());
   }

   public static void sendAdd(MachineModels.MachineData machine) {
      sendMachineAction("add", machine);
   }

   public static void sendEdit(MachineModels.MachineData machine, int baseRevision) {
      sendMachineAction("edit", machine, baseRevision);
   }

   public static void sendDelete(String machineId) {
      sendAction("delete", machineId, null);
   }

   public static void sendRefreshDetection(String machineId) {
      sendAction("refreshDetection", machineId, null);
   }

   public static void sendClearCategory(String categoryId) {
      JsonObject action = new JsonObject();
      action.addProperty("type", "clearCategory");
      action.addProperty("categoryId", categoryId);
      sendActionInternal(action.toString());
   }

   public static void sendRenameCategory(String oldName, String newName) {
      if (oldName != null && !oldName.isBlank() && newName != null && !newName.isBlank()) {
         for (MachineModels.MachineData machine : machines) {
            String category = machine.category == null ? "" : machine.category.trim();
            if (category.equals(oldName)) {
               MachineModels.MachineData copy = (MachineModels.MachineData)GSON.fromJson(GSON.toJsonTree(machine), MachineModels.MachineData.class);
               if (copy != null) {
                  copy.category = newName;
                  sendEditSession(machine.id, true);
                  sendEdit(copy, machine.revision);
                  sendEditSession(machine.id, false);
               }
            }
         }
      }
   }

   private static void sendMachineAction(String type, MachineModels.MachineData machine) {
      sendMachineAction(type, machine, -1);
   }

   private static void sendMachineAction(String type, MachineModels.MachineData machine, int baseRevision) {
      JsonObject action = new JsonObject();
      action.addProperty("type", type);
      action.add("machine", GSON.toJsonTree(machine));
      action.addProperty("baseRevision", baseRevision);
      sendActionInternal(action.toString());
   }

   private static void sendAction(String type, String machineId, MachineModels.MachineData machine) {
      JsonObject action = new JsonObject();
      action.addProperty("type", type);
      if (machineId != null) {
         action.addProperty("machineId", machineId);
      }

      if (machine != null) {
         action.add("machine", GSON.toJsonTree(machine));
      }

      sendActionInternal(action.toString());
   }

   private static void sendActionInternal(String json) {
      // FIX: 未进入游戏时（标题界面打开 GUI、或断线后残留的界面 tick）不能发包，
      // 否则 ClientPlayNetworking.send 抛 IllegalStateException: Cannot send packets when not in game!
      // 复现：标题界面 -> 模组菜单进入 GUI -> 切到「假人控制」标签 -> updateTabDependentWidgets
      //       调 sendRequestFakeStates() -> 崩溃（crash-2026-09-01_12.57.16-client.txt）
      if (Minecraft.getInstance().getConnection() == null) {
         return;
      }

      if (!ClientPlayNetworking.canSend(MachinePayloads.ActionPayload.TYPE)) {
         MachineDebug.log("[Net] drop action: server does not accept " + MachinePayloads.ActionPayload.TYPE.id());
         showLocalMessage("无法发送操作：当前服务器未启用 Command-GUI 服务端支持");
         return;
      }

      MachineDebug.log("[Net] send action (" + json.length() + " chars)");
      ClientPlayNetworking.send(new MachinePayloads.ActionPayload(json));
   }

   /** 客户端本地提示（不进服务端）：用于把“点了没反应”变成明确反馈。 */
   public static void showLocalMessage(String message) {
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.gui != null) {
         client.gui.hud.getChat().addClientSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
      }
   }

   public static boolean isServerSupported() {
      return serverSupported;
   }

   public static boolean canEdit() {
      return canEdit;
   }

   public static boolean canConfig() {
      return canConfig;
   }

   public static boolean isFakePlayerStatesSupported() {
      return fakePlayerStatesSupported;
   }

   public static int getFakeStatesVersion() {
      return fakeStatesVersion;
   }

   public static Set<String> getServerFakePlayers() {
      return Collections.unmodifiableSet(fakePlayerStates.keySet());
   }

   public static boolean isServerFakePlayer(String name) {
      return fakePlayerStates.containsKey(name);
   }

   public static void removeLocalFakePlayer(String name) {
      fakePlayerStates.remove(name);
   }

   public static boolean fakeActionActive(String name, String key) {
      JsonObject state = fakePlayerStates.get(name);
      return state != null && state.has(key) && state.get(key).getAsBoolean();
   }

   public static int fakeActionIntervalTicks(String name, String ticksKey) {
      JsonObject state = fakePlayerStates.get(name);
      if (state != null && state.has(ticksKey)) {
         try {
            return state.get(ticksKey).getAsInt();
         } catch (Exception var4) {
            return 0;
         }
      } else {
         return 0;
      }
   }

   public static List<MachineModels.MachineData> getMachines() {
      return machines;
   }

   public static MachineModels.MachineData getMachine(String id) {
      for (MachineModels.MachineData machine : machines) {
         if (machine.id != null && machine.id.equals(id)) {
            return machine;
         }
      }

      return null;
   }

   public static int getSyncVersion() {
      return syncVersion;
   }

   public static int getStructureVersion() {
      return structureVersion;
   }

   public static record PendingMachineEdit(MachineModels.MachineData machine, int baseRevision, boolean isNew, boolean modesDirty) {
   }


}

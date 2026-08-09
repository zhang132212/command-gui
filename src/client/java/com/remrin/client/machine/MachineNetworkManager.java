package com.remrin.client.machine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineModels.ModeData;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-side network layer for the machine switch system.
 * <p>
 * Registers the payload types and the sync receiver, caches the machine list pushed by the server,
 * and provides methods for sending toggle / add / edit / delete actions.
 * <p>
 * When the server does not have the server mod installed, no sync packet is ever received and
 * {@link #isServerSupported()} stays {@code false}, so the machine switch tab stays hidden and the
 * mod behaves exactly as before.
 */
public final class MachineNetworkManager {

  private static final Gson GSON = new GsonBuilder().create();
  private static final List<MachineData> machines = new ArrayList<>();
  private static boolean serverSupported = false;
  private static boolean canEdit = false;
  /** Whether the local player sees the full editing UI (permission / players rows): OP only. */
  private static boolean canConfig = false;
  private static int syncVersion = 0;
  /**
   * Incremented only when the machine list STRUCTURE changed (add / edit / delete / category
   * changes). State-only syncs (running / detected flips) keep this stable so the GUI can refresh
   * in place without resetting scroll position or pending mode selections.
   */
  private static int structureVersion = 0;
  private static String structureSignature = "";
  /** Pending block-query callback, consumed by the detection screen. */
  private static java.util.function.Consumer<String> blockQueryCallback = null;

  private MachineNetworkManager() {
  }

  /**
   * Registers payload types and receivers. Must be called once from the client initializer.
   */
  public static void init() {
    PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(
        BlockQueryResultPayload.TYPE, BlockQueryResultPayload.CODEC);

    ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) ->
        applySync(payload.json()));

    ClientPlayNetworking.registerGlobalReceiver(BlockQueryResultPayload.TYPE, (payload, context) -> {
      java.util.function.Consumer<String> callback = blockQueryCallback;
      blockQueryCallback = null;
      if (callback != null) {
        callback.accept(payload.json());
      }
    });

    // Reset on EVERY server join, not just disconnect: with a velocity proxy a /server switch keeps
    // the same connection, so DISCONNECT never fires and the previous server's machine list /
    // support flag would otherwise leak into the new server (machines shown on a server without the
    // mod). The join is followed by the server's own sync packet (if it supports machines), which
    // repopulates the state.
    ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> reset());
    ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
      reset();
      blockQueryCallback = null;
    });
  }

  /**
   * Sets the callback for the next block query result (single-shot). Used by the detection screen.
   */
  public static void setBlockQueryCallback(java.util.function.Consumer<String> callback) {
    blockQueryCallback = callback;
  }

  /**
   * Sends a block query for the detection screen. The result arrives via
   * {@link #setBlockQueryCallback}; the token is echoed back by the server so stale replies can
   * be discarded.
   */
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

  /**
   * Applies a server sync packet: replaces the cached machine list and bumps the sync version so
   * the GUI can refresh.
   */
  private static void applySync(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      canEdit = root.has("canEdit") && root.get("canEdit").getAsBoolean();
      canConfig = root.has("canConfig") && root.get("canConfig").getAsBoolean();
      machines.clear();
      if (root.has("machines")) {
        JsonArray array = root.getAsJsonArray("machines");
        for (JsonElement element : array) {
          MachineData machine = GSON.fromJson(element, MachineData.class);
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
    } catch (Exception e) {
      // Ignore malformed sync packets; keep the last known state.
    }
  }

  /**
   * Computes a structural signature of the machine list: identity, name, category, bots,
   * mode/step structure and detection configuration. Runtime fields (running / detected) are
   * excluded so state-only syncs keep the structure signature stable.
   */
  private static String computeStructureSignature() {
    StringBuilder sb = new StringBuilder();
    for (MachineData machine : machines) {
      sb.append(machine.id).append('|').append(machine.name).append('|')
          .append(machine.category == null ? "" : machine.category).append('|')
          .append(machine.description).append('|')
          .append(String.join(",", machine.bots)).append('|');
      if (machine.detection != null) {
        sb.append(machine.detection.enabled).append(':')
            .append(machine.detection.blockId).append(':')
            .append(machine.detection.property).append(':')
            .append(String.join(",", machine.detection.onValues)).append('/')
            .append(String.join(",", machine.detection.offValues)).append(':')
            .append(machine.detection.x).append(',').append(machine.detection.y)
            .append(',').append(machine.detection.z);
      }
      sb.append('|');
      if (machine.modes != null) {
        for (ModeData mode : machine.modes) {
          sb.append(mode.id).append(':').append(mode.name).append(':')
              .append(stepCount(mode.onTimeline)).append('/').append(stepCount(mode.offTimeline))
              .append(';');
        }
      }
      sb.append('|').append(stepCount(machine.onTimeline)).append('/')
          .append(stepCount(machine.offTimeline)).append('\n');
    }
    return sb.toString();
  }

  private static int stepCount(MachineModels.Timeline timeline) {
    return timeline == null || timeline.steps == null ? 0 : timeline.steps.size();
  }

  private static void reset() {
    machines.clear();
    serverSupported = false;
    canEdit = false;
    canConfig = false;
    syncVersion++;
    structureSignature = "";
    structureVersion++;
  }

  // ── Actions ─────────────────────────────────────────────────────

  public static void sendToggle(String machineId) {
    sendAction("toggle", machineId, null);
  }

  /**
   * Asks the server to push a fresh sync immediately (used when a screen opens so runtime states
   * like mode running flags are not stale).
   */
  public static void sendRequestSync() {
    JsonObject action = new JsonObject();
    action.addProperty("type", "requestSync");
    sendActionInternal(action.toString());
  }

  /**
   * Acquires or releases the hard edit lock for a machine.
   */
  public static void sendEditSession(String machineId, boolean open) {
    JsonObject action = new JsonObject();
    action.addProperty("type", "editSession");
    action.addProperty("machineId", machineId);
    action.addProperty("open", open);
    sendActionInternal(action.toString());
  }

  /**
   * Sends the DESIRED set of active mode ids: the machine's mode state converges to this set —
   * running modes that are not in the set are shut down (in the configured stop order) before the
   * missing ones are booted (in the configured start order).
   */
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

  public static void sendAdd(MachineData machine) {
    sendMachineAction("add", machine);
  }

  /**
   * Sends an edit with the base revision captured when the editor was opened, so the server can
   * reject saves based on a stale snapshot.
   */
  public static void sendEdit(MachineData machine, int baseRevision) {
    sendMachineAction("edit", machine, baseRevision);
  }

  public static void sendDelete(String machineId) {
    sendAction("delete", machineId, null);
  }

  /**
   * Asks the server to force re-detect the machine's detection block state (loading the chunk
   * if needed) and report the result.
   */
  public static void sendRefreshDetection(String machineId) {
    sendAction("refreshDetection", machineId, null);
  }

  /**
   * Asks the server to clear the given category from all machines (OP only).
   */
  public static void sendClearCategory(String categoryId) {
    JsonObject action = new JsonObject();
    action.addProperty("type", "clearCategory");
    action.addProperty("categoryId", categoryId);
    sendActionInternal(action.toString());
  }

  private static void sendMachineAction(String type, MachineData machine) {
    sendMachineAction(type, machine, -1);
  }

  private static void sendMachineAction(String type, MachineData machine, int baseRevision) {
    JsonObject action = new JsonObject();
    action.addProperty("type", type);
    action.add("machine", GSON.toJsonTree(machine));
    action.addProperty("baseRevision", baseRevision);
    sendActionInternal(action.toString());
  }

  private static void sendAction(String type, String machineId, MachineData machine) {
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
    ClientPlayNetworking.send(new ActionPayload(json));
  }

  // ── Accessors ───────────────────────────────────────────────────

  /**
   * Whether the connected server supports machine switches (a sync packet has been received).
   */
  public static boolean isServerSupported() {
    return serverSupported;
  }

  /**
   * Whether the local player may edit machines (server-side permission result).
   */
  public static boolean canEdit() {
    return canEdit;
  }

  /**
   * Whether the local player sees the full editing UI (permission level / allowed players rows):
   * only true for operators; whitelisted non-OP editors are excluded.
   */
  public static boolean canConfig() {
    return canConfig;
  }

  public static List<MachineData> getMachines() {
    return machines;
  }

  public static MachineData getMachine(String id) {
    for (MachineData machine : machines) {
      if (machine.id != null && machine.id.equals(id)) {
        return machine;
      }
    }
    return null;
  }

  /**
   * Incremented on every sync application or disconnect; GUI tabs poll this to know when to
   * rebuild their button lists.
   */
  public static int getSyncVersion() {
    return syncVersion;
  }

  /**
   * Incremented only when the machine list structure changed (add / edit / delete). State-only
   * syncs (running / detected flips) keep this stable.
   */
  public static int getStructureVersion() {
    return structureVersion;
  }

  // ── Payloads ────────────────────────────────────────────────────

  /** Server-to-client sync payload (identical wire format to the server mod). */
  public record SyncPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:machines"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new SyncPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  /** Client-to-server action payload (identical wire format to the server mod). */
  public record ActionPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ActionPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new ActionPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  /** Server-to-client block query result payload (identical wire format to the server mod). */
  public record BlockQueryResultPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlockQueryResultPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:block-query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockQueryResultPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new BlockQueryResultPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }
}

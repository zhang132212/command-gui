package com.remrin.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.remrin.server.BlockStateFileReader.BlockStateEntry;
import com.remrin.server.MachineDetector.MachineState;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.config.MachineConfig.DetectionData;
import com.remrin.server.config.MachineConfig.MachineData;
import com.remrin.server.config.MachineConfig.ModeData;
import com.remrin.server.config.MachineConfig.Step;
import com.remrin.server.config.MachineConfig.Timeline;
import com.remrin.server.net.MachinePayloads;
import com.remrin.server.net.MachinePayloads.ActionPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Manages machine switches on the server: handles client action packets (toggle / add / edit /
 * delete), enforces per-machine permissions, validates configurations, sends chat feedback, and
 * broadcasts the machine list to all online players whenever it changes.
 * <p>
 * The sync payload contains the full machine list plus each machine's running state and the
 * receiving player's {@code canEdit} flag, so the client can show or hide edit controls per player.
 */
public final class MachineManager {

  private static final Gson GSON = new GsonBuilder().create();
  /** How long an edit lock survives without activity before being force-released. */
  private static final long EDIT_LOCK_TTL_MS = 15 * 60 * 1000L;
  /**
   * Hard edit locks: machine id -> lock holder. Only the holder may open the machine editor /
   * save edits; everyone else's edit buttons are disabled. Released on save, explicit close,
   * disconnect or TTL expiry.
   */
  private static final Map<String, EditLock> editLocks = new HashMap<>();

  private MachineManager() {
  }

  // ── Action handling ─────────────────────────────────────────────

  /**
   * Entry point for client action packets. Runs on the server thread (Fabric guarantees this).
   */
  public static void handleAction(ServerPlayer player, MinecraftServer server,
      ActionPayload payload) {
    try {
      JsonObject action = JsonParser.parseString(payload.json()).getAsJsonObject();
      String type = action.has("type") ? action.get("type").getAsString() : "";
      switch (type) {
        case "toggle" -> toggleMachine(player, server, getString(action, "machineId"));
        case "setModes" -> setModes(player, server, getString(action, "machineId"),
            parseModeIds(action));
        case "editSession" -> editSession(player, server, getString(action, "machineId"),
            action.has("open") && action.get("open").getAsBoolean());
        case "add" -> addMachine(player, server, action.getAsJsonObject("machine"));
        case "edit" -> editMachine(player, server, action.getAsJsonObject("machine"),
            action.has("baseRevision") ? action.get("baseRevision").getAsInt() : -1);
        case "delete" -> deleteMachine(player, server, getString(action, "machineId"));
        case "queryBlock" -> queryBlock(player, server, action);
        case "refreshDetection" -> refreshDetection(player, server,
            getString(action, "machineId"));
        case "clearCategory" -> clearCategory(player, server,
            getString(action, "categoryId"));
        case "requestSync" -> syncTo(player);
        default -> MachineMod.LOGGER.warn("Unknown machine action '{}' from {}", type,
            player.getGameProfile().name());
      }
    } catch (Exception e) {
      MachineMod.LOGGER.warn("Malformed machine action from {}: {}",
          player.getGameProfile().name(), e.getMessage());
    }
  }

  private static void toggleMachine(ServerPlayer player, MinecraftServer server,
      String machineId) {
    MachineData machine = MachineConfig.getMachine(machineId);
    if (machine == null) {
      sendMessage(player, "机器不存在: " + machineId);
      return;
    }
    if (!canToggle(player, machine)) {
      sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      return;
    }
    int tick = server.getTickCount();
    int lockRemaining = MachineScheduler.switchLockRemaining(machine.id, tick);
    if (lockRemaining > 0) {
      sendMessage(player, "机器「" + machine.name + "」开机/关机之后有（剩余 " + lockRemaining
          + " tick）冷却时间，请稍后重试");
      MachineMod.LOGGER.info("Machine '{}' toggle rejected by {}: switch interval ({} left)",
          machine.id, player.getGameProfile().name(), lockRemaining);
      return;
    }
    // Fresh in-memory state when loaded, else the unload-time snapshot (no chunk loading needed)
    MachineDetector.DetectionResult detection =
        MachineDetector.evaluateDetailed(machine, server, true);
    MachineState detected = detection.state();
    if (detected == MachineState.ABNORMAL) {
      sendMessage(player, "机器「" + machine.name + "」处于异常状态，无法开关："
          + (detection.reason() != null ? detection.reason() : "检测方块异常"));
      return;
    }
    if (detected == MachineState.ON) {
      // The detection block says the machine is on: clicking shuts it down. The success/failure
      // message is sent when the shutdown timeline completes (or fails) — see
      // onSwitchTimelineFinished / onSwitchCommandFailed.
      MachineScheduler.start(machine, machine.offTimeline, player.getGameProfile().name(), true);
    } else if (detected == MachineState.OFF) {
      // The detection block says the machine is off: clicking boots it
      bootMachine(player, machine);
    } else if (MachineScheduler.isRunning(machine.id)) {
      // No detection configured: the toggle follows the script running state
      MachineScheduler.start(machine, machine.offTimeline, player.getGameProfile().name(), true);
    } else {
      bootMachine(player, machine);
    }
    MachineScheduler.recordSwitchTick(machine.id, tick);
    broadcastSync(server.getPlayerList());
  }

  /**
   * Force re-detects the machine's detection block (loading the chunk if needed so the fresh
   * in-memory state is read), reports the result to the player and refreshes the sync.
   */
  private static void refreshDetection(ServerPlayer player, MinecraftServer server,
      String machineId) {
    MachineData machine = MachineConfig.getMachine(machineId);
    if (machine == null) {
      sendMessage(player, "机器不存在: " + machineId);
      return;
    }
    if (!canToggle(player, machine)) {
      sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      return;
    }
    if (machine.detection == null || !machine.detection.enabled) {
      sendMessage(player, "机器「" + machine.name + "」未配置检测方块");
      return;
    }
    MachineDetector.DetectionResult result =
        MachineDetector.evaluateDetailed(machine, server, true);    String message = switch (result.state()) {
      case ON -> "§a机器「" + machine.name + "」检测状态：开机";
      case OFF -> "机器「" + machine.name + "」检测状态：关机";
      case ABNORMAL -> "§c机器「" + machine.name + "」检测异常："
          + (result.reason() != null ? result.reason() : "检测方块异常");
      default -> "机器「" + machine.name + "」检测未启用";
    };
    sendMessage(player, message);
    broadcastSync(server.getPlayerList());
  }

  /**
   * Clears the given category from all machines (machines fall back to the default category).
   * Only full editors (OP) may do this; whitelisted non-OP editors cannot.
   */
  private static void clearCategory(ServerPlayer player, MinecraftServer server,
      String categoryId) {
    if (!isFullEditor(player)) {
      sendMessage(player, "你没有权限删除机器分类");
      return;
    }
    if (categoryId == null || categoryId.isEmpty()) {
      sendMessage(player, "默认分类不能删除");
      return;
    }
    int cleared = 0;
    for (MachineData machine : MachineConfig.getMachines()) {
      if (machine.category != null && machine.category.equals(categoryId)) {
        machine.category = "";
        cleared++;
      }
    }
    if (cleared > 0) {
      MachineConfig.save();
      MachineBlockCache.rebuild();
      sendMessage(player, "已清空分类「" + categoryId + "」下的 " + cleared + " 台机器");
    } else {
      sendMessage(player, "分类「" + categoryId + "」下没有机器");
    }
    broadcastSync(server.getPlayerList());
  }

  /**
   * Runs the machine's boot process after the script check (first command must be a bot spawn).
   */
  private static void bootMachine(ServerPlayer player, MachineData machine) {
    Timeline onTimeline = machine.onTimeline;
    boolean validStart = onTimeline != null && !onTimeline.steps.isEmpty()
        && onTimeline.steps.get(0).commands != null
        && !onTimeline.steps.get(0).commands.isEmpty()
        && isSpawnCommand(onTimeline.steps.get(0).commands.get(0));
    if (!validStart) {
      sendMessage(player, "机器「" + machine.name + "」开机流程第一个指令必须是假人spawn指令");
      return;
    }
    // Pre-check the first command against the TRIGGERING PLAYER's own permissions: if it would be
    // rejected for them (e.g. a non-OP trying to run /gamemode), the boot would silently do
    // nothing — tell the player instead of claiming the machine started.
    String firstCommand = onTimeline.steps.get(0).commands.get(0);
    String permissionError = MachineScheduler.checkCommandPermission(firstCommand,
        player.getGameProfile().name());
    if (permissionError != null) {
      sendMessage(player, "机器「" + machine.name + "」启动失败：无权限执行指令（" + permissionError + "）");
      MachineMod.LOGGER.warn("Machine '{}' boot blocked: {} — {}", machine.id, firstCommand,
          permissionError);
      return;
    }
    // Bots do not need to be online: the boot process is expected to spawn them. The success
    // message is sent when the boot timeline completes (or fails immediately) — see
    // onSwitchTimelineFinished / onSwitchCommandFailed.
    MachineScheduler.start(machine, machine.onTimeline, player.getGameProfile().name(), false);
  }

  /**
   * Handles a block query for the detection screen: reads the block at the given coordinates
   * (in-memory when the chunk is loaded, otherwise from the region files) and replies with the
   * block id, all possible property values and the current property values.
   */
  private static void queryBlock(ServerPlayer player, MinecraftServer server, JsonObject action) {
    String dimension = getString(action, "dimension");
    if (dimension.isEmpty()) {
      dimension = "minecraft:overworld";
    }
    int x = action.has("x") ? action.get("x").getAsInt() : 0;
    int y = action.has("y") ? action.get("y").getAsInt() : 0;
    int z = action.has("z") ? action.get("z").getAsInt() : 0;
    long token = action.has("token") ? action.get("token").getAsLong() : -1;

    JsonObject result = new JsonObject();
    result.addProperty("token", token);
    String blockId = null;
    Map<String, String> current = new HashMap<>();

    ServerLevel level = server.getLevel(
        ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    BlockPos pos = new BlockPos(x, y, z);
    if (level != null && level.hasChunkAt(pos)) {
      BlockState state = level.getBlockState(pos);
      blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      for (Property.Value<?> value : state.getValues().toList()) {
        current.put(value.property().getName(), value.valueName());
      }
    } else if (level != null) {
      BlockStateEntry entry = BlockStateFileReader.read(server, dimension, x, y, z);
      if (entry != null) {
        blockId = entry.blockId();
        current = entry.properties();
      }
    }

    if (blockId == null) {
      result.addProperty("found", false);
    } else {
      result.addProperty("found", true);
      result.addProperty("blockId", blockId);
      JsonObject properties = new JsonObject();
      Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
      if (block != null && block != Blocks.AIR) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
          JsonArray values = new JsonArray();
          for (Object value : property.getPossibleValues()) {
            values.add(serializePropertyValue(property, value));
          }
          properties.add(property.getName(), values);
        }
      }
      result.add("properties", properties);
      JsonObject currentJson = new JsonObject();
      for (Map.Entry<String, String> entry : current.entrySet()) {
        currentJson.addProperty(entry.getKey(), entry.getValue());
      }
      result.add("current", currentJson);
    }
    ServerPlayNetworking.send(player, new MachinePayloads.BlockQueryResultPayload(result.toString()));
  }

  /**
   * Applies the desired set of active mode ids: starts modes that should run but are stopped, and
   * stops modes that should stop but are running. Modes are fully independent of the switch.
   * <p>
   * The {@code toggleIds} list is a set of DELTAS: each id is flipped (stopped modes are started,
   * running modes are stopped). Concurrent toggles from different players compose correctly.
   * <p>
   * When several modes change at once, the mode chain ({@link MachineModeChain}) sequences them
   * according to the machine's multi-mode preset (stop order/interval then start order/interval).
   * Single changes are applied immediately. The lock window ({@code switchInterval}) blocks
   * toggling a mode that was just toggled.
   */
  /**
   * Applies the DESIRED set of active mode ids: every running mode that is not desired is shut
   * down and every desired mode that is not running is booted — i.e. the machine's mode state
   * converges to the given set. The client sends the final target set (the modes the player wants
   * running), not a delta.
   * <p>
   * Shutdowns always happen BEFORE boots: the chain built here first stops the undesired modes
   * (in the configured stop order with the configured interval), then starts the desired ones (in
   * the configured start order with the configured interval). Single changes are applied
   * immediately. The lock window ({@code switchInterval}) blocks toggling a mode that was just
   * toggled.
   */
  private static void setModes(ServerPlayer player, MinecraftServer server, String machineId,
      List<String> desiredIds) {
    MachineData machine = MachineConfig.getMachine(machineId);
    if (machine == null) {
      sendMessage(player, "机器不存在: " + machineId);
      return;
    }
    if (!canToggle(player, machine)) {
      sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      return;
    }
    // While any mode process (boot or shutdown timeline) is executing — or a sequenced chain is
    // queued — further switches are rejected: the running process must finish (and then enter its
    // switch cooldown) before the modes can be toggled again.
    for (ModeData busy : machine.modes) {
      if (MachineScheduler.isModeProcessRunning(machine.id, busy.id)) {
        sendMessage(player, "机器「" + machine.name + "」模式正在执行开机/关机流程，请稍候再切换");
        return;
      }
    }
    if (MachineModeChain.isActive(machine.id)) {
      sendMessage(player, "机器「" + machine.name + "」模式正在切换中，请稍候再操作");
      return;
    }
    // The switch cooldown (switchInterval) starts when the last process COMPLETED; while any mode
    // of the machine is still cooling down, further switches are rejected.
    int tick = server.getTickCount();
    for (ModeData cooling : machine.modes) {
      int remaining = MachineScheduler.modeLockRemaining(machine.id, cooling.id, tick);
      if (remaining > 0) {
        sendMessage(player, "机器「" + machine.name + "」模式切换冷却中（剩余 " + remaining
            + " tick），请稍后重试");
        return;
      }
    }
    Set<String> desired = new HashSet<>(desiredIds);
    List<String> startIds = new ArrayList<>();
    List<String> stopIds = new ArrayList<>();
    int rejected = 0;
    for (ModeData mode : machine.modes) {
      boolean selected = desired.contains(mode.id); // the player clicked this mode
      // The mode's switch state comes from its detection block (the block IS the switch): ON =
      // running, OFF = off, ABNORMAL = locked. Read it live at click time.
      MachineState state = MachineDetector.evaluateDetection(mode.detection, server, true).state();
      MachineScheduler.updateDetectedModeState(machine.id, mode.id, state);
      if (state == MachineState.ABNORMAL) {
        if (selected) {
          MachineDetector.DetectionResult abnormal =
              MachineDetector.evaluateDetection(mode.detection, server, true);
          sendMessage(player, "模式「" + mode.name + "」检测异常，无法开启："
              + (abnormal.reason() != null ? abnormal.reason() : "检测方块异常"));
          MachineMod.LOGGER.info("Mode '{}' of machine '{}' rejected by {}: detection abnormal",
              mode.id, machine.id, player.getGameProfile().name());
          rejected++;
        }
        continue; // abnormal: neither bootable nor explicitly stopable by this click
      }
      if (mode.singleSelect) {
        // Single-select: target semantics — the clicked mode becomes the only selected one;
        // running single-select modes outside the target are shut down.
        boolean running = (state == MachineState.ON);
        if (selected == running) {
          continue; // already matches the target
        }
        if (!selected) {
          stopIds.add(mode.id);
          continue;
        }
        // selected && OFF: boot it
        if (!validModeStart(mode)) {
          sendMessage(player, "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令");
          MachineMod.LOGGER.info("Mode '{}' of machine '{}' rejected by {}: invalid start",
              mode.id, machine.id, player.getGameProfile().name());
          rejected++;
          continue;
        }
        startIds.add(mode.id);
      } else {
        // Multi-select: toggle semantics — clicking a mode flips its state (ON -> stop,
        // OFF -> start). Modes the player did NOT click are left untouched (single-select and
        // multi-select do not interfere with each other).
        if (!selected) {
          continue;
        }
        if (state == MachineState.ON) {
          stopIds.add(mode.id); // clicked a running mode -> shut it down
          continue;
        }
        if (!validModeStart(mode)) {
          sendMessage(player, "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令");
          MachineMod.LOGGER.info("Mode '{}' of machine '{}' rejected by {}: invalid start",
              mode.id, machine.id, player.getGameProfile().name());
          rejected++;
          continue;
        }
        startIds.add(mode.id); // clicked an off mode -> boot it
      }
    }
    MachineMod.LOGGER.info("Machine '{}' setModes by {}: start={} stop={} rejected={}",
        machine.id, player.getGameProfile().name(), startIds, stopIds, rejected);
    String triggerPlayer = player.getGameProfile().name();
    if (startIds.size() + stopIds.size() == 1) {
      // Single change: apply immediately (no sequencing needed)
      if (stopIds.size() == 1) {
        MachineScheduler.stopModeWithShutdown(machine, findMode(machine, stopIds.get(0)),
            triggerPlayer);
      } else {
        ModeData toStart = findMode(machine, startIds.get(0));
        // Single-select replacement: starting a single-select mode must stop any other RUNNING
        // single-select mode (otherwise both run at once and their processes fight over the same
        // bots). Route through the chain so the replacement wait applies.
        boolean needsReplacement = false;
        if (toStart != null && toStart.singleSelect && machine.modes != null) {
          for (ModeData other : machine.modes) {
            if (other.singleSelect && !other.id.equals(toStart.id)
                && MachineScheduler.isModeRunning(machine.id, other.id)) {
              needsReplacement = true;
              break;
            }
          }
        }
        if (needsReplacement) {
          List<String> allStops = new ArrayList<>(stopIds);
          for (ModeData other : machine.modes) {
            if (other.singleSelect && !other.id.equals(toStart.id)
                && MachineScheduler.isModeRunning(machine.id, other.id)) {
              allStops.add(other.id);
            }
          }
          MachineModeChain.buildChains(machine, startIds, allStops, triggerPlayer);
          broadcastSync(server.getPlayerList());
          return;
        }
        MachineScheduler.startMode(machine, toStart, triggerPlayer);
      }
    } else if (startIds.size() + stopIds.size() > 1) {
      MachineModeChain.buildChains(machine, startIds, stopIds, triggerPlayer);
    }
    if (startIds.size() + stopIds.size() == 0) {
      // Nothing to change: report (rejections were already reported per-mode).
      sendMessage(player, "机器「" + machine.name + "」没有需要变更的模式"
          + (rejected > 0 ? "（" + rejected + " 个被拒绝）" : ""));
    }
    // Otherwise the switch started; the "模式已切换" confirmation is sent when the processes
    // COMPLETE (single change: MachineScheduler; sequenced chain: MachineModeChain).
    broadcastSync(server.getPlayerList());
  }

  private static ModeData findMode(MachineData machine, String modeId) {
    if (machine.modes == null) {
      return null;
    }
    for (ModeData mode : machine.modes) {
      if (mode.id != null && mode.id.equals(modeId)) {
        return mode;
      }
    }
    return null;
  }

  /**
   * Acquires or releases the hard edit lock for a machine. Only the lock holder may open the
   * editor; releasing is only honored for the current holder.
   */
  private static void editSession(ServerPlayer player, MinecraftServer server, String machineId,
      boolean open) {
    String name = player.getGameProfile().name();
    if (open) {
      if (MachineConfig.getMachine(machineId) == null) {
        sendMessage(player, "机器不存在: " + machineId);
        return;
      }
      cleanupExpiredLocks();
      EditLock lock = editLocks.get(machineId);
      if (lock != null && !lock.editor().equals(name)) {
        sendMessage(player, "机器正在被 " + lock.editor() + " 编辑，无法打开编辑");
      } else {
        editLocks.put(machineId, new EditLock(name, System.currentTimeMillis()));
      }
    } else {
      EditLock lock = editLocks.get(machineId);
      if (lock != null && lock.editor().equals(name)) {
        editLocks.remove(machineId);
      }
    }
    broadcastSync(server.getPlayerList());
  }

  /**
   * Called when a player disconnects: releases all edit locks held by them.
   */
  public static void onPlayerDisconnect(String name) {
    boolean removed = editLocks.entrySet().removeIf(entry -> entry.getValue().editor().equals(name));
    if (removed) {
      MinecraftServer server = MachineMod.getCurrentServer();
      if (server != null) {
        broadcastSync(server.getPlayerList());
      }
    }
  }

  /**
   * Removes edit locks that have exceeded {@link #EDIT_LOCK_TTL_MS} without activity.
   */
  private static void cleanupExpiredLocks() {
    long now = System.currentTimeMillis();
    editLocks.entrySet().removeIf(
        entry -> now - entry.getValue().acquiredAt() > EDIT_LOCK_TTL_MS);
  }

  private static void addMachine(ServerPlayer player, MinecraftServer server,
      JsonObject machineJson) {
    if (!canEdit(player)) {
      sendMessage(player, "你没有权限编辑机器");
      return;
    }
    MachineData machine = GSON.fromJson(machineJson, MachineData.class);
    if (machine == null) {
      sendMessage(player, "机器数据无效");
      return;
    }
    String error = validate(machine);
    if (error != null) {
      sendMessage(player, "机器配置无效: " + error);
      return;
    }
    // De-duplicate the id server-side so concurrent creates with the same name both succeed
    machine.id = uniqueMachineId(machine.id);
    if (!MachineConfig.addMachine(machine)) {
      sendMessage(player, "机器 id 已存在: " + machine.id);
      return;
    }
    MachineBlockCache.rebuild();
    sendMessage(player, "机器「" + machine.name + "」已添加");
    broadcastSync(server.getPlayerList());
  }

  private static void editMachine(ServerPlayer player, MinecraftServer server,
      JsonObject machineJson, int baseRevision) {
    if (!canEdit(player)) {
      sendMessage(player, "你没有权限编辑机器");
      return;
    }
    MachineData machine = GSON.fromJson(machineJson, MachineData.class);
    MachineData existing = machine != null ? MachineConfig.getMachine(machine.id) : null;
    if (machine == null || existing == null) {
      sendMessage(player, "机器不存在: " + (machine != null ? machine.id : "?"));
      return;
    }
    // Hard edit lock: only the lock holder may save edits
    EditLock lock = editLocks.get(machine.id);
    if (lock == null || !lock.editor().equals(player.getGameProfile().name())) {
      sendMessage(player, "机器「" + machine.name + "」正在被 "
          + (lock != null ? lock.editor() : "其他玩家") + " 编辑，无法保存");
      return;
    }
    // Optimistic-lock backstop: reject saves based on a stale snapshot
    if (baseRevision >= 0 && baseRevision != existing.revision) {
      sendMessage(player, "机器已被其他人修改，请关闭后重新打开编辑");
      return;
    }
    String error = validate(machine);
    if (error != null) {
      sendMessage(player, "机器配置无效: " + error);
      return;
    }
    MachineConfig.updateMachine(machine);
    MachineScheduler.invalidate(machine.id);
    MachineModeChain.invalidate(machine.id);
    MachineScheduler.clearSwitchTicks(machine.id);
    MachineBlockCache.rebuild();
    editLocks.remove(machine.id);
    sendMessage(player, "机器「" + machine.name + "」已更新");
    broadcastSync(server.getPlayerList());
  }

  private static void deleteMachine(ServerPlayer player, MinecraftServer server,
      String machineId) {
    if (!canEdit(player)) {
      sendMessage(player, "你没有权限编辑机器");
      return;
    }
    MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
    if (machine == null) {
      sendMessage(player, "机器不存在: " + machineId);
      return;
    }
    EditLock lock = editLocks.get(machineId);
    if (lock != null) {
      sendMessage(player, "机器「" + machine.name + "」正在被 " + lock.editor() + " 编辑，无法删除");
      return;
    }
    MachineConfig.removeMachine(machineId);
    MachineScheduler.invalidate(machineId);
    MachineModeChain.invalidate(machineId);
    MachineScheduler.clearSwitchTicks(machineId);
    MachineBlockCache.rebuild();
    sendMessage(player, "机器「" + machine.name + "」已删除");
    broadcastSync(server.getPlayerList());
  }

  /**
   * Returns a machine id unique within the config, appending {@code -2}, {@code -3}, ... when the
   * base id is already taken (concurrent creates from different players).
   */
  private static String uniqueMachineId(String base) {
    if (MachineConfig.getMachine(base) == null) {
      return base;
    }
    int n = 2;
    while (MachineConfig.getMachine(base + "-" + n) != null) {
      n++;
    }
    return base + "-" + n;
  }

  // ── Sync broadcast ──────────────────────────────────────────────

  /**
   * Pushes the full machine list to the given player (used when they join).
   */
  public static void syncTo(ServerPlayer player) {
    if (ServerPlayNetworking.canSend(player, MachinePayloads.SyncPayload.TYPE)) {
      ServerPlayNetworking.send(player, new MachinePayloads.SyncPayload(buildSyncJson(player)));
    }
  }

  /**
   * Sends the current machine state to all online players after any change.
   */
  public static void broadcastSync(PlayerList playerList) {
    if (playerList == null) {
      return;
    }
    for (ServerPlayer player : playerList.getPlayers()) {
      syncTo(player);
    }
  }

  /** Last computed runtime-state signature, used to detect changes for periodic syncs. */
  private static String lastStateSignature = "";

  /**
   * Periodically (once per second) recomputes the machines' runtime signature (switch running
   * state, detection state, mode running states). When anything changed — e.g. the detection block
   * was flipped in-game or a timeline finished — a sync is broadcast so the client switch buttons
   * always show the current state. Cheap: detection is only evaluated for machines that have it
   * enabled.
   */
  public static void tickStates(MinecraftServer server) {
    if (server.getTickCount() % 20 != 0) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    for (MachineData machine : MachineConfig.getMachines()) {
      sb.append(machine.id).append('=').append(MachineScheduler.isRunning(machine.id)).append(',');
      if (machine.detection != null && machine.detection.enabled) {
        sb.append(MachineDetector.evaluate(machine, server).name()).append(',');
      }
      if (machine.modes != null) {
        for (ModeData mode : machine.modes) {
          // Refresh the cached detection state first, then include it in the signature. The block
          // state IS the mode's switch state (isModeRunning reads this cache).
          if (mode.detection != null && mode.detection.enabled) {
            MachineState state =
                MachineDetector.evaluateDetection(mode.detection, server, true).state();
            MachineScheduler.updateDetectedModeState(machine.id, mode.id, state);
            sb.append(state);
          } else {
            MachineScheduler.updateDetectedModeState(machine.id, mode.id, MachineState.DISABLED);
            sb.append('D');
          }
          sb.append(MachineScheduler.isModeRunning(machine.id, mode.id));
        }
      }
      sb.append(';');
    }
    String signature = sb.toString();
    if (!signature.equals(lastStateSignature)) {
      lastStateSignature = signature;
      broadcastSync(server.getPlayerList());
    }
  }

  /**
   * Called by the scheduler when a machine's timeline finishes (loop = false).
   */
  public static void onMachineFinished(String machineId) {
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null) {
      broadcastSync(server.getPlayerList());
    }
  }

  /**
   * Called when a mode switch process (a single change's timeline, or the whole sequenced chain)
   * COMPLETED successfully: notifies the triggering player that the switch is done. Cooldown for
   * the involved modes was already recorded by the scheduler at completion time.
   */
  public static void onModeSwitchFinished(MachineData machine, String triggerPlayer) {
    MachineMod.LOGGER.info("Machine '{}' mode switch finished (by {})", machine.id, triggerPlayer);
    if (triggerPlayer == null || triggerPlayer.isEmpty()) {
      return;
    }
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
      if (player != null) {
        sendMessage(player, "机器「" + machine.name + "」模式已切换");
      }
    }
  }

  /**
   * Called by the scheduler when a mode's process command fails (permission / invalid) mid-switch.
   * The process is aborted immediately and the error is reported to the triggering player.
   */
  public static void onModeCommandFailed(MachineData machine, String modeId,
      boolean isOffTimeline, String triggerPlayer, String command, String reason) {
    String modeName = modeId;
    for (ModeData mode : machine.modes) {
      if (mode.id != null && mode.id.equals(modeId)) {
        modeName = mode.name;
        break;
      }
    }
    String action = isOffTimeline ? "关闭" : "开启";
    MachineMod.LOGGER.warn("Mode '{}' of machine '{}' {} failed: {} ({})", modeId, machine.id,
        action, command, reason);
    String message = "模式「" + modeName + "」" + action + "失败：" + reason + "（" + command + "）";
    if (triggerPlayer == null || triggerPlayer.isEmpty()) {
      broadcastSystem(message);
      return;
    }
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
      if (player != null) {
        sendMessage(player, message);
      }
    }
  }

  /**
   * Called by the scheduler the moment a switch timeline command fails (permission / invalid).
   * Aborts the process and tells the triggering player immediately, with the reason.
   */
  public static void onSwitchCommandFailed(MachineData machine, boolean isOffTimeline,
      String triggerPlayer, String command, String reason) {
    MachineMod.LOGGER.warn("Machine '{}' {} failed: {} ({})", machine.id,
        isOffTimeline ? "shutdown" : "boot", command, reason);
    if (triggerPlayer == null || triggerPlayer.isEmpty()) {
      broadcastSystem("机器「" + machine.name + "」" + (isOffTimeline ? "关闭" : "启动")
          + "失败：" + reason + "（" + command + "）");
      return;
    }
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
      if (player != null) {
        sendMessage(player, "机器「" + machine.name + "」" + (isOffTimeline ? "关闭" : "启动")
            + "失败：" + reason + "（" + command + "）");
      }
    }
  }

  /**
   * Called by the scheduler when a switch timeline (boot or shutdown) finished with every command
   * succeeding. Sends the success message to the triggering player (if still online).
   */
  public static void onSwitchTimelineFinished(MachineData machine, boolean isOffTimeline,
      String triggerPlayer) {
    MachineMod.LOGGER.info("Machine '{}' {} finished", machine.id,
        isOffTimeline ? "shutdown" : "boot");
    if (triggerPlayer == null || triggerPlayer.isEmpty()) {
      return;
    }
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
      if (player != null) {
        sendMessage(player, "机器「" + machine.name + "」已"
            + (isOffTimeline ? "关闭" : "开启"));
      }
    }
  }

  private static String buildSyncJson(ServerPlayer viewer) {
    cleanupExpiredLocks();
    JsonObject root = new JsonObject();
    root.addProperty("canEdit", canEdit(viewer));
    // Full editors (OP) see the permission-level / allowed-players config rows.
    root.addProperty("canConfig", isFullEditor(viewer));
    MinecraftServer server = MachineMod.getCurrentServer();
    JsonArray machines = new JsonArray();
    for (MachineData machine : MachineConfig.getMachines()) {
      JsonObject element = GSON.toJsonTree(machine).getAsJsonObject();
      element.addProperty("running", MachineScheduler.isRunning(machine.id));
      EditLock lock = editLocks.get(machine.id);
      element.addProperty("editingBy", lock != null ? lock.editor() : "");
      if (server != null) {
        MachineState detected = MachineDetector.evaluate(machine, server);
        element.addProperty("detected", switch (detected) {
          case ON -> "on";
          case OFF -> "off";
          case ABNORMAL -> "abnormal";
          default -> "";
        });
      } else {
        element.addProperty("detected", "");
      }
      if (machine.modes != null) {
        JsonArray modes = element.getAsJsonArray("modes");
        if (modes != null) {
          for (int i = 0; i < modes.size() && i < machine.modes.size(); i++) {
            JsonObject modeJson = modes.get(i).getAsJsonObject();
            ModeData mode = machine.modes.get(i);
            String detected = "";
            if (server != null && mode.detection != null && mode.detection.enabled) {
              // The detection block is the mode's switch: evaluate it, refresh the scheduler's
              // cache, and derive both the displayed state and the running flag from the same read.
              MachineState modeDetected = MachineDetector.evaluateDetection(
                  mode.detection, server, true).state();
              MachineScheduler.updateDetectedModeState(machine.id, mode.id, modeDetected);
              detected = switch (modeDetected) {
                case ON -> "on";
                case OFF -> "off";
                case ABNORMAL -> "abnormal";
                default -> "";
              };
            } else {
              MachineScheduler.updateDetectedModeState(machine.id, mode.id, MachineState.DISABLED);
            }
            modeJson.addProperty("running", MachineScheduler.isModeRunning(machine.id, mode.id));
            modeJson.addProperty("detected", detected);
            modeJson.addProperty("processing",
                MachineScheduler.isModeProcessRunning(machine.id, mode.id));
          }
        }
      }
      machines.add(element);
    }
    root.add("machines", machines);
    return root.toString();
  }

  // ── Permissions ─────────────────────────────────────────────────

  /**
   * Whether the player may edit machines (add / edit / delete): operator permission level 2
   * (game master) or higher, OR membership in the editor whitelist.
   */
  private static boolean canEdit(ServerPlayer player) {
    if (hasLevel(player, Commands.LEVEL_MODERATORS)) {
      return true;
    }
    for (String name : MachineConfig.getEditorWhitelist()) {
      if (name.equalsIgnoreCase(player.getGameProfile().name())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the player sees the full editing UI (permission level / allowed players rows):
   * operator permission level 2 or higher. Whitelisted non-OP editors are excluded.
   */
  private static boolean isFullEditor(ServerPlayer player) {
    return hasLevel(player, Commands.LEVEL_MODERATORS);
  }

  /**
   * Whether the player may toggle a specific machine: the machine's allowedPlayers whitelist wins
   * when non-empty, otherwise the machine's configured permissionLevel applies.
   */
  private static boolean canToggle(ServerPlayer player, MachineData machine) {
    if (machine.allowedPlayers != null && !machine.allowedPlayers.isEmpty()) {
      return machine.allowedPlayers.contains(player.getGameProfile().name());
    }
    return switch (machine.permissionLevel) {
      case 0, 1 -> true;
      case 3 -> hasLevel(player, Commands.LEVEL_ADMINS);
      case 4 -> hasLevel(player, Commands.LEVEL_OWNERS);
      default -> hasLevel(player, Commands.LEVEL_MODERATORS);
    };
  }

  private static boolean hasLevel(ServerPlayer player, PermissionCheck check) {
    return check.check(player.permissions());
  }

  // ── Validation ───────────────────────────────────────────────────

  private static String validate(MachineData machine) {
    if (machine.id == null || machine.id.isBlank()) {
      return "id 不能为空";
    }
    if (machine.name == null || machine.name.isBlank()) {
      return "名称不能为空";
    }
    if (machine.category == null) {
      machine.category = "";
    }
    if (machine.category.length() > 30) {
      return "分类名称过长（最多30字符）";
    }
    if (machine.bots == null || machine.bots.isEmpty()) {
      return "至少需要一个 bot 名称";
    }
    if (machine.permissionLevel < 0 || machine.permissionLevel > 4) {
      return "权限等级必须在 0-4 之间";
    }
    if (machine.switchInterval < 0 || machine.switchInterval > 1200) {
      return "开关间隔必须在 0-1200 tick 之间";
    }
    if (machine.modeInterval < 0 || machine.modeInterval > 12000) {
      return "模式间隔必须在 0-12000 tick 之间";
    }
    if (machine.stopModeInterval < 0 || machine.stopModeInterval > 12000) {
      return "停止间隔必须在 0-12000 tick 之间";
    }
    for (int i = 0; i < machine.bots.size(); i++) {
      if (machine.bots.get(i) == null || machine.bots.get(i).isBlank()) {
        return "bot 名称不能为空";
      }
    }
    String timelineError = validateTimeline(machine.onTimeline, machine.bots.size(), "开机流程");
    if (timelineError != null) {
      return timelineError;
    }
    // The boot process must start with a fake player spawn command so bots exist before any
    // other scripted action runs.
    if (machine.onTimeline.steps.isEmpty()) {
      return "开机流程至少需要一个步骤";
    }
    Step firstStep = machine.onTimeline.steps.get(0);
    if (firstStep.commands == null || firstStep.commands.isEmpty()
        || !isSpawnCommand(firstStep.commands.get(0))) {
      return "开机流程第一个指令必须是假人spawn指令（如 /player {bot} spawn ...）";
    }
    timelineError = validateTimeline(machine.offTimeline, machine.bots.size(), "关闭流程");
    if (timelineError != null) {
      return timelineError;
    }
    if (machine.modes != null) {
      Set<String> modeIds = new HashSet<>();
      for (int i = 0; i < machine.modes.size(); i++) {
        ModeData mode = machine.modes.get(i);
        String label = "模式" + (i + 1);
        if (mode == null) {
          return label + "为空";
        }
        if (mode.id == null || mode.id.isBlank()) {
          return label + " id 不能为空";
        }
        if (!modeIds.add(mode.id)) {
          return label + " id 重复: " + mode.id;
        }
        if (mode.name == null || mode.name.isBlank()) {
          return label + " 名称不能为空";
        }
        String modeError = validateTimeline(mode.onTimeline, machine.bots.size(),
            "模式「" + mode.name + "」");
        if (modeError != null) {
          return modeError;
        }
        if (mode.onTimeline.steps.isEmpty()) {
          return "模式「" + mode.name + "」至少需要一个步骤";
        }
        // Modes never loop: the boot / shutdown processes run exactly once so sequenced
        // multi-mode chains always advance.
        mode.onTimeline.loopCount = 0;
        mode.offTimeline.loopCount = 0;
        if (mode.switchInterval < 0 || mode.switchInterval > 1200) {
          return "模式「" + mode.name + "」开关间隔必须在 0-1200 tick 之间";
        }
        Step modeFirst = mode.onTimeline.steps.get(0);
        if (modeFirst.commands == null || modeFirst.commands.isEmpty()
            || !isSpawnCommand(modeFirst.commands.get(0))) {
          return "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令";
        }
        // Shutdown process is optional and has no spawn-first requirement
        String offError = validateTimeline(mode.offTimeline, machine.bots.size(),
            "模式「" + mode.name + "」关机流程");
        if (offError != null) {
          return offError;
        }
        // Every mode MUST configure a detection block: the block state IS the mode's switch
        // (ON = running, OFF = off, ABNORMAL = locked). A mode without detection cannot be saved.
        if (mode.detection == null || !mode.detection.enabled) {
          return "模式「" + mode.name + "」必须配置开关检测";
        }
        String detectionError = validateDetection(mode.detection, "模式「" + mode.name + "」");
        if (detectionError != null) {
          return detectionError;
        }
      }
    }
    if (machine.detection != null && machine.detection.enabled) {
      String detectionError = validateDetection(machine.detection, "机器");
      if (detectionError != null) {
        return detectionError;
      }
    }
    return null;
  }

  private static String validateDetection(DetectionData detection, String label) {
    if (detection.dimension == null || detection.dimension.isBlank()) {
      return label + "检测维度不能为空";
    }
    if (detection.blockId == null || detection.blockId.isBlank()) {
      return label + "检测方块不能为空";
    }
    if ("minecraft:air".equals(MachineDetector.normalizeId(detection.blockId))) {
      return label + "检测方块不能是空气";
    }
    if (detection.property == null || detection.property.isBlank()) {
      return label + "检测属性不能为空";
    }
    if (detection.onValues == null || detection.onValues.isEmpty()) {
      return label + "开机状态至少配置一个";
    }
    if (detection.offValues == null || detection.offValues.isEmpty()) {
      return label + "关机状态至少配置一个";
    }
    Set<String> onValues = new HashSet<>(detection.onValues);
    for (String value : detection.offValues) {
      if (onValues.contains(value)) {
        return label + "开机/关机状态不能重叠: " + value;
      }
    }
    return null;
  }

  private static String validateTimeline(Timeline timeline, int botCount, String label) {    if (timeline == null) {
      return label + "时间线为空";
    }
    if (timeline.steps == null) {
      return label + "时间线步骤为空";
    }
    if (timeline.loopCount < -1) {
      return label + "时间线循环次数不能小于 -1";
    }
    for (int i = 0; i < timeline.steps.size(); i++) {
      Step step = timeline.steps.get(i);
      if (step == null) {
        return label + "时间线第 " + (i + 1) + " 步为空";
      }
      if (step.delay < 0) {
        return label + "时间线第 " + (i + 1) + " 步延时不能为负数";
      }
      if (step.bot < 0 || step.bot >= botCount) {
        return label + "时间线第 " + (i + 1) + " 步 bot 下标越界 (0-" + (botCount - 1) + ")";
      }
      if (step.commands == null || step.commands.isEmpty()) {
        return label + "时间线第 " + (i + 1) + " 步没有指令";
      }
      for (String command : step.commands) {
        String error = validateTimelineCommand(command);
        if (error != null) {
          return label + "第 " + (i + 1) + " 步: " + error;
        }
      }
    }
    return null;
  }

  /**
   * Rejects timeline commands that are always privileged, so a malicious machine cannot be saved
   * with an obvious privilege-escalation script. Actual enforcement happens at execution time:
   * every command runs AS THE TRIGGERING PLAYER, so it can never do more than that player could
   * do by hand.
   */
  private static String validateTimelineCommand(String command) {
    if (command == null || command.isBlank()) {
      return "指令为空";
    }
    String cmd = command.trim();
    if (cmd.startsWith("/")) {
      cmd = cmd.substring(1);
    }
    String lower = cmd.toLowerCase();
    String root = lower.split("\\s+", 2)[0];
    // Always-privileged commands that must never run from a machine script.
    switch (root) {
      case "op", "deop", "stop", "save-all", "save-off", "save-on",
          "ban", "ban-ip", "pardon", "pardon-ip", "kick",
          "whitelist", "seed", "kill", "gamemode", "give", "effect",
          "execute", "function", "run", "forceload", "datapack", "reload",
          "publish", "debug", "perf", "tick", "jfr", "msg" -> {
        return "指令「" + root + "」不允许写入机器脚本";
      }
      default -> {
        // Everything else is allowed at save time; execution-time player permission is the real
        // gate.
      }
    }
    return null;
  }

  // ── Helpers ──────────────────────────────────────────────────────

  /**
   * Parses the {@code modeIds} JSON array of a setModes action into a string list.
   */
  private static List<String> parseModeIds(JsonObject action) {
    List<String> result = new ArrayList<>();
    if (action.has("modeIds") && action.get("modeIds").isJsonArray()) {
      for (JsonElement element : action.getAsJsonArray("modeIds")) {
        result.add(element.getAsString());
      }
    }
    return result;
  }

  /**
   * Whether a mode can start: its boot process must have at least one step whose first command is
   * a fake player spawn command.
   */
  public static boolean validModeStart(ModeData mode) {
    return mode.onTimeline != null && !mode.onTimeline.steps.isEmpty()
        && mode.onTimeline.steps.get(0).commands != null
        && !mode.onTimeline.steps.get(0).commands.isEmpty()
        && isSpawnCommand(mode.onTimeline.steps.get(0).commands.get(0));
  }

  /**
   * Whether a command is a fake player spawn command ({@code /player <name> spawn ...}).
   */
  private static boolean isSpawnCommand(String command) {
    if (command == null) {
      return false;
    }
    String cmd = command.trim().toLowerCase();
    if (cmd.startsWith("/")) {
      cmd = cmd.substring(1);
    }
    return cmd.startsWith("player ") && cmd.contains(" spawn");
  }

  /**
   * Serializes a property value to its string name using a raw property reference (generic
   * captures make the typed call impossible here).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String serializePropertyValue(Property property, Object value) {
    return property.getName((Comparable) value);
  }

  private static String getString(JsonObject object, String key) {
    return object.has(key) ? object.get(key).getAsString() : "";
  }

  private static void sendMessage(ServerPlayer player, String message) {
    player.sendSystemMessage(Component.literal(message));
  }

  /**
   * Broadcasts a system message to all online players (used by chain events that have no single
   * trigger player).
   */
  public static void broadcastSystem(String message) {
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server != null && server.getPlayerList() != null) {
      server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
  }

  /**
   * A hard edit lock entry: who holds it and when it was acquired (for TTL expiry).
   */
  private record EditLock(String editor, long acquiredAt) {
  }
}

package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import com.remrin.server.config.MachineConfig.MachineData;
import com.remrin.server.config.MachineConfig.ModeData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/**
 * Sequenced multi-mode orchestration for one machine.
 * <p>
 * When several modes are selected at once, a single chain is built per machine: the selected
 * running modes are shut down first (in the configured stop order, each next stop waits until the
 * previous mode's shutdown process COMPLETED plus the configured interval), then the selected
 * stopped modes are booted (start order, each next boot waits until the previous mode's boot
 * process COMPLETED plus the configured interval).
 * <p>
 * Single-select replacement: when a start step launches a single-select mode while another
 * single-select mode is still running, the old mode's shutdown is queued ahead and the new mode's
 * start step waits for it plus (startInterval + stopInterval).
 * <p>
 * Detection: every start step re-validates the mode's detection at its actual launch moment; an
 * abnormal or invalid mode is skipped (a message is broadcast) and the chain continues.
 */
public final class MachineModeChain {

  /** machine id -> chain state. */
  private static final Map<String, Chain> chains = new HashMap<>();

  private MachineModeChain() {
  }

  private enum Action {
    START, STOP
  }

  private record Step(String modeId, Action action, int waitAfterPrevious) {
  }

  private static final class Chain {

    final Deque<Step> steps = new ArrayDeque<>();
    /** Tick countdown until the head step executes (after the previous step completed). */
    int waitTicks = 0;
    /** Mode id whose process is currently running for this chain (null when idle). */
    String activeModeId = null;
    /** Set once the active mode's process has completed; the chain then waits and advances. */
    boolean activeCompleted = false;
    /** Player who triggered the mode changes; every step's commands run with their permissions. */
    final String triggerPlayer;

    Chain(String triggerPlayer) {
      this.triggerPlayer = triggerPlayer;
    }
  }

  /**
   * Builds the chain for a 确定 action on a machine from the sets of mode ids to start and to
   * stop. Any existing chain for the machine is replaced. The triggering player is carried into
   * the chain so every step's commands run with that player's permissions.
   * <p>
   * Step order: the SINGLE-select logic runs entirely first (its stops then its starts, in the
   * configured stop/start order with the configured intervals), then the MULTI-select logic (its
   * stops then its starts). This guarantees the configured sequence/timing is respected exactly.
   */
  public static void buildChains(MachineData machine, List<String> startIds,
      List<String> stopIds, String triggerPlayer) {
    int startInterval = Math.max(0, machine.modeInterval);
    int stopInterval = machine.stopFollowsStart ? startInterval
        : Math.max(0, machine.stopModeInterval);

    // Split the affected modes into single-select and multi-select groups.
    List<String> singleStop = new ArrayList<>();
    List<String> singleStart = new ArrayList<>();
    List<String> multiStop = new ArrayList<>();
    List<String> multiStart = new ArrayList<>();
    for (String id : stopIds) {
      ModeData mode = findMode(machine, id);
      if (mode != null && mode.singleSelect) {
        singleStop.add(id);
      } else {
        multiStop.add(id);
      }
    }
    for (String id : startIds) {
      ModeData mode = findMode(machine, id);
      if (mode != null && mode.singleSelect) {
        singleStart.add(id);
      } else {
        multiStart.add(id);
      }
    }
    List<String> singleStopOrder = orderedIds(machine, singleStop, machine.stopFollowsStart);
    List<String> singleStartOrder = orderedIds(machine, singleStart, false);
    List<String> multiStopOrder = orderedIds(machine, multiStop, machine.stopFollowsStart);
    List<String> multiStartOrder = orderedIds(machine, multiStart, false);

    Chain chain = new Chain(triggerPlayer);

    // Single-select replacement: a running single-select mode not being stopped gets shut down
    // before the first selected single-select mode starts.
    Map<String, String> replacementOf = new HashMap<>();
    String firstStartingSingle = null;
    for (String modeId : singleStartOrder) {
      ModeData mode = findMode(machine, modeId);
      if (mode != null && mode.singleSelect) {
        firstStartingSingle = modeId;
        break;
      }
    }
    if (firstStartingSingle != null) {
      for (ModeData mode : machine.modes) {
        if (mode.singleSelect && !startIds.contains(mode.id) && !stopIds.contains(mode.id)
            && MachineScheduler.isModeRunning(machine.id, mode.id)) {
          if (!singleStopOrder.contains(mode.id)) {
            singleStopOrder.add(0, mode.id);
          }
          replacementOf.put(firstStartingSingle, mode.id);
        }
      }
    }

    boolean first = true;
    // 1) single-select stops
    for (String modeId : singleStopOrder) {
      chain.steps.add(new Step(modeId, Action.STOP, first ? 0 : stopInterval));
      first = false;
    }
    // 2) single-select starts
    for (String modeId : singleStartOrder) {
      int wait = first ? 0 : startInterval;
      if (replacementOf.containsKey(modeId)) {
        wait = startInterval + stopInterval;
      }
      chain.steps.add(new Step(modeId, Action.START, wait));
      first = false;
    }
    // 3) multi-select stops (all shutdowns before any multi-select boot)
    for (String modeId : multiStopOrder) {
      chain.steps.add(new Step(modeId, Action.STOP, first ? 0 : stopInterval));
      first = false;
    }
    // 4) multi-select starts
    for (String modeId : multiStartOrder) {
      chain.steps.add(new Step(modeId, Action.START, first ? 0 : startInterval));
      first = false;
    }

    if (chain.steps.isEmpty()) {
      chains.remove(machine.id);
      return;
    }
    chains.put(machine.id, chain);
  }

  /**
   * Returns the selected mode ids in the configured order (stop order when {@code forStops}).
   * Selected modes missing from the preset keep their natural list order, appended at the end.
   */
  private static List<String> orderedIds(MachineData machine, List<String> selected,
      boolean forStops) {
    List<String> preset = forStops ? machine.stopModeOrder : machine.modeOrder;
    List<String> result = new ArrayList<>();
    if (preset != null) {
      for (String id : preset) {
        if (selected.contains(id) && !result.contains(id)) {
          result.add(id);
        }
      }
    }
    if (machine.modes != null) {
      for (ModeData mode : machine.modes) {
        if (selected.contains(mode.id) && !result.contains(mode.id)) {
          result.add(mode.id);
        }
      }
    }
    return result;
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
   * Advances all chains once per server tick.
   */
  public static void tick(MinecraftServer server) {
    if (chains.isEmpty()) {
      return;
    }
    Iterator<Map.Entry<String, Chain>> it = chains.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Chain> entry = it.next();
      String machineId = entry.getKey();
      MachineData machine = MachineConfig.getMachine(machineId);
      Chain chain = entry.getValue();
      if (machine == null || chain.steps.isEmpty()) {
        it.remove();
        continue;
      }
      if (chain.activeModeId != null) {
        if (!chain.activeCompleted) {
          continue; // wait for the active process to finish
        }
        chain.activeModeId = null;
        chain.activeCompleted = false;
        Step next = chain.steps.peek();
        if (next == null) {
          // The whole sequenced switch finished: notify the triggering player.
          it.remove();
          MachineManager.onModeSwitchFinished(machine, chain.triggerPlayer);
          continue;
        }
        chain.waitTicks = next.waitAfterPrevious;
      }
      if (chain.waitTicks > 0) {
        chain.waitTicks--;
        continue;
      }
      Step step = chain.steps.poll();
      if (step == null) {
        // The whole sequenced switch finished: notify the triggering player.
        it.remove();
        MachineManager.onModeSwitchFinished(machine, chain.triggerPlayer);
        continue;
      }
      ModeData mode = findMode(machine, step.modeId);
      if (mode == null) {
        MachineMod.LOGGER.info("Chain: mode '{}' of machine '{}' no longer exists, skipping",
            step.modeId, machineId);
        continue; // deleted mode: skip, chain advances next tick
      }
      if (step.action == Action.STOP) {
        MachineScheduler.stopModeWithShutdown(machine, mode, chain.triggerPlayer);
        MachineMod.LOGGER.info("Chain: stopping mode '{}' of machine '{}'",
            step.modeId, machineId);
      } else if (!launchStart(server, machine, mode, chain.triggerPlayer)) {
        continue; // rejected mode: skip, chain advances next tick
      }
      // The chain advances when the mode's PROCESS completes (boot or shutdown runtime finished).
      // The persistent ON flag (isModeRunning) is not used here: it stays set after the on-timeline
      // finishes, which would otherwise block the chain forever.
      if (MachineScheduler.isModeProcessRunning(machine.id, mode.id)) {
        chain.activeModeId = mode.id;
        chain.activeCompleted = false;
      } else {
        // No process actually runs (e.g. mode without shutdown steps): complete immediately
        chain.activeModeId = null;
        chain.activeCompleted = true;
      }
    }
  }

  /**
   * Launches a mode's boot process after validating its detection, start config and the
   * triggering player's permission for the first command. Returns false when the mode cannot be
   * started (a message is broadcast).
   */
  private static boolean launchStart(MinecraftServer server, MachineData machine, ModeData mode,
      String triggerPlayer) {
    if (mode.detection != null && mode.detection.enabled) {
      MachineDetector.DetectionResult detection =
          MachineDetector.evaluateDetection(mode.detection, server, true);
      if (detection.state() == MachineDetector.MachineState.ABNORMAL) {
        MachineManager.broadcastSystem("模式「" + mode.name + "」检测异常，已跳过启动："
            + (detection.reason() != null ? detection.reason() : "检测方块异常"));
        return false;
      }
    }
    if (!MachineManager.validModeStart(mode)) {
      MachineManager.broadcastSystem("模式「" + mode.name
          + "」开启流程第一个指令必须是假人spawn指令，已跳过启动");
      return false;
    }
    // Pre-check the first command against the TRIGGERING PLAYER's own permissions, mirroring the
    // switch boot check: a mode whose first command would be rejected for the player must not
    // silently run a broken process.
    if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
      String firstCommand = mode.onTimeline.steps.get(0).commands.get(0);
      String permissionError = MachineScheduler.checkCommandPermission(firstCommand, triggerPlayer);
      if (permissionError != null) {
        MachineManager.broadcastSystem("模式「" + mode.name + "」启动失败：无权限执行指令（"
            + permissionError + "）");
        return false;
      }
    }
    MachineScheduler.startMode(machine, mode, triggerPlayer);
    return true;
  }

  /**
   * Called by the scheduler when a mode's process (boot or shutdown timeline) finishes.
   */
  public static void onModeProcessFinished(String machineId, String modeId) {
    Chain chain = chains.get(machineId);
    if (chain != null && modeId.equals(chain.activeModeId)) {
      chain.activeCompleted = true;
    }
  }

  /**
   * Whether a machine currently has a mode chain queued (i.e. a sequenced switch is in progress).
   * Used to reject further mode switches while the chain is running.
   */
  public static boolean isActive(String machineId) {
    return chains.containsKey(machineId);
  }

  /**
   * Drops all chains of a machine (used when the machine is edited or deleted).
   */
  public static void invalidate(String machineId) {
    chains.remove(machineId);
  }
}

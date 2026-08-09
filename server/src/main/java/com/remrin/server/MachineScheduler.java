package com.remrin.server;

import com.remrin.server.MachineDetector.MachineState;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.config.MachineConfig.MachineData;
import com.remrin.server.config.MachineConfig.ModeData;
import com.remrin.server.config.MachineConfig.Step;
import com.remrin.server.config.MachineConfig.Timeline;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Server-side timeline scheduler. A machine can run several timelines concurrently: the switch
 * process (key = machine id) and one timeline per active mode (key = {@code machineId#modeId}).
 * Each running timeline keeps its own cursor and is advanced once per server tick.
 * <p>
 * Command spacing: every command is executed through a per-timeline FIFO queue drained one command
 * per tick, so any two commands of the same timeline are at least 1 tick apart. In addition, a
 * per-bot gate shared by all of a machine's timelines ensures the same bot never receives two
 * commands in the same server tick, even when the switch process and several modes run at once.
 * <p>
 * Delay semantics: {@code delay} on a step is the number of ticks to wait after the previous step
 * executed. Loop semantics: {@code loopCount = 0} runs once, {@code -1} loops forever, a positive
 * value runs the timeline that many times and then stops.
 * <p>
 * Commands are executed AS THE TRIGGERING PLAYER — whoever clicked the switch / mode button is the
 * one issuing the commands, exactly like executing them from the mod's own UI. This means a machine
 * script can never do more than the triggering player could do by hand: no privilege escalation is
 * possible. When the triggering player is offline (e.g. the machine is turned off by a detection
 * block), the script runs with NO permissions.
 */
public final class MachineScheduler {

  /** Main switch runtimes: machine id -> runtime. Mode runtimes are keyed {@code id#modeId}. */
  private static final Map<String, Runtime> runtimes = new HashMap<>();
  /**
   * Built-in grace period (ticks) after a step that spawned a fake player, before the next step
   * may run. Spawning a fresh bot can take a while (the entity has to appear and register), so
   * follow-up commands must wait — otherwise look/use/attack fire before the bot exists.
   */
  private static final int SPAWN_GRACE_TICKS = 60;
  /**
   * Absolute timeout (ticks) for waiting on a spawned bot to come online. After the fixed
   * {@link #SPAWN_GRACE_TICKS} the scheduler additionally waits until the bot is actually present
   * in the player list — the very first spawn after a server restart can take several seconds
   * (cold chunk / fake-player system), far longer than the grace period, so a fixed delay alone
   * lets the follow-up commands fire before the bot exists (carpet then no-ops them). This timeout
   * just prevents the timeline from hanging forever when a spawn silently fails.
   */
  private static final int AWAIT_SPAWN_TIMEOUT = 300;
  /**
   * Per-machine per-bot gate: machine id -> (bot name -> last server tick that bot was used).
   * Shared by all timelines of a machine so the same bot never gets two commands in one tick.
   */
  private static final Map<String, Map<String, Integer>> botLastTick = new HashMap<>();
  /**
   * Last evaluated detection-block state of every detection-enabled mode
   * (machine id#mode id -> MachineState). Refreshed once per second by
   * {@code MachineManager.tickStates} and on every sync/build. A mode is considered "switched on"
   * when its detection block is ON — the block IS the switch. The runtime map, by contrast, only
   * exists while a boot/shutdown process is executing.
   */
  private static final Map<String, MachineState> detectedModeState = new HashMap<>();

  private MachineScheduler() {
  }

  private static String modeKey(String machineId, String modeId) {
    return machineId + "#" + modeId;
  }

  /**
   * Records the latest detection result of a mode (called by the state ticker and the sync
   * builder). {@code DISABLED} clears the entry (mode without detection never counts as ON).
   */
  public static void updateDetectedModeState(String machineId, String modeId,
      MachineState state) {
    String key = modeKey(machineId, modeId);
    if (state == MachineState.DISABLED) {
      detectedModeState.remove(key);
    } else {
      detectedModeState.put(key, state);
    }
  }

  /**
   * Called on every server tick; advances each running timeline's cursor and drains one pending
   * command per timeline.
   */
  public static void tick(MinecraftServer server) {
    if (runtimes.isEmpty()) {
      return;
    }
    int tick = server.getTickCount();
    Iterator<Map.Entry<String, Runtime>> it = runtimes.entrySet().iterator();
    while (it.hasNext()) {
      Runtime runtime = it.next().getValue();
      if (!runtime.pending.isEmpty()) {
        PendingCommand pending = runtime.pending.peek();
        Map<String, Integer> gate = botLastTick.get(runtime.machine.id);
        int lastUsed = gate != null ? gate.getOrDefault(pending.botName, -1) : -1;
        if (lastUsed == tick) {
          continue; // same bot already acted this tick in another timeline; retry next tick
        }
        runtime.pending.remove();
        CommandResult result = executeCommand(server, pending.command, runtime.triggerPlayer);
        if (result == CommandResult.REJECTED) {
          // A rejected command (permission too low / syntax error) aborts the boot / shutdown
          // immediately: tell the triggering player right away instead of continuing a broken
          // process. NO_OP (dispatch returned 0 without an exception) is NOT a failure — carpet
          // returns 0 for valid commands targeting a missing fake player (e.g. "stop" after a
          // kill), so it must not abort the timeline.
          runtime.hadFailures = true;
          runtime.failureMessage = pending.command;
          runtime.failedReason = "无权限执行该指令";
          int hashIndex = runtime.key.indexOf('#');
          if (hashIndex >= 0) {
            MachineManager.onModeCommandFailed(runtime.machine,
                runtime.key.substring(hashIndex + 1), runtime.isOffTimeline,
                runtime.triggerPlayer, pending.command, runtime.failedReason);
          } else {
            MachineManager.onSwitchCommandFailed(runtime.machine, runtime.isOffTimeline,
                runtime.triggerPlayer, pending.command, runtime.failedReason);
          }
          // Stop the rest of the timeline: no point continuing a broken process.
          runtime.finished = true;
          runtime.pending.clear();
          continue;
        }
        botLastTick.computeIfAbsent(runtime.machine.id, k -> new HashMap<>())
            .put(pending.botName, tick);
        continue; // one command per tick per timeline
      }
      if (runtime.finished) {
        it.remove();
        cleanupIfEmpty(runtime.machine.id);
        int hashIndex = runtime.key.indexOf('#');
        if (hashIndex >= 0) {
          String machineId = runtime.key.substring(0, hashIndex);
          String modeId = runtime.key.substring(hashIndex + 1);
          // The mode's switch process finished: the mode now enters its switch cooldown (its
          // switchInterval ticks, falling back to the machine's) BEFORE it can be toggled again.
          // The lock window therefore starts when the process completes, not at click time.
          MachineScheduler.recordModeSwitchTick(machineId, modeId, tick);
          MachineModeChain.onModeProcessFinished(machineId, modeId);
          // A single change (no active chain) that finished without failures: tell the player the
          // switch is complete. Sequenced chains report once the whole chain finishes instead.
          if (!runtime.hadFailures && !MachineModeChain.isActive(machineId)) {
            MachineManager.onModeSwitchFinished(runtime.machine, runtime.triggerPlayer);
          }
        } else {
          // Switch timeline (boot or shutdown) finished without failures: report success to the
          // manager so it can notify the triggering player AFTER the whole process completed.
          if (!runtime.hadFailures) {
            MachineManager.onSwitchTimelineFinished(runtime.machine,
                runtime.isOffTimeline, runtime.triggerPlayer);
          }
        }
        MachineManager.onMachineFinished(runtime.machine.id);
        continue;
      }
      if (runtime.waitTicks > 0) {
        runtime.waitTicks--;
        continue;
      }
      // After the fixed grace period, wait until a just-spawned bot is actually online before the
      // next step runs. Without this the very first spawn after a restart (cold chunk / fake-player
      // system) can still be registering when look/use/attack fire — carpet then no-ops them and
      // the action silently never happens.
      if (runtime.awaitBot != null) {
        if (server.getPlayerList().getPlayerByName(runtime.awaitBot) != null) {
          runtime.awaitBot = null; // online: proceed
        } else if (--runtime.awaitTimeout <= 0) {
          runtime.awaitBot = null; // give up after the absolute timeout, don't hang forever
        }
        continue;
      }
      scheduleStep(server, runtime);
    }
  }

  /**
   * Queues the current step's commands (resolved) and advances the cursor. When the loop budget is
   * exhausted or the timeline has no steps, the {@code finished} flag is set so the runtime is
   * removed once its pending queue drains.
   */
  private static void scheduleStep(MinecraftServer server, Runtime runtime) {
    List<Step> steps = runtime.timeline.steps;
    if (steps.isEmpty()) {
      runtime.finished = true;
      return;
    }
    Step step = steps.get(runtime.stepIndex);
    String botName = step.bot >= 0 && step.bot < runtime.machine.bots.size()
        ? runtime.machine.bots.get(step.bot)
        : "bot" + step.bot;
    boolean stepHasSpawn = false;
    for (String command : step.commands) {
      if (command == null || command.isBlank()) {
        continue;
      }
      String resolved = command
          .replace("{bot}", botName)
          .replace("{player}", runtime.triggerPlayer != null ? runtime.triggerPlayer : "");
      if (isSpawnCommand(resolved)) {
        stepHasSpawn = true;
      }
      runtime.pending.add(new PendingCommand(ensureSpawnFacing(resolved), botName));
    }
    runtime.stepIndex++;
    if (runtime.stepIndex >= steps.size()) {
      runtime.completedLoops++;
      boolean finished = runtime.timeline.loopCount == 0
          || (runtime.timeline.loopCount > 0
              && runtime.completedLoops >= runtime.timeline.loopCount);
      if (finished) {
        runtime.finished = true;
        return;
      }
      runtime.stepIndex = 0;
      runtime.waitTicks = Math.max(0, steps.get(0).delay);
    } else {
      runtime.waitTicks = Math.max(0, steps.get(runtime.stepIndex).delay);
    }
    // Fake player spawns can take a while to register (a fresh bot entity has to appear), so a
    // step that spawned a bot always waits at least SPAWN_GRACE_TICKS before the next step runs.
    // Afterwards it additionally waits until the bot is online (see tick()), so follow-up commands
    // never fire before the bot exists — the fixed delay alone is not enough for the first spawn
    // after a server restart.
    if (stepHasSpawn) {
      runtime.waitTicks = Math.max(runtime.waitTicks, SPAWN_GRACE_TICKS);
      runtime.awaitBot = botName;
      runtime.awaitTimeout = AWAIT_SPAWN_TIMEOUT;
    }
  }

  /**
   * Starts the switch process for a machine (boot or shutdown timeline), stopping any previous
   * switch runtime of the same machine.
   */
  public static void start(MachineData machine, Timeline timeline, String triggerPlayer,
      boolean isOffTimeline) {
    stop(machine.id);
    if (timeline == null || timeline.steps.isEmpty()) {
      return;
    }
    Runtime runtime = new Runtime(machine.id, machine, timeline, triggerPlayer, isOffTimeline);
    runtime.waitTicks = Math.max(0, timeline.steps.get(0).delay);
    runtimes.put(machine.id, runtime);
  }

  /**
   * Starts a machine mode's boot process. The mode runs independently from the switch and other
   * modes; any existing run of the same mode is stopped first. The triggering player is carried
   * into the runtime so the mode's commands run with THAT player's permissions (same model as the
   * switch process) — without it the scripts would run with NO permissions and every command would
   * be rejected.
   */
  public static void startMode(MachineData machine, ModeData mode, String triggerPlayer) {
    String key = modeKey(machine.id, mode.id);
    stopMode(machine.id, mode.id);
    if (mode.onTimeline == null || mode.onTimeline.steps.isEmpty()) {
      MachineMod.LOGGER.warn("startMode '{}' of machine '{}': empty onTimeline", mode.id,
          machine.id);
      return;
    }
    Runtime runtime = new Runtime(key, machine, mode.onTimeline, triggerPlayer, false);
    runtime.waitTicks = Math.max(0, mode.onTimeline.steps.get(0).delay);
    runtimes.put(key, runtime);
    MachineMod.LOGGER.info("startMode '{}' of machine '{}' (steps={})",
        mode.id, machine.id, mode.onTimeline.steps.size());
  }

  /**
   * Stops a machine mode's boot process and starts its shutdown process instead (if configured).
   * When the mode has no shutdown process, the mode is simply stopped. The triggering player is
   * carried into the runtime (see {@link #startMode}).
   */
  public static void stopModeWithShutdown(MachineData machine, ModeData mode, String triggerPlayer) {
    String key = modeKey(machine.id, mode.id);
    stopMode(machine.id, mode.id);
    if (mode.offTimeline == null || mode.offTimeline.steps.isEmpty()) {
      return;
    }
    Runtime runtime = new Runtime(key, machine, mode.offTimeline, triggerPlayer, false);
    runtime.waitTicks = Math.max(0, mode.offTimeline.steps.get(0).delay);
    runtimes.put(key, runtime);
  }

  /**
   * Stops the switch process of a machine (does not run the shutdown process — that is the
   * caller's responsibility).
   */
  public static void stop(String machineId) {
    if (runtimes.remove(machineId) != null) {
      cleanupIfEmpty(machineId);
    }
  }

  public static void stopMode(String machineId, String modeId) {
    String key = modeKey(machineId, modeId);
    if (runtimes.remove(key) != null) {
      cleanupIfEmpty(machineId);
    }
  }

  /**
   * Stops all timelines (switch + modes) of a machine. Used when a machine is edited or deleted.
   */
  public static void invalidate(String machineId) {
    boolean changed = false;
    Iterator<Map.Entry<String, Runtime>> it = runtimes.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Runtime> entry = it.next();
      if (entry.getValue().machine.id.equals(machineId)) {
        it.remove();
        changed = true;
      }
    }
    if (detectedModeState.keySet().removeIf(k -> k.startsWith(machineId + "#"))) {
      changed = true;
    }
    if (changed) {
      cleanupIfEmpty(machineId);
    }
  }

  public static boolean isRunning(String machineId) {
    return runtimes.containsKey(machineId);
  }

  /**
   * Whether a mode is currently switched ON — defined by its detection block state (ON = the
   * block reports the configured on-values). The block is the switch; the runtime map only tracks
   * an executing boot/shutdown process.
   */
  public static boolean isModeRunning(String machineId, String modeId) {
    return detectedModeState.getOrDefault(modeKey(machineId, modeId),
        MachineState.DISABLED) == MachineState.ON;
  }

  /**
   * Whether a mode's boot/shutdown PROCESS is currently executing (a runtime exists). Used by the
   * mode chain to know when the active mode's process has completed.
   */
  public static boolean isModeProcessRunning(String machineId, String modeId) {
    return runtimes.containsKey(modeKey(machineId, modeId));
  }

  /**
   * The server tick when the machine's switch was last toggled (boot or shutdown), or -1.
   */
  private static final Map<String, Integer> lastSwitchTick = new HashMap<>();

  /**
   * Records the switch action tick for a machine (used by the switch lock window).
   */
  public static void recordSwitchTick(String machineId, int tick) {
    lastSwitchTick.put(machineId, tick);
  }

  /**
   * Remaining lock ticks of the machine's switch lock window, or 0 when free to toggle.
   */
  public static int switchLockRemaining(String machineId, int tick) {
    Integer last = lastSwitchTick.get(machineId);
    if (last == null) {
      return 0;
    }
    MachineData machine = MachineConfig.getMachine(machineId);
    int interval = machine != null ? Math.max(0, machine.switchInterval) : 0;
    return Math.max(0, interval - (tick - last));
  }

  /**
   * The server tick when a mode was last toggled, or -1.
   */
  private static final Map<String, Integer> lastModeSwitchTick = new HashMap<>();

  /**
   * Records the switch action tick for a mode (used by the mode lock window).
   */
  public static void recordModeSwitchTick(String machineId, String modeId, int tick) {
    lastModeSwitchTick.put(modeKey(machineId, modeId), tick);
  }

  /**
   * Remaining lock ticks of a mode's lock window (its own interval, falling back to the machine's
   * interval), or 0 when free to toggle.
   */
  public static int modeLockRemaining(String machineId, String modeId, int tick) {
    Integer last = lastModeSwitchTick.get(modeKey(machineId, modeId));
    if (last == null) {
      return 0;
    }
    MachineData machine = MachineConfig.getMachine(machineId);
    int interval = 0;
    if (machine != null) {
      interval = machine.switchInterval;
      for (ModeData mode : machine.modes) {
        if (mode.id != null && mode.id.equals(modeId)) {
          if (mode.switchInterval > 0) {
            interval = mode.switchInterval;
          }
          break;
        }
      }
    }
    return Math.max(0, interval - (tick - last));
  }

  /**
   * Clears the recorded switch ticks of a machine (used when it is edited or deleted).
   */
  public static void clearSwitchTicks(String machineId) {
    lastSwitchTick.remove(machineId);
    lastModeSwitchTick.entrySet().removeIf(e -> e.getKey().startsWith(machineId + "#"));
  }

  /**
   * Removes the per-bot gate of a machine once no timeline of that machine is running anymore.
   */
  private static void cleanupIfEmpty(String machineId) {
    boolean anyRunning = false;
    for (Runtime runtime : runtimes.values()) {
      if (runtime.machine.id.equals(machineId)) {
        anyRunning = true;
        break;
      }
    }
    if (!anyRunning) {
      botLastTick.remove(machineId);
    }
  }

  /**
   * Carpet 26.2's {@code /player <name> spawn ...} syntax REQUIRES the {@code facing} argument —
   * without it the command fails to parse (and the error is suppressed in console execution, so
   * the machine appears to "do nothing"). Appends {@code facing 0 0} to spawn commands that lack
   * it, transparently fixing older saved commands.
   */
  private static String ensureSpawnFacing(String command) {
    String lower = command.toLowerCase();
    if (!lower.startsWith("/player ") || !lower.contains(" spawn ")) {
      return command;
    }
    if (lower.contains(" facing")) {
      return command;
    }
    int inIndex = lower.indexOf(" in ");
    if (inIndex >= 0) {
      // substring(inIndex) keeps the original space before "in"; substring(0, inIndex) has no
      // trailing space — together they yield exactly one space around "facing 0 0".
      return command.substring(0, inIndex) + " facing 0 0" + command.substring(inIndex);
    }
    return command + " facing 0 0";
  }

  /**
   * Checks whether a machine command can be executed with the configured permission level, by
   * parsing it against the dispatcher using a source that carries exactly that permission set.
   * Returns {@code null} when the command is permitted, otherwise a short reason (permission too
   * low / unknown command / argument error).
   * <p>
   * Used to surface a clear "boot failed" message instead of silently starting a machine whose
   * first command would be rejected by the permission gate.
   */
  public static String checkCommandPermission(String command, String triggerPlayer) {
    MinecraftServer server = MachineMod.getCurrentServer();
    if (server == null) {
      return "服务器未运行";
    }
    if (triggerPlayer == null || triggerPlayer.isEmpty()) {
      return "无触发玩家，无法确认权限";
    }
    ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
    if (player == null) {
      return "触发玩家不在线";
    }
    // parse never throws; permission failures are recorded in ParseResults.getExceptions().
    com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack> result =
        server.getCommands().getDispatcher().parse(stripLeadingSlash(command),
            player.createCommandSourceStack());
    if (result.getExceptions().isEmpty()) {
      return null;
    }
    return "你的权限不足以执行该指令";
  }

  /**
   * Outcome of a single machine command dispatch.
   */
  private enum CommandResult {
    SUCCESS,
    REJECTED,
    NO_OP
  }

  private static CommandResult executeCommand(MinecraftServer server, String command,
      String triggerPlayer) {
    // Machine scripts run as the TRIGGERING PLAYER, exactly like the player issuing the commands
    // themselves from the mod's UI (custom command tab). This means the script can never do more
    // than the player could do by hand — no privilege escalation is possible. When the triggering
    // player is offline (e.g. the machine is turned off by a detection block), the script runs
    // with NO permissions at all, so it cannot do anything privileged without a live player.
    CommandSourceStack source;
    if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
      source = player != null
          ? player.createCommandSourceStack().withSuppressedOutput()
          : server.createCommandSourceStack()
              .withPermission(PermissionSet.NO_PERMISSIONS).withSuppressedOutput();
    } else {
      source = server.createCommandSourceStack()
          .withPermission(PermissionSet.NO_PERMISSIONS).withSuppressedOutput();
    }
    try {
      // dispatcher.execute throws CommandSyntaxException when the command is rejected (permission
      // level too low, unknown command, argument error) — without this, rejected commands would be
      // silently swallowed and the permission gate would be invisible in the logs.
      int result = server.getCommands().getDispatcher().execute(
          server.getCommands().getDispatcher().parse(stripLeadingSlash(command), source));
      if (result > 0) {
        MachineMod.LOGGER.info("Machine command executed: {}", command);
        return CommandResult.SUCCESS;
      }
      MachineMod.LOGGER.warn("Machine command no-op (no permission or invalid): {}", command);
      return CommandResult.NO_OP;
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      MachineMod.LOGGER.warn(
          "Machine command REJECTED (no permission or invalid command): {} - {}",
          command, e.getMessage());
      return CommandResult.REJECTED;
    } catch (Exception e) {
      MachineMod.LOGGER.warn("Failed to execute machine command '{}': {}", command, e.getMessage());
      return CommandResult.REJECTED;
    }
  }

  /**
   * Removes a leading slash: {@code CommandDispatcher.parse} expects the command WITHOUT the "/"
   * prefix (the slash is a client-side convention; RCON and the player chat go through
   * {@code performPrefixedCommand} which strips it internally).
   */
  private static String stripLeadingSlash(String command) {
    if (command != null && command.startsWith("/")) {
      return command.substring(1);
    }
    return command;
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
   * Per-timeline runtime state: which timeline is running, the cursor position, the loop budget
   * and the pending command queue (drained one command per tick).
   */
  private static final class Runtime {

    final String key;
    final MachineData machine;
    final Timeline timeline;
    final String triggerPlayer;
    /** Whether this switch runtime runs the shutdown (off) timeline instead of the boot one. */
    final boolean isOffTimeline;
    final Deque<PendingCommand> pending = new ArrayDeque<>();
    int stepIndex = 0;
    int waitTicks = 0;
    int completedLoops = 0;
    boolean finished = false;
    boolean hadFailures = false;
    String failureMessage = null;
    String failedReason = null;
    /** Bot that a spawn step is waiting on to come online before the next step runs (null = none). */
    String awaitBot = null;
    /** Remaining ticks to wait for {@link #awaitBot} before giving up. */
    int awaitTimeout = 0;

    Runtime(String key, MachineData machine, Timeline timeline, String triggerPlayer,
        boolean isOffTimeline) {
      this.key = key;
      this.machine = machine;
      this.timeline = timeline;
      this.triggerPlayer = triggerPlayer;
      this.isOffTimeline = isOffTimeline;
    }
  }

  private record PendingCommand(String command, String botName) {
  }
}

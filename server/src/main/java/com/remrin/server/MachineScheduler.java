package com.remrin.server;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.remrin.server.config.MachineConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

public final class MachineScheduler {
   private static final Map<String, MachineScheduler.Runtime> runtimes = new HashMap<>();
   private static final int SPAWN_GRACE_TICKS = 60;
   private static final int AWAIT_SPAWN_TIMEOUT = 300;
   private static final Map<String, Map<String, Integer>> botLastTick = new HashMap<>();
   private static final Map<String, MachineDetector.MachineState> detectedModeState = new HashMap<>();
   private static final Map<String, Integer> lastSwitchTick = new HashMap<>();
   private static final Map<String, Integer> lastModeSwitchTick = new HashMap<>();

   /** 测试用：命令执行时间线（环形缓冲，记录 tick 级执行顺序，/cgtest timeline 读取）。 */
   private static final java.util.ArrayDeque<String> execTrace = new java.util.ArrayDeque<>();
   private static final int EXEC_TRACE_CAP = 500;

   public static void clearExecTrace() {
      execTrace.clear();
   }

   public static List<String> dumpExecTrace() {
      return new ArrayList<>(execTrace);
   }

   private static void recordExec(String key, String command) {
      int tick = -1;
      MinecraftServer server = MachineMod.getCurrentServer();
      if (server != null) {
         tick = server.getTickCount();
      }
      execTrace.addLast("tick=" + tick + " | " + key + " | " + command);
      if (execTrace.size() > EXEC_TRACE_CAP) {
         execTrace.removeFirst();
      }
   }

   private MachineScheduler() {
   }

   private static String modeKey(String machineId, String modeId) {
      return machineId + "#" + modeId;
   }

   public static void updateDetectedModeState(String machineId, String modeId, MachineDetector.MachineState state) {
      String key = modeKey(machineId, modeId);
      if (state == MachineDetector.MachineState.DISABLED) {
         detectedModeState.remove(key);
      } else {
         detectedModeState.put(key, state);
      }
   }

   public static void tick(MinecraftServer server) {
      if (!runtimes.isEmpty()) {
         int tick = server.getTickCount();
         Iterator<Entry<String, MachineScheduler.Runtime>> it = runtimes.entrySet().iterator();

         while (it.hasNext()) {
            MachineScheduler.Runtime runtime = it.next().getValue();
            if (runtime.commandWaitTicks > 0) {
               runtime.commandWaitTicks--;
            } else if (!runtime.pending.isEmpty() && runtime.awaitBot == null) {
               MachineScheduler.PendingCommand pending = runtime.pending.peek();
               Map<String, Integer> gate = botLastTick.get(runtime.machine.id);
               int lastUsed = gate != null ? gate.getOrDefault(pending.botName, -1) : -1;
               if (lastUsed != tick) {
                  runtime.pending.remove();
                  int delayAfter = pending.delayAfter;
                  MachineScheduler.CommandResult result = executeCommand(server, pending.command, runtime);
                  if (result == MachineScheduler.CommandResult.REJECTED) {
                     runtime.hadFailures = true;
                     runtime.failureMessage = pending.command;
                     runtime.failedReason = "无权限执行该指令";
                     int hashIndex = runtime.key.indexOf(35);
                     if (hashIndex >= 0) {
                        MachineManager.onModeCommandFailed(
                           runtime.machine,
                           runtime.key.substring(hashIndex + 1),
                           runtime.isOffTimeline,
                           runtime.triggerPlayer,
                           pending.command,
                           runtime.failedReason
                        );
                     } else {
                        MachineManager.onSwitchCommandFailed(
                           runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer, pending.command, runtime.failedReason
                        );
                     }

                     runtime.finished = true;
                     runtime.pending.clear();
                  } else {
                     botLastTick.computeIfAbsent(runtime.machine.id, k -> new HashMap<>()).put(pending.botName, tick);
                     runtime.commandWaitTicks = delayAfter;
                     if (isSpawnCommand(pending.command)) {
                        runtime.awaitBot = pending.botName;
                        runtime.awaitTimeout = 300;
                     }
                  }
               }
            } else if (runtime.awaitBot != null) {
               if (server.getPlayerList().getPlayerByName(runtime.awaitBot) != null) {
                  runtime.awaitBot = null;
               } else if (--runtime.awaitTimeout <= 0) {
                  runtime.awaitBot = null;
               }

            } else if (runtime.finished) {
               it.remove();
               cleanupIfEmpty(runtime.machine.id);
               int hashIndex = runtime.key.indexOf(35);
               if (hashIndex >= 0) {
                  String machineId = runtime.key.substring(0, hashIndex);
                  String modeId = runtime.key.substring(hashIndex + 1);
                  recordModeSwitchTick(machineId, modeId, tick);
                  MachineModeChain.onModeProcessFinished(machineId, modeId);
                  if (!runtime.hadFailures && !MachineModeChain.isActive(machineId)) {
                     MachineManager.onModeSwitchFinished(runtime.machine, runtime.triggerPlayer);
                  }
               } else if (!runtime.hadFailures) {
                  MachineManager.onSwitchTimelineFinished(runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer);
               }

               MachineManager.onMachineFinished(runtime.machine.id);
            } else if (runtime.waitTicks > 0) {
               runtime.waitTicks--;
            } else if (runtime.awaitBot != null) {
               if (server.getPlayerList().getPlayerByName(runtime.awaitBot) != null) {
                  runtime.awaitBot = null;
               } else if (--runtime.awaitTimeout <= 0) {
                  runtime.awaitBot = null;
               }
            } else {
               scheduleStep(server, runtime);
            }
         }
      }
   }

   private static void scheduleStep(MinecraftServer server, MachineScheduler.Runtime runtime) {
      List<MachineScheduler.EffectiveStep> steps = runtime.steps;
      if (steps.isEmpty()) {
         runtime.finished = true;
      } else {
         MachineScheduler.EffectiveStep step = runtime.steps.get(runtime.stepIndex);
         String botName = step.bot >= 0 && step.bot < runtime.machine.bots.size() ? runtime.machine.bots.get(step.bot) : "bot" + step.bot;
         int commandDelay = step.commandDelay;
         boolean stepHasSpawn = false;
         List<String> resolvedCommands = new ArrayList<>();

         for (String command : step.commands) {
            if (command != null && !command.isBlank()) {
               String resolved = command.replace("{bot}", botName).replace("{player}", runtime.triggerPlayer != null ? runtime.triggerPlayer : "");
               if (isSpawnCommand(resolved)) {
                  stepHasSpawn = true;
               }

               resolvedCommands.add(ensureSpawnFacing(resolved));
            }
         }

         for (int i = 0; i < resolvedCommands.size(); i++) {
            int delayAfter = i < resolvedCommands.size() - 1 ? commandDelay : 0;
            runtime.pending.add(new MachineScheduler.PendingCommand(resolvedCommands.get(i), botName, delayAfter));
         }

         runtime.stepIndex++;
         if (runtime.stepIndex >= runtime.steps.size()) {
            runtime.completedLoops++;
            boolean finished = runtime.timeline.loopCount == 0 || runtime.timeline.loopCount > 0 && runtime.completedLoops >= runtime.timeline.loopCount;
            if (finished) {
               runtime.finished = true;
               return;
            }

            runtime.stepIndex = 0;
            runtime.waitTicks = Math.max(0, runtime.steps.get(0).waitBefore);
         } else {
            runtime.waitTicks = Math.max(0, runtime.steps.get(runtime.stepIndex).waitBefore);
         }

         if (stepHasSpawn) {
            runtime.waitTicks = Math.max(runtime.waitTicks, 60);
         }
      }
   }

   public static void start(MachineConfig.MachineData machine, MachineConfig.Timeline timeline, String triggerPlayer, boolean isOffTimeline) {
      stop(machine.id);
      if (timeline != null && !timeline.steps.isEmpty()) {
         MachineScheduler.Runtime runtime = new MachineScheduler.Runtime(machine.id, machine, timeline, triggerPlayer, isOffTimeline);
         if (!runtime.steps.isEmpty()) {
            runtime.waitTicks = Math.max(0, runtime.steps.get(0).waitBefore);
            runtimes.put(machine.id, runtime);
         }
      }
   }

   public static void startMode(MachineConfig.MachineData machine, MachineConfig.ModeData mode, String triggerPlayer) {
      String key = modeKey(machine.id, mode.id);
      stopMode(machine.id, mode.id);
      if (mode.onTimeline != null && !mode.onTimeline.steps.isEmpty()) {
         MachineScheduler.Runtime runtime = new MachineScheduler.Runtime(key, machine, mode.onTimeline, triggerPlayer, false);
         if (!runtime.steps.isEmpty()) {
            runtime.waitTicks = Math.max(0, runtime.steps.get(0).waitBefore);
            runtimes.put(key, runtime);
            MachineMod.LOGGER.info("startMode '{}' of machine '{}' (steps={})", new Object[]{mode.id, machine.id, mode.onTimeline.steps.size()});
         }
      } else {
         MachineMod.LOGGER.warn("startMode '{}' of machine '{}': empty onTimeline", mode.id, machine.id);
      }
   }

   public static void stopModeWithShutdown(MachineConfig.MachineData machine, MachineConfig.ModeData mode, String triggerPlayer) {
      String key = modeKey(machine.id, mode.id);
      stopMode(machine.id, mode.id);
      if (mode.offTimeline != null && !mode.offTimeline.steps.isEmpty()) {
         MachineScheduler.Runtime runtime = new MachineScheduler.Runtime(key, machine, mode.offTimeline, triggerPlayer, true);
         if (!runtime.steps.isEmpty()) {
            runtime.waitTicks = Math.max(0, runtime.steps.get(0).waitBefore);
            runtimes.put(key, runtime);
         }
      }
   }

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

   public static void invalidate(String machineId) {
      boolean changed = false;
      Iterator<Entry<String, MachineScheduler.Runtime>> it = runtimes.entrySet().iterator();

      while (it.hasNext()) {
         Entry<String, MachineScheduler.Runtime> entry = it.next();
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

   /** 是否有任何机器/模式正在执行时序。tickStates 门闩用。 */
   public static boolean hasRunning() {
      return !runtimes.isEmpty();
   }

   public static boolean isModeRunning(String machineId, String modeId) {
      return detectedModeState.getOrDefault(modeKey(machineId, modeId), MachineDetector.MachineState.DISABLED) == MachineDetector.MachineState.ON;
   }

   public static boolean isModeProcessRunning(String machineId, String modeId) {
      return runtimes.containsKey(modeKey(machineId, modeId));
   }

   public static boolean isShuttingDown(String machineId) {
      MachineScheduler.Runtime runtime = runtimes.get(machineId);
      return runtime != null && runtime.isOffTimeline;
   }

   public static boolean isModeShuttingDown(String machineId, String modeId) {
      MachineScheduler.Runtime runtime = runtimes.get(modeKey(machineId, modeId));
      return runtime != null && runtime.isOffTimeline;
   }

   /** 测试用：导出全部 runtime 内部状态（/cgtest sched 读取）。 */
   public static String dumpRuntimeState() {
      if (runtimes.isEmpty()) {
         return "scheduler: (无运行中的时序)";
      }
      StringBuilder sb = new StringBuilder("scheduler runtimes (").append(runtimes.size()).append("):");
      for (MachineScheduler.Runtime rt : runtimes.values()) {
         sb.append("\n  [")
            .append(rt.key)
            .append("] stepIndex=")
            .append(rt.stepIndex)
            .append("/")
            .append(rt.steps.size())
            .append(" waitTicks=")
            .append(rt.waitTicks)
            .append(" cmdWait=")
            .append(rt.commandWaitTicks)
            .append(" pending=")
            .append(rt.pending.size())
            .append(" loops=")
            .append(rt.completedLoops)
            .append(" loopCount=")
            .append(rt.timeline != null ? rt.timeline.loopCount : 0)
            .append(" finished=")
            .append(rt.finished)
            .append(" awaitBot=")
            .append(rt.awaitBot)
            .append(" trigger=")
            .append(rt.triggerPlayer)
            .append(" isOff=")
            .append(rt.isOffTimeline);
      }
      return sb.toString();
   }

   public static void recordSwitchTick(String machineId, int tick) {
      lastSwitchTick.put(machineId, tick);
   }

   public static int switchLockRemaining(String machineId, int tick) {
      Integer last = lastSwitchTick.get(machineId);
      if (last == null) {
         return 0;
      } else {
         MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
         int interval = machine != null ? Math.max(0, machine.switchInterval) : 0;
         return Math.max(0, interval - (tick - last));
      }
   }

   public static void recordModeSwitchTick(String machineId, String modeId, int tick) {
      lastModeSwitchTick.put(modeKey(machineId, modeId), tick);
   }

   public static int modeLockRemaining(String machineId, String modeId, int tick) {
      Integer last = lastModeSwitchTick.get(modeKey(machineId, modeId));
      if (last == null) {
         return 0;
      } else {
         MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
         int interval = 0;
         if (machine != null) {
            interval = machine.switchInterval;

            for (MachineConfig.ModeData mode : machine.modes) {
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
   }

   public static void clearSwitchTicks(String machineId) {
      lastSwitchTick.remove(machineId);
      lastModeSwitchTick.entrySet().removeIf(e -> e.getKey().startsWith(machineId + "#"));
   }

   private static void cleanupIfEmpty(String machineId) {
      boolean anyRunning = false;

      for (MachineScheduler.Runtime runtime : runtimes.values()) {
         if (runtime.machine.id.equals(machineId)) {
            anyRunning = true;
            break;
         }
      }

      if (!anyRunning) {
         botLastTick.remove(machineId);
      }
   }

   private static String ensureSpawnFacing(String command) {
      String lower = command.toLowerCase();
      if (!lower.startsWith("/player ") || !lower.contains(" spawn ")) {
         return command;
      } else if (lower.contains(" facing")) {
         return command;
      } else {
         int inIndex = lower.indexOf(" in ");
         if (inIndex >= 0) {
            return command.substring(0, inIndex) + " facing 0 0" + command.substring(inIndex);
         }
         return command + " facing 0 0";
      }
   }

   private static PermissionSet capturePermission(String triggerPlayer) {
      if (triggerPlayer == null || triggerPlayer.isEmpty()) {
         return PermissionSet.NO_PERMISSIONS;
      }

      MinecraftServer server = MachineMod.getCurrentServer();
      if (server != null) {
         ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
         if (player != null) {
            return player.createCommandSourceStack().permissions();
         }
      }

      return PermissionSet.NO_PERMISSIONS;
   }

   public static String checkCommandPermission(String command, String triggerPlayer) {
      MinecraftServer server = MachineMod.getCurrentServer();
      if (server == null) {
         return "服务器未运行";
      } else if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
         ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
         if (player == null) {
            return "触发玩家不在线";
         } else {
            ParseResults<CommandSourceStack> result = server.getCommands().getDispatcher().parse(stripLeadingSlash(command), player.createCommandSourceStack());
            if (result.getExceptions().isEmpty()) {
               return null;
            }
            return "你的权限不足以执行该指令";
         }
      } else {
         return "无触发玩家，无法确认权限";
      }
   }

   private static MachineScheduler.CommandResult executeCommand(MinecraftServer server, String command, MachineScheduler.Runtime runtime) {
      CommandSourceStack source;
      String triggerPlayer = runtime.triggerPlayer;
      PermissionSet permissionSnapshot = runtime.permissionSnapshot;
      if (triggerPlayer != null && !triggerPlayer.isEmpty() && !runtime.usePermissionSnapshot) {
         ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
         if (player != null) {
            source = player.createCommandSourceStack().withSuppressedOutput();
         } else {
            runtime.usePermissionSnapshot = true;
            source = server.createCommandSourceStack().withPermission(permissionSnapshot).withSuppressedOutput();
         }
      } else {
         source = server.createCommandSourceStack().withPermission(permissionSnapshot).withSuppressedOutput();
      }

      try {
         int result = server.getCommands().getDispatcher().execute(server.getCommands().getDispatcher().parse(stripLeadingSlash(command), source));
         if (result > 0) {
            MachineMod.LOGGER.info("Machine command executed: {}", command);
            recordExec(runtime.key, "OK   " + command);
            return MachineScheduler.CommandResult.SUCCESS;
         } else {
            MachineMod.LOGGER.warn("Machine command no-op (no permission or invalid): {}", command);
            recordExec(runtime.key, "NOOP " + command);
            return MachineScheduler.CommandResult.NO_OP;
         }
      } catch (CommandSyntaxException var5) {
         MachineMod.LOGGER.warn("Machine command REJECTED (no permission or invalid command): {} - {}", command, var5.getMessage());
         recordExec(runtime.key, "REJ  " + command);
         return MachineScheduler.CommandResult.REJECTED;
      } catch (Exception var6) {
         MachineMod.LOGGER.warn("Failed to execute machine command '{}': {}", command, var6.getMessage());
         recordExec(runtime.key, "ERR  " + command);
         return MachineScheduler.CommandResult.REJECTED;
      }
   }

   private static String stripLeadingSlash(String command) {
      if (command != null && command.startsWith("/")) {
         return command.substring(1);
      }
      return command;
   }

   private static boolean isSpawnCommand(String command) {
      if (command == null) {
         return false;
      } else {
         String cmd = command.trim().toLowerCase();
         if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
         }

         return cmd.startsWith("player ") && cmd.contains(" spawn");
      }
   }

   private static List<MachineScheduler.EffectiveStep> compileTimeline(MachineConfig.Timeline timeline) {
      List<MachineScheduler.EffectiveStep> result = new ArrayList<>();
      if (timeline != null && timeline.steps != null) {
         int wait = 0;

         for (MachineConfig.Step entry : timeline.steps) {
            if (entry != null) {
               if (entry.isDelay()) {
                  wait += Math.max(1, Math.min(72000, entry.delay));
               } else {
                  result.add(
                     new MachineScheduler.EffectiveStep(
                        Math.max(0, wait),
                        Math.max(0, entry.bot),
                        Math.max(1, Math.min(72000, entry.commandDelay)),
                        entry.commands != null ? entry.commands : List.of()
                     )
                  );
                  wait = 0;
               }
            }
         }

         return result;
      } else {
         return result;
      }
   }

   private static enum CommandResult {
      SUCCESS,
      REJECTED,
      NO_OP;
   }

   private static record EffectiveStep(int waitBefore, int bot, int commandDelay, List<String> commands) {
   }

   private static record PendingCommand(String command, String botName, int delayAfter) {
   }

   private static final class Runtime {
      final String key;
      final MachineConfig.MachineData machine;
      final MachineConfig.Timeline timeline;
      final List<MachineScheduler.EffectiveStep> steps;
      final String triggerPlayer;
      final boolean isOffTimeline;
      final PermissionSet permissionSnapshot;
      boolean usePermissionSnapshot = false;
      final Deque<MachineScheduler.PendingCommand> pending = new ArrayDeque<>();
      int stepIndex = 0;
      int waitTicks = 0;
      int completedLoops = 0;
      boolean finished = false;
      boolean hadFailures = false;
      String failureMessage = null;
      String failedReason = null;
      int commandWaitTicks = 0;
      String awaitBot = null;
      int awaitTimeout = 0;

      Runtime(String key, MachineConfig.MachineData machine, MachineConfig.Timeline timeline, String triggerPlayer, boolean isOffTimeline) {
         this.key = key;
         this.machine = machine;
         this.timeline = timeline;
         this.steps = MachineScheduler.compileTimeline(timeline);
         this.triggerPlayer = triggerPlayer;
         this.isOffTimeline = isOffTimeline;
         this.permissionSnapshot = MachineScheduler.capturePermission(triggerPlayer);
      }
   }
}

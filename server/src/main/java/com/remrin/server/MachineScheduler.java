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
   /** spawn 后等待假人上线的总超时（tick）。首 spawn 从未上线过的假人可能较慢，放宽到 45 秒。
    *  注意：超时后是显式失败中止时间线，不是静默跳过——避免后续指令被吞。 */
   private static final int SPAWN_WAIT_TIMEOUT = 900;
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
            if (!runtime.hadFailures) for (var watched : runtime.watchedBots.entrySet()) {
               if (server.getPlayerList().getPlayerByName(watched.getKey()) != watched.getValue() || !watched.getValue().isAlive()) {
                  fail(runtime, "player " + watched.getKey(), "假人 " + watched.getKey() + " 在执行期间被移除或击杀，已中止");
                  break;
               }
            }
            if (runtime.commandWaitTicks > 0) {
               runtime.commandWaitTicks--;
            } else if (!runtime.pending.isEmpty() && runtime.awaitBot == null) {
               MachineScheduler.PendingCommand pending = runtime.pending.peek();
               Map<String, Integer> gate = botLastTick.get(runtime.machine.id);
               int lastUsed = gate != null ? gate.getOrDefault(pending.botName, -1) : -1;
               if (lastUsed != tick) {
                  runtime.pending.remove();
                  int delayAfter = pending.delayAfter;
                  String[] playerCommand = playerCommand(pending.command);
                  if (playerCommand != null && !playerCommand[2].equals("spawn")
                     && !FakePlayerStateTracker.isReady(server, server.getPlayerList().getPlayerByName(playerCommand[1]))) {
                     fail(runtime, pending.command, "目标假人未就绪或已被移除，已中止");
                     continue;
                  }
                  MachineScheduler.CommandResult result = executeCommand(server, pending.command, runtime);
                  if (result == MachineScheduler.CommandResult.SUCCESS && playerCommand != null && !playerCommand[2].equals("kill")
                     && !playerCommand[2].equals("spawn"))
                     runtime.watchedBots.put(playerCommand[1], server.getPlayerList().getPlayerByName(playerCommand[1]));
                  if (result == MachineScheduler.CommandResult.SUCCESS && playerCommand != null && playerCommand[2].equals("kill"))
                     runtime.watchedBots.remove(playerCommand[1]);
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
                   } else if (result == MachineScheduler.CommandResult.NO_OP && !isSpawnCommand(pending.command)) {
                      // NOOP 且非 spawn：目标假人不存在/无法操作（如被其他玩家 kill）。
                      // 中止时间线并明确报错，避免后续指令全部静默吞掉、机器"假完成"。
                      MachineMod.LOGGER.warn("Machine '{}': command no-op'd (fake player '{}' likely removed), aborting: {}",
                         new Object[]{runtime.machine.id, pending.botName, pending.command});
                      runtime.hadFailures = true;
                      runtime.failureMessage = pending.command;
                      runtime.failedReason = "假人 " + pending.botName + " 不存在或无法操作（可能已被移除/击杀）";
                      int hashIndex = runtime.key.indexOf(35);
                      if (hashIndex >= 0) {
                         MachineManager.onModeCommandFailed(runtime.machine, runtime.key.substring(hashIndex + 1), runtime.isOffTimeline, runtime.triggerPlayer, pending.command, runtime.failedReason);
                      } else {
                         MachineManager.onSwitchCommandFailed(runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer, pending.command, runtime.failedReason);
                      }
                      runtime.finished = true;
                      runtime.pending.clear();
                  } else {
                     botLastTick.computeIfAbsent(runtime.machine.id, k -> new HashMap<>()).put(pending.botName, tick);
                     runtime.commandWaitTicks = delayAfter;
                     if (isSpawnCommand(pending.command)) {
                        runtime.awaitBot = playerCommand[1];
                        runtime.awaitTimeout = SPAWN_WAIT_TIMEOUT;
                     }
                  }
               }
            } else if (runtime.awaitBot != null) {
               // 等待实际目标实体注册、存活且 Carpet 动作接口就绪；不按固定延时放行。
               ServerPlayer spawned = server.getPlayerList().getPlayerByName(runtime.awaitBot);
               if (FakePlayerStateTracker.isReady(server, spawned)) {
                  runtime.watchedBots.put(runtime.awaitBot, spawned);
                  runtime.awaitBot = null;
                  runtime.awaitTimeout = 0;
               } else if (--runtime.awaitTimeout <= 0) {
                  // ★ 超时：不得静默继续（后续指令会打到不存在的假人被吞）。
                  // 标记失败并终止该时间线，向触发者给出明确反馈。
                  MachineMod.LOGGER.warn("Machine '{}': fake player '{}' failed to spawn within {} ticks, aborting timeline",
                     new Object[]{runtime.machine.id, runtime.awaitBot, SPAWN_WAIT_TIMEOUT});
                  runtime.hadFailures = true;
                  runtime.failedReason = "假人 " + runtime.awaitBot + " 召唤超时（" + (SPAWN_WAIT_TIMEOUT / 20) + " 秒），已中止";
                  runtime.finished = true;
                  runtime.pending.clear();
                  if (!runtime.key.contains("#")) {
                     MachineManager.onSwitchCommandFailed(
                        runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer, "spawn " + runtime.awaitBot, runtime.failedReason
                     );
                  } else {
                     MachineManager.onModeCommandFailed(runtime.machine, runtime.key.substring(runtime.key.indexOf('#') + 1),
                        runtime.isOffTimeline, runtime.triggerPlayer, "spawn " + runtime.awaitBot, runtime.failedReason);
                  }
                  runtime.awaitBot = null;
                  runtime.awaitTimeout = 0;
               }

            } else if (runtime.finished) {
               if (!runtime.hadFailures) {
                  MachineConfig.DetectionData detection = runtime.machine.detection;
                  if (runtime.key.contains("#")) {
                     String modeId = runtime.key.substring(runtime.key.indexOf('#') + 1);
                     detection = runtime.machine.modes.stream().filter(m -> modeId.equals(m.id)).map(m -> m.detection).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                  }
                  if (detection != null && detection.enabled) {
                     var expected = runtime.isOffTimeline ? MachineDetector.MachineState.OFF : MachineDetector.MachineState.ON;
                     if (MachineDetector.evaluateDetection(detection, server, true).state() != expected)
                        fail(runtime, "检测确认", "指令已结束，但检测状态未达到" + (runtime.isOffTimeline ? "关机" : "开机") + "状态，请检查机器");
                  }
               }
               it.remove();
               cleanupIfEmpty(runtime.machine.id);
               int hashIndex = runtime.key.indexOf(35);
               if (hashIndex >= 0) {
                  String machineId = runtime.key.substring(0, hashIndex);
                  String modeId = runtime.key.substring(hashIndex + 1);
                  recordModeSwitchTick(machineId, modeId, tick);
                  MachineModeChain.onModeProcessFinished(machineId, modeId, !runtime.hadFailures);
                  if (!runtime.hadFailures && !MachineModeChain.isActive(machineId)) {
                     MachineManager.onModeSwitchFinished(runtime.machine, runtime.triggerPlayer);
                  }
               } else if (!runtime.hadFailures) {
                  MachineManager.onSwitchTimelineFinished(runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer);
               }

               MachineManager.onMachineFinished(runtime.machine.id);
            } else if (runtime.waitTicks > 0) {
               runtime.waitTicks--;
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
         List<String> resolvedCommands = new ArrayList<>();

         for (String command : step.commands) {
            if (command != null && !command.isBlank()) {
               String resolved = resolveCommand(command, runtime.machine, step.bot, runtime.triggerPlayer);
               resolvedCommands.add(resolved);
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
      startMode(machine, mode, triggerPlayer, captureSource(triggerPlayer));
   }

   static void startMode(MachineConfig.MachineData machine, MachineConfig.ModeData mode, String triggerPlayer, CommandSourceStack snapshot) {
      String key = modeKey(machine.id, mode.id);
      stopMode(machine.id, mode.id);
      if (mode.onTimeline != null && !mode.onTimeline.steps.isEmpty()) {
         MachineScheduler.Runtime runtime = new MachineScheduler.Runtime(key, machine, mode.onTimeline, triggerPlayer, false);
         runtime.sourceSnapshot = snapshot;
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
      stopModeWithShutdown(machine, mode, triggerPlayer, captureSource(triggerPlayer));
   }

   static void stopModeWithShutdown(MachineConfig.MachineData machine, MachineConfig.ModeData mode, String triggerPlayer, CommandSourceStack snapshot) {
      String key = modeKey(machine.id, mode.id);
      stopMode(machine.id, mode.id);
      if (mode.offTimeline != null && !mode.offTimeline.steps.isEmpty()) {
         MachineScheduler.Runtime runtime = new MachineScheduler.Runtime(key, machine, mode.offTimeline, triggerPlayer, true);
         runtime.sourceSnapshot = snapshot;
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

   private static CommandSourceStack detachedSource(CommandSourceStack source) {
      return new CommandSourceStack(source.getServer(), source.getPosition(), source.getRotation(), source.getLevel(),
         source.permissions(), source.getTextName(), source.getDisplayName(), source.getServer(), null).withSuppressedOutput();
   }

   static CommandSourceStack captureSource(String triggerPlayer) {
      MinecraftServer server = MachineMod.getCurrentServer();
      ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer == null ? "" : triggerPlayer);
      return player == null ? server.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS).withSuppressedOutput()
         : detachedSource(player.createCommandSourceStack());
   }

   public static void onPlayerDisconnect(ServerPlayer player) {
      CommandSourceStack snapshot = detachedSource(player.createCommandSourceStack());
      for (Runtime runtime : runtimes.values()) if (player.getGameProfile().name().equals(runtime.triggerPlayer)) runtime.sourceSnapshot = snapshot;
      MachineModeChain.onPlayerDisconnect(player.getGameProfile().name(), snapshot);
   }

   /** Use the same substitutions and spawn defaults for preflight and execution. */
   static String resolveCommand(String command, MachineConfig.MachineData machine, int bot, String triggerPlayer) {
      String botName = bot >= 0 && bot < machine.bots.size() ? machine.bots.get(bot) : "bot" + bot;
      return ensureSpawnFacing(command.replace("{bot}", botName).replace("{player}", triggerPlayer != null ? triggerPlayer : ""));
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
            return checkCommandPermission(command, player.createCommandSourceStack());
         }
      } else {
         return "无触发玩家，无法确认权限";
      }
   }

   static String checkCommandPermission(String command, CommandSourceStack source) {
      ParseResults<CommandSourceStack> result = source.getServer().getCommands().getDispatcher().parse(stripLeadingSlash(command), source);
      return result.getExceptions().isEmpty() && !result.getReader().canRead()
         && result.getContext().getLastChild().getCommand() != null ? null : "权限不足或指令无效";
   }

   private static MachineScheduler.CommandResult executeCommand(MinecraftServer server, String command, MachineScheduler.Runtime runtime) {
      ServerPlayer player = server.getPlayerList().getPlayerByName(runtime.triggerPlayer == null ? "" : runtime.triggerPlayer);
      CommandSourceStack source = player == null ? runtime.sourceSnapshot : player.createCommandSourceStack().withSuppressedOutput();
      if (player != null) runtime.sourceSnapshot = detachedSource(source);

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

   private static String[] playerCommand(String command) {
      if (command == null) return null;
      String[] parts = stripLeadingSlash(command.trim()).split("\\s+");
      return parts.length >= 3 && parts[0].equals("player") ? parts : null;
   }

   private static boolean isSpawnCommand(String command) {
      String[] parts = playerCommand(command);
      return parts != null && parts[2].equals("spawn");
   }

   private static void fail(Runtime runtime, String command, String reason) {
      runtime.hadFailures = true;
      runtime.finished = true;
      runtime.failedReason = reason;
      runtime.pending.clear();
      runtime.awaitBot = null;
      runtime.commandWaitTicks = 0;
      recordExec(runtime.key, "ABORT " + command + " : " + reason);
      int hash = runtime.key.indexOf('#');
      if (hash >= 0) MachineManager.onModeCommandFailed(runtime.machine, runtime.key.substring(hash + 1), runtime.isOffTimeline, runtime.triggerPlayer, command, reason);
      else MachineManager.onSwitchCommandFailed(runtime.machine, runtime.isOffTimeline, runtime.triggerPlayer, command, reason);
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
      CommandSourceStack sourceSnapshot;
      final Map<String, ServerPlayer> watchedBots = new HashMap<>();
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
         this.sourceSnapshot = captureSource(triggerPlayer);
         for (EffectiveStep step : steps) for (String command : step.commands) {
            if (command == null) continue;
            String[] target = playerCommand(resolveCommand(command, machine, step.bot, triggerPlayer));
            if (target != null) {
               ServerPlayer bot = sourceSnapshot.getServer().getPlayerList().getPlayerByName(target[1]);
               if (bot != null) watchedBots.put(target[1], bot);
            }
         }
      }
   }
}

package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;

public final class MachineModeChain {
   private static final Map<String, MachineModeChain.Chain> chains = new HashMap<>();

   private MachineModeChain() {
   }

   public static void buildChains(MachineConfig.MachineData machine, List<String> startIds, List<String> stopIds, String triggerPlayer) {
      int startInterval = Math.max(0, machine.modeInterval);
      int stopInterval = machine.stopFollowsStart ? startInterval : Math.max(0, machine.stopModeInterval);
      List<String> singleStop = new ArrayList<>();
      List<String> singleStart = new ArrayList<>();
      List<String> multiStop = new ArrayList<>();
      List<String> multiStart = new ArrayList<>();

      for (String id : stopIds) {
         MachineConfig.ModeData mode = findMode(machine, id);
         if (mode != null && mode.singleSelect) {
            singleStop.add(id);
         } else {
            multiStop.add(id);
         }
      }

      for (String idx : startIds) {
         MachineConfig.ModeData mode = findMode(machine, idx);
         if (mode != null && mode.singleSelect) {
            singleStart.add(idx);
         } else {
            multiStart.add(idx);
         }
      }

      List<String> singleStopOrder = orderedIds(machine, singleStop, machine.stopFollowsStart);
      List<String> singleStartOrder = orderedIds(machine, singleStart, false);
      List<String> multiStopOrder = orderedIds(machine, multiStop, machine.stopFollowsStart);
      List<String> multiStartOrder = orderedIds(machine, multiStart, false);
      MachineModeChain.Chain chain = new MachineModeChain.Chain(triggerPlayer);
      Map<String, String> replacementOf = new HashMap<>();
      String firstStartingSingle = null;

      for (String modeId : singleStartOrder) {
         MachineConfig.ModeData mode = findMode(machine, modeId);
         if (mode != null && mode.singleSelect) {
            firstStartingSingle = modeId;
            break;
         }
      }

      if (firstStartingSingle != null) {
         for (MachineConfig.ModeData mode : machine.modes) {
            if (mode.singleSelect && !startIds.contains(mode.id) && !stopIds.contains(mode.id) && MachineScheduler.isModeRunning(machine.id, mode.id)) {
               if (!singleStopOrder.contains(mode.id)) {
                  singleStopOrder.add(0, mode.id);
               }

               replacementOf.put(firstStartingSingle, mode.id);
            }
         }
      }

      boolean first = true;

      for (String modeIdx : singleStopOrder) {
         chain.steps.add(new MachineModeChain.Step(modeIdx, MachineModeChain.Action.STOP, first ? 0 : stopInterval));
         first = false;
      }

      for (String modeIdx : singleStartOrder) {
         int wait = first ? 0 : startInterval;
         if (replacementOf.containsKey(modeIdx)) {
            wait = startInterval + stopInterval;
         }

         chain.steps.add(new MachineModeChain.Step(modeIdx, MachineModeChain.Action.START, wait));
         first = false;
      }

      for (String modeIdx : multiStopOrder) {
         chain.steps.add(new MachineModeChain.Step(modeIdx, MachineModeChain.Action.STOP, first ? 0 : stopInterval));
         first = false;
      }

      for (String modeIdx : multiStartOrder) {
         chain.steps.add(new MachineModeChain.Step(modeIdx, MachineModeChain.Action.START, first ? 0 : startInterval));
         first = false;
      }

      if (chain.steps.isEmpty()) {
         chains.remove(machine.id);
      } else {
         chains.put(machine.id, chain);
      }
   }

   private static List<String> orderedIds(MachineConfig.MachineData machine, List<String> selected, boolean forStops) {
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
         for (MachineConfig.ModeData mode : machine.modes) {
            if (selected.contains(mode.id) && !result.contains(mode.id)) {
               result.add(mode.id);
            }
         }
      }

      return result;
   }

   private static MachineConfig.ModeData findMode(MachineConfig.MachineData machine, String modeId) {
      if (machine.modes == null) {
         return null;
      } else {
         for (MachineConfig.ModeData mode : machine.modes) {
            if (mode.id != null && mode.id.equals(modeId)) {
               return mode;
            }
         }

         return null;
      }
   }

   public static void tick(MinecraftServer server) {
      if (!chains.isEmpty()) {
         Iterator<Entry<String, MachineModeChain.Chain>> it = chains.entrySet().iterator();

         while (it.hasNext()) {
            Entry<String, MachineModeChain.Chain> entry = it.next();
            String machineId = entry.getKey();
            MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
            MachineModeChain.Chain chain = entry.getValue();
            if (machine != null && !chain.steps.isEmpty()) {
               if (chain.activeModeId != null) {
                  if (!chain.activeCompleted) {
                     continue;
                  }

                  chain.activeModeId = null;
                  chain.activeCompleted = false;
                  MachineModeChain.Step next = chain.steps.peek();
                  if (next == null) {
                     it.remove();
                     MachineManager.onModeSwitchFinished(machine, chain.triggerPlayer);
                     continue;
                  }

                  chain.waitTicks = next.waitAfterPrevious;
               }

               if (chain.waitTicks > 0) {
                  chain.waitTicks--;
               } else {
                  MachineModeChain.Step step = chain.steps.poll();
                  if (step == null) {
                     it.remove();
                     MachineManager.onModeSwitchFinished(machine, chain.triggerPlayer);
                  } else {
                     MachineConfig.ModeData mode = findMode(machine, step.modeId);
                     if (mode == null) {
                        MachineMod.LOGGER.info("Chain: mode '{}' of machine '{}' no longer exists, skipping", step.modeId, machineId);
                     } else {
                        if (step.action == MachineModeChain.Action.STOP) {
                           MachineScheduler.stopModeWithShutdown(machine, mode, chain.triggerPlayer);
                           MachineMod.LOGGER.info("Chain: stopping mode '{}' of machine '{}'", step.modeId, machineId);
                        } else if (!launchStart(server, machine, mode, chain.triggerPlayer)) {
                           continue;
                        }

                        if (MachineScheduler.isModeProcessRunning(machine.id, mode.id)) {
                           chain.activeModeId = mode.id;
                           chain.activeCompleted = false;
                        } else {
                           chain.activeModeId = null;
                           chain.activeCompleted = true;
                        }
                     }
                  }
               }
            } else {
               it.remove();
            }
         }
      }
   }

   private static boolean launchStart(MinecraftServer server, MachineConfig.MachineData machine, MachineConfig.ModeData mode, String triggerPlayer) {
      if (mode.detection != null && mode.detection.enabled) {
         MachineDetector.DetectionResult detection = MachineDetector.evaluateDetection(mode.detection, server, true);
         if (detection.state() == MachineDetector.MachineState.ABNORMAL) {
            MachineManager.broadcastSystem("模式「" + mode.name + "」检测异常，已跳过启动：" + (detection.reason() != null ? detection.reason() : "检测方块异常"));
            return false;
         }
      }

      if (!MachineManager.validModeStart(mode)) {
         MachineManager.broadcastSystem("模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令，已跳过启动");
         return false;
      } else {
         if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
            MachineConfig.Step firstStep = MachineManager.firstStepEntry(mode.onTimeline);
            String firstCommand = firstStep != null && firstStep.commands != null && !firstStep.commands.isEmpty() ? firstStep.commands.get(0) : "";
            String permissionError = MachineScheduler.checkCommandPermission(firstCommand, triggerPlayer);
            if (permissionError != null) {
               MachineManager.broadcastSystem("模式「" + mode.name + "」启动失败：无权限执行指令（" + permissionError + "）");
               return false;
            }
         }

         MachineScheduler.startMode(machine, mode, triggerPlayer);
         return true;
      }
   }

   public static void onModeProcessFinished(String machineId, String modeId) {
      MachineModeChain.Chain chain = chains.get(machineId);
      if (chain != null && modeId.equals(chain.activeModeId)) {
         chain.activeCompleted = true;
      }
   }

   public static boolean isActive(String machineId) {
      return chains.containsKey(machineId);
   }

   public static void invalidate(String machineId) {
      chains.remove(machineId);
   }

   private static enum Action {
      START,
      STOP;
   }

   private static final class Chain {
      final Deque<MachineModeChain.Step> steps = new ArrayDeque<>();
      int waitTicks = 0;
      String activeModeId = null;
      boolean activeCompleted = false;
      final String triggerPlayer;

      Chain(String triggerPlayer) {
         this.triggerPlayer = triggerPlayer;
      }
   }

   private static record Step(String modeId, MachineModeChain.Action action, int waitAfterPrevious) {
   }
}

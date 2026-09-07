package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;

public class TimedTaskManager {
   private static final List<TimedTaskManager.TimedTask> pendingTasks = new ArrayList<>();

   public static void addSpawnTask(String playerName, int hours, int minutes, int seconds) {
      addSpawnTask(playerName, hours, minutes, seconds, null, null, null);
   }

   public static void addSpawnTask(String playerName, int hours, int minutes, int seconds, Double x, Double y, Double z) {
      int totalTicks = (hours * 3600 + minutes * 60 + seconds) * 20;
      if (totalTicks > 0) {
         removeTask(playerName);
         pendingTasks.add(new TimedTaskManager.TimedTask(TimedTaskManager.TaskType.SPAWN, playerName, totalTicks, x, y, z));
      }
   }

   public static void addKillTask(String playerName, int hours, int minutes, int seconds) {
      int totalTicks = (hours * 3600 + minutes * 60 + seconds) * 20;
      if (totalTicks > 0) {
         removeTask(playerName);
         pendingTasks.add(new TimedTaskManager.TimedTask(TimedTaskManager.TaskType.KILL, playerName, totalTicks));
      }
   }

   public static void removeTask(String playerName) {
      pendingTasks.removeIf(task -> task.playerName.equals(playerName));
   }

   public static TimedTaskManager.TimedTask getTask(String playerName) {
      for (TimedTaskManager.TimedTask task : pendingTasks) {
         if (task.playerName.equals(playerName)) {
            return task;
         }
      }

      return null;
   }

   public static List<TimedTaskManager.TimedTask> getPendingSpawnTasks() {
      List<TimedTaskManager.TimedTask> result = new ArrayList<>();

      for (TimedTaskManager.TimedTask task : pendingTasks) {
         if (task.type == TimedTaskManager.TaskType.SPAWN) {
            result.add(task);
         }
      }

      return result;
   }

   public static List<TimedTaskManager.TimedTask> getAllTasks() {
      return new ArrayList<>(pendingTasks);
   }

   public static void tick() {
      if (!pendingTasks.isEmpty()) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            Iterator<TimedTaskManager.TimedTask> it = pendingTasks.iterator();

            while (it.hasNext()) {
               TimedTaskManager.TimedTask task = it.next();
               task.remainingTicks--;
               if (task.remainingTicks <= 0) {
                  if (task.type == TimedTaskManager.TaskType.SPAWN) {
                     String cmd;
                     if (task.spawnX != null && task.spawnY != null && task.spawnZ != null) {
                        cmd = String.format(
                           "player %s spawn at %s %s %s",
                           task.playerName,
                           CommandHelper.formatX(task.spawnX),
                           CommandHelper.formatY(task.spawnY),
                           CommandHelper.formatZ(task.spawnZ)
                        );
                     } else {
                        cmd = "player " + task.playerName + " spawn";
                     }

                     mc.player.connection.sendCommand(cmd);
                  } else {
                     mc.player.connection.sendCommand("player " + task.playerName + " kill");
                  }

                  it.remove();
               }
            }
         }
      }
   }

   public static void clear() {
      pendingTasks.clear();
   }

   public static enum TaskType {
      SPAWN,
      KILL;
   }

   public static class TimedTask {
      public final TimedTaskManager.TaskType type;
      public final String playerName;
      public final Double spawnX;
      public final Double spawnY;
      public final Double spawnZ;
      public int remainingTicks;

      public TimedTask(TimedTaskManager.TaskType type, String playerName, int delayTicks) {
         this(type, playerName, delayTicks, null, null, null);
      }

      public TimedTask(TimedTaskManager.TaskType type, String playerName, int delayTicks, Double x, Double y, Double z) {
         this.type = type;
         this.playerName = playerName;
         this.remainingTicks = delayTicks;
         this.spawnX = x;
         this.spawnY = y;
         this.spawnZ = z;
      }

      public int getRemainingSeconds() {
         return (this.remainingTicks + 19) / 20;
      }
   }
}

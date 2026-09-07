package com.remrin.client.machine;

import java.util.ArrayList;
import java.util.List;

public final class MachineModels {
   private MachineModels() {
   }

   public static String firstCommand(MachineModels.Timeline timeline) {
      if (timeline == null || timeline.steps == null || timeline.steps.isEmpty()) {
         return null;
      }

      MachineModels.Step firstStep = null;

      for (MachineModels.Step step : timeline.steps) {
         if (step != null && !step.isDelay()) {
            firstStep = step;
            break;
         }
      }

      if (firstStep == null) {
         return null;
      }

      List<String> commands = firstStep.commands;
      if (commands == null || commands.isEmpty()) {
         return null;
      }

      return commands.get(0);
   }

   public static boolean isSpawnCommand(String command) {
      if (command == null) {
         return false;
      }

      String cmd = command.trim().toLowerCase();
      if (cmd.startsWith("/")) {
         cmd = cmd.substring(1);
      }

      return cmd.startsWith("player ") && cmd.contains(" spawn");
   }

   public static String validateSteps(String label, MachineModels.Timeline timeline) {
      if (timeline == null || timeline.steps == null) {
         return null;
      }

      for (int i = 0; i < timeline.steps.size(); i++) {
         MachineModels.Step step = timeline.steps.get(i);
         if (step == null) {
            return label + " 第" + (i + 1) + "项为空";
         }

         if (step.isDelay()) {
            continue;
         }

         if (step.commands == null || step.commands.isEmpty()) {
            return label + " 第" + (i + 1) + "步没有指令";
         }
      }

      return null;
   }

   public static class DetectionData {
      public boolean enabled = false;
      public String dimension = "minecraft:overworld";
      public int x = 0;
      public int y = 0;
      public int z = 0;
      public String blockId = "";
      public String property = "";
      public List<String> onValues = new ArrayList<>();
      public List<String> offValues = new ArrayList<>();
      public List<String> ignoreValues = new ArrayList<>();

      public boolean isConfigured() {
         return this.blockId != null && !this.blockId.isEmpty() && this.property != null && !this.property.isEmpty();
      }
   }

   public static class MachineData {
      public String id;
      public String name = "";
      public String description = "";
      public String category = "";
      public List<String> bots = new ArrayList<>();
      public int permissionLevel = 2;
      public List<String> bannedPlayers = new ArrayList<>();
      public MachineModels.Timeline onTimeline = new MachineModels.Timeline();
      public MachineModels.Timeline offTimeline = new MachineModels.Timeline();
      public List<MachineModels.ModeData> modes = new ArrayList<>();
      public MachineModels.DetectionData detection;
      public int switchInterval = 20;
      public List<String> modeOrder = new ArrayList<>();
      public int modeInterval = 0;
      public List<String> stopModeOrder = new ArrayList<>();
      public int stopModeInterval = 0;
      public boolean stopFollowsStart = false;
      public boolean running = false;
      public String transition = "";
      public String detected = "";
      public int revision = 0;
      public String editingBy = "";
   }

   public static class ModeData {
      public String id;
      public String name = "";
      public MachineModels.Timeline onTimeline = new MachineModels.Timeline();
      public MachineModels.Timeline offTimeline = new MachineModels.Timeline();
      public boolean singleSelect = false;
      public int switchInterval = 0;
      public MachineModels.DetectionData detection;
      public String detected = "";
      public String transition = "";
      public boolean processing = false;
      public boolean running = false;
   }

   public static class Step {
      public String kind = "step";
      public int delay = 1;
      public int commandDelay = 1;
      public int bot = 0;
      public List<String> commands = new ArrayList<>();
      public String description = "";

      public boolean isDelay() {
         return "delay".equals(this.kind);
      }
   }

   public static class Timeline {
      public int loopCount = 0;
      public List<MachineModels.Step> steps = new ArrayList<>();
   }
}

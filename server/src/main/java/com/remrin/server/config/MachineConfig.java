package com.remrin.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.remrin.server.MachineMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class MachineConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("command-gui-server").resolve("machines.json");
   private static final Type CONFIG_TYPE = (new TypeToken<MachineConfig.ConfigData>() {
   }).getType();
   private static MachineConfig.ConfigData configData = new MachineConfig.ConfigData();

   private MachineConfig() {
   }

   public static void load() {
      if (Files.exists(CONFIG_PATH)) {
         try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            MachineConfig.ConfigData loaded = (MachineConfig.ConfigData)GSON.fromJson(reader, CONFIG_TYPE);
            if (loaded != null && loaded.machines != null) {
               configData = loaded;
               migrateLegacySteps();
               normalizeDelays();
               migrateDetectionKeys();
            }
         } catch (Exception var5) {
            MachineMod.LOGGER.error("Failed to load machine config", var5);
         }
      }
   }

   private static void migrateLegacySteps() {
      for (MachineConfig.MachineData machine : configData.machines) {
         if (machine == null) {
            continue;
         }
         migrateTimeline(machine.onTimeline);
         migrateTimeline(machine.offTimeline);

         // 防御: 单台机器 modes 缺失(null)不应拖垮整份配置加载(旧版配置/手改损坏场景)
         if (machine.modes != null) {
            for (MachineConfig.ModeData mode : machine.modes) {
               if (mode == null) {
                  continue;
               }
               migrateTimeline(mode.onTimeline);
               migrateTimeline(mode.offTimeline);
            }
         }
      }
   }

   private static void migrateTimeline(MachineConfig.Timeline timeline) {
      if (timeline != null && timeline.steps != null) {
         List<MachineConfig.Step> migrated = new ArrayList<>();

         for (MachineConfig.Step step : timeline.steps) {
            if (step != null) {
               boolean typed = "delay".equals(step.kind) || "step".equals(step.kind);
               if (!typed) {
                  MachineConfig.Step delayEntry = new MachineConfig.Step();
                  delayEntry.kind = "delay";
                  delayEntry.delay = Math.max(1, Math.min(72000, step.delay));
                  delayEntry.commands = new ArrayList<>();
                  migrated.add(delayEntry);
                  MachineConfig.Step stepEntry = new MachineConfig.Step();
                  stepEntry.kind = "step";
                  stepEntry.delay = 1;
                  stepEntry.commandDelay = Math.max(1, Math.min(72000, step.commandDelay));
                  stepEntry.bot = step.bot;
                  stepEntry.commands = (List<String>)(step.commands != null ? step.commands : new ArrayList<>());
                  stepEntry.description = step.description != null ? step.description : "";
                  migrated.add(stepEntry);
               } else {
                  migrated.add(step);
               }
            }
         }

         timeline.steps = migrated;
      }
   }

   private static void normalizeDelays() {
      for (MachineConfig.MachineData machine : configData.machines) {
         if (machine == null) {
            continue;
         }
         normalizeTimeline(machine.onTimeline);
         normalizeTimeline(machine.offTimeline);

         if (machine.modes != null) {
            for (MachineConfig.ModeData mode : machine.modes) {
               if (mode != null) {
                  normalizeTimeline(mode.onTimeline);
                  normalizeTimeline(mode.offTimeline);
               }
            }
         }
      }
   }

   private static void normalizeTimeline(MachineConfig.Timeline timeline) {
      if (timeline != null && timeline.steps != null) {
         for (MachineConfig.Step step : timeline.steps) {
            if (step != null) {
               if (step.isDelay()) {
                  step.delay = Math.max(1, Math.min(72000, step.delay));
                  if (step.commands == null) {
                     step.commands = new ArrayList<>();
                  }
               } else {
                  step.delay = 1;
                  step.commandDelay = Math.max(1, Math.min(72000, step.commandDelay));
                  if (step.commands == null) {
                     step.commands = new ArrayList<>();
                  }
               }

               if (step.description == null) {
                  step.description = "";
               }
            }
         }
      }
   }

   private static void migrateDetectionKeys() {
      for (MachineConfig.MachineData machine : configData.machines) {
         if (machine == null) {
            continue;
         }
         migrateDetection(machine.detection);

         if (machine.modes != null) {
            for (MachineConfig.ModeData mode : machine.modes) {
               if (mode != null) {
                  migrateDetection(mode.detection);
               }
            }
         }
      }
   }

   private static void migrateDetection(MachineConfig.DetectionData detection) {
      if (detection != null && detection.property != null && !detection.property.isBlank()) {
         if (detection.ignoreValues == null) {
            detection.ignoreValues = new ArrayList<>();
         }

         prefixProperty(detection.onValues, detection.property);
         prefixProperty(detection.offValues, detection.property);
         prefixProperty(detection.ignoreValues, detection.property);
      }
   }

   private static void prefixProperty(List<String> values, String property) {
      if (values != null) {
         for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value != null && !value.contains("=")) {
               values.set(i, property + "=" + value);
            }
         }
      }
   }

   public static void save() {
      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Path temp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");

         try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(configData, writer);
         }

         try {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var5) {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var7) {
         MachineMod.LOGGER.error("Failed to save machine config", var7);
      }
   }

   public static List<MachineConfig.MachineData> getMachines() {
      return configData.machines;
   }

   public static MachineConfig.MachineData getMachine(String id) {
      for (MachineConfig.MachineData machine : configData.machines) {
         if (machine.id != null && machine.id.equals(id)) {
            return machine;
         }
      }

      return null;
   }

   public static boolean addMachine(MachineConfig.MachineData machine) {
      if (getMachine(machine.id) != null) {
         return false;
      } else {
         machine.revision = 1;
         configData.machines.add(machine);
         save();
         return true;
      }
   }

   public static boolean updateMachine(MachineConfig.MachineData machine) {
      for (int i = 0; i < configData.machines.size(); i++) {
         if (configData.machines.get(i).id.equals(machine.id)) {
            machine.revision = configData.machines.get(i).revision + 1;
            configData.machines.set(i, machine);
            save();
            return true;
         }
      }

      return false;
   }

   public static boolean removeMachine(String id) {
      boolean removed = configData.machines.removeIf(m -> m.id != null && m.id.equals(id));
      if (removed) {
         save();
      }

      return removed;
   }

   public static List<String> getEditorWhitelist() {
      return configData.editorWhitelist;
   }

   public static boolean addEditorWhitelist(String name) {
      for (String existing : configData.editorWhitelist) {
         if (existing.equalsIgnoreCase(name)) {
            return false;
         }
      }

      configData.editorWhitelist.add(name);
      save();
      return true;
   }

   public static boolean removeEditorWhitelist(String name) {
      boolean removed = configData.editorWhitelist.removeIf(e -> e.equalsIgnoreCase(name));
      if (removed) {
         save();
      }

      return removed;
   }

   public static class ConfigData {
      public List<MachineConfig.MachineData> machines = new ArrayList<>();
      public List<String> editorWhitelist = new ArrayList<>();
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
   }

   public static class MachineData {
      public String id;
      public String name = "";
      public String description = "";
      public String category = "";
      public List<String> bots = new ArrayList<>();
      public int permissionLevel = 2;
      public List<String> bannedPlayers = new ArrayList<>();
      public MachineConfig.Timeline onTimeline = new MachineConfig.Timeline();
      public MachineConfig.Timeline offTimeline = new MachineConfig.Timeline();
      public List<MachineConfig.ModeData> modes = new ArrayList<>();
      public MachineConfig.DetectionData detection;
      public int switchInterval = 20;
      public List<String> modeOrder = new ArrayList<>();
      public int modeInterval = 0;
      public List<String> stopModeOrder = new ArrayList<>();
      public int stopModeInterval = 0;
      public boolean stopFollowsStart = false;
      public int revision = 0;
   }

   public static class ModeData {
      public String id;
      public String name = "";
      public MachineConfig.Timeline onTimeline = new MachineConfig.Timeline();
      public MachineConfig.Timeline offTimeline = new MachineConfig.Timeline();
      public boolean singleSelect = false;
      public int switchInterval = 0;
      public MachineConfig.DetectionData detection;
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
      public List<MachineConfig.Step> steps = new ArrayList<>();
   }
}

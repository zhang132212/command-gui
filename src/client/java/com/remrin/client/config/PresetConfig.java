package com.remrin.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.remrin.CommandGUI;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public class PresetConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path PRESETS_DIR = FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve("presets");
   private static final List<PresetConfig.Preset> presets = new ArrayList<>();

   public static void load() {
      presets.clear();
      copyDefaultPresetsIfNeeded();
      loadFromConfigDir();
   }

   private static void copyDefaultPresetsIfNeeded() {
      try {
         Files.createDirectories(PRESETS_DIR);
         Minecraft mc = Minecraft.getInstance();
         if (mc == null) {
            return;
         }

         ResourceManager resourceManager = mc.getResourceManager();
         Map<Identifier, Resource> resources = resourceManager.listResources("presets", path -> path.getPath().endsWith(".json"));

         for (Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier location = entry.getKey();
            if (location.getNamespace().equals("command-gui")) {
               String fileName = location.getPath().substring(location.getPath().lastIndexOf(47) + 1);
               Path targetPath = PRESETS_DIR.resolve(fileName);
               if (!Files.exists(targetPath)) {
                  try (
                     InputStream is = entry.getValue().open();
                     Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                  ) {
                     PresetConfig.Preset preset = (PresetConfig.Preset)GSON.fromJson(reader, PresetConfig.Preset.class);
                     if (preset != null && preset.id != null) {
                        try (Writer writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
                           GSON.toJson(preset, writer);
                        }
                     }
                  } catch (Exception var20) {
                     CommandGUI.LOGGER.error("Failed to copy preset file: {}", fileName, var20);
                  }
               }
            }
         }
      } catch (Exception var21) {
         CommandGUI.LOGGER.error("Failed to copy default presets", var21);
      }
   }

   private static void loadFromConfigDir() {
      if (Files.exists(PRESETS_DIR)) {
         try {
            Files.list(PRESETS_DIR)
               .filter(p -> p.toString().endsWith(".json"))
               .filter(p -> !p.getFileName().toString().equals("custom.json"))
               .forEach(path -> {
                  try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                     PresetConfig.Preset preset = (PresetConfig.Preset)GSON.fromJson(reader, PresetConfig.Preset.class);
                     if (preset != null && preset.id != null) {
                        PresetConfig.Preset existing = getPreset(preset.id);
                        if (existing != null) {
                           presets.remove(existing);
                        }

                        presets.add(preset);
                     }
                  } catch (Exception var6) {
                     CommandGUI.LOGGER.error("Failed to load preset: {}", path.getFileName(), var6);
                  }
               });
         } catch (Exception var1) {
            CommandGUI.LOGGER.error("Failed to list preset files", var1);
         }
      }
   }

   public static List<PresetConfig.Preset> getPresets() {
      return presets;
   }

   public static PresetConfig.Preset getPreset(String id) {
      for (PresetConfig.Preset preset : presets) {
         if (preset.id.equals(id)) {
            return preset;
         }
      }

      return null;
   }

   public static class CommandGroup {
      public String nameKey;
      public List<PresetConfig.PresetCommand> commands = new ArrayList<>();
   }

   public static class Preset {
      public String id;
      public String nameKey;
      public List<PresetConfig.CommandGroup> groups = new ArrayList<>();
   }

   public static class PresetCommand {
      public String nameKey;
      public String command;
      public String description;
      public Integer minValue;
      public Integer maxValue;
      public int[] quickValues;
      public String[] quickStrValues;
   }
}

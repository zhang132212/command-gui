package com.remrin.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.remrin.CommandGUI;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

public class SettingsConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve("settings.json");
   private static Map<String, Object> settings = new HashMap<>();

   public static void load() {
      if (!Files.exists(CONFIG_PATH)) {
         settings = new HashMap<>();
         initDefaults();
         save();
         return;
      }

      try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            settings = (Map<String, Object>)GSON.fromJson(reader, (new TypeToken<Map<String, Object>>() {
            }).getType());
            if (settings == null) {
               settings = new HashMap<>();
            }

            initDefaults();
         } catch (Exception var5) {
            CommandGUI.LOGGER.error("Failed to load settings", var5);
            settings = new HashMap<>();
            initDefaults();
         }
   }

   private static void initDefaults() {
      if (!settings.containsKey("show_vanilla_commands")) {
         settings.put("show_vanilla_commands", true);
      }

      if (!settings.containsKey("show_carpet_commands")) {
         settings.put("show_carpet_commands", true);
      }

      if (!settings.containsKey("show_fakeplayer_tab")) {
         settings.put("show_fakeplayer_tab", true);
      }

      if (!settings.containsKey("quick_command_keep_open_default")) {
         settings.put("quick_command_keep_open_default", false);
      }

      if (!settings.containsKey("quick_command_remember_view")) {
         settings.put("quick_command_remember_view", false);
      }

      if (!settings.containsKey("fakeplayer_keep_open_default")) {
         settings.put("fakeplayer_keep_open_default", false);
      }

      if (!settings.containsKey("fakeplayer_custom_commands_enabled")) {
         settings.put("fakeplayer_custom_commands_enabled", false);
      }

      if (!settings.containsKey("fakeplayer_custom_commands")) {
         settings.put("fakeplayer_custom_commands", new HashMap<String, List<String>>());
      }

      if (!settings.containsKey("fakeplayer_custom_command_list")) {
         settings.put("fakeplayer_custom_command_list", new ArrayList<Map<String, Object>>());
      }

      if (!settings.containsKey("fakeplayer_action_drop")) {
         settings.put("fakeplayer_action_drop", false);
      }

      if (!settings.containsKey("fakeplayer_action_dropstack")) {
         settings.put("fakeplayer_action_dropstack", false);
      }

      if (!settings.containsKey("fakeplayer_action_sprint")) {
         settings.put("fakeplayer_action_sprint", false);
      }

      if (!settings.containsKey("fakeplayer_action_jump_continuous")) {
         settings.put("fakeplayer_action_jump_continuous", false);
      }

      if (!settings.containsKey("fakeplayer_action_face_player")) {
         settings.put("fakeplayer_action_face_player", false);
      }
   }

   public static void save() {
      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Path temp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");

         try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(settings, writer);
         }

         try {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var5) {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception var7) {
         CommandGUI.LOGGER.error("Failed to save settings", var7);
      }
   }

   public static boolean getBoolean(String key) {
      Object value = settings.get(key);
      if (value instanceof Boolean) {
         return (Boolean)value;
      }
      return false;
   }

   public static void setBoolean(String key, boolean value) {
      settings.put(key, value);
   }

   public static String getString(String key) {
      Object value = settings.get(key);
      if (value instanceof String) {
         return (String)value;
      }
      return "";
   }

   public static void setString(String key, String value) {
      settings.put(key, value);
   }

   public static int getInt(String key) {
      Object value = settings.get(key);
      if (value instanceof Number) {
         return ((Number)value).intValue();
      }
      return 0;
   }

   public static void setInt(String key, int value) {
      settings.put(key, value);
   }

   public static List<String> getStringList(String key) {
      List<String> result = new ArrayList<>();
      Object value = settings.get(key);
      if (value instanceof List<?> list) {
         for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
               result.add(text);
            }
         }
      } else if (value instanceof String text && !text.isBlank()) {
         for (String part : text.split(",")) {
            if (!part.isBlank()) {
               result.add(part.trim());
            }
         }
      }

      return result;
   }

   public static void setStringList(String key, List<String> values) {
      settings.put(key, new ArrayList<>(values));
   }

   @SuppressWarnings("unchecked")
   public static Map<String, List<String>> getStringListMap(String key) {
      Map<String, List<String>> result = new HashMap<>();
      Object value = settings.get(key);
      if (value instanceof Map<?, ?> map) {
         for (Map.Entry<?, ?> entry : map.entrySet()) {
            String mapKey = String.valueOf(entry.getKey());
            List<String> list = new ArrayList<>();
            if (entry.getValue() instanceof List<?> rawList) {
               for (Object item : rawList) {
                  if (item instanceof String text) {
                     list.add(text);
                  }
               }
            } else if (entry.getValue() instanceof String text) {
               for (String part : text.split(",")) {
                  if (!part.isBlank()) {
                     list.add(part.trim());
                  }
               }
            }

            result.put(mapKey, list);
         }
      }

      return result;
   }

   public static void setStringListMap(String key, Map<String, List<String>> values) {
      settings.put(key, values);
   }

   public static List<Map<String, Object>> getCustomFakePlayerCommandList() {
      List<Map<String, Object>> result = new ArrayList<>();
      Object value = settings.get("fakeplayer_custom_command_list");
      if (value instanceof List<?> list) {
         for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
               Map<String, Object> converted = new HashMap<>();
               for (Map.Entry<?, ?> entry : map.entrySet()) {
                  converted.put(String.valueOf(entry.getKey()), entry.getValue());
               }

               result.add(converted);
            }
         }
      }

      return result;
   }

   public static void setCustomFakePlayerCommandList(List<Map<String, Object>> values) {
      settings.put("fakeplayer_custom_command_list", values);
   }
}

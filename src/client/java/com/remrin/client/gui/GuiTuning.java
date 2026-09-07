package com.remrin.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.remrin.CommandGUI;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 运行时 GUI 外观参数层。
 *
 * <p>游戏启动时会读取 {@code config/command-gui/gui-tuning.json}，用于覆盖
 * GUI 尺寸、间距、颜色等默认参数；未配置该文件时回退到源码内默认值。</p>
 */
public final class GuiTuning {
   public static final String FILE_NAME = "gui-tuning.json";
   private static final Gson GSON = new Gson();
   private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
   }.getType();
   private static final Map<String, Object> EMPTY = Collections.emptyMap();
   private static volatile Map<String, Object> values = EMPTY;
   private static volatile boolean loaded = false;

   private GuiTuning() {
   }

   public static Path configPath() {
      return FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve(FILE_NAME);
   }

   public static synchronized void load() {
      values = EMPTY;
      loaded = false;
      ensureLoaded();
   }

   private static void ensureLoaded() {
      if (loaded) {
         return;
      }

      loaded = true;
      Path path = configPath();
      if (!Files.exists(path)) {
         return;
      }

      try {
         Map<String, Object> parsed = (Map<String, Object>)GSON.fromJson(Files.newBufferedReader(path, StandardCharsets.UTF_8), MAP_TYPE);
         if (parsed != null) {
            values = parsed;
         }
      } catch (Exception var3) {
         CommandGUI.LOGGER.error("[GuiTuning] 无法读取 {}, 本次将使用源码默认值", path, var3);
      }
   }

   public static int getInt(String key, int fallback) {
      ensureLoaded();
      Object value = values.get(key);
      return value instanceof Number number ? number.intValue() : fallback;
   }

   public static int getColor(String key, int fallback) {
      return getInt(key, fallback);
   }

   public static float getFloat(String key, float fallback) {
      ensureLoaded();
      Object value = values.get(key);
      if (value instanceof Number number) {
         return number.floatValue();
      }
      return fallback;
   }

   public static double getDouble(String key, double fallback) {
      ensureLoaded();
      Object value = values.get(key);
      if (value instanceof Number number) {
         return number.doubleValue();
      }
      return fallback;
   }

   public static String getString(String key, String fallback) {
      ensureLoaded();
      Object value = values.get(key);
      return value instanceof String text ? text : fallback;
   }

   public static Map<String, Object> snapshot() {
      ensureLoaded();
      Map<String, Object> copy = new HashMap<>();
      copy.putAll(values);
      return copy;
   }
}

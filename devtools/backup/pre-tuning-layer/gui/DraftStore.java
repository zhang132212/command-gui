package com.remrin.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.remrin.CommandGUI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;

public final class DraftStore {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path DRAFT_DIR = FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve("drafts");

   private DraftStore() {
   }

   private static Path draftPath(String key) {
      return key != null && !key.isBlank() && !key.contains("..") && !key.contains("/") && !key.contains("\\") && !key.contains(":")
         ? DRAFT_DIR.resolve(key + ".json")
         : null;
   }

   public static void save(String key, String json) {
      Path path = draftPath(key);
      if (path != null) {
         try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException var4) {
            CommandGUI.LOGGER.error("Failed to save draft {}", key, var4);
         }
      }
   }

   public static String load(String key) {
      Path path = draftPath(key);
      if (path != null && Files.exists(path)) {
         try {
            return Files.readString(path, StandardCharsets.UTF_8);
         } catch (IOException var3) {
            CommandGUI.LOGGER.error("Failed to load draft {}", key, var3);
            return null;
         }
      } else {
         return null;
      }
   }

   public static void clear(String key) {
      Path path = draftPath(key);
      if (path != null) {
         try {
            Files.deleteIfExists(path);
         } catch (IOException var3) {
            CommandGUI.LOGGER.error("Failed to delete draft {}", key, var3);
         }
      }
   }

   public static boolean exists(String key) {
      Path path = draftPath(key);
      return path != null && Files.exists(path);
   }

   public static String lastModified(String key) {
      Path path = draftPath(key);
      if (path != null && Files.exists(path)) {
         try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
               .withZone(ZoneId.systemDefault())
               .format(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
         } catch (IOException var3) {
            return "";
         }
      } else {
         return "";
      }
   }
}

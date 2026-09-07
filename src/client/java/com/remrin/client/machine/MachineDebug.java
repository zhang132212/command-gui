package com.remrin.client.machine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class MachineDebug {
   private MachineDebug() {
   }

   public static void log(String message) {
      try {
         Path path = FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve("machine-debug.log");
         Files.createDirectories(path.getParent());
         String line = "[" + System.currentTimeMillis() + "] " + message + "\n";
         Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (Exception var3) {
      }
   }

   public static String commandsString(List<String> commands) {
      if (commands == null) {
         return "null";
      }
      return commands.toString();
   }
}

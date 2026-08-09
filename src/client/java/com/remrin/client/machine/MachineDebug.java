package com.remrin.client.machine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * TEMPORARY diagnostic logger for the machine editor data-loss investigation. Appends lines to
 * {@code config/command-gui/machine-debug.log} on the client. To be removed once the issue is
 * resolved.
 */
public final class MachineDebug {

  private MachineDebug() {
  }

  public static void log(String message) {
    try {
      Path path = FabricLoader.getInstance().getConfigDir().resolve("command-gui")
          .resolve("machine-debug.log");
      Files.createDirectories(path.getParent());
      String line = "[" + System.currentTimeMillis() + "] " + message + "\n";
      Files.writeString(path, line, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }

  public static String commandsString(java.util.List<String> commands) {
    return commands == null ? "null" : commands.toString();
  }
}

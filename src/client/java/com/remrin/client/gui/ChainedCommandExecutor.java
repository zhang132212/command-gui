package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Chained command executor that supports commands with placeholders
 * ({@code {player}}/{@code {number}}, etc.).
 * <p>
 * Execution flow: parse all placeholder types in the command 鈫?pop the corresponding input screen
 * for each 鈫?replace the placeholder text 鈫?send the complete command. Supports multi-command
 * sequences, and automatically delays subsequent action commands after a fake player spawn
 * command.
 */
public class ChainedCommandExecutor {

  /**
   * Pattern matching all placeholder tokens for type identification.
   */
  private static final Pattern PARSE_PATTERN = Pattern.compile(
      "\\{(player_all|player_fake|player|name|number|time|coords|x)\\}"
  );
  // --- Delayed command execution queue ---
  private static final int SPAWN_DELAY_TICKS = 20; // 1 second delay after spawn
  private static final List<DelayedBatch> delayedQueue = new ArrayList<>();
  private final Screen parent;
  private final List<PlaceholderType> pendingTypes = new ArrayList<>();
  private final Config config;
  private String currentCommand;
  private int currentIndex = 0;

  public ChainedCommandExecutor(Screen parent, String command) {
    this(parent, command, Config.defaultConfig());
  }

  public ChainedCommandExecutor(Screen parent, String command, Config config) {
    this.parent = parent;
    this.currentCommand = command;
    this.config = config;
    parseTypes(command);
  }

  public static void sendCommand(String command) {
    CommandHelper.sendCommand(command);
  }

  public static boolean hasPlaceholders(String command) {
    return CommandHelper.hasPlaceholders(command);
  }

  public static void execute(Screen parent, String command) {
    execute(parent, command, Config.defaultConfig());
  }

  public static void execute(Screen parent, String command, Config config) {
    if (CommandHelper.hasPlaceholders(command)) {
      new ChainedCommandExecutor(parent, command, config).start();
    } else {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
        CommandHelper.sendCommand(command);

        if (!CommandGUIScreen.shouldKeepOpen()) {
          if (parent != null) {
            parent.onClose();
          }
        } else {
          mc.gui.setScreen(parent);
        }
      }
    }
  }

  /**
   * Executes a multi-command sequence. If the first command is a fake player spawn command,
   * subsequent commands are sent with a delay after spawning to ensure the fake player entity is
   * fully loaded before any actions are executed.
   */
  public static void executeMulti(Screen parent, List<String> commands) {
    executeMulti(parent, commands, Config.defaultConfig());
  }

  public static void executeMulti(Screen parent, List<String> commands, Config config) {
		if (commands == null || commands.isEmpty()) {
			return;
		}
    if (commands.size() == 1) {
      execute(parent, commands.get(0), config);
      return;
    }
    // Execute first command with placeholder support, then remaining commands directly
    String first = commands.get(0);
    List<String> rest = commands.subList(1, commands.size());
    boolean needsDelay = CommandHelper.isFakePlayerSpawnCommand(first) && !rest.isEmpty();
    if (CommandHelper.hasPlaceholders(first)) {
      new ChainedCommandExecutor(parent, first, config) {
        @Override
        protected void onExecutionComplete() {
          if (needsDelay) {
            scheduleDelayed(new ArrayList<>(rest), 0);
          } else {
            sendInOrder(new ArrayList<>(rest));
          }
        }
      }.start();
    } else {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
        // Send all commands in strict order through the delayed queue (1 tick apart),
        // so the server always executes them in the configured sequence.
        sendInOrder(new ArrayList<>(commands));
        if (!CommandGUIScreen.shouldKeepOpen()) {
          if (parent != null) {
            parent.onClose();
          }
        } else {
          mc.gui.setScreen(parent);
        }
      }
    }
  }

  /**
   * Queues each command with an increasing per-command delay (1 tick between commands) so the
   * server receives and executes them in the exact list order, regardless of any client/network
   * batching behavior.
   */
  private static void sendInOrder(List<String> commands) {
    for (int i = 0; i < commands.size(); i++) {
      delayedQueue.add(new DelayedBatch(List.of(commands.get(i)), i + 1));
    }
  }

  /**
   * Schedule commands to execute after a delay (in ticks). Used to ensure fake player spawn
   * completes before running actions.
   */
  private static void scheduleDelayed(List<String> commands, int extraDelay) {
    delayedQueue.add(new DelayedBatch(commands, SPAWN_DELAY_TICKS + extraDelay));
  }

  /**
   * Called every client tick to process delayed command batches. Must be registered in the client
   * tick event handler.
   */
  public static void tickDelayed() {
		if (delayedQueue.isEmpty()) {
			return;
		}

    java.util.Iterator<DelayedBatch> it = delayedQueue.iterator();
    while (it.hasNext()) {
      DelayedBatch batch = it.next();
      batch.remainingTicks--;
      if (batch.remainingTicks <= 0) {
        for (String cmd : batch.commands) {
          CommandHelper.sendCommand(cmd);
        }
        it.remove();
      }
    }
  }

  /**
   * Parses all placeholder tokens in the command string and populates the {@link #pendingTypes}
   * list. Consecutive {@code {x}/{y}/{z}} coordinate placeholders are added only once as COORDS to
   * avoid showing the input screen multiple times.
   */
  private void parseTypes(String command) {
    Matcher matcher = PARSE_PATTERN.matcher(command);
    while (matcher.find()) {
      String token = matcher.group(1);
      PlaceholderType type = switch (token) {
        case "player_all" -> PlaceholderType.PLAYER_ALL;
        case "player_fake" -> PlaceholderType.PLAYER_FAKE;
        case "player" -> PlaceholderType.PLAYER_OTHER;
        case "name" -> PlaceholderType.NAME;
        case "number" -> PlaceholderType.NUMBER;
        case "time" -> PlaceholderType.TIME;
        case "coords", "x" -> PlaceholderType.COORDS;
        default -> null;
      };
      if (type != null) {
        // {x}, {y}, {z} all map to a single COORDS input; skip consecutive COORDS entries
        if (type == PlaceholderType.COORDS && !pendingTypes.isEmpty()
            && pendingTypes.get(pendingTypes.size() - 1) == PlaceholderType.COORDS) {
          continue;
        }
        pendingTypes.add(type);
      }
    }
  }

  public void start() {
    if (currentIndex < pendingTypes.size()) {
      showNextInput();
    } else {
      executeCommand();
    }
  }

  /**
   * Shows the input screen corresponding to the current pending placeholder type. After the user
   * confirms their input, the value is substituted into {@link #currentCommand} and
   * {@link #start()} is called recursively.
   */
  private void showNextInput() {
    Minecraft mc = Minecraft.getInstance();
    PlaceholderType type = pendingTypes.get(currentIndex);

    switch (type) {
      case PLAYER_ALL -> {
        PlayerSelectorScreen screen = new PlayerSelectorScreen(
            parent,
            Component.translatable("screen.command-gui.select_player"),
            null,
            PlayerSelectorScreen.FilterMode.ALL,
            playerName -> {
              currentCommand = currentCommand.replaceFirst("\\{player_all\\}", playerName);
              currentIndex++;
              start();
            }
        );
        mc.gui.setScreen(screen);
      }

      case PLAYER_OTHER -> {
        PlayerSelectorScreen screen = new PlayerSelectorScreen(
            parent,
            Component.translatable("screen.command-gui.select_player"),
            null,
            PlayerSelectorScreen.FilterMode.EXCLUDE_SELF,
            playerName -> {
              currentCommand = currentCommand.replaceFirst("\\{player\\}", playerName);
              currentIndex++;
              start();
            }
        );
        mc.gui.setScreen(screen);
      }

      case PLAYER_FAKE -> {
        PlayerSelectorScreen screen = new PlayerSelectorScreen(
            parent,
            Component.translatable("screen.command-gui.select_player"),
            null,
            PlayerSelectorScreen.FilterMode.ONLY_FAKE_PLAYERS,
            playerName -> {
              currentCommand = currentCommand.replaceFirst("\\{player_fake\\}", playerName);
              currentIndex++;
              start();
            }
        );
        mc.gui.setScreen(screen);
      }

      case NAME -> {
        TextInputScreen screen = new TextInputScreen(
            parent,
            Component.translatable("screen.command-gui.input_name"),
            null,
            "Steve"
        ) {
          @Override
          protected void onInputConfirmed(String input) {
            currentCommand = currentCommand.replaceFirst("\\{name\\}", input);
            currentIndex++;
            start();
          }
        };
        mc.gui.setScreen(screen);
      }

      case NUMBER -> {
        NumberInputScreen screen = new NumberInputScreen(
            parent,
            Component.translatable("screen.command-gui.input_number"),
            null,
            config.minValue, config.maxValue, config.quickValues
        ) {
          @Override
          protected void onNumberConfirmed(String number) {
            currentCommand = currentCommand.replaceFirst("\\{number\\}", number);
            currentIndex++;
            start();
          }
        };
        mc.gui.setScreen(screen);
      }

      case TIME -> {
        TimeInputScreen screen = new TimeInputScreen(
            parent,
            Component.translatable("screen.command-gui.input_time"),
            null,
            config.quickStrValues
        ) {
          @Override
          protected void onTimeConfirmed(String time) {
            currentCommand = currentCommand.replaceFirst("\\{time\\}", time);
            currentIndex++;
            start();
          }
        };
        mc.gui.setScreen(screen);
      }

      case COORDS -> {
        CoordinateInputScreen screen = new CoordinateInputScreen(
            parent,
            Component.translatable("screen.command-gui.input_coord"),
            null
        ) {
          @Override
          protected void onCoordsConfirmed(String x, String y, String z) {
            currentCommand = currentCommand
                .replace("{x}", x)
                .replace("{y}", y)
                .replace("{z}", z)
                .replace("{coords}", x + " " + y + " " + z);
            currentIndex++;
            start();
          }
        };
        mc.gui.setScreen(screen);
      }
    }
  }

  private void executeCommand() {
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.player != null) {
      CommandHelper.sendCommand(currentCommand);

      onExecutionComplete();

      if (!CommandGUIScreen.shouldKeepOpen()) {
        if (parent != null) {
          parent.onClose();
        }
      } else {
        mc.gui.setScreen(parent);
      }
    }
  }

  protected void onExecutionComplete() {
    // Hook for subclasses to execute additional commands after the main command
  }

  public enum PlaceholderType {
    PLAYER_ALL,
    PLAYER_OTHER,
    PLAYER_FAKE,
    NAME,
    NUMBER,
    TIME,
    COORDS
  }

  public static class Config {

    public Integer minValue;
    public Integer maxValue;
    public int[] quickValues;
    public String[] quickStrValues;

    public static Config defaultConfig() {
      return new Config();
    }

    public Config withNumberRange(Integer min, Integer max, int[] quickValues) {
      this.minValue = min;
      this.maxValue = max;
      this.quickValues = quickValues;
      return this;
    }

    public Config withTimeRange(Integer min, Integer max, String[] quickStrValues) {
      this.minValue = min;
      this.maxValue = max;
      this.quickStrValues = quickStrValues;
      return this;
    }
  }

  private static class DelayedBatch {

    final List<String> commands;
    int remainingTicks;

    DelayedBatch(List<String> commands, int delayTicks) {
      this.commands = commands;
      this.remainingTicks = delayTicks;
    }
  }
}

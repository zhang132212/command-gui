package com.remrin.client.gui;

import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ChainedCommandExecutor {
   private static final Pattern PARSE_PATTERN = Pattern.compile("\\{(player_all|player_fake|player|bot|name|number|time|coords|x)\\}");
   private static final int SPAWN_DELAY_TICKS = 20;
   private static final List<ChainedCommandExecutor.DelayedBatch> delayedQueue = new ArrayList<>();
   private final Screen parent;
   private final List<ChainedCommandExecutor.PlaceholderType> pendingTypes = new ArrayList<>();
   private final ChainedCommandExecutor.Config config;
   private String currentCommand;
   private int currentIndex = 0;

   public ChainedCommandExecutor(Screen parent, String command) {
      this(parent, command, ChainedCommandExecutor.Config.defaultConfig());
   }

   public ChainedCommandExecutor(Screen parent, String command, ChainedCommandExecutor.Config config) {
      this.parent = parent;
      this.currentCommand = command;
      this.config = config;
      this.parseTypes(command);
   }

   public static void sendCommand(String command) {
      CommandHelper.sendCommand(command);
   }

   public static boolean hasPlaceholders(String command) {
      return CommandHelper.hasPlaceholders(command);
   }

   public static void execute(Screen parent, String command) {
      execute(parent, command, ChainedCommandExecutor.Config.defaultConfig());
   }

   public static void execute(Screen parent, String command, ChainedCommandExecutor.Config config) {
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

   public static void executeMulti(Screen parent, List<String> commands) {
      executeMulti(parent, commands, 1);
   }

   public static void executeMulti(Screen parent, List<String> commands, int commandDelay) {
      if (commands == null || commands.isEmpty()) {
         return;
      }
      final int delay = Math.max(1, commandDelay);
      if (commands.size() == 1) {
         execute(parent, commands.get(0));
         return;
      }

      String first = commands.get(0);
      if (!CommandHelper.hasPlaceholders(first)) {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.player != null) {
            if (!CommandGUIScreen.shouldKeepOpen()) {
               if (parent != null) {
                  parent.onClose();
               } else {
                  mc.gui.setScreen(null);
               }
            } else {
               mc.gui.setScreen(parent);
            }
         }
      }

      executeSequence(parent, new ArrayList<>(commands), 0, delay);
   }

   private static void executeSequence(Screen parent, List<String> commands, int index, int delay) {
      if (index >= commands.size()) {
         return;
      }

      String command = commands.get(index);
      boolean hasNext = index + 1 < commands.size();

      if (CommandHelper.hasPlaceholders(command)) {
         (new ChainedCommandExecutor(parent, command) {
            @Override
            protected void onExecutionComplete() {
               if (hasNext) {
                  int nextDelay = CommandHelper.isFakePlayerSpawnCommand(command) ? 20 : delay;
                  delayedQueue.add(new DelayedBatch(() -> executeSequence(parent, commands, index + 1, delay), nextDelay));
               }
            }
         }).start();
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         CommandHelper.sendCommand(command);
         if (hasNext) {
            int nextDelay = CommandHelper.isFakePlayerSpawnCommand(command) ? 20 : delay;
            delayedQueue.add(new DelayedBatch(() -> executeSequence(parent, commands, index + 1, delay), nextDelay));
         }
      }
   }

   public static void tickDelayed() {
      if (delayedQueue.isEmpty()) {
         return;
      }

      List<ChainedCommandExecutor.DelayedBatch> ready = new ArrayList<>();
      for (ChainedCommandExecutor.DelayedBatch batch : delayedQueue) {
         batch.remainingTicks--;
         if (batch.remainingTicks <= 0) {
            ready.add(batch);
         }
      }

      if (ready.isEmpty()) {
         return;
      }

      delayedQueue.removeAll(ready);
      for (ChainedCommandExecutor.DelayedBatch batch : ready) {
         if (batch.action != null) {
            batch.action.run();
         } else {
            for (String cmd : batch.commands) {
               CommandHelper.sendCommand(cmd);
            }
         }
      }
   }

   private static List<String> botNames() {
      List<String> names = new ArrayList<>();
      if (MachineNetworkManager.isFakePlayerStatesSupported()) {
         names.addAll(MachineNetworkManager.getServerFakePlayers());
      }
      return names;
   }

   private void parseTypes(String command) {
      Matcher matcher = PARSE_PATTERN.matcher(command);

      while (matcher.find()) {
         String token = matcher.group(1);

         ChainedCommandExecutor.PlaceholderType type = switch (token) {
            case "player_all" -> ChainedCommandExecutor.PlaceholderType.PLAYER_ALL;
            case "player_fake", "bot" -> ChainedCommandExecutor.PlaceholderType.BOT;
            case "player" -> ChainedCommandExecutor.PlaceholderType.PLAYER_OTHER;
            case "name" -> ChainedCommandExecutor.PlaceholderType.NAME;
            case "number" -> ChainedCommandExecutor.PlaceholderType.NUMBER;
            case "time" -> ChainedCommandExecutor.PlaceholderType.TIME;
            case "coords", "x" -> ChainedCommandExecutor.PlaceholderType.COORDS;
            default -> null;
         };
         if (type != null
            && (
               type != ChainedCommandExecutor.PlaceholderType.COORDS
                  || this.pendingTypes.isEmpty()
                  || this.pendingTypes.get(this.pendingTypes.size() - 1) != ChainedCommandExecutor.PlaceholderType.COORDS
            )) {
            this.pendingTypes.add(type);
         }
      }
   }

   public void start() {
      if (this.currentIndex < this.pendingTypes.size()) {
         this.showNextInput();
      } else {
         this.executeCommand();
      }
   }

   private void showNextInput() {
      Minecraft mc = Minecraft.getInstance();
      ChainedCommandExecutor.PlaceholderType type = this.pendingTypes.get(this.currentIndex);
      switch (type) {
         case PLAYER_ALL: {
            PlayerSelectorScreen screen = new PlayerSelectorScreen(
               this.parent, Component.translatable("screen.command-gui.select_player"), null, PlayerSelectorScreen.FilterMode.ALL, playerName -> {
                  this.currentCommand = this.currentCommand.replaceFirst("\\{player_all\\}", playerName);
                  this.currentIndex++;
                  this.start();
               }
            );
            mc.gui.setScreen(screen);
            break;
         }
         case PLAYER_OTHER: {
            PlayerSelectorScreen screen = new PlayerSelectorScreen(
               this.parent, Component.translatable("screen.command-gui.select_player"), null, PlayerSelectorScreen.FilterMode.NORMAL, playerName -> {
                  this.currentCommand = this.currentCommand.replaceFirst("\\{player\\}", playerName);
                  this.currentIndex++;
                  this.start();
               }
            );
            mc.gui.setScreen(screen);
            break;
         }
         case PLAYER_FAKE: {
            PlayerSelectorScreen screen = new PlayerSelectorScreen(
               this.parent,
               Component.translatable("screen.command-gui.select_player"),
               null,
               PlayerSelectorScreen.FilterMode.ONLY_FAKE_PLAYERS,
               playerName -> {
                  this.currentCommand = this.currentCommand.replaceFirst("\\{player_fake\\}", playerName);
                  this.currentIndex++;
                  this.start();
               }
            );
            mc.gui.setScreen(screen);
            break;
         }
         case BOT: {
            BotSelectScreen screen = new BotSelectScreen(
               this.parent,
               ChainedCommandExecutor.botNames(),
               botName -> {
                  this.currentCommand = this.currentCommand.replaceFirst("\\{bot\\}", botName);
                  this.currentIndex++;
                  this.start();
               }
            );
            mc.gui.setScreen(screen);
            break;
         }
         case NAME: {
            TextInputScreen screen = new TextInputScreen(this.parent, Component.translatable("screen.command-gui.input_name"), null, "Steve") {

               @Override
               protected void onInputConfirmed(String input) {
                  ChainedCommandExecutor.this.currentCommand = ChainedCommandExecutor.this.currentCommand.replaceFirst("\\{name\\}", input);
                  ChainedCommandExecutor.this.currentIndex++;
                  ChainedCommandExecutor.this.start();
               }
            };
            mc.gui.setScreen(screen);
            break;
         }
         case NUMBER: {
            NumberInputScreen screen = new NumberInputScreen(
               this.parent,
               Component.translatable("screen.command-gui.input_number"),
               null,
               this.config.minValue,
               this.config.maxValue,
               this.config.quickValues
            ) {

               @Override
               protected void onNumberConfirmed(String number) {
                  ChainedCommandExecutor.this.currentCommand = ChainedCommandExecutor.this.currentCommand.replaceFirst("\\{number\\}", number);
                  ChainedCommandExecutor.this.currentIndex++;
                  ChainedCommandExecutor.this.start();
               }
            };
            mc.gui.setScreen(screen);
            break;
         }
         case TIME: {
            TimeInputScreen screen = new TimeInputScreen(this.parent, Component.translatable("screen.command-gui.input_time"), null, this.config.quickStrValues) {

               @Override
               protected void onTimeConfirmed(String time) {
                  ChainedCommandExecutor.this.currentCommand = ChainedCommandExecutor.this.currentCommand.replaceFirst("\\{time\\}", time);
                  ChainedCommandExecutor.this.currentIndex++;
                  ChainedCommandExecutor.this.start();
               }
            };
            mc.gui.setScreen(screen);
            break;
         }
         case COORDS: {
            CoordinateInputScreen screen = new CoordinateInputScreen(this.parent, Component.translatable("screen.command-gui.input_coord"), null) {

               @Override
               protected void onCoordsConfirmed(String x, String y, String z) {
                  ChainedCommandExecutor.this.currentCommand = ChainedCommandExecutor.this.currentCommand
                     .replace("{x}", x)
                     .replace("{y}", y)
                     .replace("{z}", z)
                     .replace("{coords}", x + " " + y + " " + z);
                  ChainedCommandExecutor.this.currentIndex++;
                  ChainedCommandExecutor.this.start();
               }
            };
            mc.gui.setScreen(screen);
         }
      }
   }

   private void executeCommand() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         CommandHelper.sendCommand(this.currentCommand);
         this.onExecutionComplete();
         if (!CommandGUIScreen.shouldKeepOpen()) {
            if (this.parent != null) {
               this.parent.onClose();
            } else {
               mc.gui.setScreen(null);
            }
         } else {
            mc.gui.setScreen(this.parent);
         }
      }
   }

   protected void onExecutionComplete() {
   }

   public static class Config {
      public Integer minValue;
      public Integer maxValue;
      public int[] quickValues;
      public String[] quickStrValues;

      public static ChainedCommandExecutor.Config defaultConfig() {
         return new ChainedCommandExecutor.Config();
      }

      public ChainedCommandExecutor.Config withNumberRange(Integer min, Integer max, int[] quickValues) {
         this.minValue = min;
         this.maxValue = max;
         this.quickValues = quickValues;
         return this;
      }

      public ChainedCommandExecutor.Config withTimeRange(Integer min, Integer max, String[] quickStrValues) {
         this.minValue = min;
         this.maxValue = max;
         this.quickStrValues = quickStrValues;
         return this;
      }
   }

   private static class DelayedBatch {
      final List<String> commands;
      final Runnable action;
      int remainingTicks;

      DelayedBatch(List<String> commands, int delayTicks) {
         this.commands = commands;
         this.action = null;
         this.remainingTicks = delayTicks;
      }

      DelayedBatch(Runnable action, int delayTicks) {
         this.commands = null;
         this.action = action;
         this.remainingTicks = delayTicks;
      }
   }

   public static enum PlaceholderType {
      PLAYER_ALL,
      PLAYER_OTHER,
      PLAYER_FAKE,
      BOT,
      NAME,
      NUMBER,
      TIME,
      COORDS;
   }
}

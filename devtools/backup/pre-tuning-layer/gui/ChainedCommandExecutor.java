package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ChainedCommandExecutor {
   private static final Pattern PARSE_PATTERN = Pattern.compile("\\{(player_all|player_fake|player|name|number|time|coords|x)\\}");
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
      if (commands != null && !commands.isEmpty()) {
         final int delay = Math.max(1, commandDelay);
         if (commands.size() == 1) {
            execute(parent, commands.get(0));
         } else {
            String first = commands.get(0);
            final List<String> rest = commands.subList(1, commands.size());
            final boolean needsDelay = CommandHelper.isFakePlayerSpawnCommand(first) && !rest.isEmpty();
            if (CommandHelper.hasPlaceholders(first)) {
               (new ChainedCommandExecutor(parent, first) {
                  @Override
                  protected void onExecutionComplete() {
                     if (needsDelay) {
                        ChainedCommandExecutor.scheduleDelayed(new ArrayList<>(rest), 0);
                     } else {
                        ChainedCommandExecutor.sendInOrder(new ArrayList<>(rest), delay);
                     }
                  }
               }).start();
            } else {
               Minecraft mc = Minecraft.getInstance();
               if (mc != null && mc.player != null) {
                  sendInOrder(new ArrayList<>(commands), delay);
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
      }
   }

   private static void sendInOrder(List<String> commands) {
      sendInOrder(commands, 1);
   }

   private static void sendInOrder(List<String> commands, int delayTicks) {
      for (int i = 0; i < commands.size(); i++) {
         delayedQueue.add(new ChainedCommandExecutor.DelayedBatch(List.of(commands.get(i)), 1 + i * delayTicks));
      }
   }

   private static void scheduleDelayed(List<String> commands, int extraDelay) {
      delayedQueue.add(new ChainedCommandExecutor.DelayedBatch(commands, 20 + extraDelay));
   }

   public static void tickDelayed() {
      if (!delayedQueue.isEmpty()) {
         Iterator<ChainedCommandExecutor.DelayedBatch> it = delayedQueue.iterator();

         while (it.hasNext()) {
            ChainedCommandExecutor.DelayedBatch batch = it.next();
            batch.remainingTicks--;
            if (batch.remainingTicks <= 0) {
               for (String cmd : batch.commands) {
                  CommandHelper.sendCommand(cmd);
               }

               it.remove();
            }
         }
      }
   }

   private void parseTypes(String command) {
      Matcher matcher = PARSE_PATTERN.matcher(command);

      while (matcher.find()) {
         String token = matcher.group(1);

         ChainedCommandExecutor.PlaceholderType type = switch (token) {
            case "player_all" -> ChainedCommandExecutor.PlaceholderType.PLAYER_ALL;
            case "player_fake" -> ChainedCommandExecutor.PlaceholderType.PLAYER_FAKE;
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
               this.parent, Component.translatable("screen.command-gui.select_player"), null, PlayerSelectorScreen.FilterMode.EXCLUDE_SELF, playerName -> {
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
      int remainingTicks;

      DelayedBatch(List<String> commands, int delayTicks) {
         this.commands = commands;
         this.remainingTicks = delayTicks;
      }
   }

   public static enum PlaceholderType {
      PLAYER_ALL,
      PLAYER_OTHER,
      PLAYER_FAKE,
      NAME,
      NUMBER,
      TIME,
      COORDS;
   }
}

package com.remrin.client.gui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.server.permissions.PermissionSet;

public final class CommandHelper {
   private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(?:player_all|player_fake|player|bot|name|number|time|coords|x)\\}");

   private CommandHelper() {
   }

   public static String validateCommandFormat(String command) {
      if (command == null) {
         return "指令为空";
      } else {
         String c = command.trim();
         if (c.isEmpty()) {
            return "指令为空";
         } else {
            if (c.startsWith("/")) {
               c = c.substring(1);
            }

            if (c.isEmpty()) {
               return "指令为空";
            } else if (PlaceholderResolver.hasPlaceholders(c)) {
               return validateWithPlaceholders(c);
            } else if (!c.contains("{bot}") && !c.contains("{player}")) {
               Minecraft mc = Minecraft.getInstance();
               if (mc != null && mc.getConnection() != null) {
                  try {
                     ClientSuggestionProvider provider = new ClientSuggestionProvider(mc.getConnection(), mc, PermissionSet.ALL_PERMISSIONS);
                     CommandDispatcher<ClientSuggestionProvider> dispatcher = mc.getConnection().getCommands();
                     String firstToken = c.split("\\s+", 2)[0];
                     if (dispatcher.getRoot().getChild(firstToken) == null) {
                        MachineDebug.log("[CmdCheck] UNKNOWN '" + c + "'");
                        return "未知指令: " + firstToken;
                     } else {
                        ParseResults<ClientSuggestionProvider> result = dispatcher.parse(c, provider);
                        if (!result.getExceptions().isEmpty()) {
                           String message = ((CommandSyntaxException)result.getExceptions().values().iterator().next()).getMessage();
                           MachineDebug.log("[CmdCheck] INVALID '" + c + "' -> " + (message == null ? "?" : message));
                           return message != null && !message.isBlank() ? "指令无效: " + message : "指令无效";
                        } else {
                           int consumedEnd = result.getContext().getRange().getEnd();
                           if (consumedEnd < c.length()) {
                              MachineDebug.log("[CmdCheck] TRAILING '" + c + "' consumed=" + consumedEnd);
                              return "指令末尾有多余内容，未能完整解析";
                           } else {
                              int lastStart = c.lastIndexOf(32) + 1;
                              if (lastStart > 0 && lastStart < c.length()) {
                                 try {
                                    Suggestions suggestions = (Suggestions)dispatcher.getCompletionSuggestions(result, lastStart)
                                       .get(500L, TimeUnit.MILLISECONDS);
                                    if (!suggestions.getList().isEmpty()) {
                                       String token = c.substring(lastStart);
                                       boolean numericLike = token.matches("[-+~^0-9.].*");
                                       boolean wordLike = token.matches("[A-Za-z0-9_]+");
                                       if (!numericLike && wordLike) {
                                          boolean hasWordSuggestions = suggestions.getList()
                                             .stream()
                                             .anyMatch(s -> !s.getText().isEmpty() && s.getText().matches("[A-Za-z0-9_]+"));
                                          boolean matched = suggestions.getList().stream().anyMatch(s -> s.getText().startsWith(token));
                                          if (hasWordSuggestions && !matched) {
                                             MachineDebug.log("[CmdCheck] BAD_ARG '" + c + "' -> " + token);
                                             return "无效参数: " + token;
                                          }
                                       }
                                    }
                                 } catch (Exception var15) {
                                 }
                              }

                              MachineDebug.log("[CmdCheck] OK '" + c + "'");
                              return null;
                           }
                        }
                     }
                  } catch (Exception var16) {
                     MachineDebug.log("[CmdCheck] checker failed for '" + c + "': " + var16);
                     return "无法验证指令格式";
                  }
               } else {
                  return "未连接服务器，无法验证指令";
               }
            } else {
               return null;
            }
         }
      }
   }

   private static String validateWithPlaceholders(String c) {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getConnection() == null) {
         return "未连接服务器，无法验证指令";
      }

      try {
         ClientSuggestionProvider provider = new ClientSuggestionProvider(mc.getConnection(), mc, PermissionSet.ALL_PERMISSIONS);
         CommandDispatcher<ClientSuggestionProvider> dispatcher = mc.getConnection().getCommands();
         String firstToken = c.split("\\s+", 2)[0];
         if (dispatcher.getRoot().getChild(firstToken) == null) {
            MachineDebug.log("[CmdCheck] UNKNOWN '" + c + "'");
            return "未知指令: " + firstToken;
         }

         List<PlaceholderResolver.PlaceholderMatch> matches = PlaceholderResolver.findPlaceholders(c);
         StringBuilder replaced = new StringBuilder(c);
         List<int[]> ranges = new ArrayList<>();
         int offset = 0;

         for (PlaceholderResolver.PlaceholderMatch match : matches) {
            String dummy = PlaceholderResolver.dummyFor(match.placeholder());
            int length = match.end() - match.start();
            int newStart = match.start() + offset;
            replaced.replace(newStart, newStart + length, dummy);
            ranges.add(new int[]{newStart, newStart + dummy.length()});
            offset += dummy.length() - length;
         }

         String cmd = replaced.toString();
         ParseResults<ClientSuggestionProvider> result = dispatcher.parse(cmd, provider);
         if (!result.getExceptions().isEmpty()) {
            String message = ((CommandSyntaxException)result.getExceptions().values().iterator().next()).getMessage();
            MachineDebug.log("[CmdCheck] PLACEHOLDER INVALID '" + cmd + "' -> " + (message == null ? "?" : message));
            return message != null && !message.isBlank() ? "指令无效: " + message : "指令无效";
         }

         int consumedEnd = result.getContext().getRange().getEnd();
         if (consumedEnd < cmd.length()) {
            MachineDebug.log("[CmdCheck] PLACEHOLDER TRAILING '" + cmd + "' consumed=" + consumedEnd);
            return "指令末尾有多余内容，未能完整解析";
         }

         for (int i = 0; i < matches.size(); i++) {
            int pos = ranges.get(i)[0];
            int nodeIndex = findNodeIndexAt(result, pos);
            if (nodeIndex < 0) {
               return "无法定位占位符位置: " + matches.get(i).placeholder();
            }

            CommandNode<?> node = result.getContext().getNodes().get(nodeIndex).getNode();
            List<? extends ParsedCommandNode<?>> path = result.getContext().getNodes().subList(0, nodeIndex);
            if (!PlaceholderResolver.isAllowed(matches.get(i).placeholder(), node, path)) {
               return "占位符 " + matches.get(i).placeholder() + " 不能用于此位置";
            }
         }

         return null;
      } catch (Exception e) {
         MachineDebug.log("[CmdCheck] placeholder checker failed for '" + c + "': " + e);
         return "无法验证指令格式";
      }
   }

   private static int findNodeIndexAt(ParseResults<?> result, int pos) {
      var nodes = result.getContext().getNodes();
      for (int i = 0; i < nodes.size(); i++) {
         if (nodes.get(i).getRange().getStart() <= pos && pos <= nodes.get(i).getRange().getEnd()) {
            return i;
         }
      }
      return -1;
   }

   public static void sendCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         if (command.startsWith("/")) {
            mc.player.connection.sendCommand(command.substring(1));
         } else {
            mc.player.connection.sendChat(command);
         }
      }
   }

   public static boolean hasPlaceholders(String command) {
      if (command == null) {
         return false;
      }
      return PLACEHOLDER_PATTERN.matcher(command).find();
   }

   public static boolean hasPlaceholders(List<String> commands) {
      if (commands == null) {
         return false;
      } else {
         for (String cmd : commands) {
            if (hasPlaceholders(cmd)) {
               return true;
            }
         }

         return false;
      }
   }

   public static Pattern getPlaceholderPattern() {
      return PLACEHOLDER_PATTERN;
   }

   public static boolean isFakePlayer(String name) {
      if (MachineNetworkManager.isFakePlayerStatesSupported()) {
         return MachineNetworkManager.isServerFakePlayer(name);
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.getConnection() != null) {
         PlayerInfo info = mc.getConnection().getPlayerInfo(name);
         if (info == null || info.getLatency() != 0) {
            return false;
         }

         return mc.player == null || !name.equals(mc.player.getName().getString());
      }

      return false;
   }

   public static boolean isFakePlayer(PlayerInfo playerInfo) {
      return isFakePlayer(playerInfo.getProfile().name());
   }

   public static boolean isFakePlayerSpawnCommand(String command) {
      if (command == null) {
         return false;
      } else {
         String cmd = command.trim().toLowerCase();
         if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
         }

         return cmd.startsWith("player ") && cmd.contains(" spawn");
      }
   }

   public static boolean isFakePlayerCommand(CommandConfig.CommandEntry entry) {
      if (entry == null) {
         return false;
      } else {
         List<String> commands = entry.getCommands();
         if (commands.isEmpty()) {
            return false;
         }
         return isFakePlayerSpawnCommand(commands.get(0));
      }
   }

   public static String formatX(double x) {
      return String.format("%.3f", x);
   }

   public static String formatY(double y) {
      return String.format("%.5f", y);
   }

   public static String formatZ(double z) {
      return String.format("%.3f", z);
   }

   public static String formatTime(int seconds) {
      if (seconds >= 3600) {
         int h = seconds / 3600;
         int m = seconds % 3600 / 60;
         int s = seconds % 60;
         return String.format("%d:%02d:%02d", h, m, s);
      } else if (seconds >= 60) {
         int m = seconds / 60;
         int s = seconds % 60;
         return String.format("%d:%02d", m, s);
      } else {
         return seconds + "s";
      }
   }

   public static String formatDuration(int totalSeconds) {
      if (totalSeconds <= 0) {
         return "--";
      } else {
         int h = totalSeconds / 3600;
         int m = totalSeconds % 3600 / 60;
         int s = totalSeconds % 60;
         if (h > 0) {
            return String.format("%dh %02dm %02ds", h, m, s);
         } else {
            if (m > 0) {
               return String.format("%dm %02ds", m, s);
            }
            return s + "s";
         }
      }
   }
}

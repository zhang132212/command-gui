package com.remrin.client.gui;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

public final class PlaceholderResolver {
   public static final String PLAYER_ALL = "{player_all}";
   public static final String PLAYER = "{player}";
   public static final String PLAYER_FAKE = "{player_fake}";
   public static final String BOT = "{bot}";
   public static final String NAME = "{name}";
   public static final String NUMBER = "{number}";
   public static final String TIME = "{time}";
   public static final String COORDS = "{coords}";
   public static final String X = "{x}";
   public static final List<String> ALL_PLACEHOLDERS = List.of(PLAYER_ALL, PLAYER, BOT, NAME, NUMBER, TIME, COORDS);

   private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(?:player_all|player_fake|player|bot|name|number|time|coords|x)\\}");

   private PlaceholderResolver() {
   }

   public static boolean hasPlaceholders(String command) {
      return command != null && PLACEHOLDER_PATTERN.matcher(command).find();
   }

   public static boolean isKnownPlaceholder(String text) {
      return text != null && PLACEHOLDER_PATTERN.matcher(text).matches();
   }

   public static List<PlaceholderMatch> findPlaceholders(String command) {
      List<PlaceholderMatch> list = new ArrayList<>();
      if (command == null) {
         return list;
      }
      Matcher matcher = PLACEHOLDER_PATTERN.matcher(command);
      while (matcher.find()) {
         list.add(new PlaceholderMatch(matcher.start(), matcher.end(), matcher.group()));
      }
      return list;
   }

   public static String normalizePlaceholder(String placeholder) {
      if (placeholder == null) {
         return "";
      }
      return switch (placeholder) {
         case PLAYER_FAKE -> BOT;
         case X -> COORDS;
         default -> placeholder;
      };
   }

   public static List<String> suggestionsForNode(CommandNode<?> parent, List<? extends ParsedCommandNode<?>> path) {
      if (parent == null) {
         return List.of();
      }
      Set<String> result = new LinkedHashSet<>();
      for (CommandNode<?> child : parent.getChildren()) {
         if (child instanceof ArgumentCommandNode<?, ?> argument) {
            result.addAll(suggestionsForArgument(argument.getType(), path));
         }
      }
      return new ArrayList<>(result);
   }

   public static List<String> suggestionsForArgument(ArgumentType<?> type, List<? extends ParsedCommandNode<?>> path) {
      if (type == null) {
         return List.of();
      }

      String typeName = type.getClass().getName().toLowerCase(Locale.ROOT);
      if (typeName.contains("carpet") || typeName.contains("fakeplayer") || typeName.contains("bot")) {
         return List.of(BOT);
      }
      if (isCarpetPlayerNode(path)) {
         return List.of(BOT);
      }

      if (type instanceof Vec3Argument
         || type instanceof BlockPosArgument
         || type instanceof ColumnPosArgument
         || type instanceof Vec2Argument
         || type instanceof RotationArgument) {
         return List.of(COORDS);
      }
      if (type instanceof TimeArgument) {
         return List.of(TIME);
      }
      if (type instanceof IntegerArgumentType
         || type instanceof DoubleArgumentType
         || type instanceof FloatArgumentType
         || type instanceof LongArgumentType) {
         return List.of(NUMBER);
      }
      if (type instanceof StringArgumentType
         || type instanceof MessageArgument
         || type instanceof ComponentArgument
         || type instanceof IdentifierArgument) {
         return List.of(NAME);
      }
      if (type instanceof EntityArgument) {
         if (isPlayersOnly((EntityArgument)type)) {
            return List.of(PLAYER_ALL, PLAYER, BOT);
         }
         return List.of(NAME);
      }
      if (type instanceof GameProfileArgument || type instanceof ScoreHolderArgument) {
         return List.of(PLAYER_ALL, PLAYER, BOT);
      }

      return List.of();
   }

   public static boolean isAllowed(String placeholder, CommandNode<?> node, List<? extends ParsedCommandNode<?>> path) {
      if (node == null || !(node instanceof ArgumentCommandNode<?, ?> argument)) {
         return false;
      }
      String normalized = normalizePlaceholder(placeholder);
      List<String> allowed = suggestionsForArgument(argument.getType(), path);
      return allowed.contains(normalized);
   }

   public static String dummyFor(String placeholder) {
      return switch (normalizePlaceholder(placeholder)) {
         case PLAYER_ALL, PLAYER -> "Steve";
         case BOT -> "Bot";
         case NAME -> "abc";
         case NUMBER -> "1";
         case TIME -> "1";
         case COORDS -> "1 2 3";
         default -> "x";
      };
   }

   private static boolean isCarpetPlayerNode(List<? extends ParsedCommandNode<?>> path) {
      if (path == null || path.isEmpty()) {
         return false;
      }
      CommandNode<?> last = path.get(path.size() - 1).getNode();
      return last instanceof LiteralCommandNode<?> literal && "player".equals(literal.getLiteral());
   }

   private static boolean isPlayersOnly(EntityArgument argument) {
      try {
         Field field = EntityArgument.class.getDeclaredField("playersOnly");
         field.setAccessible(true);
         return Boolean.TRUE.equals(field.get(argument));
      } catch (Exception e) {
         return false;
      }
   }

   public static record PlaceholderMatch(int start, int end, String placeholder) {
   }
}

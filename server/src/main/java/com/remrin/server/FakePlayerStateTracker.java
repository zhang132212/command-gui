package com.remrin.server;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FakePlayerStateTracker {
   private static boolean resolved = false;
   private static boolean supported = false;
   private static Class<?> fakePlayerClass;
   private static Method getActionPackMethod;
   private static Field actionsField;
   private static Field sneakingField;
   private static Field sprintingField;
   private static Field limitField;
   private static Field isContinuousField;
   private static Field intervalField;

   private FakePlayerStateTracker() {
   }

   private static void resolve() {
      if (!resolved) {
         resolved = true;

         try {
            fakePlayerClass = Class.forName("carpet.patches.EntityPlayerMPFake");
            Class<?> serverPlayerInterface = Class.forName("carpet.fakes.ServerPlayerInterface");
            Class<?> actionPack = Class.forName("carpet.helpers.EntityPlayerActionPack");
            Class<?> action = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
            getActionPackMethod = serverPlayerInterface.getMethod("getActionPack");
            actionsField = actionPack.getDeclaredField("actions");
            actionsField.setAccessible(true);
            sneakingField = actionPack.getDeclaredField("sneaking");
            sneakingField.setAccessible(true);
            sprintingField = actionPack.getDeclaredField("sprinting");
            sprintingField.setAccessible(true);
            limitField = action.getField("limit");
            isContinuousField = action.getDeclaredField("isContinuous");
            isContinuousField.setAccessible(true);
            intervalField = action.getField("interval");
            supported = true;
         } catch (ReflectiveOperationException var3) {
            supported = false;
         }
      }
   }

   public static boolean isSupported() {
      resolve();
      return supported;
   }

   /** Read-only readiness check using the same action pack Carpet commands manipulate. */
   public static boolean isReady(MinecraftServer server, ServerPlayer player) {
      resolve();
      if (!supported || player == null || !fakePlayerClass.isInstance(player) || !player.isAlive() || player.isRemoved()
         || player.connection == null || server.getPlayerList().getPlayer(player.getUUID()) != player
         || player.level().getEntity(player.getUUID()) != player) return false;
      try {
         Object pack = getActionPackMethod.invoke(player);
         return pack != null && actionsField.get(pack) instanceof Map;
      } catch (ReflectiveOperationException | RuntimeException e) {
         return false;
      }
   }

   public static String buildJson(MinecraftServer server) {
      resolve();
      JsonObject root = new JsonObject();
      root.addProperty("supported", supported);
      JsonObject players = new JsonObject();
      if (supported && server != null) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (fakePlayerClass.isInstance(player)) {
               JsonObject state = new JsonObject();
               state.addProperty("attack", false);
               state.addProperty("use", false);
               state.addProperty("jump", false);
               state.addProperty("attackInterval", false);
               state.addProperty("useInterval", false);
               state.addProperty("attackIntervalTicks", 0);
               state.addProperty("useIntervalTicks", 0);
               state.addProperty("sneak", false);
               state.addProperty("sprint", false);
               readState(player, state);
               players.add(player.getGameProfile().name(), state);
            }
         }
      }

      root.add("players", players);
      return root.toString();
   }

   private static void readState(ServerPlayer player, JsonObject state) {
      try {
         // One action-pack lookup and one action-map traversal per player per tick.
         Object pack = getActionPackMethod.invoke(player);
         Map<?, ?> actions = (Map<?, ?>)actionsField.get(pack);
         for (Entry<?, ?> entry : actions.entrySet()) {
            String property = switch (entry.getKey().toString()) {
               case "ATTACK" -> "attack";
               case "USE" -> "use";
               case "JUMP" -> "jump";
               default -> null;
            };
            Object action = entry.getValue();
            if (property == null || action == null || ((Number)limitField.get(action)).intValue() >= 0) {
               continue;
            }
            if (isContinuousField.getBoolean(action)) {
               state.addProperty(property, true);
            } else if (!property.equals("jump")) {
               state.addProperty(property + "Interval", true);
               state.addProperty(property + "IntervalTicks", ((Number)intervalField.get(action)).intValue());
            }
         }
         state.addProperty("sneak", sneakingField.getBoolean(pack));
         state.addProperty("sprint", sprintingField.getBoolean(pack));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
         // Preserve a usable snapshot if a Carpet implementation changes its action-pack shape.
      }
   }
}

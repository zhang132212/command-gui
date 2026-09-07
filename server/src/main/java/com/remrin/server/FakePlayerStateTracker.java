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

   public static String buildJson(MinecraftServer server) {
      resolve();
      JsonObject root = new JsonObject();
      root.addProperty("supported", supported);
      JsonObject players = new JsonObject();
      if (supported && server != null) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (fakePlayerClass.isInstance(player)) {
               JsonObject state = new JsonObject();
               state.addProperty("attack", hasPersistentAction(player, "ATTACK", true));
               state.addProperty("use", hasPersistentAction(player, "USE", true));
               state.addProperty("jump", hasPersistentAction(player, "JUMP", true));
               state.addProperty("attackInterval", hasPersistentAction(player, "ATTACK", false));
               state.addProperty("useInterval", hasPersistentAction(player, "USE", false));
               state.addProperty("attackIntervalTicks", persistentActionTicks(player, "ATTACK", false));
               state.addProperty("useIntervalTicks", persistentActionTicks(player, "USE", false));
               state.addProperty("sneak", isSneaking(player));
               state.addProperty("sprint", isSprinting(player));
               players.add(player.getGameProfile().name(), state);
            }
         }
      }

      root.add("players", players);
      return root.toString();
   }

   private static boolean hasPersistentAction(ServerPlayer player, String typeName, boolean continuous) {
      try {
         Object pack = getActionPackMethod.invoke(player);
         Map<?, ?> actions = (Map<?, ?>)actionsField.get(pack);

         for (Entry<?, ?> entry : actions.entrySet()) {
            if (entry.getKey().toString().equals(typeName)) {
               Object action = entry.getValue();
               if (action == null) {
                  return false;
               }

               int limit = ((Number)limitField.get(action)).intValue();
               if (limit >= 0) {
                  return false;
               }

               return isContinuousField.getBoolean(action) == continuous;
            }
         }

         return false;
      } catch (ReflectiveOperationException var9) {
         return false;
      }
   }

   private static int persistentActionTicks(ServerPlayer player, String typeName, boolean continuous) {
      try {
         Object pack = getActionPackMethod.invoke(player);
         Map<?, ?> actions = (Map<?, ?>)actionsField.get(pack);

         for (Entry<?, ?> entry : actions.entrySet()) {
            if (entry.getKey().toString().equals(typeName)) {
               Object action = entry.getValue();
               if (action == null) {
                  return 0;
               }

               int limit = ((Number)limitField.get(action)).intValue();
               if (limit < 0 && isContinuousField.getBoolean(action) == continuous) {
                  return ((Number)intervalField.get(action)).intValue();
               }

               return 0;
            }
         }

         return 0;
      } catch (ReflectiveOperationException var9) {
         return 0;
      }
   }

   private static boolean isSneaking(ServerPlayer player) {
      try {
         Object pack = getActionPackMethod.invoke(player);
         return sneakingField.getBoolean(pack);
      } catch (ReflectiveOperationException var2) {
         return false;
      }
   }

   private static boolean isSprinting(ServerPlayer player) {
      try {
         Object pack = getActionPackMethod.invoke(player);
         return sprintingField.getBoolean(pack);
      } catch (ReflectiveOperationException var2) {
         return false;
      }
   }
}

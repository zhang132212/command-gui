package com.remrin.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Consumer;

/** Validate wire types before Gson can coerce strings, booleans or overflowing numbers. */
final class MachineActionValidator {
   private MachineActionValidator() {
   }

   static void validate(JsonObject action) {
      requireString(action, "type");
      switch (action.get("type").getAsString()) {
         case "toggle", "delete", "refreshDetection" -> requireString(action, "machineId");
         case "setModes" -> {
            requireString(action, "machineId");
            array(required(action, "modeIds"), "modeIds", value -> string(value, "modeIds[]"));
         }
         case "editSession" -> {
            requireString(action, "machineId");
            bool(required(action, "open"), "open");
         }
         case "add", "edit" -> {
            machine(object(required(action, "machine"), "machine"));
            if (action.get("type").getAsString().equals("edit")) {
               integer(required(action, "baseRevision"), "baseRevision", true);
               if (action.get("baseRevision").getAsLong() < 0) fail("baseRevision");
            }
         }
         case "queryBlock" -> {
            optionalString(action, "dimension", false);
            for (String key : new String[]{"x", "y", "z"}) integer(required(action, key), key, false);
            if (action.has("token")) integer(action.get("token"), "token", true);
         }
         case "clearCategory" -> requireString(action, "categoryId");
         case "requestSync", "machineStatesUnsubscribe", "fakeStatesRequest", "fakeStatesUnsubscribe" -> { }
         default -> fail("type");
      }
   }

   private static void machine(JsonObject machine) {
      requireString(machine, "id");
      requireString(machine, "name");
      optionalString(machine, "description", true);
      optionalString(machine, "category", true);
      strings(machine, "bots", false);
      for (String key : new String[]{"bannedPlayers", "modeOrder", "stopModeOrder"}) strings(machine, key, true);
      integers(machine, "permissionLevel", "switchInterval", "modeInterval", "stopModeInterval");
      if (machine.has("revision")) integer(machine.get("revision"), "revision", true);
      if (machine.has("stopFollowsStart")) bool(machine.get("stopFollowsStart"), "stopFollowsStart");
      timeline(machine, "onTimeline");
      timeline(machine, "offTimeline");
      detection(machine);
      if (present(machine, "modes")) array(machine.get("modes"), "modes", value -> {
         JsonObject mode = object(value, "modes[]");
         requireString(mode, "id");
         requireString(mode, "name");
         if (mode.has("singleSelect")) bool(mode.get("singleSelect"), "singleSelect");
         integers(mode, "switchInterval");
         timeline(mode, "onTimeline");
         timeline(mode, "offTimeline");
         detection(mode);
      });
   }

   private static void timeline(JsonObject parent, String key) {
      if (!present(parent, key)) return;
      JsonObject timeline = object(parent.get(key), key);
      integers(timeline, "loopCount");
      if (present(timeline, "steps")) array(timeline.get("steps"), "steps", value -> {
         JsonObject step = object(value, "steps[]");
         optionalString(step, "kind", true);
         optionalString(step, "description", true);
         integers(step, "bot", "delay", "commandDelay");
         strings(step, "commands", true);
      });
   }

   private static void detection(JsonObject parent) {
      if (!present(parent, "detection")) return;
      JsonObject detection = object(parent.get("detection"), "detection");
      if (detection.has("enabled")) bool(detection.get("enabled"), "enabled");
      for (String key : new String[]{"dimension", "blockId", "property"}) optionalString(detection, key, true);
      integers(detection, "x", "y", "z");
      for (String key : new String[]{"onValues", "offValues", "ignoreValues"}) strings(detection, key, true);
   }

   private static void strings(JsonObject parent, String key, boolean nullable) {
      if (!parent.has(key) || nullable && parent.get(key).isJsonNull()) return;
      array(parent.get(key), key, value -> string(value, key + "[]"));
   }

   private static void integers(JsonObject parent, String... keys) {
      for (String key : keys) if (parent.has(key)) integer(parent.get(key), key, false);
   }

   private static void integer(JsonElement value, String key, boolean wide) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) fail(key);
      try {
         if (wide) value.getAsBigDecimal().longValueExact();
         else value.getAsBigDecimal().intValueExact();
      } catch (ArithmeticException | NumberFormatException error) {
         fail(key);
      }
   }

   private static void optionalString(JsonObject parent, String key, boolean nullable) {
      if (parent.has(key) && !(nullable && parent.get(key).isJsonNull())) string(parent.get(key), key);
   }

   private static void requireString(JsonObject parent, String key) {
      string(required(parent, key), key);
   }

   private static void string(JsonElement value, String key) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) fail(key);
   }

   private static void bool(JsonElement value, String key) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) fail(key);
   }

   private static JsonObject object(JsonElement value, String key) {
      if (!value.isJsonObject()) fail(key);
      return value.getAsJsonObject();
   }

   private static void array(JsonElement value, String key, Consumer<JsonElement> check) {
      if (!value.isJsonArray()) fail(key);
      value.getAsJsonArray().forEach(check);
   }

   private static JsonElement required(JsonObject parent, String key) {
      if (!parent.has(key) || parent.get(key).isJsonNull()) fail(key);
      return parent.get(key);
   }

   private static boolean present(JsonObject parent, String key) {
      return parent.has(key) && !parent.get(key).isJsonNull();
   }

   private static void fail(String key) {
      throw new IllegalArgumentException("字段格式无效: " + key);
   }
}

package com.remrin.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.remrin.server.config.MachineConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Keep the existing single-packet protocol within both its UTF and vanilla payload limits. */
final class MachineSyncBudget {
   // Reserve space for the payload identifier, UTF length and future envelope fields.
   static final int MAX_JSON_BYTES = 1048576 - 1024;
   private static final Gson GSON = new Gson();

   private MachineSyncBudget() {
   }

   static boolean fits(String json) {
      return json.length() <= MAX_JSON_BYTES && json.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES;
   }

   static boolean fits(List<MachineConfig.MachineData> machines) {
      JsonObject root = new JsonObject();
      root.addProperty("canEdit", false);
      root.addProperty("canConfig", false);
      JsonArray rows = new JsonArray();
      for (MachineConfig.MachineData machine : machines) {
         JsonObject row = GSON.toJsonTree(machine).getAsJsonObject();
         row.addProperty("running", false);
         row.addProperty("transition", "off");
         // More than the maximum UTF-8 length of a normal Minecraft player name.
         row.addProperty("editingBy", "x".repeat(64));
         row.addProperty("detected", "abnormal");
         JsonArray modes = row.getAsJsonArray("modes");
         if (modes != null) {
            for (var element : modes) {
               JsonObject mode = element.getAsJsonObject();
               mode.addProperty("running", false);
               mode.addProperty("detected", "abnormal");
               mode.addProperty("processing", false);
               mode.addProperty("transition", "off");
            }
         }
         rows.add(row);
      }
      root.add("machines", rows);
      return fits(root.toString());
   }
}

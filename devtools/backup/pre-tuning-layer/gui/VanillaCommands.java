package com.remrin.client.gui;

import com.remrin.client.config.PresetConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

public class VanillaCommands {
   public static List<VanillaCommands.CommandGroup> getGroups(String presetId) {
      List<VanillaCommands.CommandGroup> groups = new ArrayList<>();
      PresetConfig.Preset preset = PresetConfig.getPreset(presetId);
      if (preset == null) {
         return groups;
      } else {
         for (PresetConfig.CommandGroup pg : preset.groups) {
            VanillaCommands.CommandGroup group = new VanillaCommands.CommandGroup(pg.nameKey);

            for (PresetConfig.PresetCommand pc : pg.commands) {
               group.add(
                  new VanillaCommands.VanillaCommand(pc.nameKey, pc.command, pc.description, pc.minValue, pc.maxValue, pc.quickValues, pc.quickStrValues)
               );
            }

            groups.add(group);
         }

         return groups;
      }
   }

   public static List<VanillaCommands.VanillaCommand> getAllCommands(String presetId) {
      List<VanillaCommands.VanillaCommand> commands = new ArrayList<>();

      for (VanillaCommands.CommandGroup group : getGroups(presetId)) {
         commands.addAll(group.commands);
      }

      return commands;
   }

   public static class CommandGroup {
      public final String nameKey;
      public final List<VanillaCommands.VanillaCommand> commands;

      public CommandGroup(String nameKey) {
         this.nameKey = nameKey;
         this.commands = new ArrayList<>();
      }

      public VanillaCommands.CommandGroup add(VanillaCommands.VanillaCommand cmd) {
         this.commands.add(cmd);
         return this;
      }
   }

   public static class VanillaCommand {
      public final String nameKey;
      public final String command;
      public final String description;
      public final Integer minValue;
      public final Integer maxValue;
      public final int[] quickValues;
      public final String[] quickStrValues;

      public VanillaCommand(String nameKey, String command, String description, Integer minValue, Integer maxValue, int[] quickValues, String[] quickStrValues) {
         this.nameKey = nameKey;
         this.command = command;
         this.description = description;
         this.minValue = minValue;
         this.maxValue = maxValue;
         this.quickValues = quickValues;
         this.quickStrValues = quickStrValues;
      }

      public Component getName() {
         return Component.translatable(this.nameKey);
      }

      public Component getDescription() {
         if (this.description != null && !this.description.isEmpty()) {
            return Component.translatable(this.description);
         }
         return null;
      }
   }
}

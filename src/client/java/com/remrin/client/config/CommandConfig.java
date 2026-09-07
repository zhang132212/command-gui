package com.remrin.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.remrin.CommandGUI;
import com.remrin.client.gui.CommandHelper;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.fabricmc.loader.api.FabricLoader;

public class CommandConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("command-gui").resolve("presets").resolve("custom.json");
   private static final Type CONFIG_TYPE = (new TypeToken<CommandConfig.ConfigData>() {
   }).getType();
   private static final String DEFAULT_CATEGORY = "default";
   private static CommandConfig.ConfigData configData = new CommandConfig.ConfigData();
   private static final Map<String, CommandConfig.CommandEntry> pendingOverrides = new LinkedHashMap<>();
   private static final Map<String, String> pendingNewCategories = new HashMap<>();
   private static final Set<String> pendingRemovals = new LinkedHashSet<>();

   public static void load() {
      if (Files.exists(CONFIG_PATH)) {
         try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            CommandConfig.ConfigData loaded = (CommandConfig.ConfigData)GSON.fromJson(reader, CONFIG_TYPE);
            if (loaded != null && loaded.categories != null && !loaded.categories.isEmpty()) {
               configData = loaded;
            }
         } catch (Exception var5) {
            CommandGUI.LOGGER.error("Failed to load command config", var5);
         }
      }
   }

   public static void save() {
      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Path temp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");

         try (Writer writer = Files.newBufferedWriter(temp)) {
            GSON.toJson(configData, writer);
         }

         try {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var5) {
            Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var7) {
         CommandGUI.LOGGER.error("Failed to save command config", var7);
      }
   }

   public static List<CommandConfig.Category> getCategories() {
      return configData.categories;
   }

   public static CommandConfig.Category getCategory(String id) {
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.id.equals(id)) {
            return cat;
         }
      }

      return null;
   }

   public static CommandConfig.Category getDefaultCategory() {
      return getCategory("default");
   }

   public static void addCategory(String id, String nameKey) {
      if (getCategory(id) == null) {
         configData.categories.add(new CommandConfig.Category(id, nameKey));
         save();
      }
   }

   public static void addCategory(String id, String nameKey, String displayName) {
      if (getCategory(id) == null) {
         CommandConfig.Category cat = new CommandConfig.Category(id, nameKey);
         cat.displayName = displayName;
         configData.categories.add(cat);
         save();
      }
   }

   public static void removeCategory(String id) {
      if (!id.equals("default")) {
         configData.categories.removeIf(cat -> cat.id.equals(id));
         save();
      }
   }

   public static void updateCategory(String id, String nameKey) {
      CommandConfig.Category cat = getCategory(id);
      if (cat != null) {
         cat.nameKey = nameKey;
         save();
      }
   }

   public static void renameCategory(String id, String displayName) {
      CommandConfig.Category cat = getCategory(id);
      if (cat != null && !id.equals("default")) {
         if (displayName != null && !displayName.trim().isEmpty()) {
            cat.displayName = displayName.trim();
            cat.nameKey = null;
            save();
         }
      }
   }

   public static Map<String, CommandConfig.CommandEntry> getCommands() {
      LinkedHashMap<String, CommandConfig.CommandEntry> allCommands = new LinkedHashMap<>();

      for (CommandConfig.Category cat : configData.categories) {
         allCommands.putAll(cat.commands);
      }

      return allCommands;
   }

   public static Map<String, CommandConfig.CommandEntry> getCommandsByCategory(String categoryId) {
      CommandConfig.Category cat = getCategory(categoryId);
      if (cat != null) {
         return cat.commands;
      }
      return new LinkedHashMap<>();
   }

   public static String findCommandCategory(String name) {
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.commands.containsKey(name)) {
            return cat.id;
         }
      }

      return null;
   }

   public static String nextDefaultCommandName() {
      Map<String, CommandConfig.CommandEntry> allCommands = getCommands();
      int n = 1;

      while (allCommands.containsKey("command_" + n)) {
         n++;
      }

      return "command_" + n;
   }

   public static void addCommand(String name, String command, String description) {
      addCommand("default", name, command, description);
   }

   public static void addCommand(String categoryId, String name, String command, String description) {
      CommandConfig.Category cat = getCategory(categoryId);
      if (cat == null) {
         cat = getDefaultCategory();
      }

      cat.commands.put(name, new CommandConfig.CommandEntry(command, description));
      save();
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description) {
      addCommandMulti(categoryId, name, commands, description, 1);
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description, int commandDelay) {
      addCommandMulti(categoryId, name, commands, description, commandDelay, "");
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description, int commandDelay, String shortcut) {
      CommandConfig.Category cat = getCategory(categoryId);
      if (cat == null) {
         cat = getDefaultCategory();
      }

      CommandConfig.CommandEntry entry = new CommandConfig.CommandEntry(commands, description);
      entry.commandDelay = Math.max(1, commandDelay);
      entry.shortcut = shortcut != null ? shortcut : "";
      cat.commands.put(name, entry);
      save();
   }

   public static void removeCommand(String name) {
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.commands.remove(name) != null) {
            save();
            return;
         }
      }
   }

   public static void updateCommand(String name, String command, String description) {
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.commands.containsKey(name)) {
            cat.commands.put(name, new CommandConfig.CommandEntry(command, description));
            save();
            return;
         }
      }
   }

   public static void updateCommandMulti(String name, List<String> commands, String description) {
      updateCommandMulti(name, commands, description, 1);
   }

   public static void applyPendingCommand(String categoryId, String name, List<String> commands, String description, int commandDelay) {
      if (name != null && !name.isEmpty()) {
         CommandConfig.CommandEntry entry = new CommandConfig.CommandEntry(commands, description);
         entry.commandDelay = Math.max(1, commandDelay);
         pendingOverrides.put(name, entry);
         pendingNewCategories.put(name, categoryId != null ? categoryId : "default");
         pendingRemovals.remove(name);
      }
   }

   public static void applyPendingRemoval(String name) {
      if (name != null && !name.isEmpty()) {
         pendingRemovals.add(name);
         pendingOverrides.remove(name);
         pendingNewCategories.remove(name);
      }
   }

   public static boolean isPending(String name) {
      return pendingOverrides.containsKey(name) || pendingRemovals.contains(name);
   }

   public static boolean isPendingRemoval(String name) {
      return pendingRemovals.contains(name);
   }

   public static Set<String> getPendingNames() {
      return Collections.unmodifiableSet(pendingOverrides.keySet());
   }

   public static String getPendingCategory(String name) {
      return pendingNewCategories.getOrDefault(name, "default");
   }

   public static boolean hasPending() {
      return !pendingOverrides.isEmpty() || !pendingRemovals.isEmpty();
   }

   public static CommandConfig.CommandEntry getPendingEntry(String name) {
      return pendingOverrides.get(name);
   }

   public static void commitPending() {
      for (Entry<String, CommandConfig.CommandEntry> e : pendingOverrides.entrySet()) {
         String name = e.getKey();
         CommandConfig.CommandEntry entry = e.getValue();
         String categoryId = pendingNewCategories.getOrDefault(name, "default");
         String existing = findCommandCategory(name);
         if (existing != null) {
            updateCommandMulti(name, entry.getCommands(), entry.description, entry.commandDelay);
         } else {
            addCommandMulti(categoryId, name, entry.getCommands(), entry.description, entry.commandDelay);
         }
      }

      for (String name : pendingRemovals) {
         removeCommand(name);
      }

      pendingOverrides.clear();
      pendingNewCategories.clear();
      pendingRemovals.clear();
   }

   public static void updateCommandMulti(String name, List<String> commands, String description, int commandDelay) {
      updateCommandMulti(name, commands, description, commandDelay, "");
   }

   public static void updateCommandMulti(String name, List<String> commands, String description, int commandDelay, String shortcut) {
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.commands.containsKey(name)) {
            CommandConfig.CommandEntry entry = new CommandConfig.CommandEntry(commands, description);
            entry.commandDelay = Math.max(1, commandDelay);
            entry.shortcut = shortcut != null ? shortcut : "";
            cat.commands.put(name, entry);
            save();
            return;
         }
      }
   }

   public static void moveCommand(String name, String toCategoryId) {
      CommandConfig.CommandEntry entry = null;

      for (CommandConfig.Category cat : configData.categories) {
         entry = cat.commands.remove(name);
         if (entry != null) {
            break;
         }
      }

      if (entry != null) {
         CommandConfig.Category toCat = getCategory(toCategoryId);
         if (toCat != null) {
            toCat.commands.put(name, entry);
            save();
         }
      }
   }

   public static class Category {
      public String id;
      public String nameKey;
      public String displayName;
      public LinkedHashMap<String, CommandConfig.CommandEntry> commands = new LinkedHashMap<>();

      public Category() {
      }

      public Category(String id, String nameKey) {
         this.id = id;
         this.nameKey = nameKey;
      }

      public String getDisplayName() {
         if (this.displayName != null && !this.displayName.isEmpty()) {
            return this.displayName;
         }
         return null;
      }
   }

   public static class CommandEntry {
      public String command;
      public List<String> commands;
      public String description;
      public int commandDelay = 1;
      public String shortcut = "";

      public CommandEntry() {
      }

      public CommandEntry(String command, String description) {
         this.command = command;
         this.description = description != null ? description : "";
      }

      public CommandEntry(List<String> commands, String description) {
         this.commands = commands;
         this.command = commands != null && !commands.isEmpty() ? commands.get(0) : "";
         this.description = description != null ? description : "";
      }

      public List<String> getCommands() {
         if (this.commands != null && !this.commands.isEmpty()) {
            return this.commands;
         } else {
            if (this.command != null && !this.command.isEmpty()) {
               return List.of(this.command);
            }
            return List.of();
         }
      }

      public boolean hasPlaceholders() {
         return CommandHelper.hasPlaceholders(this.getCommands());
      }
   }

   public static class ConfigData {
      public List<CommandConfig.Category> categories = new ArrayList<>();

      public ConfigData() {
         this.categories.add(new CommandConfig.Category("default", "screen.command-gui.category.default"));
      }
   }
}

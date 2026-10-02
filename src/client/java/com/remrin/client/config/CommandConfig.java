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
   private static final Map<CommandKey, CommandConfig.CommandEntry> pendingOverrides = new LinkedHashMap<>();
   private static final Set<CommandKey> pendingRemovals = new LinkedHashSet<>();

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
      persist();
   }

   private static boolean persist() {
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
         return true;
      } catch (IOException var7) {
         CommandGUI.LOGGER.error("Failed to save command config", var7);
         return false;
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
      String result = null;
      for (CommandConfig.Category cat : configData.categories) {
         if (cat.commands.containsKey(name)) {
            // The legacy name-only API is safe only when that name is unique.
            if (result != null) {
               return null;
            }
            result = cat.id;
         }
      }
      return result;
   }

   public static CommandEntry getCommand(String categoryId, String name) {
      Category category = getCategory(categoryId);
      return category == null ? null : category.commands.get(name);
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
      addCommandMulti(categoryId, name, List.of(command), description);
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description) {
      addCommandMulti(categoryId, name, commands, description, 1);
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description, int commandDelay) {
      addCommandMulti(categoryId, name, commands, description, commandDelay, "");
   }

   public static void addCommandMulti(String categoryId, String name, List<String> commands, String description, int commandDelay, String shortcut) {
      String target = getCategory(categoryId) != null ? categoryId : DEFAULT_CATEGORY;
      saveCommand(null, null, target, name, commands, description, commandDelay, shortcut);
   }

   /** Returns a user-facing error, or null after an atomic successful save. */
   public static String saveCommand(String sourceCategoryId, String oldName, String targetCategoryId, String name,
      List<String> commands, String description, int commandDelay, String shortcut) {
      Category source = oldName == null ? null : getCategory(sourceCategoryId);
      Category target = getCategory(targetCategoryId);
      if (oldName != null && (source == null || !source.commands.containsKey(oldName))) {
         return "原指令已不存在，请返回列表刷新后重试";
      }
      if (target == null) {
         return "目标分类已不存在，请重新选择分类";
      }
      if (name == null || name.isBlank() || commands == null || commands.isEmpty()) {
         return "指令名称和内容不能为空";
      }
      if (target.commands.containsKey(name) && !(source == target && name.equals(oldName))) {
         return "目标分类已有同名指令「" + name + "」，请修改名称或选择其他分类";
      }

      LinkedHashMap<String, CommandEntry> previousTarget = new LinkedHashMap<>(target.commands);
      LinkedHashMap<String, CommandEntry> previousSource = source == null || source == target ? null : new LinkedHashMap<>(source.commands);
      CommandEntry entry = new CommandEntry(new ArrayList<>(commands), description);
      entry.commandDelay = Math.max(1, commandDelay);
      entry.shortcut = shortcut != null ? shortcut : "";
      if (source != null && (source != target || !name.equals(oldName))) {
         source.commands.remove(oldName);
      }
      target.commands.put(name, entry);
      if (!persist()) {
         target.commands.clear();
         target.commands.putAll(previousTarget);
         if (previousSource != null) {
            source.commands.clear();
            source.commands.putAll(previousSource);
         }
         return "保存配置失败，原指令已保留，请检查配置目录后重试";
      }
      return null;
   }

   public static void removeCommand(String name) {
      String categoryId = findCommandCategory(name);
      if (categoryId != null) {
         removeCommand(categoryId, name);
      }
   }

   public static String removeCommand(String categoryId, String name) {
      Category category = getCategory(categoryId);
      if (category == null || !category.commands.containsKey(name)) {
         return "原指令已不存在，请返回列表刷新后重试";
      }
      LinkedHashMap<String, CommandEntry> previous = new LinkedHashMap<>(category.commands);
      category.commands.remove(name);
      if (!persist()) {
         category.commands.clear();
         category.commands.putAll(previous);
         return "保存配置失败，原指令已保留，请检查配置目录后重试";
      }
      return null;
   }

   public static void updateCommand(String name, String command, String description) {
      updateCommandMulti(name, List.of(command), description, 1);
   }

   public static void updateCommandMulti(String name, List<String> commands, String description) {
      updateCommandMulti(name, commands, description, 1);
   }

   public static void applyPendingCommand(String categoryId, String name, List<String> commands, String description, int commandDelay) {
      if (name != null && !name.isEmpty()) {
         CommandConfig.CommandEntry entry = new CommandConfig.CommandEntry(commands, description);
         entry.commandDelay = Math.max(1, commandDelay);
         CommandKey key = new CommandKey(categoryId != null ? categoryId : DEFAULT_CATEGORY, name);
         pendingOverrides.put(key, entry);
         pendingRemovals.remove(key);
      }
   }

   public static void applyPendingRemoval(String name) {
      String categoryId = findCommandCategory(name);
      if (categoryId == null && !getCommands().containsKey(name)) {
         CommandKey pending = uniquePendingKey(name);
         categoryId = pending == null ? null : pending.categoryId();
      }
      if (categoryId != null) {
         applyPendingRemoval(categoryId, name);
      }
   }

   public static void applyPendingRemoval(String categoryId, String name) {
      if (categoryId != null && name != null && !name.isEmpty()) {
         CommandKey key = new CommandKey(categoryId, name);
         if (getCommand(categoryId, name) != null) {
            pendingRemovals.add(key);
         } else {
            pendingRemovals.remove(key);
         }
         pendingOverrides.remove(key);
      }
   }

   public static boolean isPending(String name) {
      return pendingOverrides.keySet().stream().anyMatch(key -> key.name().equals(name))
         || pendingRemovals.stream().anyMatch(key -> key.name().equals(name));
   }

   public static boolean isPending(String categoryId, String name) {
      CommandKey key = new CommandKey(categoryId, name);
      return pendingOverrides.containsKey(key) || pendingRemovals.contains(key);
   }

   public static boolean isPendingRemoval(String name) {
      String categoryId = findCommandCategory(name);
      return categoryId != null && isPendingRemoval(categoryId, name);
   }

   public static boolean isPendingRemoval(String categoryId, String name) {
      return pendingRemovals.contains(new CommandKey(categoryId, name));
   }

   public static Set<String> getPendingNames() {
      Set<String> names = new LinkedHashSet<>();
      pendingOverrides.keySet().forEach(key -> names.add(key.name()));
      return Collections.unmodifiableSet(names);
   }

   public static Set<CommandKey> getPendingKeys() {
      return Collections.unmodifiableSet(pendingOverrides.keySet());
   }

   public static String getPendingCategory(String name) {
      CommandKey key = uniquePendingKey(name);
      return key != null ? key.categoryId() : DEFAULT_CATEGORY;
   }

   public static boolean hasPending() {
      return !pendingOverrides.isEmpty() || !pendingRemovals.isEmpty();
   }

   public static CommandConfig.CommandEntry getPendingEntry(String name) {
      CommandKey key = uniquePendingKey(name);
      return key == null ? null : pendingOverrides.get(key);
   }

   public static CommandConfig.CommandEntry getPendingEntry(String categoryId, String name) {
      return pendingOverrides.get(new CommandKey(categoryId, name));
   }

   private static CommandKey uniquePendingKey(String name) {
      CommandKey result = null;
      for (CommandKey key : pendingOverrides.keySet()) {
         if (key.name().equals(name)) {
            if (result != null) return null;
            result = key;
         }
      }
      return result;
   }

   public static void commitPending() {
      for (Entry<CommandKey, CommandConfig.CommandEntry> e : new ArrayList<>(pendingOverrides.entrySet())) {
         CommandKey key = e.getKey();
         String name = key.name();
         CommandConfig.CommandEntry entry = e.getValue();
         String categoryId = key.categoryId();
         boolean existing = getCommand(categoryId, name) != null;
         if (saveCommand(existing ? categoryId : null, existing ? name : null, categoryId, name,
            entry.getCommands(), entry.description, entry.commandDelay, entry.shortcut) == null) {
            pendingOverrides.remove(key);
         }
      }

      for (CommandKey key : new ArrayList<>(pendingRemovals)) {
         if (removeCommand(key.categoryId(), key.name()) == null) {
            pendingRemovals.remove(key);
         }
      }
   }

   public static void updateCommandMulti(String name, List<String> commands, String description, int commandDelay) {
      updateCommandMulti(name, commands, description, commandDelay, "");
   }

   public static void updateCommandMulti(String name, List<String> commands, String description, int commandDelay, String shortcut) {
      String categoryId = findCommandCategory(name);
      if (categoryId != null) {
         saveCommand(categoryId, name, categoryId, name, commands, description, commandDelay, shortcut);
      }
   }

   public static void moveCommand(String name, String toCategoryId) {
      String categoryId = findCommandCategory(name);
      if (categoryId != null) {
         moveCommand(categoryId, name, toCategoryId);
      }
   }

   public static String moveCommand(String categoryId, String name, String toCategoryId) {
      CommandEntry entry = getCommand(categoryId, name);
      if (entry == null) {
         return "原指令已不存在，请返回列表刷新后重试";
      }
      return saveCommand(categoryId, name, toCategoryId, name, entry.getCommands(), entry.description, entry.commandDelay, entry.shortcut);
   }

   public static record CommandKey(String categoryId, String name) {
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

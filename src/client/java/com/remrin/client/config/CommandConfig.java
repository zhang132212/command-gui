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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Custom command configuration manager. Organizes user-created commands by category
 * ({@link Category}) and persists them as JSON to {@code config/command-gui/presets/custom.json}.
 * Supports adding, removing, updating, and querying categories and commands, as well as moving
 * commands between categories.
 */
public class CommandConfig {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
      .resolve("command-gui").resolve("presets").resolve("custom.json");
  private static final Type CONFIG_TYPE = new TypeToken<ConfigData>() {
  }.getType();

  /**
   * New commands are placed in this category by default when no category is specified
   */
  private static final String DEFAULT_CATEGORY = "default";
  private static ConfigData configData = new ConfigData();

  public static void load() {
    if (Files.exists(CONFIG_PATH)) {
      try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
        ConfigData loaded = GSON.fromJson(reader, CONFIG_TYPE);
        if (loaded != null && loaded.categories != null && !loaded.categories.isEmpty()) {
          configData = loaded;
        }
      } catch (Exception e) {
        CommandGUI.LOGGER.error("Failed to load command config", e);
      }
    }
  }

  public static void save() {
    try {
      Files.createDirectories(CONFIG_PATH.getParent());
      try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
        GSON.toJson(configData, writer);
      }
    } catch (IOException e) {
      CommandGUI.LOGGER.error("Failed to save command config", e);
    }
  }

  public static List<Category> getCategories() {
    return configData.categories;
  }

  public static Category getCategory(String id) {
    for (Category cat : configData.categories) {
      if (cat.id.equals(id)) {
        return cat;
      }
    }
    return null;
  }

  public static Category getDefaultCategory() {
    return getCategory(DEFAULT_CATEGORY);
  }

  public static void addCategory(String id, String nameKey) {
    if (getCategory(id) == null) {
      configData.categories.add(new Category(id, nameKey));
      save();
    }
  }

  public static void addCategory(String id, String nameKey, String displayName) {
    if (getCategory(id) == null) {
      Category cat = new Category(id, nameKey);
      cat.displayName = displayName;
      configData.categories.add(cat);
      save();
    }
  }

  public static void removeCategory(String id) {
    if (!id.equals(DEFAULT_CATEGORY)) {
      configData.categories.removeIf(cat -> cat.id.equals(id));
      save();
    }
  }

  public static void updateCategory(String id, String nameKey) {
    Category cat = getCategory(id);
    if (cat != null) {
      cat.nameKey = nameKey;
      save();
    }
  }

  public static Map<String, CommandEntry> getCommands() {
    LinkedHashMap<String, CommandEntry> allCommands = new LinkedHashMap<>();
    for (Category cat : configData.categories) {
      allCommands.putAll(cat.commands);
    }
    return allCommands;
  }

  public static Map<String, CommandEntry> getCommandsByCategory(String categoryId) {
    Category cat = getCategory(categoryId);
    return cat != null ? cat.commands : new LinkedHashMap<>();
  }

  public static String findCommandCategory(String name) {
    for (Category cat : configData.categories) {
      if (cat.commands.containsKey(name)) {
        return cat.id;
      }
    }
    return null;
  }

  /**
   * Generates the next available default command name ({@code command_1}, {@code command_2}, ...),
   * skipping names that are already in use.
   */
  public static String nextDefaultCommandName() {
    Map<String, CommandEntry> allCommands = getCommands();
    int n = 1;
    while (allCommands.containsKey("command_" + n)) {
      n++;
    }
    return "command_" + n;
  }

  public static void addCommand(String name, String command, String description) {
    addCommand(DEFAULT_CATEGORY, name, command, description);
  }

  public static void addCommand(String categoryId, String name, String command,
      String description) {
    Category cat = getCategory(categoryId);
    if (cat == null) {
      cat = getDefaultCategory();
    }
    cat.commands.put(name, new CommandEntry(command, description));
    save();
  }

  public static void addCommandMulti(String categoryId, String name, List<String> commands,
      String description) {
    Category cat = getCategory(categoryId);
    if (cat == null) {
      cat = getDefaultCategory();
    }
    cat.commands.put(name, new CommandEntry(commands, description));
    save();
  }

  public static void removeCommand(String name) {
    for (Category cat : configData.categories) {
      if (cat.commands.remove(name) != null) {
        save();
        return;
      }
    }
  }

  public static void updateCommand(String name, String command, String description) {
    for (Category cat : configData.categories) {
      if (cat.commands.containsKey(name)) {
        cat.commands.put(name, new CommandEntry(command, description));
        save();
        return;
      }
    }
  }

  public static void updateCommandMulti(String name, List<String> commands, String description) {
    for (Category cat : configData.categories) {
      if (cat.commands.containsKey(name)) {
        cat.commands.put(name, new CommandEntry(commands, description));
        save();
        return;
      }
    }
  }

  /**
   * Removes a command from its current category and adds it to the target category. Does nothing if
   * the command is not found in any category.
   */
  public static void moveCommand(String name, String toCategoryId) {
    CommandEntry entry = null;
    for (Category cat : configData.categories) {
      entry = cat.commands.remove(name);
		if (entry != null) {
			break;
		}
    }
    if (entry != null) {
      Category toCat = getCategory(toCategoryId);
      if (toCat != null) {
        toCat.commands.put(name, entry);
        save();
      }
    }
  }

  public static class ConfigData {

    public List<Category> categories = new ArrayList<>();

    public ConfigData() {
      categories.add(new Category(DEFAULT_CATEGORY, "screen.command-gui.category.default"));
    }
  }

  public static class Category {

    public String id;
    public String nameKey;
    public String displayName;
    public LinkedHashMap<String, CommandEntry> commands = new LinkedHashMap<>();

    public Category() {
    }

    public Category(String id, String nameKey) {
      this.id = id;
      this.nameKey = nameKey;
    }

    public String getDisplayName() {
      if (displayName != null && !displayName.isEmpty()) {
        return displayName;
      }
      return null;
    }
  }

  /**
   * A command entry representing a single executable command or a sequence of commands. The
   * {@code commands} list takes priority over the single {@code command} field; if only
   * {@code command} is present, it is treated as a single-item list.
   */
  public static class CommandEntry {

    public String command;
    public List<String> commands;
    public String description;

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
      if (commands != null && !commands.isEmpty()) {
        return commands;
      }
      if (command != null && !command.isEmpty()) {
        return List.of(command);
      }
      return List.of();
    }

    public boolean hasPlaceholders() {
      return CommandHelper.hasPlaceholders(getCommands());
    }
  }
}

package com.remrin.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.remrin.server.MachineMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side machine switch configuration. Persists the machine list as JSON to
 * {@code config/command-gui-server/machines.json}.
 * <p>
 * The data model ({@link MachineData}, {@link Timeline}, {@link Step}) is intentionally kept in
 * plain public fields with stable names, because it is serialized to JSON, sent to the client over
 * the network, and parsed independently by the client mod.
 */
public final class MachineConfig {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
      .resolve("command-gui-server").resolve("machines.json");
  private static final Type CONFIG_TYPE = new TypeToken<ConfigData>() {
  }.getType();

  private static ConfigData configData = new ConfigData();

  private MachineConfig() {
  }

  public static void load() {
    if (Files.exists(CONFIG_PATH)) {
      try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
        ConfigData loaded = GSON.fromJson(reader, CONFIG_TYPE);
        if (loaded != null && loaded.machines != null) {
          configData = loaded;
        }
      } catch (Exception e) {
        MachineMod.LOGGER.error("Failed to load machine config", e);
      }
    }
  }

  public static void save() {
    try {
      Files.createDirectories(CONFIG_PATH.getParent());
      try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
        GSON.toJson(configData, writer);
      }
    } catch (IOException e) {
      MachineMod.LOGGER.error("Failed to save machine config", e);
    }
  }

  public static List<MachineData> getMachines() {
    return configData.machines;
  }

  public static MachineData getMachine(String id) {
    for (MachineData machine : configData.machines) {
      if (machine.id != null && machine.id.equals(id)) {
        return machine;
      }
    }
    return null;
  }

  /**
   * Adds a machine (with a fresh revision) and persists the config. Returns {@code false} if a
   * machine with the same id already exists (callers should de-duplicate ids beforehand).
   */
  public static boolean addMachine(MachineData machine) {
    if (getMachine(machine.id) != null) {
      return false;
    }
    machine.revision = 1;
    configData.machines.add(machine);
    save();
    return true;
  }

  /**
   * Replaces a machine, keeping its revision and incrementing it. Returns {@code false} if no
   * machine with the given id exists.
   */
  public static boolean updateMachine(MachineData machine) {
    for (int i = 0; i < configData.machines.size(); i++) {
      if (configData.machines.get(i).id.equals(machine.id)) {
        machine.revision = configData.machines.get(i).revision + 1;
        configData.machines.set(i, machine);
        save();
        return true;
      }
    }
    return false;
  }

  public static boolean removeMachine(String id) {
    boolean removed = configData.machines.removeIf(m -> m.id != null && m.id.equals(id));
    if (removed) {
      save();
    }
    return removed;
  }

  /**
   * A single machine switch: identity, display info, managed bots, permission rules, the boot /
   * shutdown processes and optional independently-toggleable modes.
   */
  public static class MachineData {

    public String id;
    public String name = "";
    public String description = "";
    /** Optional display category; machines with the same category form a group in the GUI. */
    public String category = "";
    /** Bot (fake player) names this machine manages; timeline steps reference these by index. */
    public List<String> bots = new ArrayList<>();
    /** Permission level (0-4) required to toggle this machine; used when allowedPlayers is empty. */
    public int permissionLevel = 2;
    /** Optional player-name whitelist; when non-empty it overrides permissionLevel. */
    public List<String> allowedPlayers = new ArrayList<>();
    /** Boot process: executed when the machine switch is turned on. */
    public Timeline onTimeline = new Timeline();
    /** Shutdown process: executed when the machine switch is turned off. */
    public Timeline offTimeline = new Timeline();
    /** Additional work modes, fully independent from the switch; any combination can run. */
    public List<ModeData> modes = new ArrayList<>();
    /** Optional block-state detection that locks the switch; null = not configured. */
    public DetectionData detection;
    /**
     * Switch lock window (ticks): after booting or shutting down the machine, further switch
     * actions are rejected until this many ticks have passed. Default 20 (1 second).
     */
    public int switchInterval = 20;
    /**
     * Multi-mode launch preset: mode ids in execution priority order (first = executed first).
     * Empty = no preset (selected modes start simultaneously). Used together with
     * {@link #modeInterval}.
     */
    public List<String> modeOrder = new ArrayList<>();
    /** Ticks between consecutive mode boot processes when several are launched at once. */
    public int modeInterval = 0;
    /**
     * Multi-mode stop preset: mode ids in shutdown priority order. When
     * {@link #stopFollowsStart} is true, {@link #modeOrder}/{@link #modeInterval} are used for
     * stops instead.
     */
    public List<String> stopModeOrder = new ArrayList<>();
    /** Ticks between consecutive mode shutdown processes when several are stopped at once. */
    public int stopModeInterval = 0;
    /** When true, stopping modes uses the start preset's order and interval. */
    public boolean stopFollowsStart = false;
    /** Config revision, incremented on every add/edit. Used as an optimistic-lock backstop. */
    public int revision = 0;
  }

  /**
   * A work mode of a machine: a named pair of processes with the same structure as the switch.
   * The shutdown process is optional — when empty, stopping the mode simply stops its scripts.
   */
  public static class ModeData {

    public String id;
    public String name = "";
    /** Boot process: executed when the mode is turned on. */
    public Timeline onTimeline = new Timeline();
    /** Shutdown process (optional): executed when the mode is turned off. */
    public Timeline offTimeline = new Timeline();
    /**
     * When true, only one such mode may run at a time: starting one stops any other running
     * single-select mode (after its shutdown process, then the combined interval).
     */
    public boolean singleSelect = false;
    /**
     * Switch lock window (ticks) for this mode, same semantics as {@link MachineData#switchInterval}.
     * 0 = use the machine's switch interval.
     */
    public int switchInterval = 0;
    /** Optional block-state detection that locks the mode; null = not configured. */
    public DetectionData detection;
  }

  /**
   * Block-state detection for the machine switch. When enabled, the block at the configured
   * position is read (in-memory when the chunk is loaded, otherwise directly from the region
   * file); its property value decides whether the machine is considered ON, OFF or ABNORMAL.
   * The on/off value sets must both be non-empty; every other value (and any block mismatch or
   * missing block) is ABNORMAL. When ABNORMAL, the switch cannot be toggled.
   */
  public static class DetectionData {

    public boolean enabled = false;
    /** Dimension key, e.g. {@code minecraft:overworld}. */
    public String dimension = "minecraft:overworld";
    public int x = 0;
    public int y = 0;
    public int z = 0;
    /** Block id, e.g. {@code minecraft:lever}; "minecraft:" prefix is optional. */
    public String blockId = "";
    /** Block state property name, e.g. {@code powered} or {@code lit}. */
    public String property = "";
    /** Property values (strings) that mean the machine is ON. */
    public List<String> onValues = new ArrayList<>();
    /** Property values (strings) that mean the machine is OFF. */
    public List<String> offValues = new ArrayList<>();
  }

  /**
   * A tick-based script timeline: a flat list of steps executed in order. The timeline is executed
   * {@code loopCount} times in total: {@code 0} = run once (no loop), {@code -1} = loop forever,
   * {@code N > 0} = loop N times then stop.
   */
  public static class Timeline {

    public int loopCount = 0;
    public List<Step> steps = new ArrayList<>();
  }

  /**
   * A single timeline step. {@code delay} is the number of ticks to wait after the previous step
   * executed (0 = run as soon as possible). Commands may contain {@code {bot}} (this step's bot
   * name) and {@code {player}} (the name of the player who triggered the machine).
   */
  public static class Step {

    public int delay = 0;
    public int bot = 0;
    public List<String> commands = new ArrayList<>();
  }

  public static class ConfigData {

    public List<MachineData> machines = new ArrayList<>();
    /**
     * Player names allowed to create / edit / delete machines even without operator permission.
     * Whitelisted non-OP editors cannot see the permission-level / allowed-players config rows.
     */
    public List<String> editorWhitelist = new ArrayList<>();
  }

  /**
   * Returns the editor whitelist (player names who may manage machines without being OP).
   */
  public static List<String> getEditorWhitelist() {
    return configData.editorWhitelist;
  }

  /**
   * Adds a player to the editor whitelist and persists. Returns false if already present.
   */
  public static boolean addEditorWhitelist(String name) {
    for (String existing : configData.editorWhitelist) {
      if (existing.equalsIgnoreCase(name)) {
        return false;
      }
    }
    configData.editorWhitelist.add(name);
    save();
    return true;
  }

  /**
   * Removes a player from the editor whitelist and persists. Returns false if not present.
   */
  public static boolean removeEditorWhitelist(String name) {
    boolean removed = configData.editorWhitelist.removeIf(e -> e.equalsIgnoreCase(name));
    if (removed) {
      save();
    }
    return removed;
  }
}

package com.remrin.client.machine;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side data model for machine switches. Field names intentionally mirror the server mod's
 * {@code MachineConfig} so the JSON exchanged over the network parses correctly on both sides.
 */
public final class MachineModels {

  private MachineModels() {
  }

  /**
   * A machine switch as synced from the server. {@code running} is the script runtime state and
   * {@code detected} is the block-detection state ("on"/"off"/"abnormal"/""), both appended by
   * the server and not part of the persisted config.
   */
  public static class MachineData {

    public String id;
    public String name = "";
    public String description = "";
    /** Display category; machines with the same category form a group in the GUI. */
    public String category = "";
    /** Bot (fake player) names this machine manages; timeline steps reference these by index. */
    public List<String> bots = new ArrayList<>();
    public int permissionLevel = 2;
    public List<String> allowedPlayers = new ArrayList<>();
    public Timeline onTimeline = new Timeline();
    public Timeline offTimeline = new Timeline();
    public List<ModeData> modes = new ArrayList<>();
    public DetectionData detection;
    /** Switch lock window (ticks): after booting/shutting down, further switches are blocked. */
    public int switchInterval = 20;
    /** Multi-mode launch preset: mode ids in execution priority order. Empty = simultaneous. */
    public List<String> modeOrder = new ArrayList<>();
    /** Ticks between consecutive mode boots when several are launched at once. */
    public int modeInterval = 0;
    /** Multi-mode stop preset: mode ids in shutdown priority order. */
    public List<String> stopModeOrder = new ArrayList<>();
    /** Ticks between consecutive mode shutdowns when several are stopped at once. */
    public int stopModeInterval = 0;
    /** When true, stopping modes uses the start preset's order and interval. */
    public boolean stopFollowsStart = false;
    public boolean running = false;
    public String detected = "";
    /** Config revision (server-side optimistic-lock backstop), appended by the server. */
    public int revision = 0;
    /** Name of the player currently editing the machine, "" when unlocked (appended by server). */
    public String editingBy = "";
  }

  /**
   * A work mode of a machine: a named pair of processes, fully independent from the switch. The
   * shutdown process is optional — when empty, stopping the mode simply stops its scripts.
   * {@code running} is the runtime state appended by the server.
   */
  public static class ModeData {

    public String id;
    public String name = "";
    public Timeline onTimeline = new Timeline();
    public Timeline offTimeline = new Timeline();
    /**
     * When true, only one such mode may run at a time: selecting another single-select mode
     * replaces the current one (old runs its shutdown, then the combined interval).
     */
    public boolean singleSelect = false;
    /** Mode lock window (ticks); 0 = use the machine's switch interval. */
    public int switchInterval = 0;
    /** Optional block-state detection that locks the mode; null = not configured. */
    public DetectionData detection;
    /** Detection state appended by the server ("on"/"off"/"abnormal"/""). */
    public String detected = "";
    public boolean running = false;
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
   * A single timeline step: wait {@code delay} ticks after the previous step, then run
   * {@code commands} on the bot at index {@code bot}.
   */
  public static class Step {

    public int delay = 0;
    public int bot = 0;
    public List<String> commands = new ArrayList<>();
  }

  /**
   * Block-state detection config for the machine switch (mirrors the server model). When enabled,
   * the switch is locked while the detected block is in an unconfigured ("abnormal") state, and
   * the switch action follows the detected ON/OFF state.
   */
  public static class DetectionData {

    public boolean enabled = false;
    public String dimension = "minecraft:overworld";
    public int x = 0;
    public int y = 0;
    public int z = 0;
    public String blockId = "";
    public String property = "";
    public List<String> onValues = new ArrayList<>();
    public List<String> offValues = new ArrayList<>();

    public boolean isConfigured() {
      return blockId != null && !blockId.isEmpty() && property != null && !property.isEmpty();
    }
  }

  /**
   * Returns the first command of the timeline's first step, or {@code null} if there is no step
   * or the step has no commands.
   */
  public static String firstCommand(Timeline timeline) {
    if (timeline == null || timeline.steps == null || timeline.steps.isEmpty()) {
      return null;
    }
    List<String> commands = timeline.steps.get(0).commands;
    if (commands == null || commands.isEmpty()) {
      return null;
    }
    return commands.get(0);
  }

  /**
   * Whether a command is a fake player spawn command ({@code /player <name> spawn ...}).
   */
  public static boolean isSpawnCommand(String command) {
    if (command == null) {
      return false;
    }
    String cmd = command.trim().toLowerCase();
    if (cmd.startsWith("/")) {
      cmd = cmd.substring(1);
    }
    return cmd.startsWith("player ") && cmd.contains(" spawn");
  }

  /**
   * Validates that every step of a timeline has at least one command. Returns an error message
   * (with the given label) or {@code null} when valid.
   */
  public static String validateSteps(String label, Timeline timeline) {
    if (timeline == null || timeline.steps == null) {
      return null;
    }
    for (int i = 0; i < timeline.steps.size(); i++) {
      Step step = timeline.steps.get(i);
      if (step == null || step.commands == null || step.commands.isEmpty()) {
        return label + " 第" + (i + 1) + "步没有指令";
      }
    }
    return null;
  }
}

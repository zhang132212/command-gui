package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/**
 * Configuration screen for adding a fake player command.
 * <p>
 * Provides full configuration for spawning a fake player: name, spawn coordinates, dimension, game
 * mode, initial actions (multi-select) and their execution mode (once / continuous / timed
 * interval), ultimately generating a {@code /player <name> spawn ...} + action command sequence and
 * saving it to the custom config.
 * <p>
 * Also supports editing an existing fake player command entry (via the
 * {@code editingName}/{@code editingEntry} parameters).
 */
public class AddFakePlayerCommandScreen extends BaseParentedScreen<CommandGUIScreen> {

  private static final int MARGIN = 16;
  private static final int INNER_PAD = 10;
  private static final int FIELD_WIDTH = 150;
  private static final int FIELD_HEIGHT = 16;
  private static final int COL_GAP = 32;
  private static final int COORD_GAP = 8;
  private static final int ROT_GAP = 28;
  private static final int XYZ_FIELD_WIDTH = (FIELD_WIDTH - 2 * COORD_GAP) / 3; // 44
  private static final int YAW_PITCH_FIELD_WIDTH = (FIELD_WIDTH - ROT_GAP) / 2;  // 61
  private static final int ACTION_BTN_HEIGHT = 16;
  private static final int ACTION_BTN_GAP = 4;
  private static final int ACTIONS_PER_ROW = 3;
  private static final int ACTION_BTN_WIDTH =
      (FIELD_WIDTH - (ACTIONS_PER_ROW - 1) * ACTION_BTN_GAP) / ACTIONS_PER_ROW;
  private static final int MODE_BTN_WIDTH = 50;
  private static final int INTERVAL_FIELD_WIDTH = 40;
  private static final int LABEL_COLOR = 0xFFAAAAAA;
  private static final int BORDER_COLOR = 0xFF555555;
  private static final int LABEL_HEIGHT = 12;

  private static final String[] DIMENSIONS = {
      "minecraft:overworld",
      "minecraft:the_nether",
      "minecraft:the_end"
  };
  private static final String[] DIMENSION_NAMES = {
      "screen.command-gui.fakeplayer.dim.overworld",
      "screen.command-gui.fakeplayer.dim.nether",
      "screen.command-gui.fakeplayer.dim.end"
  };

  private static final String[] GAMEMODES = {
      "survival", "creative", "adventure", "spectator"
  };
  private static final String[] GAMEMODE_NAMES = {
      "screen.command-gui.vanilla.gamemode.survival",
      "screen.command-gui.vanilla.gamemode.creative",
      "screen.command-gui.vanilla.gamemode.adventure",
      "screen.command-gui.vanilla.gamemode.spectator"
  };

  private static final String[] ACTIONS = {
      "attack", "use", "jump", "sneak", "sprint", "drop", "dropStack", "swapHands",
      "mount", "dismount", "stop", "kill"
  };
  private static final String[] ACTION_NAMES = {
      "screen.command-gui.carpet.player.attack",
      "screen.command-gui.carpet.player.use",
      "screen.command-gui.carpet.player.jump",
      "screen.command-gui.carpet.player.sneak",
      "screen.command-gui.carpet.player.sprint",
      "screen.command-gui.carpet.player.drop",
      "screen.command-gui.carpet.player.dropstack",
      "screen.command-gui.carpet.player.swaphands",
      "screen.command-gui.carpet.player.mount",
      "screen.command-gui.carpet.player.dismount",
      "screen.command-gui.carpet.player.stop",
      "screen.command-gui.carpet.player.kill"
  };
  private static final String[] ACTION_MODES = {
      "once", "continuous", "interval"
  };
  private static final String[] ACTION_MODE_NAMES = {
      "screen.command-gui.carpet.player.mode.once",
      "screen.command-gui.carpet.player.mode.continuous",
      "screen.command-gui.carpet.player.mode.interval"
  };
  /**
   * Marks which actions support execution mode selection (once/continuous/interval); actions that
   * don't support it hide the mode button
   */
  private static final boolean[] ACTION_HAS_MODE = {
      true, true, true, false, false, false, false, false, false, false, false, false
  };

  private final String initialCategoryId;
  private final String editingName;
  private final CommandConfig.CommandEntry editingEntry;
  private final List<Button> dimensionButtons = new ArrayList<>();
  private final List<Button> gamemodeButtons = new ArrayList<>();
  private final List<Button> actionButtons = new ArrayList<>();
  private final List<Button> actionModeButtons = new ArrayList<>();
  private final List<EditBox> actionIntervalFields = new ArrayList<>();
  private final List<EditBox> configFields = new ArrayList<>();
  private final List<Button> configRemoveButtons = new ArrayList<>();
  /**
   * Set of selected action indices (index corresponds to the ACTIONS array)
   */
  private final Set<Integer> selectedActions = new HashSet<>();
  /**
   * Execution mode for each action: 0=once, 1=continuous, 2=interval
   */
  private final int[] actionModes = new int[ACTIONS.length];
  /**
   * Interval in ticks for each action (only applies when actionModes[i] == 2)
   */
  private final int[] actionIntervals = new int[ACTIONS.length];
  /**
   * Additional config command list (extra commands to execute after spawning)
   */
  private final List<String> configCommands = new ArrayList<>();
  // Input widgets
  private EditBox nameField;
  private EditBox descriptionField;
  private EditBox fakePlayerNameField;
  private Button fillCurrentPosButton;
  private EditBox xField, yField, zField;

// Screen state
  private EditBox yawField, pitchField;
  private Button addConfigButton;
  private Button saveButton;
  private Button cancelButton;
  /**
   * Currently selected dimension index (0=Overworld, 1=Nether, 2=End)
   */
  private int dimensionIndex = 0;
  /**
   * Currently selected game mode index
   */
  private int gamemodeIndex = 0;
  // Layout coordinates (computed in init())
  private int leftColX;
  private int rightColX;
  private int contentStartY;
  /** Row gap between successive left-column rows; dynamically computed in init(). */
  private int rowGap;

  public AddFakePlayerCommandScreen(CommandGUIScreen parent, String initialCategoryId) {
    this(parent, initialCategoryId, null, null);
  }

  public AddFakePlayerCommandScreen(CommandGUIScreen parent, String initialCategoryId,
      String editingName, CommandConfig.CommandEntry editingEntry) {
    super(Component.translatable("screen.command-gui.add_fakeplayer_title"), parent);
    this.initialCategoryId = initialCategoryId;
    this.editingName = editingName;
    this.editingEntry = editingEntry;
  }

  @Override
  protected void init() {
    super.init();

// Compute layout: two columns centered on screen
    int contentW = FIELD_WIDTH * 2 + COL_GAP;
    leftColX = this.width / 2 - contentW / 2;
    rightColX = leftColX + FIELD_WIDTH + COL_GAP;

    // Dynamic vertical layout: title at y=8, content starts just below it.
    // rowGap shrinks on small screens so all 8 left-column rows fit above the save buttons.
    contentStartY = 18;
    int saveBtnY = this.height - 24;
    // 8 rows with 7 inter-row gaps; last row occupies LABEL_HEIGHT + FIELD_HEIGHT.
    // Minimum rowGap = LABEL_HEIGHT + FIELD_HEIGHT so labels never overlap the field above.
    // Constraint: contentStartY + 7*rowGap + (LABEL_HEIGHT+FIELD_HEIGHT) ≤ saveBtnY - 2
    int maxAllowedRowGap = (saveBtnY - 2 - contentStartY - LABEL_HEIGHT - FIELD_HEIGHT) / 7;
    rowGap = Math.min(36, Math.max(LABEL_HEIGHT + FIELD_HEIGHT, maxAllowedRowGap));

    int leftY = contentStartY + LABEL_HEIGHT;

// === Left column: spawn settings ===

// Name (label above)
    nameField = new EditBox(this.font, leftColX, leftY, FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.name"));
    nameField.setMaxLength(50);
    nameField.setHint(Component.translatable("screen.command-gui.name_hint"));
    this.addRenderableWidget(nameField);

// Description (label above)
    leftY += rowGap;
    descriptionField = new EditBox(this.font, leftColX, leftY, FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.description"));
    descriptionField.setMaxLength(100);
    descriptionField.setHint(Component.translatable("screen.command-gui.description_hint"));
    this.addRenderableWidget(descriptionField);

// Fake Player Name (label above)
    leftY += rowGap;
    fakePlayerNameField = new EditBox(this.font, leftColX, leftY, FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.fakeplayer.playername"));
    fakePlayerNameField.setMaxLength(20);
    fakePlayerNameField.setValue("Bot_1");
    fakePlayerNameField.setHint(
        Component.translatable("screen.command-gui.fakeplayer.playername_hint"));
    this.addRenderableWidget(fakePlayerNameField);

// Fill current position button (label above)
    leftY += rowGap;
    fillCurrentPosButton = Button.builder(
        Component.translatable("screen.command-gui.fakeplayer.pos.fill_current"),
        btn -> fillCurrentPosition()
    ).bounds(leftColX, leftY, FIELD_WIDTH, FIELD_HEIGHT).build();
    this.addRenderableWidget(fillCurrentPosButton);

// XYZ row — three equal-width fields (X Y Z labels above each field)
    leftY += rowGap;
    xField = new EditBox(this.font, leftColX, leftY, XYZ_FIELD_WIDTH, FIELD_HEIGHT,
        Component.literal("X"));
    xField.setMaxLength(12);
    this.addRenderableWidget(xField);

    yField = new EditBox(this.font, leftColX + XYZ_FIELD_WIDTH + COORD_GAP, leftY, XYZ_FIELD_WIDTH,
        FIELD_HEIGHT, Component.literal("Y"));
    yField.setMaxLength(12);
    this.addRenderableWidget(yField);

    zField = new EditBox(this.font, leftColX + (XYZ_FIELD_WIDTH + COORD_GAP) * 2, leftY,
        XYZ_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Z"));
    zField.setMaxLength(12);
    this.addRenderableWidget(zField);

// Yaw / Pitch row (labels above each field)
    leftY += rowGap;
    yawField = new EditBox(this.font, leftColX, leftY, YAW_PITCH_FIELD_WIDTH, FIELD_HEIGHT,
        Component.literal("Yaw"));
    yawField.setMaxLength(10);
    this.addRenderableWidget(yawField);

    pitchField = new EditBox(this.font, leftColX + YAW_PITCH_FIELD_WIDTH + ROT_GAP, leftY,
        YAW_PITCH_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Pitch"));
    pitchField.setMaxLength(10);
    this.addRenderableWidget(pitchField);

// Dimension buttons (3 buttons in a row)
    leftY += rowGap;
    dimensionButtons.clear();
    int dimensionBtnWidth = (FIELD_WIDTH - 2 * ACTION_BTN_GAP) / 3;
    for (int i = 0; i < DIMENSIONS.length; i++) {
      final int idx = i;
      int btnX = leftColX + i * (dimensionBtnWidth + ACTION_BTN_GAP);
      DarkSelectButton dimBtn = new DarkSelectButton(btnX, leftY, dimensionBtnWidth, FIELD_HEIGHT,
          Component.translatable(DIMENSION_NAMES[i]), btn -> selectDimension(idx));
      dimBtn.setDarkSelected(() -> dimensionIndex == idx, 0xFFFFFFFF);
      dimensionButtons.add(dimBtn);
      this.addRenderableWidget(dimBtn);
    }

// Gamemode buttons (4 buttons in a row)
    leftY += rowGap;
    gamemodeButtons.clear();
    int gamemodeBtnWidth = (FIELD_WIDTH - 3 * ACTION_BTN_GAP) / 4;
    for (int i = 0; i < GAMEMODES.length; i++) {
      final int idx = i;
      int btnX = leftColX + i * (gamemodeBtnWidth + ACTION_BTN_GAP);
      DarkSelectButton gmBtn = new DarkSelectButton(btnX, leftY, gamemodeBtnWidth, FIELD_HEIGHT,
          Component.translatable(GAMEMODE_NAMES[i]), btn -> selectGamemode(idx));
      gmBtn.setDarkSelected(() -> gamemodeIndex == idx, 0xFFFFFFFF);
      gamemodeButtons.add(gmBtn);
      this.addRenderableWidget(gmBtn);
    }

// === Right column: actions then config ===

// Actions section - label above buttons
    int rightY = contentStartY + LABEL_HEIGHT;
    buildActionButtons(rightColX, rightY);

// Config section - label above fields
    rebuildConfigWidgets(getConfigStartY());

// Fill coord fields with current player position (defaults)
    fillCurrentPosition();

// If editing, restore values
    if (editingEntry != null && editingName != null) {
      nameField.setValue(editingName);
      descriptionField.setValue(editingEntry.description != null ? editingEntry.description : "");
      parseExistingCommands(editingEntry.getCommands());
    }

    // Save / Cancel buttons (bottom of screen, created here so they survive partial rebuilds)
    int contentCenterX = leftColX + (FIELD_WIDTH * 2 + COL_GAP) / 2;
    saveButton = Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(contentCenterX - 102, this.height - 24, 100, 20).build();
    this.addRenderableWidget(saveButton);

    cancelButton = Button.builder(
        Component.translatable("screen.command-gui.cancel"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(contentCenterX + 2, this.height - 24, 100, 20).build();
    this.addRenderableWidget(cancelButton);

    updateActionButtonColors();
  }

  private void buildActionButtons(int startX, int startY) {
// Clear existing widgets
    for (Button btn : actionButtons) {
      this.removeWidget(btn);
    }
    for (Button btn : actionModeButtons) {
      this.removeWidget(btn);
    }
    for (EditBox field : actionIntervalFields) {
      this.removeWidget(field);
    }
    actionButtons.clear();
    actionModeButtons.clear();
    actionIntervalFields.clear();

    int currentY = startY;

// First row: attack with mode selector
    buildActionWithMode(startX, currentY, 0); // attack
    currentY += ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 4;

// Second row: use with mode selector
    buildActionWithMode(startX, currentY, 1); // use
    currentY += ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 4;

// Third row: jump with mode selector
    buildActionWithMode(startX, currentY, 2); // jump
    currentY += ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 8;

// Remaining actions in grid (3 per row)
    int gridStartIdx = 3;
    for (int i = gridStartIdx; i < ACTIONS.length; i++) {
      int gridIdx = i - gridStartIdx;
      int row = gridIdx / ACTIONS_PER_ROW;
      int col = gridIdx % ACTIONS_PER_ROW;
      int btnX = startX + col * (ACTION_BTN_WIDTH + ACTION_BTN_GAP);
      int btnY = currentY + row * (ACTION_BTN_HEIGHT + ACTION_BTN_GAP);
      final int actionIdx = i;
      DarkSelectButton actionBtn = new DarkSelectButton(btnX, btnY, ACTION_BTN_WIDTH,
          ACTION_BTN_HEIGHT,
          Component.translatable(ACTION_NAMES[i]), btn -> toggleAction(actionIdx));
      actionBtn.setDarkSelected(() -> selectedActions.contains(actionIdx), 0xFFFFFFFF);
      actionButtons.add(actionBtn);
      this.addRenderableWidget(actionBtn);
    }
  }

  private void buildActionWithMode(int startX, int y, int actionIdx) {
// Action toggle button
    int actionBtnWidth = 50;
    DarkSelectButton actionBtn = new DarkSelectButton(startX, y, actionBtnWidth, ACTION_BTN_HEIGHT,
        Component.translatable(ACTION_NAMES[actionIdx]), btn -> toggleAction(actionIdx));
    actionBtn.setDarkSelected(() -> selectedActions.contains(actionIdx), 0xFFFFFFFF);
    actionButtons.add(actionBtn);
    this.addRenderableWidget(actionBtn);

// Mode cycle button
    int modeX = startX + actionBtnWidth + 4;
    final int idx = actionIdx;
    Button modeBtn = Button.builder(
        Component.translatable(ACTION_MODE_NAMES[actionModes[actionIdx]]),
        btn -> {
          actionModes[idx] = (actionModes[idx] + 1) % ACTION_MODES.length;
          btn.setMessage(Component.translatable(ACTION_MODE_NAMES[actionModes[idx]]));
        }
    ).bounds(modeX, y, MODE_BTN_WIDTH, ACTION_BTN_HEIGHT).build();
    actionModeButtons.add(modeBtn);
    this.addRenderableWidget(modeBtn);

// Interval input field
    int intervalX = modeX + MODE_BTN_WIDTH + 4;
    EditBox intervalField = new EditBox(this.font, intervalX, y, INTERVAL_FIELD_WIDTH,
        ACTION_BTN_HEIGHT, Component.literal("ticks"));
    intervalField.setMaxLength(5);
    intervalField.setHint(Component.literal("ticks"));
    intervalField.setValue(
        actionIntervals[actionIdx] > 0 ? String.valueOf(actionIntervals[actionIdx]) : "");
    final int fieldIdx = actionIdx;
    intervalField.setResponder(s -> {
      try {
        actionIntervals[fieldIdx] = s.isEmpty() ? 0 : Integer.parseInt(s);
      } catch (NumberFormatException e) {
        actionIntervals[fieldIdx] = 0;
      }
    });
    actionIntervalFields.add(intervalField);
    this.addRenderableWidget(intervalField);
  }

  private int getConfigStartY() {
// Config starts in the right column, after: content start + label + action buttons + gap
// 3 actions with mode (attack, use, jump): each row = ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 4, last one + 8
    int modeActionHeight =
        2 * (ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 4) + (ACTION_BTN_HEIGHT + ACTION_BTN_GAP + 8);
// Remaining 9 actions in 3x3 grid
    int gridActions = ACTIONS.length - 3;
    int gridRows = (gridActions + ACTIONS_PER_ROW - 1) / ACTIONS_PER_ROW;
    int gridHeight = gridRows * (ACTION_BTN_HEIGHT + ACTION_BTN_GAP);
    return contentStartY + LABEL_HEIGHT + modeActionHeight + gridHeight + 16;
  }

  private void rebuildConfigWidgets(int startY) {
    for (EditBox field : configFields) {
      this.removeWidget(field);
    }
    for (Button btn : configRemoveButtons) {
      this.removeWidget(btn);
    }
    if (addConfigButton != null) {
      this.removeWidget(addConfigButton);
    }
    configFields.clear();
    configRemoveButtons.clear();

    int x = rightColX;
    int currentY = startY;
    // Stop adding widgets before they overlap the save/cancel buttons
    int maxY = this.height - 24 - 4;

    for (int i = 0; i < configCommands.size(); i++) {
      if (currentY + FIELD_HEIGHT > maxY) break;
      final int idx = i;
      EditBox configField = new EditBox(this.font, x, currentY, FIELD_WIDTH - 18, FIELD_HEIGHT,
          Component.translatable("screen.command-gui.fakeplayer.config"));
      configField.setMaxLength(256);
      configField.setValue(configCommands.get(i));
      configField.setHint(Component.translatable("screen.command-gui.fakeplayer.config_hint"));
      configField.setResponder(text -> {
        if (idx < configCommands.size()) {
          configCommands.set(idx, text);
        }
      });
      configFields.add(configField);
      this.addRenderableWidget(configField);

      Button removeBtn = Button.builder(
          Component.translatable("screen.command-gui.remove_command"),
          btn -> {
            configCommands.remove(idx);
            rebuildConfigWidgets(getConfigStartY());
          }
      ).bounds(x + FIELD_WIDTH - 16, currentY, 16, FIELD_HEIGHT).build();
      configRemoveButtons.add(removeBtn);
      this.addRenderableWidget(removeBtn);

      currentY += rowGap;
    }

    if (currentY + FIELD_HEIGHT <= maxY) {
      addConfigButton = Button.builder(
          Component.translatable("screen.command-gui.fakeplayer.add_config"),
          btn -> {
            configCommands.add("");
            rebuildConfigWidgets(getConfigStartY());
          }
      ).bounds(x, currentY, 80, FIELD_HEIGHT).build();
      this.addRenderableWidget(addConfigButton);
    } else {
      addConfigButton = null;
    }
  }

  private void toggleAction(int actionIndex) {
    if (selectedActions.contains(actionIndex)) {
      selectedActions.remove(actionIndex);
    } else {
      selectedActions.add(actionIndex);
    }
    updateActionButtonColors();
  }

  private void selectDimension(int index) {
    dimensionIndex = index;
  }

  private void selectGamemode(int index) {
    gamemodeIndex = index;
  }

  private void updateActionButtonColors() {
// Keep all action buttons active so selections can be toggled on/off.
// Selected state is shown via a colored overlay drawn in render().
    for (int i = 0; i < actionButtons.size(); i++) {
      actionButtons.get(i).active = true;
    }
  }

  private void fillCurrentPosition() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null) {
      double posX = Math.round(mc.player.getX() * 10.0) / 10.0;
      double posY = Math.round(mc.player.getY() * 10.0) / 10.0;
      double posZ = Math.round(mc.player.getZ() * 10.0) / 10.0;
      float yaw = Math.round(mc.player.getYRot() * 10.0f) / 10.0f;
      float pitch = Math.round(mc.player.getXRot() * 10.0f) / 10.0f;

      xField.setValue(String.format("%.1f", posX));
      yField.setValue(String.format("%.1f", posY));
      zField.setValue(String.format("%.1f", posZ));
      yawField.setValue(String.format("%.1f", yaw));
      pitchField.setValue(String.format("%.1f", pitch));

      net.minecraft.resources.ResourceKey<Level> dim = mc.player.level().dimension();
      if (dim.equals(Level.NETHER)) {
        dimensionIndex = 1;
      } else if (dim.equals(Level.END)) {
        dimensionIndex = 2;
      } else {
        dimensionIndex = 0;
      }

      if (mc.gameMode != null) {
        String mode = mc.gameMode.getPlayerMode().getName();
        for (int i = 0; i < GAMEMODES.length; i++) {
          if (GAMEMODES[i].equals(mode)) {
            gamemodeIndex = i;
            break;
          }
        }
      }
    }
  }

  private void parseExistingCommands(List<String> commands) {
    if (commands == null || commands.isEmpty()) {
      return;
    }

    String spawnCmd = commands.get(0);
    if (spawnCmd.startsWith("/player ") || spawnCmd.startsWith("player ")) {
      String cmd = spawnCmd.startsWith("/") ? spawnCmd.substring(1) : spawnCmd;
      String[] parts = cmd.split("\\s+");
      if (parts.length >= 2) {
        fakePlayerNameField.setValue(parts[1]);
      }

// Parse AT coordinates: "at X Y Z"
      Matcher atMatcher = Pattern.compile(
          " at (-?\\d+\\.?\\d*) (-?\\d+\\.?\\d*) (-?\\d+\\.?\\d*)").matcher(cmd);
      if (atMatcher.find()) {
        xField.setValue(atMatcher.group(1));
        yField.setValue(atMatcher.group(2));
        zField.setValue(atMatcher.group(3));
      }

// Parse FACING: "facing Yaw Pitch"
      Matcher facingMatcher = Pattern.compile(
          " facing (-?\\d+\\.?\\d*) (-?\\d+\\.?\\d*)").matcher(cmd);
      if (facingMatcher.find()) {
        yawField.setValue(facingMatcher.group(1));
        pitchField.setValue(facingMatcher.group(2));
      }

// Parse dimension and gamemode: "in <dim> in <mode>"
      int lastInIdx = cmd.lastIndexOf(" in ");
      if (lastInIdx >= 0) {
        String possibleGamemode = cmd.substring(lastInIdx + 4).trim();
        for (int i = 0; i < GAMEMODES.length; i++) {
          if (GAMEMODES[i].equals(possibleGamemode)) {
            gamemodeIndex = i;
            break;
          }
        }
        int firstInIdx = cmd.indexOf(" in ");
        if (firstInIdx >= 0 && firstInIdx < lastInIdx) {
          String possibleDimension = cmd.substring(firstInIdx + 4, lastInIdx).trim();
          for (int i = 0; i < DIMENSIONS.length; i++) {
            if (DIMENSIONS[i].equals(possibleDimension)) {
              dimensionIndex = i;
              break;
            }
          }
        }
      }
    }

    selectedActions.clear();
    configCommands.clear();
    for (int i = 0; i < actionModes.length; i++) {
      actionModes[i] = 0;
      actionIntervals[i] = 0;
    }
    for (int cmdIdx = 1; cmdIdx < commands.size(); cmdIdx++) {
      String actionCmd = commands.get(cmdIdx);
      String cmd = actionCmd.startsWith("/") ? actionCmd.substring(1) : actionCmd;
      boolean matched = false;
      String[] parts = cmd.split("\\s+");
      if (parts.length >= 3 && parts[0].equals("player")) {
        String actionName = parts[2];
        for (int i = 0; i < ACTIONS.length; i++) {
          if (actionName.equals(ACTIONS[i])) {
            selectedActions.add(i);
            matched = true;
// Parse mode for actions that support it
            if (ACTION_HAS_MODE[i] && parts.length >= 4) {
              String modePart = parts[3];
              if (modePart.equals("continuous")) {
                actionModes[i] = 1;
              } else if (modePart.equals("interval") && parts.length >= 5) {
                actionModes[i] = 2;
                try {
                  actionIntervals[i] = Integer.parseInt(parts[4]);
                } catch (NumberFormatException e) {
                  actionIntervals[i] = 0;
                }
              } else {
                actionModes[i] = 0; // once
              }
            }
            break;
          }
        }
      }
      if (!matched) {
        configCommands.add(actionCmd);
      }
    }

    updateActionButtonColors();
    rebuildConfigWidgets(getConfigStartY());
  }

  private List<String> buildCommands() {
    List<String> commands = new ArrayList<>();
    String fpName = fakePlayerNameField.getValue().trim();
    if (fpName.isEmpty()) {
      return commands;
    }

    StringBuilder spawnCmd = new StringBuilder("/player ").append(fpName).append(" spawn");

    String x = xField.getValue().trim();
    String y = yField.getValue().trim();
    String z = zField.getValue().trim();
    String yawStr = yawField.getValue().trim();
    String pitchStr = pitchField.getValue().trim();

    // Carpet 26.2 spawn REQUIRES "at X Y Z" before "facing" — fall back to the player's current
    // position when any coordinate was left blank, so the generated command always parses.
    if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
        x = String.format("%.1f", Math.round(mc.player.getX() * 10.0) / 10.0);
        y = String.format("%.1f", Math.round(mc.player.getY() * 10.0) / 10.0);
        z = String.format("%.1f", Math.round(mc.player.getZ() * 10.0) / 10.0);
      } else {
        x = y = z = "0";
      }
    }
    spawnCmd.append(" at ").append(x).append(" ").append(y).append(" ").append(z);
    // Carpet 26.2 requires the facing argument whenever "in <dim>" is used: always emit it,
    // defaulting to 0 0 when the player left it blank.
    spawnCmd.append(" facing ")
        .append(yawStr.isEmpty() ? "0" : yawStr)
        .append(" ")
        .append(pitchStr.isEmpty() ? "0" : pitchStr);
    spawnCmd.append(" in ").append(DIMENSIONS[dimensionIndex]);

    commands.add(spawnCmd.toString());

    // Game mode is set separately with the VANILLA command (carpet's "in gamemode" is broken and
    // "player <name> gamemode" does not exist in 26.2).
    commands.add("/gamemode " + GAMEMODES[gamemodeIndex] + " " + fpName);

// Action commands
    List<Integer> sortedActions = new ArrayList<>(selectedActions);
    sortedActions.sort(Integer::compareTo);
    for (int actionIdx : sortedActions) {
      StringBuilder actionCmd = new StringBuilder("/player ").append(fpName).append(" ")
          .append(ACTIONS[actionIdx]);
      if (ACTION_HAS_MODE[actionIdx]) {
        int mode = actionModes[actionIdx];
        if (mode == 1) {
          actionCmd.append(" continuous");
        } else if (mode == 2 && actionIntervals[actionIdx] > 0) {
          actionCmd.append(" interval ").append(actionIntervals[actionIdx]);
        } else {
          actionCmd.append(" once");
        }
      }
      commands.add(actionCmd.toString());
    }

// Config commands
    for (int i = 0; i < configCommands.size(); i++) {
      String config;
      if (i < configFields.size()) {
        config = configFields.get(i).getValue().trim();
      } else {
        config = configCommands.get(i).trim();
      }
      if (!config.isEmpty()) {
        commands.add(config);
      }
    }

    return commands;
  }

  private void saveAndClose() {
    String name = nameField.getValue().trim();
    String description = descriptionField.getValue().trim();
    List<String> commands = buildCommands();

    if (name.isEmpty()) {
      name = com.remrin.client.config.CommandConfig.nextDefaultCommandName();
      nameField.setValue(name);
    }
    if (name.isEmpty() || commands.isEmpty()) {
      return;
    }

    String categoryId = initialCategoryId != null ? initialCategoryId : "default";

    if (editingName != null) {
      if (!name.equals(editingName)) {
        String oldCategoryId = CommandConfig.findCommandCategory(editingName);
        CommandConfig.removeCommand(editingName);
        CommandConfig.addCommandMulti(oldCategoryId != null ? oldCategoryId : categoryId, name,
            commands, description);
      } else {
        CommandConfig.updateCommandMulti(name, commands, description);
      }
    } else {
      CommandConfig.addCommandMulti(categoryId, name, commands, description);
    }

    parent.refresh();
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

// Title
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

// === Left column labels (above each field) ===
    int currentY = contentStartY;

// Name label
    guiGraphics.text(this.font, Component.translatable("screen.command-gui.name"),
        leftColX, currentY, LABEL_COLOR);

// Description label
    currentY += rowGap;
    guiGraphics.text(this.font, Component.translatable("screen.command-gui.description"),
        leftColX, currentY, LABEL_COLOR);

// Fake Player Name label
    currentY += rowGap;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.playername"),
        leftColX, currentY, LABEL_COLOR);

// Spawn At label
    currentY += rowGap;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.spawn_at"),
        leftColX, currentY, LABEL_COLOR);

// XYZ labels (above each field)
    currentY += rowGap;
    guiGraphics.text(this.font, "X", leftColX, currentY, 0xFFFF5555);
    guiGraphics.text(this.font, "Y", leftColX + XYZ_FIELD_WIDTH + COORD_GAP, currentY,
        0xFF55FF55);
    guiGraphics.text(this.font, "Z", leftColX + (XYZ_FIELD_WIDTH + COORD_GAP) * 2, currentY,
        0xFF5555FF);

// Yaw/Pitch labels (above each field)
    currentY += rowGap;
    guiGraphics.text(this.font, "Yaw", leftColX, currentY, LABEL_COLOR);
    guiGraphics.text(this.font, "Pitch", leftColX + YAW_PITCH_FIELD_WIDTH + ROT_GAP, currentY,
        LABEL_COLOR);

// Dimension label (above buttons)
    currentY += rowGap;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.dimension"),
        leftColX, currentY, LABEL_COLOR);

// Gamemode label (above buttons)
    currentY += rowGap;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.gamemode"),
        leftColX, currentY, LABEL_COLOR);

// === Right column labels (above the widgets) ===
// "Actions" label above action buttons
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.actions_label"),
        rightColX, contentStartY, LABEL_COLOR);

// "Config" label above config fields
    int configStartY = getConfigStartY();
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.config_label"),
        rightColX, configStartY - LABEL_HEIGHT, LABEL_COLOR);

// === Command preview (right column, below config section) ===
    List<String> commands = buildCommands();
    // Use configFields.size() (actually-rendered rows) not configCommands.size(),
    // since overflow protection may have truncated how many rows were added.
    int configEndY = configStartY + configFields.size() * rowGap + 20;

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.config_desc"),
        rightColX, configEndY + 2, 0xFF888888);

    // Place preview in the right column area below the config section.
    // The right column ends much sooner than the left column, leaving room here
    // even on small logical screens (e.g. 480×270 at Default/Auto scale on 1080p).
    int previewAreaTop = configEndY + 12;
    int saveBtnTopY = this.height - 24;
    if (previewAreaTop + 20 < saveBtnTopY - 4) {
      int previewBoxX = rightColX;
      int previewBoxW = FIELD_WIDTH;
      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.fakeplayer.command_preview"),
          previewBoxX, previewAreaTop, 0xFF888888);

// Calculate total lines needed for preview (with word wrap)
      int previewMaxW = previewBoxW - 8;
      List<String> wrappedLines = new ArrayList<>();
      for (String cmd : commands) {
        if (this.font.width(cmd) <= previewMaxW) {
          wrappedLines.add(cmd);
        } else {
          String remaining = cmd;
          while (!remaining.isEmpty()) {
            String line = this.font.plainSubstrByWidth(remaining, previewMaxW);
            if (line.isEmpty()) {
              line = remaining.substring(0, 1);
            }
            wrappedLines.add(line);
            remaining = remaining.substring(line.length());
          }
        }
      }

// Preview content area with border (height clamped to available space)
      int previewBoxY = previewAreaTop + 10;
      int maxPreviewH = saveBtnTopY - 4 - previewBoxY;
      int previewBoxHeight = Math.min(Math.max(wrappedLines.size() * 9 + 6, 20), maxPreviewH);

// Draw border
      guiGraphics.fill(previewBoxX, previewBoxY, previewBoxX + previewBoxW, previewBoxY + 1,
          BORDER_COLOR);
      guiGraphics.fill(previewBoxX, previewBoxY + previewBoxHeight - 1, previewBoxX + previewBoxW,
          previewBoxY + previewBoxHeight, BORDER_COLOR);
      guiGraphics.fill(previewBoxX, previewBoxY, previewBoxX + 1, previewBoxY + previewBoxHeight,
          BORDER_COLOR);
      guiGraphics.fill(previewBoxX + previewBoxW - 1, previewBoxY, previewBoxX + previewBoxW,
          previewBoxY + previewBoxHeight, BORDER_COLOR);

// Draw preview commands (left aligned, with wrap, clipped to box)
      int previewY = previewBoxY + 3;
      for (int i = 0; i < wrappedLines.size(); i++) {
        int lineY = previewY + i * 9;
        if (lineY + 9 > previewBoxY + previewBoxHeight - 2) break;
        guiGraphics.text(this.font, wrappedLines.get(i), previewBoxX + 4, lineY,
            0xFF55FF55);
      }
    }
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();
    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      String fpName = fakePlayerNameField.getValue().trim();
      if (!fpName.isEmpty()) {
        saveAndClose();
      }
      return true;
    }
    return super.keyPressed(keyEvent);
  }
}

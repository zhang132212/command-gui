package com.remrin.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Editor screen for creating or editing a machine switch. All fields except the timelines are
 * entered here; each timeline is edited in a separate {@link TimelineEditorScreen}.
 * <p>
 * The screen works on a working copy of the machine data, so cancelling discards all changes.
 * Saving validates the input client-side and sends an add / edit action to the server.
 */
public class MachineEditorScreen extends BaseParentedScreen<CommandGUIScreen> {

  private static final Gson GSON = new GsonBuilder().create();
  private static final int FIELD_WIDTH = 300;
  private static final int FIELD_HEIGHT = 20;
  private static final int FIELD_GAP = 8;
  private static final int HALF_FIELD_WIDTH = (FIELD_WIDTH - FIELD_GAP) / 2;

  /** Working copy of the machine being edited (never the synced cache object). */
  private final MachineData machine;
  /** Whether this editor creates a new machine (affects optional field defaults). */
  private final boolean isNewMachine;
  /** Whether the local player may edit machines (canEdit: OP or whitelisted). */
  private final boolean isEditor;
  /** Whether the local player sees the full config rows (permission / players): OP only. */
  private final boolean isConfigEditor;
  /** Revision captured when the editor was opened; sent with edits for conflict detection. */
  private final int baseRevision;
  /** Vertical gap between fields; larger for non-editors (fewer rows to spread out). */
  private int rowGap = 30;
  /** Y of the process/detection button row. */
  private int buttonsRowY;
  /** Validation error shown above the save bar; empty means no error. */
  private String errorMessage = "";
  /**
   * Live text backups so typed input survives screen re-init (Gui.setScreen re-inits the target
   * screen every time, wiping EditBox contents that are not backed up).
   */
  private String nameText = "";
  private String descriptionText = "";
  private String categoryText = "";
  private String permissionText = "";
  private String intervalText = "";
  private EditBox nameField;
  private EditBox descriptionField;
  private EditBox categoryField;
  private EditBox botsField;
  private EditBox intervalField;
  private EditBox permissionField;
  private EditBox playersField;
  private Button onTimelineButton;
  private Button offTimelineButton;
  private Button modesButton;
  private Button detectionButton;

  public MachineEditorScreen(CommandGUIScreen parent, MachineData existing) {
    super(Component.translatable(existing == null
        ? "screen.command-gui.machine.add_title"
        : "screen.command-gui.machine.edit_title"), parent);
    this.isNewMachine = existing == null;
    this.isEditor = MachineNetworkManager.canEdit();
    this.isConfigEditor = MachineNetworkManager.canConfig();
    this.machine = existing != null ? GSON.fromJson(GSON.toJson(existing), MachineData.class)
        : new MachineData();
    this.baseRevision = existing != null ? existing.revision : -1;
    this.nameText = this.machine.name != null ? this.machine.name : "";
    this.descriptionText = this.machine.description != null ? this.machine.description : "";
    this.categoryText = this.machine.category != null ? this.machine.category : "";
    this.intervalText = String.valueOf(this.machine.switchInterval);
    // Permission is optional: blank means every player can toggle (level 0)
    this.permissionText = this.isNewMachine ? "" : String.valueOf(this.machine.permissionLevel);
  }

  /** The working machine copy (used by the mode list / multi-mode config screens). */
  public MachineData getMachine() {
    return machine;
  }

  @Override
  protected void init() {
    super.init();

    // Acquire the hard edit lock for the whole editing session (including sub-screens). The lock
    // is released on save or when this screen is closed.
    if (!isNewMachine && machine.id != null && !machine.id.isEmpty()) {
      MachineNetworkManager.sendEditSession(machine.id, true);
    }

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    // Config editors see 4 rows (permission / players included); others 3. Editors get a wider
    // gap, whitelisted non-OP editors have fewer rows so they spread wider still.
    this.rowGap = isConfigEditor ? 36 : 44;
    int fieldsEnd = isConfigEditor ? 4 : 3; // rows before the button row
    this.buttonsRowY = 32 + rowGap * fieldsEnd;

    nameField = new EditBox(this.font, fieldX, 32, HALF_FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.name"));
    nameField.setMaxLength(50);
    nameField.setValue(nameText);
    nameField.setResponder(text -> nameText = text);
    this.addRenderableWidget(nameField);

    categoryField = new EditBox(this.font, fieldX + HALF_FIELD_WIDTH + FIELD_GAP, 32,
        HALF_FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.category"));
    categoryField.setMaxLength(30);
    categoryField.setValue(categoryText);
    categoryField.setHint(Component.translatable("screen.command-gui.machine.category_hint"));
    categoryField.setResponder(text -> categoryText = text);
    this.addRenderableWidget(categoryField);

    descriptionField = new EditBox(this.font, fieldX, 32 + rowGap, FIELD_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.description"));
    descriptionField.setMaxLength(200);
    descriptionField.setValue(descriptionText);
    descriptionField.setResponder(text -> descriptionText = text);
    this.addRenderableWidget(descriptionField);

    botsField = new EditBox(this.font, fieldX, 32 + rowGap * 2, FIELD_WIDTH - 76, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.bots"));
    botsField.setMaxLength(200);
    botsField.setHint(Component.translatable("screen.command-gui.machine.bots_hint"));
    botsField.setValue(String.join(",", machine.bots));
    botsField.setResponder(text -> machine.bots = parseList(text));
    this.addRenderableWidget(botsField);

    // Switch lock window (ticks): after booting/shutting down, further switches are blocked for
    // this many ticks. Same row as the bots field.
    intervalField = new EditBox(this.font, fieldX + FIELD_WIDTH - 72, 32 + rowGap * 2, 72,
        FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.switch_interval"));
    intervalField.setMaxLength(4);
    intervalField.setValue(intervalText);
    intervalField.setHint(Component.translatable("screen.command-gui.machine.switch_interval_hint"));
    intervalField.setResponder(text -> intervalText = text);
    this.addRenderableWidget(intervalField);

    // Permission level / allowed players: only visible to OP config editors, on one shared row
    if (isConfigEditor) {
      int permRowY = 32 + rowGap * 3;
      permissionField = new EditBox(this.font, fieldX, permRowY, 60, FIELD_HEIGHT,
          Component.translatable("screen.command-gui.machine.permission"));
      permissionField.setMaxLength(1);
      permissionField.setValue(permissionText);
      permissionField.setHint(
          Component.translatable("screen.command-gui.machine.permission_hint"));
      permissionField.setResponder(text -> permissionText = text);
      this.addRenderableWidget(permissionField);

      playersField = new EditBox(this.font, fieldX + 68, permRowY, FIELD_WIDTH - 68, FIELD_HEIGHT,
          Component.translatable("screen.command-gui.machine.players"));
      playersField.setMaxLength(200);
      playersField.setHint(Component.translatable("screen.command-gui.machine.players_hint"));
      playersField.setValue(String.join(",", machine.allowedPlayers));
      playersField.setResponder(text -> machine.allowedPlayers = parseList(text));
      this.addRenderableWidget(playersField);
    }

    int timelineWidth = (FIELD_WIDTH - 24) / 4;
    onTimelineButton = Button.builder(
        buildTimelineLabel(true),
        btn -> openTimelineEditor(true)
    ).bounds(fieldX, buttonsRowY, timelineWidth, 18).build();
    this.addRenderableWidget(onTimelineButton);

    offTimelineButton = Button.builder(
        buildTimelineLabel(false),
        btn -> openTimelineEditor(false)
    ).bounds(fieldX + timelineWidth + 8, buttonsRowY, timelineWidth, 18).build();
    this.addRenderableWidget(offTimelineButton);

    modesButton = Button.builder(
        buildModesLabel(),
        btn -> openModesEditor()
    ).bounds(fieldX + (timelineWidth + 8) * 2, buttonsRowY, timelineWidth, 18).build();
    this.addRenderableWidget(modesButton);

    detectionButton = Button.builder(
        buildDetectionLabel(),
        btn -> openDetectionEditor()
    ).bounds(fieldX + (timelineWidth + 8) * 3, buttonsRowY, timelineWidth, 18).build();
    this.addRenderableWidget(detectionButton);

    int barY = this.height - 22;
    int barWidth = Math.min(80, FIELD_WIDTH / 3);
    int barStartX = fieldX + (FIELD_WIDTH - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());
    // Back always leaves: exit without saving when the config is incomplete
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> backAndClose()
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  private void openTimelineEditor(boolean onTimeline) {
    com.remrin.client.machine.MachineDebug.log("[MachineEditor.openTimeline] on=" + onTimeline
        + " machineHash=" + System.identityHashCode(machine)
        + " onTimelineHash=" + System.identityHashCode(machine.onTimeline)
        + " steps=" + machine.onTimeline.steps.size()
        + " firstCommands=" + (machine.onTimeline.steps.isEmpty() ? "[]"
            : com.remrin.client.machine.MachineDebug.commandsString(
                machine.onTimeline.steps.get(0).commands)));
    this.minecraft.gui.setScreen(new TimelineEditorScreen(
        this,
        Component.translatable(onTimeline
            ? "screen.command-gui.machine.boot_process_title"
            : "screen.command-gui.machine.shutdown_process_title"),
        onTimeline ? machine.onTimeline : machine.offTimeline,
        onTimeline,
        true,
        () -> updateTimelineButtons(),
        machine.bots));
  }

  private void openModesEditor() {
    this.minecraft.gui.setScreen(new ModesEditorScreen(
        this,
        machine.modes,
        machine.bots,
        () -> updateTimelineButtons()));
  }

  private void openDetectionEditor() {
    this.minecraft.gui.setScreen(new DetectionScreen(
        this,
        machine.detection,
        detection -> {
          machine.detection = detection;
          detectionButton.setMessage(buildDetectionLabel());
        }));
  }

  private void updateTimelineButtons() {
    if (onTimelineButton != null) {
      onTimelineButton.setMessage(buildTimelineLabel(true));
    }
    if (offTimelineButton != null) {
      offTimelineButton.setMessage(buildTimelineLabel(false));
    }
    if (modesButton != null) {
      modesButton.setMessage(buildModesLabel());
    }
    if (detectionButton != null) {
      detectionButton.setMessage(buildDetectionLabel());
    }
  }

  private Component buildTimelineLabel(boolean onTimeline) {
    int steps = (onTimeline ? machine.onTimeline : machine.offTimeline).steps.size();
    return Component.translatable(onTimeline
        ? "screen.command-gui.machine.boot_process_short"
        : "screen.command-gui.machine.shutdown_process_short", steps);
  }

  private Component buildModesLabel() {
    return Component.translatable("screen.command-gui.machine.modes_short", machine.modes.size());
  }

  private Component buildDetectionLabel() {
    if (machine.detection == null || !machine.detection.isConfigured()) {
      return Component.translatable("screen.command-gui.machine.detection_none_short");
    }
    if (machine.detection.enabled) {
      return Component.translatable("screen.command-gui.machine.detection_on_short");
    }
    return Component.translatable("screen.command-gui.machine.detection_off_short");
  }

  /**
   * Validates the machine; returns an error message or an empty string when valid.
   */
  private String validateMachine() {
    String name = nameField.getValue().trim();
    if (name.isEmpty()) {
      return Component.translatable("screen.command-gui.machine.error_name").getString();
    }
    if (machine.bots.isEmpty()) {
      return Component.translatable("screen.command-gui.machine.error_bots").getString();
    }
    if (!MachineModels.isSpawnCommand(MachineModels.firstCommand(machine.onTimeline))) {
      return Component.translatable("screen.command-gui.machine.spawn_required").getString();
    }
    String stepsError = MachineModels.validateSteps("开机流程", machine.onTimeline);
    if (stepsError != null) {
      return stepsError;
    }
    stepsError = MachineModels.validateSteps("关机流程", machine.offTimeline);
    if (stepsError != null) {
      return stepsError;
    }
    for (MachineModels.ModeData mode : machine.modes) {
      if (mode.name == null || mode.name.isBlank()) {
        return Component.translatable("screen.command-gui.machine.error_mode_name").getString();
      }
      if (!MachineModels.isSpawnCommand(MachineModels.firstCommand(mode.onTimeline))) {
        return Component.translatable("screen.command-gui.machine.error_mode_spawn",
            mode.name).getString();
      }
      stepsError = MachineModels.validateSteps("模式「" + mode.name + "」开启流程",
          mode.onTimeline);
      if (stepsError != null) {
        return stepsError;
      }
      stepsError = MachineModels.validateSteps("模式「" + mode.name + "」关机流程",
          mode.offTimeline);
      if (stepsError != null) {
        return stepsError;
      }
    }
    return "";
  }

  private void saveAndClose() {
    errorMessage = "";
    String error = validateMachine();
    if (!error.isEmpty()) {
      errorMessage = error;
      return;
    }
    // Read the live widgets directly (the backups only serve re-init restoration)
    String name = nameField.getValue().trim();
    machine.name = name;
    machine.description = descriptionField.getValue().trim();
    machine.category = categoryField.getValue().trim();
    try {
      machine.switchInterval = Math.max(0, Math.min(1200,
          Integer.parseInt(intervalField.getValue().trim())));
    } catch (NumberFormatException ignored) {
      machine.switchInterval = 20;
    }
    // Permission is optional (OP editors only): blank or invalid input means every player can
    // toggle (level 0). Non-editors keep the machine's existing value.
    if (permissionField != null) {
      try {
        machine.permissionLevel = Integer.parseInt(permissionField.getValue().trim());
      } catch (NumberFormatException ignored) {
        machine.permissionLevel = 0;
      }
      machine.permissionLevel = Math.max(0, Math.min(machine.permissionLevel, 4));
    }

    boolean isNew = machine.id == null || machine.id.isEmpty();
    if (isNew) {
      machine.id = generateId(name);
    }
    if (isNew) {
      MachineNetworkManager.sendAdd(machine);
    } else {
      // Save first (the server releases the lock on success), then release the lock explicitly so
      // a rejected save (e.g. stale revision) does not leave it held.
      MachineNetworkManager.sendEdit(machine, baseRevision);
      MachineNetworkManager.sendEditSession(machine.id, false);
    }
    this.minecraft.gui.setScreen(parent);
  }

  /**
   * Always leaves the screen. When the machine validates, it is saved first; otherwise it exits
   * WITHOUT saving (the session is discarded) and releases the edit lock so the machine is not
   * stuck for other players.
   */
  private void backAndClose() {
    if (validateMachine().isEmpty()) {
      saveAndClose();
      return;
    }
    if (!isNewMachine && machine.id != null && !machine.id.isEmpty()) {
      MachineNetworkManager.sendEditSession(machine.id, false);
    }
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public void onClose() {
    // Safety net: release the edit lock whenever the screen is closed by the game
    if (!isNewMachine && machine.id != null && !machine.id.isEmpty()) {
      MachineNetworkManager.sendEditSession(machine.id, false);
    }
    super.onClose();
  }

  /**
   * Generates a unique machine id from the name (lowercase, dashes) and de-duplicates against
   * existing machines by appending a counter.
   */
  private String generateId(String name) {
    String base = name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
    if (base.isEmpty()) {
      base = "machine";
    }
    String candidate = base;
    int n = 2;
    while (MachineNetworkManager.getMachine(candidate) != null) {
      candidate = base + "-" + n;
      n++;
    }
    return candidate;
  }

  /**
   * Parses a comma / space / Chinese-comma separated string into a trimmed non-empty list.
   */
  private static List<String> parseList(String text) {
    List<String> result = new ArrayList<>();
    if (text == null) {
      return result;
    }
    for (String part : text.split("[,，;；\\s]+")) {
      if (!part.isEmpty()) {
        result.add(part);
      }
    }
    return result;
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
      saveAndClose();
      return true;
    }
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      backAndClose();
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

    renderLabel(guiGraphics, fieldX, 32,
        Component.translatable("screen.command-gui.machine.name"));
    renderLabel(guiGraphics, fieldX + HALF_FIELD_WIDTH + FIELD_GAP, 32,
        Component.translatable("screen.command-gui.machine.category"));
    renderLabel(guiGraphics, fieldX, 32 + rowGap,
        Component.translatable("screen.command-gui.machine.description"));
    renderLabel(guiGraphics, fieldX, 32 + rowGap * 2,
        Component.translatable("screen.command-gui.machine.bots"));
    renderLabel(guiGraphics, fieldX + FIELD_WIDTH - 72, 32 + rowGap * 2,
        Component.translatable("screen.command-gui.machine.switch_interval"));
    if (isConfigEditor) {
      renderLabel(guiGraphics, fieldX, 32 + rowGap * 3,
          Component.translatable("screen.command-gui.machine.permission"));
      renderLabel(guiGraphics, fieldX + 68, 32 + rowGap * 3,
          Component.translatable("screen.command-gui.machine.players"));
    }

    if (!errorMessage.isEmpty()) {
      guiGraphics.text(this.font, Component.literal(errorMessage),
          fieldX, buttonsRowY + 26, 0xFFFF5555);
    }
  }

  private void renderLabel(GuiGraphicsExtractor guiGraphics, int x, int fieldY, Component label) {
    guiGraphics.text(this.font, label, x, fieldY - 12, 0xFFAAAAAA);
  }
}

package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineModels.ModeData;
import com.remrin.client.machine.MachineModels.Timeline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Editor for a single machine mode: the mode name plus its boot process and an optional shutdown
 * process (both reusing {@link TimelineEditorScreen}). Edits a working copy; "保存" commits it back
 * to the mode list. A "沿用开机流程" checkbox imports the machine's boot timeline into the mode's
 * boot process in one click.
 */
public class ModeEditorScreen extends BaseParentedScreen<ModesEditorScreen> {

  private static final int FIELD_WIDTH = 300;
  private static final int FIELD_HEIGHT = 20;

  private final ModeData original;
  private final ModeData working;
  private final List<String> botNames;
  private final Runnable onChanged;
  private String errorMessage = "";
  /** Live text backup so typed input survives screen re-init (setScreen re-inits the target). */
  private String nameText = "";
  private String intervalText = "";
  private EditBox nameField;
  private EditBox intervalField;
  private Button onTimelineButton;
  private Button offTimelineButton;
  private Button detectionButton;
  private Checkbox followBootCheckbox;

  public ModeEditorScreen(ModesEditorScreen parent, ModeData original, List<String> botNames,
      Runnable onChanged) {
    super(Component.translatable("screen.command-gui.machine.mode_title"), parent);
    this.original = original;
    this.working = copy(original);
    this.botNames = botNames;
    this.onChanged = onChanged;
    this.nameText = working.name != null ? working.name : "";
    this.intervalText = String.valueOf(working.switchInterval);
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - FIELD_WIDTH) / 2;

    nameField = new EditBox(this.font, fieldX, 48, FIELD_WIDTH - 76, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.mode_name"));
    nameField.setMaxLength(30);
    nameField.setValue(nameText);
    nameField.setResponder(text -> nameText = text);
    this.addRenderableWidget(nameField);

    // Optional lock window (ticks); 0 = follow the machine's switch interval
    intervalField = new EditBox(this.font, fieldX + FIELD_WIDTH - 72, 48, 72, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.switch_interval"));
    intervalField.setMaxLength(4);
    intervalField.setValue(intervalText);
    intervalField.setHint(Component.translatable("screen.command-gui.machine.switch_interval_hint"));
    intervalField.setResponder(text -> intervalText = text);
    this.addRenderableWidget(intervalField);

    int processWidth = (FIELD_WIDTH - 16) / 3;
    onTimelineButton = Button.builder(
        buildProcessLabel(true),
        btn -> openProcessEditor(true)
    ).bounds(fieldX, 90, processWidth, 18).build();
    this.addRenderableWidget(onTimelineButton);

    offTimelineButton = Button.builder(
        buildProcessLabel(false),
        btn -> openProcessEditor(false)
    ).bounds(fieldX + processWidth + 8, 90, processWidth, 18).build();
    this.addRenderableWidget(offTimelineButton);

    detectionButton = Button.builder(
        buildDetectionLabel(),
        btn -> openDetectionEditor()
    ).bounds(fieldX + (processWidth + 8) * 2, 90, processWidth, 18).build();
    this.addRenderableWidget(detectionButton);

    // "沿用开机流程": checking it imports the machine's boot timeline into this mode's boot
    // process (one-shot copy — the mode stays editable afterwards).
    followBootCheckbox = Checkbox.builder(
        Component.translatable("screen.command-gui.machine.follow_boot"),
        this.font
    ).pos(fieldX, 114).selected(false)
        .onValueChange((checkbox, selected) -> {
          if (selected) {
            importMachineBoot();
          }
        }).build();
    this.addRenderableWidget(followBootCheckbox);

    int barY = this.height - 22;
    int barWidth = Math.min(70, FIELD_WIDTH / 3);
    int barStartX = fieldX + (FIELD_WIDTH - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());
    // Back always leaves: exit without saving when the mode name is empty
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> backAndClose()
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  private void openProcessEditor(boolean onProcess) {
    Timeline timeline = onProcess ? working.onTimeline : working.offTimeline;
    this.minecraft.gui.setScreen(new TimelineEditorScreen(
        this,
        Component.translatable(onProcess
            ? "screen.command-gui.machine.mode_boot_title"
            : "screen.command-gui.machine.mode_shutdown_title"),
        timeline,
        onProcess,
        false, // modes never loop
        () -> updateProcessButtons(),
        botNames));
  }

  private void openDetectionEditor() {
    this.minecraft.gui.setScreen(new DetectionScreen(this, working.detection,
        detection -> working.detection = detection));
  }

  /**
   * Copies the machine's boot timeline (开关机流程-开机) into this mode's boot process. One-shot
   * import: the mode keeps its own copy afterwards and stays fully editable.
   */
  private void importMachineBoot() {
    MachineData machine = parent.getMachine();
    if (machine == null || machine.onTimeline == null || machine.onTimeline.steps.isEmpty()) {
      errorMessage = Component.translatable("screen.command-gui.machine.error_follow_boot_empty")
          .getString();
      return;
    }
    Timeline imported = new Timeline();
    copyTimelineInto(machine.onTimeline, imported);
    working.onTimeline = imported;
    errorMessage = "";
    updateProcessButtons();
  }

  private void updateProcessButtons() {
    if (onTimelineButton != null) {
      onTimelineButton.setMessage(buildProcessLabel(true));
    }
    if (offTimelineButton != null) {
      offTimelineButton.setMessage(buildProcessLabel(false));
    }
    if (detectionButton != null) {
      detectionButton.setMessage(buildDetectionLabel());
    }
  }

  private Component buildProcessLabel(boolean onProcess) {
    int steps = (onProcess ? working.onTimeline : working.offTimeline).steps.size();
    return Component.translatable(onProcess
        ? "screen.command-gui.machine.mode_boot"
        : "screen.command-gui.machine.mode_shutdown", steps);
  }

  private Component buildDetectionLabel() {
    if (working.detection == null || !working.detection.isConfigured()) {
      return Component.translatable("screen.command-gui.machine.detection_none_short");
    }
    if (working.detection.enabled) {
      return Component.translatable("screen.command-gui.machine.detection_on_short");
    }
    return Component.translatable("screen.command-gui.machine.detection_off_short");
  }

  private void saveAndClose() {
    errorMessage = "";
    // Read the live widget directly (the backup only serves re-init restoration)
    String name = nameField.getValue().trim();
    if (name.isEmpty()) {
      errorMessage = Component.translatable("screen.command-gui.machine.error_mode_name")
          .getString();
      return;
    }
    // Timeline validity (spawn-first, non-empty steps) is enforced by the machine editor on save,
    // so a half-configured mode can always be saved/left here and fixed later.
    working.name = name;
    try {
      working.switchInterval = Math.max(0, Math.min(1200,
          Integer.parseInt(intervalField.getValue().trim())));
    } catch (NumberFormatException ignored) {
      working.switchInterval = 0;
    }
    copyInto(working, original);
    if (onChanged != null) {
      onChanged.run();
    }
    this.minecraft.gui.setScreen(parent);
  }

  /**
   * Always leaves the screen: saves when the mode name is valid, otherwise exits without saving.
   */
  private void backAndClose() {
    if (!nameField.getValue().trim().isEmpty()) {
      saveAndClose();
      return;
    }
    this.minecraft.gui.setScreen(parent);
  }

  private static ModeData copy(ModeData mode) {
    ModeData copy = new ModeData();
    copy.id = mode.id;
    copy.name = mode.name;
    copy.singleSelect = mode.singleSelect;
    copy.switchInterval = mode.switchInterval;
    copy.detection = copyDetection(mode.detection);
    copyTimelineInto(mode.onTimeline, copy.onTimeline);
    copyTimelineInto(mode.offTimeline, copy.offTimeline);
    return copy;
  }

  private static void copyInto(ModeData from, ModeData to) {
    to.id = from.id;
    to.name = from.name;
    to.singleSelect = from.singleSelect;
    to.switchInterval = from.switchInterval;
    to.detection = copyDetection(from.detection);
    copyTimelineInto(from.onTimeline, to.onTimeline);
    copyTimelineInto(from.offTimeline, to.offTimeline);
  }

  private static com.remrin.client.machine.MachineModels.DetectionData copyDetection(
      com.remrin.client.machine.MachineModels.DetectionData detection) {
    if (detection == null) {
      return null;
    }
    com.remrin.client.machine.MachineModels.DetectionData copy =
        new com.remrin.client.machine.MachineModels.DetectionData();
    copy.enabled = detection.enabled;
    copy.dimension = detection.dimension;
    copy.x = detection.x;
    copy.y = detection.y;
    copy.z = detection.z;
    copy.blockId = detection.blockId;
    copy.property = detection.property;
    copy.onValues = new ArrayList<>(detection.onValues);
    copy.offValues = new ArrayList<>(detection.offValues);
    return copy;
  }

  private static void copyTimelineInto(Timeline from, Timeline to) {
    to.loopCount = from.loopCount;
    to.steps = new ArrayList<>();
    for (MachineModels.Step step : from.steps) {
      MachineModels.Step stepCopy = new MachineModels.Step();
      stepCopy.delay = step.delay;
      stepCopy.bot = step.bot;
      stepCopy.commands = new ArrayList<>(step.commands);
      to.steps.add(stepCopy);
    }
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

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.mode_name"), fieldX, 36, 0xFFAAAAAA);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.switch_interval"),
        fieldX + FIELD_WIDTH - 72, 36, 0xFFAAAAAA);

    if (!errorMessage.isEmpty()) {
      guiGraphics.text(this.font, Component.literal(errorMessage), fieldX, 140, 0xFFFF5555);
    }
  }
}

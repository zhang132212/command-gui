package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Action quick-config popup for the step editor (the merged left/right-click button): picks the
 * action (attack = left click / use = right click) and its execution mode (once / continuous /
 * interval with a tick count), generating a carpet command such as
 * {@code /player {bot} attack interval 40}.
 * "插入" writes the command into the step editor's command list.
 */
public class ActionOptionScreen extends BaseParentedScreen<StepEditorScreen> {

  private static final int FIELD_WIDTH = 180;
  private static final int FIELD_HEIGHT = 16;
  private static final int LABEL_COLOR = 0xFFAAAAAA;
  private static final int BTN_GAP = 4;

  private static final String[] ACTIONS = {"attack", "use"};
  private static final String[] ACTION_NAMES = {
      "screen.command-gui.machine.action_attack",
      "screen.command-gui.machine.action_use"
  };
  private static final String[] MODES = {"once", "continuous", "interval"};
  private static final String[] MODE_NAMES = {
      "screen.command-gui.machine.action_once",
      "screen.command-gui.machine.action_continuous",
      "screen.command-gui.machine.action_interval"
  };

  private final List<Button> actionButtons = new ArrayList<>();
  private final List<Button> modeButtons = new ArrayList<>();
  private int actionIndex = 0;
  private int modeIndex = 0;
  private String intervalText = "40";
  private EditBox intervalField;

  public ActionOptionScreen(StepEditorScreen parent) {
    super(Component.translatable("screen.command-gui.machine.action_title"), parent);
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - FIELD_WIDTH) / 2;

    // Action buttons (2 in a row); selected action turns dark
    int actionBtnWidth = (FIELD_WIDTH - BTN_GAP) / 2;
    for (int i = 0; i < ACTIONS.length; i++) {
      final int idx = i;
      int btnX = fieldX + i * (actionBtnWidth + BTN_GAP);
      DarkSelectButton btn = new DarkSelectButton(btnX, 34, actionBtnWidth, FIELD_HEIGHT,
          Component.translatable(ACTION_NAMES[i]), b -> actionIndex = idx);
      btn.setDarkSelected(() -> actionIndex == idx, 0xFFFFFFFF);
      actionButtons.add(btn);
      this.addRenderableWidget(btn);
    }

    // Mode buttons (3 in a row); selected mode turns dark
    int modeBtnWidth = (FIELD_WIDTH - 2 * BTN_GAP) / 3;
    for (int i = 0; i < MODES.length; i++) {
      final int idx = i;
      int btnX = fieldX + i * (modeBtnWidth + BTN_GAP);
      DarkSelectButton btn = new DarkSelectButton(btnX, 70, modeBtnWidth, FIELD_HEIGHT,
          Component.translatable(MODE_NAMES[i]), b -> modeIndex = idx);
      btn.setDarkSelected(() -> modeIndex == idx, 0xFFFFFFFF);
      modeButtons.add(btn);
      this.addRenderableWidget(btn);
    }

    // Interval (ticks)
    intervalField = new EditBox(this.font, fieldX, 106, 80, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.action_interval_label"));
    intervalField.setMaxLength(7);
    intervalField.setValue(intervalText);
    intervalField.setResponder(text -> intervalText = text);
    this.addRenderableWidget(intervalField);

    int barY = this.height - 22;
    int barWidth = Math.min(70, 100);
    int barStartX = fieldX + (FIELD_WIDTH - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_insert"),
        btn -> insertAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  /**
   * Builds the action command using carpet's syntax:
   * {@code /player {bot} attack once|continuous|interval N}.
   */
  private String buildCommand() {
    String cmd = "/player {bot} " + ACTIONS[actionIndex];
    switch (modeIndex) {
      case 0 -> cmd += " once";
      case 1 -> cmd += " continuous";
      case 2 -> {
        int ticks = 40;
        try {
          ticks = Integer.parseInt(intervalText.trim());
        } catch (NumberFormatException ignored) {
        }
        cmd += " interval " + Math.max(1, ticks);
      }
      default -> {
      }
    }
    return cmd;
  }

  private void insertAndClose() {
    List<String> commands = new ArrayList<>();
    commands.add(buildCommand());
    parent.insertCommandSequence(commands);
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
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
        Component.translatable("screen.command-gui.machine.action_label"),
        fieldX, 22, LABEL_COLOR);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.action_mode"),
        fieldX, 58, LABEL_COLOR);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.action_interval_label"),
        fieldX, 94, LABEL_COLOR);

    // Command preview
    String preview = buildCommand();
    String display = this.font.plainSubstrByWidth(preview, FIELD_WIDTH - 8);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.command_preview"),
        fieldX, 136, 0xFF888888);
    guiGraphics.text(this.font, Component.literal(display), fieldX + 4, 146, 0xFF55FF55);
  }
}

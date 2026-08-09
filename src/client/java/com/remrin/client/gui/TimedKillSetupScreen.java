package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Configuration screen for timed fake player killing. Receives the target fake player name (passed
 * from {@link FakePlayerTab}) and allows setting the countdown time (hours/minutes/seconds).
 * Confirms by registering a timed kill task via {@link TimedTaskManager}.
 */
public class TimedKillSetupScreen extends BaseParentedScreen<Screen> {

  private static final int TIME_FIELD_WIDTH = 45;
  private static final int COLON_GAP = 14;

  private final String playerName;
  private EditBox hoursField;
  private EditBox minutesField;
  private EditBox secondsField;

  private int hours = 0;
  private int minutes = 0;
  private int seconds = 10;

  public TimedKillSetupScreen(Screen parent, String playerName) {
    super(Component.translatable("screen.command-gui.fakeplayer.timed.kill.title"), parent);
    this.playerName = playerName;
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    int y = centerY - 25;
    int totalTimeWidth = TIME_FIELD_WIDTH * 3 + COLON_GAP * 2;
    int timeStartX = centerX - totalTimeWidth / 2;

    hoursField = new EditBox(this.font, timeStartX, y, TIME_FIELD_WIDTH, 20,
        Component.literal("H"));
    hoursField.setMaxLength(5);
    hoursField.setValue(String.valueOf(hours));
    hoursField.setResponder(s -> {
      try {
        hours = Math.max(0, Integer.parseInt(s));
      } catch (NumberFormatException e) {
        hours = 0;
      }
    });
    this.addRenderableWidget(hoursField);

    minutesField = new EditBox(this.font, timeStartX + TIME_FIELD_WIDTH + COLON_GAP, y,
        TIME_FIELD_WIDTH, 20,
        Component.literal("M"));
    minutesField.setMaxLength(5);
    minutesField.setValue(String.valueOf(minutes));
    minutesField.setResponder(s -> {
      try {
        minutes = Math.max(0, Integer.parseInt(s));
      } catch (NumberFormatException e) {
        minutes = 0;
      }
    });
    this.addRenderableWidget(minutesField);

    secondsField = new EditBox(this.font, timeStartX + (TIME_FIELD_WIDTH + COLON_GAP) * 2, y,
        TIME_FIELD_WIDTH, 20,
        Component.literal("S"));
    secondsField.setMaxLength(5);
    secondsField.setValue(String.valueOf(seconds));
    secondsField.setResponder(s -> {
      try {
        seconds = Math.max(0, Integer.parseInt(s));
      } catch (NumberFormatException e) {
        seconds = 0;
      }
    });
    this.addRenderableWidget(secondsField);

    y += 52;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> {
          if (hours > 0 || minutes > 0 || seconds > 0) {
            TimedTaskManager.addKillTask(playerName, hours, minutes, seconds);
            this.minecraft.gui.setScreen(parent);
          }
        }
    ).bounds(centerX - 102, y, 100, 20).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.cancel"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(centerX + 2, y, 100, 20).build());
  }

  private String formatDuration(int totalSeconds) {
    return CommandHelper.formatDuration(totalSeconds);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    guiGraphics.centeredText(this.font, this.title, centerX, centerY - 78, 0xFFFFFFFF);

    guiGraphics.centeredText(this.font,
        Component.literal(playerName),
        centerX, centerY - 62, 0xFF55FF55);

    int y = centerY - 25;
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.fakeplayer.timed.time"),
        centerX, y - 24, 0xFFAAAAAA);

    int totalTimeWidth = TIME_FIELD_WIDTH * 3 + COLON_GAP * 2;
    int timeStartX = centerX - totalTimeWidth / 2;

    guiGraphics.centeredText(this.font, "H", timeStartX + TIME_FIELD_WIDTH / 2, y - 12,
        0xFF888888);
    guiGraphics.centeredText(this.font, "M",
        timeStartX + TIME_FIELD_WIDTH + COLON_GAP + TIME_FIELD_WIDTH / 2, y - 12, 0xFF888888);
    guiGraphics.centeredText(this.font, "S",
        timeStartX + (TIME_FIELD_WIDTH + COLON_GAP) * 2 + TIME_FIELD_WIDTH / 2, y - 12, 0xFF888888);

    int colonY = y + 6;
    guiGraphics.text(this.font, ":", timeStartX + TIME_FIELD_WIDTH + COLON_GAP / 2 - 2,
        colonY, 0xFFCCCCCC);
    guiGraphics.text(this.font, ":",
        timeStartX + TIME_FIELD_WIDTH + COLON_GAP + TIME_FIELD_WIDTH + COLON_GAP / 2 - 2, colonY,
        0xFFCCCCCC);

    int totalSec = hours * 3600 + minutes * 60 + seconds;
    int previewColor = totalSec > 0 ? 0xFFFF7755 : 0xFF666666;
    guiGraphics.centeredText(this.font,
        Component.literal(formatDuration(totalSec)),
        centerX, y + 28, previewColor);
  }
}

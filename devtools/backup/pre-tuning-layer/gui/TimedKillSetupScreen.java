package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      int y = centerY - 25;
      int totalTimeWidth = 163;
      int timeStartX = centerX - totalTimeWidth / 2;
      this.hoursField = new EditBox(this.font, timeStartX, y, 45, 20, Component.literal("H"));
      this.hoursField.setMaxLength(5);
      this.hoursField.setValue(String.valueOf(this.hours));
      this.hoursField.setResponder(s -> {
         try {
            this.hours = Math.max(0, Integer.parseInt(s));
         } catch (NumberFormatException var3x) {
            this.hours = 0;
         }
      });
      this.addRenderableWidget(this.hoursField);
      this.minutesField = new EditBox(this.font, timeStartX + 45 + 14, y, 45, 20, Component.literal("M"));
      this.minutesField.setMaxLength(5);
      this.minutesField.setValue(String.valueOf(this.minutes));
      this.minutesField.setResponder(s -> {
         try {
            this.minutes = Math.max(0, Integer.parseInt(s));
         } catch (NumberFormatException var3x) {
            this.minutes = 0;
         }
      });
      this.addRenderableWidget(this.minutesField);
      this.secondsField = new EditBox(this.font, timeStartX + 118, y, 45, 20, Component.literal("S"));
      this.secondsField.setMaxLength(5);
      this.secondsField.setValue(String.valueOf(this.seconds));
      this.secondsField.setResponder(s -> {
         try {
            this.seconds = Math.max(0, Integer.parseInt(s));
         } catch (NumberFormatException var3x) {
            this.seconds = 0;
         }
      });
      this.addRenderableWidget(this.secondsField);
      y += 52;
      this.addRenderableWidget(Button.builder(Component.translatable("screen.command-gui.save"), btn -> {
         if (this.hours > 0 || this.minutes > 0 || this.seconds > 0) {
            TimedTaskManager.addKillTask(this.playerName, this.hours, this.minutes, this.seconds);
            this.minecraft.gui.setScreen(this.parent);
         }
      }).bounds(centerX - 102, y, 100, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.cancel"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX + 2, y, 100, 20)
            .build()
      );
   }

   private String formatDuration(int totalSeconds) {
      return CommandHelper.formatDuration(totalSeconds);
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 78, -1);
      guiGraphics.centeredText(this.font, Component.literal(this.playerName), centerX, centerY - 62, -11141291);
      int y = centerY - 25;
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.fakeplayer.timed.time"), centerX, y - 24, -5592406);
      int totalTimeWidth = 163;
      int timeStartX = centerX - totalTimeWidth / 2;
      guiGraphics.centeredText(this.font, "H", timeStartX + 22, y - 12, -7829368);
      guiGraphics.centeredText(this.font, "M", timeStartX + 45 + 14 + 22, y - 12, -7829368);
      guiGraphics.centeredText(this.font, "S", timeStartX + 118 + 22, y - 12, -7829368);
      int colonY = y + 6;
      guiGraphics.text(this.font, ":", timeStartX + 45 + 7 - 2, colonY, -3355444);
      guiGraphics.text(this.font, ":", timeStartX + 45 + 14 + 45 + 7 - 2, colonY, -3355444);
      int totalSec = this.hours * 3600 + this.minutes * 60 + this.seconds;
      int previewColor = totalSec > 0 ? -34987 : -10066330;
      guiGraphics.centeredText(this.font, Component.literal(this.formatDuration(totalSec)), centerX, y + 28, previewColor);
   }
}

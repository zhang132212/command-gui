package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TimedSpawnSetupScreen extends BaseParentedScreen<Screen> {
   private static final int TIME_FIELD_WIDTH = 45;
   private static final int COLON_GAP = 14;
   private static final int COORD_FIELD_WIDTH = 58;
   private static final int COORD_GAP = 6;
   private static final int FULL_CONTENT_H = 205;
   private EditBox nameField;
   private EditBox hoursField;
   private EditBox minutesField;
   private EditBox secondsField;
   private EditBox xField;
   private EditBox yField;
   private EditBox zField;
   private Button posToggle;
   private String playerName = "Bot_1";
   private int hours = 0;
   private int minutes = 0;
   private int seconds = 10;
   private boolean useCurrentPos = true;
   private double spawnX = 0.0;
   private double spawnY = 64.0;
   private double spawnZ = 0.0;
   private int titleY;

   public TimedSpawnSetupScreen(Screen parent) {
      super(Component.translatable("screen.command-gui.fakeplayer.timed.spawn.title"), parent);
   }

   private int computeTitleY() {
      return Math.max(15, (this.height - 205) / 2 - 10);
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      this.titleY = this.computeTitleY();
      int nameFieldY = this.titleY + 34;
      int timeFieldY = nameFieldY + 45;
      int posToggleY = timeFieldY + 42;
      int coordFieldY = posToggleY + 28;
      int saveCancelY = coordFieldY + 36;
      this.nameField = new GuiEditBox(this.font, centerX - 75, nameFieldY, 150, 20, Component.translatable("screen.command-gui.fakeplayer.timed.name"));
      this.nameField.setMaxLength(20);
      this.nameField.setValue(this.playerName);
      this.nameField.setResponder(s -> this.playerName = s);
      this.addRenderableWidget(this.nameField);
      int totalTimeWidth = 163;
      int timeStartX = centerX - totalTimeWidth / 2;
      this.hoursField = new GuiEditBox(this.font, timeStartX, timeFieldY, 45, 20, Component.literal("H"));
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
      this.minutesField = new GuiEditBox(this.font, timeStartX + 45 + 14, timeFieldY, 45, 20, Component.literal("M"));
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
      this.secondsField = new GuiEditBox(this.font, timeStartX + 118, timeFieldY, 45, 20, Component.literal("S"));
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
      this.posToggle = GuiButton.themed(this.getPosToggleLabel(), btn -> {
         this.useCurrentPos = !this.useCurrentPos;
         this.posToggle.setMessage(this.getPosToggleLabel());
         this.updateCoordFieldVisibility();
      }).bounds(centerX - 90, posToggleY, 180, 20).build();
      this.addRenderableWidget(this.posToggle);
      int coordTotalWidth = 186;
      int coordStartX = centerX - coordTotalWidth / 2;
      this.xField = new GuiEditBox(this.font, coordStartX, coordFieldY, 58, 20, Component.literal("X"));
      this.xField.setMaxLength(12);
      this.xField.setResponder(s -> {
         try {
            this.spawnX = Double.parseDouble(s);
         } catch (NumberFormatException var3x) {
            this.spawnX = 0.0;
         }
      });
      this.addRenderableWidget(this.xField);
      this.yField = new GuiEditBox(this.font, coordStartX + 58 + 6, coordFieldY, 58, 20, Component.literal("Y"));
      this.yField.setMaxLength(12);
      this.yField.setResponder(s -> {
         try {
            this.spawnY = Double.parseDouble(s);
         } catch (NumberFormatException var3x) {
            this.spawnY = 64.0;
         }
      });
      this.addRenderableWidget(this.yField);
      this.zField = new GuiEditBox(this.font, coordStartX + 128, coordFieldY, 58, 20, Component.literal("Z"));
      this.zField.setMaxLength(12);
      this.zField.setResponder(s -> {
         try {
            this.spawnZ = Double.parseDouble(s);
         } catch (NumberFormatException var3x) {
            this.spawnZ = 0.0;
         }
      });
      this.addRenderableWidget(this.zField);
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         this.spawnX = mc.player.getX();
         this.spawnY = mc.player.getY();
         this.spawnZ = mc.player.getZ();
      }

      this.xField.setValue(CommandHelper.formatX(this.spawnX));
      this.yField.setValue(CommandHelper.formatY(this.spawnY));
      this.zField.setValue(CommandHelper.formatZ(this.spawnZ));
      this.updateCoordFieldVisibility();
      this.addRenderableWidget(GuiButton.themed(Component.translatable("screen.command-gui.save"), btn -> {
         if (!this.playerName.isEmpty() && (this.hours > 0 || this.minutes > 0 || this.seconds > 0)) {
            if (this.useCurrentPos) {
               TimedTaskManager.addSpawnTask(this.playerName, this.hours, this.minutes, this.seconds);
            } else {
               TimedTaskManager.addSpawnTask(this.playerName, this.hours, this.minutes, this.seconds, this.spawnX, this.spawnY, this.spawnZ);
            }

            this.minecraft.gui.setScreen(this.parent);
         }
      }).bounds(centerX - 102, saveCancelY, 100, 20).build());
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.cancel"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX + 2, saveCancelY, 100, 20)
            .build()
      );
   }

   private Component getPosToggleLabel() {
      return Component.translatable(
         this.useCurrentPos ? "screen.command-gui.fakeplayer.timed.spawn.pos.current" : "screen.command-gui.fakeplayer.timed.spawn.pos.custom"
      );
   }

   private void updateCoordFieldVisibility() {
      this.xField.visible = !this.useCurrentPos;
      this.xField.active = !this.useCurrentPos;
      this.yField.visible = !this.useCurrentPos;
      this.yField.active = !this.useCurrentPos;
      this.zField.visible = !this.useCurrentPos;
      this.zField.active = !this.useCurrentPos;
   }

   private String formatDuration(int totalSeconds) {
      return CommandHelper.formatDuration(totalSeconds);
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int nameFieldY = this.titleY + 34;
      int timeFieldY = nameFieldY + 45;
      int coordFieldY = timeFieldY + 42 + 28;
      guiGraphics.centeredText(this.font, this.title, centerX, this.titleY, -1);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.fakeplayer.timed.name"), centerX, nameFieldY - 12, -5592406);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.fakeplayer.timed.time"), centerX, timeFieldY - 24, -5592406);
      int totalTimeWidth = 163;
      int timeStartX = centerX - totalTimeWidth / 2;
      guiGraphics.centeredText(this.font, "H", timeStartX + 22, timeFieldY - 12, -7829368);
      guiGraphics.centeredText(this.font, "M", timeStartX + 45 + 14 + 22, timeFieldY - 12, -7829368);
      guiGraphics.centeredText(this.font, "S", timeStartX + 118 + 22, timeFieldY - 12, -7829368);
      int colonY = timeFieldY + 6;
      guiGraphics.text(this.font, ":", timeStartX + 45 + 7 - 2, colonY, -3355444);
      guiGraphics.text(this.font, ":", timeStartX + 45 + 14 + 45 + 7 - 2, colonY, -3355444);
      int totalSec = this.hours * 3600 + this.minutes * 60 + this.seconds;
      int previewColor = totalSec > 0 ? -11141121 : -10066330;
      guiGraphics.centeredText(this.font, Component.literal(this.formatDuration(totalSec)), centerX, timeFieldY + 28, previewColor);
      if (!this.useCurrentPos) {
         int coordTotalWidth = 186;
         int coordStartX = centerX - coordTotalWidth / 2;
         guiGraphics.centeredText(this.font, "X", coordStartX + 29, coordFieldY - 10, -7829368);
         guiGraphics.centeredText(this.font, "Y", coordStartX + 58 + 6 + 29, coordFieldY - 10, -7829368);
         guiGraphics.centeredText(this.font, "Z", coordStartX + 128 + 29, coordFieldY - 10, -7829368);
      }
   }
}

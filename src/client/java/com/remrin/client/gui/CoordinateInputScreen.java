package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class CoordinateInputScreen extends BaseParentedScreen<Screen> {
   private final String commandTemplate;
   private EditBox xField;
   private EditBox yField;
   private EditBox zField;
   private static final int FIELD_WIDTH = 60;
   private static final int FIELD_GAP = 5;

   public CoordinateInputScreen(Screen parent, Component title, String commandTemplate) {
      super(title, parent);
      this.commandTemplate = commandTemplate;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      int totalWidth = 190;
      int startX = centerX - totalWidth / 2;
      this.xField = new EditBox(this.font, startX, centerY - 30, 60, 20, Component.literal("X"));
      this.xField.setMaxLength(10);
      this.xField.setHint(Component.literal("X"));
      this.addRenderableWidget(this.xField);
      this.setInitialFocus(this.xField);
      this.yField = new EditBox(this.font, startX + 60 + 5, centerY - 30, 60, 20, Component.literal("Y"));
      this.yField.setMaxLength(10);
      this.yField.setHint(Component.literal("Y"));
      this.addRenderableWidget(this.yField);
      this.zField = new EditBox(this.font, startX + 130, centerY - 30, 60, 20, Component.literal("Z"));
      this.zField.setMaxLength(10);
      this.zField.setHint(Component.literal("Z"));
      this.addRenderableWidget(this.zField);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.coord.current"), btn -> this.fillCurrentPosition())
            .bounds(centerX - 75, centerY + 5, 150, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.coord.current_block"), btn -> this.fillCurrentBlockPosition())
            .bounds(centerX - 75, centerY + 30, 150, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX - 75, centerY + 60, 150, 20)
            .build()
      );
   }

   private void fillCurrentPosition() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         this.xField.setValue(CommandHelper.formatX(mc.player.getX()));
         this.yField.setValue(CommandHelper.formatY(mc.player.getY()));
         this.zField.setValue(CommandHelper.formatZ(mc.player.getZ()));
      }
   }

   private void fillCurrentBlockPosition() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         this.xField.setValue(String.valueOf(mc.player.getBlockX()));
         this.yField.setValue(String.valueOf(mc.player.getBlockY()));
         this.zField.setValue(String.valueOf(mc.player.getBlockZ()));
      }
   }

   protected void onCoordsConfirmed(String x, String y, String z) {
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 257 || keyCode == 335) {
         String x = this.xField.getValue().trim();
         String y = this.yField.getValue().trim();
         String z = this.zField.getValue().trim();
         if (!x.isEmpty() && !y.isEmpty() && !z.isEmpty()) {
            this.onCoordsConfirmed(x, y, z);
            if (this.commandTemplate != null) {
               String command = this.commandTemplate.replace("{x}", x).replace("{y}", y).replace("{z}", z).replace("{coords}", x + " " + y + " " + z);
               this.executeCommand(command);
            }
         }

         return true;
      } else if (keyCode == 258) {
         if (this.xField.isFocused()) {
            this.setFocused(this.yField);
         } else if (this.yField.isFocused()) {
            this.setFocused(this.zField);
         } else if (this.zField.isFocused()) {
            this.setFocused(this.xField);
         }

         return true;
      } else if (keyCode == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   private void executeCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         mc.gui.setScreen(null);
         ChainedCommandExecutor.sendCommand(command);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 60, -1);
      int totalWidth = 190;
      int startX = centerX - totalWidth / 2;
      guiGraphics.text(this.font, "X", startX, centerY - 42, -43691);
      guiGraphics.text(this.font, "Y", startX + 60 + 5, centerY - 42, -11141291);
      guiGraphics.text(this.font, "Z", startX + 130, centerY - 42, -11184641);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.enter_to_confirm"), centerX, centerY + 90, -7829368);
   }
}

package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.Component;

public class PassiveButton extends Button {
   public PassiveButton(int x, int y, int width, int height, Component message, OnPress onPress) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
   }

   public boolean shouldTakeFocusAfterInteraction() {
      return false;
   }

   protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      this.extractDefaultSprite(guiGraphics);
      Font font = Minecraft.getInstance().font;
      int color = this.active ? -1 : -6250336;
      guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, this.getY() + (this.getHeight() - 8) / 2, color);
   }
}

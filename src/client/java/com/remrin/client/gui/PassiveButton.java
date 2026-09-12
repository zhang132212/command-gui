package com.remrin.client.gui;

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
      GuiTheme.button(guiGraphics, this, false, this.active, mouseX, mouseY);
      GuiTheme.label(guiGraphics, this, this.getMessage(), this.active ? GuiTheme.text() : GuiTheme.disabled(), true);
   }
}

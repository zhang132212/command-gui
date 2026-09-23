package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.Component;

public class MarkCheckbox extends Button {
   private boolean selected;
   private boolean available = true;

   public MarkCheckbox(int x, int y, int width, int height, Component message, boolean selected, OnPress onPress) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
      this.selected = selected;
   }

   public boolean selected() {
      return this.selected;
   }

   public void setSelected(boolean selected) {
      this.selected = selected;
   }

   public void setAvailable(boolean available) {
      this.available = available;
      this.active = available;
   }

   public boolean isMouseOver(double mouseX, double mouseY) {
      if (!this.active) {
         return false;
      } else {
         int boxSize = 14;
         int boxX = this.getX();
         int boxY = this.getY() + (this.getHeight() - boxSize) / 2;
         return mouseX >= (double)boxX && mouseX < (double)(boxX + boxSize) && mouseY >= (double)boxY && mouseY < (double)(boxY + boxSize);
      }
   }

   protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      Font font = Minecraft.getInstance().font;
      int boxSize = 14;
      int boxX = this.getX();
      int boxY = this.getY() + (this.getHeight() - boxSize) / 2;
      GuiTheme.checkbox(guiGraphics, boxX, boxY, boxSize, this.selected, this.available,
         this.available && (this.isHovered() || this.isFocused()));

      guiGraphics.text(font, this.getMessage(), boxX + boxSize + 4, this.getY() + (this.getHeight() - 9) / 2, this.available ? GuiTheme.text() : GuiTheme.disabled());
   }
}

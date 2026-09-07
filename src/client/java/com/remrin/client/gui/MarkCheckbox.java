package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
      guiGraphics.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, -16777216);
      guiGraphics.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1, this.available ? -13421773 : -14540254);
      if (!this.available) {
         guiGraphics.blitSprite(
            RenderPipelines.GUI_TEXTURED, Identifier.parse("minecraft:spectator/close"), boxX + 2, boxY + 2, boxSize - 4, boxSize - 4, -43691
         );
      } else if (this.selected) {
         guiGraphics.blitSprite(
            RenderPipelines.GUI_TEXTURED, Identifier.parse("minecraft:icon/checkmark"), boxX + 2, boxY + 2, boxSize - 4, boxSize - 4, -11141291
         );
      }

      guiGraphics.text(font, this.getMessage(), boxX + boxSize + 4, this.getY() + (this.getHeight() - 9) / 2, this.available ? -2236963 : -7829368);
   }
}

package com.remrin.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Preserve vanilla editing, selection, suggestions and narration; only restyle the frame. */
public class GuiEditBox extends EditBox {
   private boolean themedBorder = true;

   public GuiEditBox(Font font, int x, int y, int width, int height, Component message) {
      super(font, x, y, width, height, message);
      this.setTextColor(GuiTheme.text());
      this.setTextColorUneditable(GuiTheme.disabled());
      this.setTextShadow(false);
   }

   @Override
   public void setBordered(boolean bordered) {
      super.setBordered(bordered);
      this.themedBorder = bordered;
   }

   @Override
   public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      g.enableScissor(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight());
      super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
      g.disableScissor();
      if (this.themedBorder) {
         GuiTheme.outline(g, this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.isFocused() ? GuiTheme.accent() : GuiTheme.border());
      }
   }
}

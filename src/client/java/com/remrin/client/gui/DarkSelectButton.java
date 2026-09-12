package com.remrin.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class DarkSelectButton extends Button {
   private Supplier<Boolean> selected = () -> false;
   private int selectedTextColor = -1;
   private int unselectedTextColor = -1;
   private Runnable onRightClick = null;
   private boolean visualMuted;

   public DarkSelectButton(int x, int y, int width, int height, Component message, OnPress onPress) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
   }

   public void setDarkSelected(Supplier<Boolean> selected, int selectedTextColor) {
      this.selected = selected;
      this.selectedTextColor = selectedTextColor;
   }

   public void setUnselectedTextColor(int color) {
      this.unselectedTextColor = color;
   }

   public void setVisualMuted(boolean muted) {
      this.visualMuted = muted;
   }

   public void setOnRightClick(Runnable action) {
      this.onRightClick = action;
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 1 && this.active && this.visible && this.isMouseOver(mouseEvent.x(), mouseEvent.y()) && this.onRightClick != null) {
         this.onRightClick.run();
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      boolean selected = this.selected.get();
      GuiTheme.button(guiGraphics, this, selected, this.active && !this.visualMuted, mouseX, mouseY);
      int color = selected ? this.selectedTextColor : this.unselectedTextColor;
      if (color == -1) color = selected ? GuiTheme.accent() : GuiTheme.text();
      if (!this.active || this.visualMuted) color = GuiTheme.disabled();
      GuiTheme.label(guiGraphics, this, this.getMessage(), color, true);
   }
}

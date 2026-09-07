package com.remrin.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class DarkSelectButton extends Button {
   private static final Identifier DARK_SPRITE = Identifier.parse("minecraft:widget/button_disabled");
   private static final int DEFAULT_TEXT_COLOR = -1;
   private Supplier<Boolean> selected = () -> false;
   private int selectedTextColor = -1;
   private int unselectedTextColor = -1;
   private Runnable onRightClick = null;

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
      Font font = Minecraft.getInstance().font;
      int textY = this.getY() + (this.getHeight() - 8) / 2;
      if (this.selected.get()) {
         guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, DARK_SPRITE, this.getX(), this.getY(), this.getWidth(), this.getHeight(), -1);
         guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, textY, this.selectedTextColor);
      } else {
         this.extractDefaultSprite(guiGraphics);
         int color;
         if (this.unselectedTextColor >= 0) {
            color = this.unselectedTextColor;
         } else {
            color = this.active ? -1 : -6250336;
         }

         guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, textY, color);
      }
   }
}

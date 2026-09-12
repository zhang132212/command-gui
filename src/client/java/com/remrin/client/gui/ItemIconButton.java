package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

public class ItemIconButton extends Button {
   private static final float ICON_SCALE = 0.75F;
   private static final int ICON_SIZE = Math.round(12.0F);
   private final ItemStack iconItem;

   public ItemIconButton(int x, int y, int width, int height, ItemStack icon, OnPress onPress) {
      super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
      this.iconItem = icon;
   }

   public ItemIconButton(int x, int y, int width, int height, ItemStack icon, Component tooltip, OnPress onPress) {
      this(x, y, width, height, icon, onPress);
      this.setMessage(tooltip);
      this.setTooltip(Tooltip.create(tooltip));
   }

   public void onPress(InputWithModifiers input) {
      this.onPress.onPress(this);
   }

   protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      GuiTheme.button(guiGraphics, this, false, this.active, mouseX, mouseY);
      int iconX = this.getX() + (this.getWidth() - ICON_SIZE) / 2;
      int iconY = this.getY() + (this.getHeight() - ICON_SIZE) / 2;
      Matrix3x2fStack pose = guiGraphics.pose();
      pose.pushMatrix();
      pose.translate((float)iconX, (float)iconY);
      pose.scale(0.75F, 0.75F);
      guiGraphics.item(this.iconItem, 0, 0);
      pose.popMatrix();
   }
}

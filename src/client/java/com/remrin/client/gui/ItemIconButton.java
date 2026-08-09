package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Custom button widget that uses a scaled-down Minecraft item icon as its graphic. Used for the
 * edit, delete, and move action buttons in the command list. The item is rendered at 75% of its
 * normal size (12×12 px) so the buttons stay compact. No background sprite is drawn, keeping the
 * icons visually lightweight.
 */
public class ItemIconButton extends Button {

  /** Scale factor applied to the 16×16 item icon — produces a 12×12 visible icon. */
  private static final float ICON_SCALE = 0.75f;
  private static final int ICON_SIZE = Math.round(16 * ICON_SCALE); // 12

  private final ItemStack iconItem;

  public ItemIconButton(int x, int y, int width, int height, ItemStack icon, OnPress onPress) {
    super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
    this.iconItem = icon;
  }

  public ItemIconButton(int x, int y, int width, int height, ItemStack icon, Component tooltip,
      OnPress onPress) {
    this(x, y, width, height, icon, onPress);
    this.setTooltip(Tooltip.create(tooltip));
  }

  @Override
  public void onPress(net.minecraft.client.input.InputWithModifiers input) {
    this.onPress.onPress(this);
  }

  @Override
  protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    int iconX = this.getX() + (this.getWidth() - ICON_SIZE) / 2;
    int iconY = this.getY() + (this.getHeight() - ICON_SIZE) / 2;
    var pose = guiGraphics.pose();
    pose.pushMatrix();
    pose.translate(iconX, iconY);
    pose.scale(ICON_SCALE, ICON_SCALE);
    guiGraphics.item(iconItem, 0, 0);
    pose.popMatrix();
  }
}

package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Settings button widget that uses a custom gear icon sprite instead of text; clicking it opens
 * {@link SettingsScreen}.
 */
public class SettingsButton extends Button {

  /**
   * Sprite ID — maps to {@code assets/command-gui/textures/gui/sprites/settings.png}, registered
   * automatically in the GUI atlas (no explicit atlas JSON needed for the gui/sprites/ directory).
   */
  private static final Identifier SETTINGS_ICON = Identifier.parse("command-gui:settings");

  public SettingsButton(int x, int y, int width, int height, OnPress onPress) {
    super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
    this.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.settings.title")));
  }

  @Override
  protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    int iconSize = 16;
    int iconX = this.getX() + (this.getWidth() - iconSize) / 2;
    int iconY = this.getY() + (this.getHeight() - iconSize) / 2;
    guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, SETTINGS_ICON,
        iconX, iconY, iconSize, iconSize);
  }
}

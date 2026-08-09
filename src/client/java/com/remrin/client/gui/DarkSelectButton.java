package com.remrin.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Button that renders with the dark (disabled-looking) button texture while "selected",
 * matching the visual language of masa-style mod UIs (tweakeroo / minihud) where a chosen
 * option turns black. The button stays fully clickable — the selection only affects rendering.
 * <p>
 * The dark texture is drawn in {@link #extractContents} (which runs after the default
 * background sprite), so the normal hover / disabled background still applies underneath and
 * the label is re-drawn in the configured selection color.
 */
public class DarkSelectButton extends Button {

  private static final Identifier DARK_SPRITE =
      Identifier.parse("minecraft:widget/button_disabled");
  private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;

  private Supplier<Boolean> selected = () -> false;
  private int selectedTextColor = DEFAULT_TEXT_COLOR;
  /** Optional colour for the unselected label (defaults to white / grey-when-disabled). */
  private int unselectedTextColor = -1;

  public DarkSelectButton(int x, int y, int width, int height, Component message,
      OnPress onPress) {
    super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
  }

  /**
   * Makes the button render dark while the supplier returns true, with the label in the given
   * color (e.g. amber for pending selections).
   */
  public void setDarkSelected(Supplier<Boolean> selected, int selectedTextColor) {
    this.selected = selected;
    this.selectedTextColor = selectedTextColor;
  }

  /**
   * Sets the label colour used while NOT selected (e.g. a status colour for detection states).
   * Pass -1 to fall back to the default white label.
   */
  public void setUnselectedTextColor(int color) {
    this.unselectedTextColor = color;
  }

  @Override
  protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    var font = Minecraft.getInstance().font;
    int textY = this.getY() + (this.getHeight() - 8) / 2;
    if (selected.get()) {
      // Dark (disabled-looking) background + label in the selection color
      guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, DARK_SPRITE,
          this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0xFFFFFFFF);
      guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, textY,
          selectedTextColor);
    } else {
      // Default background (normal / hover / disabled per active state) + default label
      this.extractDefaultSprite(guiGraphics);
      int color;
      if (unselectedTextColor >= 0) {
        color = unselectedTextColor;
      } else {
        color = this.active ? DEFAULT_TEXT_COLOR : 0xFFA0A0A0;
      }
      guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, textY,
          color);
    }
  }
}

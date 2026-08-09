package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Button that never takes keyboard focus after being clicked.
 * <p>
 * Vanilla's {@code ContainerEventHandler.mouseClicked} calls
 * {@code shouldTakeFocusAfterInteraction()} after a successful click and refocuses the widget when
 * it returns true (the default). For insert buttons that hand focus back to a text field inside
 * {@code onClick}, that refocus would steal the field's focus again — and the next Space press
 * would re-trigger the button. Returning {@code false} here keeps the focus where {@code onClick}
 * left it.
 */
public class PassiveButton extends Button {

  public PassiveButton(int x, int y, int width, int height, Component message,
      OnPress onPress) {
    super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
  }

  @Override
  public boolean shouldTakeFocusAfterInteraction() {
    return false;
  }

  @Override
  protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    this.extractDefaultSprite(guiGraphics);
    var font = Minecraft.getInstance().font;
    int color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
    guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2,
        this.getY() + (this.getHeight() - 8) / 2, color);
  }
}

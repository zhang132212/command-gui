package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Text input screen used to fill in the {@code {name}} placeholder in a command. Provides a single
 * text field confirmed with Enter; the result is passed via the {@link #onInputConfirmed(String)}
 * callback.
 */
public class TextInputScreen extends BaseParentedScreen<Screen> {

  private final String commandTemplate;
  private final String placeholder;
  private EditBox inputField;

  public TextInputScreen(Screen parent, Component title, String commandTemplate,
      String placeholder) {
    super(title, parent);
    this.commandTemplate = commandTemplate;
    this.placeholder = placeholder;
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    inputField = new EditBox(this.font, centerX - 100, centerY - 10, 200, 20,
        Component.literal(placeholder));
    inputField.setMaxLength(50);
    inputField.setHint(Component.literal(placeholder));
    this.addRenderableWidget(inputField);
    this.setInitialFocus(inputField);
  }

  protected void onInputConfirmed(String input) {
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      String value = inputField.getValue().trim();
      if (!value.isEmpty()) {
        onInputConfirmed(value);
        if (commandTemplate != null) {
          String command = commandTemplate.replace("{name}", value);
          executeCommand(command);
        }
      }
      return true;
    }

    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }

    return super.keyPressed(keyEvent);
  }

  private void executeCommand(String command) {
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.player != null) {
      mc.gui.setScreen(null);
      ChainedCommandExecutor.sendCommand(command);
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    guiGraphics.centeredText(this.font, this.title, centerX, centerY - 40, 0xFFFFFFFF);
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.enter_to_confirm"),
        centerX, centerY + 20, 0xFF888888);
  }
}

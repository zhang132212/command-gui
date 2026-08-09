package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Time value input screen providing a text input field and quick-time buttons.
 * <p>
 * Supported input formats: {@code <number>t} (ticks), {@code <number>s} (seconds),
 * {@code <number>d} (days). If no unit is specified, {@code t} (ticks) is appended by default.
 */
public class TimeInputScreen extends BaseParentedScreen<Screen> {

  private static final int BUTTON_WIDTH = 60;
  private static final int BUTTON_HEIGHT = 20;
  private static final int BUTTONS_PER_ROW = 5;
  private static final String[] DEFAULT_QUICK_VALUES = {"1t", "1s", "5s", "10s", "30s", "1d"};

  private final String commandTemplate;
  private final String[] quickValues;

  private EditBox inputField;

  public TimeInputScreen(Screen parent, Component title, String commandTemplate,
      String[] quickValues) {
    super(title, parent);
    this.commandTemplate = commandTemplate;
    this.quickValues =
        quickValues != null && quickValues.length > 0 ? quickValues : DEFAULT_QUICK_VALUES;
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    inputField = new EditBox(this.font, centerX - 50, centerY - 40, 100, 20,
        Component.literal(""));
    inputField.setMaxLength(20);
    inputField.setHint(Component.literal("1s, 20t, 0.5d"));
    this.addRenderableWidget(inputField);
    this.setInitialFocus(inputField);

    int rows = (quickValues.length + BUTTONS_PER_ROW - 1) / BUTTONS_PER_ROW;
    int totalWidth = Math.min(quickValues.length, BUTTONS_PER_ROW) * (BUTTON_WIDTH + 4) - 4;
    int startX = centerX - totalWidth / 2;
    int startY = centerY - 5;

    // Clamp visible rows so buttons don't push the close button off-screen.
    // Reserve 34px: 10px gap + 20px close button + 4px bottom margin.
    int availableForRows = this.height - startY - 34;
    int maxRows = Math.max(1, availableForRows / (BUTTON_HEIGHT + 4));
    int visibleRows = Math.min(rows, maxRows);
    int visibleCount = Math.min(quickValues.length, visibleRows * BUTTONS_PER_ROW);

    for (int i = 0; i < visibleCount; i++) {
      String value = quickValues[i];
      int col = i % BUTTONS_PER_ROW;
      int row = i / BUTTONS_PER_ROW;
      int x = startX + col * (BUTTON_WIDTH + 4);
      int y = startY + row * (BUTTON_HEIGHT + 4);

      this.addRenderableWidget(Button.builder(
          Component.literal(value),
          btn -> executeWithValue(value)
      ).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    int closeBtnY = Math.min(centerY + 10 + visibleRows * (BUTTON_HEIGHT + 4),
        this.height - BUTTON_HEIGHT - 4);
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(centerX - 50, closeBtnY, 100, 20).build());
  }

  protected void onTimeConfirmed(String time) {
  }

  private void executeWithValue(String value) {
    onTimeConfirmed(value);
    if (commandTemplate != null) {
      String command = commandTemplate.replace("{time}", value);
      executeCommand(command);
    }
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      String text = inputField.getValue().trim();
      if (!text.isEmpty()) {
        if (!text.matches(".*[dst]$")) {
          text = text + "t";
        }
        executeWithValue(text);
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

    guiGraphics.centeredText(this.font, this.title, centerX, centerY - 70, 0xFFFFFFFF);
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.time_hint"),
        centerX, centerY - 55, 0xFF888888);
  }
}

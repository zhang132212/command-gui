package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Number input screen providing a manual input field and quick-value buttons.
 * <p>
 * If {@code minValue}/{@code maxValue} are configured, the manual input is constrained to that
 * range; {@code quickValues} is a preset array of shortcut values — if not provided, one is
 * auto-generated from the range.
 */
public class NumberInputScreen extends BaseParentedScreen<Screen> {

  private static final int BUTTON_WIDTH = 50;
  private static final int BUTTON_HEIGHT = 20;
  private static final int BUTTONS_PER_ROW = 5;

  private final String commandTemplate;
  private final int minValue;
  private final int maxValue;
  private final int[] quickValues;

  private EditBox inputField;

  public NumberInputScreen(Screen parent, Component title, String commandTemplate, Integer minValue,
      Integer maxValue, int[] quickValues) {
    super(title, parent);
    this.commandTemplate = commandTemplate;
    this.minValue = minValue != null ? minValue : 0;
    this.maxValue = maxValue != null ? maxValue : Integer.MAX_VALUE;
    this.quickValues =
        quickValues != null ? quickValues : generateQuickValues(this.minValue, this.maxValue);
  }

  /**
   * Auto-generates a quick-value array based on the numeric range. Small ranges (≤100) are sampled
   * evenly; large ranges use a preset set of common values (1, 5, 10, 100, etc.).
   */
  private static int[] generateQuickValues(int min, int max) {
    List<Integer> values = new ArrayList<>();
    if (max <= 100) {
      for (int i = min; i <= max; i += Math.max(1, (max - min) / 10)) {
        values.add(i);
      }
      if (!values.contains(max)) {
        values.add(max);
      }
    } else {
      int[] common = {1, 5, 10, 20, 50, 100, 200, 500, 1000, 5000, 10000, 72000};
      for (int v : common) {
        if (v >= min && v <= max) {
          values.add(v);
        }
      }
    }
    return values.stream().mapToInt(i -> i).toArray();
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    inputField = new EditBox(this.font, centerX - 50, centerY - 40, 100, 20,
        Component.literal(""));
    inputField.setMaxLength(10);
    inputField.setHint(Component.literal(minValue + " - " + maxValue));
    this.addRenderableWidget(inputField);
    this.setInitialFocus(inputField);

    int rows = (quickValues.length + BUTTONS_PER_ROW - 1) / BUTTONS_PER_ROW;
    int totalWidth = Math.min(quickValues.length, BUTTONS_PER_ROW) * (BUTTON_WIDTH + 4) - 4;
    int startX = centerX - totalWidth / 2;
    int startY = centerY - 5;

    // Clamp the number of visible quick-value rows so they don't push the close button off-screen.
    // Reserve 34px below the last row: 10px gap + 20px close button + 4px bottom margin.
    int availableForRows = this.height - startY - 34;
    int maxRows = Math.max(1, availableForRows / (BUTTON_HEIGHT + 4));
    int visibleRows = Math.min(rows, maxRows);
    int visibleCount = Math.min(quickValues.length, visibleRows * BUTTONS_PER_ROW);

    for (int i = 0; i < visibleCount; i++) {
      int value = quickValues[i];
      int col = i % BUTTONS_PER_ROW;
      int row = i / BUTTONS_PER_ROW;
      int x = startX + col * (BUTTON_WIDTH + 4);
      int y = startY + row * (BUTTON_HEIGHT + 4);

      this.addRenderableWidget(Button.builder(
          Component.literal(String.valueOf(value)),
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

  protected void onNumberConfirmed(String number) {
  }

  private void executeWithValue(int value) {
    onNumberConfirmed(String.valueOf(value));
    if (commandTemplate != null) {
      String command = commandTemplate.replace("{number}", String.valueOf(value));
      executeCommand(command);
    }
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      String text = inputField.getValue().trim();
      if (!text.isEmpty()) {
        try {
          int value = Integer.parseInt(text);
          if (value >= minValue && value <= maxValue) {
            executeWithValue(value);
          }
        } catch (NumberFormatException ignored) {
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

    guiGraphics.centeredText(this.font, this.title, centerX, centerY - 70, 0xFFFFFFFF);
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.number_range", minValue, maxValue),
        centerX, centerY - 55, 0xFF888888);
  }
}
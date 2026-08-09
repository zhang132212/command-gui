package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Screen for adding a command category. After entering a category name, it is automatically
 * converted to a valid category ID (lowercase letters, digits, and underscores) and a corresponding
 * translation key is generated.
 */
public class AddCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {

  private EditBox nameField;

  public AddCategoryScreen(CommandGUIScreen parent) {
    super(Component.translatable("screen.command-gui.add_category_title"), parent);
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    // Name input
    nameField = new EditBox(this.font, centerX - 100, centerY - 20, 200, 20,
        Component.translatable("screen.command-gui.category_name_hint"));
    nameField.setHint(Component.translatable("screen.command-gui.category_name_hint"));
    nameField.setMaxLength(50);
    this.addRenderableWidget(nameField);
    this.setInitialFocus(nameField);

    // Save button
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        button -> saveCategory()
    ).bounds(centerX - 102, centerY + 15, 100, 20).build());

    // Cancel button
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.cancel"),
        button -> this.minecraft.gui.setScreen(parent)
    ).bounds(centerX + 2, centerY + 15, 100, 20).build());
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int centerX = this.width / 2;
    int centerY = this.height / 2;

    // Title
    guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, 0xFFFFFF);

    // Description
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.add_category_desc"),
        centerX, centerY - 40, 0xFF888888);

    // Label
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.category_name"),
        centerX - 100, centerY - 32, 0xFFAAAAAA);

    // Hint
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.enter_to_save"),
        centerX, centerY + 45, 0xFF888888);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      saveCategory();
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  private void saveCategory() {
    String name = nameField.getValue().trim();
    if (name.isEmpty()) {
      return;
    }

    // Convert the display name to a valid ID: lowercase, spaces to underscores, remove illegal characters
    String id = name.toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
    if (id.isEmpty()) {
      // Fall back to a timestamp-based unique ID if all characters are invalid
      id = "category_" + System.currentTimeMillis();
    }

    // Create translation key
    String nameKey = "screen.command-gui.custom." + id;

    CommandConfig.addCategory(id, nameKey, name);

    if (parent != null) {
      parent.refresh();
    }
    this.minecraft.gui.setScreen(parent);
  }
}

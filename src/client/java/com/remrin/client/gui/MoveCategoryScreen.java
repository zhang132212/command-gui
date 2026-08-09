package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Screen for moving a command to a different category, displaying all available categories for
 * selection. The button for the command's current category is shown as disabled to prevent moving
 * it to the same category.
 */
public class MoveCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {

  private static final int PADDING = 10;
  private static final int BTN_WIDTH = 100;
  private static final int BTN_HEIGHT = 20;
  private static final int BTN_GAP = 4;
  private final String commandName;

  public MoveCategoryScreen(CommandGUIScreen parent, String commandName) {
    super(Component.translatable("screen.command-gui.move_category_title"), parent);
    this.commandName = commandName;
  }

  @Override
  protected void init() {
    super.init();

    List<CommandConfig.Category> categories = CommandConfig.getCategories();
    String currentCategoryId = CommandConfig.findCommandCategory(commandName);

    int availableWidth = this.width - 2 * PADDING;
    int cols = Math.max(1, (availableWidth + BTN_GAP) / (BTN_WIDTH + BTN_GAP));
    int totalRowWidth = cols * BTN_WIDTH + (cols - 1) * BTN_GAP;
    int startX = (this.width - totalRowWidth) / 2;
    int startY = 30;

    int col = 0;
    int row = 0;
    for (CommandConfig.Category cat : categories) {
      Component btnText = cat.getDisplayName() != null
          ? Component.literal(cat.getDisplayName())
          : Component.translatable(cat.nameKey);

      int btnX = startX + col * (BTN_WIDTH + BTN_GAP);
      int btnY = startY + row * (BTN_HEIGHT + BTN_GAP);

      final String targetCategoryId = cat.id;
      boolean isCurrent = cat.id.equals(currentCategoryId);

      Button catBtn = Button.builder(btnText, btn -> {
        CommandConfig.moveCommand(commandName, targetCategoryId);
        parent.refresh();
        this.minecraft.gui.setScreen(parent);
      }).bounds(btnX, btnY, BTN_WIDTH, BTN_HEIGHT).build();
      catBtn.active = !isCurrent;
      this.addRenderableWidget(catBtn);

      col++;
      if (col >= cols) {
        col = 0;
        row++;
      }
    }

    // Cancel button below the grid
    int cancelY = startY + (row + (col > 0 ? 1 : 0)) * (BTN_HEIGHT + BTN_GAP) + 10;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.cancel"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(this.width / 2 - 50, cancelY, 100, 20).build());
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }
    return super.keyPressed(keyEvent);
  }
}


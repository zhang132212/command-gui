package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Screen for adding a custom command, extending {@link BaseCommandEditorScreen}. If multiple
 * categories exist, an additional category selection button is shown at the bottom, allowing the
 * new command to be saved to a specific category.
 */
public class AddCommandScreen extends BaseCommandEditorScreen {

  private final String initialCategoryId;
  private List<CommandConfig.Category> categories;
  private int selectedCategoryIndex = 0;
  private Button categoryButton;
  private int savedCategoryIndex;

  public AddCommandScreen(CommandGUIScreen parent) {
    this(parent, null);
  }

  public AddCommandScreen(CommandGUIScreen parent, String initialCategoryId) {
    super(Component.translatable("screen.command-gui.add_title"), parent);
    this.initialCategoryId = initialCategoryId;
  }

  @Override
  protected String getInitialName() {
    return "";
  }

  @Override
  protected String getInitialDescription() {
    return "";
  }

  @Override
  protected String getInitialCommand() {
    return "";
  }

  @Override
  protected boolean showNameHint() {
    return true;
  }

  @Override
  protected void init() {
    categories = CommandConfig.getCategories();
    if (initialCategoryId != null) {
      for (int i = 0; i < categories.size(); i++) {
        if (categories.get(i).id.equals(initialCategoryId)) {
          selectedCategoryIndex = i;
          break;
        }
      }
    }
    super.init();
  }

  @Override
  protected int getNameFieldWidth(int leftWidth) {
    return categories.size() > 1 ? leftWidth - leftWidth / 3 - BTN_GAP : leftWidth;
  }

  @Override
  protected int initExtraRow(int fieldX, int currentY) {
    if (categories.size() > 1) {
      int leftWidth = effectiveLeftColWidth();
      int catBtnWidth = leftWidth / 3;
      int nameFieldWidth = getNameFieldWidth(leftWidth);
      categoryButton = Button.builder(getCategoryDisplayName(), btn -> cycleCategory())
          .bounds(fieldX + nameFieldWidth + BTN_GAP, nameField.getY(), catBtnWidth, INPUT_HEIGHT)
          .build();
      this.addRenderableWidget(categoryButton);
    }
    return currentY;  // inline — no extra row, description goes at currentY
  }

  @Override
  protected int renderExtraLabel(GuiGraphicsExtractor guiGraphics, int fieldX, int currentY) {
    return currentY;  // category is shown inline in the button; no separate label needed
  }

  @Override
  protected void onBeforeResize() {
    savedCategoryIndex = selectedCategoryIndex;
  }

  @Override
  protected void onAfterResize() {
    selectedCategoryIndex = savedCategoryIndex;
    if (categoryButton != null) {
      categoryButton.setMessage(getCategoryDisplayName());
    }
  }

  /**
   * Cycles through the available categories and updates the category button label
   */
  private void cycleCategory() {
    selectedCategoryIndex = (selectedCategoryIndex + 1) % categories.size();
    categoryButton.setMessage(getCategoryDisplayName());
  }

  private Component getCategoryDisplayName() {
    Component catName;
    if (categories.isEmpty()) {
      catName = Component.translatable("screen.command-gui.category.default");
    } else {
      CommandConfig.Category cat = categories.get(selectedCategoryIndex);
      catName = cat.getDisplayName() != null
          ? Component.literal(cat.getDisplayName())
          : Component.translatable(cat.nameKey);
    }
    return Component.translatable("screen.command-gui.save_to_category", catName);
  }

  @Override
  protected void performSave() {
    String name = nameField.getValue().trim();
    String description = descriptionField.getValue().trim();
    String categoryId = categories.isEmpty() ? "default" : categories.get(selectedCategoryIndex).id;
    List<String> commands = getAllCommands();
    if (commands.size() > 1) {
      CommandConfig.addCommandMulti(categoryId, name, commands, description);
    } else if (!commands.isEmpty()) {
      CommandConfig.addCommand(categoryId, name, commands.get(0), description);
    }
  }

}

package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class SelectCategoryScreen extends BaseParentedScreen<Screen> {
   private static final int PADDING = 10;
   private static final int BTN_WIDTH = 100;
   private static final int BTN_HEIGHT = 20;
   private static final int BTN_GAP = 4;
   private final String currentCategoryId;
   private final Consumer<String> onSelected;
   private String selectedCategoryId;
   private boolean selectionChanged = false;

   public SelectCategoryScreen(Screen parent, String currentCategoryId, Consumer<String> onSelected) {
      super(Component.translatable("screen.command-gui.select_category_title"), parent);
      this.currentCategoryId = currentCategoryId;
      this.selectedCategoryId = currentCategoryId;
      this.onSelected = onSelected;
   }

   protected void init() {
      super.init();
      List<CommandConfig.Category> categories = CommandConfig.getCategories();
      int availableWidth = this.width - 20;
      int cols = Math.max(1, (availableWidth + 4) / 104);
      int totalRowWidth = cols * 100 + (cols - 1) * 4;
      int startX = (this.width - totalRowWidth) / 2;
      int startY = 30;
      int col = 0;
      int row = 0;

      for (CommandConfig.Category cat : categories) {
         Component btnText = cat.getDisplayName() != null ? Component.literal(cat.getDisplayName()) : Component.translatable(cat.nameKey);
         String catId = cat.id;
         int btnX = startX + col * 104;
         int btnY = startY + row * 24;
         DarkSelectButton catBtn = new DarkSelectButton(btnX, btnY, 100, 20, btnText, btn -> this.select(catId));
         catBtn.setDarkSelected(() -> catId.equals(this.selectedCategoryId), -1);
         this.addRenderableWidget(catBtn);
         if (++col >= cols) {
            col = 0;
            row++;
         }
      }

      int buttonRowY = startY + (row + (col > 0 ? 1 : 0)) * 24 + 10;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.confirm"), btn -> this.confirm()).bounds(this.width / 2 - 104, buttonRowY, 100, 20).build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.cancel"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(this.width / 2 + 4, buttonRowY, 100, 20)
            .build()
      );
   }

   private void select(String categoryId) {
      if (!categoryId.equals(this.selectedCategoryId)) {
         this.selectedCategoryId = categoryId;
         this.selectionChanged = true;
      }
   }

   private void confirm() {
      if (this.selectionChanged && this.selectedCategoryId != null && this.onSelected != null) {
         this.onSelected.accept(this.selectedCategoryId);
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else if (keyEvent.key() != 257 && keyEvent.key() != 335) {
         return super.keyPressed(keyEvent);
      } else {
         this.confirm();
         return true;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, -1);
   }
}

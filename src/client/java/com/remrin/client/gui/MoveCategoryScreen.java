package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class MoveCategoryScreen extends BaseParentedScreen<Screen> {
   private static final int PADDING = 10;
   private static final int BTN_WIDTH = 100;
   private static final int BTN_HEIGHT = 20;
   private static final int BTN_GAP = 4;
   private final String commandName;
   private final String sourceCategoryId;
   private final Consumer<String> onMoved;
   private String errorMessage = "";

   public MoveCategoryScreen(CommandGUIScreen parent, String commandName) {
      this(parent, commandName, category -> parent.refresh());
   }

   public MoveCategoryScreen(Screen parent, String commandName, Consumer<String> onMoved) {
      this(parent, CommandConfig.findCommandCategory(commandName), commandName, onMoved);
   }

   public MoveCategoryScreen(Screen parent, String sourceCategoryId, String commandName, Consumer<String> onMoved) {
      super(Component.translatable("screen.command-gui.move_category_title"), parent);
      this.sourceCategoryId = sourceCategoryId;
      this.commandName = commandName;
      this.onMoved = onMoved;
   }

   protected void init() {
      super.init();
      List<CommandConfig.Category> categories = CommandConfig.getCategories();
      String currentCategoryId = this.sourceCategoryId;
      int availableWidth = this.width - 20;
      int cols = Math.max(1, (availableWidth + 4) / 104);
      int totalRowWidth = cols * 100 + (cols - 1) * 4;
      int startX = (this.width - totalRowWidth) / 2;
      int startY = 30;
      int col = 0;
      int row = 0;

      for (CommandConfig.Category cat : categories) {
         Component btnText = cat.getDisplayName() != null ? Component.literal(cat.getDisplayName()) : Component.translatable(cat.nameKey);
         int btnX = startX + col * 104;
         int btnY = startY + row * 24;
         String targetCategoryId = cat.id;
         boolean isCurrent = cat.id.equals(currentCategoryId);
         Button catBtn = GuiButton.themed(btnText, btn -> {
            String error = CommandConfig.moveCommand(this.sourceCategoryId, this.commandName, targetCategoryId);
            if (error != null) {
               this.errorMessage = error;
               return;
            }
            if (this.onMoved != null) {
               this.onMoved.accept(targetCategoryId);
            }

            this.minecraft.gui.setScreen(this.parent);
         }).bounds(btnX, btnY, 100, 20).build();
         catBtn.active = !isCurrent;
         this.addRenderableWidget(catBtn);
         if (++col >= cols) {
            col = 0;
            row++;
         }
      }

      int cancelY = startY + (row + (col > 0 ? 1 : 0)) * 24 + 10;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.cancel"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(this.width / 2 - 50, cancelY, 100, 20)
            .build()
      );
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, -1);
      if (!this.errorMessage.isEmpty()) {
         guiGraphics.centeredText(this.font, this.font.plainSubstrByWidth(this.errorMessage, this.width - 20),
            this.width / 2, this.height - 12, GuiTheme.danger());
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }
}

package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class AddCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {
   private EditBox nameField;

   public AddCategoryScreen(CommandGUIScreen parent) {
      super(Component.translatable("screen.command-gui.add_category_title"), parent);
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.nameField = new EditBox(this.font, centerX - 100, centerY - 20, 200, 20, Component.translatable("screen.command-gui.category_name_hint"));
      this.nameField.setHint(Component.translatable("screen.command-gui.category_name_hint"));
      this.nameField.setMaxLength(50);
      this.addRenderableWidget(this.nameField);
      this.setInitialFocus(this.nameField);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), button -> this.saveCategory()).bounds(centerX - 102, centerY + 15, 100, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.cancel"), button -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX + 2, centerY + 15, 100, 20)
            .build()
      );
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, 16777215);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.add_category_desc"), centerX, centerY - 40, -7829368);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.category_name"), centerX - 100, centerY - 32, -5592406);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.enter_to_save"), centerX, centerY + 45, -7829368);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else if (keyCode != 257 && keyCode != 335) {
         return super.keyPressed(keyEvent);
      } else {
         this.saveCategory();
         return true;
      }
   }

   private void saveCategory() {
      String name = this.nameField.getValue().trim();
      if (!name.isEmpty()) {
         String id = name.toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
         if (id.isEmpty()) {
            id = "category_" + System.currentTimeMillis();
         }

         String nameKey = "screen.command-gui.custom." + id;
         CommandConfig.addCategory(id, nameKey, name);
         if (this.parent != null) {
            this.parent.refresh();
         }

         this.minecraft.gui.setScreen(this.parent);
      }
   }
}

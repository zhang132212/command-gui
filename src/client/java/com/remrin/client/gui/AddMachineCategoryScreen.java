package com.remrin.client.gui;

import com.remrin.client.config.SettingsConfig;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class AddMachineCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {
   private EditBox nameField;

   public AddMachineCategoryScreen(CommandGUIScreen parent) {
      super(Component.translatable("screen.command-gui.machine.add_category_title"), parent);
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.nameField = new GuiEditBox(this.font, centerX - 100, centerY - 20, 200, 20, Component.translatable("screen.command-gui.machine.add_category_name"));
      this.nameField.setHint(Component.translatable("screen.command-gui.machine.add_category_name"));
      this.nameField.setMaxLength(30);
      this.addRenderableWidget(this.nameField);
      this.setInitialFocus(this.nameField);
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.save"), button -> this.saveAndOpen()).bounds(centerX - 102, centerY + 15, 100, 20).build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.cancel"), button -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX + 2, centerY + 15, 100, 20)
            .build()
      );
   }

   private void saveAndOpen() {
      String name = this.nameField.getValue().trim();
      if (!name.isEmpty()) {
         List<String> categories = SettingsConfig.getStringList("machine_categories");
         if (!categories.contains(name)) {
            categories.add(name);
            SettingsConfig.setStringList("machine_categories", categories);
            SettingsConfig.save();
         }

         this.parent.refresh();
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else if (keyEvent.key() != 257 && keyEvent.key() != 335) {
         return super.keyPressed(keyEvent);
      } else {
         this.saveAndOpen();
         return true;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.add_category_name"), centerX - 100, centerY - 32, -5592406);
   }
}

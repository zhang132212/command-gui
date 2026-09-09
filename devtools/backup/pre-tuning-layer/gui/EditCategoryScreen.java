package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class EditCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {
   private final CommandConfig.Category category;
   private EditBox nameField;
   private String errorText = null;

   public EditCategoryScreen(CommandGUIScreen parent, CommandConfig.Category category) {
      super(Component.translatable("screen.command-gui.edit_category_title"), parent);
      this.category = category;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.nameField = new EditBox(this.font, centerX - 100, centerY - 20, 200, 20, Component.translatable("screen.command-gui.category_name_hint"));
      this.nameField.setMaxLength(50);
      this.nameField.setValue(this.initialName());
      this.nameField.setHint(Component.translatable("screen.command-gui.category_name_hint"));
      this.nameField.setResponder(s -> this.errorText = null);
      this.addRenderableWidget(this.nameField);
      this.setInitialFocus(this.nameField);
      int startX = centerX - 154;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), button -> this.saveRename()).bounds(startX, centerY + 15, 100, 20).build()
      );
      boolean hasCommands = this.category != null && !this.category.commands.isEmpty();
      Button deleteButton = Button.builder(Component.translatable("screen.command-gui.delete_category"), button -> this.confirmDelete())
         .bounds(startX + 104, centerY + 15, 100, 20)
         .build();
      deleteButton.active = !hasCommands;
      deleteButton.setTooltip(
         Tooltip.create(Component.translatable(hasCommands ? "screen.command-gui.category_not_empty" : "screen.command-gui.delete_category_confirm_hint"))
      );
      this.addRenderableWidget(deleteButton);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.cancel"), button -> this.minecraft.gui.setScreen(this.parent))
            .bounds(startX + 208, centerY + 15, 100, 20)
            .build()
      );
   }

   private String initialName() {
      if (this.category == null) {
         return "";
      } else {
         if (this.category.getDisplayName() != null) {
            return this.category.getDisplayName();
         }
         return Component.translatable(this.category.nameKey).getString();
      }
   }

   private void saveRename() {
      String name = this.nameField.getValue().trim();
      if (!name.isEmpty() && this.category != null) {
         if (name.equals(this.initialName())) {
            this.minecraft.gui.setScreen(this.parent);
         } else {
            for (CommandConfig.Category other : CommandConfig.getCategories()) {
               if (!other.id.equals(this.category.id)) {
                  String otherName = other.getDisplayName() != null ? other.getDisplayName() : Component.translatable(other.nameKey).getString();
                  if (otherName.equals(name)) {
                     this.errorText = Component.translatable("screen.command-gui.category_name_duplicate").getString();
                     return;
                  }
               }
            }

            CommandConfig.renameCategory(this.category.id, name);
            if (this.parent != null) {
               this.parent.refresh();
            }

            this.minecraft.gui.setScreen(this.parent);
         }
      }
   }

   private void confirmDelete() {
      if (this.category != null && this.category.commands.isEmpty()) {
         this.minecraft
            .gui
            .setScreen(
               new ConfirmScreen(
                  this,
                  Component.translatable("screen.command-gui.delete_category_confirm_title"),
                  Component.translatable("screen.command-gui.delete_category"),
                  Component.translatable("screen.command-gui.cancel"),
                  () -> {
                     CommandConfig.removeCategory(this.category.id);
                     if (this.parent != null) {
                        this.parent.refresh();
                     }

                     this.minecraft.gui.setScreen(this.parent);
                  },
                  Component.translatable("screen.command-gui.delete_category_confirm_message", new Object[]{this.initialName()})
               )
            );
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else if (keyEvent.key() != 257 && keyEvent.key() != 335) {
         return super.keyPressed(keyEvent);
      } else {
         this.saveRename();
         return true;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.category_name"), centerX - 100, centerY - 32, -5592406);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.enter_to_save"), centerX, centerY + 45, -7829368);
      if (this.errorText != null) {
         guiGraphics.centeredText(this.font, Component.literal(this.errorText), centerX, centerY + 58, -43691);
      }
   }
}

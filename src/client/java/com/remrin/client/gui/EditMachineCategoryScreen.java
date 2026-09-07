package com.remrin.client.gui;

import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class EditMachineCategoryScreen extends BaseParentedScreen<CommandGUIScreen> {
   private final String category;
   private EditBox nameField;
   private String errorText = null;

   public EditMachineCategoryScreen(CommandGUIScreen parent, String category) {
      super(Component.translatable("screen.command-gui.machine.edit_category_title"), parent);
      this.category = category;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.nameField = new EditBox(this.font, centerX - 100, centerY - 20, 200, 20, Component.translatable("screen.command-gui.machine.add_category_name"));
      this.nameField.setMaxLength(30);
      this.nameField.setValue(this.category != null ? this.category : "");
      this.nameField.setResponder(s -> this.errorText = null);
      this.addRenderableWidget(this.nameField);
      this.setInitialFocus(this.nameField);
      int startX = centerX - 154;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), button -> this.saveRename()).bounds(startX, centerY + 15, 100, 20).build()
      );
      boolean canDelete = MachineNetworkManager.canConfig();
      Button deleteButton = Button.builder(Component.translatable("screen.command-gui.delete_category"), button -> this.confirmDelete())
         .bounds(startX + 104, centerY + 15, 100, 20)
         .build();
      deleteButton.active = canDelete;
      deleteButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.delete_category_confirm_hint")));
      this.addRenderableWidget(deleteButton);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.cancel"), button -> this.minecraft.gui.setScreen(this.parent))
            .bounds(startX + 208, centerY + 15, 100, 20)
            .build()
      );
   }

   private void saveRename() {
      String newName = this.nameField.getValue().trim();
      if (!newName.isEmpty() && this.category != null && !this.category.equals(newName)) {
         Set<String> existing = new LinkedHashSet<>();

         for (MachineModels.MachineData machine : MachineNetworkManager.getMachines()) {
            String cat = machine.category == null ? "" : machine.category.trim();
            if (!cat.isEmpty() && !cat.equals(this.category)) {
               existing.add(cat);
            }
         }

         for (String cat : SettingsConfig.getStringList("machine_categories")) {
            if (!cat.isBlank() && !cat.equals(this.category)) {
               existing.add(cat);
            }
         }

         if (existing.contains(newName)) {
            this.errorText = Component.translatable("screen.command-gui.machine.category_name_duplicate").getString();
         } else {
            List<String> saved = SettingsConfig.getStringList("machine_categories");
            if (saved.contains(this.category)) {
               saved.set(saved.indexOf(this.category), newName);
            } else if (!saved.contains(newName)) {
               saved.add(newName);
            }

            SettingsConfig.setStringList("machine_categories", saved);
            SettingsConfig.save();
            MachineNetworkManager.sendRenameCategory(this.category, newName);
            if (this.parent != null) {
               this.parent.refresh();
            }

            this.minecraft.gui.setScreen(this.parent);
         }
      }
   }

   private void confirmDelete() {
      if (this.category != null && !this.category.isEmpty()) {
         this.minecraft
            .gui
            .setScreen(
               new ConfirmScreen(
                  this,
                  Component.translatable("screen.command-gui.machine.delete_category_confirm_title"),
                  Component.translatable("screen.command-gui.delete_category"),
                  Component.translatable("screen.command-gui.cancel"),
                  () -> {
                     List<String> saved = SettingsConfig.getStringList("machine_categories");
                     if (saved.remove(this.category)) {
                        SettingsConfig.setStringList("machine_categories", saved);
                        SettingsConfig.save();
                     }

                     MachineNetworkManager.sendClearCategory(this.category);
                     if (this.parent != null) {
                        this.parent.refresh();
                     }

                     this.minecraft.gui.setScreen(this.parent);
                  },
                  Component.translatable("screen.command-gui.machine.delete_category_confirm_message", new Object[]{this.category})
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
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.add_category_name"), centerX - 100, centerY - 32, -5592406);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.enter_to_save"), centerX, centerY + 45, -7829368);
      if (this.errorText != null) {
         guiGraphics.centeredText(this.font, Component.literal(this.errorText), centerX, centerY + 58, -43691);
      }
   }
}

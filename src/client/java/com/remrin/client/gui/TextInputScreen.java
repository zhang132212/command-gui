package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class TextInputScreen extends BaseParentedScreen<Screen> {
   private final String commandTemplate;
   private final String placeholder;
   private EditBox inputField;

   public TextInputScreen(Screen parent, Component title, String commandTemplate, String placeholder) {
      super(title, parent);
      this.commandTemplate = commandTemplate;
      this.placeholder = placeholder;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.inputField = new GuiEditBox(this.font, centerX - 100, centerY - 10, 200, 20, Component.literal(this.placeholder));
      this.inputField.setMaxLength(50);
      this.inputField.setHint(Component.literal(this.placeholder));
      this.addRenderableWidget(this.inputField);
      this.setInitialFocus(this.inputField);
   }

   protected void onInputConfirmed(String input) {
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 257 || keyCode == 335) {
         String value = this.inputField.getValue().trim();
         if (!value.isEmpty()) {
            this.onInputConfirmed(value);
            if (this.commandTemplate != null) {
               String command = this.commandTemplate.replace("{name}", value);
               this.executeCommand(command);
            }
         }

         return true;
      } else if (keyCode == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   private void executeCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         mc.gui.setScreen(null);
         ChainedCommandExecutor.sendCommand(command);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 40, -1);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.enter_to_confirm"), centerX, centerY + 20, -7829368);
   }
}

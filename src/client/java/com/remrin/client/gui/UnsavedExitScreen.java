package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class UnsavedExitScreen extends BaseParentedScreen<Screen> {
   private final Runnable onSave;
   private final Runnable onDiscard;
   private final Component messageText;

   public UnsavedExitScreen(Screen parent, Component title, Component message, Runnable onSave, Runnable onDiscard) {
      super(title, parent);
      this.onSave = onSave;
      this.onDiscard = onDiscard;
      this.messageText = message;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      int btnW = 96;
      int gap = 6;
      int totalW = btnW * 3 + gap * 2;
      int startX = centerX - totalW / 2;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.save_exit"), btn -> this.saveAndExit()).bounds(startX, centerY + 30, btnW, 20).build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.discard"), btn -> this.discardAndExit())
            .bounds(startX + btnW + gap, centerY + 30, btnW, 20)
            .build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.cancel"), btn -> this.cancelExit())
            .bounds(startX + (btnW + gap) * 2, centerY + 30, btnW, 20)
            .build()
      );
   }

   private void saveAndExit() {
      if (this.onSave != null) {
         this.onSave.run();
      }
   }

   private void discardAndExit() {
      if (this.onDiscard != null) {
         this.onDiscard.run();
      }
   }

   private void cancelExit() {
      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 257 || keyEvent.key() == 335) {
         this.saveAndExit();
         return true;
      } else if (keyEvent.key() == 256) {
         this.cancelExit();
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, -1);
      guiGraphics.centeredText(this.font, this.messageText, centerX, centerY - 20, -5592406);
   }
}

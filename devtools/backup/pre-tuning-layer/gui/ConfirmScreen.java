package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class ConfirmScreen extends BaseParentedScreen<Screen> {
   private final Component confirmLabel;
   private final Component cancelLabel;
   private final Runnable onConfirm;
   private final Runnable onCancel;
   private final List<Component> messageLines = new ArrayList<>();

   public ConfirmScreen(Screen parent, Component title, Component confirmLabel, Component cancelLabel, Runnable onConfirm, Component... messageLines) {
      this(parent, title, confirmLabel, cancelLabel, onConfirm, null, messageLines);
   }

   public ConfirmScreen(
      Screen parent, Component title, Component confirmLabel, Component cancelLabel, Runnable onConfirm, Runnable onCancel, Component... messageLines
   ) {
      super(title, parent);
      this.confirmLabel = confirmLabel;
      this.cancelLabel = cancelLabel;
      this.onConfirm = onConfirm;
      this.onCancel = onCancel;

      for (Component line : messageLines) {
         if (line != null) {
            this.messageLines.add(line);
         }
      }
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      int btnW = 100;
      int gap = 8;
      int startX = centerX - btnW - gap / 2;
      this.addRenderableWidget(Button.builder(this.confirmLabel, btn -> this.confirm()).bounds(startX, centerY + 30, btnW, 20).build());
      this.addRenderableWidget(Button.builder(this.cancelLabel, btn -> this.cancel()).bounds(startX + btnW + gap, centerY + 30, btnW, 20).build());
   }

   private void confirm() {
      if (this.onConfirm != null) {
         this.onConfirm.run();
      }

      if (this.minecraft.gui.screen() == this) {
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   private void cancel() {
      if (this.onCancel != null) {
         this.onCancel.run();
      }

      if (this.minecraft.gui.screen() == this) {
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 257 || keyEvent.key() == 335) {
         this.confirm();
         return true;
      } else if (keyEvent.key() == 256) {
         this.cancel();
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      int lineStart = centerY - 30 - this.messageLines.size() * 5;
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 55, -1);

      for (int i = 0; i < this.messageLines.size(); i++) {
         guiGraphics.centeredText(this.font, this.messageLines.get(i), centerX, lineStart + i * 10, -5592406);
      }
   }
}

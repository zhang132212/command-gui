package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class TimeInputScreen extends BaseParentedScreen<Screen> {
   private static final int BUTTON_WIDTH = 60;
   private static final int BUTTON_HEIGHT = 20;
   private static final int BUTTONS_PER_ROW = 5;
   private static final String[] DEFAULT_QUICK_VALUES = new String[]{"1t", "1s", "5s", "10s", "30s", "1d"};
   private final String commandTemplate;
   private final String[] quickValues;
   private EditBox inputField;

   public TimeInputScreen(Screen parent, Component title, String commandTemplate, String[] quickValues) {
      super(title, parent);
      this.commandTemplate = commandTemplate;
      this.quickValues = quickValues != null && quickValues.length > 0 ? quickValues : DEFAULT_QUICK_VALUES;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.inputField = new EditBox(this.font, centerX - 50, centerY - 40, 100, 20, Component.literal(""));
      this.inputField.setMaxLength(20);
      this.inputField.setHint(Component.literal("1s, 20t, 0.5d"));
      this.addRenderableWidget(this.inputField);
      this.setInitialFocus(this.inputField);
      int rows = (this.quickValues.length + 5 - 1) / 5;
      int totalWidth = Math.min(this.quickValues.length, 5) * 64 - 4;
      int startX = centerX - totalWidth / 2;
      int startY = centerY - 5;
      int availableForRows = this.height - startY - 34;
      int maxRows = Math.max(1, availableForRows / 24);
      int visibleRows = Math.min(rows, maxRows);
      int visibleCount = Math.min(this.quickValues.length, visibleRows * 5);

      for (int i = 0; i < visibleCount; i++) {
         String value = this.quickValues[i];
         int col = i % 5;
         int row = i / 5;
         int x = startX + col * 64;
         int y = startY + row * 24;
         this.addRenderableWidget(Button.builder(Component.literal(value), btn -> this.executeWithValue(value)).bounds(x, y, 60, 20).build());
      }

      int closeBtnY = Math.min(centerY + 10 + visibleRows * 24, this.height - 20 - 4);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX - 50, closeBtnY, 100, 20)
            .build()
      );
   }

   protected void onTimeConfirmed(String time) {
   }

   private void executeWithValue(String value) {
      this.onTimeConfirmed(value);
      if (this.commandTemplate != null) {
         String command = this.commandTemplate.replace("{time}", value);
         this.executeCommand(command);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 257 || keyCode == 335) {
         String text = this.inputField.getValue().trim();
         if (!text.isEmpty()) {
            if (!text.matches(".*[dst]$")) {
               text = text + "t";
            }

            this.executeWithValue(text);
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
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 70, -1);
      guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.time_hint"), centerX, centerY - 55, -7829368);
   }
}

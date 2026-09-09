package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class NumberInputScreen extends BaseParentedScreen<Screen> {
   private static final int BUTTON_WIDTH = 50;
   private static final int BUTTON_HEIGHT = 20;
   private static final int BUTTONS_PER_ROW = 5;
   private final String commandTemplate;
   private final int minValue;
   private final int maxValue;
   private final int[] quickValues;
   private EditBox inputField;

   public NumberInputScreen(Screen parent, Component title, String commandTemplate, Integer minValue, Integer maxValue, int[] quickValues) {
      super(title, parent);
      this.commandTemplate = commandTemplate;
      this.minValue = minValue != null ? minValue : 0;
      this.maxValue = maxValue != null ? maxValue : Integer.MAX_VALUE;
      this.quickValues = quickValues != null ? quickValues : generateQuickValues(this.minValue, this.maxValue);
   }

   private static int[] generateQuickValues(int min, int max) {
      List<Integer> values = new ArrayList<>();
      if (max <= 100) {
         for (int i = min; i <= max; i += Math.max(1, (max - min) / 10)) {
            values.add(i);
         }

         if (!values.contains(max)) {
            values.add(max);
         }
      } else {
         int[] common = new int[]{1, 5, 10, 20, 50, 100, 200, 500, 1000, 5000, 10000, 72000};

         for (int v : common) {
            if (v >= min && v <= max) {
               values.add(v);
            }
         }
      }

      return values.stream().mapToInt(i -> i).toArray();
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.inputField = new EditBox(this.font, centerX - 50, centerY - 40, 100, 20, Component.literal(""));
      this.inputField.setMaxLength(10);
      this.inputField.setHint(Component.literal(this.minValue + " - " + this.maxValue));
      this.addRenderableWidget(this.inputField);
      this.setInitialFocus(this.inputField);
      int rows = (this.quickValues.length + 5 - 1) / 5;
      int totalWidth = Math.min(this.quickValues.length, 5) * 54 - 4;
      int startX = centerX - totalWidth / 2;
      int startY = centerY - 5;
      int availableForRows = this.height - startY - 34;
      int maxRows = Math.max(1, availableForRows / 24);
      int visibleRows = Math.min(rows, maxRows);
      int visibleCount = Math.min(this.quickValues.length, visibleRows * 5);

      for (int i = 0; i < visibleCount; i++) {
         int value = this.quickValues[i];
         int col = i % 5;
         int row = i / 5;
         int x = startX + col * 54;
         int y = startY + row * 24;
         this.addRenderableWidget(Button.builder(Component.literal(String.valueOf(value)), btn -> this.executeWithValue(value)).bounds(x, y, 50, 20).build());
      }

      int closeBtnY = Math.min(centerY + 10 + visibleRows * 24, this.height - 20 - 4);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX - 50, closeBtnY, 100, 20)
            .build()
      );
   }

   protected void onNumberConfirmed(String number) {
   }

   private void executeWithValue(int value) {
      this.onNumberConfirmed(String.valueOf(value));
      if (this.commandTemplate != null) {
         String command = this.commandTemplate.replace("{number}", String.valueOf(value));
         this.executeCommand(command);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 257 || keyCode == 335) {
         String text = this.inputField.getValue().trim();
         if (!text.isEmpty()) {
            try {
               int value = Integer.parseInt(text);
               if (value >= this.minValue && value <= this.maxValue) {
                  this.executeWithValue(value);
               }
            } catch (NumberFormatException var5) {
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
      guiGraphics.centeredText(this.font, this.title, centerX, centerY - 70, -1);
      guiGraphics.centeredText(
         this.font, Component.translatable("screen.command-gui.number_range", new Object[]{this.minValue, this.maxValue}), centerX, centerY - 55, -7829368
      );
   }
}

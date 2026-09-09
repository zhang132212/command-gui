package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class BatchSpawnScreen extends BaseParentedScreen<Screen> {
   private static final String[] ENGLISH_NAMES = new String[]{
      "Alex",
      "Ben",
      "Carl",
      "David",
      "Eric",
      "Frank",
      "George",
      "Henry",
      "Ivan",
      "Jack",
      "Kevin",
      "Leo",
      "Mike",
      "Nick",
      "Oscar",
      "Paul",
      "Quinn",
      "Ryan",
      "Steve",
      "Tom",
      "Uma",
      "Victor",
      "Will",
      "Xavier",
      "York",
      "Zack"
   };
   private static final int FIELD_WIDTH = 150;
   private static final int LABEL_WIDTH = 80;
   private static final int ROW_GAP = 28;
   private EditBox prefixField;
   private EditBox startNumField;
   private EditBox countField;
   private String prefix = "Bot_";
   private int startNum = 1;
   private int count = 1;
   private boolean useEnglishNames = false;
   private int layoutTopY;

   public BatchSpawnScreen(Screen parent) {
      super(Component.translatable("screen.command-gui.fakeplayer.batch.title"), parent);
   }

   private int computeLayoutTopY() {
      int rows = this.useEnglishNames ? 2 : 4;
      int contentH = 24 + rows * 28 + 20 + 12 + 9;
      return Math.max(20, (this.height - contentH) / 2 - 10);
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int maxCount = this.useEnglishNames ? ENGLISH_NAMES.length : 50;
      this.count = Math.max(1, Math.min(this.count, maxCount));
      this.layoutTopY = this.computeLayoutTopY();
      int y = this.layoutTopY + 24;
      this.addRenderableWidget(Button.builder(this.getTypeLabel(), btn -> {
         this.useEnglishNames = !this.useEnglishNames;
         this.rebuildWidgets();
      }).bounds(centerX - 75, y, 150, 20).build());
      y += 28;
      if (!this.useEnglishNames) {
         this.prefixField = new EditBox(this.font, centerX - 75, y, 150, 20, Component.translatable("screen.command-gui.fakeplayer.batch.prefix"));
         this.prefixField.setMaxLength(20);
         this.prefixField.setValue(this.prefix);
         this.prefixField.setResponder(s -> this.prefix = s);
         this.addRenderableWidget(this.prefixField);
         y += 28;
         this.startNumField = new DigitsOnlyEditBox(this.font, centerX - 75, y, 150, 20, Component.translatable("screen.command-gui.fakeplayer.batch.start"));
         this.startNumField.setMaxLength(5);
         this.startNumField.setValue(String.valueOf(this.startNum));
         this.startNumField.setResponder(s -> {
            try {
               this.startNum = Integer.parseInt(s);
            } catch (NumberFormatException var3x) {
            }
         });
         this.addRenderableWidget(this.startNumField);
         y += 28;
      } else {
         this.prefixField = null;
         this.startNumField = null;
      }

      this.countField = new DigitsOnlyEditBox(this.font, centerX - 75, y, 150, 20, Component.translatable("screen.command-gui.fakeplayer.batch.count"));
      this.countField.setMaxLength(3);
      this.countField.setValue(String.valueOf(this.count));
      this.countField.setResponder(s -> {
         try {
            this.count = Math.max(1, Math.min(maxCount, Integer.parseInt(s)));
         } catch (NumberFormatException var4x) {
         }
      });
      this.addRenderableWidget(this.countField);
      y += 40;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.fakeplayer.batch.spawn"), btn -> this.spawnBatch())
            .bounds(centerX - 102, y, 100, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(centerX + 2, y, 100, 20)
            .build()
      );
   }

   private Component getTypeLabel() {
      return Component.translatable(
         this.useEnglishNames ? "screen.command-gui.fakeplayer.batch.type.english" : "screen.command-gui.fakeplayer.batch.type.numbered"
      );
   }

   private void spawnBatch() {
      if (this.useEnglishNames) {
         int n = Math.min(this.count, ENGLISH_NAMES.length);

         for (int i = 0; i < n; i++) {
            CommandHelper.sendCommand("/player " + ENGLISH_NAMES[i] + " spawn");
         }
      } else {
         for (int i = 0; i < this.count; i++) {
            CommandHelper.sendCommand("/player " + this.prefix + (this.startNum + i) + " spawn");
         }
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   private String buildPreview() {
      if (this.useEnglishNames) {
         int n = Math.min(this.count, ENGLISH_NAMES.length);
         if (n <= 1) {
            return ENGLISH_NAMES[0];
         }
         return ENGLISH_NAMES[0] + " ~ " + ENGLISH_NAMES[n - 1];
      } else {
         String first = this.prefix + this.startNum;
         if (this.count <= 1) {
            return first;
         }
         return first + " ~ " + this.prefix + (this.startNum + this.count - 1);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int labelX = Math.max(4, centerX - 75 - 40 - 5);
      int y = this.layoutTopY;
      guiGraphics.centeredText(this.font, this.title, centerX, y, -1);
      y += 24;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.fakeplayer.batch.type"), labelX, y + 6, -1);
      y += 28;
      if (!this.useEnglishNames) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.fakeplayer.batch.prefix"), labelX, y + 6, -1);
         y += 28;
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.fakeplayer.batch.start"), labelX, y + 6, -1);
         y += 28;
      }

      guiGraphics.text(this.font, Component.translatable("screen.command-gui.fakeplayer.batch.count"), labelX, y + 6, -1);
      y += 72;
      guiGraphics.centeredText(
         this.font, Component.translatable("screen.command-gui.fakeplayer.batch.preview", new Object[]{this.buildPreview()}), centerX, y, -7829368
      );
   }
}

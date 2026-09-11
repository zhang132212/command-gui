package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class ActionOptionScreen extends BaseParentedScreen<Screen> {
   private static final int FIELD_WIDTH = 180;
   private static final int FIELD_HEIGHT = 16;
   private static final int LABEL_COLOR = -5592406;
   private static final int BTN_GAP = 4;
   private static final String[] ACTIONS = new String[]{"attack", "use"};
   private static final String[] ACTION_NAMES = new String[]{"screen.command-gui.machine.action_attack", "screen.command-gui.machine.action_use"};
   private static final String[] MODES = new String[]{"once", "continuous", "interval"};
   private static final String[] MODE_NAMES = new String[]{
      "screen.command-gui.machine.action_once", "screen.command-gui.machine.action_continuous", "screen.command-gui.machine.action_interval"
   };
   private static final String[] MODE_DESCRIPTIONS = new String[]{
      "screen.command-gui.machine.action_once_desc", "screen.command-gui.machine.action_continuous_desc", "screen.command-gui.machine.action_interval_desc"
   };
   private final List<Button> actionButtons = new ArrayList<>();
   private final List<Button> modeButtons = new ArrayList<>();
   private int actionIndex = 0;
   private int modeIndex = 0;
   private String intervalText = "40";
   private EditBox intervalField;
   private final StepCommandHost host;

   public ActionOptionScreen(StepCommandHost host) {
      super(Component.translatable("screen.command-gui.machine.action_title"), (Screen)host);
      this.host = host;
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 180) / 2;
      int actionBtnWidth = 88;

      for (int i = 0; i < ACTIONS.length; i++) {
         int idx = i;
         int btnX = fieldX + i * (actionBtnWidth + 4);
         DarkSelectButton btn = new DarkSelectButton(btnX, 34, actionBtnWidth, 16, Component.translatable(ACTION_NAMES[idx]), b -> this.actionIndex = idx);
         btn.setDarkSelected(() -> this.actionIndex == idx, -1);
         this.actionButtons.add(btn);
         this.addRenderableWidget(btn);
      }

      int modeBtnWidth = 57;

      for (int i = 0; i < MODES.length; i++) {
         int idx = i;
         int btnX = fieldX + i * (modeBtnWidth + 4);
         DarkSelectButton btn = new DarkSelectButton(btnX, 70, modeBtnWidth, 16, Component.translatable(MODE_NAMES[i]), b -> {
            this.modeIndex = idx;
            if (this.intervalField != null) {
               this.intervalField.visible = idx == 2;
            }
         });
         btn.setDarkSelected(() -> this.modeIndex == idx, -1);
         this.modeButtons.add(btn);
         this.addRenderableWidget(btn);
      }

      this.intervalField = new DigitsOnlyEditBox(this.font, fieldX, 106, 80, 16, Component.translatable("screen.command-gui.machine.action_interval_label"));
      this.intervalField.setMaxLength(7);
      this.intervalField.setValue(this.intervalText);
      this.intervalField.setResponder(text -> this.intervalText = text);
      this.intervalField.visible = this.modeIndex == 2;
      this.addRenderableWidget(this.intervalField);
      int barY = this.height - 22;
      int barWidth = Math.min(70, 100);
      int barStartX = fieldX + (180 - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.machine.spawn_insert"), btnx -> this.insertAndClose())
            .bounds(barStartX, barY, barWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.back"), btnx -> this.minecraft.gui.setScreen(this.parent))
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private String buildCommand() {
      String cmd = "/player {bot} " + ACTIONS[this.actionIndex];
      switch (this.modeIndex) {
         case 0:
            cmd = cmd + " once";
            break;
         case 1:
            cmd = cmd + " continuous";
            break;
         case 2:
            int ticks = 40;

            try {
               ticks = Integer.parseInt(this.intervalText.trim());
            } catch (NumberFormatException var4) {
            }

            cmd = cmd + " interval " + Math.max(1, Math.min(72000, ticks));
      }

      return cmd;
   }

   private void insertAndClose() {
      List<String> commands = new ArrayList<>();
      commands.add(this.buildCommand());
      this.host.insertCommandSequence(commands);
      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int fieldX = (this.width - 180) / 2;
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.action_label"), fieldX, 22, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.action_mode"), fieldX, 58, -5592406);
      if (this.modeIndex == 2) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.action_interval_label"), fieldX, 94, -5592406);
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.tick_hint"), fieldX + 100, 94, -7829368);
      } else {
         guiGraphics.text(this.font, Component.translatable(MODE_DESCRIPTIONS[this.modeIndex]), fieldX, 94, -5592406);
      }

      String preview = this.buildCommand();
      String display = this.font.plainSubstrByWidth(preview, 172);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.fakeplayer.command_preview"), fieldX, 136, -7829368);
      guiGraphics.text(this.font, Component.literal(display), fieldX + 4, 146, -11141291);
   }
}

package com.remrin.client.gui;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class BotSelectScreen extends BaseParentedScreen<Screen> {
   private static final int FIELD_WIDTH = 200;
   private static final int ROW_HEIGHT = 20;
   private final List<String> botNames;
   private final StepCommandHost host;
   private final Consumer<String> onBotSelected;

   public BotSelectScreen(StepCommandHost host, List<String> botNames) {
      super(Component.translatable("screen.command-gui.machine.bot_select_title"), (Screen)host);
      this.host = host;
      this.onBotSelected = null;
      this.botNames = botNames;
   }

   public BotSelectScreen(Screen parent, List<String> botNames, Consumer<String> onBotSelected) {
      super(Component.translatable("screen.command-gui.machine.bot_select_title"), parent);
      this.host = null;
      this.onBotSelected = onBotSelected;
      this.botNames = botNames;
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 200) / 2;
      int y = 30;
      int maxY = this.height - 30;

      for (int i = 0; i < this.botNames.size() && y + 20 <= maxY; i++) {
         String name = this.botNames.get(i);
         Button btn = GuiButton.themed(Component.literal(name), b -> {
            if (this.host != null) {
               this.host.selectBotByName(name);
               this.minecraft.gui.setScreen(this.parent);
            } else if (this.onBotSelected != null) {
               this.onBotSelected.accept(name);
            }
         }).bounds(fieldX, y, 200, 18).build();
         this.addRenderableWidget(btn);
         y += 20;
      }

      int barY = this.height - 22;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.back"), btnx -> this.minecraft.gui.setScreen(this.parent))
            .bounds(fieldX, barY, 80, 18)
            .build()
      );
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
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, -1);
      if (this.botNames.isEmpty()) {
         guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.machine.bot_select_empty"), this.width / 2, 40, -7829368);
      }
   }
}

package com.remrin.client.gui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Fake player picker for the step editor: lists the machine's bot names (shown as player names);
 * clicking one sets it as the step's bot and renames the already-typed commands.
 */
public class BotSelectScreen extends BaseParentedScreen<StepEditorScreen> {

  private static final int FIELD_WIDTH = 200;
  private static final int ROW_HEIGHT = 20;

  private final List<String> botNames;

  public BotSelectScreen(StepEditorScreen parent, List<String> botNames) {
    super(Component.translatable("screen.command-gui.machine.bot_select_title"), parent);
    this.botNames = botNames;
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    int y = 30;
    int maxY = this.height - 30;
    for (int i = 0; i < botNames.size() && y + ROW_HEIGHT <= maxY; i++) {
      final String name = botNames.get(i);
      Button btn = Button.builder(Component.literal(name), b -> {
        parent.selectBotByName(name);
        this.minecraft.gui.setScreen(parent);
      }).bounds(fieldX, y, FIELD_WIDTH, ROW_HEIGHT - 2).build();
      this.addRenderableWidget(btn);
      y += ROW_HEIGHT;
    }

    int barY = this.height - 22;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(fieldX, barY, 80, 18).build());
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

    if (botNames.isEmpty()) {
      guiGraphics.centeredText(this.font,
          Component.translatable("screen.command-gui.machine.bot_select_empty"),
          this.width / 2, 40, 0xFF888888);
    }
  }
}

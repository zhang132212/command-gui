package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class SpawnOptionScreen extends BaseParentedScreen<Screen> {
   private static final int FIELD_WIDTH = 180;
   private static final int FIELD_HEIGHT = 16;
   private static final int COORD_GAP = 6;
   private static final int XYZ_FIELD_WIDTH = 56;
   private static final int HALF_FIELD_WIDTH = 87;
   private static final int LABEL_COLOR = -5592406;
   private static final int BTN_GAP = 4;
   private static final int SCROLLBAR_WIDTH = 12;
   private static final int VIEWPORT_TOP = 18;
   private static final int CONTENT_HEIGHT = 260;
   private final StepCommandHost host;
   private static final String[] DIMENSIONS = new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
   private static final String[] DIMENSION_NAMES = new String[]{
      "screen.command-gui.fakeplayer.dim.overworld", "screen.command-gui.fakeplayer.dim.nether", "screen.command-gui.fakeplayer.dim.end"
   };
   private final List<Button> dimensionButtons = new ArrayList<>();
   private final List<AbstractWidget> contentWidgets = new ArrayList<>();
   private final List<Integer> contentWidgetYs = new ArrayList<>();
   private static final String[] GAME_MODES = new String[]{"", "survival", "creative", "adventure", "spectator"};
   private static final String[] GAME_MODE_NAMES = new String[]{
      "screen.command-gui.machine.spawn_gamemode_default_short",
      "screen.command-gui.fakeplayer.gamemode.survival_short",
      "screen.command-gui.fakeplayer.gamemode.creative_short",
      "screen.command-gui.fakeplayer.gamemode.adventure_short",
      "screen.command-gui.fakeplayer.gamemode.spectator_short"
   };
   private int dimensionIndex = 0;
   private boolean usePlayerPosition = true;
   private String spawnGameMode = "";
   private String xText = "";
   private String yText = "";
   private String zText = "";
   private String yawText = "";
   private String pitchText = "";
   private EditBox xField;
   private EditBox yField;
   private EditBox zField;
   private EditBox yawField;
   private EditBox pitchField;
   private int contentScroll = 0;
   private int viewportBottom;
   private boolean scrollbarDragging = false;
   private double scrollbarGrabOffset = 0.0;

   public SpawnOptionScreen(StepCommandHost host) {
      super(Component.translatable("screen.command-gui.machine.spawn_title"), (Screen)host);
      this.host = host;
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         ResourceKey<Level> dim = mc.player.level().dimension();
         if (dim.equals(Level.NETHER)) {
            this.dimensionIndex = 1;
         } else if (dim.equals(Level.END)) {
            this.dimensionIndex = 2;
         } else {
            this.dimensionIndex = 0;
         }

         this.xText = CommandHelper.formatX(mc.player.getX());
         this.yText = CommandHelper.formatY(mc.player.getY());
         this.zText = CommandHelper.formatZ(mc.player.getZ());
      }
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 180) / 2;
      this.viewportBottom = this.height - 28;
      this.dimensionButtons.clear();
      int dimensionBtnWidth = 57;

      for (int i = 0; i < DIMENSIONS.length; i++) {
         int idx = i;
         int btnX = fieldX + i * (dimensionBtnWidth + 4);
         DarkSelectButton dimBtn = new DarkSelectButton(
            btnX, 34, dimensionBtnWidth, 16, Component.translatable(DIMENSION_NAMES[i]), btn -> this.dimensionIndex = idx
         );
         dimBtn.setDarkSelected(() -> this.dimensionIndex == idx, -1);
         this.dimensionButtons.add(dimBtn);
         this.registerContent(dimBtn);
      }

      DarkSelectButton posBtn = new DarkSelectButton(
         fieldX, 58, 87, 16, Component.translatable("screen.command-gui.machine.spawn_own_feet"), btn -> this.togglePlayerPosition()
      );
      posBtn.setDarkSelected(() -> this.usePlayerPosition, -1);
      this.registerContent(posBtn);
      this.registerContent(
         Button.builder(Component.translatable("screen.command-gui.machine.spawn_own_rotation"), btn -> this.fillOwnRotation())
            .bounds(fieldX + 87 + 4, 58, 87, 16)
            .build()
      );
      this.xField = new EditBox(this.font, fieldX, 94, 56, 16, Component.literal("X"));
      this.xField.setMaxLength(12);
      this.xField.setValue(this.xText);
      this.xField.setResponder(text -> this.xText = text);
      this.registerContent(this.xField);
      this.yField = new EditBox(this.font, fieldX + 56 + 6, 94, 56, 16, Component.literal("Y"));
      this.yField.setMaxLength(12);
      this.yField.setValue(this.yText);
      this.yField.setResponder(text -> this.yText = text);
      this.registerContent(this.yField);
      this.zField = new EditBox(this.font, fieldX + 124, 94, 56, 16, Component.literal("Z"));
      this.zField.setMaxLength(12);
      this.zField.setValue(this.zText);
      this.zField.setResponder(text -> this.zText = text);
      this.registerContent(this.zField);
      this.yawField = new EditBox(this.font, fieldX, 130, 87, 16, Component.literal("Yaw"));
      this.yawField.setMaxLength(10);
      this.yawField.setValue(this.yawText);
      this.yawField.setResponder(text -> this.yawText = text);
      this.registerContent(this.yawField);
      this.pitchField = new EditBox(this.font, fieldX + 87 + 6, 130, 87, 16, Component.literal("Pitch"));
      this.pitchField.setMaxLength(10);
      this.pitchField.setValue(this.pitchText);
      this.pitchField.setResponder(text -> this.pitchText = text);
      this.registerContent(this.pitchField);
      if (this.isOperator()) {
         int gamemodeY = 166;
         int gamemodeBtnWidth = 34;
         for (int i = 0; i < GAME_MODES.length; i++) {
            int index = i;
            DarkSelectButton gamemodeBtn = new DarkSelectButton(
               fieldX + i * (gamemodeBtnWidth + 2),
               gamemodeY,
               gamemodeBtnWidth,
               16,
               Component.translatable(GAME_MODE_NAMES[i]),
               btn -> this.spawnGameMode = GAME_MODES[index]
            );
            gamemodeBtn.setDarkSelected(() -> this.spawnGameMode.equals(GAME_MODES[index]), -1);
            this.registerContent(gamemodeBtn);
         }
      }
      int barY = this.height - 22;
      int barWidth = Math.min(70, 100);
      int barStartX = fieldX + (180 - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.spawn_insert"), btn -> this.insertAndClose())
            .bounds(barStartX, barY, barWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.minecraft.gui.setScreen(this.parent))
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
      this.applyContentScroll();
   }

   private void registerContent(AbstractWidget widget) {
      this.contentWidgets.add(widget);
      this.contentWidgetYs.add(widget.getY());
      this.addRenderableWidget(widget);
   }

   private int getMaxScroll() {
      return Math.max(0, 260 - (this.viewportBottom - 18));
   }

   private void setContentScroll(int scroll) {
      this.contentScroll = Math.max(0, Math.min(scroll, this.getMaxScroll()));
      this.applyContentScroll();
   }

   private void applyContentScroll() {
      for (int i = 0; i < this.contentWidgets.size(); i++) {
         AbstractWidget widget = this.contentWidgets.get(i);
         widget.setY(this.contentWidgetYs.get(i) - this.contentScroll);
         widget.visible = widget.getY() >= 18 && widget.getY() + widget.getHeight() <= this.viewportBottom;
      }
   }

   private int scrollbarX() {
      return (this.width - 180) / 2 + 180 + 8;
   }

   private boolean isOverScrollbar(double mouseX, double mouseY) {
      return mouseX >= (double)this.scrollbarX() && mouseX <= (double)(this.scrollbarX() + 12) && mouseY >= 18.0 && mouseY <= (double)this.viewportBottom;
   }

   private double thumbTop() {
      int viewportH = this.viewportBottom - 18;
      int maxScroll = this.getMaxScroll();
      if (maxScroll <= 0) {
         return 18.0;
      } else {
         int thumbH = Math.max(12, viewportH * viewportH / 260);
         return (double)(18 + (viewportH - thumbH) * this.contentScroll / maxScroll);
      }
   }

   private double thumbHeight() {
      int viewportH = this.viewportBottom - 18;
      int maxScroll = this.getMaxScroll();
      if (maxScroll <= 0) {
         return (double)viewportH;
      }
      return (double)Math.max(12, viewportH * viewportH / 260);
   }

   private void updateScrollFromMouseY(double mouseY) {
      int viewportH = this.viewportBottom - 18;
      int maxScroll = this.getMaxScroll();
      double thumbH = this.thumbHeight();
      double scroll = (mouseY - 18.0 - this.scrollbarGrabOffset) * (double)maxScroll / ((double)viewportH - thumbH);
      this.setContentScroll((int)Math.round(scroll));
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.getMaxScroll() > 0) {
         this.setContentScroll(this.contentScroll - (scrollY > 0.0 ? 8 : -8));
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (this.getMaxScroll() > 0 && mouseEvent.button() == 0 && this.isOverScrollbar(mouseEvent.x(), mouseEvent.y())) {
         this.scrollbarDragging = true;
         this.scrollbarGrabOffset = mouseEvent.y() - this.thumbTop();
         this.updateScrollFromMouseY(mouseEvent.y());
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.scrollbarDragging) {
         this.updateScrollFromMouseY(mouseEvent.y());
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.scrollbarDragging = false;
      return super.mouseReleased(mouseEvent);
   }

   private void togglePlayerPosition() {
      this.usePlayerPosition = !this.usePlayerPosition;
      if (this.usePlayerPosition) {
         this.fillCurrentPosition();
      } else {
         this.xText = "";
         this.yText = "";
         this.zText = "";
         this.xField.setValue("");
         this.yField.setValue("");
         this.zField.setValue("");
      }
   }

   private boolean isOperator() {
      Minecraft mc = Minecraft.getInstance();
      return mc != null && mc.player != null && Commands.LEVEL_MODERATORS.check(mc.player.permissions());
   }

   private void fillCurrentPosition() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         this.setPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ());
      }
   }

   private void fillOwnRotation() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         this.yawText = String.format("%.1f", (float)Math.round(mc.player.getYRot() * 10.0F) / 10.0F);
         this.pitchText = String.format("%.1f", (float)Math.round(mc.player.getXRot() * 10.0F) / 10.0F);
         this.yawField.setValue(this.yawText);
         this.pitchField.setValue(this.pitchText);
      }
   }

   private void setPosition(double x, double y, double z) {
      this.xText = CommandHelper.formatX(x);
      this.yText = CommandHelper.formatY(y);
      this.zText = CommandHelper.formatZ(z);
      this.xField.setValue(this.xText);
      this.yField.setValue(this.yText);
      this.zField.setValue(this.zText);
   }

   private String buildCommand() {
      String cmd = "/player {bot} spawn at " + this.xText + " " + this.yText + " " + this.zText;
      String yaw = this.yawText.trim();
      String pitch = this.pitchText.trim();
      cmd = cmd + " facing " + (yaw.isEmpty() ? "0" : yaw) + " " + (pitch.isEmpty() ? "0" : pitch);
      cmd = cmd + " in " + DIMENSIONS[this.dimensionIndex];
      if (!this.spawnGameMode.isEmpty()) {
         cmd = cmd + " in " + this.spawnGameMode;
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
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.fakeplayer.dimension"), 22);
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.fakeplayer.spawn_at"), 82);
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.machine.spawn_facing"), 118);
      if (this.isOperator()) {
         this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.fakeplayer.gamemode"), 154);
      }
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.fakeplayer.command_preview"), 214);
      List<String> previewCommands = new ArrayList<>();
      previewCommands.add(this.buildCommand());

      int previewMaxW = 172;
      int lineY = 224 - this.contentScroll;

      for (String cmd : previewCommands) {
         String remaining = cmd;

         while (!remaining.isEmpty() && lineY < this.viewportBottom) {
            String line = this.font.plainSubstrByWidth(remaining, previewMaxW);
            if (line.isEmpty()) {
               line = remaining.substring(0, 1);
            }

            if (lineY >= 18) {
               guiGraphics.text(this.font, line, fieldX + 4, lineY, -11141291);
            }
            lineY += 9;
            remaining = remaining.substring(line.length());
         }
      }

      if (this.getMaxScroll() > 0) {
         int scrollbarX = this.scrollbarX();
         ScrollbarHandle handle = new ScrollbarHandle(scrollbarX, 18, 12, this.viewportBottom - 18);
         int viewportH = this.viewportBottom - 18;
         handle.render(guiGraphics, this.contentScroll, this.getMaxScroll(), viewportH, 260, false);
      }
   }

   private void renderScrolledLabel(GuiGraphicsExtractor guiGraphics, int x, Component label, int y) {
      int scrolledY = y - this.contentScroll;
      if (scrolledY >= 16 && scrolledY + 10 <= this.viewportBottom) {
         guiGraphics.text(this.font, label, x, scrolledY, -5592406);
      }
   }
}

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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

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
   private int dimensionIndex = 0;
   private String xText = "";
   private String yText = "";
   private String zText = "";
   private String yawText = "";
   private String pitchText = "";
   private String lookXText = "";
   private String lookYText = "";
   private String lookZText = "";
   private EditBox xField;
   private EditBox yField;
   private EditBox zField;
   private EditBox yawField;
   private EditBox pitchField;
   private EditBox lookXField;
   private EditBox lookYField;
   private EditBox lookZField;
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

         this.xText = String.format("%.1f", (double)Math.round(mc.player.getX() * 10.0) / 10.0);
         this.yText = String.format("%.1f", (double)Math.round(mc.player.getY() * 10.0) / 10.0);
         this.zText = String.format("%.1f", (double)Math.round(mc.player.getZ() * 10.0) / 10.0);
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

      this.registerContent(
         Button.builder(Component.translatable("screen.command-gui.machine.spawn_own_feet"), btn -> this.fillFeet()).bounds(fieldX, 58, 87, 16).build()
      );
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
      this.registerContent(
         Button.builder(Component.translatable("screen.command-gui.machine.spawn_look_target"), btn -> this.fillLookTarget())
            .bounds(fieldX, 166, 87, 16)
            .build()
      );
      this.registerContent(
         Button.builder(Component.translatable("screen.command-gui.machine.spawn_look_center"), btn -> this.fillLookCenter())
            .bounds(fieldX + 87 + 4, 166, 87, 16)
            .build()
      );
      this.lookXField = new EditBox(this.font, fieldX, 190, 56, 16, Component.literal("LX"));
      this.lookXField.setMaxLength(12);
      this.lookXField.setValue(this.lookXText);
      this.lookXField.setResponder(text -> this.lookXText = text);
      this.registerContent(this.lookXField);
      this.lookYField = new EditBox(this.font, fieldX + 56 + 6, 190, 56, 16, Component.literal("LY"));
      this.lookYField.setMaxLength(12);
      this.lookYField.setValue(this.lookYText);
      this.lookYField.setResponder(text -> this.lookYText = text);
      this.registerContent(this.lookYField);
      this.lookZField = new EditBox(this.font, fieldX + 124, 190, 56, 16, Component.literal("LZ"));
      this.lookZField.setMaxLength(12);
      this.lookZField.setValue(this.lookZText);
      this.lookZField.setResponder(text -> this.lookZText = text);
      this.registerContent(this.lookZField);
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
         this.contentWidgets.get(i).setY(this.contentWidgetYs.get(i) - this.contentScroll);
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

   private void fillFeet() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         this.setPosition((double)((int)Math.ceil(mc.player.getX())), (double)((int)Math.ceil(mc.player.getY())), (double)((int)Math.ceil(mc.player.getZ())));
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

   private void fillLookTarget() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.hitResult instanceof BlockHitResult blockHit) {
         BlockPos pos = blockHit.getBlockPos();
         this.lookXField.setValue(String.valueOf(pos.getX()));
         this.lookYField.setValue(String.valueOf(pos.getY()));
         this.lookZField.setValue(String.valueOf(pos.getZ()));
      }
   }

   private void fillLookCenter() {
      this.lookXText = blockCenterCoord(this.lookXText);
      this.lookYText = blockCenterCoord(this.lookYText);
      this.lookZText = blockCenterCoord(this.lookZText);
      this.lookXField.setValue(this.lookXText);
      this.lookYField.setValue(this.lookYText);
      this.lookZField.setValue(this.lookZText);
   }

   private static String blockCenterCoord(String text) {
      try {
         double value = Double.parseDouble(text.trim());
         return String.format("%.1f", Math.floor(value) + 0.5);
      } catch (NumberFormatException var3) {
         return text;
      }
   }

   private void setPosition(double x, double y, double z) {
      this.xText = String.format("%.1f", (double)Math.round(x * 10.0) / 10.0);
      this.yText = String.format("%.1f", (double)Math.round(y * 10.0) / 10.0);
      this.zText = String.format("%.1f", (double)Math.round(z * 10.0) / 10.0);
      this.xField.setValue(this.xText);
      this.yField.setValue(this.yText);
      this.zField.setValue(this.zText);
   }

   private String buildCommand() {
      String cmd = "/player {bot} spawn at " + this.xText + " " + this.yText + " " + this.zText;
      String yaw = this.yawText.trim();
      String pitch = this.pitchText.trim();
      cmd = cmd + " facing " + (yaw.isEmpty() ? "0" : yaw) + " " + (pitch.isEmpty() ? "0" : pitch);
      return cmd + " in " + DIMENSIONS[this.dimensionIndex];
   }

   private String buildLookCommand() {
      return !this.lookXText.isEmpty() && !this.lookYText.isEmpty() && !this.lookZText.isEmpty()
         ? "/player {bot} look at " + this.lookXText + " " + this.lookYText + " " + this.lookZText
         : null;
   }

   private void insertAndClose() {
      List<String> commands = new ArrayList<>();
      commands.add(this.buildCommand());
      String look = this.buildLookCommand();
      if (look != null) {
         commands.add(look);
      }

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
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.machine.spawn_look_at"), 154);
      this.renderScrolledLabel(guiGraphics, fieldX, Component.translatable("screen.command-gui.fakeplayer.command_preview"), 214);
      List<String> previewCommands = new ArrayList<>();
      previewCommands.add(this.buildCommand());
      String look = this.buildLookCommand();
      if (look != null) {
         previewCommands.add(look);
      }

      int previewMaxW = 172;
      int lineY = 224 - this.contentScroll;

      for (String cmd : previewCommands) {
         String remaining = cmd;

         while (!remaining.isEmpty() && lineY < this.viewportBottom) {
            String line = this.font.plainSubstrByWidth(remaining, previewMaxW);
            if (line.isEmpty()) {
               line = remaining.substring(0, 1);
            }

            guiGraphics.text(this.font, line, fieldX + 4, lineY, -11141291);
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

package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class MultiModeConfigScreen extends BaseParentedScreen<ModesEditorScreen> {
   private static final int ROW_HEIGHT = 20;
   private static final int MOVE_BTN_W = 48;
   private final List<MachineModels.ModeData> modes;
   private List<String> order = new ArrayList<>();
   private List<String> stopOrder = new ArrayList<>();
   private EditBox intervalField;
   private EditBox stopIntervalField;
   private MarkCheckbox followStartCheckbox;
   private boolean followStart;
   private final List<Button> rowButtons = new ArrayList<>();
   private int listLeft;
   private int listTop;
   private int listRight;
   private int listBottom;
   private int scrollStartOffset = 0;
   private int scrollStopOffset = 0;
   private ScrollbarHandle scrollbarStart = null;
   private ScrollbarHandle scrollbarStop = null;
   private boolean draggingStart = false;
   private boolean draggingStop = false;
   private double grabStartOffset = 0.0;
   private double grabStopOffset = 0.0;

   public MultiModeConfigScreen(ModesEditorScreen parent) {
      super(Component.translatable("screen.command-gui.machine.multi_mode_config_title"), parent);
      this.modes = parent.getWorking();
      this.order = new ArrayList<>(this.effectiveOrder(parent.getWorkingModeOrder()));
      this.stopOrder = new ArrayList<>(this.filterShutdownModes(this.effectiveOrder(parent.getWorkingStopModeOrder())));
      this.followStart = parent.isWorkingStopFollowsStart();
   }

   protected void init() {
      super.init();
      int listWidth = Math.min(520, this.width - 40);
      this.listLeft = (this.width - listWidth) / 2;
      this.listRight = this.listLeft + listWidth;
      this.listTop = 30;
      this.listBottom = this.height - 90;
      this.rebuildOrderButtons();
      int y = this.listBottom + 12;
      int fieldW = 56;
      int fieldH = 16;
      int labelH = 10;
      int colGap = 40;
      int colWidth = (this.listRight - this.listLeft - colGap) / 2;
      int startFieldX = this.listLeft;
      this.intervalField = new DigitsOnlyEditBox(
         this.font, startFieldX, y, fieldW, fieldH, Component.translatable("screen.command-gui.machine.mode_start_interval_label")
      );
      this.intervalField.setMaxLength(6);
      this.intervalField.setValue(String.valueOf(this.parent.getWorkingModeInterval()));
      this.addRenderableWidget(this.intervalField);
      int stopFieldX = this.listLeft + colWidth + colGap;
      this.stopIntervalField = new DigitsOnlyEditBox(
         this.font, stopFieldX, y, fieldW, fieldH, Component.translatable("screen.command-gui.machine.mode_stop_interval_label")
      );
      this.stopIntervalField.setMaxLength(6);
      this.stopIntervalField.setValue(String.valueOf(this.parent.getWorkingStopModeInterval()));
      this.stopIntervalField.active = !this.followStart;
      this.addRenderableWidget(this.stopIntervalField);
      this.followStartCheckbox = new MarkCheckbox(
         this.listLeft, y + fieldH + 12, 240, 14, Component.translatable("screen.command-gui.machine.stop_follows_start"), this.followStart, b -> {
            boolean newValue = !this.followStartCheckbox.selected();
            this.followStartCheckbox.setSelected(newValue);
            this.onFollowChanged(newValue);
         }
      );
      this.followStartCheckbox.setAvailable(this.followAvailable());
      this.addRenderableWidget(this.followStartCheckbox);
      int barY = this.height - 26;
      int barWidth = 70;
      int barStartX = this.listLeft + (listWidth - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.save"), b -> this.saveAndClose()).bounds(barStartX, barY, barWidth, 18).build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.back"), b -> this.minecraft.gui.setScreen(this.parent))
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private void rebuildOrderButtons() {
      for (Button button : this.rowButtons) {
         this.removeWidget(button);
      }

      this.rowButtons.clear();
      int visibleRows = Math.max(1, (this.listBottom - this.listTop) / 20);
      int startMax = this.getStartMaxScroll(visibleRows);
      int stopMax = this.getStopMaxScroll(visibleRows);
      this.scrollStartOffset = Math.max(0, Math.min(this.scrollStartOffset, startMax));
      this.scrollStopOffset = Math.max(0, Math.min(this.scrollStopOffset, stopMax));
      int colGap = 40;
      int colWidth = (this.listRight - this.listLeft - colGap) / 2;
      int actionW = 48;
      int labelW = colWidth - (actionW + 1) * 2 - 16;

      for (int col = 0; col < 2; col++) {
         List<String> list = col == 0 ? this.order : this.stopOrder;
         int colOffset = col == 0 ? this.scrollStartOffset : this.scrollStopOffset;
         int colX = this.listLeft + col * (colWidth + colGap);

         for (int i = colOffset; i < Math.min(colOffset + visibleRows, list.size()); i++) {
            int y = this.listTop + (i - colOffset) * 20;
            String modeName = this.modeName(list.get(i));
            int textMaxW = labelW * 2 / 3;
            boolean tooLong = this.font.width(modeName) > textMaxW;
            if (tooLong) {
               textMaxW = Math.max(0, textMaxW - this.font.width("..."));
            }

            String label = this.font.plainSubstrByWidth(modeName, textMaxW);
            if (tooLong) {
               label = label + "...";
            }

            this.rowButtons.add(GuiButton.themed(Component.literal(label), b -> {
            }).bounds(colX, y, labelW, 18).build());
            int x = colX + labelW + 2;
            int colFinal = col;
            int idx = i;
            Button upBtn = GuiButton.themed(Component.translatable("screen.command-gui.step_up_short"), b -> this.move(colFinal, idx, -1))
               .bounds(x, y, actionW, 18)
               .build();
            upBtn.active = idx > 0;
            if (colFinal == 1 && this.followStart) {
               upBtn.active = false;
            }

            upBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_up")));
            this.rowButtons.add(upBtn);
            x += actionW + 1;
            Button downBtn = GuiButton.themed(Component.translatable("screen.command-gui.step_down_short"), b -> this.move(colFinal, idx, 1))
               .bounds(x, y, actionW, 18)
               .build();
            downBtn.active = idx < list.size() - 1;
            if (colFinal == 1 && this.followStart) {
               downBtn.active = false;
            }

            downBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_down")));
            this.rowButtons.add(downBtn);
         }
      }

      for (Button button : this.rowButtons) {
         this.addRenderableWidget(button);
      }
   }

   private int getStartRows() {
      return this.order.size();
   }

   private int getStopRows() {
      return this.stopOrder.size();
   }

   private int getStartMaxScroll(int visibleRows) {
      return Math.max(0, this.getStartRows() - visibleRows);
   }

   private int getStopMaxScroll(int visibleRows) {
      return Math.max(0, this.getStopRows() - visibleRows);
   }

   private int getStartMaxScroll() {
      return this.getStartMaxScroll(Math.max(1, (this.listBottom - this.listTop) / 20));
   }

   private int getStopMaxScroll() {
      return this.getStopMaxScroll(Math.max(1, (this.listBottom - this.listTop) / 20));
   }

   private void scrollStart(double delta) {
      if (delta > 0.0 && this.scrollStartOffset > 0) {
         this.scrollStartOffset--;
         this.rebuildOrderButtons();
      } else if (delta < 0.0 && this.scrollStartOffset < this.getStartMaxScroll()) {
         this.scrollStartOffset++;
         this.rebuildOrderButtons();
      }
   }

   private void scrollStop(double delta) {
      if (delta > 0.0 && this.scrollStopOffset > 0) {
         this.scrollStopOffset--;
         this.rebuildOrderButtons();
      } else if (delta < 0.0 && this.scrollStopOffset < this.getStopMaxScroll()) {
         this.scrollStopOffset++;
         this.rebuildOrderButtons();
      }
   }

   private void setStartScrollOffset(int offset) {
      this.scrollStartOffset = Math.max(0, Math.min(offset, this.getStartMaxScroll()));
      this.rebuildOrderButtons();
   }

   private void setStopScrollOffset(int offset) {
      this.scrollStopOffset = Math.max(0, Math.min(offset, this.getStopMaxScroll()));
      this.rebuildOrderButtons();
   }

   private int columnAtX(double mouseX) {
      int colGap = 40;
      int colWidth = (this.listRight - this.listLeft - colGap) / 2;
      if (mouseX < (double)(this.listLeft + colWidth) + (double)colGap / 2.0) {
         return 0;
      }
      return 1;
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int column = this.columnAtX(mouseX);
      if (column == 0) {
         if (this.getStartMaxScroll() > 0) {
            this.scrollStart(scrollY > 0.0 ? 1.0 : -1.0);
            return true;
         }
      } else if (this.getStopMaxScroll() > 0) {
         this.scrollStop(scrollY > 0.0 ? 1.0 : -1.0);
         return true;
      }

      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0) {
         int visibleRows = Math.max(1, (this.listBottom - this.listTop) / 20);
         if (this.scrollbarStart != null && this.scrollbarStart.contains(mouseEvent.x(), mouseEvent.y()) && this.getStartMaxScroll() > 0) {
            int thumbTop = this.scrollbarStart.thumbTop(this.scrollStartOffset, this.getStartMaxScroll(), visibleRows, this.getStartRows());
            this.grabStartOffset = mouseEvent.y() - (double)thumbTop;
            this.draggingStart = true;
            return true;
         }

         if (this.scrollbarStop != null && this.scrollbarStop.contains(mouseEvent.x(), mouseEvent.y()) && this.getStopMaxScroll() > 0) {
            int thumbTop = this.scrollbarStop.thumbTop(this.scrollStopOffset, this.getStopMaxScroll(), visibleRows, this.getStopRows());
            this.grabStopOffset = mouseEvent.y() - (double)thumbTop;
            this.draggingStop = true;
            return true;
         }
      }

      return super.mouseClicked(mouseEvent, focused);
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      int visibleRows = Math.max(1, (this.listBottom - this.listTop) / 20);
      if (this.draggingStart && this.scrollbarStart != null) {
         int offset = this.scrollbarStart.offsetFromY(mouseEvent.y(), this.grabStartOffset, this.getStartMaxScroll(), visibleRows, this.getStartRows());
         this.setStartScrollOffset(offset);
         return true;
      } else if (this.draggingStop && this.scrollbarStop != null) {
         int offset = this.scrollbarStop.offsetFromY(mouseEvent.y(), this.grabStopOffset, this.getStopMaxScroll(), visibleRows, this.getStopRows());
         this.setStopScrollOffset(offset);
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingStart = false;
      this.draggingStop = false;
      return super.mouseReleased(mouseEvent);
   }

   private void move(int col, int index, int delta) {
      List<String> list = col == 0 ? this.order : this.stopOrder;
      if (index >= 0 && index < list.size()) {
         int target = index + delta;
         if (target >= 0 && target < list.size()) {
            String value = list.remove(index);
            list.add(target, value);
            if (this.followStart && col == 0) {
               this.stopOrder = new ArrayList<>(this.filterShutdownModes(this.order));
            }

            this.rebuildOrderButtons();
         }
      }
   }

   private void onFollowChanged(boolean value) {
      this.followStart = value;
      if (value) {
         this.stopOrder = new ArrayList<>(this.filterShutdownModes(this.order));
         this.stopIntervalField.setValue(this.intervalField.getValue());
         this.stopIntervalField.active = false;
      } else {
         this.stopIntervalField.active = true;
      }

      this.rebuildOrderButtons();
   }

   private boolean followAvailable() {
      return new HashSet<>(this.order).equals(new HashSet<>(this.stopOrder));
   }

   private String modeName(String modeId) {
      for (MachineModels.ModeData mode : this.modes) {
         if (mode.id != null && mode.id.equals(modeId)) {
            return mode.name;
         }
      }

      return modeId;
   }

   private List<String> effectiveOrder(List<String> preset) {
      List<String> result = new ArrayList<>();
      if (preset != null) {
         result.addAll(preset);
      }

      for (MachineModels.ModeData mode : this.modes) {
         if (!result.contains(mode.id)) {
            result.add(mode.id);
         }
      }

      return result;
   }

   private List<String> filterShutdownModes(List<String> ids) {
      List<String> result = new ArrayList<>();

      for (String id : ids) {
         MachineModels.ModeData mode = this.findMode(id);
         if (mode != null && hasShutdownFlow(mode)) {
            result.add(id);
         }
      }

      return result;
   }

   private MachineModels.ModeData findMode(String modeId) {
      for (MachineModels.ModeData mode : this.modes) {
         if (mode.id != null && mode.id.equals(modeId)) {
            return mode;
         }
      }

      return null;
   }

   private static boolean hasShutdownFlow(MachineModels.ModeData mode) {
      return mode.offTimeline != null && countSteps(mode.offTimeline) > 0;
   }

   private static int countSteps(MachineModels.Timeline timeline) {
      if (timeline != null && timeline.steps != null) {
         int count = 0;

         for (MachineModels.Step step : timeline.steps) {
            if (step != null && !step.isDelay()) {
               count++;
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   private void saveAndClose() {
      this.parent.setWorkingModeOrder(new ArrayList<>(this.order));

      int startInterval;
      try {
         startInterval = Math.max(1, Math.min(12000, Integer.parseInt(this.intervalField.getValue().trim())));
      } catch (NumberFormatException var4) {
         startInterval = 0;
      }

      this.parent.setWorkingModeInterval(startInterval);
      if (this.followStart) {
         this.parent.setWorkingStopModeOrder(new ArrayList<>(this.filterShutdownModes(this.order)));
         this.parent.setWorkingStopModeInterval(startInterval);
      } else {
         this.parent.setWorkingStopModeOrder(new ArrayList<>(this.stopOrder));

         try {
            this.parent.setWorkingStopModeInterval(Math.max(1, Math.min(12000, Integer.parseInt(this.stopIntervalField.getValue().trim()))));
         } catch (NumberFormatException var3) {
            this.parent.setWorkingStopModeInterval(0);
         }
      }

      this.parent.setWorkingStopFollowsStart(this.followStartCheckbox.selected());
      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 257 || keyEvent.key() == 335) {
         this.saveAndClose();
         return true;
      } else if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, -1);
      int colGap = 40;
      int colWidth = (this.listRight - this.listLeft - colGap) / 2;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.mode_start_order_label"), this.listLeft, this.listTop - 12, -5592406);
      guiGraphics.text(
         this.font, Component.translatable("screen.command-gui.machine.mode_stop_order_label"), this.listLeft + colWidth + colGap, this.listTop - 12, -5592406
      );
      int labelY = this.listBottom + 2;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.mode_start_interval_label"), this.listLeft, labelY, -5592406);
      guiGraphics.text(
         this.font, Component.translatable("screen.command-gui.machine.mode_stop_interval_label"), this.listLeft + colWidth + colGap, labelY, -5592406
      );
      int visibleRows = Math.max(1, (this.listBottom - this.listTop) / 20);
      int scrollbarH = this.listBottom - this.listTop;
      this.scrollbarStart = new ScrollbarHandle(this.listLeft + colWidth + 12, this.listTop, 12, scrollbarH);
      boolean hoveredStart = this.scrollbarStart.contains((double)mouseX, (double)mouseY);
      this.scrollbarStart.render(guiGraphics, this.scrollStartOffset, this.getStartMaxScroll(), visibleRows, this.getStartRows(), hoveredStart);
      this.scrollbarStop = new ScrollbarHandle(this.listRight + 6, this.listTop, 12, scrollbarH);
      boolean hoveredStop = this.scrollbarStop.contains((double)mouseX, (double)mouseY);
      this.scrollbarStop.render(guiGraphics, this.scrollStopOffset, this.getStopMaxScroll(), visibleRows, this.getStopRows(), hoveredStop);
   }
}

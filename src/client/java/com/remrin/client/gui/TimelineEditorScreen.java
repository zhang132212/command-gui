package com.remrin.client.gui;

import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineModels;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class TimelineEditorScreen extends BaseParentedScreen<Screen> {
   private static final int ROW_HEIGHT = 20;
   private static final int LIST_TOP = 26;
   private static final int BOTTOM_BAR_Y_OFFSET = 26;
   private static final int STATUS_LINE_HEIGHT = 12;
   private static final int CMD_BTN_W = 30;
   private static final int MOVE_BTN_W = 48;
   private static final int DELETE_BTN_W = 30;
   private static final int CMD_ACTIONS_WIDTH = 159;
   private final MachineModels.Timeline original;
   private final MachineModels.Timeline working;
   private final boolean requireSpawnFirst;
   private final boolean allowLoop;
   private final Runnable onApply;
   private final List<String> botNames;
   private final List<Button> rowButtons = new ArrayList<>();
   private EditBox loopCountField;
   private int scrollOffset = 0;
   private int listLeft;
   private int listRight;
   private int listBottom;
   private String loopCountText = "";
   private ScrollbarHandle scrollbar = null;
   private boolean draggingScrollbar = false;
   private double scrollbarGrabOffset = 0.0;

   public TimelineEditorScreen(
      Screen parent, Component title, MachineModels.Timeline original, boolean requireSpawnFirst, boolean allowLoop, Runnable onApply, List<String> botNames
   ) {
      super(title, parent);
      this.original = original;
      this.working = copy(original);
      this.requireSpawnFirst = requireSpawnFirst;
      this.allowLoop = allowLoop;
      this.onApply = onApply;
      this.botNames = botNames;
      this.loopCountText = String.valueOf(this.working.loopCount);
   }

   protected void init() {
      super.init();
      int listWidth = Math.min(400, this.width - 40);
      this.listLeft = (this.width - listWidth) / 2;
      this.listRight = this.listLeft + listWidth;
      this.listBottom = this.height - 26 - 4 - 12;
      this.rebuildStepButtons();
      if (!this.allowLoop) {
         this.working.loopCount = 0;
      } else {
         int loopLabelWidth = this.font.width(Component.translatable("screen.command-gui.machine.loop_count")) + 6;
         this.loopCountField = new EditBox(
            this.font, this.listLeft + loopLabelWidth, this.height - 26, 46, 18, Component.translatable("screen.command-gui.machine.loop_count")
         );
         this.loopCountField.setMaxLength(6);
         this.loopCountField.setValue(this.loopCountText);
         this.loopCountField.setHint(Component.literal("0/-1/N"));
         this.loopCountField.setResponder(text -> this.loopCountText = text);
         this.addRenderableWidget(this.loopCountField);
      }

      int addWidth = Math.min(80, listWidth / 4);
      int saveWidth = Math.min(60, listWidth / 4);
      int barTotal = addWidth + 4 + saveWidth + 4 + saveWidth;
      int barStartX = this.listRight - barTotal;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.add_step"), btn -> this.addStep())
            .bounds(barStartX, this.height - 26, addWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose())
            .bounds(barStartX + addWidth + 4, this.height - 26, saveWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.saveAndClose())
            .bounds(barStartX + addWidth + 4 + saveWidth + 4, this.height - 26, saveWidth, 18)
            .build()
      );
   }

   private void rebuildStepButtons() {
      for (Button button : this.rowButtons) {
         this.removeWidget(button);
      }

      this.rowButtons.clear();
      int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
      int start = Math.min(this.scrollOffset, Math.max(0, this.working.steps.size() - visibleRows));
      this.scrollOffset = start;

      for (int i = 0; i < visibleRows; i++) {
         int index = start + i;
         if (index >= this.working.steps.size()) {
            break;
         }

         int y = 26 + i * 20;
         int x = this.listLeft;
         MachineModels.Step entry = this.working.steps.get(index);
         List<Button> row = new ArrayList<>();
         if (entry.isDelay()) {
            int stepLabelWidth = this.listRight - this.listLeft - 159 - 18;
            int stepButtonsStart = this.listLeft + stepLabelWidth + 2;
            int delayButtonsStart = stepButtonsStart + 30 + 1;
            int labelWidth = delayButtonsStart - this.listLeft - 2;
            Button label = Button.builder(this.buildDelayLabel(index), btn -> this.editDelay(index)).bounds(x, y, labelWidth, 18).build();
            label.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.step_delay_edit_hint")));
            row.add(label);
            Button up = Button.builder(Component.translatable("screen.command-gui.step_up_short"), btn -> this.moveEntry(index, -1))
               .bounds(delayButtonsStart, y, 48, 18)
               .build();
            up.active = index > 0;
            up.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_up")));
            row.add(up);
            x = delayButtonsStart + 49;
            Button down = Button.builder(Component.translatable("screen.command-gui.step_down_short"), btn -> this.moveEntry(index, 1))
               .bounds(x, y, 48, 18)
               .build();
            down.active = index < this.working.steps.size() - 1;
            down.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_down")));
            row.add(down);
            x += 49;
            Button del = Button.builder(Component.translatable("screen.command-gui.delete"), btn -> this.deleteEntry(index)).bounds(x, y, 30, 18).build();
            del.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.delete_delay_bar")));
            row.add(del);
         } else {
            int labelWidth = this.listRight - this.listLeft - 159 - 18;
            Button label = Button.builder(this.buildCommandLabel(index), btn -> this.editStep(index)).bounds(x, y, labelWidth, 18).build();
            if (entry.description != null && !entry.description.isBlank()) {
               label.setTooltip(Tooltip.create(Component.literal(entry.description)));
            }

            row.add(label);
            x += labelWidth + 2;
            Button edit = Button.builder(Component.translatable("screen.command-gui.action.edit"), btn -> this.editStep(index)).bounds(x, y, 30, 18).build();
            row.add(edit);
            x += 31;
            Button up = Button.builder(Component.translatable("screen.command-gui.step_up_short"), btn -> this.moveEntry(index, -1))
               .bounds(x, y, 48, 18)
               .build();
            up.active = index > 0;
            up.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_up")));
            row.add(up);
            x += 49;
            Button down = Button.builder(Component.translatable("screen.command-gui.step_down_short"), btn -> this.moveEntry(index, 1))
               .bounds(x, y, 48, 18)
               .build();
            down.active = index < this.working.steps.size() - 1;
            down.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_down")));
            row.add(down);
            x += 49;
            Button del = Button.builder(Component.translatable("screen.command-gui.delete"), btn -> this.deleteEntry(index)).bounds(x, y, 30, 18).build();
            del.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.delete_step_bar")));
            row.add(del);
         }

         this.rowButtons.addAll(row);

         for (Button button : row) {
            this.addRenderableWidget(button);
         }
      }
   }

   private Component buildDelayLabel(int index) {
      MachineModels.Step entry = this.working.steps.get(index);
      int stepLabelWidth = this.listRight - this.listLeft - 159 - 18;
      int stepButtonsStart = this.listLeft + stepLabelWidth + 2;
      int delayButtonsStart = stepButtonsStart + 30 + 1;
      int delayLabelWidth = delayButtonsStart - this.listLeft - 2;
      int maxW = delayLabelWidth * 2 / 3;
      String text = Component.translatable("screen.command-gui.machine.step_delay_row", new Object[]{entry.delay}).getString();
      return Component.literal(this.font.plainSubstrByWidth(text, Math.max(30, maxW)));
   }

   private Component buildCommandLabel(int index) {
      MachineModels.Step entry = this.working.steps.get(index);
      int stepOrdinal = this.stepOrdinal(index);
      int stepLabelWidth = this.listRight - this.listLeft - 159 - 18;
      int maxW = stepLabelWidth * 2 / 3;
      String prefix;
      String suffix;
      String content;
      if (entry.description != null && !entry.description.isBlank()) {
         prefix = Component.translatable("screen.command-gui.machine.step_cmd_row_desc_prefix", new Object[]{stepOrdinal}).getString();
         suffix = "";
         content = entry.description;
      } else {
         prefix = Component.translatable("screen.command-gui.machine.step_cmd_row_prefix", new Object[]{stepOrdinal}).getString();
         suffix = ")";
         content = entry.commands != null && !entry.commands.isEmpty() ? entry.commands.get(0) : "";
      }

      int contentMaxW = Math.max(20, maxW - this.font.width(prefix) - this.font.width(suffix));
      boolean tooLong = this.font.width(content) > contentMaxW;
      if (tooLong) {
         contentMaxW = Math.max(0, contentMaxW - this.font.width("..."));
      }

      String truncated = this.font.plainSubstrByWidth(content, contentMaxW);
      if (tooLong) {
         truncated = truncated + "...";
      }

      return Component.literal(prefix + truncated + suffix);
   }

   private int stepOrdinal(int index) {
      int ordinal = 0;

      for (int i = 0; i <= index && i < this.working.steps.size(); i++) {
         if (!this.working.steps.get(i).isDelay()) {
            ordinal++;
         }
      }

      return ordinal;
   }

   private void addStep() {
      MachineModels.Step delayEntry = new MachineModels.Step();
      delayEntry.kind = "delay";
      delayEntry.delay = 1;
      delayEntry.commands = new ArrayList<>();
      this.working.steps.add(delayEntry);
      MachineModels.Step stepEntry = new MachineModels.Step();
      stepEntry.kind = "step";
      stepEntry.delay = 1;
      stepEntry.commandDelay = 1;
      stepEntry.bot = Math.max(0, this.botNames.size() - 1);
      stepEntry.commands = new ArrayList<>();
      this.working.steps.add(stepEntry);
      this.scrollToEnd();
      MachineDebug.log(
         "[Timeline.addStep] delayEntry="
            + System.identityHashCode(delayEntry)
            + " stepEntry="
            + System.identityHashCode(stepEntry)
            + " entries="
            + this.working.steps.size()
      );
      this.rebuildStepButtons();
   }

   private void scrollToEnd() {
      int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
      this.scrollOffset = Math.max(0, this.working.steps.size() - visibleRows);
   }

   private void moveEntry(int index, int delta) {
      int target = index + delta;
      if (target >= 0 && target < this.working.steps.size()) {
         MachineModels.Step entry = this.working.steps.remove(index);
         this.working.steps.add(target, entry);
         this.scrollToEnd();
         this.rebuildStepButtons();
      }
   }

   private void deleteEntry(int index) {
      this.working.steps.remove(index);
      this.rebuildStepButtons();
   }

   private void editStep(int index) {
      MachineModels.Step step = this.working.steps.get(index);
      MachineDebug.log(
         "[Timeline.editStep] index="
            + index
            + " stepHash="
            + System.identityHashCode(step)
            + " commands="
            + MachineDebug.commandsString(step.commands)
            + " workingHash="
            + System.identityHashCode(this.working)
      );
      this.minecraft.gui.setScreen(new StepEditorScreen(this, step, this.botNames, () -> this.rebuildStepButtons()));
   }

   private void editDelay(int index) {
      final MachineModels.Step entry = this.working.steps.get(index);
      NumberInputScreen picker = new NumberInputScreen(
         this, Component.translatable("screen.command-gui.machine.step_delay"), null, 1, 72000, new int[]{1, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400}
      ) {

         @Override
         protected void onNumberConfirmed(String number) {
            try {
               entry.delay = Math.max(1, Math.min(72000, Integer.parseInt(number)));
            } catch (NumberFormatException var3) {
            }

            TimelineEditorScreen.this.rebuildStepButtons();
            TimelineEditorScreen.this.minecraft.gui.setScreen(TimelineEditorScreen.this);
         }
      };
      this.minecraft.gui.setScreen(picker);
   }

   private void saveAndClose() {
      this.parseLoopCount();
      MachineDebug.log(
         "[Timeline.save] workingHash="
            + System.identityHashCode(this.working)
            + " originalHash="
            + System.identityHashCode(this.original)
            + " steps="
            + this.working.steps.size()
            + " firstCommands="
            + (this.working.steps.isEmpty() ? "[]" : MachineDebug.commandsString(this.working.steps.get(0).commands))
      );
      if (this.isModified()) {
         copyInto(this.working, this.original);
         if (this.onApply != null) {
            this.onApply.run();
         }
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   private void parseLoopCount() {
      if (!this.allowLoop) {
         this.working.loopCount = 0;
      } else {
         try {
            this.working.loopCount = Integer.parseInt(this.loopCountField.getValue().trim());
         } catch (NumberFormatException var2) {
         }

         this.working.loopCount = Math.max(-1, this.working.loopCount);
      }
   }

   private boolean isModified() {
      return !sameTimeline(this.working, this.original);
   }

   private static boolean sameTimeline(MachineModels.Timeline a, MachineModels.Timeline b) {
      if (a == null || b == null) {
         return a == b;
      }

      if (a.loopCount != b.loopCount) {
         return false;
      }

      if (a.steps == null || b.steps == null) {
         return a.steps == b.steps;
      }

      if (a.steps.size() != b.steps.size()) {
         return false;
      }

      for (int i = 0; i < a.steps.size(); i++) {
         MachineModels.Step sa = a.steps.get(i);
         MachineModels.Step sb = b.steps.get(i);
         if (sa == null || sb == null) {
            if (sa != sb) {
               return false;
            }
            continue;
         }

         if (sa.kind != null ? !sa.kind.equals(sb.kind) : sb.kind != null) {
            return false;
         }

         if (sa.delay != sb.delay || sa.commandDelay != sb.commandDelay || sa.bot != sb.bot) {
            return false;
         }

         if (!java.util.Objects.equals(sa.description, sb.description)) {
            return false;
         }

         if (!java.util.Objects.equals(sa.commands, sb.commands)) {
            return false;
         }
      }

      return true;
   }

   private static MachineModels.Timeline copy(MachineModels.Timeline timeline) {
      MachineModels.Timeline copy = new MachineModels.Timeline();
      copy.loopCount = timeline.loopCount;

      for (MachineModels.Step step : timeline.steps) {
         MachineModels.Step stepCopy = new MachineModels.Step();
         stepCopy.kind = step.kind;
         stepCopy.delay = step.delay;
         stepCopy.commandDelay = step.commandDelay;
         stepCopy.bot = step.bot;
         stepCopy.commands = step.commands != null ? new ArrayList<>(step.commands) : new ArrayList<>();
         stepCopy.description = step.description;
         copy.steps.add(stepCopy);
      }

      return copy;
   }

   private static void copyInto(MachineModels.Timeline from, MachineModels.Timeline to) {
      to.loopCount = from.loopCount;
      to.steps = new ArrayList<>();

      for (MachineModels.Step step : from.steps) {
         MachineModels.Step stepCopy = new MachineModels.Step();
         stepCopy.kind = step.kind;
         stepCopy.delay = step.delay;
         stepCopy.commandDelay = step.commandDelay;
         stepCopy.bot = step.bot;
         stepCopy.commands = step.commands != null ? new ArrayList<>(step.commands) : new ArrayList<>();
         stepCopy.description = step.description;
         to.steps.add(stepCopy);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 257 || keyCode == 335) {
         this.saveAndClose();
         return true;
      } else if (keyCode == 256) {
         this.saveAndClose();
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
      int maxScroll = Math.max(0, this.working.steps.size() - visibleRows);
      if (scrollY > 0.0 && this.scrollOffset > 0) {
         this.scrollOffset--;
         this.rebuildStepButtons();
      } else if (scrollY < 0.0 && this.scrollOffset < maxScroll) {
         this.scrollOffset++;
         this.rebuildStepButtons();
      }

      return true;
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      guiGraphics.fill(this.listLeft - 1, 25, this.listRight + 1, this.listBottom + 1, -13421773);
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, -1);
      if (this.working.steps.isEmpty()) {
         guiGraphics.centeredText(
            this.font, Component.translatable("screen.command-gui.machine.timeline_empty"), this.width / 2, (26 + this.listBottom) / 2, -7829368
         );
      }

      if (this.requireSpawnFirst) {
         Component status;
         int color;
         if (MachineModels.isSpawnCommand(MachineModels.firstCommand(this.working))) {
            status = Component.translatable("screen.command-gui.machine.spawn_ok");
            color = -11141291;
         } else {
            status = Component.translatable("screen.command-gui.machine.spawn_required");
            color = -43691;
         }

         guiGraphics.text(this.font, status, this.listLeft, this.listBottom + 2, color);
      }

      if (this.allowLoop) {
         int loopLabelWidth = this.font.width(Component.translatable("screen.command-gui.machine.loop_count")) + 6;
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.loop_count"), this.listLeft, this.height - 26 + 2, -5592406);
         guiGraphics.text(
            this.font,
            Component.translatable("screen.command-gui.machine.loop_count_hint"),
            this.listLeft + loopLabelWidth + 46 + 4,
            this.height - 26 + 2,
            -8947849
         );
      }

      int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
      int totalRows = Math.max(1, this.working.steps.size());
      int maxScroll = Math.max(0, totalRows - visibleRows);
      int scrollbarX = this.listRight - 12;
      int scrollbarH = this.listBottom - 26;
      this.scrollbar = new ScrollbarHandle(scrollbarX, 26, 12, scrollbarH);
      boolean hovered = this.scrollbar.contains((double)mouseX, (double)mouseY);
      this.scrollbar.render(guiGraphics, this.scrollOffset, maxScroll, visibleRows, totalRows, hovered);
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.isOverScrollbar(mouseEvent.x(), mouseEvent.y())) {
         int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
         int maxScroll = Math.max(0, this.working.steps.size() - visibleRows);
         int thumbTop = this.scrollbar.thumbTop(this.scrollOffset, maxScroll, visibleRows, Math.max(1, this.working.steps.size()));
         this.scrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingScrollbar && this.scrollbar != null) {
         int visibleRows = Math.max(1, (this.listBottom - 26) / 20);
         int maxScroll = Math.max(0, this.working.steps.size() - visibleRows);
         int offset = this.scrollbar.offsetFromY(mouseEvent.y(), this.scrollbarGrabOffset, maxScroll, visibleRows, Math.max(1, this.working.steps.size()));
         this.scrollOffset = Math.max(0, Math.min(offset, maxScroll));
         this.rebuildStepButtons();
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   private boolean isOverScrollbar(double mouseX, double mouseY) {
      return this.scrollbar != null && this.scrollbar.contains(mouseX, mouseY);
   }
}

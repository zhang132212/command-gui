package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class ModesEditorScreen extends BaseParentedScreen<MachineEditorScreen> {
   private static final int ROW_HEIGHT = 26;
   private static final int LIST_TOP = 26;
   private static final int BOTTOM_BAR_Y_OFFSET = 26;
   private static final int SINGLE_BTN_W = 48;
   private static final int EDIT_BTN_W = 30;
   private static final int DELETE_BTN_W = 30;
   private static final int ACTIONS_WIDTH = 110;
   private final List<MachineModels.ModeData> original;
   private final List<MachineModels.ModeData> working;
   private final List<String> botNames;
   private final Runnable onApply;
   private List<String> workingModeOrder;
   private int workingModeInterval;
   private List<String> workingStopModeOrder;
   private int workingStopModeInterval;
   private boolean workingStopFollowsStart;
   private final List<Button> rowButtons = new ArrayList<>();
   private int scrollOffset = 0;
   private int listLeft;
   private int listRight;
   private int listBottom;
   private ScrollbarHandle scrollbar = null;
   private boolean draggingScrollbar = false;
   private double scrollbarGrabOffset = 0.0;

   public ModesEditorScreen(MachineEditorScreen parent, List<MachineModels.ModeData> originalModes, List<String> botNames, Runnable onApply) {
      super(Component.translatable("screen.command-gui.machine.modes_title"), parent);
      this.original = originalModes;
      this.working = copy(originalModes);
      this.botNames = botNames;
      this.onApply = onApply;
      this.workingModeOrder = new ArrayList<>(parent.getMachine().modeOrder);
      this.workingModeInterval = parent.getMachine().modeInterval;
      this.workingStopModeOrder = new ArrayList<>(parent.getMachine().stopModeOrder);
      this.workingStopModeInterval = parent.getMachine().stopModeInterval;
      this.workingStopFollowsStart = parent.getMachine().stopFollowsStart;
   }

   protected void init() {
      super.init();
      int listWidth = Math.min(400, this.width - 40);
      this.listLeft = (this.width - listWidth) / 2;
      this.listRight = this.listLeft + listWidth;
      this.listBottom = this.height - 26 - 18;
      this.rebuildModeButtons();
      int addWidth = Math.min(80, listWidth / 4);
      int saveWidth = Math.min(60, listWidth / 4);
      int barTotal = addWidth + 4 + saveWidth + 4 + saveWidth;
      int barStartX = this.listRight - barTotal;
      int barY = this.height - 26;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.machine.multi_mode_config"), btn -> this.openMultiModeConfig())
            .bounds(this.listLeft, barY, Math.max(32, barStartX - this.listLeft - 8), 18)
            .build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.machine.add_mode"), btn -> this.addMode()).bounds(barStartX, barY, addWidth, 18).build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose())
            .bounds(barStartX + addWidth + 4, barY, saveWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.back"), btn -> this.saveAndClose())
            .bounds(barStartX + addWidth + 4 + saveWidth + 4, barY, saveWidth, 18)
            .build()
      );
   }

   private void openMultiModeConfig() {
      this.minecraft.gui.setScreen(new MultiModeConfigScreen(this));
   }

   private void rebuildModeButtons() {
      for (Button button : this.rowButtons) {
         this.removeWidget(button);
      }

      this.rowButtons.clear();
      int visibleRows = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
      int start = Math.min(this.scrollOffset, Math.max(0, this.working.size() - visibleRows));
      this.scrollOffset = start;

      for (int i = 0; i < visibleRows; i++) {
         int index = start + i;
         if (index >= this.working.size()) {
            break;
         }

         int y = LIST_TOP + i * ROW_HEIGHT;
         int labelWidth = this.listRight - this.listLeft - 110 - 16;
         int x = this.listLeft;
         this.rowButtons.add(GuiButton.themed(this.buildModeLabel(index), btn -> this.editMode(index)).bounds(x, y, labelWidth, 20).build());
         x += labelWidth + 2;
         DarkSelectButton singleBtn = new DarkSelectButton(
            x, y, 48, 20, Component.translatable("screen.command-gui.machine.mode_single_label"), b -> this.toggleSingleSelect(index)
         );
         singleBtn.setDarkSelected(() -> this.working.get(index) != null && this.working.get(index).singleSelect, -1);
         this.rowButtons.add(singleBtn);
         x += 49;
         this.rowButtons
            .add(GuiButton.themed(Component.translatable("screen.command-gui.action.edit"), btn -> this.editMode(index)).bounds(x, y, 30, 20).build());
         x += 31;
         this.rowButtons.add(GuiButton.themed(Component.translatable("screen.command-gui.delete"), btn -> this.deleteMode(index)).bounds(x, y, 30, 20).build());

         for (Button button : this.rowButtons.subList(this.rowButtons.size() - 4, this.rowButtons.size())) {
            this.addRenderableWidget(button);
         }
      }
   }

   private void toggleSingleSelect(int index) {
      MachineModels.ModeData mode = this.working.get(index);
      if (mode != null) {
         mode.singleSelect = !mode.singleSelect;
         this.rebuildModeButtons();
      }
   }

   private Component buildModeLabel(int index) {
      MachineModels.ModeData mode = this.working.get(index);
      String suffix = Component.translatable(
            "screen.command-gui.machine.mode_steps_suffix", new Object[]{countSteps(mode.onTimeline), countSteps(mode.offTimeline)}
         )
         .getString();
      return Component.literal(mode.name + suffix);
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

   private void addMode() {
      MachineModels.ModeData mode = new MachineModels.ModeData();
      mode.id = this.nextModeId();
      mode.name = Component.translatable("screen.command-gui.machine.new_mode").getString();
      this.working.add(mode);
      this.scrollOffset = Math.max(0, this.working.size() - 1);
      this.rebuildModeButtons();
      this.editMode(this.working.size() - 1);
   }

   private void deleteMode(int index) {
      MachineModels.ModeData mode = this.working.get(index);
      this.working.remove(index);
      if (mode != null && mode.id != null) {
         this.workingModeOrder.remove(mode.id);
         this.workingStopModeOrder.remove(mode.id);
      }

      this.rebuildModeButtons();
   }

   private void editMode(int index) {
      this.minecraft.gui.setScreen(new ModeEditorScreen(this, this.working.get(index), this.botNames, () -> this.rebuildModeButtons()));
   }

   private String nextModeId() {
      Set<String> ids = new HashSet<>();

      for (MachineModels.ModeData mode : this.working) {
         if (mode.id != null) {
            ids.add(mode.id);
         }
      }

      int n = 1;

      while (ids.contains("mode_" + n)) {
         n++;
      }

      return "mode_" + n;
   }

   private void saveAndClose() {
      copyInto(this.working, this.original);
      MachineModels.MachineData machine = this.parent.getMachine();
      machine.modeOrder = new ArrayList<>(this.workingModeOrder);
      machine.modeInterval = this.workingModeInterval;
      machine.stopModeOrder = new ArrayList<>(this.workingStopModeOrder);
      machine.stopModeInterval = this.workingStopModeInterval;
      machine.stopFollowsStart = this.workingStopFollowsStart;
      if (this.onApply != null) {
         this.onApply.run();
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   private static List<MachineModels.ModeData> copy(List<MachineModels.ModeData> modes) {
      List<MachineModels.ModeData> result = new ArrayList<>();
      copyInto(modes, result);
      return result;
   }

   private static void copyInto(List<MachineModels.ModeData> from, List<MachineModels.ModeData> to) {
      to.clear();

      for (MachineModels.ModeData mode : from) {
         MachineModels.ModeData copy = new MachineModels.ModeData();
         copy.id = mode.id;
         copy.name = mode.name;
         copy.singleSelect = mode.singleSelect;
         copy.switchInterval = mode.switchInterval;
         copy.detection = copyDetection(mode.detection);
         copyTimelineInto(mode.onTimeline, copy.onTimeline);
         copyTimelineInto(mode.offTimeline, copy.offTimeline);
         to.add(copy);
      }
   }

   private static MachineModels.DetectionData copyDetection(MachineModels.DetectionData detection) {
      if (detection == null) {
         return null;
      } else {
         MachineModels.DetectionData copy = new MachineModels.DetectionData();
         copy.enabled = detection.enabled;
         copy.dimension = detection.dimension;
         copy.x = detection.x;
         copy.y = detection.y;
         copy.z = detection.z;
         copy.blockId = detection.blockId;
         copy.property = detection.property;
         copy.onValues = new ArrayList<>(detection.onValues);
         copy.offValues = new ArrayList<>(detection.offValues);
         copy.ignoreValues = new ArrayList<>(detection.ignoreValues);
         return copy;
      }
   }

   private static void copyTimelineInto(MachineModels.Timeline from, MachineModels.Timeline to) {
      to.loopCount = from.loopCount;
      to.steps = new ArrayList<>();

      for (MachineModels.Step step : from.steps) {
         MachineModels.Step stepCopy = new MachineModels.Step();
         stepCopy.kind = step.kind;
         stepCopy.delay = step.delay;
         stepCopy.commandDelay = step.commandDelay;
         stepCopy.bot = step.bot;
         stepCopy.commands = new ArrayList<>(step.commands);
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
      int visibleRows = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
      int maxScroll = Math.max(0, this.working.size() - visibleRows);
      if (scrollY > 0.0 && this.scrollOffset > 0) {
         this.scrollOffset--;
         this.rebuildModeButtons();
      } else if (scrollY < 0.0 && this.scrollOffset < maxScroll) {
         this.scrollOffset++;
         this.rebuildModeButtons();
      }

      return true;
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, -1);
      if (this.working.isEmpty()) {
         guiGraphics.centeredText(
            this.font, Component.translatable("screen.command-gui.machine.modes_empty"), this.width / 2, (LIST_TOP + this.listBottom) / 2, -7829368
         );
      }

      guiGraphics.centeredText(
         this.font, Component.translatable("screen.command-gui.machine.single_select_hint"), this.width / 2, this.height - 26 - 10, -7829368
      );
      int visibleRows = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
      int totalRows = Math.max(1, this.working.size());
      int maxScroll = Math.max(0, totalRows - visibleRows);
      int scrollbarX = this.listRight - 12;
      int scrollbarH = this.listBottom - LIST_TOP;
      this.scrollbar = new ScrollbarHandle(scrollbarX, LIST_TOP, 12, scrollbarH);
      boolean hovered = this.scrollbar.contains((double)mouseX, (double)mouseY);
      this.scrollbar.render(guiGraphics, this.scrollOffset, maxScroll, visibleRows, totalRows, hovered);
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.isOverScrollbar(mouseEvent.x(), mouseEvent.y())) {
         int visibleRows = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
         int maxScroll = Math.max(0, this.working.size() - visibleRows);
         int thumbTop = this.scrollbar.thumbTop(this.scrollOffset, maxScroll, visibleRows, Math.max(1, this.working.size()));
         this.scrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingScrollbar && this.scrollbar != null) {
         int visibleRows = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
         int maxScroll = Math.max(0, this.working.size() - visibleRows);
         int offset = this.scrollbar.offsetFromY(mouseEvent.y(), this.scrollbarGrabOffset, maxScroll, visibleRows, Math.max(1, this.working.size()));
         this.scrollOffset = Math.max(0, Math.min(offset, maxScroll));
         this.rebuildModeButtons();
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

   List<MachineModels.ModeData> getWorking() {
      return this.working;
   }

   List<String> getWorkingModeOrder() {
      return this.workingModeOrder;
   }

   int getWorkingModeInterval() {
      return this.workingModeInterval;
   }

   MachineModels.MachineData getMachine() {
      return this.parent.getMachine();
   }

   List<String> getWorkingStopModeOrder() {
      return this.workingStopModeOrder;
   }

   int getWorkingStopModeInterval() {
      return this.workingStopModeInterval;
   }

   boolean isWorkingStopFollowsStart() {
      return this.workingStopFollowsStart;
   }

   void setWorkingModeOrder(List<String> order) {
      this.workingModeOrder = order;
   }

   void setWorkingModeInterval(int interval) {
      this.workingModeInterval = interval;
   }

   void setWorkingStopModeOrder(List<String> order) {
      this.workingStopModeOrder = order;
   }

   void setWorkingStopModeInterval(int interval) {
      this.workingStopModeInterval = interval;
   }

   void setWorkingStopFollowsStart(boolean value) {
      this.workingStopFollowsStart = value;
   }
}

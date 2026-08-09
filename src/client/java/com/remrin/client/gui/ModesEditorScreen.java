package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineModels.ModeData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Editor for a machine's mode list: a scrollable list of modes with reorder / edit / delete
 * actions and an add-mode button. Each mode is edited in a {@link ModeEditorScreen}, which in turn
 * reuses the timeline editor.
 * <p>
 * Edits a working copy of the list; "保存" commits it back to the machine being edited, while
 * "返回" discards changes.
 */
public class ModesEditorScreen extends BaseParentedScreen<MachineEditorScreen> {

  private static final int ROW_HEIGHT = 20;
  private static final int LIST_TOP = 26;
  private static final int BOTTOM_BAR_Y_OFFSET = 26;
  private static final int ACTIONS_WIDTH = 14 + 1 + 12 + 1 + 12 + 1 + 14 + 1 + 14;

  private final List<ModeData> original;
  private final List<ModeData> working;
  private final List<String> botNames;
  private final Runnable onApply;
  /** Working copy of the multi-mode preset (start order / interval / stop order / interval). */
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
  private double scrollbarGrabOffset = 0;

  public ModesEditorScreen(MachineEditorScreen parent, List<ModeData> originalModes,
      List<String> botNames, Runnable onApply) {
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

  @Override
  protected void init() {
    super.init();

    int listWidth = Math.min(400, this.width - 40);
    listLeft = (this.width - listWidth) / 2;
    listRight = listLeft + listWidth;
    listBottom = this.height - BOTTOM_BAR_Y_OFFSET - 4;

    rebuildModeButtons();

    int addWidth = Math.min(80, listWidth / 4);
    int saveWidth = Math.min(60, listWidth / 4);
    int barTotal = addWidth + 4 + saveWidth + 4 + saveWidth;
    int barStartX = listRight - barTotal;
    int barY = this.height - BOTTOM_BAR_Y_OFFSET;

    // Multi-mode config button on the LEFT of the bottom bar
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.multi_mode_config"),
        btn -> openMultiModeConfig()
    ).bounds(listLeft, barY, addWidth + 4 + saveWidth, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.add_mode"),
        btn -> addMode()
    ).bounds(barStartX, barY, addWidth, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX + addWidth + 4, barY, saveWidth, 18).build());

    // Back commits the mode list: edits must never be silently discarded
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> saveAndClose()
    ).bounds(barStartX + addWidth + 4 + saveWidth + 4, barY,
        saveWidth, 18).build());
  }

  private void openMultiModeConfig() {
    this.minecraft.gui.setScreen(new MultiModeConfigScreen(this));
  }

  private void rebuildModeButtons() {
    for (Button button : rowButtons) {
      this.removeWidget(button);
    }
    rowButtons.clear();

    int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    int start = Math.min(scrollOffset, Math.max(0, working.size() - visibleRows));
    scrollOffset = start;

    for (int i = 0; i < visibleRows; i++) {
      int index = start + i;
      if (index >= working.size()) {
        break;
      }
      int y = LIST_TOP + i * ROW_HEIGHT;
      // Leave room for the 12px right-edge scrollbar (same width as the main screen's)
      int labelWidth = (listRight - listLeft) - ACTIONS_WIDTH - 16;
      int x = listLeft;

      rowButtons.add(Button.builder(buildModeLabel(index),
          btn -> editMode(index)
      ).bounds(x, y, labelWidth, ROW_HEIGHT - 2).build());
      x += labelWidth + 2;

      // ✓ single-select toggle (turns dark when marked)
      final int modeIndex = index;
      DarkSelectButton singleBtn = new DarkSelectButton(x, y, 14, ROW_HEIGHT - 2,
          Component.literal("✓"), b -> toggleSingleSelect(modeIndex));
      singleBtn.setDarkSelected(
          () -> working.get(modeIndex) != null && working.get(modeIndex).singleSelect,
          0xFFFFFFFF);
      rowButtons.add(singleBtn);
      x += 14 + 1;

      rowButtons.add(Button.builder(Component.literal("↑"),
          btn -> moveMode(index, -1)
      ).bounds(x, y, 12, ROW_HEIGHT - 2).build());
      x += 12 + 1;

      rowButtons.add(Button.builder(Component.literal("↓"),
          btn -> moveMode(index, 1)
      ).bounds(x, y, 12, ROW_HEIGHT - 2).build());
      x += 12 + 1;

      rowButtons.add(Button.builder(Component.literal("✎"),
          btn -> editMode(index)
      ).bounds(x, y, 14, ROW_HEIGHT - 2).build());
      x += 14 + 1;

      rowButtons.add(Button.builder(Component.literal("✗"),
          btn -> deleteMode(index)
      ).bounds(x, y, 14, ROW_HEIGHT - 2).build());

      for (Button button : rowButtons.subList(rowButtons.size() - 6, rowButtons.size())) {
        this.addRenderableWidget(button);
      }
    }
  }

  private void toggleSingleSelect(int index) {
    ModeData mode = working.get(index);
    if (mode != null) {
      mode.singleSelect = !mode.singleSelect;
      rebuildModeButtons();
    }
  }

  private Component buildModeLabel(int index) {
    ModeData mode = working.get(index);
    String text = "#" + (index + 1) + " " + mode.name + " (开"
        + mode.onTimeline.steps.size() + "步 关" + mode.offTimeline.steps.size() + "步)";
    return Component.literal(this.font.plainSubstrByWidth(text,
        (listRight - listLeft) - ACTIONS_WIDTH - 40));
  }

  private void addMode() {
    ModeData mode = new ModeData();
    mode.id = nextModeId();
    mode.name = Component.translatable("screen.command-gui.machine.new_mode").getString();
    working.add(mode);
    scrollOffset = Math.max(0, working.size() - 1);
    rebuildModeButtons();
    editMode(working.size() - 1);
  }

  private void moveMode(int index, int delta) {
    int target = index + delta;
    if (target < 0 || target >= working.size()) {
      return;
    }
    ModeData mode = working.remove(index);
    working.add(target, mode);
    rebuildModeButtons();
  }

  private void deleteMode(int index) {
    ModeData mode = working.get(index);
    working.remove(index);
    // Keep the multi-mode presets consistent: a deleted mode must not linger in the start / stop
    // order lists (it would show up as a raw-id row in the multi-mode config screen).
    if (mode != null && mode.id != null) {
      workingModeOrder.remove(mode.id);
      workingStopModeOrder.remove(mode.id);
    }
    rebuildModeButtons();
  }

  private void editMode(int index) {
    this.minecraft.gui.setScreen(new ModeEditorScreen(
        this,
        working.get(index),
        botNames,
        () -> rebuildModeButtons()));
  }

  /**
   * Generates a unique mode id within the current list.
   */
  private String nextModeId() {
    Set<String> ids = new HashSet<>();
    for (ModeData mode : working) {
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
    copyInto(working, original);
    // Commit the multi-mode preset into the machine being edited
    MachineData machine = parent.getMachine();
    machine.modeOrder = new ArrayList<>(workingModeOrder);
    machine.modeInterval = workingModeInterval;
    machine.stopModeOrder = new ArrayList<>(workingStopModeOrder);
    machine.stopModeInterval = workingStopModeInterval;
    machine.stopFollowsStart = workingStopFollowsStart;
    if (onApply != null) {
      onApply.run();
    }
    this.minecraft.gui.setScreen(parent);
  }

  private static List<ModeData> copy(List<ModeData> modes) {
    List<ModeData> result = new ArrayList<>();
    copyInto(modes, result);
    return result;
  }

  private static void copyInto(List<ModeData> from, List<ModeData> to) {
    to.clear();
    for (ModeData mode : from) {
      ModeData copy = new ModeData();
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

  private static com.remrin.client.machine.MachineModels.DetectionData copyDetection(
      com.remrin.client.machine.MachineModels.DetectionData detection) {
    if (detection == null) {
      return null;
    }
    com.remrin.client.machine.MachineModels.DetectionData copy =
        new com.remrin.client.machine.MachineModels.DetectionData();
    copy.enabled = detection.enabled;
    copy.dimension = detection.dimension;
    copy.x = detection.x;
    copy.y = detection.y;
    copy.z = detection.z;
    copy.blockId = detection.blockId;
    copy.property = detection.property;
    copy.onValues = new ArrayList<>(detection.onValues);
    copy.offValues = new ArrayList<>(detection.offValues);
    return copy;
  }

  private static void copyTimelineInto(com.remrin.client.machine.MachineModels.Timeline from,
      com.remrin.client.machine.MachineModels.Timeline to) {
    to.loopCount = from.loopCount;
    to.steps = new ArrayList<>();
    for (com.remrin.client.machine.MachineModels.Step step : from.steps) {
      com.remrin.client.machine.MachineModels.Step stepCopy =
          new com.remrin.client.machine.MachineModels.Step();
      stepCopy.delay = step.delay;
      stepCopy.bot = step.bot;
      stepCopy.commands = new ArrayList<>(step.commands);
      to.steps.add(stepCopy);
    }
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      saveAndClose();
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      saveAndClose();
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    int maxScroll = Math.max(0, working.size() - visibleRows);
    if (scrollY > 0 && scrollOffset > 0) {
      scrollOffset--;
      rebuildModeButtons();
    } else if (scrollY < 0 && scrollOffset < maxScroll) {
      scrollOffset++;
      rebuildModeButtons();
    }
    return true;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    guiGraphics.fill(listLeft - 1, LIST_TOP - 1, listRight + 1, listBottom + 1, 0xFF333333);
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

    if (working.isEmpty()) {
      guiGraphics.centeredText(this.font,
          Component.translatable("screen.command-gui.machine.modes_empty"),
          this.width / 2, (LIST_TOP + listBottom) / 2, 0xFF888888);
    }

    // Hint about the single-select marking (below the list)
    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.machine.single_select_hint"),
        this.width / 2, this.height - BOTTOM_BAR_Y_OFFSET - 10, 0xFF888888);

    // Right-edge scrollbar for the mode list. Always drawn (full-height grey thumb when the list
    // fits entirely), matching the main screen's scrollbar convention.
    int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    int totalRows = Math.max(1, working.size());
    int maxScroll = Math.max(0, totalRows - visibleRows);
    int scrollbarX = listRight - 12;
    int scrollbarH = listBottom - LIST_TOP;
    scrollbar = new ScrollbarHandle(scrollbarX, LIST_TOP, 12, scrollbarH);
    boolean hovered = scrollbar.contains(mouseX, mouseY);
    scrollbar.render(guiGraphics, scrollOffset, maxScroll, visibleRows, totalRows, hovered);
  }

  @Override
  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      boolean focused) {
    if (mouseEvent.button() == 0 && isOverScrollbar(mouseEvent.x(), mouseEvent.y())) {
      int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
      int maxScroll = Math.max(0, working.size() - visibleRows);
      int thumbTop = scrollbar.thumbTop(scrollOffset, maxScroll, visibleRows,
          Math.max(1, working.size()));
      scrollbarGrabOffset = mouseEvent.y() - thumbTop;
      draggingScrollbar = true;
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      double dragX, double dragY) {
    if (draggingScrollbar && scrollbar != null) {
      int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
      int maxScroll = Math.max(0, working.size() - visibleRows);
      int offset = scrollbar.offsetFromY(mouseEvent.y(), scrollbarGrabOffset, maxScroll,
          visibleRows, Math.max(1, working.size()));
      scrollOffset = Math.max(0, Math.min(offset, maxScroll));
      rebuildModeButtons();
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent mouseEvent) {
    draggingScrollbar = false;
    return super.mouseReleased(mouseEvent);
  }

  private boolean isOverScrollbar(double mouseX, double mouseY) {
    return scrollbar != null && scrollbar.contains(mouseX, mouseY);
  }

  // ── Multi-mode preset accessors (used by MultiModeConfigScreen) ──

  List<ModeData> getWorking() {
    return working;
  }

  List<String> getWorkingModeOrder() {
    return workingModeOrder;
  }

  int getWorkingModeInterval() {
    return workingModeInterval;
  }

  List<String> getWorkingStopModeOrder() {
    return workingStopModeOrder;
  }

  int getWorkingStopModeInterval() {
    return workingStopModeInterval;
  }

  boolean isWorkingStopFollowsStart() {
    return workingStopFollowsStart;
  }

  void setWorkingModeOrder(List<String> order) {
    workingModeOrder = order;
  }

  void setWorkingModeInterval(int interval) {
    workingModeInterval = interval;
  }

  void setWorkingStopModeOrder(List<String> order) {
    workingStopModeOrder = order;
  }

  void setWorkingStopModeInterval(int interval) {
    workingStopModeInterval = interval;
  }

  void setWorkingStopFollowsStart(boolean value) {
    workingStopFollowsStart = value;
  }
}

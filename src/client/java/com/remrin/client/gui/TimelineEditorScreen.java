package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineModels.Step;
import com.remrin.client.machine.MachineModels.Timeline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Timeline editor screen: a scrollable list of timeline steps with reorder / edit / delete
 * actions, a loop count field, and an add-step button.
 * <p>
 * Used for the boot / shutdown processes of a machine as well as for machine mode timelines.
 * Edits a working copy of the timeline; "保存" commits the copy back to the owning screen, while
 * "返回" discards changes.
 */
public class TimelineEditorScreen extends BaseParentedScreen<Screen> {

  private static final int ROW_HEIGHT = 20;
  private static final int LIST_TOP = 26;
  private static final int BOTTOM_BAR_Y_OFFSET = 26;
  private static final int STATUS_LINE_HEIGHT = 12;
  private static final int ACTIONS_WIDTH = 12 + 1 + 12 + 1 + 14 + 1 + 14;

  private final Timeline original;
  private final Timeline working;
  private final boolean requireSpawnFirst;
  /** Whether the loop count field is shown; false for mode timelines (always run once). */
  private final boolean allowLoop;
  private final Runnable onApply;
  private final List<String> botNames;
  private final List<Button> rowButtons = new ArrayList<>();
  private EditBox loopCountField;
  private int scrollOffset = 0;
  private int listLeft;
  private int listRight;
  private int listBottom;
  /** Live text backup so typed input survives screen re-init (setScreen re-inits the target). */
  private String loopCountText = "";
  private ScrollbarHandle scrollbar = null;
  private boolean draggingScrollbar = false;
  private double scrollbarGrabOffset = 0;

  public TimelineEditorScreen(Screen parent, Component title, Timeline original,
      boolean requireSpawnFirst, boolean allowLoop, Runnable onApply, List<String> botNames) {
    super(title, parent);
    this.original = original;
    this.working = copy(original);
    this.requireSpawnFirst = requireSpawnFirst;
    this.allowLoop = allowLoop;
    this.onApply = onApply;
    this.botNames = botNames;
    this.loopCountText = String.valueOf(working.loopCount);
  }

  @Override
  protected void init() {
    super.init();

    int listWidth = Math.min(400, this.width - 40);
    listLeft = (this.width - listWidth) / 2;
    listRight = listLeft + listWidth;
    listBottom = this.height - BOTTOM_BAR_Y_OFFSET - 4 - STATUS_LINE_HEIGHT;

    rebuildStepButtons();

    // Mode timelines never loop: the loop field is hidden and loopCount stays 0 so sequenced
    // multi-mode chains always advance.
    if (!allowLoop) {
      working.loopCount = 0;
    } else {
      int loopLabelWidth = this.font.width(
          Component.translatable("screen.command-gui.machine.loop_count")) + 6;
      loopCountField = new EditBox(this.font, listLeft + loopLabelWidth,
          this.height - BOTTOM_BAR_Y_OFFSET, 46, 18,
          Component.translatable("screen.command-gui.machine.loop_count"));
      loopCountField.setMaxLength(6);
      loopCountField.setValue(loopCountText);
      loopCountField.setHint(Component.literal("0/-1/N"));
      loopCountField.setResponder(text -> loopCountText = text);
      this.addRenderableWidget(loopCountField);
    }

    int addWidth = Math.min(80, listWidth / 4);
    int saveWidth = Math.min(60, listWidth / 4);
    int barTotal = addWidth + 4 + saveWidth + 4 + saveWidth;
    int barStartX = listRight - barTotal;

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.add_step"),
        btn -> addStep()
    ).bounds(barStartX, this.height - BOTTOM_BAR_Y_OFFSET, addWidth, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX + addWidth + 4, this.height - BOTTOM_BAR_Y_OFFSET, saveWidth, 18).build());

    // Back commits the timeline: edits must never be silently discarded
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> saveAndClose()
    ).bounds(barStartX + addWidth + 4 + saveWidth + 4, this.height - BOTTOM_BAR_Y_OFFSET,
        saveWidth, 18).build());
  }

  /**
   * Rebuilds the visible step-row widgets for the current scroll offset.
   */
  private void rebuildStepButtons() {
    for (Button button : rowButtons) {
      this.removeWidget(button);
    }
    rowButtons.clear();

    int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    int start = Math.min(scrollOffset, Math.max(0, working.steps.size() - visibleRows));
    scrollOffset = start;

    for (int i = 0; i < visibleRows; i++) {
      int index = start + i;
      if (index >= working.steps.size()) {
        break;
      }
      int y = LIST_TOP + i * ROW_HEIGHT;
      // Leave room for the 12px right-edge scrollbar (same width as the main screen's)
      int labelWidth = (listRight - listLeft) - ACTIONS_WIDTH - 16;
      int x = listLeft;

      rowButtons.add(Button.builder(buildStepLabel(index),
          btn -> editStep(index)
      ).bounds(x, y, labelWidth, ROW_HEIGHT - 2).build());
      x += labelWidth + 2;

      rowButtons.add(Button.builder(Component.literal("↑"),
          btn -> moveStep(index, -1)
      ).bounds(x, y, 12, ROW_HEIGHT - 2).build());
      x += 12 + 1;

      rowButtons.add(Button.builder(Component.literal("↓"),
          btn -> moveStep(index, 1)
      ).bounds(x, y, 12, ROW_HEIGHT - 2).build());
      x += 12 + 1;

      rowButtons.add(Button.builder(Component.literal("✎"),
          btn -> editStep(index)
      ).bounds(x, y, 14, ROW_HEIGHT - 2).build());
      x += 14 + 1;

      rowButtons.add(Button.builder(Component.literal("✗"),
          btn -> deleteStep(index)
      ).bounds(x, y, 14, ROW_HEIGHT - 2).build());

      for (Button button : rowButtons.subList(rowButtons.size() - 5, rowButtons.size())) {
        this.addRenderableWidget(button);
      }
    }
  }

  private Component buildStepLabel(int index) {
    Step step = working.steps.get(index);
    String botName = step.bot >= 0 && step.bot < botNames.size() ? botNames.get(step.bot)
        : "bot" + step.bot;
    String command = step.commands == null || step.commands.isEmpty() ? "" : step.commands.get(0);
    String text = "#" + (index + 1) + " " + botName + " +" + step.delay + "t "
        + this.font.plainSubstrByWidth(command, (listRight - listLeft) - ACTIONS_WIDTH - 40);
    return Component.literal(text);
  }

  private void addStep() {
    Step step = new Step();
    step.bot = Math.max(0, botNames.size() - 1);
    working.steps.add(step);
    scrollOffset = Math.max(0, working.steps.size() - 1);
    com.remrin.client.machine.MachineDebug.log("[Timeline.addStep] stepHash="
        + System.identityHashCode(step) + " steps=" + working.steps.size());
    rebuildStepButtons();
  }

  private void moveStep(int index, int delta) {
    int target = index + delta;
    if (target < 0 || target >= working.steps.size()) {
      return;
    }
    Step step = working.steps.remove(index);
    working.steps.add(target, step);
    scrollOffset = Math.max(0, target - 1);
    rebuildStepButtons();
  }

  private void deleteStep(int index) {
    working.steps.remove(index);
    rebuildStepButtons();
  }

  private void editStep(int index) {
    Step step = working.steps.get(index);
    com.remrin.client.machine.MachineDebug.log("[Timeline.editStep] index=" + index
        + " stepHash=" + System.identityHashCode(step) + " commands="
        + com.remrin.client.machine.MachineDebug.commandsString(step.commands)
        + " workingHash=" + System.identityHashCode(working));
    this.minecraft.gui.setScreen(new StepEditorScreen(
        this,
        step,
        botNames,
        () -> rebuildStepButtons()));
  }

  private void saveAndClose() {
    parseLoopCount();
    com.remrin.client.machine.MachineDebug.log("[Timeline.save] workingHash="
        + System.identityHashCode(working) + " originalHash="
        + System.identityHashCode(original) + " steps=" + working.steps.size()
        + " firstCommands=" + (working.steps.isEmpty() ? "[]"
            : com.remrin.client.machine.MachineDebug.commandsString(
                working.steps.get(0).commands)));
    copyInto(working, original);
    if (onApply != null) {
      onApply.run();
    }
    this.minecraft.gui.setScreen(parent);
  }

  /**
   * Reads the loop count field into the working timeline (no-op for non-loop timelines, which
   * always stay at 0). Invalid input keeps the previous value.
   */
  private void parseLoopCount() {
    if (!allowLoop) {
      working.loopCount = 0;
      return;
    }
    try {
      working.loopCount = Integer.parseInt(loopCountField.getValue().trim());
    } catch (NumberFormatException ignored) {
    }
    working.loopCount = Math.max(-1, working.loopCount);
  }

  private static Timeline copy(Timeline timeline) {
    Timeline copy = new Timeline();
    copy.loopCount = timeline.loopCount;
    for (Step step : timeline.steps) {
      Step stepCopy = new Step();
      stepCopy.delay = step.delay;
      stepCopy.bot = step.bot;
      stepCopy.commands = step.commands != null ? new ArrayList<>(step.commands) : new ArrayList<>();
      copy.steps.add(stepCopy);
    }
    return copy;
  }

  private static void copyInto(Timeline from, Timeline to) {
    to.loopCount = from.loopCount;
    to.steps = new ArrayList<>();
    for (Step step : from.steps) {
      Step stepCopy = new Step();
      stepCopy.delay = step.delay;
      stepCopy.bot = step.bot;
      stepCopy.commands = step.commands != null ? new ArrayList<>(step.commands) : new ArrayList<>();
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
    int maxScroll = Math.max(0, working.steps.size() - visibleRows);
    if (scrollY > 0 && scrollOffset > 0) {
      scrollOffset--;
      rebuildStepButtons();
    } else if (scrollY < 0 && scrollOffset < maxScroll) {
      scrollOffset++;
      rebuildStepButtons();
    }
    return true;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    // Draw the list background BEFORE children so step rows render on top of it
    guiGraphics.fill(listLeft - 1, LIST_TOP - 1, listRight + 1, listBottom + 1, 0xFF333333);

    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

    if (working.steps.isEmpty()) {
      guiGraphics.centeredText(this.font,
          Component.translatable("screen.command-gui.machine.timeline_empty"),
          this.width / 2, (LIST_TOP + listBottom) / 2, 0xFF888888);
    }

    // Scripts that spawn bots must start with a fake player spawn command
    if (requireSpawnFirst) {
      Component status;
      int color;
      if (MachineModels.isSpawnCommand(MachineModels.firstCommand(working))) {
        status = Component.translatable("screen.command-gui.machine.spawn_ok");
        color = 0xFF55FF55;
      } else {
        status = Component.translatable("screen.command-gui.machine.spawn_required");
        color = 0xFFFF5555;
      }
      guiGraphics.text(this.font, status, listLeft, listBottom + 2, color);
    }

    // The loop-count label/hint only belongs to loopable timelines (machine boot/shutdown
    // processes); mode timelines never loop, so the field AND its labels are hidden there.
    if (allowLoop) {
      int loopLabelWidth = this.font.width(
          Component.translatable("screen.command-gui.machine.loop_count")) + 6;
      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.machine.loop_count"),
          listLeft, this.height - BOTTOM_BAR_Y_OFFSET + 2, 0xFFAAAAAA);
      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.machine.loop_count_hint"),
          listLeft + loopLabelWidth + 46 + 4, this.height - BOTTOM_BAR_Y_OFFSET + 2, 0xFF777777);
    }

    // Right-edge scrollbar for the step list. Always drawn — when the list fits entirely the
    // handle renders as a full-height grey thumb (same convention as the main screen's scrollbar),
    // so the user always sees a scrollbar here.
    int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
    int totalRows = Math.max(1, working.steps.size());
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
      int maxScroll = Math.max(0, working.steps.size() - visibleRows);
      int thumbTop = scrollbar.thumbTop(scrollOffset, maxScroll, visibleRows,
          Math.max(1, working.steps.size()));
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
      int maxScroll = Math.max(0, working.steps.size() - visibleRows);
      int offset = scrollbar.offsetFromY(mouseEvent.y(), scrollbarGrabOffset, maxScroll,
          visibleRows, Math.max(1, working.steps.size()));
      scrollOffset = Math.max(0, Math.min(offset, maxScroll));
      rebuildStepButtons();
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
}

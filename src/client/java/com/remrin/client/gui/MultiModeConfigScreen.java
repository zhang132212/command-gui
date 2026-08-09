package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels.ModeData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Multi-mode preset configuration: the execution priority order and tick interval for starting
 * several modes at once, the corresponding stop order / interval, and a checkbox to reuse the
 * start preset for stops. Top of each order list = executed first.
 * <p>
 * Edits the parent {@link ModesEditorScreen}'s working preset state; the machine save flow commits
 * it together with the mode list. Each column scrolls independently (mouse wheel follows the
 * hovered column, plus its own right-edge drag handle).
 */
public class MultiModeConfigScreen extends BaseParentedScreen<ModesEditorScreen> {

  private static final int ROW_HEIGHT = 20;

  private final List<ModeData> modes;
  private List<String> order = new ArrayList<>();
  private List<String> stopOrder = new ArrayList<>();
  private EditBox intervalField;
  private EditBox stopIntervalField;
  private Checkbox followStartCheckbox;
  private final List<Button> rowButtons = new ArrayList<>();
  private int listLeft;
  private int listTop;
  private int listRight;
  private int listBottom;
  /** Scroll offset (rows) of the start-order column (left). */
  private int scrollStartOffset = 0;
  /** Scroll offset (rows) of the stop-order column (right). */
  private int scrollStopOffset = 0;
  private ScrollbarHandle scrollbarStart = null;
  private ScrollbarHandle scrollbarStop = null;
  private boolean draggingStart = false;
  private boolean draggingStop = false;
  private double grabStartOffset = 0;
  private double grabStopOffset = 0;

  public MultiModeConfigScreen(ModesEditorScreen parent) {
    super(Component.translatable("screen.command-gui.machine.multi_mode_config_title"), parent);
    this.modes = parent.getWorking();
    // The editable list is the FULL execution order: preset ids first (in their saved order),
    // remaining modes appended in list order. Saving it back keeps the server behaviour identical
    // (orderedIds filters selected ids from the preset order) while every row stays movable.
    this.order = new ArrayList<>(effectiveOrder(parent.getWorkingModeOrder()));
    this.stopOrder = new ArrayList<>(effectiveOrder(parent.getWorkingStopModeOrder()));
  }

  @Override
  protected void init() {
    super.init();

    int listWidth = Math.min(360, this.width - 40);
    listLeft = (this.width - listWidth) / 2;
    listRight = listLeft + listWidth;
    listTop = 30;
    listBottom = this.height - 90;

    rebuildOrderButtons();

    // Interval fields + checkbox row (below the list), each labeled so start / stop intervals are
    // clearly distinguishable (left column = start, right column = stop).
    int y = listBottom + 12;
    int fieldW = 56;
    int fieldH = 16;
    int labelH = 10;

    int colGap = 40;
    int colWidth = (listRight - listLeft - colGap) / 2;

    int startFieldX = listLeft + colWidth - fieldW;
    intervalField = new EditBox(this.font, startFieldX, y, fieldW, fieldH,
        Component.translatable("screen.command-gui.machine.mode_start_interval_label"));
    intervalField.setMaxLength(6);
    intervalField.setValue(String.valueOf(parent.getWorkingModeInterval()));
    this.addRenderableWidget(intervalField);

    int stopFieldX = listLeft + colWidth + colGap + colWidth - fieldW;
    stopIntervalField = new EditBox(this.font, stopFieldX, y, fieldW, fieldH,
        Component.translatable("screen.command-gui.machine.mode_stop_interval_label"));
    stopIntervalField.setMaxLength(6);
    stopIntervalField.setValue(String.valueOf(parent.getWorkingStopModeInterval()));
    this.addRenderableWidget(stopIntervalField);

    followStartCheckbox = Checkbox.builder(
        Component.translatable("screen.command-gui.machine.stop_follows_start"),
        this.font
    ).pos(listLeft, y + fieldH + 12).selected(parent.isWorkingStopFollowsStart()).build();
    this.addRenderableWidget(followStartCheckbox);

    int barY = this.height - 26;
    int barWidth = 70;
    int barStartX = listLeft + (listWidth - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        b -> saveAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        b -> this.minecraft.gui.setScreen(parent)
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  /**
   * Builds two columns of modes: the start order (left) and the stop order (right), each with ↑↓
   * reorder buttons. Modes not listed keep their natural order at the end when executed. The list
   * scrolls (wheel + right-edge handle) when there are more modes than visible rows.
   */
  private void rebuildOrderButtons() {
    for (Button button : rowButtons) {
      this.removeWidget(button);
    }
    rowButtons.clear();

    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    int startMax = getStartMaxScroll(visibleRows);
    int stopMax = getStopMaxScroll(visibleRows);
    scrollStartOffset = Math.max(0, Math.min(scrollStartOffset, startMax));
    scrollStopOffset = Math.max(0, Math.min(scrollStopOffset, stopMax));

    int colGap = 40;
    int colWidth = (listRight - listLeft - colGap) / 2;
    int actionW = 12;
    // Leave room for the 12px right-edge scrollbar of the stop column (sits slightly right of the
    // list edge, with extra spacing from the last column so it reads as a scrollbar, not part of
    // the row)
    int labelW = colWidth - (actionW + 1) * 2 - 22;

    for (int col = 0; col < 2; col++) {
      List<String> list = col == 0 ? order : stopOrder;
      int colOffset = col == 0 ? scrollStartOffset : scrollStopOffset;
      int colX = listLeft + col * (colWidth + colGap);
      for (int i = colOffset; i < Math.min(colOffset + visibleRows, list.size()); i++) {
        int y = listTop + (i - colOffset) * ROW_HEIGHT;
        int x = colX;
        String modeName = modeName(list.get(i));
        rowButtons.add(Button.builder(Component.literal(
            this.font.plainSubstrByWidth(modeName, labelW)),
            b -> {
            }
        ).bounds(x, y, labelW, ROW_HEIGHT - 2).build());
        x += labelW + 2;

        final int colFinal = col;
        final int idx = i;
        rowButtons.add(Button.builder(Component.literal("↑"),
            b -> move(colFinal, idx, -1)
        ).bounds(x, y, actionW, ROW_HEIGHT - 2).build());
        x += actionW + 1;
        rowButtons.add(Button.builder(Component.literal("↓"),
            b -> move(colFinal, idx, 1)
        ).bounds(x, y, actionW, ROW_HEIGHT - 2).build());
      }
    }
    for (Button button : rowButtons) {
      this.addRenderableWidget(button);
    }
  }

  private int getStartRows() {
    return order.size();
  }

  private int getStopRows() {
    return stopOrder.size();
  }

  private int getStartMaxScroll(int visibleRows) {
    return Math.max(0, getStartRows() - visibleRows);
  }

  private int getStopMaxScroll(int visibleRows) {
    return Math.max(0, getStopRows() - visibleRows);
  }

  private int getStartMaxScroll() {
    return getStartMaxScroll(Math.max(1, (listBottom - listTop) / ROW_HEIGHT));
  }

  private int getStopMaxScroll() {
    return getStopMaxScroll(Math.max(1, (listBottom - listTop) / ROW_HEIGHT));
  }

  private void scrollStart(double delta) {
    if (delta > 0 && scrollStartOffset > 0) {
      scrollStartOffset--;
      rebuildOrderButtons();
    } else if (delta < 0 && scrollStartOffset < getStartMaxScroll()) {
      scrollStartOffset++;
      rebuildOrderButtons();
    }
  }

  private void scrollStop(double delta) {
    if (delta > 0 && scrollStopOffset > 0) {
      scrollStopOffset--;
      rebuildOrderButtons();
    } else if (delta < 0 && scrollStopOffset < getStopMaxScroll()) {
      scrollStopOffset++;
      rebuildOrderButtons();
    }
  }

  private void setStartScrollOffset(int offset) {
    scrollStartOffset = Math.max(0, Math.min(offset, getStartMaxScroll()));
    rebuildOrderButtons();
  }

  private void setStopScrollOffset(int offset) {
    scrollStopOffset = Math.max(0, Math.min(offset, getStopMaxScroll()));
    rebuildOrderButtons();
  }

  /** The column the given mouse X falls into: 0 = start (left), 1 = stop (right). */
  private int columnAtX(double mouseX) {
    int colGap = 40;
    int colWidth = (listRight - listLeft - colGap) / 2;
    return mouseX < listLeft + colWidth + colGap / 2.0 ? 0 : 1;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int column = columnAtX(mouseX);
    if (column == 0) {
      if (getStartMaxScroll() > 0) {
        scrollStart(scrollY > 0 ? 1 : -1);
        return true;
      }
    } else {
      if (getStopMaxScroll() > 0) {
        scrollStop(scrollY > 0 ? 1 : -1);
        return true;
      }
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      boolean focused) {
    if (mouseEvent.button() == 0) {
      int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
      if (scrollbarStart != null && scrollbarStart.contains(mouseEvent.x(), mouseEvent.y())
          && getStartMaxScroll() > 0) {
        int thumbTop = scrollbarStart.thumbTop(scrollStartOffset, getStartMaxScroll(),
            visibleRows, getStartRows());
        grabStartOffset = mouseEvent.y() - thumbTop;
        draggingStart = true;
        return true;
      }
      if (scrollbarStop != null && scrollbarStop.contains(mouseEvent.x(), mouseEvent.y())
          && getStopMaxScroll() > 0) {
        int thumbTop = scrollbarStop.thumbTop(scrollStopOffset, getStopMaxScroll(),
            visibleRows, getStopRows());
        grabStopOffset = mouseEvent.y() - thumbTop;
        draggingStop = true;
        return true;
      }
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      double dragX, double dragY) {
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    if (draggingStart && scrollbarStart != null) {
      int offset = scrollbarStart.offsetFromY(mouseEvent.y(), grabStartOffset,
          getStartMaxScroll(), visibleRows, getStartRows());
      setStartScrollOffset(offset);
      return true;
    }
    if (draggingStop && scrollbarStop != null) {
      int offset = scrollbarStop.offsetFromY(mouseEvent.y(), grabStopOffset,
          getStopMaxScroll(), visibleRows, getStopRows());
      setStopScrollOffset(offset);
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent mouseEvent) {
    draggingStart = false;
    draggingStop = false;
    return super.mouseReleased(mouseEvent);
  }

  private void move(int col, int index, int delta) {
    List<String> list = col == 0 ? order : stopOrder;
    if (index < 0 || index >= list.size()) {
      return;
    }
    int target = index + delta;
    if (target < 0 || target >= list.size()) {
      return;
    }
    String value = list.remove(index);
    list.add(target, value);
    rebuildOrderButtons();
  }

  private String modeName(String modeId) {
    for (ModeData mode : modes) {
      if (mode.id != null && mode.id.equals(modeId)) {
        return mode.name;
      }
    }
    return modeId;
  }

  /**
   * The full execution order shown in the editor: preset ids first (in their saved order), any
   * remaining modes appended in list order. Used once at construction so the list starts as the
   * effective order while every row stays editable afterwards.
   */
  private List<String> effectiveOrder(List<String> preset) {
    List<String> result = new ArrayList<>();
    if (preset != null) {
      result.addAll(preset);
    }
    for (ModeData mode : modes) {
      if (!result.contains(mode.id)) {
        result.add(mode.id);
      }
    }
    return result;
  }

  private void saveAndClose() {
    parent.setWorkingModeOrder(new ArrayList<>(order));
    parent.setWorkingStopModeOrder(new ArrayList<>(stopOrder));
    try {
      parent.setWorkingModeInterval(Math.max(0, Integer.parseInt(intervalField.getValue().trim())));
    } catch (NumberFormatException ignored) {
      parent.setWorkingModeInterval(0);
    }
    try {
      parent.setWorkingStopModeInterval(
          Math.max(0, Integer.parseInt(stopIntervalField.getValue().trim())));
    } catch (NumberFormatException ignored) {
      parent.setWorkingStopModeInterval(0);
    }
    parent.setWorkingStopFollowsStart(followStartCheckbox.selected());
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
      saveAndClose();
      return true;
    }
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

    guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

    int colGap = 40;
    int colWidth = (listRight - listLeft - colGap) / 2;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.mode_start_order_label"),
        listLeft, listTop - 12, 0xFFAAAAAA);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.mode_stop_order_label"),
        listLeft + colWidth + colGap, listTop - 12, 0xFFAAAAAA);

    // Interval labels above each field (aligned with their respective column)
    int labelY = listBottom + 2;
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.mode_start_interval_label"),
        listLeft + colWidth - 56, labelY, 0xFFAAAAAA);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.mode_stop_interval_label"),
        listLeft + colWidth + colGap + colWidth - 56, labelY, 0xFFAAAAAA);

    // Right-edge scrollbars for the two columns: one beside each column so the start and stop
    // orders scroll independently. Always drawn (full-height grey thumbs when a column fits
    // entirely), 12px wide like the main screen's scrollbar.
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    int scrollbarH = listBottom - listTop;
    scrollbarStart = new ScrollbarHandle(listLeft + colWidth + 12, listTop, 12, scrollbarH);
    boolean hoveredStart = scrollbarStart.contains(mouseX, mouseY);
    scrollbarStart.render(guiGraphics, scrollStartOffset, getStartMaxScroll(), visibleRows,
        getStartRows(), hoveredStart);
    // The stop-column scrollbar sits just right of the list edge (the list has no background
    // border here), closer to the window edge than the start-column one.
    scrollbarStop = new ScrollbarHandle(listRight - 8, listTop, 12, scrollbarH);
    boolean hoveredStop = scrollbarStop.contains(mouseX, mouseY);
    scrollbarStop.render(guiGraphics, scrollStopOffset, getStopMaxScroll(), visibleRows,
        getStopRows(), hoveredStop);
  }
}

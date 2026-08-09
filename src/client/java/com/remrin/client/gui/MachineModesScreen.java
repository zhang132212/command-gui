package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineModels.ModeData;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Per-machine mode selection screen. Shows the machine's modes as chips (pending selection turns
 * the chip dark + amber, like the masa-style toggles); clicking "确定" applies all pending mode
 * toggles at once via {@link MachineNetworkManager#sendSetModes}.
 * <p>
 * Single-select modes are mutually exclusive among themselves (multi-select modes are
 * unaffected); detection-enabled modes show their ⏸/▶/⚠ state and are locked while abnormal.
 * <p>
 * A "刷新检测" button forces the server to re-read the detection blocks and push a fresh sync;
 * the chip area is scrollable (mouse wheel + styled drag handle) when the machine has many modes.
 */
public class MachineModesScreen extends BaseParentedScreen<CommandGUIScreen> {

  private static final long TICK_MS = 50L;
  private static final int CHIP_HEIGHT = 18;
  private static final int CHIP_GAP = 6;
  private static final int CHIPS_PER_ROW = 3;
  /** Top of the chip grid (below the title / refresh bar). */
  private static final int GRID_TOP = 46;
  /** Bottom padding above the action bar. */
  private static final int GRID_BOTTOM_PAD = 8;
  /** Rows of chips always visible (scrollbar appears when more rows exist). */
  private static final int VISIBLE_ROWS = 6;

  private final String machineId;
  private final List<ModeData> modes = new ArrayList<>();
  private final Set<String> pending = new HashSet<>();
  /** machineId#modeId -> millis until the mode may be toggled again (local estimate). */
  private final Map<String, Long> modeCooldownUntil = new HashMap<>();
  private final List<Button> chipButtons = new ArrayList<>();
  private int listLeft;
  private int listRight;
  private int listBottom;
  /** Scroll offset (rows) for the chip grid. */
  private int gridScrollOffset = 0;
  private int chipWidth;
  private ScrollbarHandle gridScrollbar = null;
  private boolean draggingGridScrollbar = false;
  private double gridScrollbarGrabOffset = 0;
  /** Last seen machine sync version; used to reload modes when a sync arrives. */
  private int lastSyncVersion = -1;

  public MachineModesScreen(CommandGUIScreen parent, MachineData machine) {
    super(Component.translatable("screen.command-gui.machine.modes_select_title", machine.name),
        parent);
    this.machineId = machine.id;
    if (machine.modes != null) {
      this.modes.addAll(machine.modes);
    }
  }

  @Override
  protected void init() {
    super.init();

    int listWidth = Math.min(340, this.width - 40);
    listLeft = (this.width - listWidth) / 2;
    listRight = listLeft + listWidth;
    listBottom = this.height - 30;
    // Leave room for the 12px right-edge scrollbar (same width as the main screen's)
    chipWidth = (listRight - listLeft - 16 - CHIP_GAP * (CHIPS_PER_ROW - 1)) / CHIPS_PER_ROW;

    rebuildChips();

    // Refresh detection button (top-right of the chip grid)
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.refresh_detection"),
        b -> refreshDetection()
    ).bounds(listRight - 100, 24, 100, 18).build());

    // Action bar: confirm / back
    int barY = this.height - 24;
    int barWidth = Math.min(80, listWidth / 3);
    int barStartX = listLeft + (listWidth - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.confirm_modes"),
        b -> applyPending()
    ).bounds(barStartX, barY, barWidth, 18).build());
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        b -> this.minecraft.gui.setScreen(parent)
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  private void rebuildChips() {
    for (Button button : chipButtons) {
      this.removeWidget(button);
    }
    chipButtons.clear();

    int totalRows = getTotalRows();
    int maxRows = getMaxScroll();
    gridScrollOffset = Math.max(0, Math.min(gridScrollOffset, maxRows));
    int visibleRows = Math.min(VISIBLE_ROWS, totalRows);

    var font = this.minecraft.font;
    for (int i = gridScrollOffset * CHIPS_PER_ROW;
        i < Math.min(modes.size(), (gridScrollOffset + visibleRows) * CHIPS_PER_ROW); i++) {
      ModeData mode = modes.get(i);
      int gridIndex = i - gridScrollOffset * CHIPS_PER_ROW;
      int col = gridIndex % CHIPS_PER_ROW;
      int row = gridIndex / CHIPS_PER_ROW;
      int x = listLeft + col * (chipWidth + CHIP_GAP);
      int y = GRID_TOP + row * (CHIP_HEIGHT + CHIP_GAP);

      boolean modeDetectionEnabled = mode.detection != null && mode.detection.enabled;
      String prefix = "";
      int stateColor = 0xFFFFFFFF;
      boolean chipLocked = false;
      if (modeDetectionEnabled && "abnormal".equals(mode.detected)) {
        prefix = "⚠ ";
        stateColor = 0xFFFF5555;
        chipLocked = true;
      } else if (modeDetectionEnabled && "on".equals(mode.detected)) {
        prefix = "⏸ ";
        stateColor = 0xFF55FF55;
      } else if (modeDetectionEnabled) {
        prefix = "▶ ";
      } else if (mode.running) {
        prefix = "⏸ ";
        stateColor = 0xFF55FF55;
      } else {
        prefix = "▶ ";
      }
      Component chipText = Component.literal(
          font.plainSubstrByWidth(prefix + mode.name, chipWidth - 10));
      DarkSelectButton chip = new DarkSelectButton(x, y, chipWidth, CHIP_HEIGHT, chipText,
          b -> togglePending(mode));
      chip.setDarkSelected(() -> pending.contains(mode.id), 0xFFFFAA00);
      chip.setUnselectedTextColor(stateColor);
      chip.setTooltip(Tooltip.create(Component.translatable(
          "screen.command-gui.machine.chip_hint", mode.name)));
      if (chipLocked || inCooldown(modeCooldownUntil.get(mode.id))) {
        chip.active = false;
      }
      chipButtons.add(chip);
      this.addRenderableWidget(chip);
    }
  }

  private int getTotalRows() {
    return (modes.size() + CHIPS_PER_ROW - 1) / CHIPS_PER_ROW;
  }

  private int getMaxScroll() {
    return Math.max(0, getTotalRows() - VISIBLE_ROWS);
  }

  private void scrollGrid(double delta) {
    if (delta > 0 && gridScrollOffset > 0) {
      gridScrollOffset--;
      rebuildChips();
    } else if (delta < 0 && gridScrollOffset < getMaxScroll()) {
      gridScrollOffset++;
      rebuildChips();
    }
  }

  private void setGridScrollOffset(int offset) {
    gridScrollOffset = Math.max(0, Math.min(offset, getMaxScroll()));
    rebuildChips();
  }

  private boolean isOverGridScrollbar(double mouseX, double mouseY) {
    if (gridScrollbar == null) {
      return false;
    }
    return gridScrollbar.contains(mouseX, mouseY);
  }

  /**
   * Asks the server to re-read the detection blocks; the resulting sync refresh re-populates the
   * machines list, so this screen reloads its modes shortly after.
   */
  private void refreshDetection() {
    MachineNetworkManager.sendRefreshDetection(machineId);
    lastSyncVersion = MachineNetworkManager.getSyncVersion();
  }

  @Override
  public void tick() {
    super.tick();
    // When the server's sync arrives (refresh detection pushed a fresh machine list), reload the
    // modes and rebuild the chips so the ⏸/▶/⚠ detection states are current.
    int current = MachineNetworkManager.getSyncVersion();
    if (lastSyncVersion >= 0 && current != lastSyncVersion) {
      lastSyncVersion = current;
      MachineData fresh = MachineNetworkManager.getMachine(machineId);
      if (fresh != null && fresh.modes != null) {
        modes.clear();
        modes.addAll(fresh.modes);
        rebuildChips();
      }
    }
  }

  private static boolean inCooldown(Long until) {
    return until != null && System.currentTimeMillis() < until;
  }

  private void togglePending(ModeData clicked) {
    // Single-select modes are mutually exclusive: clicking one clears any other pending
    // single-select mode (multi-select modes are unaffected).
    if (clicked.singleSelect) {
      for (ModeData mode : modes) {
        if (mode.singleSelect && !mode.id.equals(clicked.id)) {
          pending.remove(mode.id);
        }
      }
    }
    if (!pending.add(clicked.id)) {
      pending.remove(clicked.id);
    }
    rebuildChips();
  }

  private void applyPending() {
    if (pending.isEmpty()) {
      this.minecraft.gui.setScreen(parent);
      return;
    }
    MachineData machine = MachineNetworkManager.getMachine(machineId);
    if (machine == null) {
      this.minecraft.gui.setScreen(parent);
      return;
    }
    long now = System.currentTimeMillis();
    int interval = machine.switchInterval;
    for (ModeData mode : machine.modes) {
      if (mode.switchInterval > 0) {
        interval = mode.switchInterval;
        break;
      }
    }
    Set<String> desired = new HashSet<>();
    if (machine.modes != null) {
      for (ModeData mode : machine.modes) {
        if (mode.running) {
          desired.add(mode.id);
        }
      }
    }
    for (String modeId : pending) {
      if (!desired.remove(modeId)) {
        desired.add(modeId);
      }
      modeCooldownUntil.put(modeId, now + interval * TICK_MS);
    }
    MachineNetworkManager.sendSetModes(machineId, new ArrayList<>(desired));
    pending.clear();
    this.minecraft.gui.setScreen(parent);
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
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (getMaxScroll() > 0) {
      scrollGrid(scrollY > 0 ? 1 : -1);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      boolean focused) {
    if (mouseEvent.button() == 0 && isOverGridScrollbar(mouseEvent.x(), mouseEvent.y())
        && getMaxScroll() > 0 && gridScrollbar != null) {
      int thumbTop = gridScrollbar.thumbTop(gridScrollOffset, getMaxScroll(), VISIBLE_ROWS,
          getTotalRows());
      gridScrollbarGrabOffset = mouseEvent.y() - thumbTop;
      draggingGridScrollbar = true;
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      double dragX, double dragY) {
    if (draggingGridScrollbar && gridScrollbar != null) {
      int offset = gridScrollbar.offsetFromY(mouseEvent.y(), gridScrollbarGrabOffset,
          getMaxScroll(), VISIBLE_ROWS, getTotalRows());
      setGridScrollOffset(offset);
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent mouseEvent) {
    draggingGridScrollbar = false;
    return super.mouseReleased(mouseEvent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

    if (modes.isEmpty()) {
      guiGraphics.centeredText(this.font,
          Component.translatable("screen.command-gui.machine.modes_empty"),
          this.width / 2, (GRID_TOP + listBottom) / 2, 0xFF888888);
      return;
    }

    // Grid scrollbar (right of the chip grid). Always drawn — when the grid fits entirely the
    // handle renders as a full-height grey thumb, matching the main screen's scrollbar convention.
    int gridTop = GRID_TOP;
    int gridH = VISIBLE_ROWS * (CHIP_HEIGHT + CHIP_GAP) - CHIP_GAP;
    int scrollbarX = listRight - 12;
    gridScrollbar = new ScrollbarHandle(scrollbarX, gridTop, 12, gridH);
    boolean hovered = gridScrollbar.contains(mouseX, mouseY);
    gridScrollbar.render(guiGraphics, gridScrollOffset, getMaxScroll(), VISIBLE_ROWS,
        getTotalRows(), hovered);
  }
}

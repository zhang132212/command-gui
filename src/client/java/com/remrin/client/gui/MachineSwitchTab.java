package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels.MachineData;
import com.remrin.client.machine.MachineModels.ModeData;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Machine switch tab, shown only when the connected server supports machine switches.
 * <p>
 * The left sidebar lists the machine categories (默认 plus the distinct categories of the saved
 * machines) and an add-machine {@code +} button (for editors). Rows are single-column (one machine
 * per row) so each row can host the switch button, the mode chips and the edit / delete actions.
 * Mode chips are clicked to mark a pending change (highlighted amber) and the footer "确定" button
 * applies all pending mode changes at once via {@link MachineNetworkManager#sendSetModes}. The
 * switch itself is one-click and always reflects the machine's actual running state.
 * <p>
 * Toggling a machine does not close the GUI — this is a switch panel.
 */
public class MachineSwitchTab extends AbstractCommandTab {

  private static final int ACTION_BTN_WIDTH = 14;
  private static final int CAT_DEL_BTN_WIDTH = 14;

  private static final ItemStack EDIT_ICON = new ItemStack(Items.WRITABLE_BOOK);

  /**
   * Shifts the whole sidebar right so the category delete (×) buttons have clean room; the
   * command rows absorb the shift (there is slack between the switch and the action cluster).
   */
  @Override
  protected int sidebarOffset() {
    return 8;
  }

  /**
   * The machine sidebar is wider than the default: it extends to roughly a quarter of the tab
   * area, giving the category buttons room to breathe.
   */
  @Override
  protected int categoryTabWidth() {
    if (area == null) {
      return CATEGORY_TAB_WIDTH;
    }
    return Math.max(CATEGORY_TAB_WIDTH, area.width() / 4);
  }

  /**
   * Horizontal margin between the sidebar's edges and the centered category button column.
   * Keep small so the buttons are as long as possible while the column stays centered between
   * the window's left edge and the sidebar's right edge.
   */
  private static final int CATEGORY_COLUMN_MARGIN = 4;

  /**
   * Left edge of the category button column. The column is centered between the game window's
   * left edge and the sidebar's right edge: the distance to the window's left border equals the
   * distance to the sidebar's right border.
   */
  private int categoryColumnX() {
    int sidebarRight = area.left() + sidebarOffset() + categoryTabWidth();
    return Math.max(0, (sidebarRight - categoryColumnWidth()) / 2);
  }

  /** Width of the centered category button column. */
  private int categoryColumnWidth() {
    return categoryTabWidth() - CATEGORY_COLUMN_MARGIN * 2;
  }
  private static final ItemStack DELETE_ICON = new ItemStack(Items.LAVA_BUCKET);
  private static final ItemStack REFRESH_ICON = new ItemStack(Items.CLOCK);
  private static final ItemStack MODES_ICON = new ItemStack(Items.IRON_PICKAXE);

  /** Currently visible machines (after search / category filtering). */
  private final List<MachineData> filteredMachines = new ArrayList<>();
  /** Action buttons (modes / refresh / edit / delete), parallel to visible machine rows. */
  private final List<Button> extraButtons = new ArrayList<>();
  /**
   * Category delete (×) buttons, PARALLEL to {@link #allCategoryButtons} (null when a category has
   * no delete button). Kept in a separate list so each × is positioned on the SAME row as its
   * category button (the base layout gives every sidebar entry its own row).
   */
  private final List<Button> allCategoryDeleteButtons = new ArrayList<>();
  /** Currently visible delete buttons (same rows as {@link #categoryButtons}). */
  private final List<Button> visibleCategoryDeleteButtons = new ArrayList<>();
  /**
   * Selected category filter: {@code null} = all machines, {@code ""} = the default category
   * (machines without a category), otherwise the exact category string.
   */
  private String selectedCategoryId = null;

  public MachineSwitchTab(Screen parent) {
    super(parent);
    buildFilteredCommands();
  }

  @Override
  public Component getTabTitle() {
    return Component.translatable("screen.command-gui.tab.machine");
  }

  @Override
  protected int getFilteredCommandCount() {
    return filteredMachines.size();
  }

  @Override
  protected void buildFilteredCommands() {
    filteredMachines.clear();
    String search = searchText;
    for (MachineData machine : MachineNetworkManager.getMachines()) {
      if (!matchesCategory(machine)) {
        continue;
      }
      if (search.isEmpty() ||
          machine.name.toLowerCase().contains(search) ||
          machine.description.toLowerCase().contains(search)) {
        filteredMachines.add(machine);
      }
    }
  }

  private boolean matchesCategory(MachineData machine) {
    String category = machine.category == null ? "" : machine.category;
    if (selectedCategoryId == null) {
      return true;
    }
    return category.equals(selectedCategoryId);
  }

  /**
   * Builds the sidebar: 默认, one button per distinct category (in order of first appearance), and
   * an add-machine {@code +} button at the bottom for editors.
   */
  @Override
  protected void buildAllCategoryButtons() {
    allCategoryButtons.clear();
    allCategoryDeleteButtons.clear();
    if (area == null) {
      return;
    }

    int x = categoryColumnX();
    int y = area.top();
    // Delete buttons are only visible to OP players (server-side machine categories are shared,
    // unlike the per-player custom / fake player configs).
    boolean canDeleteCategories = MachineNetworkManager.canConfig();
    int columnWidth = categoryColumnWidth();

    Button defaultBtn = Button.builder(
        Component.translatable("screen.command-gui.category.default"),
        btn -> onCategoryButtonClick("")
    ).bounds(x, y, columnWidth, CATEGORY_TAB_HEIGHT).build();
    defaultBtn.active = !"".equals(selectedCategoryId);
    allCategoryButtons.add(defaultBtn);
    allCategoryDeleteButtons.add(null);

    Set<String> seen = new LinkedHashSet<>();
    for (MachineData machine : MachineNetworkManager.getMachines()) {
      String category = machine.category == null ? "" : machine.category.trim();
      if (!category.isEmpty()) {
        seen.add(category);
      }
    }
    for (String category : seen) {
      // Category button + its × must fit the column width: the button narrows by the delete
      // button's space so the pair's left and right edges align with the default button.
      int catWidth = canDeleteCategories ? columnWidth - CAT_DEL_BTN_WIDTH - 1 : columnWidth;
      var font = net.minecraft.client.Minecraft.getInstance().font;
      Button catBtn = Button.builder(
          Component.literal(font.plainSubstrByWidth(category, catWidth - 4)),
          btn -> onCategoryButtonClick(category)
      ).bounds(x, y, catWidth, CATEGORY_TAB_HEIGHT).build();
      catBtn.active = !category.equals(selectedCategoryId);
      // The button text is truncated to fit; the tooltip always shows the full category name.
      catBtn.setTooltip(Tooltip.create(Component.literal(category)));
      allCategoryButtons.add(catBtn);
      if (canDeleteCategories) {
        Button delBtn = Button.builder(
            Component.literal("×"),
            btn -> MachineNetworkManager.sendClearCategory(category)
        ).bounds(x + catWidth + 1, y, CAT_DEL_BTN_WIDTH, CATEGORY_TAB_HEIGHT).build();
        delBtn.setTooltip(Tooltip.create(Component.translatable(
            "screen.command-gui.machine.delete_category", category)));
        allCategoryDeleteButtons.add(delBtn);
      } else {
        allCategoryDeleteButtons.add(null);
      }
    }

    if (MachineNetworkManager.canEdit()) {
      Button addBtn = Button.builder(
          Component.literal("+"),
          btn -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(
              new MachineEditorScreen((CommandGUIScreen) parent, null))
      ).bounds(x, y, columnWidth, CATEGORY_TAB_HEIGHT).build();
      addBtn.setTooltip(Tooltip.create(
          Component.translatable("screen.command-gui.machine.add_title")));
      allCategoryButtons.add(addBtn);
      allCategoryDeleteButtons.add(null);
    }
  }

  /**
   * Positions the delete buttons on the SAME rows as their category buttons, mirroring the custom
   * tab's sidebar pattern.
   */
  @Override
  protected void rebuildVisibleCategoryButtons() {
    removeOldCategoryDeleteButtonsFromScreen();
    visibleCategoryDeleteButtons.clear();
    super.rebuildVisibleCategoryButtons();
    if (area == null) {
      return;
    }
    int startIndex = getCategoryScrollOffset();
    int visibleCount = getVisibleCategoryCount();
    int endIndex = Math.min(startIndex + visibleCount, allCategoryDeleteButtons.size());
    for (int i = startIndex; i < endIndex; i++) {
      Button delBtn = allCategoryDeleteButtons.get(i);
      if (delBtn != null) {
        int y = area.top() + (i - startIndex) * (CATEGORY_TAB_HEIGHT + CATEGORY_TAB_GAP);
        delBtn.setY(y);
        visibleCategoryDeleteButtons.add(delBtn);
      }
    }
  }

  private void removeOldCategoryDeleteButtonsFromScreen() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : visibleCategoryDeleteButtons) {
        screen.removeTabButton(button);
      }
    }
  }

  /** Returns the visible category buttons plus their paired delete buttons. */
  @Override
  public List<Button> getCategoryButtons() {
    List<Button> all = new ArrayList<>(categoryButtons);
    all.addAll(visibleCategoryDeleteButtons);
    return all;
  }

  @Override
  protected void reRegisterCategoryButtons() {
    super.reRegisterCategoryButtons();
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : visibleCategoryDeleteButtons) {
        screen.addTabButton(button);
      }
    }
  }

  private void onCategoryButtonClick(String categoryId) {
    if (Objects.equals(selectedCategoryId, categoryId)) {
      // Clicking the active category again shows everything
      notifyCategoryChange(() -> {
        selectedCategoryId = null;
        scrollOffset = 0;
        buildFilteredCommands();
        buildAllCategoryButtons();
        rebuildVisibleCategoryButtons();
        rebuildButtons();
      });
      return;
    }
    notifyCategoryChange(() -> {
      selectedCategoryId = categoryId;
      scrollOffset = 0;
      buildFilteredCommands();
      buildAllCategoryButtons();
      rebuildVisibleCategoryButtons();
      rebuildButtons();
    });
  }

  /**
   * Not used: {@link #rebuildButtons()} builds the single-column rows directly.
   */
  @Override
  protected Button buildCommandButton(int index, int x, int y, int width, int height) {
    return null;
  }

  /**
   * Single-column row layout: each machine gets one full-width row with the switch button, its
   * mode chips and (for editors) the edit / delete icons.
   */
  @Override
  protected void rebuildButtons() {
    // Remove the previous widgets from the parent screen FIRST, otherwise stale buttons linger at
    // their old positions after a refresh (they would overlap the new rows).
    CommandGUIScreen parentScreen = (CommandGUIScreen) parent;
    for (Button button : commandButtons) {
      parentScreen.removeTabButton(button);
    }
    for (Button button : extraButtons) {
      parentScreen.removeTabButton(button);
    }
    extraButtons.clear();
    commandButtons.clear();
    if (area == null) {
      return;
    }

    int left = getCommandAreaLeft();
    int right = area.right();
    int visibleRows = Math.max(1, area.height() / ITEM_HEIGHT);
    int start = Math.min(scrollOffset, Math.max(0, filteredMachines.size() - visibleRows));
    scrollOffset = start;

    for (int i = 0; i < visibleRows; i++) {
      int index = start + i;
      if (index >= filteredMachines.size()) {
        break;
      }
      int y = area.top() + i * ITEM_HEIGHT;
      buildRow(index, left, right, y);
    }
  }

  private void buildRow(int index, int left, int right, int y) {
    MachineData machine = filteredMachines.get(index);
    int h = ITEM_HEIGHT - 2;
    boolean canEdit = MachineNetworkManager.canEdit();
    int x = left;

    // Switch button: reflects the DETECTION BLOCK state at the configured position (the machine
    // status the block indicates). Clicking shuts down when the block shows ON (⏸) and boots when
    // it shows OFF (▶); the button itself only changes when the block state changes, not directly
    // on click. During the switch lock window the button is disabled.
    var font = net.minecraft.client.Minecraft.getInstance().font;
    int switchWidth = Math.min(200, Math.max(100, font.width(machine.name) + 44));
    boolean detectionEnabled = machine.detection != null && machine.detection.enabled;
    String detected = machine.detected;
    net.minecraft.network.chat.MutableComponent label;
    boolean locked = false;
    if (detectionEnabled && "abnormal".equals(detected)) {
      label = Component.literal("⚠ " + machine.name).withColor(0xFFFF5555);
      locked = true;
    } else if (detectionEnabled && "on".equals(detected)) {
      label = Component.literal("⏸ " + machine.name).withColor(0xFF55FF55);
    } else if (detectionEnabled) {
      label = Component.literal("▶ " + machine.name).withColor(0xFFFFFFFF);
    } else if (machine.running) {
      label = Component.literal("⏸ " + machine.name).withColor(0xFF55FF55);
    } else {
      label = Component.literal("▶ " + machine.name).withColor(0xFFFFFFFF);
    }
    boolean switchCooling = inCooldown(switchCooldownUntil.get(machine.id));
    Button switchBtn = Button.builder(label, b -> toggleMachine(machine))
        .bounds(x, y, switchWidth, h)
        .tooltip(Tooltip.create(buildTooltip(machine)))
        .build();
    if (locked || switchCooling) {
      switchBtn.active = false;
    }
    commandButtons.add(switchBtn);

    // Right-side action cluster, right-aligned: [modes ⛏] [refresh 🕐] [edit ✎] [delete ✖]
    List<Button> cluster = new ArrayList<>();
    if (machine.modes != null && !machine.modes.isEmpty()) {
      ItemIconButton modesBtn = new ItemIconButton(
          0, y, ACTION_BTN_WIDTH, h,
          MODES_ICON,
          Component.translatable("screen.command-gui.machine.modes_select"),
          btn -> openModesScreen(machine));
      cluster.add(modesBtn);
    }
    if (detectionEnabled) {
      ItemIconButton refreshBtn = new ItemIconButton(
          0, y, ACTION_BTN_WIDTH, h,
          REFRESH_ICON,
          Component.translatable("screen.command-gui.machine.refresh_detection"),
          btn -> MachineNetworkManager.sendRefreshDetection(machine.id));
      cluster.add(refreshBtn);
    }
    if (canEdit) {
      boolean editLocked = isLockedByOther(machine);
      Tooltip lockTooltip = editLocked
          ? Tooltip.create(Component.translatable(
              "screen.command-gui.machine.editing_by", machine.editingBy))
          : null;
      ItemIconButton editButton = new ItemIconButton(
          0, y, ACTION_BTN_WIDTH, h,
          EDIT_ICON,
          Component.translatable("screen.command-gui.action.edit"),
          btn -> editMachine(machine));
      if (editLocked) {
        editButton.active = false;
        editButton.setTooltip(lockTooltip);
      }
      cluster.add(editButton);
      ItemIconButton deleteButton = new ItemIconButton(
          0, y, ACTION_BTN_WIDTH, h,
          DELETE_ICON,
          Component.translatable("screen.command-gui.action.delete"),
          btn -> deleteMachine(machine));
      if (editLocked) {
        deleteButton.active = false;
        deleteButton.setTooltip(lockTooltip);
      }
      cluster.add(deleteButton);
    }
    int clusterTotal = cluster.size() * ACTION_BTN_WIDTH + Math.max(0, cluster.size() - 1);
    int bx = right - clusterTotal;
    for (Button button : cluster) {
      button.setX(bx);
      extraButtons.add(button);
      bx += ACTION_BTN_WIDTH + 1;
    }
  }

  private void openModesScreen(MachineData machine) {
    net.minecraft.client.Minecraft.getInstance().gui.setScreen(
        new MachineModesScreen((CommandGUIScreen) parent, machine));
  }

  /**
   * Whether another player currently holds the hard edit lock on this machine.
   */
  private boolean isLockedByOther(MachineData machine) {
    if (machine.editingBy == null || machine.editingBy.isEmpty()) {
      return false;
    }
    var player = net.minecraft.client.Minecraft.getInstance().player;
    return player == null || !machine.editingBy.equals(player.getName().getString());
  }

  @Override
  public List<Button> getButtons() {
    List<Button> all = new ArrayList<>(commandButtons);
    all.addAll(extraButtons);
    return all;
  }

  @Override
  public int getMaxScroll() {
    if (area == null || filteredMachines.isEmpty()) {
      return 0;
    }
    int visibleRows = Math.max(1, area.height() / ITEM_HEIGHT);
    return Math.max(0, filteredMachines.size() - visibleRows);
  }

  /**
   * The machine list is laid out as a SINGLE column (one machine per row, see
   * {@link #rebuildButtons()}), so the total row count equals the filtered machine count. The
   * base class computes 3-column rows, which would make the scrollbar think the content fits and
   * refuse to draw a draggable thumb.
   */
  @Override
  public int getTotalRowCount() {
    return Math.max(1, filteredMachines.size());
  }

  public void refresh() {
    this.scrollOffset = 0;
    buildFilteredCommands();
    buildAllCategoryButtons();
    rebuildVisibleCategoryButtons();
    rebuildButtons();
  }

  /**
   * Rebuilds the visible rows after a state-only sync, preserving the scroll position and any
   * pending mode selections.
   */
  public void rebuildRows() {
    buildFilteredCommands();
    rebuildButtons();
  }

  public boolean isEmpty() {
    return filteredMachines.isEmpty();
  }

  private Component buildTooltip(MachineData machine) {
    StringBuilder sb = new StringBuilder();
    if (machine.description != null && !machine.description.isEmpty()) {
      sb.append(machine.description);
      sb.append("\n");
    }
    if (machine.editingBy != null && !machine.editingBy.isEmpty()) {
      sb.append("§e✎ ").append(machine.editingBy).append(" 正在编辑\n");
    }
    sb.append("§7Bots: ").append(String.join(", ", machine.bots)).append("\n");
    boolean detectionEnabled = machine.detection != null && machine.detection.enabled;
    if (detectionEnabled) {
      sb.append(switch (machine.detected) {
        case "on" -> "§a检测: 开机";
        case "off" -> "§f检测: 关机";
        case "abnormal" -> "§c检测: 异常（无法开关）";
        default -> "§7检测: 未知";
      }).append("\n");
      sb.append("§8").append(machine.detection.blockId).append(" @ ")
          .append(machine.detection.x).append(" ").append(machine.detection.y)
          .append(" ").append(machine.detection.z);
    } else {
      sb.append(machine.running ? "§a运行中" : "§8已停止");
    }
    return Component.literal(sb.toString());
  }

  // ── Switch lock window ───────────────────────────────────────────

  private static final long TICK_MS = 50L;
  /** machineId -> millis until the switch may be toggled again (local estimate of the server lock). */
  private final Map<String, Long> switchCooldownUntil = new HashMap<>();

  private static boolean inCooldown(Long until) {
    return until != null && System.currentTimeMillis() < until;
  }

  /**
   * Re-registers the current button set with the parent screen. Required after direct rebuilds
   * because {@link #rebuildButtons()} removes the old widgets from the screen but does not add
   * the new ones back.
   */
  private void reRegisterAll() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : getCategoryButtons()) {
        screen.addTabButton(button);
      }
      for (Button button : getButtons()) {
        screen.addTabButton(button);
      }
    }
  }

  // ── Actions ─────────────────────────────────────────────────────

  private void toggleMachine(MachineData machine) {
    switchCooldownUntil.put(machine.id,
        System.currentTimeMillis() + (long) machine.switchInterval * TICK_MS);
    MachineNetworkManager.sendToggle(machine.id);
  }

  private void editMachine(MachineData machine) {
    // Defensive lock check before opening the editor: if another player holds the hard edit lock,
    // do not even open the editing screen (the row button is disabled too, but a race could slip
    // through). The server also rejects the edit session / save.
    if (isLockedByOther(machine)) {
      return;
    }
    net.minecraft.client.Minecraft.getInstance().gui.setScreen(
        new MachineEditorScreen((CommandGUIScreen) parent, machine));
  }

  private void deleteMachine(MachineData machine) {
    MachineNetworkManager.sendDelete(machine.id);
  }
}

package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Custom command tab, displaying all commands created by the user via {@link AddCommandScreen}.
 * <p>
 * Each command row has a main {@link Button} for execution plus three small
 * {@link ItemIconButton} widgets (edit / delete / move) placed to the right.
 * Supports text search and filtering by category.
 */
public class CustomCommandTab extends AbstractCommandTab {

  /** Width of the small "×" delete button appended to each deletable category tab. */
  private static final int CAT_DEL_BTN_W = 12;
  /** Width of the category name button when a delete button is present beside it. */
  private static final int NARROW_CAT_WIDTH = CATEGORY_TAB_WIDTH - CAT_DEL_BTN_W - 1;

  /** Width of each action icon button (smaller than default to stay compact). */
  private static final int ACTION_BTN_WIDTH = 14;
  private static final int NUM_ACTION_BTNS = 3;
  private static final int ACTION_BTNS_TOTAL = NUM_ACTION_BTNS * ACTION_BTN_WIDTH + (NUM_ACTION_BTNS - 1);

  /** Item icons for the action buttons (edit / delete / move). */
  private static final ItemStack EDIT_ICON   = new ItemStack(Items.WRITABLE_BOOK);
  private static final ItemStack DELETE_ICON = new ItemStack(Items.LAVA_BUCKET);
  private static final ItemStack MOVE_ICON   = new ItemStack(Items.SHULKER_BOX);

  private final List<FilteredCommand> filteredCommands = new ArrayList<>();
  /** Extra action icon button widgets generated alongside each command button row. */
  private final List<Button> extraButtons = new ArrayList<>();
  /**
   * Parallel to {@link #allCategoryButtons}: holds the "×" delete button for each deletable
   * user category, or {@code null} for the "All" button, "+" button, and the default category.
   */
  private final List<Button> allDeleteButtons = new ArrayList<>();
  /**
   * Visible (scrolled) subset of {@link #allDeleteButtons}, kept in sync with
   * {@link #categoryButtons}.
   */
  private final List<Button> visibleDeleteButtons = new ArrayList<>();
  private String selectedCategoryId = null;
  public CustomCommandTab(Screen parent) {
    super(parent);
    buildFilteredCommands();
  }

  @Override
  public Component getTabTitle() {
    return Component.translatable("screen.command-gui.tab.custom");
  }

  @Override
  protected int getFilteredCommandCount() {
    return filteredCommands.size();
  }

  /**
   * Filters commands by search text and selected category, writing results to
   * {@link #filteredCommands}. A command matches if its name, description, or any command in its
   * list contains the search string.
   */
  @Override
  protected void buildFilteredCommands() {
    filteredCommands.clear();
    String search = searchText;
    for (CommandConfig.Category category : CommandConfig.getCategories()) {
      if (selectedCategoryId != null && !selectedCategoryId.equals(category.id)) {
        continue;
      }
      for (Map.Entry<String, CommandConfig.CommandEntry> entry : category.commands.entrySet()) {
        if (search.isEmpty() ||
            entry.getKey().toLowerCase().contains(search) ||
            entry.getValue().description.toLowerCase().contains(search) ||
            matchesAnyCommand(entry.getValue(), search)) {
          filteredCommands.add(new FilteredCommand(entry.getKey(), category.id, entry.getValue()));
        }
      }
    }
  }

  private boolean matchesAnyCommand(CommandConfig.CommandEntry entry, String search) {
    for (String cmd : entry.getCommands()) {
			if (cmd.toLowerCase().contains(search)) {
				return true;
			}
    }
    return false;
  }

  @Override
  protected void buildAllCategoryButtons() {
    allCategoryButtons.clear();
    allDeleteButtons.clear();
    if (area == null) {
      return;
    }

    int x = area.left();
    int y = area.top();

    for (CommandConfig.Category category : CommandConfig.getCategories()) {
      final String catId = category.id;
      Component btnText = category.getDisplayName() != null
          ? Component.literal(category.getDisplayName())
          : Component.translatable(category.nameKey);

      boolean isDeletable = !catId.equals("default");
      int catBtnWidth = isDeletable ? NARROW_CAT_WIDTH : CATEGORY_TAB_WIDTH;

      Button catBtn = Button.builder(btnText, btn -> onCategoryButtonClick(catId))
          .bounds(x, y, catBtnWidth, CATEGORY_TAB_HEIGHT).build();
      catBtn.active = !catId.equals(selectedCategoryId);
      allCategoryButtons.add(catBtn);

      if (isDeletable) {
        boolean hasCommands = !category.commands.isEmpty();
        Button delBtn = Button.builder(
            Component.literal("×"),
            btn -> deleteCategory(catId)
        ).bounds(x + catBtnWidth + 1, y, CAT_DEL_BTN_W, CATEGORY_TAB_HEIGHT).build();
        delBtn.active = !hasCommands;
        if (hasCommands) {
          delBtn.setTooltip(Tooltip.create(
              Component.translatable("screen.command-gui.category_not_empty")));
        } else {
          delBtn.setTooltip(Tooltip.create(
              Component.translatable("screen.command-gui.delete_category")));
        }
        allDeleteButtons.add(delBtn);
      } else {
        allDeleteButtons.add(null); // default category has no delete button
      }
    }

    Button addCatBtn = Button.builder(
        Component.literal("+"),
        btn -> openAddCategoryScreen()
    ).bounds(x, y, CATEGORY_TAB_WIDTH, CATEGORY_TAB_HEIGHT).build();
    addCatBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.add_category")));
    allCategoryButtons.add(addCatBtn);
    allDeleteButtons.add(null); // "+" has no delete button
  }

  @Override
  protected Button buildCommandButton(int index, int x, int y, int width, int height) {
    FilteredCommand cmd = filteredCommands.get(index);
    final String cmdName = cmd.name();
    final CommandConfig.CommandEntry cmdEntry = cmd.entry();

    java.util.List<String> commands = cmdEntry.getCommands();
    String commandText = String.join("\n", commands);
    String tooltipText = commandText;
    if (cmdEntry.description != null && !cmdEntry.description.isEmpty()) {
      tooltipText = cmdEntry.description + "\n§7" + commandText;
    }

    int cmdWidth = width - ACTION_BTNS_TOTAL;
    Button btn = Button.builder(Component.literal(cmdName), b -> handleCommand(cmdEntry))
        .bounds(x, y, cmdWidth, height)
        .tooltip(Tooltip.create(Component.literal(tooltipText)))
        .build();
    return btn;
  }

  @Override
  protected void onCommandButtonBuilt(int index, int x, int y, int width, int height) {
    FilteredCommand cmd = filteredCommands.get(index);
    final String cmdName = cmd.name();
    final CommandConfig.CommandEntry cmdEntry = cmd.entry();

    int cmdWidth = width - ACTION_BTNS_TOTAL;
    int actionX = x + cmdWidth;

    extraButtons.add(new ItemIconButton(
        actionX, y, ACTION_BTN_WIDTH, height,
        EDIT_ICON,
        Component.translatable("screen.command-gui.action.edit"),
        btn -> editCommand(cmdName, cmdEntry)));
    actionX += ACTION_BTN_WIDTH + 1;

    extraButtons.add(new ItemIconButton(
        actionX, y, ACTION_BTN_WIDTH, height,
        DELETE_ICON,
        Component.translatable("screen.command-gui.action.delete"),
        btn -> deleteCommand(cmdName)));
    actionX += ACTION_BTN_WIDTH + 1;

    extraButtons.add(new ItemIconButton(
        actionX, y, ACTION_BTN_WIDTH, height,
        MOVE_ICON,
        Component.translatable("screen.command-gui.action.move"),
        btn -> moveCommand(cmdName)));
  }

  @Override
  protected void rebuildButtons() {
    // The base class removes the old command buttons from the parent screen but NOT the extra
    // action buttons built by onCommandButtonBuilt — without this, stale action icons linger at
    // their old positions after a resize (fullscreen <-> windowed) or a scroll, overlapping the
    // new rows ("window-sized buttons still visible").
    removeOldExtraButtonsFromScreen();
    extraButtons.clear();
    super.rebuildButtons();
  }

  private void removeOldExtraButtonsFromScreen() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : extraButtons) {
        screen.removeTabButton(button);
      }
    }
  }

  /**
   * Keeps {@link #visibleDeleteButtons} in sync with the visible category buttons: for each
   * visible slot that has a corresponding delete button, position and collect it.
   */
  @Override
  protected void rebuildVisibleCategoryButtons() {
    // Same stale-widget problem as rebuildButtons: the base class only removes the category
    // buttons, not the paired delete buttons, so remove them explicitly before rebuilding.
    removeOldDeleteButtonsFromScreen();
    visibleDeleteButtons.clear();
    super.rebuildVisibleCategoryButtons();

    if (area == null) return;
    int startIndex = getCategoryScrollOffset();
    int visibleCount = getVisibleCategoryCount();
    int endIndex = Math.min(startIndex + visibleCount, allDeleteButtons.size());
    for (int i = startIndex; i < endIndex; i++) {
      Button delBtn = allDeleteButtons.get(i);
      if (delBtn != null) {
        int y = area.top() + (i - startIndex) * (CATEGORY_TAB_HEIGHT + CATEGORY_TAB_GAP);
        delBtn.setY(y);
        visibleDeleteButtons.add(delBtn);
      }
    }
  }

  private void removeOldDeleteButtonsFromScreen() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : visibleDeleteButtons) {
        screen.removeTabButton(button);
      }
    }
  }

  /** Returns visible category buttons plus their paired delete buttons. */
  @Override
  public List<Button> getCategoryButtons() {
    List<Button> all = new ArrayList<>(categoryButtons);
    all.addAll(visibleDeleteButtons);
    return all;
  }

  /**
   * Selects the edit screen based on the command entry type: fake player commands use
   * {@link AddFakePlayerCommandScreen}, regular commands use {@link EditCommandScreen}.
   */
  private void editCommand(String name, CommandConfig.CommandEntry entry) {
    Minecraft mc = Minecraft.getInstance();
    CommandGUIScreen parentScreen = (CommandGUIScreen) parent;
    if (isFakePlayerCommand(entry)) {
      String categoryId = CommandConfig.findCommandCategory(name);
      mc.gui.setScreen(new AddFakePlayerCommandScreen(parentScreen, categoryId, name, entry));
    } else {
      mc.gui.setScreen(new EditCommandScreen(parentScreen, name, entry));
    }
  }

  private void deleteCommand(String name) {
    CommandConfig.removeCommand(name);
    notifyCategoryChange(() -> {
      buildFilteredCommands();
      buildAllCategoryButtons();
      rebuildVisibleCategoryButtons();
      rebuildButtons();
    });
  }

  /**
   * Deletes a user category. Only allowed when the category is empty; the button is disabled when
   * the category still has commands, so this guard is a safety net.
   */
  private void deleteCategory(String categoryId) {
    CommandConfig.Category cat = CommandConfig.getCategory(categoryId);
    if (cat == null || !cat.commands.isEmpty()) {
      return; // should not happen since the button is disabled, but guard anyway
    }
    if (categoryId.equals(selectedCategoryId)) {
      selectedCategoryId = null;
    }
    CommandConfig.removeCategory(categoryId);
    notifyCategoryChange(() -> {
      buildFilteredCommands();
      buildAllCategoryButtons();
      rebuildVisibleCategoryButtons();
      rebuildButtons();
    });
  }

  private void moveCommand(String name) {
		if (CommandConfig.getCategories().size() <= 1) {
			return;
		}
    Minecraft mc = Minecraft.getInstance();
    mc.gui.setScreen(new MoveCategoryScreen((CommandGUIScreen) parent, name));
  }

  private boolean isFakePlayerCommand(CommandConfig.CommandEntry entry) {
    return CommandHelper.isFakePlayerCommand(entry);
  }

  @Override
  public List<Button> getButtons() {
    List<Button> all = new ArrayList<>(commandButtons);
    all.addAll(extraButtons);
    return all;
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

  private void openAddCategoryScreen() {
    net.minecraft.client.Minecraft.getInstance()
        .gui.setScreen(new AddCategoryScreen((CommandGUIScreen) parent));
  }

  /**
   * Executes a command or command sequence: multiple commands are sent in order via
   * {@link ChainedCommandExecutor#executeMulti}, while a single command is handled by
   * {@link ChainedCommandExecutor#execute} (including placeholder resolution).
   */
  private void handleCommand(CommandConfig.CommandEntry entry) {
    java.util.List<String> commands = entry.getCommands();
    if (commands.size() > 1) {
      ChainedCommandExecutor.executeMulti(parent, commands);
    } else if (!commands.isEmpty()) {
      ChainedCommandExecutor.execute(parent, commands.get(0));
    }
  }

  public void refresh() {
    this.scrollOffset = 0;
    buildFilteredCommands();
    buildAllCategoryButtons();
    rebuildVisibleCategoryButtons();
    rebuildButtons();
  }

  public boolean isEmpty() {
    return filteredCommands.isEmpty() && CommandConfig.getCategories().isEmpty();
  }

  public String getSelectedCategoryId() {
    return selectedCategoryId;
  }

  private record FilteredCommand(String name, String categoryId, CommandConfig.CommandEntry entry) {

  }
}

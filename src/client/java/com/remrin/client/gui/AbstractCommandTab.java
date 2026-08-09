package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Abstract base class for command tabs, providing common logic for category filtering, paginated
 * scrolling, and button layout.
 * <p>
 * Layout: the left side is a fixed-width category sidebar ({@value #CATEGORY_TAB_WIDTH}), and the
 * right side is a 3-column command button grid. Rows that overflow the visible area are controlled
 * by {@link #scrollOffset}.
 * <p>
 * Subclasses must implement {@link #buildFilteredCommands()} to build the filtered command list and
 * {@link #buildAllCategoryButtons()} to build the category button list.
 */
public abstract class AbstractCommandTab implements Tab {

  /**
   * Height of each command button row (pixels)
   */
  protected static final int ITEM_HEIGHT = 24;
  /**
   * Number of columns in the command grid
   */
  protected static final int COLUMNS = 3;
  /**
   * Width of each category button in the sidebar
   */
  protected static final int CATEGORY_TAB_WIDTH = 50;

  /**
   * Width of the category sidebar (buttons / separator / scrollbar / command area offset). The
   * default is {@link #CATEGORY_TAB_WIDTH}; subclasses may override to widen the sidebar.
   */
  protected int categoryTabWidth() {
    return CATEGORY_TAB_WIDTH;
  }
  protected static final int CATEGORY_TAB_HEIGHT = 16;
  protected static final int CATEGORY_TAB_GAP = 2;
  /**
   * Width of the category sidebar scrollbar
   */
  protected static final int CATEGORY_SCROLLBAR_WIDTH = 12;

  protected final Screen parent;
  protected final List<Button> commandButtons = new ArrayList<>();
  /**
   * Currently visible category buttons (a slice of allCategoryButtons based on scroll offset)
   */
  protected final List<Button> categoryButtons = new ArrayList<>();
  /**
   * Full list of all category buttons, including those scrolled out of view
   */
  protected final List<Button> allCategoryButtons = new ArrayList<>();
  /**
   * Vertical scroll offset for the command list (in rows)
   */
  protected int scrollOffset = 0;
  /**
   * Vertical scroll offset for the category sidebar (in entries)
   */
  protected int categoryScrollOffset = 0;
  protected String searchText = "";
  protected ScreenRectangle area;
  private Runnable onBeforeCategoryChanged;
  private Runnable onAfterCategoryChanged;

  protected AbstractCommandTab(Screen parent) {
    this.parent = parent;
  }

  protected abstract int getFilteredCommandCount();

  protected abstract void buildFilteredCommands();

  protected abstract void buildAllCategoryButtons();

  protected abstract Button buildCommandButton(int index, int x, int y, int width, int height);

  @Override
  public Component getTabExtraNarration() {
    return Component.empty();
  }

  @Override
  public Layout getLayout() {
    return new FrameLayout();
  }

  @Override
  public void visitChildren(Consumer consumer) {
    commandButtons.forEach(consumer);
    categoryButtons.forEach(consumer);
  }

  @Override
  public void doLayout(ScreenRectangle rectangle) {
    this.area = rectangle;
    buildAllCategoryButtons();
    rebuildVisibleCategoryButtons();
    rebuildButtons();
  }

  /**
   * Recalculates the Y positions of visible category buttons based on the current scroll offset and
   * updates {@link #categoryButtons}. Called whenever category data or scroll position changes.
   */
  protected void rebuildVisibleCategoryButtons() {
    removeOldCategoryButtonsFromScreen();
    categoryButtons.clear();
      if (area == null) {
          return;
      }

    int visibleCount = getVisibleCategoryCount();
    int startIndex = categoryScrollOffset;
    int endIndex = Math.min(startIndex + visibleCount, allCategoryButtons.size());

    for (int i = startIndex; i < endIndex; i++) {
      Button btn = allCategoryButtons.get(i);
      int y = area.top() + (i - startIndex) * (CATEGORY_TAB_HEIGHT + CATEGORY_TAB_GAP);
      btn.setY(y);
      categoryButtons.add(btn);
    }
  }

  /**
   * Removes the currently visible category buttons from the parent screen so a rebuild never
   * leaves stale instances behind (they would linger on other tabs).
   */
  protected void removeOldCategoryButtonsFromScreen() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : categoryButtons) {
        screen.removeTabButton(button);
      }
    }
  }

  /**
   * Re-registers the visible category buttons with the parent screen (used after direct rebuilds
   * such as sidebar scrolling, which are not wrapped by the tab change callbacks).
   */
  protected void reRegisterCategoryButtons() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : categoryButtons) {
        screen.addTabButton(button);
      }
    }
  }

  protected int getVisibleCategoryCount() {
      if (area == null) {
          return 0;
      }
    return area.height() / (CATEGORY_TAB_HEIGHT + CATEGORY_TAB_GAP);
  }

  public int getCategoryScrollOffset() {
    return categoryScrollOffset;
  }

  public int getMaxCategoryScroll() {
      if (area == null || allCategoryButtons.isEmpty()) {
          return 0;
      }
    int visibleCount = getVisibleCategoryCount();
    return Math.max(0, allCategoryButtons.size() - visibleCount);
  }

  public void scrollCategory(double delta) {
    if (area == null) {
      return;
    }
    int maxScroll = getMaxCategoryScroll();
    if (maxScroll > 0) {
      if (delta > 0 && categoryScrollOffset > 0) {
        categoryScrollOffset--;
        rebuildVisibleCategoryButtons();
        reRegisterCategoryButtons();
      } else if (delta < 0 && categoryScrollOffset < maxScroll) {
        categoryScrollOffset++;
        rebuildVisibleCategoryButtons();
        reRegisterCategoryButtons();
      }
    }
  }

  public boolean isInCategoryArea(double mouseX, double mouseY) {
      if (area == null) {
          return false;
      }
    return mouseX >= area.left() + sidebarOffset()
        && mouseX < area.left() + sidebarOffset() + categoryTabWidth() + CATEGORY_SCROLLBAR_WIDTH + 2 &&
        mouseY >= area.top() && mouseY < area.bottom();
  }

  public void setSearchText(String text) {
    this.searchText = text.toLowerCase().trim();
    this.scrollOffset = 0;
    buildFilteredCommands();
    rebuildButtons();
  }

  public void setOnCategoryChanged(Runnable before, Runnable after) {
    this.onBeforeCategoryChanged = before;
    this.onAfterCategoryChanged = after;
  }

  /**
   * Notifies of a category change: calls the before callback (to remove old buttons), runs the
   * change action, then calls the after callback (to register new buttons). Use this method when
   * switching categories to keep the parent screen's widget list in sync with the tab's button
   * lists.
   */
  protected void notifyCategoryChange(Runnable changeAction) {
      if (onBeforeCategoryChanged != null) {
          onBeforeCategoryChanged.run();
      }
    changeAction.run();
      if (onAfterCategoryChanged != null) {
          onAfterCategoryChanged.run();
      }
  }

  /**
   * Horizontal offset of the category sidebar from the area's left edge. Applied to the sidebar
   * buttons, the separator, the scrollbar and the command area's left edge so they stay aligned.
   * Subclasses can override this to shift the whole sidebar right (e.g. when delete buttons make
   * the column wider).
   */
  protected int sidebarOffset() {
    return 0;
  }

  protected int getCommandAreaLeft() {
    return area.left() + sidebarOffset() + categoryTabWidth() + CATEGORY_SCROLLBAR_WIDTH + 8;
  }

  protected int getCommandAreaWidth() {
    return area.width() - sidebarOffset() - categoryTabWidth() - CATEGORY_SCROLLBAR_WIDTH - 8;
  }

  /**
   * Rebuilds the command button list based on the current scroll offset and visible area. Buttons
   * are arranged in a COLUMNS-column grid; entries that fall below the bottom are not rendered.
   * After each button is constructed, {@link #onCommandButtonBuilt(int, int, int, int, int)} is
   * called so subclasses can add extra widgets (e.g., action icon buttons) aligned with it.
   */
  protected void rebuildButtons() {
    removeOldCommandButtonsFromScreen();
    commandButtons.clear();
      if (area == null) {
          return;
      }

    int commandAreaLeft = getCommandAreaLeft();
    int commandAreaWidth = getCommandAreaWidth();
    int colWidth = commandAreaWidth / COLUMNS;
    int y = area.top();
    int maxY = area.bottom();

    int startIndex = scrollOffset * COLUMNS;
    int visibleRows = area.height() / ITEM_HEIGHT;
    int maxItems = visibleRows * COLUMNS;
    int count = getFilteredCommandCount();

    for (int i = 0; i < Math.min(maxItems, count - startIndex); i++) {
      int index = startIndex + i;
        if (index >= count) {
            break;
        }

      int col = i % COLUMNS;
      int row = i / COLUMNS;
      int btnX = commandAreaLeft + col * colWidth;
      int btnY = y + row * ITEM_HEIGHT;
        if (btnY + ITEM_HEIGHT > maxY) {
            break;
        }

      int btnWidth = colWidth - 4;
      commandButtons.add(buildCommandButton(index, btnX + 2, btnY, btnWidth, ITEM_HEIGHT - 2));
      onCommandButtonBuilt(index, btnX + 2, btnY, btnWidth, ITEM_HEIGHT - 2);
    }
  }

  /**
   * Removes the currently visible command buttons from the parent screen so a rebuild never leaves
   * stale instances behind (they would linger on other tabs).
   */
  protected void removeOldCommandButtonsFromScreen() {
    if (parent instanceof CommandGUIScreen screen) {
      for (Button button : commandButtons) {
        screen.removeTabButton(button);
      }
    }
  }

  /**
   * Called after each command button is built during {@link #rebuildButtons()}. Subclasses can
   * override this to add extra widgets (e.g., edit/delete/move action buttons) positioned alongside
   * the command button. The default implementation does nothing.
   *
   * @param index  filtered command index
   * @param x      left edge of the command button
   * @param y      top edge of the command button
   * @param width  full column width minus padding (same value passed to buildCommandButton)
   * @param height button height (same value passed to buildCommandButton)
   */
  protected void onCommandButtonBuilt(int index, int x, int y, int width, int height) {
  }

  public void scroll(double delta) {
      if (area == null) {
          return;
      }
    int maxScroll = getMaxScroll();
    if (maxScroll > 0) {
      if (delta > 0 && scrollOffset > 0) {
        scrollOffset--;
        rebuildButtons();
      } else if (delta < 0 && scrollOffset < maxScroll) {
        scrollOffset++;
        rebuildButtons();
      }
    }
  }

  public int getScrollOffset() {
    return scrollOffset;
  }

  public void setScrollOffset(int offset) {
    int maxScroll = getMaxScroll();
    this.scrollOffset = Math.max(0, Math.min(offset, maxScroll));
    rebuildButtons();
  }

  public int getMaxScroll() {
      if (area == null || getFilteredCommandCount() == 0) {
          return 0;
      }
    int visibleRows = area.height() / ITEM_HEIGHT;
    int totalRows = (getFilteredCommandCount() + COLUMNS - 1) / COLUMNS;
    return Math.max(0, totalRows - visibleRows);
  }

  /** Number of command rows visible in the scrollable area. */
  public int getVisibleRowCount() {
      if (area == null) {
          return 1;
      }
    return Math.max(1, area.height() / ITEM_HEIGHT);
  }

  /** Total number of command rows (rounded up to full columns). */
  public int getTotalRowCount() {
    int count = getFilteredCommandCount();
    if (count == 0) {
      return 1;
    }
    return (count + COLUMNS - 1) / COLUMNS;
  }

  /** Horizontal offset of the category sidebar (see {@link #sidebarOffset()}). */
  public int getCategorySidebarOffset() {
    return sidebarOffset();
  }

  /** Total number of category entries (used for the sidebar thumb ratio). */
  public int getAllCategoryCount() {
    return allCategoryButtons.size();
  }

  public List<Button> getButtons() {
    return commandButtons;
  }

  public List<Button> getCategoryButtons() {
    return categoryButtons;
  }

  public ScreenRectangle getArea() {
    return area;
  }

  public void renderSeparator(GuiGraphicsExtractor guiGraphics) {
      if (area == null) {
          return;
      }
    int separatorX = area.left() + sidebarOffset() + categoryTabWidth() + CATEGORY_SCROLLBAR_WIDTH + 4;
    guiGraphics.fill(separatorX, area.top(), separatorX + 1, area.bottom(), 0xFF555555);
  }

  public void renderCategoryScrollbar(GuiGraphicsExtractor guiGraphics) {
      if (area == null) {
          return;
      }

    int maxScroll = getMaxCategoryScroll();
    int scrollbarX = area.left() + sidebarOffset() + categoryTabWidth() + 2;
    int scrollbarTop = area.top();
    int scrollbarHeight = area.height();

    ScrollbarHandle handle = new ScrollbarHandle(scrollbarX, scrollbarTop, CATEGORY_SCROLLBAR_WIDTH,
        scrollbarHeight);
    handle.render(guiGraphics, categoryScrollOffset, maxScroll,
        getVisibleCategoryCount(), allCategoryButtons.size(), false);
  }

  /**
   * Sets the category scroll offset directly (drag gestures), clamped to the valid range.
   */
  public void setCategoryScrollOffset(int offset) {
    int maxScroll = getMaxCategoryScroll();
    categoryScrollOffset = Math.max(0, Math.min(offset, maxScroll));
    rebuildVisibleCategoryButtons();
    reRegisterCategoryButtons();
  }
}

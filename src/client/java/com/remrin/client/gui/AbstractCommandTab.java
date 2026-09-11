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

public abstract class AbstractCommandTab implements Tab {
   protected static final int ITEM_HEIGHT = 30;
   protected static final int COLUMNS = 3;
   protected static final int CATEGORY_TAB_WIDTH = 68;
   protected static final int CATEGORY_TAB_HEIGHT = 24;
   protected static final int CATEGORY_TAB_GAP = 6;
   protected static final int CATEGORY_SCROLLBAR_WIDTH = 12;
   protected final Screen parent;
   protected final List<Button> commandButtons = new ArrayList<>();
   protected final List<Button> categoryButtons = new ArrayList<>();
   protected final List<Button> allCategoryButtons = new ArrayList<>();
   protected int scrollOffset = 0;
   protected int categoryScrollOffset = 0;
   protected String searchText = "";
   protected ScreenRectangle area;
   private Runnable onBeforeCategoryChanged;
   private Runnable onAfterCategoryChanged;


   protected int tunedItemHeight() {
      return GuiTuning.getInt("AbstractCommandTab.ITEM_HEIGHT", ITEM_HEIGHT);
   }

   protected int tunedColumns() {
      int requested = Math.max(1, GuiTuning.getInt("AbstractCommandTab.COLUMNS", COLUMNS));
      return this.area == null ? requested : Math.max(1, Math.min(requested, this.getCommandAreaWidth() / 90));
   }

   protected int tunedCategoryTabWidth() {
      return GuiTuning.getInt("AbstractCommandTab.CATEGORY_TAB_WIDTH", CATEGORY_TAB_WIDTH);
   }

   protected int tunedCategoryTabHeight() {
      return GuiTuning.getInt("AbstractCommandTab.CATEGORY_TAB_HEIGHT", CATEGORY_TAB_HEIGHT);
   }

   protected int tunedCategoryTabGap() {
      return GuiTuning.getInt("AbstractCommandTab.CATEGORY_TAB_GAP", CATEGORY_TAB_GAP);
   }

   protected int tunedCategoryScrollbarWidth() {
      return GuiTuning.getInt("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH", CATEGORY_SCROLLBAR_WIDTH);
   }

   protected int tunedCategoryCommandGap() {
      return Math.max(16, GuiTuning.getInt("AbstractCommandTab.CATEGORY_COMMAND_GAP", 16));
   }

   protected int tunedCategoryRowHeight() {
      return this.tunedCategoryTabHeight() + this.tunedCategoryTabGap();
   }

   protected int tunedItemVerticalPad() {
      return GuiTuning.getInt("AbstractCommandTab.ITEM_VERTICAL_PAD", 6);
   }

   protected int tunedItemHorizontalPad() {
      return GuiTuning.getInt("AbstractCommandTab.ITEM_HORIZONTAL_PAD", 2);
   }

   protected int tunedColumnGap() {
      return GuiTuning.getInt("AbstractCommandTab.COLUMN_GAP", 4);
   }

   protected int categoryTabWidth() {
      return this.tunedCategoryTabWidth();
   }

   protected AbstractCommandTab(Screen parent) {
      this.parent = parent;
   }

   protected abstract int getFilteredCommandCount();

   protected abstract void buildFilteredCommands();

   protected abstract void buildAllCategoryButtons();

   protected abstract Button buildCommandButton(int var1, int var2, int var3, int var4, int var5);

   public Component getTabExtraNarration() {
      return Component.empty();
   }

   public Layout getLayout() {
      return new FrameLayout();
   }

   public void visitChildren(Consumer consumer) {
      this.commandButtons.forEach(consumer);
      this.categoryButtons.forEach(consumer);
   }

   public void doLayout(ScreenRectangle rectangle) {
      this.area = rectangle;
      this.buildAllCategoryButtons();
      this.rebuildVisibleCategoryButtons();
      this.rebuildButtons();
   }

   protected void rebuildVisibleCategoryButtons() {
      this.removeOldCategoryButtonsFromScreen();
      this.categoryButtons.clear();
      if (this.area != null) {
         int visibleCount = this.getVisibleCategoryCount();
         int startIndex = this.categoryScrollOffset;
         int endIndex = Math.min(startIndex + visibleCount, this.allCategoryButtons.size());

         for (int i = startIndex; i < endIndex; i++) {
            Button btn = this.allCategoryButtons.get(i);
            int y = this.area.top() + (i - startIndex) * this.tunedCategoryRowHeight();
            btn.setY(y);
            this.categoryButtons.add(btn);
         }
      }
   }

   protected void removeOldCategoryButtonsFromScreen() {
      if (this.parent instanceof CommandGUIScreen screen) {
         for (Button button : this.categoryButtons) {
            screen.removeTabButton(button);
         }
      }
   }

   protected void reRegisterCategoryButtons() {
      if (this.parent instanceof CommandGUIScreen screen) {
         for (Button button : this.categoryButtons) {
            screen.addTabButton(button);
         }
      }
   }

   protected int getVisibleCategoryCount() {
      if (this.area == null) {
         return 0;
      }
      return this.area.height() / this.tunedCategoryRowHeight();
   }

   public int getCategoryScrollOffset() {
      return this.categoryScrollOffset;
   }

   public int getMaxCategoryScroll() {
      if (this.area != null && !this.allCategoryButtons.isEmpty()) {
         int visibleCount = this.getVisibleCategoryCount();
         return Math.max(0, this.allCategoryButtons.size() - visibleCount);
      } else {
         return 0;
      }
   }

   public void scrollCategory(double delta) {
      if (this.area != null) {
         int maxScroll = this.getMaxCategoryScroll();
         if (maxScroll > 0) {
            if (delta > 0.0 && this.categoryScrollOffset > 0) {
               this.categoryScrollOffset--;
               this.rebuildVisibleCategoryButtons();
               this.reRegisterCategoryButtons();
            } else if (delta < 0.0 && this.categoryScrollOffset < maxScroll) {
               this.categoryScrollOffset++;
               this.rebuildVisibleCategoryButtons();
               this.reRegisterCategoryButtons();
            }
         }
      }
   }

   public boolean isInCategoryArea(double mouseX, double mouseY) {
      return this.area == null
         ? false
         : mouseX >= (double)(this.area.left() + this.sidebarOffset())
            && mouseX < (double)(this.area.left() + this.sidebarOffset() + this.categoryTabWidth() + this.tunedCategoryScrollbarWidth() + this.tunedCategoryTabGap())
            && mouseY >= (double)this.area.top()
            && mouseY < (double)this.area.bottom();
   }

   public void setSearchText(String text) {
      this.searchText = text.toLowerCase().trim();
      this.scrollOffset = 0;
      this.buildFilteredCommands();
      this.rebuildButtons();
   }

   public void setOnCategoryChanged(Runnable before, Runnable after) {
      this.onBeforeCategoryChanged = before;
      this.onAfterCategoryChanged = after;
   }

   protected void notifyCategoryChange(Runnable changeAction) {
      if (this.onBeforeCategoryChanged != null) {
         this.onBeforeCategoryChanged.run();
      }

      changeAction.run();
      if (this.onAfterCategoryChanged != null) {
         this.onAfterCategoryChanged.run();
      }
   }

   protected int sidebarOffset() {
      return 0;
   }

   protected int getCommandAreaLeft() {
      return this.area.left() + this.sidebarOffset() + this.categoryTabWidth() + this.tunedCategoryScrollbarWidth() + this.tunedCategoryCommandGap();
   }

   protected int getCommandAreaWidth() {
      return this.area.width() - this.sidebarOffset() - this.categoryTabWidth() - this.tunedCategoryScrollbarWidth() - this.tunedCategoryCommandGap();
   }

   protected void rebuildButtons() {
      this.removeOldCommandButtonsFromScreen();
      this.commandButtons.clear();
      if (this.area != null) {
         int commandAreaLeft = this.getCommandAreaLeft();
         int commandAreaWidth = this.getCommandAreaWidth();
         int colWidth = Math.max(1, commandAreaWidth / this.tunedColumns());
         int y = this.area.top();
         int maxY = this.area.bottom();
         int startIndex = this.scrollOffset * this.tunedColumns();
         int visibleRows = this.area.height() / this.tunedItemHeight();
         int maxItems = visibleRows * this.tunedColumns();
         int count = this.getFilteredCommandCount();

         for (int i = 0; i < Math.min(maxItems, count - startIndex); i++) {
            int index = startIndex + i;
            if (index >= count) {
               break;
            }

            int col = i % this.tunedColumns();
            int row = i / this.tunedColumns();
            int btnX = commandAreaLeft + col * colWidth;
            int btnY = y + row * this.tunedItemHeight();
            if (btnY + this.tunedItemHeight() > maxY) {
               break;
            }

            int btnWidth = Math.max(1, colWidth - this.tunedColumnGap());
            this.commandButtons.add(this.buildCommandButton(index, btnX + this.tunedItemHorizontalPad(), btnY, btnWidth, this.tunedItemHeight() - this.tunedItemVerticalPad()));
            this.onCommandButtonBuilt(index, btnX + this.tunedItemHorizontalPad(), btnY, btnWidth, this.tunedItemHeight() - this.tunedItemVerticalPad());
         }
      }
   }

   protected void removeOldCommandButtonsFromScreen() {
      if (this.parent instanceof CommandGUIScreen screen) {
         for (Button button : this.commandButtons) {
            screen.removeTabButton(button);
         }
      }
   }

   protected void onCommandButtonBuilt(int index, int x, int y, int width, int height) {
   }

   public void scroll(double delta) {
      if (this.area != null) {
         int maxScroll = this.getMaxScroll();
         if (maxScroll > 0) {
            if (delta > 0.0 && this.scrollOffset > 0) {
               this.scrollOffset--;
               this.rebuildButtons();
            } else if (delta < 0.0 && this.scrollOffset < maxScroll) {
               this.scrollOffset++;
               this.rebuildButtons();
            }
         }
      }
   }

   public int getScrollOffset() {
      return this.scrollOffset;
   }

   public void setScrollOffset(int offset) {
      int maxScroll = this.getMaxScroll();
      this.scrollOffset = Math.max(0, Math.min(offset, maxScroll));
      this.rebuildButtons();
   }

   public int getMaxScroll() {
      if (this.area != null && this.getFilteredCommandCount() != 0) {
         int visibleRows = this.area.height() / this.tunedItemHeight();
         int totalRows = (this.getFilteredCommandCount() + this.tunedColumns() - 1) / this.tunedColumns();
         return Math.max(0, totalRows - visibleRows);
      } else {
         return 0;
      }
   }

   public int getVisibleRowCount() {
      if (this.area == null) {
         return 1;
      }
      return Math.max(1, this.area.height() / this.tunedItemHeight());
   }

   public int getTotalRowCount() {
      int count = this.getFilteredCommandCount();
      if (count == 0) {
         return 1;
      }
      return (count + this.tunedColumns() - 1) / this.tunedColumns();
   }

   public int getCategorySidebarOffset() {
      return this.sidebarOffset();
   }

   public int getAllCategoryCount() {
      return this.allCategoryButtons.size();
   }

   public List<Button> getButtons() {
      return this.commandButtons;
   }

   public List<Button> getCategoryButtons() {
      return this.categoryButtons;
   }

   public ScreenRectangle getArea() {
      return this.area;
   }

   public void renderSeparator(GuiGraphicsExtractor guiGraphics) {
      if (this.area != null) {
         int separatorX = this.area.left() + this.sidebarOffset() + this.categoryTabWidth() + this.tunedCategoryScrollbarWidth() + 4;
         guiGraphics.fill(separatorX, this.area.top(), separatorX + 1, this.area.bottom(), GuiTheme.border());
      }
   }

   public void renderCategoryScrollbar(GuiGraphicsExtractor guiGraphics) {
      if (this.area != null) {
         int maxScroll = this.getMaxCategoryScroll();
         int scrollbarX = this.area.left() + this.sidebarOffset() + this.categoryTabWidth() + this.tunedCategoryTabGap();
         int scrollbarTop = this.area.top();
         int scrollbarHeight = this.area.height();
         ScrollbarHandle handle = new ScrollbarHandle(scrollbarX, scrollbarTop, this.tunedCategoryScrollbarWidth(), scrollbarHeight);
         handle.render(guiGraphics, this.categoryScrollOffset, maxScroll, this.getVisibleCategoryCount(), this.allCategoryButtons.size(), false);
      }
   }

   public void setCategoryScrollOffset(int offset) {
      int maxScroll = this.getMaxCategoryScroll();
      this.categoryScrollOffset = Math.max(0, Math.min(offset, maxScroll));
      this.rebuildVisibleCategoryButtons();
      this.reRegisterCategoryButtons();
   }
}

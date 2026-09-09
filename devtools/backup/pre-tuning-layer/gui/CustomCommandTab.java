package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import com.remrin.client.config.SettingsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class CustomCommandTab extends AbstractCommandTab {
   private static final int EDIT_BTN_W = 28;
   private static final int MOVE_BTN_W = 28;
   private static final int DELETE_BTN_W = 28;
   private static final int MAX_CLUSTER_WIDTH = 135;
   private static final int CATEGORY_COLUMN_MARGIN = 4;
   private static final int LIST_BOTTOM_RESERVE = 18;
   private final List<CustomCommandTab.FilteredCommand> filteredCommands = new ArrayList<>();
   private final List<Button> extraButtons = new ArrayList<>();
   private Button addCategoryButton = null;
   private String selectedCategoryId = null;
   private static String rememberedCategoryId = null;
   private static int rememberedScrollOffset = 0;
   private static int rememberedCategoryScrollOffset = 0;

   public CustomCommandTab(Screen parent) {
      super(parent);
      if (SettingsConfig.getBoolean("quick_command_remember_view") && rememberedCategoryId != null && CommandConfig.getCategory(rememberedCategoryId) != null) {
         this.selectedCategoryId = rememberedCategoryId;
         this.scrollOffset = rememberedScrollOffset;
         this.categoryScrollOffset = rememberedCategoryScrollOffset;
      } else {
         this.selectedCategoryId = null;
      }

      this.buildFilteredCommands();
   }

   public Component getTabTitle() {
      return Component.translatable("screen.command-gui.tab.custom");
   }

   @Override
   protected int sidebarOffset() {
      return 8;
   }

   @Override
   protected int categoryTabWidth() {
      if (this.area == null) {
         return 50;
      }
      return Math.max(50, this.baseCategoryTabWidth() * this.categoryWidthPercent() / 100);
   }

   protected int baseCategoryTabWidth() {
      if (this.area == null) {
         return 50;
      }
      return Math.max(50, this.area.width() / 4);
   }

   private int categoryWidthPercent() {
      return 100;
   }

   private int buttonScalePercent() {
      return 100;
   }

   private int columns() {
      return 3;
   }

   @Override
   protected int getCommandAreaLeft() {
      return this.area.left() + this.sidebarOffset() + this.baseCategoryTabWidth() + 12 + 8;
   }

   @Override
   protected int getCommandAreaWidth() {
      return this.area.width() - this.sidebarOffset() - this.baseCategoryTabWidth() - 12 - 8;
   }

   private int categoryColumnX() {
      int sidebarRight = this.area.left() + this.sidebarOffset() + this.categoryTabWidth();
      return Math.max(0, (sidebarRight - this.categoryColumnWidth()) / 2);
   }

   private int categoryColumnWidth() {
      return this.categoryTabWidth() - 8;
   }

   @Override
   protected int getVisibleCategoryCount() {
      if (this.area == null) {
         return 0;
      } else {
         int rowHeight = 18;
         return Math.max(1, (this.area.height() - 18) / rowHeight);
      }
   }

   public int getSwitchX() {
      if (this.area != null) {
         return this.getCommandAreaLeft();
      }
      return 0;
   }

   public int getSwitchWidth() {
      if (this.area == null) {
         return 100;
      }
      return Math.max(24, this.baseSwitchWidth() * this.buttonScalePercent() / 100);
   }

   private int baseSwitchWidth() {
      if (this.area == null) {
         return 100;
      }
      return Math.max(60, this.area.right() - this.getCommandAreaLeft() - 12);
   }

   public int getSearchBoxWidth() {
      if (this.area == null) {
         return 100;
      }
      return Math.max(60, this.area.right() - this.getCommandAreaLeft() - 135 - 12);
   }

   public int getModesX() {
      if (this.area != null) {
         return this.area.right() - 135;
      }
      return 0;
   }

   public int getCategoryColumnX() {
      return this.categoryColumnX();
   }

   public boolean isPanelKeepOpen() {
      return SettingsConfig.getBoolean("quick_command_keep_open_default");
   }

   @Override
   protected int getFilteredCommandCount() {
      return this.filteredCommands.size();
   }

   @Override
   protected void buildFilteredCommands() {
      this.filteredCommands.clear();
      String search = this.searchText;

      for (CommandConfig.Category category : CommandConfig.getCategories()) {
         if (this.selectedCategoryId == null || this.selectedCategoryId.equals(category.id)) {
            for (Entry<String, CommandConfig.CommandEntry> entry : category.commands.entrySet()) {
               String name = entry.getKey();
               if (!CommandConfig.isPendingRemoval(name)) {
                  CommandConfig.CommandEntry effective = CommandConfig.getPendingEntry(name);
                  if (effective == null) {
                     effective = entry.getValue();
                  }

                  if (search.isEmpty() || name.toLowerCase().contains(search)) {
                     this.filteredCommands.add(new CustomCommandTab.FilteredCommand(name, category.id, effective));
                  }
               }
            }
         }
      }

      for (String name : CommandConfig.getPendingNames()) {
         CommandConfig.CommandEntry effectivex = CommandConfig.getPendingEntry(name);
         if (effectivex != null && CommandConfig.findCommandCategory(name) == null) {
            String categoryId = CommandConfig.getPendingCategory(name);
            if ((this.selectedCategoryId == null || this.selectedCategoryId.equals(categoryId)) && (search.isEmpty() || name.toLowerCase().contains(search))) {
               this.filteredCommands.add(new CustomCommandTab.FilteredCommand(name, categoryId, effectivex));
            }
         }
      }
   }

   @Override
   protected void buildAllCategoryButtons() {
      this.allCategoryButtons.clear();
      if (this.area != null) {
         if (this.addCategoryButton != null && this.parent instanceof CommandGUIScreen screen) {
            screen.removeTabButton(this.addCategoryButton);
         }

         int x = this.categoryColumnX();
         int y = this.area.top();
         int columnWidth = this.categoryColumnWidth();
         Font font = Minecraft.getInstance().font;

         for (CommandConfig.Category category : CommandConfig.getCategories()) {
            String catId = category.id;
            String displayName = category.getDisplayName();
            boolean isDeletable = !catId.equals("default");
            boolean nameTruncated = displayName != null && font.width(displayName) > columnWidth * 2 / 3;
            Component btnText = displayName != null
               ? Component.literal(truncate(displayName, columnWidth * 2 / 3, font))
               : Component.translatable(category.nameKey);
            DarkSelectButton catBtn = new DarkSelectButton(x, y, columnWidth, 16, btnText, btn -> this.onCategoryButtonClick(catId));
            catBtn.setDarkSelected(() -> Objects.equals(this.selectedCategoryId, catId), -1);
            if (nameTruncated && displayName != null) {
               catBtn.setTooltip(Tooltip.create(Component.literal("名称:" + displayName)));
            }

            if (isDeletable) {
               catBtn.setOnRightClick(() -> this.openEditCategoryScreen(catId));
               String tip = (nameTruncated && displayName != null ? "名称:" + displayName + "\n" : "")
                  + Component.translatable("screen.command-gui.category_right_click_edit").getString();
               catBtn.setTooltip(Tooltip.create(Component.literal(tip)));
            }

            this.allCategoryButtons.add(catBtn);
         }

         this.addCategoryButton = Button.builder(Component.literal("+"), btn -> this.openAddCategoryScreen()).bounds(x, 0, columnWidth, 16).build();
         this.addCategoryButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.add_category")));
      }
   }

   private static String truncate(String text, int maxWidth, Font font) {
      if (font.width(text) <= maxWidth) {
         return text;
      }
      return font.plainSubstrByWidth(text, maxWidth) + "...";
   }

   @Override
   protected void rebuildVisibleCategoryButtons() {
      super.rebuildVisibleCategoryButtons();
      if (this.area != null) {
         if (this.addCategoryButton != null) {
            this.addCategoryButton.setX(this.categoryColumnX());
            this.addCategoryButton.setY(this.area.bottom() - 16);
            this.addCategoryButton.setWidth(this.categoryColumnWidth());
         }
      }
   }

   @Override
   public List<Button> getCategoryButtons() {
      List<Button> all = new ArrayList<>(this.categoryButtons);
      if (this.addCategoryButton != null) {
         all.add(this.addCategoryButton);
      }

      return all;
   }

   @Override
   protected void reRegisterCategoryButtons() {
      super.reRegisterCategoryButtons();
      if (this.parent instanceof CommandGUIScreen screen && this.addCategoryButton != null) {
         screen.addTabButton(this.addCategoryButton);
      }
   }

   @Override
   protected Button buildCommandButton(int index, int x, int y, int width, int height) {
      return null;
   }

   @Override
   protected void rebuildButtons() {
      CommandGUIScreen parentScreen = (CommandGUIScreen)this.parent;

      for (Button button : this.commandButtons) {
         parentScreen.removeTabButton(button);
      }

      for (Button button : this.extraButtons) {
         parentScreen.removeTabButton(button);
      }

      this.extraButtons.clear();
      this.commandButtons.clear();
      if (this.area != null) {
         int left = this.getCommandAreaLeft();
         int right = this.area.right();
         int contentHeight = Math.max(1, this.area.height());
         int rowStep = this.scaledRowHeight();
         int visibleRows = Math.max(1, contentHeight / rowStep);
         int cols = this.columns();
         int totalRows = (this.filteredCommands.size() + cols - 1) / cols;
         int start = Math.min(this.scrollOffset, Math.max(0, totalRows - visibleRows));
         this.scrollOffset = start;
         int columnGap = Math.max(4, 8 * this.buttonScalePercent() / 100);
         int groupWidth = (right - left - columnGap * (cols - 1)) / cols;

         for (int i = 0; i < visibleRows; i++) {
            for (int c = 0; c < cols; c++) {
               int index = (start + i) * cols + c;
               if (index >= this.filteredCommands.size()) {
                  break;
               }

               int y = this.area.top() + i * rowStep;
               int groupLeft = left + c * (groupWidth + columnGap);
               this.buildRow(index, groupLeft, groupLeft + groupWidth, y, rowStep);
            }
         }
      }
   }

   private int scaledRowHeight() {
      int contentHeight = Math.max(1, this.area == null ? 0 : this.area.height());
      return Math.max(12, contentHeight / 7);
   }

   private void buildRow(int index, int left, int right, int y, int rowHeight) {
      CustomCommandTab.FilteredCommand cmd = this.filteredCommands.get(index);
      String cmdName = cmd.name();
      CommandConfig.CommandEntry cmdEntry = cmd.entry();
      int scale = this.buttonScalePercent();
      int h = Math.max(10, rowHeight - 2);
      int commandWidth;
      if (this.columns() > 1) {
         commandWidth = Math.max(20, (right - left) * scale / 100 - 4);
      } else {
         commandWidth = this.getSwitchWidth();
      }

      List<String> commands = cmdEntry.getCommands();
      String commandText = String.join("\n", commands);
      String tooltipText = "§7" + commands.size() + " 步";
      if (cmdEntry.description != null && !cmdEntry.description.isEmpty()) {
         tooltipText = cmdEntry.description + "\n" + tooltipText + "\n" + commandText;
      } else {
         tooltipText = tooltipText + "\n" + commandText;
      }

      Font font = Minecraft.getInstance().font;
      int textMaxW = commandWidth * 2 / 3;
      String label = commands.size() > 1 ? cmdName + " (" + commands.size() + ")" : cmdName;
      boolean pending = CommandConfig.isPending(cmdName) && !CommandConfig.isPendingRemoval(cmdName);
      if (pending) {
         label = label + "（未保存）";
      }

      boolean truncated = font.width(label) > textMaxW;
      String name = font.plainSubstrByWidth(label, textMaxW);
      if (truncated) {
         name = name + "...";
      }

      if (truncated) {
         tooltipText = tooltipText + "\n名称:" + label;
      }

      Component labelComponent = pending ? Component.literal(name).withColor(-22016) : Component.literal(name);
      String shortcutLine = "";
      if (cmdEntry.shortcut != null && !cmdEntry.shortcut.isBlank()) {
         shortcutLine = "\n" + Component.translatable("screen.command-gui.shortcut").getString() + ": " + CommandShortcut.display(cmdEntry.shortcut);
      }

      CustomCommandTab.CommandRowButton cmdBtn = new CustomCommandTab.CommandRowButton(
         left, y, commandWidth, h, labelComponent, b -> this.handleCommand(cmdEntry), () -> this.editCommand(cmdName, cmdEntry)
      );
      cmdBtn.setTooltip(
         Tooltip.create(
            Component.literal(tooltipText + shortcutLine + "\n" + Component.translatable("screen.command-gui.action.left_execute_right_edit").getString())
         )
      );
      this.commandButtons.add(cmdBtn);
   }

   @Override
   public int getMaxScroll() {
      if (this.area != null && !this.filteredCommands.isEmpty()) {
         int visibleRows = Math.max(1, Math.max(1, this.area.height()) / this.scaledRowHeight());
         int totalRows = (this.filteredCommands.size() + this.columns() - 1) / this.columns();
         return Math.max(0, totalRows - visibleRows);
      } else {
         return 0;
      }
   }

   @Override
   public int getVisibleRowCount() {
      if (this.area == null) {
         return 1;
      }
      return Math.max(1, Math.max(1, this.area.height()) / this.scaledRowHeight());
   }

   @Override
   public int getTotalRowCount() {
      return Math.max(1, (this.filteredCommands.size() + this.columns() - 1) / this.columns());
   }

   @Override
   public List<Button> getButtons() {
      List<Button> all = new ArrayList<>(this.commandButtons);
      all.addAll(this.extraButtons);
      return all;
   }

   private void onCategoryButtonClick(String categoryId) {
      if (Objects.equals(this.selectedCategoryId, categoryId)) {
         this.notifyCategoryChange(() -> {
            this.selectedCategoryId = null;
            this.scrollOffset = 0;
            this.buildFilteredCommands();
            this.buildAllCategoryButtons();
            this.rebuildVisibleCategoryButtons();
            this.rebuildButtons();
            this.rememberViewState();
         });
      } else {
         this.notifyCategoryChange(() -> {
            this.selectedCategoryId = categoryId;
            this.scrollOffset = 0;
            this.buildFilteredCommands();
            this.buildAllCategoryButtons();
            this.rebuildVisibleCategoryButtons();
            this.rebuildButtons();
            this.rememberViewState();
         });
      }
   }

   private void rememberViewState() {
      if (SettingsConfig.getBoolean("quick_command_remember_view")) {
         rememberedCategoryId = this.selectedCategoryId;
         rememberedScrollOffset = this.scrollOffset;
         rememberedCategoryScrollOffset = this.categoryScrollOffset;
      }
   }

   @Override
   public void scroll(double delta) {
      super.scroll(delta);
      this.rememberViewState();
   }

   @Override
   public void scrollCategory(double delta) {
      super.scrollCategory(delta);
      this.rememberViewState();
   }

   @Override
   public void setScrollOffset(int offset) {
      super.setScrollOffset(offset);
      this.rememberViewState();
   }

   @Override
   public void setCategoryScrollOffset(int offset) {
      super.setCategoryScrollOffset(offset);
      this.rememberViewState();
   }

   private void openAddCategoryScreen() {
      Minecraft.getInstance().gui.setScreen(new AddCategoryScreen((CommandGUIScreen)this.parent));
   }

   private void openEditCategoryScreen(String categoryId) {
      CommandConfig.Category category = CommandConfig.getCategory(categoryId);
      if (category != null && !categoryId.equals("default")) {
         Minecraft.getInstance().gui.setScreen(new EditCategoryScreen((CommandGUIScreen)this.parent, category));
      }
   }

   private void editCommand(String name, CommandConfig.CommandEntry entry) {
      Minecraft mc = Minecraft.getInstance();
      CommandGUIScreen parentScreen = (CommandGUIScreen)this.parent;
      String categoryId = CommandConfig.findCommandCategory(name);
      mc.gui.setScreen(new AddCommandScreen(parentScreen, categoryId, name, entry));
   }

   private void handleCommand(CommandConfig.CommandEntry entry) {
      List<String> commands = entry.getCommands();
      if (commands.size() > 1) {
         ChainedCommandExecutor.executeMulti(this.parent, commands, entry.commandDelay);
      } else if (!commands.isEmpty()) {
         ChainedCommandExecutor.execute(this.parent, commands.get(0));
      }
   }

   public void refresh() {
      this.scrollOffset = 0;
      this.buildFilteredCommands();
      this.buildAllCategoryButtons();
      this.rebuildVisibleCategoryButtons();
      this.rebuildButtons();
   }

   public boolean isEmpty() {
      return this.filteredCommands.isEmpty() && CommandConfig.getCategories().isEmpty();
   }

   public String getSelectedCategoryId() {
      return this.selectedCategoryId;
   }

   private final class CommandRowButton extends Button {
      private final Runnable onRightClick;

      CommandRowButton(int x, int y, int width, int height, Component message, OnPress onPress, Runnable onRightClick) {
         super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
         this.onRightClick = onRightClick;
      }

      public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
         if (mouseEvent.button() == 1 && this.active && this.visible && this.isMouseOver(mouseEvent.x(), mouseEvent.y())) {
            this.onRightClick.run();
            return true;
         } else {
            return super.mouseClicked(mouseEvent, focused);
         }
      }

      protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
         this.extractDefaultSprite(guiGraphics);
         Font font = Minecraft.getInstance().font;
         int color = this.active ? -1 : -6250336;
         guiGraphics.centeredText(font, this.getMessage(), this.getX() + this.getWidth() / 2, this.getY() + (this.getHeight() - 8) / 2, color);
      }
   }

   private static record FilteredCommand(String name, String categoryId, CommandConfig.CommandEntry entry) {
   }
}

package com.remrin.client.gui;

import com.mojang.blaze3d.platform.Window;
import com.remrin.client.config.PresetConfig;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.tabs.MenuTabBar.Builder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Abilities;
import org.lwjgl.glfw.GLFW;

public class CommandGUIScreen extends Screen {
   private static final int FOOTER_HEIGHT = 44;
   private static final int PADDING = 10;
   private static final int SCROLLBAR_WIDTH = 12;
   private static final int RIGHT_MARGIN = 16;
   private static final int REFRESH_INTERVAL = 10;
   private static int lastSelectedTabIndex = 0;
   private static boolean keepOpenAfterExecute = false;
   private static CommandGUIScreen currentInstance = null;
   private TabManager tabManager;
   private TabNavigationBar tabNavigationBar;
   private CustomCommandTab customTab;
   private FakePlayerTab fakePlayerTab;
   private MachineSwitchTab machineTab;
   private List<PresetCommandTab> presetTabs = new ArrayList<>();
   private EditBox searchField;
   private Button addButton;
   private Button addMachineButton;
   private Button addFakePlayerButton;
   private Button machineSaveButton;
   private Button fpRemoveSelectedButton;
   private Button fpTimedAddButton;
   private Button fpRemoveAllButton;
   private Button fpBatchSpawnButton;
   private MarkCheckbox filterOnCheckbox;
   private MarkCheckbox filterOffCheckbox;
   private Button closeButton;
   private Checkbox keepOpenCheckbox;
   private SettingsButton settingsButton;
   private String searchText = "";
   private Tab lastTab = null;
   private ScreenRectangle tabArea;
   private ScreenRectangle fakePlayerArea;
   private int fakePlayerRefreshTicks = 0;
   private int lastMachineSyncVersion = -1;
   private int lastMachineStructureVersion = -1;
   private int lastFakeStatesVersion = -1;
   private final Set<Button> registeredTabButtons = new HashSet<>();
   private ScrollbarHandle mainScrollbar = null;
   private int mainScrollbarMaxScroll = 0;
   private int mainScrollbarViewport = 1;
   private int mainScrollbarContent = 1;
   private boolean draggingMainScrollbar = false;
   private double mainScrollbarGrabOffset = 0.0;
   private boolean draggingCategoryScrollbar = false;
   private double categoryScrollbarGrabOffset = 0.0;
   private boolean draggingFakePlayerScrollbar = false;
   private double fakePlayerScrollbarGrabOffset = 0.0;
   private boolean draggingFakePlayerPanelScrollbar = false;
   private double fakePlayerPanelScrollbarGrabOffset = 0.0;

   public CommandGUIScreen() {
      super(Component.translatable("screen.command-gui.title"));
   }

   public static boolean shouldKeepOpen() {
      if (currentInstance != null
         && currentInstance.customTab != null
         && currentInstance.tabManager != null
         && currentInstance.tabManager.getCurrentTab() == currentInstance.customTab) {
         return currentInstance.customTab.isPanelKeepOpen();
      } else {
         if (currentInstance != null && currentInstance.keepOpenCheckbox != null) {
            return currentInstance.keepOpenCheckbox.selected();
         }
         return keepOpenAfterExecute;
      }
   }


   private int footerHeight() {
      return GuiTuning.getInt("CommandGUIScreen.FOOTER_HEIGHT", FOOTER_HEIGHT);
   }

   private int footerControlTopOffset() {
      return GuiTuning.getInt("CommandGUIScreen.FOOTER_CONTROL_TOP_OFFSET", 12);
   }

   private int padding() {
      return GuiTuning.getInt("CommandGUIScreen.PADDING", PADDING);
   }

   private int scrollbarWidth() {
      return GuiTuning.getInt("CommandGUIScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH);
   }

   private int rightMargin() {
      return GuiTuning.getInt("CommandGUIScreen.RIGHT_MARGIN", RIGHT_MARGIN);
   }

   private int searchWidth() {
      return GuiTuning.getInt("CommandGUIScreen.SEARCH_WIDTH", 90);
   }

   private int closeButtonWidth() {
      return GuiTuning.getInt("CommandGUIScreen.CLOSE_BUTTON_WIDTH", 60);
   }

   private int batchButtonWidth() {
      return GuiTuning.getInt("CommandGUIScreen.BATCH_BUTTON_WIDTH", 88);
   }

   private int tabAreaTopGap() {
      return GuiTuning.getInt("CommandGUIScreen.TAB_AREA_TOP_GAP", 4);
   }

   private int tabAreaBottomGap() {
      return GuiTuning.getInt("CommandGUIScreen.TAB_AREA_BOTTOM_GAP", 2);
   }

   boolean hasCommandPermission() {
      if (this.minecraft == null || this.minecraft.player == null) {
         return false;
      } else if (this.minecraft.hasSingleplayerServer()) {
         return true;
      } else {
         Abilities abilities = this.minecraft.player.getAbilities();
         return abilities.instabuild;
      }
   }

   boolean isOperator() {
      return this.minecraft != null && this.minecraft.player != null && Commands.LEVEL_MODERATORS.check(this.minecraft.player.permissions());
   }

   protected void init() {
      super.init();
      GuiTuning.load();
      currentInstance = this;
      this.tabManager = new TabManager(w -> {
      }, w -> {
      });
      this.customTab = new CustomCommandTab(this);
      this.customTab.setOnCategoryChanged(() -> this.removeTabButtons(this.customTab), () -> this.addTabButtons(this.customTab));
      this.fakePlayerTab = new FakePlayerTab(this);
      this.fakePlayerTab.setOnRebuild(() -> this.removeTabButtons(this.fakePlayerTab), () -> this.addTabButtons(this.fakePlayerTab));
      this.machineTab = null;
      if (MachineNetworkManager.isServerSupported()) {
         this.machineTab = new MachineSwitchTab(this);
         this.machineTab.setOnCategoryChanged(() -> this.removeTabButtons(this.machineTab), () -> this.addTabButtons(this.machineTab));
      }

      this.presetTabs.clear();
      boolean hasPermission = this.hasCommandPermission();

      for (PresetConfig.Preset preset : PresetConfig.getPresets()) {
         if ((!"vanilla".equals(preset.id) || hasPermission && SettingsConfig.getBoolean("show_vanilla_commands"))
            && (!"carpet".equals(preset.id) || SettingsConfig.getBoolean("show_carpet_commands"))) {
            PresetCommandTab tab = new PresetCommandTab(this, preset.id, preset.nameKey);
            tab.setOnCategoryChanged(() -> this.removeTabButtons(tab), () -> this.addTabButtons(tab));
            this.presetTabs.add(tab);
         }
      }

      Builder builder = MenuTabBar.builder(this.tabManager, this.width);
      builder.addTabs(new Tab[]{this.customTab});
      if (SettingsConfig.getBoolean("show_fakeplayer_tab")) {
         builder.addTabs(new Tab[]{this.fakePlayerTab});
      }

      if (this.machineTab != null) {
         builder.addTabs(new Tab[]{this.machineTab});
      }

      for (PresetCommandTab tab : this.presetTabs) {
         builder.addTabs(new Tab[]{tab});
      }

      this.tabNavigationBar = builder.build();
      this.addRenderableWidget(this.tabNavigationBar);
      this.tabNavigationBar.arrangeElements(this.width);
      int tabBarBottom = this.tabNavigationBar.getRectangle().bottom();
      int closeBtnY = this.height - this.footerHeight() + this.footerControlTopOffset();
      this.keepOpenCheckbox = Checkbox.builder(Component.translatable("screen.command-gui.keep_open"), this.font)
         .pos(this.padding(), closeBtnY)
         .selected(keepOpenAfterExecute)
         .build();
      this.addRenderableWidget(this.keepOpenCheckbox);
      int searchWidth = this.searchWidth();
      int searchGroupWidth = searchWidth + 2 + 20 + 2 + 20;
      int searchGroupX = this.width / 2 - searchGroupWidth / 2;
      this.searchField = new EditBox(this.font, searchGroupX, closeBtnY, searchWidth, 20, Component.translatable("screen.command-gui.search_hint"));
      this.searchField.setHint(Component.translatable("screen.command-gui.search_hint"));
      this.searchField.setMaxLength(50);
      this.searchField.setValue(this.searchText);
      this.searchField.setResponder(this::onSearchChanged);
      this.addRenderableWidget(this.searchField);
      this.addButton = Button.builder(Component.translatable("screen.command-gui.add_command"), button -> {
         Tab current = this.tabManager.getCurrentTab();
         if (current == this.machineTab) {
            this.minecraft.gui.setScreen(new MachineEditorScreen(this, null));
         } else {
            this.minecraft.gui.setScreen(new AddCommandScreen(this, this.customTab.getSelectedCategoryId()));
         }
      }).bounds(searchGroupX + searchWidth + 2, closeBtnY, 20, 20).build();
      this.addRenderableWidget(this.addButton);
      this.addMachineButton = Button.builder(
            Component.translatable("screen.command-gui.machine.add_machine"),
            button -> this.minecraft
                  .gui
                  .setScreen(new MachineEditorScreen(this, null, this.machineTab != null ? this.machineTab.getSelectedCategoryIdForNew() : null))
         )
         .bounds(searchGroupX + searchWidth + 2, closeBtnY, 48, 20)
         .build();
      this.addMachineButton.visible = false;
      this.addMachineButton.active = false;
      this.addRenderableWidget(this.addMachineButton);
      this.machineSaveButton = Button.builder(Component.translatable("screen.command-gui.machine.save_pending"), b -> {
         MachineNetworkManager.uploadPendingMachines();
         if (this.machineTab != null) {
            this.removeTabButtons(this.machineTab);
            this.machineTab.refresh();
            this.addTabButtons(this.machineTab);
         }

         this.updateMachineSaveVisibility();
      }).bounds(0, closeBtnY, 72, 20).build();
      this.machineSaveButton.visible = false;
      this.machineSaveButton.active = false;
      this.addRenderableWidget(this.machineSaveButton);
      this.filterOnCheckbox = new MarkCheckbox(
         this.padding(), closeBtnY - 8, 112, 14, Component.translatable("screen.command-gui.machine.filter_on"), false, b -> this.toggleMachineFilter(true)
      );
      this.filterOnCheckbox.visible = false;
      this.filterOnCheckbox.active = false;
      this.addRenderableWidget(this.filterOnCheckbox);
      this.filterOffCheckbox = new MarkCheckbox(
         this.padding(), closeBtnY + 6, 112, 14, Component.translatable("screen.command-gui.machine.filter_off"), false, b -> this.toggleMachineFilter(false)
      );
      this.filterOffCheckbox.visible = false;
      this.filterOffCheckbox.active = false;
      this.addRenderableWidget(this.filterOffCheckbox);
      this.addFakePlayerButton = Button.builder(
            Component.translatable("screen.command-gui.add_fakeplayer"),
            button -> this.minecraft.gui.setScreen(new AddCommandScreen(this, this.customTab.getSelectedCategoryId(), true))
         )
         .bounds(this.padding(), closeBtnY, 88, 20)
         .build();
      this.addFakePlayerButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.add_fakeplayer_cmd")));
      this.addRenderableWidget(this.addFakePlayerButton);
      this.fpRemoveSelectedButton = Button.builder(
            Component.translatable("screen.command-gui.fakeplayer.remove_selected", new Object[]{0}), b -> this.fakePlayerTab.confirmRemoveSelected()
         )
         .bounds(this.padding(), closeBtnY, 76, 20)
         .build();
      this.fpRemoveSelectedButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.fakeplayer.remove_selected_hint")));
      this.fpRemoveSelectedButton.visible = false;
      this.fpRemoveSelectedButton.active = false;
      this.addRenderableWidget(this.fpRemoveSelectedButton);
      this.fpTimedAddButton = Button.builder(
            Component.translatable("screen.command-gui.fakeplayer.timed.spawn.short"), b -> this.fakePlayerTab.openTimedSpawnScreen()
         )
         .bounds(90, closeBtnY, 76, 20)
         .build();
      this.fpTimedAddButton.visible = false;
      this.fpTimedAddButton.active = false;
      this.addRenderableWidget(this.fpTimedAddButton);
      this.fpRemoveAllButton = Button.builder(Component.translatable("screen.command-gui.fakeplayer.killall"), b -> this.fakePlayerTab.confirmRemoveAll())
         .bounds(170, closeBtnY, 76, 20)
         .build();
      this.fpRemoveAllButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.fakeplayer.killall.confirm_hint")));
      this.fpRemoveAllButton.visible = false;
      this.fpRemoveAllButton.active = false;
      this.addRenderableWidget(this.fpRemoveAllButton);
      this.fpBatchSpawnButton = Button.builder(
            Component.translatable("screen.command-gui.fakeplayer.batch.title"), b -> this.fakePlayerTab.openBatchSpawnScreen()
         )
         .bounds(this.width - this.padding() - this.closeButtonWidth() - 4 - this.batchButtonWidth(), closeBtnY, this.batchButtonWidth(), 20)
         .build();
      this.fpBatchSpawnButton.visible = false;
      this.fpBatchSpawnButton.active = false;
      this.addRenderableWidget(this.fpBatchSpawnButton);
      this.closeButton = Button.builder(Component.translatable("screen.command-gui.close"), button -> this.onClose())
         .bounds(this.width - this.padding() - this.closeButtonWidth(), closeBtnY, this.closeButtonWidth(), 20)
         .build();
      this.addRenderableWidget(this.closeButton);
      this.settingsButton = new SettingsButton(4, 2, 20, 20, btn -> this.minecraft.gui.setScreen(new SettingsScreen(this)));
      this.settingsButton.visible = true;
      this.settingsButton.active = true;
      this.addRenderableWidget(this.settingsButton);
      int tabCount = this.tabNavigationBar.getTabs().size();
      int restoreIndex = Math.max(0, Math.min(lastSelectedTabIndex, tabCount - 1));
      this.tabNavigationBar.selectTab(restoreIndex, false);
      int listTop = tabBarBottom + this.tabAreaTopGap();
      int tabAreaHeight = Math.max(20, this.height - this.footerHeight() - listTop - this.tabAreaBottomGap());
      this.tabArea = new ScreenRectangle(this.padding(), listTop, this.width - this.padding() - this.rightMargin() - this.scrollbarWidth(), tabAreaHeight);
      this.tabManager.setTabArea(this.tabArea);
      this.customTab.doLayout(this.tabArea);
      if (this.machineTab != null) {
         this.machineTab.doLayout(this.tabArea);
      }

      int fpAreaHeight = Math.max(20, this.height - this.footerHeight() - (tabBarBottom + this.tabAreaTopGap()) - this.tabAreaBottomGap());
      this.fakePlayerArea = new ScreenRectangle(this.padding(), tabBarBottom + this.tabAreaTopGap(), this.width - this.padding() - this.rightMargin() - this.scrollbarWidth(), fpAreaHeight);
      this.fakePlayerTab.doLayout(this.fakePlayerArea);

      for (PresetCommandTab tab : this.presetTabs) {
         tab.doLayout(this.tabArea);
      }

      this.addTabButtons(this.tabManager.getCurrentTab());
      this.updateTabDependentWidgets(this.tabManager.getCurrentTab());
      this.lastTab = this.tabManager.getCurrentTab();
   }

   public void removeTabButton(Button button) {
      this.registeredTabButtons.remove(button);
      this.removeWidget(button);
   }

   public void addTabButton(Button button) {
      if (this.registeredTabButtons.add(button)) {
         this.addRenderableWidget(button);
      }
   }

   private void addTabButtons(Tab tab) {
      if (tab instanceof AbstractCommandTab ct) {
         ct.getCategoryButtons().forEach(this::addTabButton);
         ct.getButtons().forEach(this::addTabButton);
      } else if (tab == this.fakePlayerTab) {
         this.fakePlayerTab.getButtons().forEach(this::addTabButton);
         this.fakePlayerTab.getIntervalFields().forEach(x$0 -> this.addRenderableWidget(x$0));
      }

      MachineDebug.log("[Layout] addTabButtons " + tabName(tab) + " -> screen has " + this.registeredTabButtons.size() + " tab buttons");
   }

   private void removeTabButtons(Tab tab) {
      if (tab instanceof AbstractCommandTab ct) {
         ct.getCategoryButtons().forEach(this::removeTabButton);
         ct.getButtons().forEach(this::removeTabButton);
      } else if (tab == this.fakePlayerTab) {
         this.fakePlayerTab.getButtons().forEach(this::removeTabButton);
         this.fakePlayerTab.getIntervalFields().forEach(x$0 -> this.removeWidget(x$0));
      }

      MachineDebug.log("[Layout] removeTabButtons " + tabName(tab) + " -> screen has " + this.registeredTabButtons.size() + " tab buttons");
   }

   private static String tabName(Tab tab) {
      if (tab instanceof CustomCommandTab) {
         return "custom";
      } else if (tab instanceof MachineSwitchTab) {
         return "machine";
      } else {
         if (tab instanceof FakePlayerTab) {
            return "fakePlayer";
         }
         return "preset";
      }
   }

   private void updateTabDependentWidgets(Tab tab) {
      boolean isFakePlayerTab = tab == this.fakePlayerTab;
      boolean isCustomTab = tab == this.customTab;
      boolean isMachineTab = tab == this.machineTab && MachineNetworkManager.isServerSupported();
      if (isFakePlayerTab) {
         MachineNetworkManager.sendRequestFakeStates();
         int closeBtnY = this.height - this.footerHeight() + this.footerControlTopOffset();
         int x = this.padding();
         int w = 76;
         int gap = 4;
         this.fpBatchSpawnButton.setX(x);
         this.fpBatchSpawnButton.setY(closeBtnY);
         this.fpBatchSpawnButton.setWidth(w);
         this.fpBatchSpawnButton.visible = true;
         this.fpBatchSpawnButton.active = true;
         x += w + gap;
         int batchW = 88;
         int batchX = this.closeButton.getX() - gap - batchW;
         if (this.machineTab != null) {
            this.fpTimedAddButton.setX(this.machineTab.getModesX());
            this.fpTimedAddButton.setY(closeBtnY);
            this.fpTimedAddButton.setWidth(48);
         } else {
            this.fpTimedAddButton.setX(batchX);
            this.fpTimedAddButton.setY(closeBtnY);
            this.fpTimedAddButton.setWidth(batchW);
         }

         this.fpTimedAddButton.visible = true;
         this.fpTimedAddButton.active = true;
         this.searchField.setX(this.customTab.getSwitchX());
         this.searchField.setWidth(this.customTab.getSearchBoxWidth());
         this.searchField.visible = true;
         this.searchField.active = true;
         ScreenRectangle fpArea = this.fakePlayerTab.getArea();
         if (fpArea != null) {
            int btnH = 16;
            int listX = this.fakePlayerTab.getPlayerListX();
            int listW = this.fakePlayerTab.getPlayerListWidth();
            if (this.isOperator()) {
               this.fpRemoveAllButton.setX(listX);
               this.fpRemoveAllButton.setY(fpArea.bottom() - 32);
               this.fpRemoveAllButton.setWidth(listW);
               this.fpRemoveAllButton.setHeight(btnH);
               this.fpRemoveAllButton.visible = true;
               this.fpRemoveAllButton.active = true;
            } else {
               this.fpRemoveAllButton.visible = false;
               this.fpRemoveAllButton.active = false;
            }

            this.fpRemoveSelectedButton.setX(listX);
            this.fpRemoveSelectedButton.setY(fpArea.bottom() - 16);
            this.fpRemoveSelectedButton.setWidth(listW);
            this.fpRemoveSelectedButton.setHeight(btnH);
         }

         this.fpRemoveSelectedButton.visible = true;
         this.fpRemoveSelectedButton.active = true;
         this.addMachineButton.visible = false;
         this.addMachineButton.active = false;
         this.machineSaveButton.visible = false;
         this.machineSaveButton.active = false;
         this.filterOnCheckbox.visible = false;
         this.filterOnCheckbox.active = false;
         this.filterOffCheckbox.visible = false;
         this.filterOffCheckbox.active = false;
         this.updateFakePlayerFooter();
      } else if (isMachineTab && this.machineTab != null) {
         int sx = this.machineTab.getSwitchX();
         int modesX = this.machineTab.getModesX();
         int sw = this.machineTab.getSwitchWidth();
         this.searchField.setX(sx);
         this.searchField.setWidth(sw);
         this.addMachineButton.setX(modesX);
         this.addMachineButton.visible = true;
         this.addMachineButton.active = true;
         this.machineSaveButton.setX(modesX - 4 - 72);
         this.machineSaveButton.setY(this.height - this.footerHeight() + this.footerControlTopOffset());
         this.machineSaveButton.visible = MachineNetworkManager.hasPendingMachines();
         this.machineSaveButton.active = this.machineSaveButton.visible;
         this.filterOnCheckbox.visible = true;
         this.filterOnCheckbox.active = true;
         this.filterOffCheckbox.visible = true;
         this.filterOffCheckbox.active = true;
         int catX = this.machineTab.getCategoryColumnX();
         this.filterOnCheckbox.setX(catX);
         this.filterOffCheckbox.setX(catX);
         this.searchField.visible = true;
         this.searchField.active = true;
      } else if (isCustomTab) {
         int sx = this.customTab.getSwitchX();
         int modesX = this.customTab.getModesX();
         this.searchField.setX(sx);
         this.searchField.setWidth(this.customTab.getSearchBoxWidth());
         this.addButton.setX(modesX);
         this.addButton.setWidth(48);
         this.addButton.visible = true;
         this.addButton.active = true;
         this.machineSaveButton.visible = false;
         this.machineSaveButton.active = false;
         this.addFakePlayerButton.setX(this.customTab.getCategoryColumnX());
         this.addFakePlayerButton.setY(this.height - this.footerHeight() + this.footerControlTopOffset());
         this.addFakePlayerButton.setWidth(88);
         this.addFakePlayerButton.visible = true;
         this.addFakePlayerButton.active = true;
         this.addMachineButton.visible = false;
         this.addMachineButton.active = false;
         this.filterOnCheckbox.visible = false;
         this.filterOnCheckbox.active = false;
         this.filterOffCheckbox.visible = false;
         this.filterOffCheckbox.active = false;
         this.searchField.visible = true;
         this.searchField.active = true;
      } else {
         int searchWidth = this.searchWidth();
         int searchGroupWidth = searchWidth + 2 + 20 + 2 + 20;
         int searchGroupX = this.width / 2 - searchGroupWidth / 2;
         this.searchField.setX(searchGroupX);
         this.searchField.setWidth(searchWidth);
         this.addButton.setWidth(20);
         this.addMachineButton.visible = false;
         this.addMachineButton.active = false;
         this.machineSaveButton.visible = false;
         this.machineSaveButton.active = false;
         this.filterOnCheckbox.visible = false;
         this.filterOnCheckbox.active = false;
         this.filterOffCheckbox.visible = false;
         this.filterOffCheckbox.active = false;
         this.searchField.visible = true;
         this.searchField.active = true;
      }

      this.addButton.visible = isCustomTab;
      this.addButton.active = isCustomTab;
      this.addFakePlayerButton.visible = isCustomTab;
      this.addFakePlayerButton.active = isCustomTab;
      if (!isFakePlayerTab) {
         this.fpRemoveSelectedButton.visible = false;
         this.fpRemoveSelectedButton.active = false;
         this.fpTimedAddButton.visible = false;
         this.fpTimedAddButton.active = false;
         this.fpRemoveAllButton.visible = false;
         this.fpRemoveAllButton.active = false;
         this.fpBatchSpawnButton.visible = false;
         this.fpBatchSpawnButton.active = false;
      }

      this.closeButton.visible = true;
      this.closeButton.active = true;
      this.keepOpenCheckbox.visible = !isFakePlayerTab && !isMachineTab && !isCustomTab;
      this.keepOpenCheckbox.active = !isFakePlayerTab && !isMachineTab && !isCustomTab;
   }

   public void updateFakePlayerFooter() {
      if (this.fpRemoveSelectedButton != null && this.fakePlayerTab != null) {
         int count = this.fakePlayerTab.getMultiSelectionCount();
         this.fpRemoveSelectedButton.setMessage(Component.translatable("screen.command-gui.fakeplayer.remove_selected", new Object[]{count}));
         this.fpRemoveSelectedButton.active = count > 0;
      }
   }

   private void onSearchChanged(String text) {
      this.searchText = text;
      Tab currentTab = this.tabManager.getCurrentTab();
      if (currentTab == this.fakePlayerTab) {
         this.fakePlayerTab.setSearchText(text);
      } else if (currentTab instanceof AbstractCommandTab ct) {
         this.removeTabButtons(ct);
         ct.setSearchText(text);
         this.addTabButtons(ct);
      }
   }

   private void toggleMachineFilter(boolean on) {
      if (this.machineTab != null) {
         MachineSwitchTab.MachineFilter next;
         if (on) {
            boolean select = !this.filterOnCheckbox.selected();
            this.filterOnCheckbox.setSelected(select);
            this.filterOffCheckbox.setSelected(false);
            next = select ? MachineSwitchTab.MachineFilter.ON : MachineSwitchTab.MachineFilter.ALL;
         } else {
            boolean select = !this.filterOffCheckbox.selected();
            this.filterOffCheckbox.setSelected(select);
            this.filterOnCheckbox.setSelected(false);
            next = select ? MachineSwitchTab.MachineFilter.OFF : MachineSwitchTab.MachineFilter.ALL;
         }

         this.removeTabButtons(this.machineTab);
         this.machineTab.setMachineFilter(next);
         this.addTabButtons(this.machineTab);
      }
   }

   public void repositionElements() {
      MachineDebug.log("[Layout] repositionElements -> rebuildWidgets " + this.width + "x" + this.height);
      this.rebuildWidgets();
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      Tab currentTab = this.tabManager.getCurrentTab();
      if (currentTab == this.fakePlayerTab) {
         if (this.fakePlayerTab.isInPanelArea(mouseX, mouseY)) {
            this.fakePlayerTab.scrollPanel(scrollY);
         } else {
            this.fakePlayerTab.scroll(scrollY);
         }
      } else if (currentTab == this.machineTab && this.machineTab != null) {
         if (this.machineTab.isInCategoryArea(mouseX, mouseY)) {
            this.removeTabButtons(this.machineTab);
            this.machineTab.scrollCategory(scrollY);
            this.addTabButtons(this.machineTab);
         } else {
            this.removeTabButtons(this.machineTab);
            this.machineTab.scroll(scrollY);
            this.addTabButtons(this.machineTab);
         }
      } else if (currentTab == this.customTab) {
         if (this.customTab.isInCategoryArea(mouseX, mouseY)) {
            this.removeTabButtons(this.customTab);
            this.customTab.scrollCategory(scrollY);
            this.addTabButtons(this.customTab);
         } else {
            this.removeTabButtons(this.customTab);
            this.customTab.scroll(scrollY);
            this.addTabButtons(this.customTab);
         }
      } else {
         for (PresetCommandTab presetTab : this.presetTabs) {
            if (currentTab == presetTab) {
               if (presetTab.isInCategoryArea(mouseX, mouseY)) {
                  this.removeTabButtons(presetTab);
                  presetTab.scrollCategory(scrollY);
                  this.addTabButtons(presetTab);
               } else {
                  this.removeTabButtons(presetTab);
                  presetTab.scroll(scrollY);
                  this.addTabButtons(presetTab);
               }
               break;
            }
         }
      }

      return true;
   }

   private void executeCommand(String command) {
      if (this.minecraft != null && this.minecraft.player != null) {
         CommandHelper.sendCommand(command);
      }

      if (!shouldKeepOpen()) {
         this.minecraft.gui.setScreen(null);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      Tab currentTab = this.tabManager.getCurrentTab();
      int bottomSeparatorY = this.height - this.footerHeight();
      guiGraphics.fill(0, bottomSeparatorY, this.width, bottomSeparatorY + 1, -11184811);
      if (currentTab != this.fakePlayerTab) {
         this.renderScrollbar(guiGraphics, mouseX, mouseY);
      }

      if (currentTab == this.customTab) {
         if (this.tabArea != null) {
            guiGraphics.enableScissor(this.tabArea.left(), this.tabArea.top(), this.tabArea.right(), this.tabArea.bottom());
         }

         this.customTab.renderCategoryScrollbar(guiGraphics);
         if (this.tabArea != null) {
            guiGraphics.disableScissor();
         }

         if (this.customTab.isEmpty()) {
            ScreenRectangle area = this.customTab.getArea();
            if (area != null) {
               guiGraphics.centeredText(
                  this.font, Component.translatable("screen.command-gui.empty"), this.width / 2, area.top() + area.height() / 2 - 4, -7829368
               );
            }
         }
      } else if (currentTab == this.machineTab && this.machineTab != null) {
         if (this.tabArea != null) {
            guiGraphics.enableScissor(this.tabArea.left(), this.tabArea.top(), this.tabArea.right(), this.tabArea.bottom());
         }

         this.machineTab.renderCategoryScrollbar(guiGraphics);
         if (this.tabArea != null) {
            guiGraphics.disableScissor();
         }

         if (this.machineTab.isEmpty()) {
            ScreenRectangle area = this.machineTab.getArea();
            if (area != null) {
               guiGraphics.centeredText(
                  this.font, Component.translatable("screen.command-gui.machine.empty"), this.width / 2, area.top() + area.height() / 2 - 4, -7829368
               );
            }
         }
      } else if (currentTab == this.fakePlayerTab) {
         if (this.fakePlayerArea != null) {
            guiGraphics.enableScissor(this.fakePlayerArea.left(), this.fakePlayerArea.top(), this.fakePlayerArea.right(), this.fakePlayerArea.bottom());
         }

         this.fakePlayerTab.render(guiGraphics, mouseX, mouseY);
         this.fakePlayerTab.renderFaces(guiGraphics);
         this.fakePlayerTab.renderScrollbar(guiGraphics);
         if (this.fakePlayerArea != null) {
            guiGraphics.disableScissor();
         }

         this.fakePlayerTab.renderPanelScrollbar(guiGraphics);
      } else {
         for (PresetCommandTab presetTab : this.presetTabs) {
            if (currentTab == presetTab) {
               if (this.tabArea != null) {
                  guiGraphics.enableScissor(this.tabArea.left(), this.tabArea.top(), this.tabArea.right(), this.tabArea.bottom());
               }

               presetTab.renderCategoryScrollbar(guiGraphics);
               if (this.tabArea != null) {
                  guiGraphics.disableScissor();
               }
               break;
            }
         }
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      if (this.keepOpenCheckbox != null) {
         keepOpenAfterExecute = this.keepOpenCheckbox.selected();
      }

      this.fakePlayerRefreshTicks = 0;
      currentInstance = null;
      super.onClose();
   }

   public void refresh() {
      this.removeTabButtons(this.customTab);
      this.customTab.refresh();
      this.addTabButtons(this.customTab);
   }

   public void refreshFakePlayerTab() {
      if (this.fakePlayerTab == null) {
         return;
      }

      boolean intervalFocused = this.fakePlayerTab.isIntervalFieldFocused();
      this.removeTabButtons(this.fakePlayerTab);
      this.fakePlayerTab.refresh();
      this.addTabButtons(this.fakePlayerTab);
      if (intervalFocused) {
         this.fakePlayerTab.restoreIntervalFieldFocus();
      }
   }

   private void renderScrollbar(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
      if (this.tabArea != null) {
         Tab currentTab = this.tabManager.getCurrentTab();
         int scrollOffset;
         int maxScroll;
         int viewport;
         int content;
         if (currentTab == this.customTab) {
            scrollOffset = this.customTab.getScrollOffset();
            maxScroll = this.customTab.getMaxScroll();
            viewport = this.customTab.getVisibleRowCount();
            content = this.customTab.getTotalRowCount();
         } else if (currentTab == this.machineTab && this.machineTab != null) {
            scrollOffset = this.machineTab.getScrollOffset();
            maxScroll = this.machineTab.getMaxScroll();
            viewport = this.machineTab.getVisibleRowCount();
            content = this.machineTab.getTotalRowCount();
         } else if (currentTab == this.fakePlayerTab) {
            scrollOffset = this.fakePlayerTab.getScrollOffset();
            maxScroll = this.fakePlayerTab.getMaxScroll();
            viewport = this.fakePlayerTab.getVisibleRowCount();
            content = this.fakePlayerTab.getTotalRowCount();
         } else {
            scrollOffset = 0;
            maxScroll = 0;
            viewport = 1;
            content = 1;

            for (PresetCommandTab presetTab : this.presetTabs) {
               if (currentTab == presetTab) {
                  scrollOffset = presetTab.getScrollOffset();
                  maxScroll = presetTab.getMaxScroll();
                  viewport = presetTab.getVisibleRowCount();
                  content = presetTab.getTotalRowCount();
                  break;
               }
            }
         }

         boolean isMachineTab = currentTab == this.machineTab && this.machineTab != null;
         int scrollbarX = !isMachineTab && currentTab != this.customTab ? this.width - this.rightMargin() - this.scrollbarWidth() : this.width - 8 - this.scrollbarWidth();
         int scrollbarTop = this.tabArea.top();
         int scrollbarHeight = this.tabArea.height();
         this.mainScrollbar = new ScrollbarHandle(scrollbarX, scrollbarTop, this.scrollbarWidth(), scrollbarHeight);
         boolean hovered = this.mainScrollbar.contains((double)mouseX, (double)mouseY);
         this.mainScrollbar.render(guiGraphics, scrollOffset, maxScroll, viewport, content, hovered);
         this.mainScrollbarMaxScroll = maxScroll;
         this.mainScrollbarViewport = viewport;
         this.mainScrollbarContent = content;
      }
   }

   private void tryStartScrollbarDrag(double mouseX, double mouseY) {
      Tab currentTab = this.tabManager.getCurrentTab();
      if (currentTab instanceof AbstractCommandTab ct && ct.getArea() != null) {
         int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + 2;
         int catY = ct.getArea().top();
         int catH = ct.getArea().height();
         ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY, this.scrollbarWidth(), catH);
         if (catHandle.contains(mouseX, mouseY) && ct.getMaxCategoryScroll() > 0) {
            int thumbTop = catHandle.thumbTop(ct.getCategoryScrollOffset(), ct.getMaxCategoryScroll(), ct.getVisibleCategoryCount(), ct.getAllCategoryCount());
            this.categoryScrollbarGrabOffset = mouseY - (double)thumbTop;
            this.draggingCategoryScrollbar = true;
            return;
         }
      }

      if (currentTab == this.fakePlayerTab) {
         int fpScrollX = this.fakePlayerTab.getScrollbarX();
         int fpY = this.fakePlayerTab.getArea().top();
         int fpH = this.fakePlayerTab.getArea().height();
         ScrollbarHandle fpHandle = new ScrollbarHandle(fpScrollX, fpY, this.scrollbarWidth(), fpH);
         if (fpHandle.contains(mouseX, mouseY) && this.fakePlayerTab.getMaxScroll() > 0) {
            int thumbTop = fpHandle.thumbTop(
               this.fakePlayerTab.getScrollOffset(),
               this.fakePlayerTab.getMaxScroll(),
               this.fakePlayerTab.getVisibleRowCount(),
               this.fakePlayerTab.getTotalRowCount()
            );
            this.fakePlayerScrollbarGrabOffset = mouseY - (double)thumbTop;
            this.draggingFakePlayerScrollbar = true;
            return;
         }
      }

      if (currentTab == this.fakePlayerTab && this.fakePlayerTab.getArea() != null) {
         int fpPanelX = this.fakePlayerTab.getPanelScrollbarX();
         int fpPanelY = this.fakePlayerTab.getArea().top();
         int fpPanelH = this.fakePlayerTab.getArea().height();
         ScrollbarHandle fpPanelHandle = new ScrollbarHandle(fpPanelX, fpPanelY, this.scrollbarWidth(), fpPanelH);
         if (fpPanelHandle.contains(mouseX, mouseY) && this.fakePlayerTab.getPanelMaxScroll() > 0) {
            int thumbTop = fpPanelHandle.thumbTop(
               this.fakePlayerTab.getPanelScrollOffset(),
               this.fakePlayerTab.getPanelMaxScroll(),
               this.fakePlayerTab.getPanelVisibleRowCount(),
               this.fakePlayerTab.getPanelContentRowCount()
            );
            this.fakePlayerPanelScrollbarGrabOffset = mouseY - (double)thumbTop;
            this.draggingFakePlayerPanelScrollbar = true;
            return;
         }
      }

      if (this.mainScrollbar != null && this.mainScrollbar.contains(mouseX, mouseY) && this.mainScrollbarMaxScroll > 0) {
         int thumbTop = this.mainScrollbar
            .thumbTop(this.getMainScrollOffset(), this.mainScrollbarMaxScroll, this.mainScrollbarViewport, this.mainScrollbarContent);
         this.mainScrollbarGrabOffset = mouseY - (double)thumbTop;
         this.draggingMainScrollbar = true;
      }
   }

   private void applyMainScrollbarDrag(double mouseY) {
      if (this.mainScrollbar != null && this.mainScrollbarMaxScroll > 0) {
         int offset = this.mainScrollbar
            .offsetFromY(mouseY, this.mainScrollbarGrabOffset, this.mainScrollbarMaxScroll, this.mainScrollbarViewport, this.mainScrollbarContent);
         Tab currentTab = this.tabManager.getCurrentTab();
         if (currentTab == this.customTab) {
            this.removeTabButtons(this.customTab);
            this.customTab.setScrollOffset(offset);
            this.addTabButtons(this.customTab);
         } else if (currentTab == this.machineTab && this.machineTab != null) {
            this.removeTabButtons(this.machineTab);
            this.machineTab.setScrollOffset(offset);
            this.addTabButtons(this.machineTab);
         } else if (currentTab == this.fakePlayerTab) {
            this.fakePlayerTab.setScrollOffset(offset);
         } else {
            for (PresetCommandTab presetTab : this.presetTabs) {
               if (currentTab == presetTab) {
                  this.removeTabButtons(presetTab);
                  presetTab.setScrollOffset(offset);
                  this.addTabButtons(presetTab);
                  break;
               }
            }
         }
      }
   }

   private void applyCategoryScrollbarDrag(double mouseY) {
      if (this.tabManager.getCurrentTab() instanceof AbstractCommandTab ct && ct.getArea() != null) {
         int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + 2;
         int catY = ct.getArea().top();
         int catH = ct.getArea().height();
         ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY, this.scrollbarWidth(), catH);
         int offset = catHandle.offsetFromY(
            mouseY, this.categoryScrollbarGrabOffset, ct.getMaxCategoryScroll(), ct.getVisibleCategoryCount(), ct.getAllCategoryCount()
         );
         this.removeTabButtons(ct);
         ct.setCategoryScrollOffset(offset);
         this.addTabButtons(ct);
         return;
      }
   }

   private int getMainScrollOffset() {
      Tab currentTab = this.tabManager.getCurrentTab();
      if (currentTab == this.customTab) {
         return this.customTab.getScrollOffset();
      } else if (currentTab == this.machineTab && this.machineTab != null) {
         return this.machineTab.getScrollOffset();
      } else if (currentTab == this.fakePlayerTab) {
         return this.fakePlayerTab.getScrollOffset();
      } else {
         for (PresetCommandTab presetTab : this.presetTabs) {
            if (currentTab == presetTab) {
               return presetTab.getScrollOffset();
            }
         }

         return 0;
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0) {
         this.tryStartScrollbarDrag(mouseEvent.x(), mouseEvent.y());
      }

      return super.mouseClicked(mouseEvent, focused);
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingMainScrollbar) {
         this.applyMainScrollbarDrag(mouseEvent.y());
         return true;
      } else if (this.draggingCategoryScrollbar) {
         this.applyCategoryScrollbarDrag(mouseEvent.y());
         return true;
      } else if (this.draggingFakePlayerScrollbar) {
         this.applyFakePlayerScrollbarDrag(mouseEvent.y());
         return true;
      } else if (this.draggingFakePlayerPanelScrollbar) {
         this.applyFakePlayerPanelScrollbarDrag(mouseEvent.y());
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   private void applyFakePlayerScrollbarDrag(double mouseY) {
      if (this.fakePlayerTab.getArea() != null && this.fakePlayerTab.getMaxScroll() > 0) {
         int fpX = this.fakePlayerTab.getScrollbarX();
         int fpY = this.fakePlayerTab.getArea().top();
         int fpH = this.fakePlayerTab.getArea().height();
         ScrollbarHandle fpHandle = new ScrollbarHandle(fpX, fpY, this.scrollbarWidth(), fpH);
         int offset = fpHandle.offsetFromY(
            mouseY,
            this.fakePlayerScrollbarGrabOffset,
            this.fakePlayerTab.getMaxScroll(),
            this.fakePlayerTab.getVisibleRowCount(),
            this.fakePlayerTab.getTotalRowCount()
         );
         this.fakePlayerTab.setScrollOffset(offset);
      }
   }

   private void applyFakePlayerPanelScrollbarDrag(double mouseY) {
      if (this.fakePlayerTab.getArea() != null && this.fakePlayerTab.getPanelMaxScroll() > 0) {
         int fpPanelX = this.fakePlayerTab.getPanelScrollbarX();
         int fpPanelY = this.fakePlayerTab.getArea().top();
         int fpPanelH = this.fakePlayerTab.getArea().height();
         ScrollbarHandle fpPanelHandle = new ScrollbarHandle(fpPanelX, fpPanelY, this.scrollbarWidth(), fpPanelH);
         int offset = fpPanelHandle.offsetFromY(
            mouseY,
            this.fakePlayerPanelScrollbarGrabOffset,
            this.fakePlayerTab.getPanelMaxScroll(),
            this.fakePlayerTab.getPanelVisibleRowCount(),
            this.fakePlayerTab.getPanelContentRowCount()
         );
         this.fakePlayerTab.setPanelScrollOffset(offset);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingMainScrollbar = false;
      this.draggingCategoryScrollbar = false;
      this.draggingFakePlayerScrollbar = false;
      this.draggingFakePlayerPanelScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   public void resize(int width, int height) {
      MachineDebug.log("[Layout] CommandGUIScreen.resize called " + width + "x" + height + " (current " + this.width + "x" + this.height + ")");
      super.resize(width, height);
   }

   private void syncWindowSize() {
      if (this.minecraft != null && this.minecraft.getWindow() != null) {
         Window window = this.minecraft.getWindow();
         int[] fbW = new int[1];
         int[] fbH = new int[1];
         GLFW.glfwGetFramebufferSize(window.handle(), fbW, fbH);
         if (fbW[0] > 0 && fbH[0] > 0) {
            if (fbW[0] == window.getWidth() && fbH[0] == window.getHeight()) {
               if (this.width != window.getGuiScaledWidth() || this.height != window.getGuiScaledHeight()) {
                  MachineDebug.log(
                     "[Layout] syncWindowSize logical lag: screen "
                        + this.width
                        + "x"
                        + this.height
                        + " scaled "
                        + window.getGuiScaledWidth()
                        + "x"
                        + window.getGuiScaledHeight()
                  );
                  this.minecraft.resizeGui();
               }
            } else {
               MachineDebug.log(
                  "[Layout] syncWindowSize framebuffer lag: cached "
                     + window.getWidth()
                     + "x"
                     + window.getHeight()
                     + " live "
                     + fbW[0]
                     + "x"
                     + fbH[0]
                     + " screen "
                     + this.width
                     + "x"
                     + this.height
               );
               window.setWidth(fbW[0]);
               window.setHeight(fbH[0]);
               this.minecraft.resizeGui();
            }
         }
      }
   }

   public void tick() {
      super.tick();
      this.syncWindowSize();
      Tab currentTab = this.tabManager.getCurrentTab();
      if (this.lastTab != currentTab) {
         this.removeTabButtons(this.lastTab);
         if (this.lastTab == this.fakePlayerTab) {
            MachineNetworkManager.sendUnsubscribeFakeStates();
            this.fakePlayerTab.clearSelection();
         }

         if (currentTab == this.customTab) {
            this.customTab.setSearchText(this.searchText);
            lastSelectedTabIndex = 0;
         } else if (currentTab == this.fakePlayerTab) {
            this.fakePlayerTab.setSearchText(this.searchText);
            this.fakePlayerRefreshTicks = 0;
            lastSelectedTabIndex = 1;
         } else if (currentTab == this.machineTab) {
            this.machineTab.setSearchText(this.searchText);
            this.removeTabButtons(this.machineTab);
            this.machineTab.refresh();
            this.addTabButtons(this.machineTab);
            lastSelectedTabIndex = 2;
         } else {
            for (int i = 0; i < this.presetTabs.size(); i++) {
               if (currentTab == this.presetTabs.get(i)) {
                  this.presetTabs.get(i).setSearchText(this.searchText);
                  lastSelectedTabIndex = i + 3;
                  break;
               }
            }
         }

         this.addTabButtons(currentTab);
         this.updateTabDependentWidgets(currentTab);
         this.lastTab = currentTab;
      }

      if (currentTab == this.fakePlayerTab && !MachineNetworkManager.isFakePlayerStatesSupported()) {
         this.fakePlayerRefreshTicks++;
         if (this.fakePlayerRefreshTicks >= 10) {
            this.fakePlayerRefreshTicks = 0;
            boolean intervalFocused = this.fakePlayerTab.isIntervalFieldFocused();
            this.removeTabButtons(this.fakePlayerTab);
            this.fakePlayerTab.refresh();
            this.addTabButtons(this.fakePlayerTab);
            if (intervalFocused) {
               this.fakePlayerTab.restoreIntervalFieldFocus();
            }
         }
      }

      if (this.machineTab == null && MachineNetworkManager.isServerSupported()) {
         this.rebuildWidgets();
      } else {
         if (this.machineTab != null && MachineNetworkManager.getSyncVersion() != this.lastMachineSyncVersion) {
            this.lastMachineSyncVersion = MachineNetworkManager.getSyncVersion();
            boolean machineActive = this.tabManager.getCurrentTab() == this.machineTab;
            boolean structureChanged = MachineNetworkManager.getStructureVersion() != this.lastMachineStructureVersion;
            this.removeTabButtons(this.machineTab);
            if (structureChanged) {
               this.lastMachineStructureVersion = MachineNetworkManager.getStructureVersion();
               this.machineTab.refresh();
            } else {
               this.machineTab.rebuildRows();
            }

            if (machineActive) {
               this.addTabButtons(this.machineTab);
               this.updateTabDependentWidgets(currentTab);
            }
         }

         if (currentTab == this.fakePlayerTab && MachineNetworkManager.getFakeStatesVersion() != this.lastFakeStatesVersion) {
            this.lastFakeStatesVersion = MachineNetworkManager.getFakeStatesVersion();
            boolean intervalFocused = this.fakePlayerTab.isIntervalFieldFocused();
            this.removeTabButtons(this.fakePlayerTab);
            this.fakePlayerTab.refresh();
            this.addTabButtons(this.fakePlayerTab);
            if (intervalFocused) {
               this.fakePlayerTab.restoreIntervalFieldFocus();
            }
         }

         this.updateMachineSaveVisibility();
      }
   }

   private void updateMachineSaveVisibility() {
      if (this.machineSaveButton != null && this.machineTab != null) {
         boolean show = this.tabManager != null && this.tabManager.getCurrentTab() == this.machineTab && MachineNetworkManager.hasPendingMachines();
         this.machineSaveButton.visible = show;
         this.machineSaveButton.active = show;
      }
   }
}

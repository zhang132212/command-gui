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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Abilities;
import org.lwjgl.glfw.GLFW;

public class CommandGUIScreen extends Screen {
   private static final int FOOTER_HEIGHT = 58;
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
   private GuiSearchBox searchField;
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
      return Math.max(58, GuiTuning.getInt("CommandGUIScreen.FOOTER_HEIGHT", FOOTER_HEIGHT));
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
      return Math.max(26, GuiTuning.getInt("CommandGUIScreen.TAB_AREA_TOP_GAP", 26));
   }

   private int tabAreaBottomGap() {
      return Math.max(6, GuiTuning.getInt("CommandGUIScreen.TAB_AREA_BOTTOM_GAP", 6));
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
      MachineSwitchTab previousMachineTab = this.machineTab;
      this.machineTab = null;
      if (MachineNetworkManager.isServerSupported()) {
         this.machineTab = new MachineSwitchTab(this);
         this.machineTab.restoreSelection(previousMachineTab);
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

      List<Tab> tabs = new ArrayList<>();
      tabs.add(this.customTab);
      if (SettingsConfig.getBoolean("show_fakeplayer_tab")) {
         tabs.add(this.fakePlayerTab);
      }

      if (this.machineTab != null) {
         tabs.add(this.machineTab);
      }

      for (PresetCommandTab tab : this.presetTabs) {
         tabs.add(tab);
      }

      MachineDebug.log("[UI] init tabs=" + tabs.size() + " machineTab=" + (this.machineTab != null)
         + " serverSupported=" + MachineNetworkManager.isServerSupported() + " names="
         + tabs.stream().map(CommandGUIScreen::tabName).toList());
      this.tabNavigationBar = new GuiTabBar(this.tabManager, this.width, tabs);
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
      this.searchField = new GuiSearchBox(this.font, searchGroupX, closeBtnY, searchWidth, 22, Component.translatable("screen.command-gui.search_hint"));
      this.searchField.setHint(Component.translatable("screen.command-gui.search_hint"));
      this.searchField.setMaxLength(50);
      this.searchField.setValue(this.searchText);
      this.searchField.setResponder(this::onSearchChanged);
      this.addRenderableWidget(this.searchField);
      this.addButton = GuiButton.themed(Component.translatable("screen.command-gui.add_command"), button -> {
         Tab current = this.tabManager.getCurrentTab();
         if (current == this.machineTab) {
            this.minecraft.gui.setScreen(new MachineEditorScreen(this, null));
         } else {
            this.minecraft.gui.setScreen(new AddCommandScreen(this, this.customTab.getSelectedCategoryId()));
         }
      }).bounds(searchGroupX + searchWidth + 2, closeBtnY, 20, 20).build();
      this.addRenderableWidget(this.addButton);
      this.addMachineButton = GuiButton.themed(
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
      this.machineSaveButton = GuiButton.themed(Component.translatable("screen.command-gui.machine.save_pending"), b -> {
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
      this.addFakePlayerButton = GuiButton.themed(
            Component.translatable("screen.command-gui.add_fakeplayer"),
            button -> this.minecraft.gui.setScreen(new AddCommandScreen(this, this.customTab.getSelectedCategoryId(), true))
         )
         .bounds(this.padding(), closeBtnY, 88, 20)
         .build();
      this.addFakePlayerButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.add_fakeplayer_cmd")));
      this.addRenderableWidget(this.addFakePlayerButton);
      this.fpRemoveSelectedButton = GuiButton.themed(
            Component.translatable("screen.command-gui.fakeplayer.remove_selected", new Object[]{0}), b -> this.fakePlayerTab.confirmRemoveSelected()
         )
         .bounds(this.padding(), closeBtnY, 76, 20)
         .build();
      this.fpRemoveSelectedButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.fakeplayer.remove_selected_hint")));
      this.fpRemoveSelectedButton.visible = false;
      this.fpRemoveSelectedButton.active = false;
      this.addRenderableWidget(this.fpRemoveSelectedButton);
      this.fpTimedAddButton = GuiButton.themed(
            Component.translatable("screen.command-gui.fakeplayer.timed.spawn.short"), b -> this.fakePlayerTab.openTimedSpawnScreen()
         )
         .bounds(90, closeBtnY, 76, 20)
         .build();
      this.fpTimedAddButton.visible = false;
      this.fpTimedAddButton.active = false;
      this.addRenderableWidget(this.fpTimedAddButton);
      this.fpRemoveAllButton = GuiButton.themed(Component.translatable("screen.command-gui.fakeplayer.killall"), b -> this.fakePlayerTab.confirmRemoveAll())
         .bounds(170, closeBtnY, 76, 20)
         .build();
      this.fpRemoveAllButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.fakeplayer.killall.confirm_hint")));
      this.fpRemoveAllButton.visible = false;
      this.fpRemoveAllButton.active = false;
      this.addRenderableWidget(this.fpRemoveAllButton);
      this.fpBatchSpawnButton = GuiButton.themed(
            Component.translatable("screen.command-gui.fakeplayer.batch.title"), b -> this.fakePlayerTab.openBatchSpawnScreen()
         )
         .bounds(this.width - this.padding() - this.closeButtonWidth() - 4 - this.batchButtonWidth(), closeBtnY, this.batchButtonWidth(), 20)
         .build();
      this.fpBatchSpawnButton.visible = false;
      this.fpBatchSpawnButton.active = false;
      this.addRenderableWidget(this.fpBatchSpawnButton);
      this.closeButton = GuiButton.themed(Component.translatable("screen.command-gui.close"), button -> this.onClose())
         .bounds(this.width - this.padding() - this.closeButtonWidth(), closeBtnY, this.closeButtonWidth(), 20)
         .build();
      this.addRenderableWidget(this.closeButton);
      this.settingsButton = new SettingsButton(8, 6, 20, 22, btn -> this.minecraft.gui.setScreen(new SettingsScreen(this)));
      this.settingsButton.visible = true;
      this.settingsButton.active = true;
      this.addRenderableWidget(this.settingsButton);
      GuiButton.tone(this.addButton, GuiButton.Tone.PRIMARY);
      GuiButton.tone(this.addMachineButton, GuiButton.Tone.PRIMARY);
      GuiButton.tone(this.machineSaveButton, GuiButton.Tone.PRIMARY);
      GuiButton.tone(this.fpRemoveSelectedButton, GuiButton.Tone.DANGER);
      GuiButton.tone(this.fpRemoveAllButton, GuiButton.Tone.DANGER);
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
      this.showFooterWidget(this.searchField, true);
      this.showFooterWidget(this.closeButton, true);
      this.showFooterWidget(this.addButton, isCustomTab);
      this.showFooterWidget(this.addFakePlayerButton, isCustomTab);
      this.showFooterWidget(this.addMachineButton, isMachineTab);
      this.showFooterWidget(this.machineSaveButton, isMachineTab && MachineNetworkManager.hasPendingMachines());
      this.showFooterWidget(this.filterOnCheckbox, isMachineTab);
      this.showFooterWidget(this.filterOffCheckbox, isMachineTab);
      this.showFooterWidget(this.keepOpenCheckbox, !isFakePlayerTab && !isMachineTab && !isCustomTab);
      this.showFooterWidget(this.fpBatchSpawnButton, isFakePlayerTab);
      this.showFooterWidget(this.fpTimedAddButton, isFakePlayerTab);
      this.showFooterWidget(this.fpRemoveSelectedButton, isFakePlayerTab);
      this.showFooterWidget(this.fpRemoveAllButton, isFakePlayerTab && this.isOperator());
      if (isFakePlayerTab) {
         MachineNetworkManager.sendRequestFakeStates();
         ScreenRectangle area = this.fakePlayerTab.getArea();
         if (area != null) {
            int x = this.fakePlayerTab.getPlayerListX();
            int width = this.fakePlayerTab.getPlayerListWidth();
            this.fpRemoveAllButton.setX(x);
            this.fpRemoveAllButton.setY(area.bottom() - 32);
            this.fpRemoveAllButton.setWidth(width);
            this.fpRemoveAllButton.setHeight(16);
            this.fpRemoveSelectedButton.setX(x);
            this.fpRemoveSelectedButton.setY(area.bottom() - 16);
            this.fpRemoveSelectedButton.setWidth(width);
            this.fpRemoveSelectedButton.setHeight(16);
         }
         this.updateFakePlayerFooter();
      }
      this.layoutFooter();
   }

   private void showFooterWidget(AbstractWidget widget, boolean visible) {
      widget.visible = visible;
      widget.active = visible;
   }

   /** Two predictable rows keep search and actions separate, including at GUI scale 4. */
   private void layoutFooter() {
      int top = this.height - this.footerHeight() + 6;
      int left = this.padding();
      int gap = 6;
      Tab current = this.tabManager.getCurrentTab();
      this.searchField.setX(left);
      this.searchField.setY(top);
      this.closeButton.setX(this.width - left - this.closeButtonWidth());
      this.closeButton.setY(top + 26);
      this.closeButton.setWidth(this.closeButtonWidth());
      this.searchField.setWidth(Math.max(30, this.width - left * 2));
      int actionX = left;
      if (current == this.customTab) {
         actionX = this.footerButton(this.addButton, actionX, top + 26, 74) + gap;
         this.footerButton(this.addFakePlayerButton, actionX, top + 26, 88);
      } else if (current == this.machineTab && this.machineTab != null) {
         actionX = this.footerButton(this.addMachineButton, actionX, top + 26, 74) + gap;
         this.footerButton(this.machineSaveButton, actionX, top + 26, 72);
         int filterWidth = Math.max(this.font.width(this.filterOnCheckbox.getMessage()), this.font.width(this.filterOffCheckbox.getMessage())) + 20;
         int filtersX = this.width - left - filterWidth * 2 - gap;
         this.filterOnCheckbox.setX(filtersX);
         this.filterOnCheckbox.setY(top + 3);
         this.filterOnCheckbox.setWidth(filterWidth);
         this.filterOffCheckbox.setX(filtersX + filterWidth + gap);
         this.filterOffCheckbox.setY(top + 3);
         this.filterOffCheckbox.setWidth(filterWidth);
         this.searchField.setWidth(Math.max(30, filtersX - left - gap));
      } else if (current == this.fakePlayerTab) {
         actionX = this.footerButton(this.fpBatchSpawnButton, actionX, top + 26, 76) + gap;
         this.footerButton(this.fpTimedAddButton, actionX, top + 26, 88);
      } else {
         this.keepOpenCheckbox.setX(left);
         this.keepOpenCheckbox.setY(top + 26);
      }
   }

   private int footerButton(Button button, int x, int y, int width) {
      button.setX(x);
      button.setY(y);
      button.setWidth(width);
      button.setHeight(20);
      return x + width;
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      super.extractBackground(g, mouseX, mouseY, partialTick);
      g.fill(0, 0, this.width, this.height, GuiTheme.background());
      g.fill(0, 0, this.width, 34, GuiTheme.panel());
      g.fill(0, 0, this.width, 1, GuiTheme.border());
      g.fill(0, 33, this.width, 34, GuiTheme.border());
      int footerTop = this.height - this.footerHeight();
      g.fill(0, footerTop, this.width, this.height, GuiTheme.panel());
      if (this.tabManager == null || this.tabArea == null) return;
      Tab current = this.tabManager.getCurrentTab();
      int contentX = this.padding();
      if (current instanceof AbstractCommandTab commands) {
         contentX = commands.getCommandAreaLeft();
         GuiTheme.panel(g, this.padding() - 4, this.tabArea.top() - 4,
            contentX - this.padding() - 4, this.tabArea.height() + 8);
      } else if (current == this.fakePlayerTab) {
         contentX = this.fakePlayerTab.getScrollbarX() + this.scrollbarWidth() + 8;
         GuiTheme.panel(g, this.padding() - 4, this.tabArea.top() - 4,
            contentX - this.padding() - 4, this.tabArea.height() + 8);
      }
      GuiTheme.panel(g, contentX - 4, this.tabArea.top() - 4,
         this.width - contentX - this.padding() + 8, this.tabArea.height() + 8);
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
      guiGraphics.fill(0, bottomSeparatorY, this.width, bottomSeparatorY + 1, GuiTheme.border());
      if (this.tabArea != null) {
         int headingY = this.tabArea.top() - 16;
         guiGraphics.text(this.font, currentTab.getTabTitle(), this.padding(), headingY, GuiTheme.text());
         Component hint = currentTab == this.machineTab ? Component.literal("左键多选 · 确认执行 · 右键编辑")
            : Component.translatable(currentTab == this.fakePlayerTab
               ? "screen.command-gui.ui.player_hint" : "screen.command-gui.ui.command_hint");
         if (this.width > 400) {
            guiGraphics.text(this.font, hint, this.width - this.padding() - this.font.width(hint), headingY, GuiTheme.muted());
         }
         if (currentTab instanceof AbstractCommandTab commands && commands.getFilteredCommandCount() == 0) {
            Component empty = Component.translatable(this.searchText.isBlank()
               ? "screen.command-gui.ui.empty_category" : "screen.command-gui.ui.no_results");
            int emptyX = commands.getCommandAreaLeft() + commands.getCommandAreaWidth() / 2;
            int emptyY = this.tabArea.top() + this.tabArea.height() / 2;
            guiGraphics.centeredText(this.font, empty, emptyX, emptyY - 8, GuiTheme.text());
            Component help = Component.translatable(this.searchText.isBlank()
               ? "screen.command-gui.ui.empty_help" : "screen.command-gui.ui.search_help");
            guiGraphics.centeredText(this.font, help, emptyX, emptyY + 8, GuiTheme.muted());
         }
      }
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

      } else if (currentTab == this.machineTab && this.machineTab != null) {
         if (this.tabArea != null) {
            guiGraphics.enableScissor(this.tabArea.left(), this.tabArea.top(), this.tabArea.right(), this.tabArea.bottom());
         }

         this.machineTab.renderCategoryScrollbar(guiGraphics);
         if (this.tabArea != null) {
            guiGraphics.disableScissor();
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
         int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + ct.tunedCategoryTabGap();
         int catY = ct.getArea().top();
         int catH = ct.getArea().height();
         ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY, ct.tunedCategoryScrollbarWidth(), catH);
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
         int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + ct.tunedCategoryTabGap();
         int catY = ct.getArea().top();
         int catH = ct.getArea().height();
         ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY, ct.tunedCategoryScrollbarWidth(), catH);
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

      boolean handled = super.mouseClicked(mouseEvent, focused);
      // 临时诊断：确认点击是否被某个控件吃掉、以及当前标签，便于定位“标签点不动”这类问题。
      MachineDebug.log("[UI] screen click " + (int)mouseEvent.x() + "," + (int)mouseEvent.y()
         + " button=" + mouseEvent.button() + " handled=" + handled
         + " current=" + (this.tabManager.getCurrentTab() == null ? "null" : this.tabManager.getCurrentTab().getTabTitle().getString()));
      return handled;
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
         MachineDebug.log("[UI] tab switch " + (this.lastTab == null ? "null" : tabName(this.lastTab))
            + " -> " + (currentTab == null ? "null" : tabName(currentTab)));
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
            MachineDebug.log("[UI] machine page entered: machines=" + MachineNetworkManager.getMachines().size()
               + " canEdit=" + MachineNetworkManager.canEdit() + " buttons=" + this.machineTab.getButtons().size());
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

package com.remrin.client.gui;

import com.remrin.client.config.PresetConfig;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mod's main screen, containing multiple tabs (custom commands, fake player management, preset
 * commands).
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Managing the {@link TabNavigationBar} and the lifecycle of each tab</li>
 *   <li>Registering/unregistering each tab's buttons in the parent screen widget list</li>
 *   <li>Rendering the scrollbar and the bottom separator line</li>
 *   <li>Maintaining the search filter state and the "keep open after execute" checkbox state</li>
 * </ul>
 */
public class CommandGUIScreen extends Screen {

  /**
   * Height of the bottom toolbar (pixels). Kept generous so the toolbar buttons and their label
   * never touch the window's bottom edge (vertical centering leaves padding both above and below).
   */
  private static final int FOOTER_HEIGHT = 44;
  private static final int PADDING = 10;
  private static final int SCROLLBAR_WIDTH = 12;
  /**
   * Reserved right margin between the scrollbar and the window edge. Without it, the scrollbar and
   * the rightmost widgets sit flush against the window and get clipped when the window is narrow.
   */
  private static final int RIGHT_MARGIN = 16;
  /**
   * Tick interval for auto-refreshing the fake player list
   */
  private static final int REFRESH_INTERVAL = 10;
  /**
   * Remembers the last selected tab index across screen rebuilds
   */
  private static int lastSelectedTabIndex = 0;
  /**
   * Persistent "keep GUI open after execute" state, preserved across screen instances
   */
  private static boolean keepOpenAfterExecute = false;
  /**
   * Current active instance, used by the static {@link #shouldKeepOpen()} method to query the
   * checkbox state in real time
   */
  private static CommandGUIScreen currentInstance = null;
  private TabManager tabManager;
  private TabNavigationBar tabNavigationBar;
  private CustomCommandTab customTab;
  private FakePlayerTab fakePlayerTab;
  private MachineSwitchTab machineTab;
  private List<PresetCommandTab> presetTabs = new ArrayList<>();
  private EditBox searchField;
  private Button addButton;
  private Button addFakePlayerButton;
  private Button closeButton;
  private Checkbox keepOpenCheckbox;
  private SettingsButton settingsButton;
  private String searchText = "";
  private Tab lastTab = null;
  private ScreenRectangle tabArea;
  private ScreenRectangle fakePlayerArea;
  /**
   * Tick counter for periodic fake player tab refresh (refreshes every {@link #REFRESH_INTERVAL}
   * ticks)
   */
  private int fakePlayerRefreshTicks = 0;
  /**
   * Last seen machine sync version, used to refresh the machine tab when the server pushes a new
   * machine list
   */
  private int lastMachineSyncVersion = -1;
  private int lastMachineStructureVersion = -1;

  public CommandGUIScreen() {
    super(Component.translatable("screen.command-gui.title"));
  }

  public static boolean shouldKeepOpen() {
    if (currentInstance != null && currentInstance.keepOpenCheckbox != null) {
      return currentInstance.keepOpenCheckbox.selected();
    }
    return keepOpenAfterExecute;
  }

  /**
   * Checks whether the current player has command permissions: always allowed in singleplayer,
   * requires creative-mode permissions on multiplayer. Used to decide whether to show the vanilla
   * commands tab.
   */
  private boolean hasCommandPermission() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return false;
		}
    if (this.minecraft.hasSingleplayerServer()) {
      return true;
    }
    var abilities = this.minecraft.player.getAbilities();
    return abilities.instabuild;
  }

  @Override
  protected void init() {
    super.init();

    currentInstance = this;

    this.tabManager = new TabManager(w -> {
    }, w -> {
    });

    this.customTab = new CustomCommandTab(this);
    this.customTab.setOnCategoryChanged(
        () -> removeTabButtons(customTab),
        () -> addTabButtons(customTab)
    );

    this.fakePlayerTab = new FakePlayerTab(this);
    this.fakePlayerTab.setOnRebuild(
        () -> removeTabButtons(fakePlayerTab),
        () -> addTabButtons(fakePlayerTab)
    );

    // Machine switch tab: only present when the connected server supports machine switches
    this.machineTab = null;
    if (MachineNetworkManager.isServerSupported()) {
      this.machineTab = new MachineSwitchTab(this);
      this.machineTab.setOnCategoryChanged(
          () -> removeTabButtons(machineTab),
          () -> addTabButtons(machineTab)
      );
    }

    presetTabs.clear();
    boolean hasPermission = hasCommandPermission();
    for (PresetConfig.Preset preset : PresetConfig.getPresets()) {
      if ("vanilla".equals(preset.id)) {
        if (!hasPermission || !SettingsConfig.getBoolean("show_vanilla_commands")) {
          continue;
        }
      }
      if ("carpet".equals(preset.id)) {
        if (!SettingsConfig.getBoolean("show_carpet_commands")) {
          continue;
        }
      }
      PresetCommandTab tab = new PresetCommandTab(this, preset.id, preset.nameKey);
      tab.setOnCategoryChanged(
          () -> removeTabButtons(tab),
          () -> addTabButtons(tab)
      );
      presetTabs.add(tab);
    }

    MenuTabBar.Builder builder = MenuTabBar.builder(this.tabManager, this.width);
    builder.addTabs(this.customTab);
    if (SettingsConfig.getBoolean("show_fakeplayer_tab")) {
      builder.addTabs(this.fakePlayerTab);
    }
    if (this.machineTab != null) {
      builder.addTabs(this.machineTab);
    }
    for (PresetCommandTab tab : presetTabs) {
      builder.addTabs(tab);
    }

    this.tabNavigationBar = builder.build();
    this.addRenderableWidget(this.tabNavigationBar);
    this.tabNavigationBar.arrangeElements(this.width);

    int tabBarBottom = this.tabNavigationBar.getRectangle().bottom();

    // Vertically center the 20px toolbar buttons inside the 44px footer (12px above and below)
    int closeBtnY = this.height - FOOTER_HEIGHT + 12;

    // Keep-open checkbox: far left
    keepOpenCheckbox = Checkbox.builder(
        Component.translatable("screen.command-gui.keep_open"),
        this.font
    ).pos(PADDING, closeBtnY).selected(keepOpenAfterExecute).build();
    this.addRenderableWidget(keepOpenCheckbox);

    // Search bar + add buttons: centered
    int searchWidth = 90;
    int searchGroupWidth = searchWidth + 2 + 20 + 2 + 20; // 134
    int searchGroupX = this.width / 2 - searchGroupWidth / 2;
    searchField = new EditBox(this.font, searchGroupX, closeBtnY, searchWidth, 20,
        Component.translatable("screen.command-gui.search_hint"));
    searchField.setHint(Component.translatable("screen.command-gui.search_hint"));
    searchField.setMaxLength(50);
    searchField.setValue(searchText);
    searchField.setResponder(this::onSearchChanged);
    this.addRenderableWidget(searchField);

    addButton = Button.builder(
        Component.translatable("screen.command-gui.add"),
        button -> {
          Tab current = tabManager.getCurrentTab();
          if (current == machineTab) {
            this.minecraft.gui.setScreen(new MachineEditorScreen(this, null));
          } else {
            this.minecraft.gui.setScreen(
                new AddCommandScreen(this, customTab.getSelectedCategoryId()));
          }
        }
    ).bounds(searchGroupX + searchWidth + 2, closeBtnY, 20, 20).build();
    this.addRenderableWidget(addButton);

    addFakePlayerButton = Button.builder(
        Component.literal("\uD83E\uDDD1"),
        button -> this.minecraft.gui.setScreen(
            new AddFakePlayerCommandScreen(this, customTab.getSelectedCategoryId()))
    ).bounds(searchGroupX + searchWidth + 24, closeBtnY, 20, 20).build();
    addFakePlayerButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
        Component.translatable("screen.command-gui.add_fakeplayer_cmd")));
    this.addRenderableWidget(addFakePlayerButton);

    // Close button: far right
    closeButton = Button.builder(
        Component.translatable("screen.command-gui.close"),
        button -> this.onClose()
    ).bounds(this.width - PADDING - 60, closeBtnY, 60, 20).build();
    this.addRenderableWidget(closeButton);

    // Settings button: hidden (functionality kept, entry hidden)
    settingsButton = new SettingsButton(
        this.width - PADDING - 20, closeBtnY,
        20, 20,
        btn -> this.minecraft.gui.setScreen(new SettingsScreen(this))
    );
    settingsButton.visible = false;
    settingsButton.active = false;
    this.addRenderableWidget(settingsButton);

    this.tabNavigationBar.selectTab(lastSelectedTabIndex, false);

    int listTop = tabBarBottom + 4;
    // Tab area now extends to the footer (no separate search bar row)
    int tabAreaHeight = Math.max(20, this.height - FOOTER_HEIGHT - listTop - 2);

    tabArea = new ScreenRectangle(
        PADDING,
        listTop,
        this.width - PADDING - RIGHT_MARGIN - SCROLLBAR_WIDTH,
        tabAreaHeight
    );
    this.tabManager.setTabArea(tabArea);
    this.customTab.doLayout(tabArea);
    if (this.machineTab != null) {
      this.machineTab.doLayout(tabArea);
    }
    int fpAreaHeight = Math.max(20, this.height - (tabBarBottom + 4) - 4);
    fakePlayerArea = new ScreenRectangle(
        PADDING,
        tabBarBottom + 4,
        this.width - PADDING - RIGHT_MARGIN - SCROLLBAR_WIDTH,
        fpAreaHeight
    );
    this.fakePlayerTab.doLayout(fakePlayerArea);
    for (PresetCommandTab tab : presetTabs) {
      tab.doLayout(tabArea);
    }

    addTabButtons(tabManager.getCurrentTab());
    updateTabDependentWidgets(tabManager.getCurrentTab());
    lastTab = tabManager.getCurrentTab();
  }

  /**
   * Tracks tab-owned buttons currently registered in this screen, so registration is idempotent
   * and stale buttons can never linger after a tab rebuilds its lists.
   */
  private final java.util.Set<Button> registeredTabButtons = new java.util.HashSet<>();

  /**
   * Removes a tab-owned button from this screen's widget list. Public so tabs can clean up stale
   * widgets when they rebuild their own button lists.
   */
  public void removeTabButton(Button button) {
    registeredTabButtons.remove(button);
    this.removeWidget(button);
  }

  /**
   * Registers a tab-owned button, skipping it when it is already registered (prevents duplicate
   * registration when a tab rebuilds and the caller also registers).
   */
  public void addTabButton(Button button) {
    if (registeredTabButtons.add(button)) {
      this.addRenderableWidget(button);
    }
  }

  /**
   * Add only the current tab's buttons to the screen widget list.
   */
  private void addTabButtons(Tab tab) {
    if (tab instanceof AbstractCommandTab ct) {
      ct.getCategoryButtons().forEach(this::addTabButton);
      ct.getButtons().forEach(this::addTabButton);
    } else if (tab == fakePlayerTab) {
      fakePlayerTab.getButtons().forEach(this::addTabButton);
      fakePlayerTab.getIntervalFields().forEach(this::addRenderableWidget);
    }
  }

  /**
   * Remove only the given tab's buttons from the screen widget list.
   */
  private void removeTabButtons(Tab tab) {
    if (tab instanceof AbstractCommandTab ct) {
      ct.getCategoryButtons().forEach(this::removeTabButton);
      ct.getButtons().forEach(this::removeTabButton);
    } else if (tab == fakePlayerTab) {
      fakePlayerTab.getButtons().forEach(this::removeTabButton);
      fakePlayerTab.getIntervalFields().forEach(this::removeWidget);
    }
  }

  /**
   * Update visibility of widgets that depend on which tab is active.
   */
  private void updateTabDependentWidgets(Tab tab) {
    boolean isFakePlayerTab = (tab == fakePlayerTab);
    boolean isCustomTab = (tab == customTab);
    boolean isMachineTab = (tab == machineTab) && MachineNetworkManager.isServerSupported();
    // The machine tab adds machines via its sidebar "+" button; the footer add button stays for
    // the custom tab only.
    boolean showAdd = isCustomTab;
    searchField.visible = !isFakePlayerTab;
    searchField.active = !isFakePlayerTab;
    addButton.visible = showAdd;
    addButton.active = showAdd;
    addFakePlayerButton.visible = isCustomTab;
    addFakePlayerButton.active = isCustomTab;
    closeButton.visible = !isFakePlayerTab;
    closeButton.active = !isFakePlayerTab;
    keepOpenCheckbox.visible = !isFakePlayerTab && !isMachineTab;
    keepOpenCheckbox.active = !isFakePlayerTab && !isMachineTab;
    // Settings button is always hidden (functionality kept via SettingsScreen)
    settingsButton.visible = false;
    settingsButton.active = false;
  }

  private void onSearchChanged(String text) {
    searchText = text;
    Tab currentTab = tabManager.getCurrentTab();

    if (currentTab instanceof AbstractCommandTab ct) {
      removeTabButtons(ct);
      ct.setSearchText(text);
      addTabButtons(ct);
    }
  }

  /**
   * Relayouts the whole screen on a window resize by running Minecraft's native rebuild pipeline
   * ({@code clearWidgets() + init()}), exactly like the vanilla default. A hand-rolled fine-grained
   * relayout proved unreliable here: rebuilding tabs in place could leave stale button instances
   * registered on the screen ("two layers of buttons" after fullscreen <-> windowed toggles),
   * because refresh() replaces tab button lists without synchronising the screen's widget list.
   * {@code rebuildWidgets()} clears every widget first, so nothing can linger.
   */
  @Override
  public void repositionElements() {
    this.rebuildWidgets();
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    Tab currentTab = tabManager.getCurrentTab();
    if (currentTab == fakePlayerTab) {
      fakePlayerTab.scroll(scrollY);
    } else if (currentTab == machineTab && machineTab != null) {
      if (machineTab.isInCategoryArea(mouseX, mouseY)) {
        removeTabButtons(machineTab);
        machineTab.scrollCategory(scrollY);
        addTabButtons(machineTab);
      } else {
        removeTabButtons(machineTab);
        machineTab.scroll(scrollY);
        addTabButtons(machineTab);
      }
    } else if (currentTab == customTab) {
      if (customTab.isInCategoryArea(mouseX, mouseY)) {
        removeTabButtons(customTab);
        customTab.scrollCategory(scrollY);
        addTabButtons(customTab);
      } else {
        removeTabButtons(customTab);
        customTab.scroll(scrollY);
        addTabButtons(customTab);
      }
    } else {
      for (PresetCommandTab presetTab : presetTabs) {
        if (currentTab == presetTab) {
          if (presetTab.isInCategoryArea(mouseX, mouseY)) {
            removeTabButtons(presetTab);
            presetTab.scrollCategory(scrollY);
            addTabButtons(presetTab);
          } else {
            removeTabButtons(presetTab);
            presetTab.scroll(scrollY);
            addTabButtons(presetTab);
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

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    Tab currentTab = tabManager.getCurrentTab();

    if (currentTab != fakePlayerTab) {
      int bottomSeparatorY = this.height - FOOTER_HEIGHT;
      guiGraphics.fill(0, bottomSeparatorY, this.width, bottomSeparatorY + 1, 0xFF555555);
      renderScrollbar(guiGraphics, mouseX, mouseY);
    }

    if (currentTab == customTab) {
      if (tabArea != null) {
        guiGraphics.enableScissor(tabArea.left(), tabArea.top(),
            tabArea.right(), tabArea.bottom());
      }
      customTab.renderCategoryScrollbar(guiGraphics);
      if (tabArea != null) {
        guiGraphics.disableScissor();
      }

      if (customTab.isEmpty()) {
        ScreenRectangle area = customTab.getArea();
        if (area != null) {
          guiGraphics.centeredText(this.font,
              Component.translatable("screen.command-gui.empty"),
              this.width / 2, area.top() + area.height() / 2 - 4, 0xFF888888);
        }
      }
    } else if (currentTab == machineTab && machineTab != null) {
      if (tabArea != null) {
        guiGraphics.enableScissor(tabArea.left(), tabArea.top(),
            tabArea.right(), tabArea.bottom());
      }
      machineTab.renderCategoryScrollbar(guiGraphics);
      if (tabArea != null) {
        guiGraphics.disableScissor();
      }

      if (machineTab.isEmpty()) {
        ScreenRectangle area = machineTab.getArea();
        if (area != null) {
          guiGraphics.centeredText(this.font,
              Component.translatable("screen.command-gui.machine.empty"),
              this.width / 2, area.top() + area.height() / 2 - 4, 0xFF888888);
        }
      }
    } else if (currentTab == fakePlayerTab) {
      if (fakePlayerArea != null) {
        guiGraphics.enableScissor(fakePlayerArea.left(), fakePlayerArea.top(),
            fakePlayerArea.right(), fakePlayerArea.bottom());
      }
      fakePlayerTab.render(guiGraphics, mouseX, mouseY);
      fakePlayerTab.renderFaces(guiGraphics);
      fakePlayerTab.renderScrollbar(guiGraphics);
      if (fakePlayerArea != null) {
        guiGraphics.disableScissor();
      }
    } else {
      for (PresetCommandTab presetTab : presetTabs) {
        if (currentTab == presetTab) {
          if (tabArea != null) {
            guiGraphics.enableScissor(tabArea.left(), tabArea.top(),
                tabArea.right(), tabArea.bottom());
          }
          presetTab.renderCategoryScrollbar(guiGraphics);
          if (tabArea != null) {
            guiGraphics.disableScissor();
          }
          break;
        }
      }
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    if (keepOpenCheckbox != null) {
      keepOpenAfterExecute = keepOpenCheckbox.selected();
    }
    fakePlayerRefreshTicks = 0;
    currentInstance = null;
    super.onClose();
  }

  public void refresh() {
    removeTabButtons(customTab);
    customTab.refresh();
    addTabButtons(customTab);
  }

  /**
   * Renders the main scrollbar (right edge) as a styled drag handle, and stores its geometry for
   * mouse dragging. When nothing is scrollable the thumb fills the whole track (greyed out).
   */
  private void renderScrollbar(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		if (tabArea == null) {
			return;
		}

    Tab currentTab = tabManager.getCurrentTab();
    int scrollOffset;
    int maxScroll;
    int viewport;
    int content;
    if (currentTab == customTab) {
      scrollOffset = customTab.getScrollOffset();
      maxScroll = customTab.getMaxScroll();
      viewport = customTab.getVisibleRowCount();
      content = customTab.getTotalRowCount();
    } else if (currentTab == machineTab && machineTab != null) {
      scrollOffset = machineTab.getScrollOffset();
      maxScroll = machineTab.getMaxScroll();
      viewport = machineTab.getVisibleRowCount();
      content = machineTab.getTotalRowCount();
    } else if (currentTab == fakePlayerTab) {
      scrollOffset = fakePlayerTab.getScrollOffset();
      maxScroll = fakePlayerTab.getMaxScroll();
      viewport = fakePlayerTab.getVisibleRowCount();
      content = fakePlayerTab.getTotalRowCount();
    } else {
      scrollOffset = 0;
      maxScroll = 0;
      viewport = 1;
      content = 1;
      for (PresetCommandTab presetTab : presetTabs) {
        if (currentTab == presetTab) {
          scrollOffset = presetTab.getScrollOffset();
          maxScroll = presetTab.getMaxScroll();
          viewport = presetTab.getVisibleRowCount();
          content = presetTab.getTotalRowCount();
          break;
        }
      }
    }

    int scrollbarX = this.width - RIGHT_MARGIN - SCROLLBAR_WIDTH;
    int scrollbarTop = tabArea.top();
    int scrollbarHeight = tabArea.height();

    mainScrollbar = new ScrollbarHandle(scrollbarX, scrollbarTop, SCROLLBAR_WIDTH, scrollbarHeight);
    boolean hovered = mainScrollbar.contains(mouseX, mouseY);
    mainScrollbar.render(guiGraphics, scrollOffset, maxScroll, viewport, content, hovered);
    mainScrollbarMaxScroll = maxScroll;
    mainScrollbarViewport = viewport;
    mainScrollbarContent = content;
  }

  /** Geometry of the main (right-edge) scrollbar, refreshed every render frame. */
  private ScrollbarHandle mainScrollbar = null;
  private int mainScrollbarMaxScroll = 0;
  private int mainScrollbarViewport = 1;
  private int mainScrollbarContent = 1;
  /** True while the user is dragging the main scrollbar thumb. */
  private boolean draggingMainScrollbar = false;
  /** Pointer Y offset inside the thumb where the drag started. */
  private double mainScrollbarGrabOffset = 0;
  /** True while the user is dragging the category sidebar thumb. */
  private boolean draggingCategoryScrollbar = false;
  private double categoryScrollbarGrabOffset = 0;
  /** True while the user is dragging the fake player list thumb. */
  private boolean draggingFakePlayerScrollbar = false;
  private double fakePlayerScrollbarGrabOffset = 0;

  /**
   * Starts dragging a scrollbar when its track is clicked (main or category), capturing the grab
   * offset so the thumb does not jump to the pointer.
   */
  private void tryStartScrollbarDrag(double mouseX, double mouseY) {
    Tab currentTab = tabManager.getCurrentTab();

    // Category sidebar thumb (custom / machine / preset tabs)
    if (currentTab instanceof AbstractCommandTab ct && ct.getArea() != null) {
      int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + 2;
      int catY = ct.getArea().top();
      int catH = ct.getArea().height();
      ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY,
          AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH, catH);
      if (catHandle.contains(mouseX, mouseY) && ct.getMaxCategoryScroll() > 0) {
        int thumbTop = catHandle.thumbTop(ct.getCategoryScrollOffset(),
            ct.getMaxCategoryScroll(), ct.getVisibleCategoryCount(),
            ct.getAllCategoryCount());
        categoryScrollbarGrabOffset = mouseY - thumbTop;
        draggingCategoryScrollbar = true;
        return;
      }
    }

    // Fake player list scrollbar (between the list and the actions column)
    if (currentTab == fakePlayerTab) {
      int fpScrollX = fakePlayerTab.getScrollbarX();
      int fpY = fakePlayerTab.getArea().top();
      int fpH = fakePlayerTab.getArea().height();
      ScrollbarHandle fpHandle = new ScrollbarHandle(fpScrollX, fpY,
          AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH, fpH);
      if (fpHandle.contains(mouseX, mouseY) && fakePlayerTab.getMaxScroll() > 0) {
        int thumbTop = fpHandle.thumbTop(fakePlayerTab.getScrollOffset(),
            fakePlayerTab.getMaxScroll(), fakePlayerTab.getVisibleRowCount(),
            fakePlayerTab.getTotalRowCount());
        fakePlayerScrollbarGrabOffset = mouseY - thumbTop;
        draggingFakePlayerScrollbar = true;
        return;
      }
    }

    // Main (right-edge) scrollbar
    if (mainScrollbar != null && mainScrollbar.contains(mouseX, mouseY)
        && mainScrollbarMaxScroll > 0) {
      int thumbTop = mainScrollbar.thumbTop(getMainScrollOffset(), mainScrollbarMaxScroll,
          mainScrollbarViewport, mainScrollbarContent);
      mainScrollbarGrabOffset = mouseY - thumbTop;
      draggingMainScrollbar = true;
    }
  }

  /** Applies the thumb drag to the active tab's scroll offset. */
  private void applyMainScrollbarDrag(double mouseY) {
    if (mainScrollbar == null || mainScrollbarMaxScroll <= 0) {
      return;
    }
    int offset = mainScrollbar.offsetFromY(mouseY, mainScrollbarGrabOffset,
        mainScrollbarMaxScroll, mainScrollbarViewport, mainScrollbarContent);
    Tab currentTab = tabManager.getCurrentTab();
    if (currentTab == customTab) {
      removeTabButtons(customTab);
      customTab.setScrollOffset(offset);
      addTabButtons(customTab);
    } else if (currentTab == machineTab && machineTab != null) {
      removeTabButtons(machineTab);
      machineTab.setScrollOffset(offset);
      addTabButtons(machineTab);
    } else if (currentTab == fakePlayerTab) {
      fakePlayerTab.setScrollOffset(offset);
    } else {
      for (PresetCommandTab presetTab : presetTabs) {
        if (currentTab == presetTab) {
          removeTabButtons(presetTab);
          presetTab.setScrollOffset(offset);
          addTabButtons(presetTab);
          break;
        }
      }
    }
  }

  private void applyCategoryScrollbarDrag(double mouseY) {
    Tab currentTab = tabManager.getCurrentTab();
    if (!(currentTab instanceof AbstractCommandTab ct) || ct.getArea() == null) {
      return;
    }
    int catX = ct.getArea().left() + ct.getCategorySidebarOffset() + ct.categoryTabWidth() + 2;
    int catY = ct.getArea().top();
    int catH = ct.getArea().height();
    ScrollbarHandle catHandle = new ScrollbarHandle(catX, catY,
        AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH, catH);
    int offset = catHandle.offsetFromY(mouseY, categoryScrollbarGrabOffset,
        ct.getMaxCategoryScroll(), ct.getVisibleCategoryCount(), ct.getAllCategoryCount());
    removeTabButtons(ct);
    ct.setCategoryScrollOffset(offset);
    addTabButtons(ct);
  }

  private int getMainScrollOffset() {
    Tab currentTab = tabManager.getCurrentTab();
    if (currentTab == customTab) {
      return customTab.getScrollOffset();
    }
    if (currentTab == machineTab && machineTab != null) {
      return machineTab.getScrollOffset();
    }
    if (currentTab == fakePlayerTab) {
      return fakePlayerTab.getScrollOffset();
    }
    for (PresetCommandTab presetTab : presetTabs) {
      if (currentTab == presetTab) {
        return presetTab.getScrollOffset();
      }
    }
    return 0;
  }

  @Override
  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      boolean focused) {
    if (mouseEvent.button() == 0) {
      tryStartScrollbarDrag(mouseEvent.x(), mouseEvent.y());
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      double dragX, double dragY) {
    if (draggingMainScrollbar) {
      applyMainScrollbarDrag(mouseEvent.y());
      return true;
    }
    if (draggingCategoryScrollbar) {
      applyCategoryScrollbarDrag(mouseEvent.y());
      return true;
    }
    if (draggingFakePlayerScrollbar) {
      applyFakePlayerScrollbarDrag(mouseEvent.y());
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  /** Applies the fake player list thumb drag. */
  private void applyFakePlayerScrollbarDrag(double mouseY) {
    if (fakePlayerTab.getArea() == null || fakePlayerTab.getMaxScroll() <= 0) {
      return;
    }
    int fpX = fakePlayerTab.getScrollbarX();
    int fpY = fakePlayerTab.getArea().top();
    int fpH = fakePlayerTab.getArea().height();
    ScrollbarHandle fpHandle = new ScrollbarHandle(fpX, fpY,
        AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH, fpH);
    int offset = fpHandle.offsetFromY(mouseY, fakePlayerScrollbarGrabOffset,
        fakePlayerTab.getMaxScroll(), fakePlayerTab.getVisibleRowCount(),
        fakePlayerTab.getTotalRowCount());
    fakePlayerTab.setScrollOffset(offset);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent mouseEvent) {
    draggingMainScrollbar = false;
    draggingCategoryScrollbar = false;
    draggingFakePlayerScrollbar = false;
    return super.mouseReleased(mouseEvent);
  }

  /**
   * Tracks the vanilla resize pipeline reaching this screen (Minecraft.framebufferSizeChanged ->
   * resizeGui -> screen.resize). Logs so a missed event can be distinguished from a failed
   * relayout.
   */
  @Override
  public void resize(int width, int height) {
    super.resize(width, height);
  }

  /**
   * Polls the LIVE window (framebuffer) size every tick and repairs the layout when the
   * framebuffer-resize callback was skipped — F11 fullscreen toggles do not always fire it on
   * Windows, so the cached size (and therefore the screen's width/height) would keep the old
   * fullscreen values and every button would stay at fullscreen coordinates after switching to a
   * windowed mode. Two independent checks:
   * <ul>
   *   <li>the cached framebuffer size lags behind the live GLFW size (the callback never ran)</li>
   *   <li>this screen's logical size differs from the cached GUI-scaled size (the resize chain ran
   *       for the framebuffer but this screen did not receive the new dimensions)</li>
   * </ul>
   * Either way Minecraft's resize pipeline is re-run, which relayouts the current screen.
   */
  private void syncWindowSize() {
    if (this.minecraft == null || this.minecraft.getWindow() == null) {
      return;
    }
    com.mojang.blaze3d.platform.Window window = this.minecraft.getWindow();
    int[] fbW = new int[1];
    int[] fbH = new int[1];
    org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window.handle(), fbW, fbH);
    if (fbW[0] <= 0 || fbH[0] <= 0) {
      return;
    }
    if (fbW[0] != window.getWidth() || fbH[0] != window.getHeight()) {
      // The framebuffer-resize callback never ran: update the cached size and re-run the pipeline
      window.setWidth(fbW[0]);
      window.setHeight(fbH[0]);
      this.minecraft.resizeGui();
      return;
    }
    if (this.width != window.getGuiScaledWidth() || this.height != window.getGuiScaledHeight()) {
      // The framebuffer is in sync but this screen never got the new logical size
      this.minecraft.resizeGui();
    }
  }

  @Override
  public void tick() {
    super.tick();

    // F11 fullscreen toggles do not always fire a GLFW framebuffer-resize callback, leaving the
    // screen laid out for the old logical size (buttons stay at fullscreen coordinates after
    // switching to a window). Poll the LIVE window size every tick and repair the layout whenever
    // the cached size lags behind the real window.
    syncWindowSize();

    Tab currentTab = tabManager.getCurrentTab();
    if (lastTab != currentTab) {
      removeTabButtons(lastTab);
      if (lastTab == fakePlayerTab) {
        fakePlayerTab.clearSelection();
      }

      if (currentTab == customTab) {
        customTab.setSearchText(searchText);
        lastSelectedTabIndex = 0;
      } else if (currentTab == fakePlayerTab) {
        fakePlayerTab.refresh();
        fakePlayerRefreshTicks = 0;
        lastSelectedTabIndex = 1;
      } else if (currentTab == machineTab) {
        machineTab.setSearchText(searchText);
        removeTabButtons(machineTab);
        machineTab.refresh();
        addTabButtons(machineTab);
        lastSelectedTabIndex = 2;
      } else {
        for (int i = 0; i < presetTabs.size(); i++) {
          if (currentTab == presetTabs.get(i)) {
            presetTabs.get(i).setSearchText(searchText);
            lastSelectedTabIndex = i + 3;
            break;
          }
        }
      }

      addTabButtons(currentTab);
      updateTabDependentWidgets(currentTab);
      lastTab = currentTab;
    }

    if (currentTab == fakePlayerTab) {
      fakePlayerRefreshTicks++;
      if (fakePlayerRefreshTicks >= REFRESH_INTERVAL) {
        fakePlayerRefreshTicks = 0;
        removeTabButtons(fakePlayerTab);
        fakePlayerTab.refresh();
        addTabButtons(fakePlayerTab);
      }
    }

    // If the server advertised machines after this screen was opened, rebuild to add the tab.
    if (machineTab == null && MachineNetworkManager.isServerSupported()) {
      this.rebuildWidgets();
      return;
    }

    // Refresh the machine tab when the server pushes an updated machine list / state.
    // Only register the buttons into the screen when the machine tab is ACTIVE, otherwise they
    // would leak onto the other tabs (custom / fake player / presets).
    if (machineTab != null && MachineNetworkManager.getSyncVersion() != lastMachineSyncVersion) {
      lastMachineSyncVersion = MachineNetworkManager.getSyncVersion();
      boolean machineActive = (tabManager.getCurrentTab() == machineTab);
      boolean structureChanged =
          MachineNetworkManager.getStructureVersion() != lastMachineStructureVersion;
      removeTabButtons(machineTab);
      if (structureChanged) {
        // Add / edit / delete: reset scroll and pending mode selections
        lastMachineStructureVersion = MachineNetworkManager.getStructureVersion();
        machineTab.refresh();
      } else {
        // State-only sync (running / detection flips): rebuild rows in place, preserving scroll
        // position and pending mode selections
        machineTab.rebuildRows();
      }
      if (machineActive) {
        addTabButtons(machineTab);
        updateTabDependentWidgets(currentTab);
      }
    }
  }
}

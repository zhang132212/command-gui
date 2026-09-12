package com.remrin.client.gui;

import com.google.gson.Gson;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class MachineSwitchTab extends AbstractCommandTab {
   private static final int ACTION_BTN_WIDTH = 14;
   private static final int MODES_BTN_W = 48;
   private static final int REFRESH_BTN_W = 28;
   private static final int EDIT_BTN_W = 28;
   /** 开关按钮防连点窗口：只用于避免重复发包，不再像旧实现那样按 switchInterval 长时间静默吞掉点击。 */
   private static final long SWITCH_DEBOUNCE_MS = 400L;
   private static final int DELETE_BTN_W = 28;
   private static final int ACTION_CLUSTER_WIDTH = 77;
   private static final int MAX_CLUSTER_WIDTH = 135;
   private static final int CATEGORY_COLUMN_MARGIN = 4;
   private final List<MachineModels.MachineData> filteredMachines = new ArrayList<>();
   private final List<Button> extraButtons = new ArrayList<>();
   private Button addCategoryButton = null;
   private String selectedCategoryId = null;
   private MachineSwitchTab.MachineFilter filter = MachineSwitchTab.MachineFilter.ALL;
   private static final long TICK_MS = 50L;
   private final Map<String, Long> switchCooldownUntil = new HashMap<>();
   // 保存选择时的检测状态，确认时不得按已经变化的状态反向操作。
   private final Map<String, String> selectedMachines = new LinkedHashMap<>();
   private static final int CONFIRM_BAR_HEIGHT = 28;

   void restoreSelection(MachineSwitchTab previous) {
      if (previous != null) {
         this.selectedMachines.putAll(previous.selectedMachines);
         this.switchCooldownUntil.putAll(previous.switchCooldownUntil);
      }
   }

   @Override
   protected int sidebarOffset() {
      return GuiTuning.getInt("MachineSwitchTab.SIDEBAR_OFFSET", 8);
   }

   @Override
   protected int categoryTabWidth() {
      if (this.area == null) {
         return this.categoryMinWidth();
      }
      return Math.max(this.categoryMinWidth(), this.area.width() / this.categoryWidthDivisor());
   }

   private int categoryColumnX() {
      return this.area.left() + this.sidebarOffset();
   }

   private int categoryColumnWidth() {
      return this.categoryTabWidth() - this.categoryInnerMargin();
   }


   private int categoryMinWidth() {
      return GuiTuning.getInt("MachineSwitchTab.CATEGORY_MIN_WIDTH", 50);
   }

   private int categoryWidthDivisor() {
      return GuiTuning.getInt("MachineSwitchTab.CATEGORY_WIDTH_DIVISOR", 4);
   }

   private int categoryInnerMargin() {
      return GuiTuning.getInt("MachineSwitchTab.CATEGORY_INNER_MARGIN", 8);
   }

   private int categoryBottomReserve() {
      return Math.max(this.tunedCategoryRowHeight(), GuiTuning.getInt("MachineSwitchTab.CATEGORY_BOTTOM_RESERVE", 24));
   }

   private int maxClusterWidth() {
      return GuiTuning.getInt("MachineSwitchTab.MAX_CLUSTER_WIDTH", 135);
   }

   private int clusterGap() {
      return GuiTuning.getInt("MachineSwitchTab.CLUSTER_GAP", 4);
   }

   public void setMachineFilter(MachineSwitchTab.MachineFilter filter) {
      this.filter = filter != null ? filter : MachineSwitchTab.MachineFilter.ALL;
      this.rebuildRows();
   }

   public MachineSwitchTab.MachineFilter getMachineFilter() {
      return this.filter;
   }

   public MachineSwitchTab(Screen parent) {
      super(parent);
      this.buildFilteredCommands();
   }

   public Component getTabTitle() {
      return Component.translatable("screen.command-gui.tab.machine");
   }

   @Override
   protected int getFilteredCommandCount() {
      return this.filteredMachines.size();
   }

   @Override
   protected void buildFilteredCommands() {
      this.filteredMachines.clear();
      String search = this.searchText;

      for (MachineModels.MachineData machine : MachineNetworkManager.getMachines()) {
         if (this.matchesCategory(machine)
            && (this.filter != MachineSwitchTab.MachineFilter.ON || "on".equals(machine.detected))
            && (this.filter != MachineSwitchTab.MachineFilter.OFF || "off".equals(machine.detected))
            && (search.isEmpty() || machine.name.toLowerCase().contains(search) || machine.description.toLowerCase().contains(search))) {
            this.filteredMachines.add(machine);
         }
      }
   }

   private boolean matchesCategory(MachineModels.MachineData machine) {
      String category = machine.category == null ? "" : machine.category;
      if (this.selectedCategoryId == null) {
         return true;
      }
      return category.equals(this.selectedCategoryId);
   }

   @Override
   protected void buildAllCategoryButtons() {
      this.allCategoryButtons.clear();
      if (this.area != null) {
         int x = this.categoryColumnX();
         int y = this.area.top();
         boolean canDeleteCategories = MachineNetworkManager.canConfig();
         int columnWidth = this.categoryColumnWidth();
         DarkSelectButton defaultBtn = new DarkSelectButton(
            x, y, columnWidth, this.tunedCategoryTabHeight(), Component.translatable("screen.command-gui.category.default"), btn -> this.onCategoryButtonClick("")
         );
         defaultBtn.setDarkSelected(() -> Objects.equals(this.selectedCategoryId, ""), -1);
         this.allCategoryButtons.add(defaultBtn);
         Set<String> seen = new LinkedHashSet<>();

         for (MachineModels.MachineData machine : MachineNetworkManager.getMachines()) {
            String category = machine.category == null ? "" : machine.category.trim();
            if (!category.isEmpty()) {
               seen.add(category);
            }
         }

         for (String category : SettingsConfig.getStringList("machine_categories")) {
            if (!category.isBlank()) {
               seen.add(category);
            }
         }

         for (String category : seen) {
            Font font = Minecraft.getInstance().font;
            DarkSelectButton catBtn = new DarkSelectButton(
               x, y, columnWidth, this.tunedCategoryTabHeight(), Component.literal(font.plainSubstrByWidth(category, columnWidth - 4)), btn -> this.onCategoryButtonClick(category)
            );
            catBtn.setDarkSelected(() -> Objects.equals(this.selectedCategoryId, category), -1);
            if (canDeleteCategories) {
               catBtn.setOnRightClick(() -> this.openEditMachineCategoryScreen(category));
               String tip = category + "\n" + Component.translatable("screen.command-gui.category_right_click_edit").getString();
               catBtn.setTooltip(Tooltip.create(Component.literal(tip)));
            }

            this.allCategoryButtons.add(catBtn);
         }

         if (this.addCategoryButton != null && this.parent instanceof CommandGUIScreen screen) {
            screen.removeTabButton(this.addCategoryButton);
         }

         if (MachineNetworkManager.canEdit()) {
            this.addCategoryButton = GuiButton.themed(
                  Component.literal("+"), btn -> Minecraft.getInstance().gui.setScreen(new AddMachineCategoryScreen((CommandGUIScreen)this.parent))
               )
               .bounds(x, 0, columnWidth, this.tunedCategoryTabHeight())
               .build();
            this.addCategoryButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.add_category_tip")));
         } else {
            this.addCategoryButton = null;
         }
      }
   }

   public int getSwitchX() {
      if (this.area != null) {
         return this.getCommandAreaLeft();
      }
      return 0;
   }

   @Override
   protected int getVisibleCategoryCount() {
      if (this.area == null) {
         return 0;
      } else {
         int rowHeight = this.tunedCategoryRowHeight();
         if (this.addCategoryButton == null) {
            return this.area.height() / rowHeight;
         }
         return Math.max(1, (this.area.height() - this.categoryBottomReserve()) / rowHeight);
      }
   }

   public int getCategoryColumnX() {
      return this.categoryColumnX();
   }

   public int getSwitchWidth() {
      if (this.area == null) {
         return 100;
      } else {
         int rowWidth = this.area.right() - this.getCommandAreaLeft();
         int actionsWidth = GuiTuning.getInt("MachineSwitchTab.MODES_BTN_W", MODES_BTN_W)
            + GuiTuning.getInt("MachineSwitchTab.REFRESH_BTN_W", REFRESH_BTN_W) + this.clusterGap();
         return Math.max(24, rowWidth - actionsWidth - 8);
      }
   }

   public int getModesX() {
      if (this.area != null) {
         return this.area.right() - this.maxClusterWidth();
      }
      return 0;
   }

   public String getSelectedCategoryIdForNew() {
      if (this.selectedCategoryId != null && !this.selectedCategoryId.isEmpty()) {
         return this.selectedCategoryId;
      }
      return null;
   }

   @Override
   protected void rebuildVisibleCategoryButtons() {
      super.rebuildVisibleCategoryButtons();
      if (this.area != null) {
         if (this.addCategoryButton != null) {
            this.addCategoryButton.setX(this.categoryColumnX());
            this.addCategoryButton.setY(this.area.bottom() - this.categoryBottomReserve());
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

   private void onCategoryButtonClick(String categoryId) {
      if (Objects.equals(this.selectedCategoryId, categoryId)) {
         this.notifyCategoryChange(() -> {
            this.selectedCategoryId = null;
            this.scrollOffset = 0;
            this.buildFilteredCommands();
            this.buildAllCategoryButtons();
            this.rebuildVisibleCategoryButtons();
            this.rebuildButtons();
         });
      } else {
         this.notifyCategoryChange(() -> {
            this.selectedCategoryId = categoryId;
            this.scrollOffset = 0;
            this.buildFilteredCommands();
            this.buildAllCategoryButtons();
            this.rebuildVisibleCategoryButtons();
            this.rebuildButtons();
         });
      }
   }

   private void openEditMachineCategoryScreen(String category) {
      if (category != null && !category.isEmpty() && MachineNetworkManager.canConfig()) {
         Minecraft.getInstance().gui.setScreen(new EditMachineCategoryScreen((CommandGUIScreen)this.parent, category));
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
         int visibleRows = this.getVisibleRowCount();
         int start = Math.min(this.scrollOffset, Math.max(0, this.filteredMachines.size() - visibleRows));
         this.scrollOffset = start;
         int rowStep = this.tunedItemHeight();

         for (int i = 0; i < visibleRows; i++) {
            int index = start + i;
            if (index >= this.filteredMachines.size()) {
               break;
            }

            int y = this.area.top() + i * rowStep;
            this.buildRow(index, left, right, y, rowStep);
         }
         int clearWidth = 52;
         Button confirm = GuiButton.themed(Component.literal("确认执行（" + this.selectedMachines.size() + "）"),
            btn -> this.confirmSelection()).bounds(left, this.area.bottom() - 22, right - left - clearWidth - 6, 22).build();
         confirm.active = !this.selectedMachines.isEmpty();
         StringBuilder summary = new StringBuilder("左键选择多台机器，再确认执行；再次点击取消选择。\n包含其他分类或搜索条件下的已选机器：");
         for (MachineModels.MachineData machine : MachineNetworkManager.getMachines()) {
            String state = this.selectedMachines.get(machine.id);
            if (state != null) summary.append("\n").append(machine.name).append("on".equals(state) ? " → 关闭" : " → 开启");
         }
         confirm.setTooltip(Tooltip.create(Component.literal(summary.toString())));
         this.extraButtons.add(confirm);
         Button clear = GuiButton.themed(Component.literal("清空"), btn -> {
            this.selectedMachines.clear();
            this.refreshSelectionButtons();
         }).bounds(right - clearWidth, this.area.bottom() - 22, clearWidth, 22).build();
         clear.active = confirm.active;
         this.extraButtons.add(clear);
      }
   }

   private void buildRow(int index, int left, int right, int y, int rowHeight) {
      MachineModels.MachineData machine = this.filteredMachines.get(index);
      int h = rowHeight - this.tunedItemVerticalPad();
      boolean canEdit = MachineNetworkManager.canEdit();
      Font font = Minecraft.getInstance().font;
      int switchWidth = this.getSwitchWidth();
      boolean detectionEnabled = machine.detection != null && machine.detection.enabled;
      String detected = machine.detected;
      boolean transition = machine.running;
      boolean locked = false;
      String suffix;
      int color;
      if (this.hasLocalDraft(machine)) {
         suffix = "（未保存）";
         color = GuiTheme.warning();
      } else if (!detectionEnabled) {
         suffix = "（未配置检测）";
         color = -7829368;
         locked = true;
      } else if (transition) {
         suffix = "off".equals(machine.transition) ? "（正在关机...）" : "（正在开机...）";
         color = GuiTheme.warning();
         locked = true;
      } else if ("abnormal".equals(detected)) {
         suffix = "（异常）";
         color = GuiTheme.danger();
         locked = true;
      } else if ("on".equals(detected)) {
         suffix = "（已开机）";
         color = GuiTheme.accent();
      } else {
         suffix = "（已关机）";
         color = -1;
      }

      boolean selected = this.selectedMachines.containsKey(machine.id);
      if (selected) {
         suffix = "on".equals(this.selectedMachines.get(machine.id)) ? "（待关闭）" : "（待开启）";
         color = GuiTheme.accent();
      }
      int textMaxW = switchWidth - 16;
      int nameMaxW = Math.max(20, textMaxW - font.width(suffix));
      String name = machine.name;
      if (font.width(machine.name) > nameMaxW) {
         name = font.plainSubstrByWidth(machine.name, Math.max(0, nameMaxW - font.width("…"))) + "…";
      }

      MutableComponent label = Component.literal(name + suffix).withColor(color);
      boolean editedByOther = this.isLockedByOther(machine);
      boolean blocked = locked || editedByOther;
      String switchTooltip = this.buildSwitchTooltip(machine).getString()
         + "\n"
         + "左键选择/取消，点击底部确认后执行；右键编辑";
      if (editedByOther) {
         switchTooltip = switchTooltip + "\n§e" + machine.editingBy + " 正在编辑，无法开关/切换模式/删除";
      } else if (transition) {
         switchTooltip = switchTooltip + "\n§e" + ("off".equals(machine.transition) ? "正在关机中，无法编辑/切换模式" : "正在开机中，无法编辑/切换模式");
      }
      MachineSwitchTab.MachineSwitchButton switchBtn = new MachineSwitchTab.MachineSwitchButton(left, y, switchWidth, h, label, b -> {
         this.onSwitchClick(machine);
      }, () -> this.editMachine(machine));
      switchBtn.setTooltip(Tooltip.create(Component.literal(switchTooltip)));
      // 被挡住时提示原因；已经选中的条目始终允许取消选择。
      switchBtn.active = true;
      switchBtn.setVisualDisabled(blocked);
      switchBtn.setTextColor(color);
      switchBtn.selected = selected;
      this.commandButtons.add(switchBtn);
      List<Button> cluster = new ArrayList<>();
      MachineSwitchTab.ModesRowButton modesBtn = new MachineSwitchTab.ModesRowButton(
         0,
         y,
         GuiTuning.getInt("MachineSwitchTab.MODES_BTN_W", MODES_BTN_W),
         h,
         Component.translatable("screen.command-gui.machine.modes_short_btn"),
         btn -> this.openModesScreen(machine),
         () -> this.openModesListScreen(machine)
      );
      modesBtn.setDarkSelected(() -> machine.modes == null || machine.modes.isEmpty(), -1);
      modesBtn.active = machine.modes != null && !machine.modes.isEmpty() && !transition;
      String modesTip = (machine.modes != null && !machine.modes.isEmpty()
               ? Component.translatable("screen.command-gui.machine.modes_select")
               : Component.translatable("screen.command-gui.machine.no_modes_tip"))
            .getString()
         + "\n"
         + Component.translatable("screen.command-gui.machine.right_click_mode_list").getString();
      modesBtn.setTooltip(Tooltip.create(Component.literal(modesTip)));
      cluster.add(modesBtn);
      DarkSelectButton refreshBtn = new DarkSelectButton(
         0, y, GuiTuning.getInt("MachineSwitchTab.REFRESH_BTN_W", REFRESH_BTN_W), h, Component.translatable("screen.command-gui.machine.refresh_short"), btn -> MachineNetworkManager.sendRefreshDetection(machine.id)
      );
      refreshBtn.setVisualMuted(!detectionEnabled);
      refreshBtn.setTooltip(
         Tooltip.create(
            detectionEnabled
               ? Component.translatable("screen.command-gui.machine.refresh_detection")
               : Component.translatable("screen.command-gui.machine.no_detection_refresh_tip")
         )
      );
      cluster.add(refreshBtn);
      int clusterTotal = 0;

      for (Button button : cluster) {
         clusterTotal += button.getWidth();
      }

      clusterTotal += Math.max(0, cluster.size() - 1) * this.clusterGap();
      int bx = right - clusterTotal;

      for (Button button : cluster) {
         button.setX(bx);
         this.extraButtons.add(button);
         bx += button.getWidth() + this.clusterGap();
      }
   }

   private void openModesScreen(MachineModels.MachineData machine) {
      if (machine.running) {
         return;
      }
      if (this.isLockedByOther(machine)) {
         return;
      }

      Minecraft.getInstance().gui.setScreen(new MachineModesScreen((CommandGUIScreen)this.parent, machine));
   }

   private void openModesListScreen(MachineModels.MachineData machine) {
      if (!machine.running && !this.isLockedByOther(machine)) {
         MachineEditorScreen host = new MachineEditorScreen((CommandGUIScreen)this.parent, machine);
         Minecraft.getInstance().gui.setScreen(new ModesEditorScreen(host, machine.modes, machine.bots, () -> {
         }));
      }
   }

   private boolean isLockedByOther(MachineModels.MachineData machine) {
      if (machine.editingBy != null && !machine.editingBy.isEmpty()) {
         LocalPlayer player = Minecraft.getInstance().player;
         return player == null || !machine.editingBy.equals(player.getName().getString());
      } else {
         return false;
      }
   }

   @Override
   public List<Button> getButtons() {
      List<Button> all = new ArrayList<>(this.commandButtons);
      all.addAll(this.extraButtons);
      return all;
   }

   @Override
   public int getMaxScroll() {
      if (this.area != null && !this.filteredMachines.isEmpty()) {
         int visibleRows = this.getVisibleRowCount();
         return Math.max(0, this.filteredMachines.size() - visibleRows);
      } else {
         return 0;
      }
   }

   @Override
   public int getVisibleRowCount() {
      return this.area == null ? 1 : Math.max(0, (this.area.height() - CONFIRM_BAR_HEIGHT) / this.tunedItemHeight());
   }

   @Override
   public int getTotalRowCount() {
      return Math.max(1, this.filteredMachines.size());
   }

   public void refresh() {
      this.scrollOffset = 0;
      this.buildFilteredCommands();
      this.buildAllCategoryButtons();
      this.rebuildVisibleCategoryButtons();
      this.rebuildButtons();
   }

   public void rebuildRows() {
      this.buildFilteredCommands();
      this.rebuildButtons();
   }

   public boolean isEmpty() {
      return this.filteredMachines.isEmpty();
   }

   private Component buildTooltip(MachineModels.MachineData machine) {
      boolean detectionEnabled = machine.detection != null && machine.detection.enabled;
      if (!detectionEnabled) {
         return Component.translatable("screen.command-gui.machine.no_detection_tooltip");
      } else {
         StringBuilder sb = new StringBuilder();
         if (machine.description != null && !machine.description.isEmpty()) {
            sb.append(machine.description);
            sb.append("\n");
         }

         if (machine.editingBy != null && !machine.editingBy.isEmpty()) {
            sb.append("§e✎ ").append(machine.editingBy).append(" 正在编辑\n");
         }

         sb.append("§7Bots: ").append(String.join(", ", machine.bots)).append("\n");
         String var4 = machine.detected;

         sb.append(switch (var4) {
            case "on" -> "§a检测: 开机";
            case "off" -> "§f检测: 关机";
            case "abnormal" -> "§c检测: 异常（无法开关）";
            default -> "§7检测: 未知";
         }).append("\n");
         sb.append("§8")
            .append(machine.detection.blockId)
            .append(" @ ")
            .append(machine.detection.x)
            .append(" ")
            .append(machine.detection.y)
            .append(" ")
            .append(machine.detection.z);
         return Component.literal(sb.toString());
      }
   }

   private Component buildSwitchTooltip(MachineModels.MachineData machine) {
      Component base = this.buildTooltip(machine);
      return (Component)(!this.hasLocalDraft(machine) ? base : Component.literal(base.getString() + this.draftHints(machine)));
   }

   private boolean hasLocalDraft(MachineModels.MachineData machine) {
      return machine.id != null && !machine.id.isEmpty() && DraftStore.exists("machine-" + machine.id);
   }

   private String draftHints(MachineModels.MachineData machine) {
      String json = DraftStore.load("machine-" + machine.id);
      if (json == null) {
         return "\n§e机器开关未保存";
      } else {
         try {
            MachineModels.MachineData draft = (MachineModels.MachineData)new Gson().fromJson(json, MachineModels.MachineData.class);
            if (draft == null) {
               return "\n§e机器开关未保存";
            } else {
               StringBuilder sb = new StringBuilder();
               if (!sameModes(machine, draft)) {
                  sb.append("\n§e模式开关流程未保存");
               }

               if (!sameMachineConfig(machine, draft)) {
                  sb.append("\n§e机器开关未保存");
               }

               if (sb.length() == 0) {
                  sb.append("\n§e机器开关未保存");
               }

               return sb.toString();
            }
         } catch (Exception var5) {
            return "\n§e机器开关未保存";
         }
      }
   }

   private static boolean sameModes(MachineModels.MachineData a, MachineModels.MachineData b) {
      List<MachineModels.ModeData> ma = a.modes == null ? List.of() : a.modes;
      List<MachineModels.ModeData> mb = b.modes == null ? List.of() : b.modes;
      if (ma.size() != mb.size()) {
         return false;
      } else {
         for (int i = 0; i < ma.size(); i++) {
            MachineModels.ModeData x = ma.get(i);
            MachineModels.ModeData y = mb.get(i);
            if (!Objects.equals(x.id, y.id)
               || !Objects.equals(x.name, y.name)
               || !Objects.equals(x.singleSelect, y.singleSelect)
               || !sameTimeline(x.onTimeline, y.onTimeline)
               || !sameTimeline(x.offTimeline, y.offTimeline)) {
               return false;
            }
         }

         return true;
      }
   }

   private static boolean sameMachineConfig(MachineModels.MachineData a, MachineModels.MachineData b) {
      return Objects.equals(a.name, b.name)
         && Objects.equals(a.description, b.description)
         && Objects.equals(a.category, b.category)
         && a.switchInterval == b.switchInterval
         && sameTimeline(a.onTimeline, b.onTimeline)
         && sameTimeline(a.offTimeline, b.offTimeline)
         && sameDetection(a.detection, b.detection);
   }

   private static boolean sameDetection(MachineModels.DetectionData a, MachineModels.DetectionData b) {
      return a != null && b != null
         ? a.enabled == b.enabled
            && Objects.equals(a.dimension, b.dimension)
            && a.x == b.x
            && a.y == b.y
            && a.z == b.z
            && Objects.equals(a.blockId, b.blockId)
            && Objects.equals(a.property, b.property)
            && Objects.equals(a.onValues, b.onValues)
            && Objects.equals(a.offValues, b.offValues)
            && Objects.equals(a.ignoreValues, b.ignoreValues)
         : a == b;
   }

   private static boolean sameTimeline(MachineModels.Timeline a, MachineModels.Timeline b) {
      if (a != null && b != null) {
         if (a.steps == null != (b.steps == null)) {
            return false;
         } else if (a.steps == null) {
            return true;
         } else if (a.steps.size() != b.steps.size()) {
            return false;
         } else {
            for (int i = 0; i < a.steps.size(); i++) {
               MachineModels.Step sa = a.steps.get(i);
               MachineModels.Step sb = b.steps.get(i);
               if (sa != null && sb != null) {
                  if (sa.delay != sb.delay
                     || sa.commandDelay != sb.commandDelay
                     || sa.bot != sb.bot
                     || !Objects.equals(sa.kind, sb.kind)
                     || !Objects.equals(sa.description, sb.description)
                     || !Objects.equals(sa.commands, sb.commands)) {
                     return false;
                  }
               } else if (sa != sb) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return a == b;
      }
   }

   private static boolean inCooldown(Long until) {
      return until != null && System.currentTimeMillis() < until;
   }

   private void reRegisterAll() {
      if (this.parent instanceof CommandGUIScreen screen) {
         for (Button button : this.getCategoryButtons()) {
            screen.addTabButton(button);
         }

         for (Button button : this.getButtons()) {
            screen.addTabButton(button);
         }
      }
   }

   /** 左键只修改选择，不发送开关请求。 */
   private void onSwitchClick(MachineModels.MachineData machine) {
      if (this.selectedMachines.remove(machine.id) != null) {
         this.refreshSelectionButtons();
         return;
      }
      String reason = this.selectionBlockReason(machine);
      if (reason != null) {
         MachineNetworkManager.showLocalMessage("机器「" + machine.name + "」" + reason);
         return;
      }
      this.selectedMachines.put(machine.id, machine.detected);
      this.refreshSelectionButtons();
   }

   private String selectionBlockReason(MachineModels.MachineData machine) {
      if (this.hasLocalDraft(machine)) return "有未保存的编辑草稿，请先保存或放弃";
      if (machine.running) return "正在开机/关机中，请稍候";
      if (this.isLockedByOther(machine)) return "正在被其他玩家编辑";
      if (machine.detection == null || !machine.detection.enabled) return "未配置开关机检测";
      if (!"on".equals(machine.detected) && !"off".equals(machine.detected)) return "检测状态异常或未知，请刷新检测后重试";
      if (inCooldown(this.switchCooldownUntil.get(machine.id))) return "操作已提交，请稍候";
      return null;
   }

   private void refreshSelectionButtons() {
      this.rebuildButtons();
      this.reRegisterAll();
   }

   private void confirmSelection() {
      if (this.selectedMachines.isEmpty()) return;
      List<MachineModels.MachineData> batch = new ArrayList<>();
      for (Map.Entry<String, String> entry : this.selectedMachines.entrySet()) {
         MachineModels.MachineData machine = MachineNetworkManager.getMachines().stream()
            .filter(candidate -> Objects.equals(candidate.id, entry.getKey())).findFirst().orElse(null);
         if (machine == null || !Objects.equals(machine.detected, entry.getValue()) || this.selectionBlockReason(machine) != null) {
            // 整批不发送，避免部分提交后用户按原来的清单再次确认。
            this.selectedMachines.clear();
            this.refreshSelectionButtons();
            MachineNetworkManager.showLocalMessage("所选机器状态已变化或无法操作，本次未执行，请重新选择后确认");
            return;
         }
         batch.add(machine);
      }
      this.selectedMachines.clear();
      this.refreshSelectionButtons();
      for (MachineModels.MachineData machine : batch) {
         this.switchCooldownUntil.put(machine.id, System.currentTimeMillis() + SWITCH_DEBOUNCE_MS);
         MachineNetworkManager.sendToggle(machine.id);
      }
   }

   private void editMachine(MachineModels.MachineData machine) {
      if (machine.running) {
         MachineNetworkManager.showLocalMessage("机器「" + machine.name + "」正在开机/关机中，暂时无法编辑");
         return;
      }
      if (this.isLockedByOther(machine)) {
         MachineNetworkManager.showLocalMessage(machine.editingBy + " 正在编辑机器「" + machine.name + "」，无法同时编辑");
         return;
      }
      Minecraft.getInstance().gui.setScreen(new MachineEditorScreen((CommandGUIScreen)this.parent, machine));
   }

   public static enum MachineFilter {
      ALL,
      ON,
      OFF;
   }

   private final class MachineSwitchButton extends Button {
      private final Runnable onRightClick;
      private boolean visualDisabled;
      private int textColor;
      private boolean selected;

      MachineSwitchButton(int x, int y, int width, int height, Component message, OnPress onPress, Runnable onRightClick) {
         super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
         this.visualDisabled = false;
         this.textColor = -1;
         this.onRightClick = onRightClick;
      }

      void setVisualDisabled(boolean disabled) {
         this.visualDisabled = disabled;
      }

      void setTextColor(int color) {
         this.textColor = color;
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
         GuiTheme.button(guiGraphics, this, this.selected, !this.visualDisabled);
         guiGraphics.fill(this.getX(), this.getY() + 3, this.getX() + 2, this.getY() + this.getHeight() - 3, this.textColor);
         GuiTheme.label(guiGraphics, this, this.getMessage(), this.textColor, false);
      }
   }

   private final class ModesRowButton extends DarkSelectButton {
      private final Runnable onRightClick;

      ModesRowButton(int x, int y, int width, int height, Component message, OnPress onPress, Runnable onRightClick) {
         super(x, y, width, height, message, onPress);
         this.onRightClick = onRightClick;
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
         if (mouseEvent.button() == 1 && this.active && this.visible && this.isMouseOver(mouseEvent.x(), mouseEvent.y())) {
            this.onRightClick.run();
            return true;
         } else {
            return super.mouseClicked(mouseEvent, focused);
         }
      }
   }
}

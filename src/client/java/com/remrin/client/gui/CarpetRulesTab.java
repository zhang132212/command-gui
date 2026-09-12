package com.remrin.client.gui;

import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Reuses the preset-tab navigation contract, but renders live rules in one column. */
public final class CarpetRulesTab extends PresetCommandTab {
   private final List<RuleData> filtered = new ArrayList<>();
   private String selectedCategory;
   private int revision = -1;
   public CarpetRulesTab(CommandGUIScreen parent) {
      super(parent, "carpet", "screen.command-gui.preset.carpet");
      buildFilteredCommands();
   }
   @Override public Component getTabTitle() { return Component.literal("Carpet 规则"); }
   @Override protected int tunedColumns() { return 1; }
   @Override protected int sidebarOffset() { return 8; }
   @Override protected int categoryTabWidth() { return area == null ? 80 : Math.max(64, area.width() / 4); }
   @Override protected int getFilteredCommandCount() { return filtered == null ? 0 : filtered.size(); }
   @Override protected void buildFilteredCommands() {
      if (filtered == null) return; // PresetCommandTab constructor.
      filtered.clear();
      for (RuleData rule : CarpetRuleClient.rules()) {
         boolean category = selectedCategory == null || selectedCategory.equals(rule.manager())
            || rule.categories().stream().anyMatch(c -> selectedCategory.equals(rule.manager() + ":" + c));
         String text = (rule.name() + " " + rule.title() + " " + rule.description() + " " + rule.manager()).toLowerCase(Locale.ROOT);
         if (category && (searchText.isBlank() || text.contains(searchText))) filtered.add(rule);
      }
   }
   @Override protected void buildAllCategoryButtons() {
      allCategoryButtons.clear();
      if (area == null) return;
      category(null, "全部规则");
      Map<String, String> categories = new LinkedHashMap<>();
      for (RuleData rule : CarpetRuleClient.rules()) {
         categories.putIfAbsent(rule.manager(), rule.manager());
         for (int i = 0; i < rule.categories().size(); i++)
            categories.putIfAbsent(rule.manager() + ":" + rule.categories().get(i), "  " + rule.categoryLabels().get(i));
      }
      categories.forEach(this::category);
   }
   private void category(String id, String title) {
      DarkSelectButton button = new DarkSelectButton(area.left() + sidebarOffset(), area.top(), categoryTabWidth(), tunedCategoryTabHeight(),
         Component.literal(title), b -> notifyCategoryChange(() -> {
            selectedCategory = id; scrollOffset = 0; buildFilteredCommands(); rebuildButtons();
         }));
      button.setDarkSelected(() -> Objects.equals(id, selectedCategory), -1);
      button.setTooltip(Tooltip.create(Component.literal(title + (id == null ? "" : "\n" + id))));
      allCategoryButtons.add(button);
   }
   @Override public int getVisibleRowCount() { return area == null ? 1 : Math.max(0, (area.height() - 28) / tunedItemHeight()); }
   @Override public int getMaxScroll() { return Math.max(0, filtered.size() - getVisibleRowCount()); }
   @Override public int getTotalRowCount() { return Math.max(1, filtered.size()); }
   @Override public VanillaCommands.VanillaCommand getCommandAt(int i) { return null; }
   @Override public int getCommandIndexAtPosition(double x, double y) { return -1; }
   @Override protected void rebuildButtons() {
      removeOldCommandButtonsFromScreen(); commandButtons.clear();
      if (area == null) return;
      int x = getCommandAreaLeft(), width = area.right() - x;
      scrollOffset = Math.min(scrollOffset, getMaxScroll());
      for (int i = 0; i < getVisibleRowCount() && scrollOffset + i < filtered.size(); i++) {
         RuleData rule = filtered.get(scrollOffset + i);
         RuleData.Change change = CarpetRuleClient.pending.get(rule.key());
         String value = change == null ? rule.value() : change.operation().equals("removeDefault") ? "移除默认" : change.target();
         String title = rule.title() + " · " + value + (change == null ? "" : "（待确认）");
         DarkSelectButton button = new DarkSelectButton(x, area.top() + i * tunedItemHeight(), width, tunedItemHeight() - tunedItemVerticalPad(),
            Component.literal(title), b -> Minecraft.getInstance().gui.setScreen(new CarpetRuleEditScreen((CommandGUIScreen)parent, rule))) {
               @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
                  GuiTheme.button(g, this, CarpetRuleClient.pending.containsKey(rule.key()), this.active);
                  var font = Minecraft.getInstance().font;
                  int valueWidth = Math.min(72, Math.max(38, this.getWidth() / 3));
                  String display = font.plainSubstrByWidth((change == null ? "" : "→ ") + value, valueWidth);
                  int baseline = getY() + (getHeight() - 9) / 2;
                  int nameWidth = Math.max(0, getWidth() - valueWidth - 20);
                  String name = rule.title();
                  if (font.width(name) > nameWidth) name = font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("…"))) + "…";
                  g.text(font, name, getX() + 6, baseline, active ? GuiTheme.text() : GuiTheme.disabled(), false);
                  g.text(font, display, getRight() - 6 - font.width(display), baseline,
                     change == null ? GuiTheme.muted() : GuiTheme.accent(), false);
               }
            };
         button.setDarkSelected(() -> CarpetRuleClient.pending.containsKey(rule.key()), -1);
         button.active = !CarpetRuleClient.busy();
         button.setTooltip(Tooltip.create(Component.literal(rule.title() + "\n/" + rule.manager() + " " + rule.name()
            + "\n当前：" + rule.value() + "  内置默认：" + rule.builtinDefault() + "\n" + rule.description()
            + (rule.locked() ? "\n管理员已锁定此规则管理器" : "\n左键查看及选择目标值；右键取消待执行项"))));
         button.setOnRightClick(() -> { if (!CarpetRuleClient.busy()) { CarpetRuleClient.pending.remove(rule.key()); reload(); } });
         commandButtons.add(button);
      }
      int y = area.bottom() - 22, small = Math.min(48, width / 4);
      Button confirm = GuiButton.themed(Component.literal("确认（" + CarpetRuleClient.pending.size() + "）"), b -> Minecraft.getInstance().gui.setScreen(new CarpetRuleConfirmScreen((CommandGUIScreen)parent)))
         .bounds(x, y, width - (small + 4) * 2, 22).tone(GuiButton.Tone.PRIMARY).build();
      confirm.active = !CarpetRuleClient.pending.isEmpty() && !CarpetRuleClient.busy();
      commandButtons.add(confirm);
      Button clear = GuiButton.themed(Component.literal("清空"), b -> { CarpetRuleClient.pending.clear(); reload(); }).bounds(area.right() - small * 2 - 4, y, small, 22).build();
      clear.active = confirm.active; commandButtons.add(clear);
      Button refresh = GuiButton.themed(Component.literal("刷新"), b -> CarpetRuleClient.refresh()).bounds(area.right() - small, y, small, 22).build();
      refresh.active = !CarpetRuleClient.busy(); refresh.setTooltip(Tooltip.create(Component.literal(CarpetRuleClient.status()))); commandButtons.add(refresh);
   }
   public void tickRules() {
      if (revision != CarpetRuleClient.version()) { revision = CarpetRuleClient.version(); reload(); }
   }
   private void reload() {
      CommandGUIScreen screen = (CommandGUIScreen)parent;
      getCategoryButtons().forEach(screen::removeTabButton);
      buildFilteredCommands(); buildAllCategoryButtons(); rebuildVisibleCategoryButtons(); rebuildButtons();
      getCategoryButtons().forEach(screen::addTabButton); getButtons().forEach(screen::addTabButton);
   }
}

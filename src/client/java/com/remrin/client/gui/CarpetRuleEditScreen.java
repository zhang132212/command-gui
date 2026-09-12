package com.remrin.client.gui;

import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class CarpetRuleEditScreen extends BaseParentedScreen<CommandGUIScreen> {
   private final RuleData rule;
   private GuiEditBox value;
   private String target, error = "";
   private int operation, optionPage, descriptionScroll;
   private final List<Button> suggestions = new ArrayList<>();
   private static final String[] OPERATIONS = {"set", "setDefault", "removeDefault"};
   private static final String[] LABELS = {"仅本次生效", "设为默认", "移除默认"};
   public CarpetRuleEditScreen(CommandGUIScreen parent, RuleData rule) {
      super(Component.literal(rule.title()), parent); this.rule = rule; this.target = rule.value();
      RuleData.Change pending = CarpetRuleClient.pending.get(rule.key());
      if (pending != null) { target = pending.target(); operation = Arrays.asList(OPERATIONS).indexOf(pending.operation()); }
   }
   @Override protected void init() {
      super.init();
      int left = left(), w = panelWidth();
      value = new GuiEditBox(font, left, height - 86, w, 22, Component.literal("目标值"));
      value.setMaxLength(240); value.setValue(target); value.setResponder(text -> target = text);
      value.setEditable(!rule.strict()); value.active = operation != 2 && !rule.locked();
      value.setTooltip(Tooltip.create(Component.literal(rule.strict() ? "此规则只接受候选值，请点击上方选项" : "支持自定义值；具体范围由规则自身验证")));
      addRenderableWidget(value);
      addRenderableWidget(GuiButton.themed(Component.literal(LABELS[operation]), b -> {
         operation = (operation + 1) % 3; b.setMessage(Component.literal(LABELS[operation])); value.active = operation != 2 && !rule.locked();
      }).bounds(left, height - 58, w / 2 - 3, 22).tooltip(Tooltip.create(Component.literal("点击切换：本次设置 / setDefault 持久保存 / removeDefault 取消保存的默认设置"))).build());
      Button stage = GuiButton.themed(Component.literal("加入待执行"), b -> {
         try { CarpetRuleClient.stage(rule, OPERATIONS[operation], target); onClose(); }
         catch (IllegalArgumentException e) { error = e.getMessage(); }
      }).bounds(left + w / 2 + 3, height - 58, w / 2 - 3, 22).tone(GuiButton.Tone.PRIMARY).build();
      stage.active = !rule.locked() && !CarpetRuleClient.busy(); addRenderableWidget(stage);
      addRenderableWidget(GuiButton.themed(Component.literal("返回"), b -> onClose()).bounds(left, height - 30, w, 20).build());
      rebuildOptions();
   }
   private int panelWidth() { return Math.min(400, width - 32); }
   private int left() { return (width - panelWidth()) / 2; }
   private void rebuildOptions() {
      suggestions.forEach(this::removeWidget); suggestions.clear();
      int w = panelWidth(), left = left(), count = rule.options().size();
      int cols = 3, slot = (w - 32) / cols;
      for (int i = 0; i < cols && optionPage * cols + i < count; i++) {
         String option = rule.options().get(optionPage * cols + i);
         Button b = GuiButton.themed(Component.literal(option), button -> { value.setValue(option); error = ""; })
            .bounds(left + i * slot, height - 114, slot - 4, 22).tooltip(Tooltip.create(Component.literal(option))).build();
         b.active = !rule.locked(); suggestions.add(b); addRenderableWidget(b);
      }
      if (count > cols) {
         Button more = GuiButton.themed(Component.literal("›"), b -> { optionPage = (optionPage + 1) % ((count + cols - 1) / cols); rebuildOptions(); })
            .bounds(left + w - 28, height - 114, 28, 22).tooltip(Tooltip.create(Component.literal("下一组候选值"))).build();
         suggestions.add(more); addRenderableWidget(more);
      }
   }
   @Override public void tick() { super.tick(); if (!CarpetRuleClient.canView()) onClose(); }
   @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
      int lines = font.split(Component.literal(rule.description()), panelWidth() - 12).size();
      int visible = Math.max(1, (height - 180) / 12);
      descriptionScroll = Math.clamp(descriptionScroll - (int)Math.signum(sy) * 2, 0, Math.max(0, lines - visible));
      return true;
   }
   @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
      super.extractRenderState(g, mx, my, tick);
      int left = left(), w = panelWidth();
      g.text(font, font.plainSubstrByWidth(rule.title(), w), left, 10, GuiTheme.text(), false);
      g.text(font, font.plainSubstrByWidth("/" + rule.manager() + " " + rule.name() + " · " + rule.type(), w), left, 25, GuiTheme.muted(), false);
      GuiTheme.panel(g, left, 42, w, Math.max(14, height - 184));
      var lines = font.split(Component.literal(rule.description()), w - 12);
      int visible = Math.max(1, (height - 190) / 12);
      for (int i = 0; i < visible && descriptionScroll + i < lines.size(); i++)
         g.text(font, lines.get(descriptionScroll + i), left + 6, 47 + i * 12, GuiTheme.muted(), false);
      String info = error.isEmpty() ? "当前 " + rule.value() + " · 内置默认 " + rule.builtinDefault() : error;
      g.text(font, font.plainSubstrByWidth(info, w), left, height - 136, error.isEmpty() ? GuiTheme.text() : GuiTheme.danger(), false);
   }
}

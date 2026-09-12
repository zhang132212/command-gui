package com.remrin.client.gui;

import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class CarpetRuleConfirmScreen extends BaseParentedScreen<CommandGUIScreen> {
   private int offset;
   private final List<Button> rows = new ArrayList<>();
   public CarpetRuleConfirmScreen(CommandGUIScreen parent) { super(Component.literal("确认规则修改"), parent); }
   @Override protected void init() {
      super.init(); buildRows();
      int w = Math.min(400, width - 32), x = (width - w) / 2;
      addRenderableWidget(GuiButton.themed(Component.literal("返回调整"), b -> onClose()).bounds(x, height - 30, w / 2 - 3, 22).build());
      Button confirm = GuiButton.themed(Component.literal("确认执行 " + CarpetRuleClient.pending.size() + " 项"), b -> {
         CarpetRuleClient.apply(); onClose();
      }).bounds(x + w / 2 + 3, height - 30, w / 2 - 3, 22).tone(GuiButton.Tone.PRIMARY).build();
      confirm.active = !CarpetRuleClient.pending.isEmpty() && !CarpetRuleClient.busy(); addRenderableWidget(confirm);
   }
   private int visible() { return Math.max(1, (height - 96) / 28); }
   private void buildRows() {
      rows.forEach(this::removeWidget); rows.clear();
      List<RuleData.Change> changes = List.copyOf(CarpetRuleClient.pending.values());
      offset = Math.min(offset, Math.max(0, changes.size() - visible()));
      int w = Math.min(400, width - 32), x = (width - w) / 2;
      for (int i = 0; i < visible() && offset + i < changes.size(); i++) {
         RuleData.Change change = changes.get(offset + i);
         String action = switch(change.operation()) { case "setDefault" -> "设为默认"; case "removeDefault" -> "移除默认"; default -> "本次设置"; };
         String text = action + " · " + change.key() + (change.operation().equals("removeDefault") ? "" : " → " + change.target());
         Button row = GuiButton.themed(Component.literal(text), b -> {}).bounds(x, 52 + i * 28, w, 24)
            .tooltip(Tooltip.create(Component.literal(text + "\n选择时值：" + change.expected()))).build();
         rows.add(row); addRenderableWidget(row);
      }
   }
   @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
      offset = Math.clamp(offset - (int)Math.signum(sy), 0, Math.max(0, CarpetRuleClient.pending.size() - visible())); buildRows(); return true;
   }
   @Override public void tick() { super.tick(); if (!CarpetRuleClient.canView()) onClose(); }
   @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
      super.extractRenderState(g, mx, my, tick);
      g.centeredText(font, title, width / 2, 10, GuiTheme.text());
      g.centeredText(font, Component.literal("setDefault 持久保存；移除默认取消保存"), width / 2, 26, GuiTheme.muted());
      g.centeredText(font, Component.literal("逐项验证；失败不会回滚已成功项"), width / 2, 39, GuiTheme.muted());
   }
}

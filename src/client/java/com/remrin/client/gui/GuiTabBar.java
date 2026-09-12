package com.remrin.client.gui;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.input.MouseButtonEvent;

/** Keeps vanilla tab navigation, keyboard shortcuts and narration, with a compact custom skin. */
public final class GuiTabBar extends TabNavigationBar {
   public GuiTabBar(TabManager manager, int width, List<Tab> tabs) {
      super(34, 6, width - 68, 22, manager, buttons(manager, tabs), ImmutableList.copyOf(tabs));
   }

   private static ImmutableList<TabButton> buttons(TabManager manager, List<Tab> tabs) {
      ImmutableList.Builder<TabButton> buttons = ImmutableList.builder();
      for (Tab tab : tabs) buttons.add(new StyledTab(manager, tab));
      return buttons.build();
   }

   @Override
   public void arrangeElements(int width) {
      super.arrangeElements(width);
      int available = Math.max(1, width - 68);
      int gap = 4;
      int buttonWidth = Math.max(1, Math.min(100, (available - gap * (this.tabButtons.size() - 1)) / Math.max(1, this.tabButtons.size())));
      int total = buttonWidth * this.tabButtons.size() + gap * (this.tabButtons.size() - 1);
      int x = (width - total) / 2;
      for (TabButton button : this.tabButtons) {
         button.setX(x);
         button.setY(6);
         button.setWidth(buttonWidth);
         button.setTooltip(Minecraft.getInstance().font.width(button.getMessage()) > buttonWidth - 12
            ? Tooltip.create(button.getMessage()) : null);
         x += buttonWidth + gap;
      }
   }

   private static final class StyledTab extends TabButton {
      private final TabManager manager;

      StyledTab(TabManager manager, Tab tab) {
         super(manager, tab, 80, 22);
         this.manager = manager;
      }

      /**
       * 原版 {@link TabButton} 不处理点击（{@code AbstractWidget.onClick} 默认空实现），点击逻辑必须由使用方接上；
       * 旧版用的是原版 MenuTabBar（内部自己注册按钮），换成自研 TabNavigationBar 后漏掉了这一步，
       * 表现为所有标签点了没反应。
       */
      @Override
      public void onClick(MouseButtonEvent mouseEvent, boolean focused) {
         this.manager.setCurrentTab(this.tab(), false);
      }

      @Override
      protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
         if (this.isSelected() || this.isHovered() || this.isFocused()) {
            GuiTheme.rounded(g, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 4,
               this.isSelected() ? GuiTheme.selected() : GuiTheme.surface());
         }
         if (this.isFocused()) GuiTheme.outline(g, this.getX(), this.getY(), this.getWidth(), this.getHeight(), GuiTheme.accent());
         GuiTheme.label(g, this, this.getMessage(), this.isSelected() ? GuiTheme.accent() : GuiTheme.muted(), true);
      }
   }
}

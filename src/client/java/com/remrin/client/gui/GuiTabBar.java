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
import net.minecraft.client.gui.navigation.ScreenRectangle;
import com.remrin.client.machine.MachineDebug;

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
      int available = Math.max(1, width - 68);
      int gap = 4;
      int buttonWidth = Math.max(1, Math.min(100, (available - gap * (this.tabButtons.size() - 1)) / Math.max(1, this.tabButtons.size())));
      int total = buttonWidth * this.tabButtons.size() + gap * (this.tabButtons.size() - 1);
      int x = (width - total) / 2;
      this.setX(x);
      this.setY(6);
      this.setWidth(total);
      this.setHeight(22);
      for (TabButton button : this.tabButtons) {
         button.setX(x);
         button.setY(6);
         button.setWidth(buttonWidth);
         MachineDebug.log("[UI] arrange width=" + width + " btn=" + button.getMessage().getString()
            + " x=" + x + " w=" + buttonWidth + " h=22");
         button.setTooltip(Minecraft.getInstance().font.width(button.getMessage()) > buttonWidth - 12
            ? Tooltip.create(button.getMessage()) : null);
         x += buttonWidth + gap;
      }
   }

   /** Screen dispatch checks the container before its children. Use the painted buttons,
    * not the vanilla FrameLayout's LinearLayout, which our custom arrangement does not use. */
   @Override
   public boolean isMouseOver(double x, double y) {
      if (!this.visible || !this.active) return false;
      for (TabButton button : this.tabButtons) {
         if (button.visible && button.active && button.isMouseOver(x, y)) return true;
      }
      return false;
   }

   @Override
   public ScreenRectangle getRectangle() {
      return new ScreenRectangle(this.getX(), this.getY(), this.getWidth(), this.getHeight());
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (!this.isMouseOver(mouseEvent.x(), mouseEvent.y())) return false;
      MachineDebug.log("[UI] tabbar click " + (int)mouseEvent.x() + "," + (int)mouseEvent.y() + " rects=" + describeButtons(mouseEvent));
      boolean handled = super.mouseClicked(mouseEvent, focused);
      MachineDebug.log("[UI] tabbar click at " + (int)mouseEvent.x() + "," + (int)mouseEvent.y() + " -> handled=" + handled);
      return handled;
   }

   /** 诊断用：把每个标签按钮的真实几何与状态打出来，便于定位"画的位置 != 命中位置"。 */
   private String describeButtons(MouseButtonEvent mouseEvent) {
      StringBuilder sb = new StringBuilder();
      for (TabButton button : this.tabButtons) {
         sb.append('[').append(button.getMessage().getString())
            .append(' ').append(button.getX()).append(',').append(button.getY())
            .append('+').append(button.getWidth()).append('x').append(button.getHeight())
            .append(" active=").append(button.active)
            .append(" visible=").append(button.visible)
            .append(" over=").append(button.isMouseOver(mouseEvent.x(), mouseEvent.y()))
            .append(']');
      }
      return sb.toString();
   }

   private static final class StyledTab extends TabButton {
      private final TabManager manager;

      StyledTab(TabManager manager, Tab tab) {
         super(manager, tab, 80, 22);
         this.manager = manager;
      }

      /** Keep click diagnostics at the leaf as well as the parent dispatch boundary. */
      @Override
      public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
         MachineDebug.log("[UI] tabbutton.mouseClicked name=" + this.getMessage().getString()
            + " button=" + mouseEvent.button() + " active=" + this.isActive()
            + " over=" + this.isMouseOver(mouseEvent.x(), mouseEvent.y())
            + " rect=" + this.getX() + "," + this.getY() + "+" + this.getWidth() + "x" + this.getHeight()
            + " click=" + (int)mouseEvent.x() + "," + (int)mouseEvent.y());
         return super.mouseClicked(mouseEvent, focused);
      }

      @Override
      public void onClick(MouseButtonEvent mouseEvent, boolean focused) {
         MachineDebug.log("[UI] tabbutton.onClick -> " + this.getMessage().getString());
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

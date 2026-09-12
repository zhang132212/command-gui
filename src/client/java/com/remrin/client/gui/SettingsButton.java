package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

public class SettingsButton extends Button {
   private static final List<int[]> GEAR_SPANS = createGear();

   // An eight-tooth outline with a separate circular hub, sampled once at 4x resolution.
   private static List<int[]> createGear() {
      List<int[]> spans = new ArrayList<>();
      for (int y = 0; y < 64; y++) {
         int start = -1;
         for (int x = 0; x <= 64; x++) {
            double dx = x + 0.5 - 32, dy = y + 0.5 - 32;
            double radius = Math.hypot(dx, dy);
            double tooth = Math.abs(Math.IEEEremainder(Math.atan2(dy, dx), Math.PI / 4));
            double outer = tooth < 0.12 ? 28 : tooth < 0.22 ? 28 - (tooth - 0.12) * 60 : 22;
            boolean ink = x < 64 && (radius <= outer && radius >= outer - 4.5 || radius >= 8 && radius <= 12);
            if (ink && start < 0) start = x;
            if (!ink && start >= 0) {
               spans.add(new int[]{start, y, x});
               start = -1;
            }
         }
      }
      return List.copyOf(spans);
   }

   public SettingsButton(int x, int y, int width, int height, OnPress onPress) {
      super(x, y, width, height, Component.translatable("screen.command-gui.settings.title"), onPress, DEFAULT_NARRATION);
      this.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.settings.title")));
   }

   protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      if (this.isHovered() || this.isFocused()) GuiTheme.button(guiGraphics, this, false, this.active, mouseX, mouseY);
      int iconSize = 16;
      int iconX = this.getX() + (this.getWidth() - iconSize) / 2;
      int iconY = this.getY() + (this.getHeight() - iconSize) / 2;
      int color = !this.active ? GuiTheme.disabled() : this.isHovered() || this.isFocused() ? GuiTheme.accent() : GuiTheme.muted();
      var pose = guiGraphics.pose();
      pose.pushMatrix();
      pose.translate(iconX, iconY);
      pose.scale(0.25F, 0.25F);
      for (int[] span : GEAR_SPANS) guiGraphics.fill(span[0], span[1], span[2], span[1] + 1, color);
      pose.popMatrix();
   }
}

package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;

/** Shared, client-only paint primitives. Colors remain configurable through GuiTuning. */
public final class GuiTheme {
   private GuiTheme() {}

   public static int background() { return GuiTuning.getColor("GuiTheme.BACKGROUND", 0xF510151D); }
   public static int panel() { return GuiTuning.getColor("GuiTheme.PANEL", 0xFF171E28); }
   public static int surface() { return GuiTuning.getColor("GuiTheme.SURFACE", 0xFF242F3D); }
   public static int hover() { return GuiTuning.getColor("GuiTheme.HOVER", 0xFF303F4F); }
   public static int border() { return GuiTuning.getColor("GuiTheme.BORDER", 0xFF364352); }
   public static int accent() { return GuiTuning.getColor("GuiTheme.ACCENT", 0xFF8DE0CC); }
   public static int selected() { return GuiTuning.getColor("GuiTheme.SELECTED", 0xFF28453F); }
   public static int text() { return GuiTuning.getColor("GuiTheme.TEXT", 0xFFE7EFF7); }
   public static int muted() { return GuiTuning.getColor("GuiTheme.MUTED", 0xFFA5B6C8); }
   public static int disabled() { return GuiTuning.getColor("GuiTheme.DISABLED", 0xFF738395); }
   public static int danger() { return GuiTuning.getColor("GuiTheme.DANGER", 0xFFFF939A); }
   public static int warning() { return GuiTuning.getColor("GuiTheme.WARNING", 0xFFF1CA80); }

   public static void outline(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
      if (width <= 0 || height <= 0) return;
      g.fill(x, y, x + width, y + 1, color);
      g.fill(x, y + height - 1, x + width, y + height, color);
      g.fill(x, y, x + 1, y + height, color);
      g.fill(x + width - 1, y, x + width, y + height, color);
   }

   public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
      if (width <= 0 || height <= 0) return;
      rounded(g, x, y, width, height, 4, panel());
      g.fill(x + 4, y, x + width - 4, y + 1, 0xFF28323E);
   }

   /** Small code-native corners stay crisp at every Minecraft GUI scale. */
   public static void rounded(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int color) {
      if (width <= 0 || height <= 0) return;
      int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
      g.fill(x, y + r, x + width, y + height - r, color);
      for (int row = 0; row < r; row++) {
         double dy = r - row - 0.5;
         int inset = (int)Math.ceil(r - Math.sqrt(r * r - dy * dy));
         g.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
         g.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
      }
   }

   public static void button(GuiGraphicsExtractor g, AbstractWidget widget, boolean selected, boolean enabled) {
      boolean highlighted = enabled && (widget.isHovered() || widget.isFocused());
      int fill = !enabled ? panel() : selected ? selected() : highlighted ? hover() : surface();
      int x = widget.getX(), y = widget.getY(), w = widget.getWidth(), h = widget.getHeight();
      rounded(g, x, y + 1, w, h, 3, 0x70070B11);
      rounded(g, x, y, w, h, 3, highlighted ? accent() : selected && enabled ? 0xFF44695F : border());
      rounded(g, x + 1, y + 1, w - 2, h - 2, 2, fill);
      if (selected && enabled) rounded(g, x + 3, y + 6, 2, Math.max(2, h - 12), 1, accent());
   }

   /** Clip labels without losing Component styles or their full narration/tooltip text. */
   public static void label(GuiGraphicsExtractor g, AbstractWidget widget, Component label, int color, boolean centered) {
      Font font = Minecraft.getInstance().font;
      int inset = Math.min(6, Math.max(1, widget.getWidth() / 6));
      int maxWidth = Math.max(0, widget.getWidth() - inset * 2);
      int y = widget.getY() + (widget.getHeight() - 9) / 2;
      if (font.width(label) <= maxWidth) {
         int x = centered ? widget.getX() + (widget.getWidth() - font.width(label)) / 2 : widget.getX() + inset;
         g.text(font, label, x, y, color, false);
      } else {
         int dots = font.width("…");
         g.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(label, Math.max(0, maxWidth - dots))), widget.getX() + inset, y, color, false);
         if (maxWidth >= dots) g.text(font, "…", widget.getX() + widget.getWidth() - inset - dots, y, color, false);
      }
   }

   public static void editorBackground(GuiGraphicsExtractor g, int width, int height) {
      g.fill(0, 0, width, height, background());
      g.fill(0, 0, width, 1, border());
   }
}

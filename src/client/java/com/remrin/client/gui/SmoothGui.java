package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Subpixel coverage for small UI shapes; does not consume ReGlass's widget budget. */
final class SmoothGui {
   private static final int SCALE = 4;
   private SmoothGui() {}

   static void rounded(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
      shape(g, x, y, w, h, radius, 0, color);
   }

   static void outline(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, float stroke, int color) {
      shape(g, x, y, w, h, radius, stroke, color);
   }

   private static void shape(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, float stroke, int color) {
      if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
      int width = Math.max(1, Math.round(w * SCALE)), height = Math.max(1, Math.round(h * SCALE));
      float r = Math.max(0, Math.min(radius * SCALE, Math.min(width, height) / 2f));
      float thickness = Math.min(stroke * SCALE, Math.min(width, height) / 2f);
      int cap = Math.min((height + 1) / 2, Math.max(1, (int)Math.ceil(Math.max(r, thickness))));
      var pose = g.pose();
      pose.pushMatrix();
      pose.translate(x, y);
      pose.scale(1f / SCALE, 1f / SCALE);
      try {
         if (height > cap * 2) {
            if (stroke <= 0) g.fill(0, cap, width, height - cap, color);
            else {
               // Integer thickness here is a quarter GUI pixel.
               g.fill(0, cap, Math.round(thickness), height - cap, color);
               g.fill(width - Math.round(thickness), cap, width, height - cap, color);
            }
         }
         for (int row = 0; row < cap; row++) {
            float outer = inset(row + 0.5f, r);
            float inner = row + 0.5f < thickness ? width / 2f
               : thickness + inset(row + 0.5f - thickness, Math.max(0, r - thickness));
            drawRow(g, row, width, outer, inner, stroke > 0, color);
            if (height - 1 - row != row) drawRow(g, height - 1 - row, width, outer, inner, stroke > 0, color);
         }
      } finally { pose.popMatrix(); }
   }

   private static float inset(float rowCenter, float radius) {
      if (rowCenter >= radius) return 0;
      float dy = radius - rowCenter;
      return radius - (float)Math.sqrt(Math.max(0, radius * radius - dy * dy));
   }

   private static void drawRow(GuiGraphicsExtractor g, int y, int width, float outer, float inner, boolean ring, int color) {
      if (!ring || inner >= width / 2f) span(g, outer, width - outer, y, color);
      else {
         span(g, outer, inner, y, color);
         span(g, width - inner, width - outer, y, color);
      }
   }

   private static void span(GuiGraphicsExtractor g, float left, float right, int y, int color) {
      if (right <= left) return;
      int first = (int)Math.floor(left), last = (int)Math.ceil(right) - 1;
      if (first == last) { pixel(g, first, y, right - left, color); return; }
      pixel(g, first, y, first + 1 - left, color);
      if (last > first + 1) g.fill(first + 1, y, last, y + 1, color);
      pixel(g, last, y, right - last, color);
   }

   private static void pixel(GuiGraphicsExtractor g, int x, int y, float coverage, int color) {
      int alpha = Math.round((color >>> 24) * Math.clamp(coverage, 0f, 1f));
      if (alpha > 0) g.fill(x, y, x + 1, y + 1, (color & 0xFFFFFF) | (alpha << 24));
   }

   static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
      float length = (float)Math.hypot(x2 - x1, y2 - y1);
      var pose = g.pose();
      pose.pushMatrix();
      pose.translate(x1, y1);
      pose.rotate((float)Math.atan2(y2 - y1, x2 - x1));
      try { rounded(g, -thickness / 2, -thickness / 2, length + thickness, thickness, thickness / 2, color); }
      finally { pose.popMatrix(); }
   }
}

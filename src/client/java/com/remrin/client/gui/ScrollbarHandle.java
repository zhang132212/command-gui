package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class ScrollbarHandle {
   private static final int TRACK_COLOR = 0x24FFFFFF;
   private static final int THUMB_COLOR = 0x5CFFFFFF;
   private static final int THUMB_HOVER_COLOR = 0xCC8DE0CC;
   private static final int THUMB_OUTLINE = 0x33000000;
   private static final int MIN_THUMB_HEIGHT = 10;
   private final int x;
   private final int y;
   private final int width;
   private final int height;

   public ScrollbarHandle(int x, int y, int width, int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
   }

   public int maxScroll() {
      return this.height;
   }

   public void render(GuiGraphicsExtractor guiGraphics, int scrollOffset, int maxOffset, int viewport, int content, boolean hovered) {
      if (content > viewport && maxOffset > 0) {
         GuiTheme.rounded(guiGraphics, this.x + this.width / 2 - 1, this.y, 2, this.height, 1, GuiTuning.getColor("ScrollbarHandle.TRACK_COLOR", TRACK_COLOR));
         int thumbTop = this.thumbTop(scrollOffset, maxOffset, viewport, content);
         int thumbHeight = this.thumbHeight(viewport, content);
         int thumbBottom = Math.min(this.y + this.height, thumbTop + thumbHeight);
         this.drawThumb(guiGraphics, thumbTop, thumbBottom, hovered);
      }
   }

   private void drawThumb(GuiGraphicsExtractor guiGraphics, int top, int bottom, boolean hovered) {
      int bodyColor = hovered ? GuiTuning.getColor("ScrollbarHandle.THUMB_HOVER_COLOR", THUMB_HOVER_COLOR) : GuiTuning.getColor("ScrollbarHandle.THUMB_COLOR", THUMB_COLOR);
      int inset = Math.max(0, (this.width - 4) / 2);
      int thumbWidth = Math.max(1, this.width - inset * 2);
      int thumbHeight = Math.max(1, bottom - top);
      int radius = GuiTheme.clampRadius(Math.min(3, thumbWidth), thumbWidth, thumbHeight);
      GuiTheme.rounded(guiGraphics, this.x + inset, top, thumbWidth, thumbHeight, radius,
         GuiTheme.alpha(GuiTuning.getColor("ScrollbarHandle.THUMB_OUTLINE", THUMB_OUTLINE), 0x40));
      GuiTheme.rounded(guiGraphics, this.x + inset, top, Math.max(1, thumbWidth - 1), Math.max(1, thumbHeight - 1), radius, bodyColor);
      if (thumbHeight > 6) {
         guiGraphics.fill(this.x + inset + 1, top + 1, this.x + inset + thumbWidth - 1, top + 2,
            GuiTheme.alpha(0xFFFFFFFF, hovered ? 0x33 : 0x1F));
      }
   }

   public int thumbTop(int scrollOffset, int maxOffset, int viewport, int content) {
      int track = this.height - this.thumbHeight(viewport, content);
      if (maxOffset <= 0) {
         return this.y;
      }
      return this.y + track * scrollOffset / maxOffset;
   }

   public int thumbHeight(int viewport, int content) {
      if (content <= viewport) {
         return this.height;
      }
      return Math.min(this.height, Math.max(GuiTuning.getInt("ScrollbarHandle.MIN_THUMB_HEIGHT", MIN_THUMB_HEIGHT), this.height * viewport / content));
   }

   public boolean contains(double mouseX, double mouseY) {
      return mouseX >= (double)this.x && mouseX < (double)(this.x + this.width) && mouseY >= (double)this.y && mouseY < (double)(this.y + this.height);
   }

   public int offsetFromY(double mouseY, double grabOffsetY, int maxOffset, int viewport, int content) {
      int thumbH = this.thumbHeight(viewport, content);
      int track = this.height - thumbH;
      if (track > 0 && maxOffset > 0) {
         double position = mouseY - (double)this.y - grabOffsetY;
         int offset = (int)Math.round(position * (double)maxOffset / (double)track);
         return Math.max(0, Math.min(maxOffset, offset));
      } else {
         return 0;
      }
   }
}

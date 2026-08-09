package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A compact, styled vertical scrollbar handle ("drag thumb") rendered with the same layered look
 * as the vanilla new-UI widgets: a translucent track plus a rounded-gradient thumb with a bright
 * top edge and a soft shadow, brightening while hovered.
 * <p>
 * Pure rendering + geometry (no widget registration): the owning screen keeps the drag state and
 * converts pointer movement into scroll offsets via {@link #offsetFromY} / {@link #thumbTop()}.
 * The thumb length shrinks as the content-to-viewport ratio grows, and it slides proportionally
 * to the scroll offset, exactly like a native scrollbar.
 */
public final class ScrollbarHandle {

  /** Track colour: translucent dark grey (subtle, lets the thumb pop). */
  private static final int TRACK_COLOR = 0x33000000;
  /** Thumb base gradient: vanilla-button grey family. */
  private static final int THUMB_TOP = 0xFF9E9E9E;
  private static final int THUMB_BOTTOM = 0xFF7A7A7A;
  /** Thumb while hovered: lighter, matching button hover. */
  private static final int THUMB_HOVER_TOP = 0xFFB8B8B8;
  private static final int THUMB_HOVER_BOTTOM = 0xFF909090;
  /** Bright 1px edge on the thumb's left side for a subtle 3D look. */
  private static final int THUMB_EDGE = 0xFFD0D0D0;
  /** Minimum thumb height so it never becomes an un-draggable sliver. */
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

  /** Total range of the scroll (max offset). */
  public int maxScroll() {
    return height;
  }

  /**
   * Draws the scrollbar. {@code scrollOffset} is the current 0..{@code maxOffset} scroll position,
   * {@code maxOffset} the largest offset, {@code viewport} the visible span and {@code content} the
   * full content span (both in the scrolled axis); the thumb height is
   * {@code viewport / content * handleHeight}.
   */
  public void render(GuiGraphicsExtractor guiGraphics, int scrollOffset, int maxOffset,
      int viewport, int content, boolean hovered) {
    // Track
    guiGraphics.fill(x, y, x + width, y + height, TRACK_COLOR);

    if (content <= viewport || maxOffset <= 0) {
      // Nothing scrollable: show a full-height thumb (greyed) so the handle is still visible.
      drawThumb(guiGraphics, y, y + height, false);
      return;
    }
    int thumbTop = thumbTop(scrollOffset, maxOffset, viewport, content);
    int thumbHeight = thumbHeight(viewport, content);
    int thumbBottom = Math.min(y + height, thumbTop + thumbHeight);
    drawThumb(guiGraphics, thumbTop, thumbBottom, hovered);
  }

  /** Draws the thumb between the given vertical bounds with a layered 3D look. */
  private void drawThumb(GuiGraphicsExtractor guiGraphics, int top, int bottom, boolean hovered) {
    // Single colour (the lower half of the old gradient), keeping just the border for depth.
    int bodyColor = hovered ? THUMB_HOVER_BOTTOM : THUMB_BOTTOM;

    // Main body: one flat fill
    guiGraphics.fill(x + 1, top, x + width - 1, bottom, bodyColor);
    // Full 1px border: bright top + left, darker right + bottom (vanilla button look)
    guiGraphics.fill(x + 1, top, x + width - 1, top + 1, THUMB_EDGE);
    guiGraphics.fill(x + 1, top, x + 2, bottom, THUMB_EDGE);
    guiGraphics.fill(x + width - 2, top, x + width - 1, bottom, 0xFF808080);
    guiGraphics.fill(x + 1, bottom - 1, x + width - 1, bottom, 0xFF8E8E8E);
  }
  /** Top Y of the thumb for the given scroll state. */
  public int thumbTop(int scrollOffset, int maxOffset, int viewport, int content) {
    int track = height - thumbHeight(viewport, content);
    return maxOffset <= 0 ? y : y + track * scrollOffset / maxOffset;
  }

  /** Thumb height for the given viewport/content sizes. */
  public int thumbHeight(int viewport, int content) {
    if (content <= viewport) {
      return height;
    }
    return Math.max(MIN_THUMB_HEIGHT, height * viewport / content);
  }

  /** Whether the given point hits the track. */
  public boolean contains(double mouseX, double mouseY) {
    return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
  }

  /**
   * Maps a pointer Y inside the track to a scroll offset, given the grabbed offset within the thumb
   * ({@code grabOffsetY}) and the scroll geometry. This is the inverse of {@link #thumbTop()}.
   */
  public int offsetFromY(double mouseY, double grabOffsetY, int maxOffset, int viewport,
      int content) {
    int thumbH = thumbHeight(viewport, content);
    int track = height - thumbH;
    if (track <= 0 || maxOffset <= 0) {
      return 0;
    }
    double position = mouseY - y - grabOffsetY;
    int offset = (int) Math.round(position * maxOffset / track);
    return Math.max(0, Math.min(maxOffset, offset));
  }
}

package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/** A custom search surface around the vanilla editor, including its IME and selection support. */
public final class GuiSearchBox extends AbstractWidget {
   private static final List<int[]> SEARCH_SPANS = createSearchIcon();
   private final EditBox editor;
   private final Font font;
   private Component hint = Component.empty();

   public GuiSearchBox(Font font, int x, int y, int width, int height, Component message) {
      super(x, y, width, height, message);
      this.font = font;
      this.editor = new EditBox(font, message);
      this.editor.setBordered(false);
      this.editor.setTextColor(GuiTheme.text());
      this.editor.setTextColorUneditable(GuiTheme.disabled());
      this.editor.setTextShadow(false);
      this.layoutEditor();
   }

   public void setHint(Component hint) { this.hint = hint; }
   public void setMaxLength(int length) { this.editor.setMaxLength(length); }
   public void setValue(String value) { this.editor.setValue(value); }
   public String getValue() { return this.editor.getValue(); }
   public void setResponder(Consumer<String> responder) { this.editor.setResponder(responder); }

   private void layoutEditor() {
      if (this.editor == null) return;
      int inset = this.getWidth() >= 48 ? 26 : 7;
      this.editor.setX(this.getX() + inset);
      this.editor.setY(this.getY() + (this.getHeight() - this.font.lineHeight) / 2);
      this.editor.setWidth(Math.max(1, this.getWidth() - inset - 8));
      this.editor.setHeight(this.font.lineHeight);
   }

   @Override public void setX(int x) { super.setX(x); this.layoutEditor(); }
   @Override public void setY(int y) { super.setY(y); this.layoutEditor(); }
   @Override public void setWidth(int width) { super.setWidth(width); this.layoutEditor(); }
   @Override public void setHeight(int height) { super.setHeight(height); this.layoutEditor(); }

   @Override
   public void setFocused(boolean focused) {
      super.setFocused(focused);
      this.editor.setFocused(focused);
   }

   @Override
   protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
      int stroke = this.isFocused() && this.active ? GuiTheme.accent() : this.isHovered() && this.active ? GuiTheme.muted() : GuiTheme.border();
      GuiTheme.rounded(g, x, y + 1, w, h, 6, 0x50070B11);
      GuiTheme.rounded(g, x, y, w, h, 6, stroke);
      GuiTheme.rounded(g, x + 1, y + 1, w - 2, h - 2, 5, GuiTuning.getColor("GuiSearchBox.BACKGROUND", 0xFF1D2935));
      if (w >= 48) {
         int color = this.isFocused() && this.active ? GuiTheme.accent() : GuiTheme.muted();
         var pose = g.pose();
         pose.pushMatrix();
         pose.translate(x + 7, y + (h - 12) / 2.0F);
         pose.scale(0.25F, 0.25F);
         for (int[] span : SEARCH_SPANS) g.fill(span[0], span[1], span[2], span[1] + 1, color);
         pose.popMatrix();
         g.fill(x + 21, y + 6, x + 22, y + h - 6, GuiTheme.border());
      }
      this.editor.active = this.active;
      g.enableScissor(this.editor.getX(), y + 2, this.editor.getRight() + 1, y + h - 2);
      if (this.editor.getValue().isEmpty()) {
         g.text(this.font, this.hint, this.editor.getX(), this.editor.getY(), GuiTheme.muted(), false);
      }
      this.editor.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
      g.disableScissor();
   }

   @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { this.editor.onClick(event, doubleClick); }
   @Override protected void onDrag(MouseButtonEvent event, double dragX, double dragY) { this.editor.mouseDragged(event, dragX, dragY); }
   @Override public void onRelease(MouseButtonEvent event) { this.editor.onRelease(event); }
   @Override public void playDownSound(SoundManager soundManager) { }
   @Override public boolean keyPressed(KeyEvent event) { return this.active && this.visible && this.isFocused() && this.editor.keyPressed(event); }
   @Override public boolean charTyped(CharacterEvent event) { return this.active && this.visible && this.isFocused() && this.editor.charTyped(event); }
   @Override public boolean preeditUpdated(PreeditEvent event) { return this.active && this.visible && this.isFocused() && this.editor.preeditUpdated(event); }
   @Override protected void updateWidgetNarration(NarrationElementOutput output) { this.editor.updateWidgetNarration(output); }

   private static List<int[]> createSearchIcon() {
      List<int[]> spans = new ArrayList<>();
      for (int y = 0; y < 48; y++) {
         int start = -1;
         for (int x = 0; x <= 48; x++) {
            double radius = Math.hypot(x + 0.5 - 18, y + 0.5 - 18);
            boolean ring = radius >= 10 && radius <= 14;
            boolean handle = x + y >= 54 && x + y <= 80 && Math.abs(x - y) <= 3;
            boolean ink = x < 48 && (ring || handle);
            if (ink && start < 0) start = x;
            if (!ink && start >= 0) {
               spans.add(new int[]{start, y, x});
               start = -1;
            }
         }
      }
      return List.copyOf(spans);
   }
}

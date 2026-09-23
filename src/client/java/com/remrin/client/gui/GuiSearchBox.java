package com.remrin.client.gui;

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
      boolean focused = this.isFocused() && this.active;
      GuiTheme.input(g, x, y, w, h, GuiTuning.getColor("GuiSearchBox.BACKGROUND", 0x59242E40),
         focused, this.isHovered() && this.active);
      if (w >= 48) {
         int color = this.isFocused() && this.active ? GuiTheme.accent() : GuiTheme.muted();
         float iconX = x + 7, iconY = y + (h - 12) / 2f;
         SmoothGui.outline(g, iconX, iconY, 8, 8, 4, 1.25f, color);
         SmoothGui.line(g, iconX + 6.8f, iconY + 6.8f, iconX + 11, iconY + 11, 1.3f, color);
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

}

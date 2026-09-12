package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A visual replacement for vanilla buttons; callbacks and input semantics are unchanged. */
public class GuiButton extends Button {
   public enum Tone { NORMAL, PRIMARY, DANGER }
   private Tone tone = Tone.NORMAL;

   public GuiButton(int x, int y, int width, int height, Component message, OnPress onPress, CreateNarration narration) {
      super(x, y, width, height, message, onPress, narration);
   }

   public static Builder themed(Component message, OnPress onPress) { return new Builder(message, onPress); }

   public static void tone(Button button, Tone tone) {
      if (button instanceof GuiButton themed) themed.tone = tone;
   }

   @Override
   public void onPress(InputWithModifiers input) { this.onPress.onPress(this); }

   @Override
   protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      GuiTheme.button(g, this, this.tone == Tone.PRIMARY, this.active, mouseX, mouseY);
      int color = !this.active ? GuiTheme.disabled() : this.tone == Tone.DANGER ? GuiTheme.danger() : GuiTheme.text();
      GuiTheme.label(g, this, this.getMessage(), color, true);
   }

   public static final class Builder {
      private final Component message;
      private final OnPress onPress;
      private int x, y, width = 150, height = 20;
      private Tooltip tooltip;
      private CreateNarration narration = DEFAULT_NARRATION;
      private Tone tone = Tone.NORMAL;

      private Builder(Component message, OnPress onPress) { this.message = message; this.onPress = onPress; }
      public Builder pos(int x, int y) { this.x = x; this.y = y; return this; }
      public Builder width(int width) { this.width = width; return this; }
      public Builder size(int width, int height) { this.width = width; this.height = height; return this; }
      public Builder bounds(int x, int y, int width, int height) { return this.pos(x, y).size(width, height); }
      public Builder tooltip(Tooltip tooltip) { this.tooltip = tooltip; return this; }
      public Builder createNarration(CreateNarration narration) { this.narration = narration; return this; }
      public Builder tone(Tone tone) { this.tone = tone; return this; }
      public GuiButton build() {
         GuiButton button = new GuiButton(this.x, this.y, this.width, this.height, this.message, this.onPress, this.narration);
         button.tone = this.tone;
         button.setTooltip(this.tooltip != null ? this.tooltip
            : Minecraft.getInstance().font.width(this.message) > this.width - 12 ? Tooltip.create(this.message) : null);
         return button;
      }
   }
}

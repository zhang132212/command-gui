package com.remrin.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({CommandSuggestions.class})
public abstract class CommandSuggestionsMixin {
   private static final int POPUP_OFFSET = 4;
   @Shadow
   @Final
   private EditBox input;
   @Shadow
   @Final
   private boolean anchorToBottom;

   @ModifyArg(
      method = {"showSuggestions"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/CommandSuggestions$SuggestionsList;<init>(Lnet/minecraft/client/gui/components/CommandSuggestions;IIILjava/util/List;Z)V"
      ),
      index = 2
   )
   private int relocateSuggestionListY(int y) {
      if (this.anchorToBottom) {
         return y;
      }
      return this.input.getY() + this.input.getHeight() + 4;
   }

   @Redirect(
      method = {"extractUsage"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
      )
   )
   private void relocateUsageLine(GuiGraphicsExtractor guiGraphics, int x1, int y1, int x2, int y2, int color) {
      if (this.anchorToBottom) {
         guiGraphics.fill(x1, y1, x2, y2, color);
      } else {
         int base = this.input.getY() + this.input.getHeight() + 4;
         int row = (y1 - 72) / 12;
         guiGraphics.fill(x1, base + row * 12, x2, base + row * 12 + 12, color);
      }
   }

   @Redirect(
      method = {"extractUsage"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"
      )
   )
   private void relocateUsageText(GuiGraphicsExtractor guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color) {
      if (this.anchorToBottom) {
         guiGraphics.text(font, text, x, y, color);
      } else {
         int base = this.input.getY() + this.input.getHeight() + 4;
         int row = (y - 72) / 12;
         guiGraphics.text(font, text, x, base + row * 12 + 2, color);
      }
   }
}

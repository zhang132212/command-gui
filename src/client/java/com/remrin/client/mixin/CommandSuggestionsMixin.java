package com.remrin.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the vanilla tab-completion popup follow its EditBox instead of using a fixed position
 * (y=72 or bottom-anchored), so screens can place the command field anywhere.
 * <p>
 * Only applies when the popup is NOT anchored to the bottom ({@code anchorToBottom = false}),
 * keeping the mod's other editors (which use the bottom-anchored style) unchanged.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

  /** Gap between the command field's bottom edge and the suggestion popup's top edge. */
  private static final int POPUP_OFFSET = 4;

  @Shadow
  @Final
  private EditBox input;

  @Shadow
  @Final
  private boolean anchorToBottom;

  /**
   * Relocates the suggestion list to just below the command field.
   * Constructor args: (this$0, int x, int y, int width, List, boolean) — y is arg index 2.
   */
  @ModifyArg(
      method = "showSuggestions",
      at = @At(
          value = "INVOKE",
          target = "Lnet/minecraft/client/gui/components/CommandSuggestions$SuggestionsList;<init>"
              + "(Lnet/minecraft/client/gui/components/CommandSuggestions;IIILjava/util/List;Z)V"),
      index = 2)
  private int relocateSuggestionListY(int y) {
    if (anchorToBottom) {
      return y;
    }
    return input.getY() + input.getHeight() + POPUP_OFFSET;
  }

  /**
   * Relocates the command-usage line to the same column as the suggestion popup.
   */
  @Redirect(
      method = "extractUsage",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill"
          + "(IIIII)V"))
  private void relocateUsageLine(GuiGraphicsExtractor guiGraphics, int x1, int y1, int x2, int y2,
      int color) {
    if (anchorToBottom) {
      guiGraphics.fill(x1, y1, x2, y2, color);
      return;
    }
    int base = input.getY() + input.getHeight() + POPUP_OFFSET;
    int row = (y1 - 72) / 12;
    guiGraphics.fill(x1, base + row * 12, x2, base + row * 12 + 12, color);
  }

  /**
   * Relocates the usage-line TEXT to the same place as its background box, so the error hint (e.g.
   * "Expected integer at ... <--[HERE]") no longer renders detached from the relocated black
   * background — previously the box moved below the command field while the text stayed at the
   * vanilla y=72 position, leaving a stray black block and stray text over the editor UI.
   */
  @Redirect(
      method = "extractUsage",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text"
              + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
  private void relocateUsageText(GuiGraphicsExtractor guiGraphics, Font font,
      FormattedCharSequence text, int x, int y, int color) {
    if (anchorToBottom) {
      guiGraphics.text(font, text, x, y, color);
      return;
    }
    int base = input.getY() + input.getHeight() + POPUP_OFFSET;
    int row = (y - 72) / 12;
    guiGraphics.text(font, text, x, base + row * 12 + 2, color);
  }
}

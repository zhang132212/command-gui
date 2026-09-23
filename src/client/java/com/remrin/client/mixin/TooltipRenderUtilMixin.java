package com.remrin.client.mixin;

import com.remrin.client.gui.BaseParentedScreen;
import com.remrin.client.gui.CommandGUIScreen;
import com.remrin.client.gui.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {
    @Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
    private static void commandGui$tooltipBackground(GuiGraphicsExtractor graphics, int x, int y,
                                                     int width, int height, Identifier sprite,
                                                     CallbackInfo ci) {
        var screen = Minecraft.getInstance().gui.screen();
        if (screen instanceof CommandGUIScreen || screen instanceof BaseParentedScreen<?>) {
            GuiTheme.popup(graphics, x - 3, y - 3, width + 6, height + 6);
            ci.cancel();
        }
    }
}

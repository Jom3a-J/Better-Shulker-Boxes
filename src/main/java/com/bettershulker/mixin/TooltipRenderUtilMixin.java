package com.bettershulker.mixin;

import com.bettershulker.client.render.ModernTooltipFrame;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the vanilla tooltip background and frame for Modern-theme container previews, so the
 * panel reads as its own window rather than a picture inside a vanilla tooltip.
 *
 * <p>Every other tooltip in the game, including Better Shulker's own tooltips under the other
 * themes, is left untouched.</p>
 */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {

    @Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
    private static void bettershulker$skipFrameForModernTheme(GuiGraphicsExtractor context,
                                                               int x, int y, int width, int height,
                                                               Identifier style, CallbackInfo ci) {
        if (ModernTooltipFrame.consumeSuppressNextBackground()) {
            ci.cancel();
        }
    }
}

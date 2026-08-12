package com.bettershulker.mixin;

import com.bettershulker.client.render.ModernTooltipFrame;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Records whether the tooltip about to be drawn is a Modern-theme container preview.
 *
 * <p>This is the last point that still sees the component list; the background renderer below it
 * only receives a rectangle. See {@link ModernTooltipFrame}.</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsTooltipMixin {

    @Inject(method = "tooltip", at = @At("HEAD"))
    private void bettershulker$flagModernTooltip(Font font, List<ClientTooltipComponent> components,
                                                  int x, int y, ClientTooltipPositioner positioner,
                                                  Identifier style, CallbackInfo ci) {
        ModernTooltipFrame.setSuppressNextBackground(ModernTooltipFrame.shouldSuppressFrame(components));
    }
}

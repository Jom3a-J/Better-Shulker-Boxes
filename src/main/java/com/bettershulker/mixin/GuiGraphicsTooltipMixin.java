package com.bettershulker.mixin;

import com.bettershulker.client.render.ModernTooltipFrame;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

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
 *
 * <p>NeoForge adds a {@code tooltip} overload that also takes the hovered stack, and item
 * tooltips go straight to it without passing through the vanilla one. Both are hooked; the
 * NeoForge one is optional so Fabric and Quilt, which lack it, still load.</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsTooltipMixin {

    @Inject(method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"))
    private void bettershulker$flagModernTooltip(Font font, List<ClientTooltipComponent> components,
                                                  int x, int y, ClientTooltipPositioner positioner,
                                                  Identifier style, boolean unusedFlag, CallbackInfo ci) {
        ModernTooltipFrame.setSuppressNextBackground(ModernTooltipFrame.shouldSuppressFrame(components));
    }

    @Inject(method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;ZLnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), require = 0)
    private void bettershulker$flagModernTooltipNeoForge(Font font, List<ClientTooltipComponent> components,
                                                          int x, int y, ClientTooltipPositioner positioner,
                                                          Identifier style, boolean unusedFlag, ItemStack stack,
                                                          CallbackInfo ci) {
        ModernTooltipFrame.setSuppressNextBackground(ModernTooltipFrame.shouldSuppressFrame(components));
    }
}

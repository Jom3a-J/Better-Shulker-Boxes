package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.EnderChestCache;
import com.bettershulker.client.ClientKeybinds;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Mixin for ItemStack to inject the custom container preview tooltip.
 * Intercepts getTooltipImage to provide custom ShulkerTooltipData.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    // =========================================================================
    //  Mixin Injections
    // =========================================================================

    /**
     * Intercepts ItemStack::getTooltipImage to inject the Better Shulker tooltip component
     * for Shulker Boxes and Ender Chests if the feature is enabled.
     */
    @Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
    private void bettershulker$injectGetTooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;

        ItemStack self = (ItemStack) (Object) this;

        if (ContainerHelper.isShulkerBox(self)) {
            NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(self);
            if (ClientKeybinds.isCompactModeActive() && bettershulker$isEmpty(contents)) {
                ci.setReturnValue(Optional.empty());
                return;
            }
            String selectedItemName = "";
            int selectedIndex = BetterShulkerClient.getSelectedSlotIndex();
            if (selectedIndex >= 0 && selectedIndex < contents.size()) {
                ItemStack selectedStack = contents.get(selectedIndex);
                if (!selectedStack.isEmpty()) {
                    selectedItemName = selectedStack.getHoverName().getString();
                }
            }
            var color = ContainerHelper.getShulkerColor(self);
            ci.setReturnValue(Optional.of(new ShulkerTooltipData(contents, color, false, selectedItemName, self.getHoverName().getString())));
        } else if (ContainerHelper.isEnderChest(self)) {
            var player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive() || player.isSpectator()
                    || !ContainerHelper.canAccessContainer(self, player)) {
                ci.setReturnValue(Optional.empty());
                return;
            }

            // Ender Chest contents can change through vanilla screens or other mods, so refresh
            // while the tooltip is in use instead of treating the first snapshot as permanent.
            EnderChestCache.requestEnderChestSync();
            NonNullList<ItemStack> cachedContents = EnderChestCache.getEnderChestContents();
            if (cachedContents == null) {
                ci.setReturnValue(Optional.empty());
                return;
            }
            if (ClientKeybinds.isCompactModeActive() && bettershulker$isEmpty(cachedContents)) {
                ci.setReturnValue(Optional.empty());
                return;
            }
            String selectedItemName = "";
            int selectedIndex = BetterShulkerClient.getSelectedSlotIndex();
            if (selectedIndex >= 0 && selectedIndex < cachedContents.size()) {
                ItemStack selectedStack = cachedContents.get(selectedIndex);
                if (!selectedStack.isEmpty()) {
                    selectedItemName = selectedStack.getHoverName().getString();
                }
            }
            ci.setReturnValue(Optional.of(new ShulkerTooltipData(cachedContents, null, true, selectedItemName, self.getHoverName().getString())));
        }
    }

    private boolean bettershulker$isEmpty(NonNullList<ItemStack> contents) {
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

}

package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
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
            if (BetterShulkerClient.isCompactModeActive() && bettershulker$isEmpty(contents)) {
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
            NonNullList<ItemStack> cachedContents = BetterShulkerClient.getEnderChestContents();
            if (cachedContents == null) {
                BetterShulkerClient.requestEnderChestSync();
                ci.setReturnValue(Optional.empty());
                return;
            }
            if (BetterShulkerClient.isCompactModeActive() && bettershulker$isEmpty(cachedContents)) {
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

    @Inject(at = @At("RETURN"), method =
            "getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;", cancellable = true)
    private void bettershulker$hideCompactContainerName(Item.TooltipContext context, Player player, TooltipFlag type,
                                                       CallbackInfoReturnable<List<Component>> ci) {
        if (!BetterShulkerConfig.tooltipEnabled || !BetterShulkerClient.isCompactModeActive()) return;

        ItemStack self = (ItemStack) (Object) this;
        if (!ContainerHelper.isShulkerBox(self) && !ContainerHelper.isEnderChest(self)) return;

        List<Component> tooltip = ci.getReturnValue();
        if (tooltip == null || tooltip.isEmpty()) return;

        // Compact mode draws the selected item name with the preview component.
        // Replace the vanilla text list outright so immutable/wrapped lists cannot leave
        // the container name behind and visually merge it with the selected item name.
        ci.setReturnValue(new java.util.ArrayList<>());
    }
}

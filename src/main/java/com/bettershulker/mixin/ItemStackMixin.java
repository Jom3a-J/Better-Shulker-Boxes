package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
    private void bettershulker$injectGetTooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;

        ItemStack self = (ItemStack) (Object) this;

        if (ContainerHelper.isShulkerBox(self)) {
            NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(self);
            boolean empty = true;
            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
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
            boolean empty = true;
            for (ItemStack stack : cachedContents) {
                if (!stack.isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
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
}

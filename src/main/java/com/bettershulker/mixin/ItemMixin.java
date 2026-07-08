package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.platform.PlatformNetworking;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for Item to add native bundle-like slot click interactions for Shulker Boxes and Ender Chests.
 * Handles item insertion and extraction from within the inventory UI screen.
 */
@Mixin(Item.class)
public abstract class ItemMixin {

    // =========================================================================
    //  Slot Intercept Hooks & Click Overrides
    // =========================================================================

    /**
     * Called when the player holds this item (carried) and right-clicks on another slot.
     */
    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void bettershulker$overrideStackedOnOther(ItemStack stack, Slot slot, ClickAction clickAction, Player player, CallbackInfoReturnable<Boolean> ci) {
        if (!bettershulker$canHandleContainerClick(stack, slot, clickAction, player)) {
            return;
        }

        if (ContainerHelper.isShulkerBox(stack)) {
            ItemStack slotStack = slot.getItem();
            if (slotStack.isEmpty()) {
                // Carried Shulker Box, right-click on empty slot -> Extract/dump first item
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(stack);
                int extractionIndex = bettershulker$firstOccupiedSlot(contents);
                if (extractionIndex != -1) {
                    ItemStack extracted = ContainerHelper.tryExtract(contents, extractionIndex, false);
                    slot.set(extracted);
                    ContainerHelper.setContainerContents(stack, contents);
                    bettershulker$playLevelSound(player, extracted, false);
                    ci.setReturnValue(true);
                }
            } else {
                // Carried Shulker Box, right-click on stack -> Insert/vacuum stack into Shulker Box
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(stack);
                int originalCount = slotStack.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, slotStack, false);
                if (remainder.getCount() != originalCount) {
                    slot.set(remainder);
                    ContainerHelper.setContainerContents(stack, contents);
                    bettershulker$playLevelSound(player, slotStack, true);
                    ci.setReturnValue(true);
                }
            }
        } else if (ContainerHelper.isEnderChest(stack)) {
            ItemStack slotStack = slot.getItem();
            if (slotStack.isEmpty()) {
                // Carried Ender Chest, right-click on empty slot -> Extract first item
                if (!player.level().isClientSide()) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    var enderInv = serverPlayer.getEnderChestInventory();
                    int extractionIndex = -1;
                    for (int i = 0; i < enderInv.getContainerSize(); i++) {
                        if (!enderInv.getItem(i).isEmpty()) {
                            extractionIndex = i;
                            break;
                        }
                    }

                    if (extractionIndex != -1) {
                        ItemStack extracted = enderInv.removeItemNoUpdate(extractionIndex);
                        slot.set(extracted);

                        bettershulker$syncEnderChest(serverPlayer);
                        bettershulker$playLevelSound(player, extracted, false);
                        ci.setReturnValue(true);
                    }
                } else {
                    // Client side: return true if we can extract, to prevent client-server mismatch
                    NonNullList<ItemStack> cached = bettershulker$getClientEnderChestContents();
                    if (cached != null) {
                        if (bettershulker$firstOccupiedSlot(cached) != -1) {
                            ci.setReturnValue(true);
                        }
                    } else {
                        ci.setReturnValue(true);
                    }
                }
            } else {
                // Carried Ender Chest, right-click on stack -> Insert stack into Ender Chest
                if (!player.level().isClientSide()) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    ItemStack invStack = bettershulker$insertIntoEnderChest(serverPlayer, slotStack.copy());

                    if (invStack.getCount() != slotStack.getCount()) {
                        slot.set(invStack);

                        bettershulker$syncEnderChest(serverPlayer);
                        bettershulker$playLevelSound(player, invStack, true);
                        ci.setReturnValue(true);
                    }
                } else {
                    ci.setReturnValue(true);
                }
            }
        }
    }

    /**
     * Called when this item is inside a slot and a carried item is right-clicked on top of it.
     */
    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void bettershulker$overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction clickAction, Player player, SlotAccess slotAccess, CallbackInfoReturnable<Boolean> ci) {
        if (!bettershulker$canHandleContainerClick(stack, slot, clickAction, player)) {
            return;
        }

        if (ContainerHelper.isShulkerBox(stack)) {
            if (!other.isEmpty()) {
                // Insert carried item into Shulker Box (vanilla bundle style)
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(stack);
                int originalCount = other.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, other, false);
                if (remainder.getCount() != originalCount) {
                    slotAccess.set(remainder);
                    ContainerHelper.setContainerContents(stack, contents);
                    bettershulker$playLevelSound(player, other, true);
                    ci.setReturnValue(true);
                }
            }
        } else if (ContainerHelper.isEnderChest(stack)) {
            if (!other.isEmpty()) {
                // Carried item right-clicked onto Ender Chest in slot -> Insert carried item into Ender Chest
                if (!player.level().isClientSide()) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    ItemStack invStack = bettershulker$insertIntoEnderChest(serverPlayer, other.copy());

                    if (invStack.getCount() != other.getCount()) {
                        slotAccess.set(invStack);

                        bettershulker$syncEnderChest(serverPlayer);
                        bettershulker$playLevelSound(player, other, true);
                        ci.setReturnValue(true);
                    }
                } else {
                    ci.setReturnValue(true);
                }
            }
        }
    }

    // =========================================================================
    //  Private Helpers
    // =========================================================================

    @org.spongepowered.asm.mixin.Unique
    private int bettershulker$firstOccupiedSlot(NonNullList<ItemStack> contents) {
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean bettershulker$canHandleContainerClick(ItemStack stack, Slot slot, ClickAction clickAction, Player player) {
        if (clickAction != ClickAction.SECONDARY) {
            return false;
        }
        if (!ContainerHelper.isContainer(stack)) {
            return false;
        }
        if (!(slot.container instanceof Inventory)) {
            return false;
        }
        return player.level().isClientSide()
                || (player instanceof ServerPlayer serverPlayer && BetterShulkerMod.consumeInteraction(serverPlayer));
    }

    @org.spongepowered.asm.mixin.Unique
    private void bettershulker$syncEnderChest(ServerPlayer player) {
        PlatformNetworking.sendToPlayer(player, BetterShulkerMod.buildEnderChestSyncPayload(player));
    }

    @org.spongepowered.asm.mixin.Unique
    private ItemStack bettershulker$insertIntoEnderChest(ServerPlayer player, ItemStack stack) {
        var enderInv = player.getEnderChestInventory();
        for (int i = 0; i < enderInv.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = enderInv.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int toInsert = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
                if (toInsert > 0) {
                    existing.grow(toInsert);
                    stack.shrink(toInsert);
                }
            }
        }
        for (int i = 0; i < enderInv.getContainerSize() && !stack.isEmpty(); i++) {
            if (enderInv.getItem(i).isEmpty()) {
                int toInsert = Math.min(stack.getMaxStackSize(), stack.getCount());
                enderInv.setItem(i, stack.copyWithCount(toInsert));
                stack.shrink(toInsert);
            }
        }
        return stack;
    }

    @org.spongepowered.asm.mixin.Unique
    private void bettershulker$playLevelSound(Player player, ItemStack stack, boolean isInsert) {
        float volume = player.level().isClientSide()
                ? BetterShulkerConfig.soundVolume
                : 0.3F;
        ContainerHelper.playInteractionSound(player, stack, isInsert, volume);
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private NonNullList<ItemStack> bettershulker$getClientEnderChestContents() {
        try {
            Class<?> clientClass = Class.forName("com.bettershulker.client.BetterShulkerClient");
            java.lang.reflect.Method method = clientClass.getMethod("getEnderChestContents");
            return (NonNullList<ItemStack>) method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.server.EnderChestService;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.platform.PlatformNetworking;

import com.bettershulker.server.InteractionRateLimiter;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
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
                // Carried Shulker Box, right-click on empty slot -> Extract/dump first item.
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(stack);
                int extractionIndex = bettershulker$firstOccupiedSlot(contents);
                if (extractionIndex != -1) {
                    ItemStack extracted = ContainerHelper.tryExtract(contents, extractionIndex, false);
                    ItemStack soundStack = extracted.copy();
                    int originalCount = extracted.getCount();
                    ItemStack remainder = bettershulker$safeInsertIntoSlot(player, slot, extracted);
                    bettershulker$restoreExtractedStack(player, contents, extractionIndex, remainder);
                    if (remainder.getCount() < originalCount) {
                        ContainerHelper.setContainerContents(stack, contents);
                        bettershulker$playLevelSound(player, soundStack, false);
                        ci.setReturnValue(true);
                    }
                }
            } else if (slot.allowModification(player)) {
                // Carried Shulker Box, right-click on stack -> Insert/vacuum stack into Shulker Box.
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(stack);
                int originalCount = slotStack.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, slotStack, false);
                if (remainder.getCount() != originalCount) {
                    slot.setByPlayer(remainder, slotStack);
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
                        ItemStack sourceStack = enderInv.getItem(extractionIndex);
                        ItemStack transfer = sourceStack.copy();
                        ItemStack soundStack = transfer.copy();
                        int originalCount = transfer.getCount();
                        ItemStack remainder = bettershulker$safeInsertIntoSlot(player, slot, transfer);
                        int moved = originalCount - remainder.getCount();
                        if (moved > 0) {
                            sourceStack.shrink(moved);
                            if (sourceStack.isEmpty()) {
                                enderInv.setItem(extractionIndex, ItemStack.EMPTY);
                            }
                            bettershulker$syncEnderChest(serverPlayer);
                            bettershulker$playLevelSound(player, soundStack, false);
                            ci.setReturnValue(true);
                        }
                    }
                } else {
                    // Client side: only consume the click when the cached contents prove that an
                    // extraction can happen. An unknown cache must fall through to vanilla; the
                    // old unconditional true swallowed the click while the server was still
                    // waiting for the first sync response.
                    NonNullList<ItemStack> cached = bettershulker$getClientEnderChestContents();
                    int extractionIndex = cached == null ? -1 : bettershulker$firstOccupiedSlot(cached);
                    if (extractionIndex != -1
                            && bettershulker$canReceiveStack(player, slot, cached.get(extractionIndex))) {
                        ci.setReturnValue(true);
                    }
                }
            } else if (slot.allowModification(player)) {
                // Carried Ender Chest, right-click on stack -> Insert stack into Ender Chest.
                if (!player.level().isClientSide()) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    ItemStack invStack = bettershulker$insertIntoEnderChest(serverPlayer, slotStack.copy());

                    if (invStack.getCount() != slotStack.getCount()) {
                        slot.setByPlayer(invStack, slotStack);
                        bettershulker$syncEnderChest(serverPlayer);
                        bettershulker$playLevelSound(player, slotStack, true);
                        ci.setReturnValue(true);
                    }
                } else if (bettershulker$canInsertIntoEnderChest(bettershulker$getClientEnderChestContents(), slotStack)) {
                    // Do not swallow a full/incompatible or not-yet-synchronised Ender Chest
                    // click. The server will handle the operation after a confirmed cache state.
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
        if (!bettershulker$canHandleContainerClick(stack, slot, clickAction, player)
                || !slot.allowModification(player)) {
            return;
        }

        if (ContainerHelper.isShulkerBox(stack)) {
            if (!other.isEmpty()) {
                // Insert carried item into Shulker Box (vanilla bundle style)
                ItemStack updatedContainer = stack.copy();
                NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(updatedContainer);
                int originalCount = other.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, other, false);
                if (remainder.getCount() != originalCount && slotAccess.set(remainder)) {
                    ContainerHelper.setContainerContents(updatedContainer, contents);
                    slot.setByPlayer(updatedContainer, stack);
                    bettershulker$playLevelSound(player, other, true);
                    ci.setReturnValue(true);
                }
            }
        } else if (ContainerHelper.isEnderChest(stack)) {
            if (!other.isEmpty()) {
                // Carried item right-clicked onto Ender Chest in slot -> Insert carried item into Ender Chest
                if (!player.level().isClientSide()) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    NonNullList<ItemStack> originalContents = bettershulker$copyEnderChestContents(serverPlayer);
                    ItemStack invStack = bettershulker$insertIntoEnderChest(serverPlayer, other.copy());

                    if (invStack.getCount() != other.getCount() && slotAccess.set(invStack)) {
                        bettershulker$syncEnderChest(serverPlayer);
                        bettershulker$playLevelSound(player, other, true);
                        ci.setReturnValue(true);
                    } else {
                        // SlotAccess can reject a write; never retain a transferred item in that case.
                        bettershulker$restoreEnderChestContents(serverPlayer, originalContents);
                    }
                } else if (bettershulker$canInsertIntoEnderChest(
                        bettershulker$getClientEnderChestContents(), other)) {
                    ci.setReturnValue(true);
                }
            }
        }
    }

    // =========================================================================
    //  Private Helpers
    // =========================================================================

    @org.spongepowered.asm.mixin.Unique
    private ItemStack bettershulker$safeInsertIntoSlot(Player player, Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !slot.isActive() || slot.isFake() || !slot.mayPlace(stack)) {
            return stack;
        }
        if (!slot.allowModification(player)) {
            return stack;
        }
        return slot.safeInsert(stack);
    }

    @org.spongepowered.asm.mixin.Unique
    private void bettershulker$restoreExtractedStack(Player player, NonNullList<ItemStack> contents,
                                                      int index, ItemStack remainder) {
        if (remainder.isEmpty()) return;

        ItemStack current = contents.get(index);
        if (current.isEmpty()) {
            contents.set(index, remainder);
        } else if (ItemStack.isSameItemSameComponents(current, remainder)) {
            current.grow(remainder.getCount());
        } else if (!player.level().isClientSide()) {
            // Unreachable by construction: the remainder came from this very slot. Falling
            // through silently would have deleted the stack, so hand it back to the player.
            BetterShulkerMod.LOGGER.error("[BetterShulker] Extracted stack no longer matches its"
                    + " source slot for player {}; returning {} to their inventory",
                    player.getName().getString(), remainder);
            player.getInventory().placeItemBackInInventory(remainder);
        }
    }

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
        if (!ContainerHelper.isContainer(stack)
                || !ContainerHelper.canAccessContainer(stack, player)
                || !player.isAlive()
                || player.isSpectator()) {
            return false;
        }
        if (!ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                || !slot.allowModification(player)) {
            return false;
        }
        return player.level().isClientSide()
                || (player instanceof ServerPlayer serverPlayer && InteractionRateLimiter.consume(serverPlayer));
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean bettershulker$canReceiveStack(Player player, Slot slot, ItemStack stack) {
        return !stack.isEmpty()
                && ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                && slot.allowModification(player)
                && slot.mayPlace(stack);
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean bettershulker$canInsertIntoEnderChest(NonNullList<ItemStack> cached, ItemStack stack) {
        if (cached == null || stack == null || stack.isEmpty()) return false;

        ItemStack remainder = stack.copy();
        for (ItemStack existing : cached) {
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remainder)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space > 0) {
                remainder.shrink(Math.min(space, remainder.getCount()));
            }
            if (remainder.isEmpty()) return true;
        }
        for (ItemStack existing : cached) {
            if (existing.isEmpty()) {
                return true;
            }
        }
        return remainder.getCount() < stack.getCount();
    }

    @org.spongepowered.asm.mixin.Unique
    private void bettershulker$syncEnderChest(ServerPlayer player) {
        PlatformNetworking.sendToPlayer(player, EnderChestService.buildEnderChestSyncPayload(player));
    }

    @org.spongepowered.asm.mixin.Unique
    private NonNullList<ItemStack> bettershulker$copyEnderChestContents(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            contents.set(i, enderInv.getItem(i).copy());
        }
        return contents;
    }

    @org.spongepowered.asm.mixin.Unique
    private void bettershulker$restoreEnderChestContents(ServerPlayer player, NonNullList<ItemStack> contents) {
        var enderInv = player.getEnderChestInventory();
        for (int i = 0; i < enderInv.getContainerSize() && i < contents.size(); i++) {
            enderInv.setItem(i, contents.get(i));
        }
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
                ? BetterShulkerConfig.getSoundVolume()
                : 0.3F;
        ContainerHelper.playInteractionSound(player, stack, isInsert, volume);
    }

    @org.spongepowered.asm.mixin.Unique
    private NonNullList<ItemStack> bettershulker$getClientEnderChestContents() {
        // Reads a supplier the client entrypoint installed. The previous reflection did this on
        // every right-click, and caught only Exception, so a NoClassDefFoundError from loading a
        // client class would have escaped rather than degrading to "no cache".
        return BetterShulkerMod.getClientEnderChestContents();
    }
}

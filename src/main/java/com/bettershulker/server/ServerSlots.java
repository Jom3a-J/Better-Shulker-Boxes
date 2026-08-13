package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Slot checks the server-side handlers share.
 *
 * <p>An interaction names a slot the client chose, so every one of these treats that choice as a
 * claim to verify rather than a fact: that the slot exists, that it belongs to the player's own
 * inventory, and that it will accept what is being put there. A stack that cannot be placed is
 * handed back rather than dropped.</p>
 */
public final class ServerSlots {

    private ServerSlots() {}

    // =========================================================================
    public static boolean isUsableSlot(Slot slot) {
        return slot != null && slot.isActive() && !slot.isFake();
    }

    public static Slot getPlayerInventorySlot(ServerPlayer player, int slotId, String actionDescription) {
        Slot slot = MenuSlotRef.resolve(slotId, player);
        if (slot == null) {
            BetterShulkerMod.warnRejectedInteraction(player, "tried " + actionDescription + " with invalid inventory slot: " + slotId);
            return null;
        }

        if (!ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                || !slot.allowModification(player)) {
            BetterShulkerMod.warnRejectedInteraction(player, "tried " + actionDescription
                    + " on an unavailable player-inventory slot: " + slotId);
            return null;
        }
        return slot;
    }

    /**
     * Uses the vanilla slot insertion path while additionally rejecting fake/inactive slots and
     * occupied slots the player is not allowed to modify. The returned stack is the remainder.
     */
    public static ItemStack safeInsertIntoSlot(ServerPlayer player, Slot slot, ItemStack stack) {
        if (stack.isEmpty()
                || !ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                || !slot.allowModification(player)
                || !slot.mayPlace(stack)) {
            return stack;
        }
        return slot.safeInsert(stack);
    }

    /**
     * Returns an un-inserted remainder to the container copy.
     *
     * <p>The mismatch branch is unreachable by construction, since the remainder always
     * originates from the very slot being restored. It is handled rather than asserted because
     * this runs inside a task on the server thread, where an uncaught throw takes down the tick
     * loop instead of the single interaction that caused it. The stack goes to the player because
     * that conserves it exactly: discarding it would delete items, and abandoning the operation
     * after the destination slot was already written would duplicate them.</p>
     */
    public static void restoreExtractedStack(ServerPlayer player, NonNullList<ItemStack> contents,
                                              int index, ItemStack remainder) {
        if (remainder.isEmpty()) return;

        ItemStack current = contents.get(index);
        if (current.isEmpty()) {
            contents.set(index, remainder);
        } else if (ItemStack.isSameItemSameComponents(current, remainder)) {
            current.grow(remainder.getCount());
        } else {
            BetterShulkerMod.LOGGER.error("[BetterShulker] Extracted stack no longer matches its source slot for"
                    + " player {}; returning {} to their inventory",
                    player.getName().getString(), remainder);
            player.getInventory().placeItemBackInInventory(remainder);
        }
    }

    public static boolean canMergeInto(ItemStack target, ItemStack source) {
        return !target.isEmpty()
                && ItemStack.isSameItemSameComponents(target, source)
                && target.getCount() < target.getMaxStackSize();
    }

    public static boolean hasStackChanged(ItemStack current, ItemStack previous) {
        return !ItemStack.isSameItemSameComponents(current, previous)
                || current.getCount() != previous.getCount();
    }

    /**
     * Re-syncs the player's entire inventory to fix any client-side desync.
     * This is the nuclear option — used when server validation fails.
     */
    public static void resyncPlayer(ServerPlayer player) {
        player.containerMenu.broadcastFullState();
        player.inventoryMenu.broadcastFullState();
    }
}

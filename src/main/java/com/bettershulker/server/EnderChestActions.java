package com.bettershulker.server;

import com.bettershulker.util.ContainerTransfer;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * One method per Ender Chest interaction.
 *
 * <p>These were eight cases of a single switch sharing four mutable locals. Each now reports what
 * it did through an {@link Outcome} instead of assigning to those locals, which is the only way
 * they can stand apart - a case that changed nothing has to be distinguishable from one that
 * moved a stack, since only the latter makes a sound.</p>
 *
 * <p>Every method reads the cursor and the chest itself rather than receiving them, matching the
 * original, which captured both once before the switch and before anything could replace them.</p>
 */
final class EnderChestActions {

    /**
     * What an action did: whether anything moved, in which direction, and the stack whose material
     * the sound is chosen from.
     *
     * <p>The stack is held by reference, not copied. Two of the actions below deliberately point
     * it at a stack still sitting in the chest, and the reference has to keep behaving that way.</p>
     */
    record Outcome(boolean success, boolean isInsert, ItemStack soundStack) {
        static final Outcome NONE = new Outcome(false, false, ItemStack.EMPTY);
    }

    private EnderChestActions() {}

    /** Inserts the whole carried stack, merging into matching stacks before taking empty slots. */
    static Outcome insert(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        if (cursorStack.isEmpty()) return Outcome.NONE;
        int originalCount = cursorStack.getCount();
        // Captured before the passes below shrink it. Reading it afterwards named the
        // remainder, so a stack that fit entirely left an empty one behind and the
        // contextual sound fell back to its generic default instead of the item's own.
        ItemStack insertedSource = cursorStack.copy();

        // First pass: merge with existing compatible stacks
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            ItemStack existing = enderInv.getItem(i);
            if (ServerSlots.canMergeInto(existing, cursorStack)) {
                int canFit = existing.getMaxStackSize() - existing.getCount();
                int toInsert = Math.min(canFit, cursorStack.getCount());
                if (toInsert > 0) {
                    existing.grow(toInsert);
                    cursorStack.shrink(toInsert);
                }
            }
            if (cursorStack.isEmpty()) break;
        }

        // Second pass: put into empty slots using smart-merge
        if (!cursorStack.isEmpty()) {
            while (cursorStack.getCount() > 0) {
                NonNullList<ItemStack> enderList = EnderChestService.copyEnderChestContents(player);
                int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(enderList, cursorStack);
                if (bestSlot == -1) break;

                int toInsert = Math.min(cursorStack.getMaxStackSize(), cursorStack.getCount());
                enderInv.setItem(bestSlot, cursorStack.copyWithCount(toInsert));
                cursorStack.shrink(toInsert);
            }
        }

        if (cursorStack.getCount() < originalCount) {
            return new Outcome(true, true, insertedSource);
        }
        return Outcome.NONE;
    }

    /** Precision mode: inserts exactly one item from the carried stack. */
    static Outcome insertOne(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        if (cursorStack.isEmpty()) return Outcome.NONE;
        ItemStack singleItem = cursorStack.copyWithCount(1);
        boolean inserted = false;

        // First pass: merge with existing compatible stacks
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            ItemStack existing = enderInv.getItem(i);
            if (ServerSlots.canMergeInto(existing, singleItem)) {
                existing.grow(1);
                inserted = true;
                break;
            }
        }

        // Second pass: put into empty slots using smart-merge
        if (!inserted) {
            NonNullList<ItemStack> enderList = EnderChestService.copyEnderChestContents(player);
            int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(enderList, singleItem);
            if (bestSlot != -1) {
                enderInv.setItem(bestSlot, singleItem);
                inserted = true;
            }
        }

        if (inserted) {
            cursorStack.shrink(1);
            return new Outcome(true, true, singleItem);
        }
        return Outcome.NONE;
    }

    /** Takes the whole stack at {@code targetIndex} onto an empty cursor. */
    static Outcome extract(ServerPlayer player, int targetIndex) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        if (!cursorStack.isEmpty()) return Outcome.NONE;
        ItemStack extracted = enderInv.getItem(targetIndex).copy();
        if (!extracted.isEmpty()) {
            enderInv.setItem(targetIndex, ItemStack.EMPTY);
            player.containerMenu.setCarried(extracted);
            return new Outcome(true, false, extracted);
        }
        return Outcome.NONE;
    }

    /** Precision mode: takes one item, to a named slot, the cursor, or a matching cursor stack. */
    static Outcome extractOne(ServerPlayer player, int targetIndex, int inventorySlotId) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        ItemStack slotStack = enderInv.getItem(targetIndex);
        if (slotStack.isEmpty()) return Outcome.NONE;
        ItemStack extracted = slotStack.copyWithCount(1);
        ItemStack soundStack = extracted.copy();
        boolean success = false;

        if (inventorySlotId != -1) {
            Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "ender chest slot extraction");
            if (destination == null) return Outcome.NONE;
            ItemStack remainder = ServerSlots.safeInsertIntoSlot(player, destination, extracted);
            if (remainder.isEmpty()) {
                slotStack.shrink(1);
                if (slotStack.isEmpty()) {
                    enderInv.setItem(targetIndex, ItemStack.EMPTY);
                }
                success = true;
            }
        } else if (cursorStack.isEmpty()) {
            player.containerMenu.setCarried(extracted);
            slotStack.shrink(1);
            if (slotStack.isEmpty()) {
                enderInv.setItem(targetIndex, ItemStack.EMPTY);
            }
            success = true;
        } else if (ServerSlots.canMergeInto(cursorStack, extracted)) {
            cursorStack.grow(1);
            slotStack.shrink(1);
            if (slotStack.isEmpty()) {
                enderInv.setItem(targetIndex, ItemStack.EMPTY);
            }
            success = true;
        }
        return new Outcome(success, false, soundStack);
    }

    /** Sweeps a whole inventory slot into the chest, writing the remainder back to that slot. */
    static Outcome sweepInsert(ServerPlayer player, int inventorySlotId) {
        var enderInv = player.getEnderChestInventory();
        Slot targetSlot = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "SWEEP_INSERT");
        if (targetSlot == null || !targetSlot.allowModification(player)) return Outcome.NONE;
        ItemStack originalStack = targetSlot.getItem();
        if (originalStack.isEmpty()) return Outcome.NONE;
        ItemStack invStack = originalStack.copy();
        int originalCount = invStack.getCount();

        // Auto-insert invStack into the ender chest inventory
        // First pass: merge with existing compatible stacks
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            ItemStack existing = enderInv.getItem(i);
            if (ServerSlots.canMergeInto(existing, invStack)) {
                int canFit = existing.getMaxStackSize() - existing.getCount();
                int toInsert = Math.min(canFit, invStack.getCount());
                if (toInsert > 0) {
                    existing.grow(toInsert);
                    invStack.shrink(toInsert);
                }
            }
            if (invStack.isEmpty()) break;
        }

        // Second pass: put into empty slots using smart-merge
        if (!invStack.isEmpty()) {
            while (invStack.getCount() > 0) {
                NonNullList<ItemStack> enderList = EnderChestService.copyEnderChestContents(player);
                int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(enderList, invStack);
                if (bestSlot == -1) break;

                int toInsert = Math.min(invStack.getMaxStackSize(), invStack.getCount());
                enderInv.setItem(bestSlot, invStack.copyWithCount(toInsert));
                invStack.shrink(toInsert);
            }
        }

        // Update the source inventory slot containing the remainder only after insertion succeeds.
        if (invStack.getCount() < originalCount) {
            targetSlot.setByPlayer(invStack, originalStack);
            return new Outcome(true, true, originalStack.copy());
        }
        return Outcome.NONE;
    }

    /** Sweeps a chest slot out, to a named inventory slot or onto the cursor. */
    static Outcome sweepExtract(ServerPlayer player, int targetIndex, int inventorySlotId) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        ItemStack shulkerStack = enderInv.getItem(targetIndex);
        if (shulkerStack.isEmpty()) return Outcome.NONE;
        // Deliberately not copied: this points at the stack still in the chest, and the branches
        // below shrink it. Preserved as it was - see the note in EnderChestService.
        ItemStack soundStack = shulkerStack;
        boolean success = false;

        if (inventorySlotId == -1) {
            if (cursorStack.isEmpty()) {
                enderInv.setItem(targetIndex, ItemStack.EMPTY);
                player.containerMenu.setCarried(shulkerStack.copy());
                success = true;
            } else if (ServerSlots.canMergeInto(cursorStack, shulkerStack)) {
                int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                int toAdd = Math.min(canFit, shulkerStack.getCount());
                if (toAdd > 0) {
                    cursorStack.grow(toAdd);
                    shulkerStack.shrink(toAdd);
                    if (shulkerStack.isEmpty()) {
                        enderInv.setItem(targetIndex, ItemStack.EMPTY);
                    }
                    success = true;
                }
            }
        } else {
            Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "ender chest slot sweep extraction");
            if (destination == null) return Outcome.NONE;
            ItemStack transfer = shulkerStack.copy();
            int originalCount = transfer.getCount();
            ItemStack remainder = ServerSlots.safeInsertIntoSlot(player, destination, transfer);
            int moved = originalCount - remainder.getCount();
            if (moved > 0) {
                shulkerStack.shrink(moved);
                if (shulkerStack.isEmpty()) {
                    enderInv.setItem(targetIndex, ItemStack.EMPTY);
                }
                success = true;
            }
        }
        return new Outcome(success, false, soundStack);
    }

    /** Pulls matching items from the hotbar into the chest. */
    static Outcome restock(ServerPlayer player) {
        NonNullList<ItemStack> contents = EnderChestService.copyEnderChestContents(player);
        boolean success = ContainerTransfer.restockContents(contents, player.containerMenu.slots, player);
        if (success) {
            EnderChestService.applyEnderChestContents(player, contents);
        }
        return new Outcome(success, false, ItemStack.EMPTY);
    }

    /** Pushes inventory items matching what the chest already holds into it. */
    static Outcome deposit(ServerPlayer player, Slot containerSlot) {
        NonNullList<ItemStack> contents = EnderChestService.copyEnderChestContents(player);
        boolean success = ContainerTransfer.depositContents(contents, player.containerMenu.slots, containerSlot, player);
        if (success) {
            EnderChestService.applyEnderChestContents(player, contents);
            return new Outcome(true, true, ItemStack.EMPTY);
        }
        return new Outcome(false, false, ItemStack.EMPTY);
    }
}

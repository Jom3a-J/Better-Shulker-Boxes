package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.util.InteractionSounds;
import com.bettershulker.util.ContainerTransfer;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side Shulker Box handling.
 *
 * <p>Unlike an Ender Chest, a box carries its own contents in a data component, so the work here
 * is to apply the requested move to a copy and write it back only once the move has actually
 * happened - the client is told what it asked for was refused by simply not seeing it change.</p>
 */
public final class ShulkerInteractionHandler {

    private ShulkerInteractionHandler() {}

    /**
     * Processes shulker box insertion/extraction on the server.
     * Reads from DataComponents.CONTAINER, validates, modifies, and writes back.
     */
    public static void handleShulkerInteraction(ServerPlayer player, Slot containerSlot, ItemStack containerStack,
                                           int targetIndex, ContainerInteractPayload.InteractType action, int inventorySlotId) {
        NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(containerStack);
        ItemStack cursorStack = player.containerMenu.getCarried();
        boolean success = false;
        boolean isInsert = false;
        ItemStack soundStack = ItemStack.EMPTY;

        switch (action) {
            case INSERT -> {
                // Insert the entire cursor stack into the container
                if (cursorStack.isEmpty()) return;
                int originalCount = cursorStack.getCount();
                ItemStack remainder = ContainerTransfer.tryInsert(contents, cursorStack.copy(), false);
                player.containerMenu.setCarried(remainder);
                if (remainder.getCount() < originalCount) {
                    success = true;
                    isInsert = true;
                    soundStack = cursorStack;
                }
            }
            case INSERT_ONE -> {
                // Precision mode: insert exactly 1 item from cursor
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                ItemStack remainder = ContainerTransfer.tryInsert(contents, singleItem, true);
                if (remainder.isEmpty()) {
                    // Successfully inserted 1 item — shrink cursor
                    cursorStack.shrink(1);
                    success = true;
                    isInsert = true;
                    soundStack = singleItem;
                }
            }
            case EXTRACT -> {
                // Extract the full stack at targetIndex
                if (!cursorStack.isEmpty()) return; // Cursor must be empty to extract
                ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                if (!extracted.isEmpty()) {
                    player.containerMenu.setCarried(extracted);
                    success = true;
                    soundStack = extracted;
                }
            }
            case EXTRACT_ONE -> {
                // Precision mode: extract exactly 1 item from targetIndex.
                ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, true);
                if (!extracted.isEmpty()) {
                    soundStack = extracted.copy();
                    if (inventorySlotId != -1) {
                        Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "slot extraction");
                        if (destination == null) {
                            ServerSlots.restoreExtractedStack(player, contents, targetIndex, extracted);
                            return;
                        }
                        int originalCount = extracted.getCount();
                        ItemStack remainder = ServerSlots.safeInsertIntoSlot(player, destination, extracted);
                        ServerSlots.restoreExtractedStack(player, contents, targetIndex, remainder);
                        success = remainder.getCount() < originalCount;
                    } else if (cursorStack.isEmpty()) {
                        player.containerMenu.setCarried(extracted);
                        success = true;
                    } else if (ServerSlots.canMergeInto(cursorStack, extracted)) {
                        cursorStack.grow(1);
                        success = true;
                    } else {
                        ServerSlots.restoreExtractedStack(player, contents, targetIndex, extracted);
                        return;
                    }
                }
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "SWEEP_INSERT");
                if (targetSlot == null || !targetSlot.allowModification(player)) return;
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                int originalCount = invStack.getCount();
                ItemStack remainder = ContainerTransfer.tryInsert(contents, invStack.copy(), false);
                if (remainder.getCount() < originalCount) {
                    targetSlot.setByPlayer(remainder, invStack);
                    success = true;
                    isInsert = true;
                    soundStack = invStack.copy();
                }
            }
            case SWEEP_EXTRACT -> {
                if (targetIndex < 0 || targetIndex >= contents.size()) return;
                ItemStack shulkerStack = contents.get(targetIndex);
                if (shulkerStack.isEmpty()) return;
                soundStack = shulkerStack;

                if (inventorySlotId == -1) {
                    if (cursorStack.isEmpty()) {
                        ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                        player.containerMenu.setCarried(extracted);
                        success = true;
                    } else if (ServerSlots.canMergeInto(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            cursorStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                            success = true;
                        }
                    }
                } else {
                    Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "slot sweep extraction");
                    if (destination == null) return;
                    ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                    int originalCount = extracted.getCount();
                    ItemStack remainder = ServerSlots.safeInsertIntoSlot(player, destination, extracted);
                    ServerSlots.restoreExtractedStack(player, contents, targetIndex, remainder);
                    success = remainder.getCount() < originalCount;
                }
            }
            case RESTOCK -> {
                success = ContainerTransfer.restockContents(contents, player.containerMenu.slots, player);
            }
            case DEPOSIT -> {
                success = ContainerTransfer.depositContents(contents, player.containerMenu.slots, containerSlot, player);
                if (success) {
                    isInsert = true;
                }
            }
        }

        if (success) {
            // Commit only after the complete operation succeeds. The source slot was preflighted
            // above, and setByPlayer preserves slot-specific bookkeeping and callbacks.
            ContainerHelper.setContainerContents(containerStack, contents);
            if (containerSlot == null) {
                player.containerMenu.setCarried(containerStack);
            } else {
                containerSlot.setByPlayer(containerStack, containerSlot.getItem());
            }
            InteractionSounds.playInteractionSound(player, soundStack, isInsert, 0.3F);
        }

    }
}

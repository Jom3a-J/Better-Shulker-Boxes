package com.bettershulker.client.interact;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.EnderChestCache;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.platform.PlatformNetworking;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.util.ContainerTransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side prediction for container interactions.
 *
 * <p>Every interaction is applied locally the moment it is sent, so the preview and the cursor
 * move with the click rather than a round trip later, and is reconciled when the server's own
 * view of the menu arrives. Creative mode never sends at all: the cursor there is client-only,
 * so a validated packet could only be rejected, and the rejection resyncs the player.</p>
 *
 * <p>Lifted out of the screen mixin, which it only ever needed for the screen itself.</p>
 */
public final class ContainerPrediction {

    private ContainerPrediction() {}

        public static void sendInteractPayload(AbstractContainerScreen<?> self, int containerSlotId, int targetIndex, int actionId, int inventorySlotId) {
        if (handleCreatively(self, containerSlotId, targetIndex, actionId, inventorySlotId)) {
            return;
        }

        predictAction(self, containerSlotId, targetIndex, actionId, inventorySlotId);
        PlatformNetworking.sendToServer(new ContainerInteractPayload(containerSlotId, targetIndex, actionId, inventorySlotId));
    }

    /**
     * Handles an interaction locally in creative mode, returning whether it consumed it.
     *
     * <p>The validated path cannot serve creative at all. The cursor there is client-only, so the
     * server sees an empty one and any action naming it does nothing - and the rejection resyncs
     * the player, which overwrites the client's own cursor and looks like the held item being
     * deleted. So nothing is sent from creative; a shulker box is instead updated in place and
     * committed with the packet vanilla itself uses for creative slot edits.</p>
     *
     * <p>Deliberately narrow. Only actions whose sole inventory-slot effect is on the container
     * itself qualify, so exactly one slot is ever written, and that slot holds the box and is
     * therefore never empty. That matters because a slot's real inventory position cannot be
     * recovered reliably when it is both empty and wrapped by another menu, and writing a
     * creative packet to a wrong position would destroy whatever was there. Actions with a
     * separate source or destination slot, and Ender Chests, stay unsupported here.</p>
     */
        private static boolean handleCreatively(AbstractContainerScreen<?> self, int containerSlotId, int targetIndex,
                                                    int actionId, int inventorySlotId) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.gameMode == null || !player.hasInfiniteMaterials()) return false;

        // Every return below is true: nothing may be sent from creative even when it cannot be
        // handled here, because the payload could only be rejected and the rejection resyncs.
        // The container is either a player-inventory slot or the carried stack. A carried box
        // needs no packet of its own: it stays on the client's cursor until placed, and vanilla
        // syncs it then, contents included.
        boolean carriedContainer = containerSlotId == MenuSlotRef.NONE;
        Slot containerSlot = null;
        int containerPosition = -1;
        ItemStack containerStack;
        if (carriedContainer) {
            containerStack = self.getMenu().getCarried();
        } else {
            containerSlot = MenuSlotRef.resolve(containerSlotId, self.getMenu(), player);
            containerPosition = MenuSlotRef.playerInventoryPosition(containerSlot, player);
            if (containerSlot == null || containerPosition < 0 || containerPosition >= 36) {
                return true;
            }
            containerStack = containerSlot.getItem();
        }

        if (!ContainerHelper.isShulkerBox(containerStack)
                || !ContainerHelper.canAccessContainer(containerStack, player)) {
            return true;
        }

        ContainerInteractPayload.InteractType action;
        try {
            action = ContainerInteractPayload.InteractType.fromId(actionId);
        } catch (IllegalArgumentException e) {
            return true;
        }

        // Every occupied player-inventory slot, pinned to a position now, while it still holds the
        // stack that makes that position certain. This covers the container slot and both of the
        // multi-slot actions: restock and deposit only move items between the box and slots that
        // are already occupied, so neither ever writes to one that was empty.
        List<WatchedSlot> watched = new ArrayList<>();
        for (Slot candidate : self.getMenu().slots) {
            if (candidate.getItem().isEmpty()) continue;
            int position = MenuSlotRef.playerInventoryPosition(candidate, player);
            if (position >= 0 && position < 36) {
                watched.add(new WatchedSlot(candidate, position, candidate.getItem().copy()));
            }
        }

        // Extraction to a named slot is the one case that can write to a slot which was empty
        // beforehand, so it needs the stricter test: an empty slot on a wrapping screen cannot be
        // pinned to one position, and clearing the wrong one would destroy what is there.
        boolean usesOtherSlot = switch (action) {
            case SWEEP_INSERT -> true;
            case EXTRACT_ONE, SWEEP_EXTRACT -> inventorySlotId != MenuSlotRef.NONE;
            default -> false;
        };
        Slot otherSlot = null;
        int otherPosition = -1;
        ItemStack otherBefore = ItemStack.EMPTY;
        if (usesOtherSlot) {
            otherSlot = MenuSlotRef.resolve(inventorySlotId, self.getMenu(), player);
            otherPosition = MenuSlotRef.playerInventoryPosition(otherSlot, player);
            if (otherSlot == null || otherPosition < 0 || otherPosition >= 36
                    || !MenuSlotRef.hasUnambiguousPlayerPosition(otherSlot, player)) {
                return true;
            }
            otherBefore = otherSlot.getItem().copy();
        }

        ItemStack working = containerStack.copy();
        predictShulkerBox(self, 0L, containerSlotId, working, targetIndex, action, inventorySlotId);

        // A carried box needs no packet and is absent from the watched list anyway, the cursor
        // not being a slot.
        Set<Integer> committed = new HashSet<>();
        for (WatchedSlot entry : watched) {
            if (ItemStack.matches(entry.before(), entry.slot().getItem())) continue;
            if (committed.add(entry.position())) {
                commitCreativeSlot(mc, player, entry.slot(), entry.position());
            }
        }
        if (otherSlot != null
                && !ItemStack.matches(otherBefore, otherSlot.getItem())
                && committed.add(otherPosition)) {
            commitCreativeSlot(mc, player, otherSlot, otherPosition);
        }
        return true;
    }

    /** An occupied slot with the position it resolved to before a simulation ran. */
        private record WatchedSlot(Slot slot, int position, ItemStack before) {}


    /** Pushes one slot's current contents to the server using the creative slot packet. */
        private static void commitCreativeSlot(Minecraft mc, net.minecraft.world.entity.player.Player player,
                                                   Slot slot, int position) {
        Slot target = MenuSlotRef.resolve(MenuSlotRef.forPlayerPosition(position), player.inventoryMenu, player);
        // The server accepts inventory-menu slot numbers 1-45; 0 is the crafting result.
        if (target == null || target.index < 1 || target.index > 45) return;

        ItemStack updated = slot.getItem();
        mc.gameMode.handleCreativeModeItemAdd(updated.copy(), target.index);
    }

        private static void predictAction(AbstractContainerScreen<?> self, int containerSlotId, int targetIndex, int actionId, int inventorySlotId) {
        try {
            ItemStack carried = self.getMenu().getCarried();
            ItemStack containerStack = ItemStack.EMPTY;
            if (containerSlotId == -1) {
                containerStack = carried.copy();
            } else if (MenuSlotRef.isSlot(containerSlotId)) {
                var slotOwner = Minecraft.getInstance().player;
                Slot sourceSlot = MenuSlotRef.resolve(containerSlotId, self.getMenu(), slotOwner);
                if (slotOwner == null || sourceSlot == null || !sourceSlot.isActive() || sourceSlot.isFake()
                        || !sourceSlot.allowModification(slotOwner)) {
                    return;
                }
                containerStack = sourceSlot.getItem().copy();
            }
            var player = Minecraft.getInstance().player;
            if (player == null || !ContainerHelper.canAccessContainer(containerStack, player)) return;

            long txId = BetterShulkerClient.startPrediction(carried, containerStack, containerSlotId);

            Slot snapshotSlot = MenuSlotRef.resolve(inventorySlotId, self.getMenu(), player);
            if (snapshotSlot != null) {
                BetterShulkerClient.addOriginalSlotSnapshot(txId, inventorySlotId, snapshotSlot.getItem());
            }

            ContainerInteractPayload.InteractType action = ContainerInteractPayload.InteractType.fromId(actionId);
            boolean isEnder = ContainerHelper.isEnderChest(containerStack);

            if (isEnder) {
                predictEnderChest(self, txId, containerSlotId, targetIndex, action, inventorySlotId);
            } else if (ContainerHelper.isShulkerBox(containerStack)) {
                predictShulkerBox(self, txId, containerSlotId, containerStack, targetIndex, action, inventorySlotId);
            }
        } catch (Exception e) {
            logPredictionFailure("predictAction error: " + e);
        }
    }

        private static void logPredictionFailure(String msg) {
        BetterShulkerMod.LOGGER.info("[BetterShulker-ClientPrediction] " + msg);
    }

        private static void commitPredictedContainerStack(AbstractContainerScreen<?> self, int containerSlotId, ItemStack containerStack) {
        if (containerStack.isEmpty()) return;

        // Push the predicted component change back into the same UI slot/cursor immediately.
        // Mutating the ItemStack component alone can leave the rendered tooltip waiting for the
        // next server menu sync, which feels like item insertion lag.
        if (containerSlotId == -1) {
            self.getMenu().setCarried(containerStack);
        } else if (MenuSlotRef.isSlot(containerSlotId)) {
            var player = Minecraft.getInstance().player;
            Slot sourceSlot = MenuSlotRef.resolve(containerSlotId, self.getMenu(), player);
            if (player != null && sourceSlot != null && sourceSlot.isActive() && !sourceSlot.isFake()
                    && sourceSlot.allowModification(player)) {
                sourceSlot.setByPlayer(containerStack, sourceSlot.getItem());
            }
        }

        BetterShulkerClient.setActiveContainerStack(containerStack);
    }

        private static Slot getPredictionPlayerSlot(AbstractContainerScreen<?> self, int slotId) {
        var player = Minecraft.getInstance().player;
        Slot slot = MenuSlotRef.resolve(slotId, self.getMenu(), player);
        if (player == null || slot == null) return null;
        return ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                && slot.allowModification(player) ? slot : null;
    }

        private static ItemStack safePredictSlotInsert(Slot slot, ItemStack stack) {
        var player = Minecraft.getInstance().player;
        if (player == null || slot == null || stack.isEmpty() || !slot.mayPlace(stack)) return stack;
        if (!slot.getItem().isEmpty() && !slot.allowModification(player)) return stack;
        return slot.safeInsert(stack);
    }

        private static void restorePredictedExtraction(NonNullList<ItemStack> contents, int index,
                                                           ItemStack remainder) {
        if (remainder.isEmpty()) return;
        ItemStack current = contents.get(index);
        if (current.isEmpty()) {
            contents.set(index, remainder);
        } else if (ItemStack.isSameItemSameComponents(current, remainder)) {
            current.grow(remainder.getCount());
        }
    }

        private static void predictShulkerBox(AbstractContainerScreen<?> self, long txId, int containerSlotId, ItemStack containerStack,
                                                  int targetIndex, ContainerInteractPayload.InteractType action, int inventorySlotId) {
        NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(containerStack);
        ItemStack cursorStack = self.getMenu().getCarried();

        switch (action) {
            case INSERT -> {
                if (cursorStack.isEmpty()) return;
                ItemStack remainder = ContainerTransfer.tryInsert(contents, cursorStack.copy(), false);
                self.getMenu().setCarried(remainder);
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                ItemStack remainder = ContainerTransfer.tryInsert(contents, singleItem, true);
                if (remainder.isEmpty()) {
                    cursorStack.shrink(1);
                    self.getMenu().setCarried(cursorStack.isEmpty() ? ItemStack.EMPTY : cursorStack);
                }
            }
            case EXTRACT -> {
                if (!cursorStack.isEmpty()) return;
                ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                if (!extracted.isEmpty()) {
                    self.getMenu().setCarried(extracted);
                }
            }
            case EXTRACT_ONE -> {
                ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, true);
                if (!extracted.isEmpty()) {
                    if (inventorySlotId != -1) {
                        Slot destination = getPredictionPlayerSlot(self, inventorySlotId);
                        if (destination == null) {
                            restorePredictedExtraction(contents, targetIndex, extracted);
                            return;
                        }
                        ItemStack remainder = safePredictSlotInsert(destination, extracted);
                        restorePredictedExtraction(contents, targetIndex, remainder);
                    } else if (cursorStack.isEmpty()) {
                        self.getMenu().setCarried(extracted);
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                            && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                        cursorStack.grow(1);
                    } else {
                        restorePredictedExtraction(contents, targetIndex, extracted);
                    }
                }
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = getPredictionPlayerSlot(self, inventorySlotId);
                var player = Minecraft.getInstance().player;
                if (targetSlot == null || player == null || !targetSlot.allowModification(player)) return;
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                ItemStack remainder = ContainerTransfer.tryInsert(contents, invStack.copy(), false);
                if (remainder.getCount() < invStack.getCount()) {
                    targetSlot.setByPlayer(remainder, invStack);
                }
            }
            case SWEEP_EXTRACT -> {
                if (targetIndex < 0 || targetIndex >= contents.size()) return;
                ItemStack shulkerStack = contents.get(targetIndex);
                if (shulkerStack.isEmpty()) return;

                if (inventorySlotId == -1) {
                    if (cursorStack.isEmpty()) {
                        ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                        self.getMenu().setCarried(extracted);
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            cursorStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                        }
                    }
                } else {
                    Slot destination = getPredictionPlayerSlot(self, inventorySlotId);
                    if (destination == null) return;
                    ItemStack extracted = ContainerTransfer.tryExtract(contents, targetIndex, false);
                    ItemStack remainder = safePredictSlotInsert(destination, extracted);
                    restorePredictedExtraction(contents, targetIndex, remainder);
                }
            }
            case RESTOCK -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    ContainerTransfer.restockContents(contents, self.getMenu().slots, player);
                }
            }
            case DEPOSIT -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    Slot excludedSlot = MenuSlotRef.resolve(containerSlotId, self.getMenu(), player);
                    ContainerTransfer.depositContents(contents, self.getMenu().slots, excludedSlot, player);
                }
            }
        }

        ContainerHelper.setContainerContents(containerStack, contents);
        commitPredictedContainerStack(self, containerSlotId, containerStack);
    }

        private static void predictEnderChest(AbstractContainerScreen<?> self, long txId, int containerSlotId, int targetIndex,
                                                  ContainerInteractPayload.InteractType action, int inventorySlotId) {
        NonNullList<ItemStack> contents = EnderChestCache.getEnderChestContents();
        if (contents == null) return;
        ItemStack cursorStack = self.getMenu().getCarried();

        switch (action) {
            case INSERT -> {
                if (cursorStack.isEmpty()) return;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, cursorStack)) {
                        int canFit = existing.getMaxStackSize() - existing.getCount();
                        int toInsert = Math.min(canFit, cursorStack.getCount());
                        if (toInsert > 0) {
                            existing.grow(toInsert);
                            cursorStack.shrink(toInsert);
                        }
                    }
                    if (cursorStack.isEmpty()) break;
                }
                if (!cursorStack.isEmpty()) {
                    while (cursorStack.getCount() > 0) {
                        int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(contents, cursorStack);
                        if (bestSlot == -1) break;
                        int toInsert = Math.min(cursorStack.getMaxStackSize(), cursorStack.getCount());
                        contents.set(bestSlot, cursorStack.copyWithCount(toInsert));
                        cursorStack.shrink(toInsert);
                    }
                }
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                boolean inserted = false;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, singleItem)) {
                        if (existing.getCount() < existing.getMaxStackSize()) {
                            existing.grow(1);
                            inserted = true;
                            break;
                        }
                    }
                }
                if (!inserted) {
                    int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(contents, singleItem);
                    if (bestSlot != -1) {
                        contents.set(bestSlot, singleItem);
                        inserted = true;
                    }
                }
                if (inserted) {
                    cursorStack.shrink(1);
                }
            }
            case EXTRACT -> {
                if (!cursorStack.isEmpty()) return;
                ItemStack extracted = contents.get(targetIndex).copy();
                if (!extracted.isEmpty()) {
                    contents.set(targetIndex, ItemStack.EMPTY);
                    self.getMenu().setCarried(extracted);
                }
            }
            case EXTRACT_ONE -> {
                ItemStack slotStack = contents.get(targetIndex);
                if (slotStack.isEmpty()) return;
                ItemStack extracted = slotStack.copyWithCount(1);
                if (inventorySlotId != -1) {
                    Slot destination = getPredictionPlayerSlot(self, inventorySlotId);
                    if (destination == null) return;
                    ItemStack remainder = safePredictSlotInsert(destination, extracted);
                    if (remainder.isEmpty()) {
                        slotStack.shrink(1);
                        if (slotStack.isEmpty()) {
                            contents.set(targetIndex, ItemStack.EMPTY);
                        }
                    }
                } else if (cursorStack.isEmpty()) {
                    self.getMenu().setCarried(extracted);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                    }
                } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                        && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                    cursorStack.grow(1);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                    }
                }
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = getPredictionPlayerSlot(self, inventorySlotId);
                var player = Minecraft.getInstance().player;
                if (targetSlot == null || player == null || !targetSlot.allowModification(player)) return;
                ItemStack originalStack = targetSlot.getItem();
                if (originalStack.isEmpty()) return;
                ItemStack invStack = originalStack.copy();
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, invStack)) {
                        int canFit = existing.getMaxStackSize() - existing.getCount();
                        int toInsert = Math.min(canFit, invStack.getCount());
                        if (toInsert > 0) {
                            existing.grow(toInsert);
                            invStack.shrink(toInsert);
                        }
                    }
                    if (invStack.isEmpty()) break;
                }
                if (!invStack.isEmpty()) {
                    while (invStack.getCount() > 0) {
                        int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(contents, invStack);
                        if (bestSlot == -1) break;
                        int toInsert = Math.min(invStack.getMaxStackSize(), invStack.getCount());
                        contents.set(bestSlot, invStack.copyWithCount(toInsert));
                        invStack.shrink(toInsert);
                    }
                }
                if (invStack.getCount() < originalStack.getCount()) {
                    targetSlot.setByPlayer(invStack, originalStack);
                }
            }
            case SWEEP_EXTRACT -> {
                ItemStack shulkerStack = contents.get(targetIndex);
                if (shulkerStack.isEmpty()) return;

                if (inventorySlotId == -1) {
                    if (cursorStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                        self.getMenu().setCarried(shulkerStack.copy());
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        int toAdd = Math.min(canFit, shulkerStack.getCount());
                        if (toAdd > 0) {
                            cursorStack.grow(toAdd);
                            shulkerStack.shrink(toAdd);
                            if (shulkerStack.isEmpty()) {
                                contents.set(targetIndex, ItemStack.EMPTY);
                            }
                        }
                    }
                } else {
                    Slot destination = getPredictionPlayerSlot(self, inventorySlotId);
                    if (destination == null) return;
                    ItemStack transfer = shulkerStack.copy();
                    int originalCount = transfer.getCount();
                    ItemStack remainder = safePredictSlotInsert(destination, transfer);
                    int moved = originalCount - remainder.getCount();
                    if (moved > 0) {
                        shulkerStack.shrink(moved);
                        if (shulkerStack.isEmpty()) {
                            contents.set(targetIndex, ItemStack.EMPTY);
                        }
                    }
                }
            }
            case RESTOCK -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    ContainerTransfer.restockContents(contents, self.getMenu().slots, player);
                }
            }
            case DEPOSIT -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    Slot excludedSlot = MenuSlotRef.resolve(containerSlotId, self.getMenu(), player);
                    ContainerTransfer.depositContents(contents, self.getMenu().slots, excludedSlot, player);
                }
            }
        }
    }

        public static void verifyPredictions(AbstractContainerScreen<?> self) {
        try {
            ItemStack carried = self.getMenu().getCarried();
            long now = System.currentTimeMillis();
            List<BetterShulkerClient.PredictionTransaction> txs = BetterShulkerClient.getActiveTransactions();

            for (int idx = txs.size() - 1; idx >= 0; idx--) {
                BetterShulkerClient.PredictionTransaction tx = txs.get(idx);

                // Client prediction is only an instant visual layer; the server still corrects state
                // through the normal menu sync. The old rollback detector compared slots against
                // their pre-prediction values every frame, which caused valid extractions to look
                // like items spawned elsewhere and then slid into the real slot when a transient
                // server sync briefly matched the old state. Treat any observed change as accepted
                // and silently expire unchanged transactions instead of animating false rollbacks.
                boolean accepted = false;

                if (!tx.originalCarried.isEmpty()
                        && (!ItemStack.isSameItemSameComponents(carried, tx.originalCarried)
                        || carried.getCount() != tx.originalCarried.getCount())) {
                    accepted = true;
                }

                if (!accepted) {
                    var player = Minecraft.getInstance().player;
                    for (Map.Entry<Integer, ItemStack> entry : tx.originalSlots.entrySet()) {
                        ItemStack orig = entry.getValue();
                        Slot slot = MenuSlotRef.resolve(entry.getKey(), self.getMenu(), player);
                        if (slot != null) {
                            ItemStack current = slot.getItem();
                            if (!ItemStack.isSameItemSameComponents(current, orig) || current.getCount() != orig.getCount()) {
                                accepted = true;
                                break;
                            }
                        }
                    }
                }

                if (accepted || now - tx.timestamp > 750) {
                    txs.remove(idx);
                }
            }
        } catch (Exception e) {
            logPredictionFailure("verifyPredictions error: " + e);
        }
    }

    /**
     * Whether the carried stack could actually be dropped into the box sitting in this slot.
     *
     * <p>Both the bounce and the plus badge are promises that a drop will land somewhere. A full
     * box refuses the drop, so neither is shown for one.</p>
     */
}

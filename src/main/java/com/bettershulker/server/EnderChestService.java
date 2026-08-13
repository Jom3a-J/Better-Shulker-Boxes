package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.platform.PlatformNetworking;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.util.InteractionSounds;
import com.bettershulker.util.ContainerTransfer;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side Ender Chest handling.
 *
 * <p>An Ender Chest's contents live on the player, not in the item, so the client cannot read
 * them and every preview is a copy the server sent. That makes this the authority twice over: it
 * decides whether a player may see the contents at all - which requires an Ender Chest they can
 * actually reach - and it performs every change to them.</p>
 *
 * <p>Syncs are diffed against the last state sent to each player, so an open preview costs only
 * the slots that changed.</p>
 */
public final class EnderChestService {

    /** Last state synced to each player, so a sync can send only what changed. */
    private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = new HashMap<>();

    /** Last server tick on which each player requested a full Ender Chest tooltip sync. */
    private static final Map<UUID, Long> lastEnderChestSyncRequestTick = new HashMap<>();

    /** Matches the normal client's 500 ms request cooldown at 20 ticks per second. */
    private static final int ENDER_CHEST_SYNC_COOLDOWN_TICKS = 10;

    private EnderChestService() {}

    /** Forgets a player's cached state, when they disconnect. */
    public static void clearPlayer(UUID uuid) {
        lastSyncedEnderChest.remove(uuid);
        lastEnderChestSyncRequestTick.remove(uuid);
    }

    public static void resetEnderChestSync(UUID uuid) {
        lastSyncedEnderChest.remove(uuid);
    }

    /** Returns whether the player has an Ender Chest item they may actually use. */
    public static boolean hasAccessibleEnderChestInInventory(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return false;

        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()
                    && ContainerHelper.isEnderChest(stack)
                    && ContainerHelper.canAccessContainer(stack, player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the exact source that caused a client to request an Ender Chest preview.
     *
     * <p>Tooltip requests may originate from a normal menu slot, the menu's carried stack,
     * or a context where Minecraft did not expose a menu source. The exact-source paths
     * deliberately use the same active/modifiable slot checks as container interactions;
     * the fallback keeps non-screen tooltip calls compatible with the original inventory
     * authorization.</p>
     */
    public static boolean hasAccessibleEnderChestSource(ServerPlayer player, int sourceSlotId) {
        if (!player.isAlive() || player.isSpectator()) return false;

        AbstractContainerMenu menu = player.containerMenu;
        if (!menu.stillValid(player)) return false;

        if (sourceSlotId == EnderChestRequestPayload.CARRIED_SOURCE_SLOT) {
            ItemStack carried = menu.getCarried();
            return isAccessibleEnderChest(carried, player);
        }

        if (MenuSlotRef.isSlot(sourceSlotId)) {
            Slot sourceSlot = MenuSlotRef.resolve(sourceSlotId, menu, player);
            return sourceSlot != null
                    && ServerSlots.isUsableSlot(sourceSlot)
                    && sourceSlot.allowModification(player)
                    && isAccessibleEnderChest(sourceSlot.getItem(), player);
        }

        if (sourceSlotId == EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE) {
            return hasAccessibleEnderChestInInventory(player);
        }

        return false;
    }

    public static boolean isAccessibleEnderChest(ItemStack stack, ServerPlayer player) {
        return ContainerHelper.isEnderChest(stack)
                && ContainerHelper.canAccessContainer(stack, player);
    }

    public static void handleEnderChestSyncRequest(ServerPlayer player, int sourceSlotId) {
        UUID uuid = player.getUUID();
        long currentTick = player.level().getGameTime();
        Long lastRequestTick = lastEnderChestSyncRequestTick.get(uuid);
        if (lastRequestTick != null && currentTick - lastRequestTick < ENDER_CHEST_SYNC_COOLDOWN_TICKS) {
            return;
        }
        lastEnderChestSyncRequestTick.put(uuid, currentTick);

        if (!hasAccessibleEnderChestSource(player, sourceSlotId)) {
            resetEnderChestSync(uuid);
            clearEnderChestClientCache(player);
            InteractionRateLimiter.warnRejectedInteraction(player, "requested Ender Chest sync from an inaccessible source: " + sourceSlotId);
            return;
        }

        sendAuthoritativeEnderChestSync(player);
        BetterShulkerMod.LOGGER.debug("[BetterShulker] Synced ender chest for player {}", player.getName().getString());
    }

    /**
     * Pushes a complete, authoritative Ender Chest snapshot to the client.
     *
     * <p>A diff sync is computed against the last state the server sent, which cannot describe
     * what the client's optimistic prediction wrote into its own cache. Once a prediction has run
     * the two have diverged by an unknown amount: a rejected action yields an empty diff, and an
     * accepted one only names the slots the server touched, so a prediction that guessed a
     * different slot is left stranded there. Resetting the baseline transmits every slot, which
     * replaces the client cache instead of patching it.</p>
     */
    public static void sendAuthoritativeEnderChestSync(ServerPlayer player) {
        resetEnderChestSync(player.getUUID());
        PlatformNetworking.sendToPlayer(player, buildEnderChestSyncPayload(player));
    }

    public static void clearEnderChestClientCache(ServerPlayer player) {
        List<EnderChestSyncPayload.EnderChestDiff> emptyDiffs = new ArrayList<>();
        for (int i = 0; i < ContainerHelper.SHULKER_SLOT_COUNT; i++) {
            emptyDiffs.add(new EnderChestSyncPayload.EnderChestDiff(i, ItemStack.EMPTY));
        }
        PlatformNetworking.sendToPlayer(player, new EnderChestSyncPayload(emptyDiffs));
    }

    public static NonNullList<ItemStack> copyEnderChestContents(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            contents.set(i, enderInv.getItem(i).copy());
        }
        return contents;
    }

    public static void applyEnderChestContents(ServerPlayer player, NonNullList<ItemStack> contents) {
        var enderInv = player.getEnderChestInventory();
        for (int i = 0; i < enderInv.getContainerSize() && i < contents.size(); i++) {
            enderInv.setItem(i, contents.get(i));
        }
    }

    /**
     * Processes ender chest insertion/extraction on the server and reconciles both the normal
     * menu and the separate client-side Ender Chest cache.
     *
     * <p>The reconciliation is always a full snapshot rather than a diff. The client mutates its
     * cache optimistically before this packet arrives, and the server has no record of what that
     * prediction wrote, so a diff cannot describe the correction: rejected actions produce an
     * empty one, and accepted actions only cover the slots the server itself touched. Sending
     * every slot is the only reconciliation that holds regardless of what the client guessed.</p>
     */
    public static void handleEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
                                             ContainerInteractPayload.InteractType action, int inventorySlotId) {
        try {
            performEnderChestInteraction(player, containerSlot, targetIndex, action, inventorySlotId);
        } finally {
            player.containerMenu.broadcastFullState();
            sendAuthoritativeEnderChestSync(player);
        }
    }

    public static void performEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
                                             ContainerInteractPayload.InteractType action, int inventorySlotId) {
        var enderInv = player.getEnderChestInventory();
        ItemStack cursorStack = player.containerMenu.getCarried();
        boolean success = false;
        boolean isInsert = false;
        ItemStack soundStack = ItemStack.EMPTY;

        switch (action) {
            case INSERT -> {
                if (cursorStack.isEmpty()) return;
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
                        NonNullList<ItemStack> enderList = copyEnderChestContents(player);
                        int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(enderList, cursorStack);
                        if (bestSlot == -1) break;

                        int toInsert = Math.min(cursorStack.getMaxStackSize(), cursorStack.getCount());
                        enderInv.setItem(bestSlot, cursorStack.copyWithCount(toInsert));
                        cursorStack.shrink(toInsert);
                    }
                }

                if (cursorStack.getCount() < originalCount) {
                    success = true;
                    isInsert = true;
                    soundStack = insertedSource;
                }
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
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
                    NonNullList<ItemStack> enderList = copyEnderChestContents(player);
                    int bestSlot = ContainerTransfer.findSmartMergeEmptySlot(enderList, singleItem);
                    if (bestSlot != -1) {
                        enderInv.setItem(bestSlot, singleItem);
                        inserted = true;
                    }
                }

                if (inserted) {
                    cursorStack.shrink(1);
                    success = true;
                    isInsert = true;
                    soundStack = singleItem;
                }
            }
            case EXTRACT -> {
                if (!cursorStack.isEmpty()) return;
                ItemStack extracted = enderInv.getItem(targetIndex).copy();
                if (!extracted.isEmpty()) {
                    enderInv.setItem(targetIndex, ItemStack.EMPTY);
                    player.containerMenu.setCarried(extracted);
                    success = true;
                    soundStack = extracted;
                }
            }
            case EXTRACT_ONE -> {
                ItemStack slotStack = enderInv.getItem(targetIndex);
                if (slotStack.isEmpty()) return;
                ItemStack extracted = slotStack.copyWithCount(1);
                soundStack = extracted.copy();

                if (inventorySlotId != -1) {
                    Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "ender chest slot extraction");
                    if (destination == null) return;
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
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "SWEEP_INSERT");
                if (targetSlot == null || !targetSlot.allowModification(player)) return;
                ItemStack originalStack = targetSlot.getItem();
                if (originalStack.isEmpty()) return;
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
                        NonNullList<ItemStack> enderList = copyEnderChestContents(player);
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
                    success = true;
                    isInsert = true;
                    soundStack = originalStack.copy();
                }
            }
            case SWEEP_EXTRACT -> {
                ItemStack shulkerStack = enderInv.getItem(targetIndex);
                if (shulkerStack.isEmpty()) return;
                soundStack = shulkerStack;

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
                    if (destination == null) return;
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
            }
            case RESTOCK -> {
                NonNullList<ItemStack> contents = copyEnderChestContents(player);
                success = ContainerTransfer.restockContents(contents, player.containerMenu.slots, player);
                if (success) {
                    applyEnderChestContents(player, contents);
                }
            }
            case DEPOSIT -> {
                NonNullList<ItemStack> contents = copyEnderChestContents(player);
                success = ContainerTransfer.depositContents(contents, player.containerMenu.slots, containerSlot, player);
                if (success) {
                    applyEnderChestContents(player, contents);
                    isInsert = true;
                }
            }
        }

        if (success) {
            InteractionSounds.playInteractionSound(player, soundStack, isInsert, 0.3F);
        }
    }

    /**
     * Builds an S2C sync payload containing only the differences (diffs)
     * between the current player's ender chest contents and the last synced state.
     * Ensures minimum bandwidth overhead.
     */
    public static EnderChestSyncPayload buildEnderChestSyncPayload(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        int size = enderInv.getContainerSize();
        UUID uuid = player.getUUID();

        NonNullList<ItemStack> lastState = lastSyncedEnderChest.get(uuid);
        boolean isFullSync = (lastState == null);

        if (isFullSync) {
            lastState = NonNullList.withSize(size, ItemStack.EMPTY);
            lastSyncedEnderChest.put(uuid, lastState);
        }

        List<EnderChestSyncPayload.EnderChestDiff> diffs = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            ItemStack currentStack = enderInv.getItem(i);
            ItemStack lastStack = lastState.get(i);

            if (isFullSync || ServerSlots.hasStackChanged(currentStack, lastStack)) {
                diffs.add(new EnderChestSyncPayload.EnderChestDiff(i, currentStack.copy()));
                lastState.set(i, currentStack.copy());
            }
        }

        return new EnderChestSyncPayload(diffs);
    }
}

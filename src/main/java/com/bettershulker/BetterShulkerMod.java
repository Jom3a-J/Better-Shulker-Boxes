package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.platform.PlatformNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/**
 * Better Shulker — Main server/common entry point.
 *
 * Responsibilities:
 * Loader-specific entrypoints register networking/events and call into this shared validation layer.
 *
 * Minecraft 26.2 is unobfuscated — all names use Mojang official mappings.
 */
public class BetterShulkerMod {

    // =========================================================================
    //  Constants & Fields
    // =========================================================================

    public static final String MOD_ID = "bettershulker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Diffs cache to keep track of the last synced Ender Chest state per player UUID. */
    private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = new HashMap<>();
    
    /** Keeps track of the last game tick an interaction was processed per player UUID. */
    private static final Map<UUID, Long> lastInteractionTick = new HashMap<>();
    
    /** Rate-limiting count of interactions processed in the current tick per player. */
    private static final Map<UUID, Integer> interactionCountsThisTick = new HashMap<>();

    /** Last server tick on which each player's rejected-interaction warning was logged. */
    private static final Map<UUID, Long> lastInteractionWarningTick = new HashMap<>();

    /** Last server tick on which each player requested a full Ender Chest tooltip sync. */
    private static final Map<UUID, Long> lastEnderChestSyncRequestTick = new HashMap<>();

    private static final long INTERACTION_WARNING_COOLDOWN_TICKS = 100L;

    /** Matches the normal client's 500 ms request cooldown at 20 ticks per second. */
    private static final int ENDER_CHEST_SYNC_COOLDOWN_TICKS = 10;
    
    /**
     * Maximum allowed container interactions per single game tick (exploit protection).
     * Multi-select extraction can legitimately send up to one packet per shulker slot,
     * so this must be high enough for a full 27-slot batch while still bounding spam.
     */
    private static final int MAX_INTERACTIONS_PER_TICK = 32;

    // =========================================================================
    //  Shared Cache / Validation Utilities

    public static void clearPlayerCaches(UUID uuid) {
        lastSyncedEnderChest.remove(uuid);
        lastInteractionTick.remove(uuid);
        interactionCountsThisTick.remove(uuid);
        lastInteractionWarningTick.remove(uuid);
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

    public static boolean consumeInteraction(ServerPlayer player) {
        long currentTick = player.level().getGameTime();
        UUID uuid = player.getUUID();

        long lastTick = lastInteractionTick.getOrDefault(uuid, -1L);
        if (lastTick != currentTick) {
            lastInteractionTick.put(uuid, currentTick);
            interactionCountsThisTick.put(uuid, 0);
        }

        int count = interactionCountsThisTick.get(uuid);
        if (count >= MAX_INTERACTIONS_PER_TICK) {
            return false;
        }
        interactionCountsThisTick.put(uuid, count + 1);
        return true;
    }

    /** Limits logs from malformed or unauthorized client payloads without hiding them entirely. */
    private static void warnRejectedInteraction(ServerPlayer player, String detail) {
        long currentTick = player.level().getGameTime();
        UUID uuid = player.getUUID();
        Long lastWarningTick = lastInteractionWarningTick.get(uuid);
        if (lastWarningTick == null || currentTick - lastWarningTick >= INTERACTION_WARNING_COOLDOWN_TICKS) {
            lastInteractionWarningTick.put(uuid, currentTick);
            LOGGER.warn("[BetterShulker] Player {} {}", player.getName().getString(), detail);
        }
    }

    public static void handleEnderChestSyncRequest(ServerPlayer player) {
        UUID uuid = player.getUUID();
        long currentTick = player.level().getGameTime();
        Long lastRequestTick = lastEnderChestSyncRequestTick.get(uuid);
        if (lastRequestTick != null && currentTick - lastRequestTick < ENDER_CHEST_SYNC_COOLDOWN_TICKS) {
            return;
        }
        lastEnderChestSyncRequestTick.put(uuid, currentTick);

        if (!hasAccessibleEnderChestInInventory(player)) {
            warnRejectedInteraction(player, "requested Ender Chest sync without an accessible Ender Chest item");
            return;
        }

        resetEnderChestSync(uuid);
        PlatformNetworking.sendToPlayer(player, buildEnderChestSyncPayload(player));
        LOGGER.debug("[BetterShulker] Synced ender chest for player {}", player.getName().getString());
    }

    public static void handleRateLimitedContainerInteraction(ServerPlayer player, ContainerInteractPayload payload) {
        if (!consumeInteraction(player)) {
            warnRejectedInteraction(player, "exceeded interaction rate limit; dropping packets");
            return;
        }
        handleContainerInteraction(player, payload);
    }

    //  Interaction Logic Handler & Validation
    // =========================================================================

    /**
     * Static entry point for processing a ContainerInteractPayload.
     * Used by both the remote packet handler AND single-player direct calls,
     * ensuring identical server-side validation regardless of invocation path.
     *
     * The server independently validates every operation:
     * 1. The container slot ID maps to a real slot in the player's current menu
     * 2. The item in that slot is actually a shulker box or ender chest
     * 3. The target index (0-26) is within bounds
     * 4. The insertion/extraction is physically possible (stack sizes, nesting rules)
     * 5. The cursor stack matches what the client claims
     */
    public static void handleContainerInteraction(ServerPlayer player, ContainerInteractPayload payload) {
        int containerSlotId = payload.containerSlotId();
        int targetIndex = payload.targetIndex();
        AbstractContainerMenu menu = player.containerMenu;

        // Custom payloads bypass vanilla's normal container-click gate, so repeat its
        // fundamental player/menu checks before touching any inventory state.
        if (!player.isAlive() || player.isSpectator() || !menu.stillValid(player)) {
            resyncPlayer(player);
            return;
        }

        // -- Validate Slot Bounds
        if (containerSlotId != -1 && (containerSlotId < 0 || containerSlotId >= menu.slots.size())) {
            warnRejectedInteraction(player, "sent invalid container slot ID: " + containerSlotId);
            return;
        }

        // -- Parse action type BEFORE validating targetIndex
        ContainerInteractPayload.InteractType action;
        try {
            action = ContainerInteractPayload.InteractType.fromId(payload.action());
        } catch (IllegalArgumentException e) {
            warnRejectedInteraction(player, "sent invalid action ID: " + payload.action());
            return;
        }

        // -- Validate targetIndex only for actions that use it
        boolean needsTargetIndex = (action != ContainerInteractPayload.InteractType.SWEEP_INSERT
                && action != ContainerInteractPayload.InteractType.INSERT
                && action != ContainerInteractPayload.InteractType.INSERT_ONE
                && action != ContainerInteractPayload.InteractType.RESTOCK
                && action != ContainerInteractPayload.InteractType.DEPOSIT);
        if (needsTargetIndex && (targetIndex < 0 || targetIndex >= 27)) {
            warnRejectedInteraction(player, "sent invalid target index: " + targetIndex + " for action " + action);
            return;
        }

        int inventorySlotId = payload.inventorySlotId();

        // -- Validate the source slot before reading or mutating a container preview.
        // Result, fake, inactive, locked, and otherwise non-modifiable slots must never be
        // treated as real container items. In particular, crafting result stacks are only
        // previews until ResultSlot.onTake consumes the recipe inputs.
        Slot containerSlot = null;
        if (containerSlotId != -1) {
            containerSlot = menu.slots.get(containerSlotId);
            if (!isUsableSlot(containerSlot) || !containerSlot.allowModification(player)) {
                warnRejectedInteraction(player, "referenced non-modifiable container slot: " + containerSlotId);
                resyncPlayer(player);
                return;
            }
        }

        // Copy the stack so all component changes remain transactional until commit.
        ItemStack containerStack = containerSlot == null ? menu.getCarried().copy() : containerSlot.getItem().copy();

        if (containerStack.isEmpty()) {
            warnRejectedInteraction(player, "referenced empty container");
            resyncPlayer(player);
            return;
        }
        if (!ContainerHelper.canAccessContainer(containerStack, player)) {
            resyncPlayer(player);
            return;
        }

        // -- Handle Ender Chest / Shulker Interactions
        if (ContainerHelper.isEnderChest(containerStack)) {
            handleEnderChestInteraction(player, containerSlot, targetIndex, action, inventorySlotId);
            return;
        }

        if (ContainerHelper.isShulkerBox(containerStack)) {
            handleShulkerInteraction(player, containerSlot, containerStack, targetIndex, action, inventorySlotId);
            // Always correct rejected client prediction, including case-local early returns.
            player.containerMenu.broadcastFullState();
            return;
        }

        // Item is neither a shulker nor ender chest -- reject
        warnRejectedInteraction(player, "tried to interact with non-container item: " + containerStack.getItem());
        resyncPlayer(player);
    }

    // =========================================================================
    //  Shulker Box Operations
    // =========================================================================

    /**
     * Processes shulker box insertion/extraction on the server.
     * Reads from DataComponents.CONTAINER, validates, modifies, and writes back.
     */
    private static void handleShulkerInteraction(ServerPlayer player, Slot containerSlot, ItemStack containerStack,
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
                ItemStack remainder = ContainerHelper.tryInsert(contents, cursorStack.copy(), false);
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
                ItemStack remainder = ContainerHelper.tryInsert(contents, singleItem, true);
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
                ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                if (!extracted.isEmpty()) {
                    player.containerMenu.setCarried(extracted);
                    success = true;
                    soundStack = extracted;
                }
            }
            case EXTRACT_ONE -> {
                // Precision mode: extract exactly 1 item from targetIndex.
                ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, true);
                if (!extracted.isEmpty()) {
                    soundStack = extracted.copy();
                    if (inventorySlotId != -1) {
                        Slot destination = getPlayerInventorySlot(player, inventorySlotId, "slot extraction");
                        if (destination == null) {
                            restoreExtractedStack(contents, targetIndex, extracted);
                            return;
                        }
                        int originalCount = extracted.getCount();
                        ItemStack remainder = safeInsertIntoSlot(player, destination, extracted);
                        restoreExtractedStack(contents, targetIndex, remainder);
                        success = remainder.getCount() < originalCount;
                    } else if (cursorStack.isEmpty()) {
                        player.containerMenu.setCarried(extracted);
                        success = true;
                    } else if (canMergeInto(cursorStack, extracted)) {
                        cursorStack.grow(1);
                        success = true;
                    } else {
                        restoreExtractedStack(contents, targetIndex, extracted);
                        return;
                    }
                }
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = getPlayerInventorySlot(player, inventorySlotId, "SWEEP_INSERT");
                if (targetSlot == null || !targetSlot.allowModification(player)) return;
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                int originalCount = invStack.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, invStack.copy(), false);
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
                        ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                        player.containerMenu.setCarried(extracted);
                        success = true;
                    } else if (canMergeInto(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            cursorStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                            success = true;
                        }
                    }
                } else {
                    Slot destination = getPlayerInventorySlot(player, inventorySlotId, "slot sweep extraction");
                    if (destination == null) return;
                    ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                    int originalCount = extracted.getCount();
                    ItemStack remainder = safeInsertIntoSlot(player, destination, extracted);
                    restoreExtractedStack(contents, targetIndex, remainder);
                    success = remainder.getCount() < originalCount;
                }
            }
            case RESTOCK -> {
                success = ContainerHelper.restockContents(contents, player.containerMenu.slots, player);
            }
            case DEPOSIT -> {
                success = ContainerHelper.depositContents(contents, player.containerMenu.slots, containerSlot, player);
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
            ContainerHelper.playInteractionSound(player, soundStack, isInsert, 0.3F);
        }

    }

    // =========================================================================
    private static boolean isUsableSlot(Slot slot) {
        return slot != null && slot.isActive() && !slot.isFake();
    }

    private static Slot getPlayerInventorySlot(ServerPlayer player, int slotId, String actionDescription) {
        if (slotId < 0 || slotId >= player.containerMenu.slots.size()) {
            warnRejectedInteraction(player, "tried " + actionDescription + " with invalid inventory slot: " + slotId);
            return null;
        }

        Slot slot = player.containerMenu.slots.get(slotId);
        if (slot.container != player.getInventory() || !isUsableSlot(slot)) {
            warnRejectedInteraction(player, "tried " + actionDescription
                    + " on an unavailable player-inventory slot: " + slotId);
            return null;
        }
        return slot;
    }

    /**
     * Uses the vanilla slot insertion path while additionally rejecting fake/inactive slots and
     * occupied slots the player is not allowed to modify. The returned stack is the remainder.
     */
    private static ItemStack safeInsertIntoSlot(ServerPlayer player, Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !isUsableSlot(slot) || !slot.mayPlace(stack)) {
            return stack;
        }
        if (!slot.getItem().isEmpty() && !slot.allowModification(player)) {
            return stack;
        }
        return slot.safeInsert(stack);
    }

    private static void restoreExtractedStack(NonNullList<ItemStack> contents, int index, ItemStack remainder) {
        if (remainder.isEmpty()) return;

        ItemStack current = contents.get(index);
        if (current.isEmpty()) {
            contents.set(index, remainder);
        } else if (ItemStack.isSameItemSameComponents(current, remainder)) {
            current.grow(remainder.getCount());
        } else {
            throw new IllegalStateException("Extracted stack no longer matches its source slot");
        }
    }

    private static boolean canMergeInto(ItemStack target, ItemStack source) {
        return !target.isEmpty()
                && ItemStack.isSameItemSameComponents(target, source)
                && target.getCount() < target.getMaxStackSize();
    }

    private static boolean hasStackChanged(ItemStack current, ItemStack previous) {
        return !ItemStack.isSameItemSameComponents(current, previous)
                || current.getCount() != previous.getCount();
    }

    private static NonNullList<ItemStack> copyEnderChestContents(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            contents.set(i, enderInv.getItem(i).copy());
        }
        return contents;
    }

    private static void applyEnderChestContents(ServerPlayer player, NonNullList<ItemStack> contents) {
        var enderInv = player.getEnderChestInventory();
        for (int i = 0; i < enderInv.getContainerSize() && i < contents.size(); i++) {
            enderInv.setItem(i, contents.get(i));
        }
    }

    private static boolean enderChestContentsChanged(ServerPlayer player, NonNullList<ItemStack> before) {
        var enderInv = player.getEnderChestInventory();
        if (enderInv.getContainerSize() != before.size()) return true;
        for (int i = 0; i < before.size(); i++) {
            if (hasStackChanged(enderInv.getItem(i), before.get(i))) {
                return true;
            }
        }
        return false;
    }

    //  Ender Chest Operations
    // =========================================================================

    /**
     * Processes ender chest insertion/extraction on the server and reconciles both the normal
     * menu and the separate client-side Ender Chest cache. Rejected extraction predictions get
     * one authoritative target-slot update without amplifying a no-op packet into a 27-slot sync.
     */
    private static void handleEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
                                             ContainerInteractPayload.InteractType action, int inventorySlotId) {
        NonNullList<ItemStack> before = copyEnderChestContents(player);
        try {
            performEnderChestInteraction(player, containerSlot, targetIndex, action, inventorySlotId);
        } finally {
            player.containerMenu.broadcastFullState();
            boolean changed = enderChestContentsChanged(player, before);
            boolean extractionAction = action == ContainerInteractPayload.InteractType.EXTRACT
                    || action == ContainerInteractPayload.InteractType.EXTRACT_ONE
                    || action == ContainerInteractPayload.InteractType.SWEEP_EXTRACT;
            if (!changed && extractionAction && targetIndex >= 0
                    && targetIndex < player.getEnderChestInventory().getContainerSize()) {
                ItemStack authoritative = player.getEnderChestInventory().getItem(targetIndex).copy();
                PlatformNetworking.sendToPlayer(player, new EnderChestSyncPayload(List.of(
                        new EnderChestSyncPayload.EnderChestDiff(targetIndex, authoritative)
                )));
            } else {
                PlatformNetworking.sendToPlayer(player, buildEnderChestSyncPayload(player));
            }
        }
    }

    private static void performEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
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
                
                // First pass: merge with existing compatible stacks
                for (int i = 0; i < enderInv.getContainerSize(); i++) {
                    ItemStack existing = enderInv.getItem(i);
                    if (canMergeInto(existing, cursorStack)) {
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
                        int bestSlot = ContainerHelper.findSmartMergeEmptySlot(enderList, cursorStack);
                        if (bestSlot == -1) break;

                        int toInsert = Math.min(cursorStack.getMaxStackSize(), cursorStack.getCount());
                        enderInv.setItem(bestSlot, cursorStack.copyWithCount(toInsert));
                        cursorStack.shrink(toInsert);
                    }
                }

                if (cursorStack.getCount() < originalCount) {
                    success = true;
                    isInsert = true;
                    soundStack = cursorStack;
                }
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                boolean inserted = false;

                // First pass: merge with existing compatible stacks
                for (int i = 0; i < enderInv.getContainerSize(); i++) {
                    ItemStack existing = enderInv.getItem(i);
                    if (canMergeInto(existing, singleItem)) {
                        existing.grow(1);
                        inserted = true;
                        break;
                    }
                }

                // Second pass: put into empty slots using smart-merge
                if (!inserted) {
                    NonNullList<ItemStack> enderList = copyEnderChestContents(player);
                    int bestSlot = ContainerHelper.findSmartMergeEmptySlot(enderList, singleItem);
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
                    Slot destination = getPlayerInventorySlot(player, inventorySlotId, "ender chest slot extraction");
                    if (destination == null) return;
                    ItemStack remainder = safeInsertIntoSlot(player, destination, extracted);
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
                } else if (canMergeInto(cursorStack, extracted)) {
                    cursorStack.grow(1);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        enderInv.setItem(targetIndex, ItemStack.EMPTY);
                    }
                    success = true;
                }
            }
            case SWEEP_INSERT -> {
                Slot targetSlot = getPlayerInventorySlot(player, inventorySlotId, "SWEEP_INSERT");
                if (targetSlot == null || !targetSlot.allowModification(player)) return;
                ItemStack originalStack = targetSlot.getItem();
                if (originalStack.isEmpty()) return;
                ItemStack invStack = originalStack.copy();
                int originalCount = invStack.getCount();

                // Auto-insert invStack into the ender chest inventory
                // First pass: merge with existing compatible stacks
                for (int i = 0; i < enderInv.getContainerSize(); i++) {
                    ItemStack existing = enderInv.getItem(i);
                    if (canMergeInto(existing, invStack)) {
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
                        int bestSlot = ContainerHelper.findSmartMergeEmptySlot(enderList, invStack);
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
                    } else if (canMergeInto(cursorStack, shulkerStack)) {
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
                    Slot destination = getPlayerInventorySlot(player, inventorySlotId, "ender chest slot sweep extraction");
                    if (destination == null) return;
                    ItemStack transfer = shulkerStack.copy();
                    int originalCount = transfer.getCount();
                    ItemStack remainder = safeInsertIntoSlot(player, destination, transfer);
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
                success = ContainerHelper.restockContents(contents, player.containerMenu.slots, player);
                if (success) {
                    applyEnderChestContents(player, contents);
                }
            }
            case DEPOSIT -> {
                NonNullList<ItemStack> contents = copyEnderChestContents(player);
                success = ContainerHelper.depositContents(contents, player.containerMenu.slots, containerSlot, player);
                if (success) {
                    applyEnderChestContents(player, contents);
                    isInsert = true;
                }
            }
        }

        if (success) {
            ContainerHelper.playInteractionSound(player, soundStack, isInsert, 0.3F);
        }
    }

    // =========================================================================
    //  Synchronization & Resync Utilities
    // =========================================================================

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

            if (isFullSync || hasStackChanged(currentStack, lastStack)) {
                diffs.add(new EnderChestSyncPayload.EnderChestDiff(i, currentStack.copy()));
                lastState.set(i, currentStack.copy());
            }
        }

        return new EnderChestSyncPayload(diffs);
    }

    /**
     * Re-syncs the player's entire inventory to fix any client-side desync.
     * This is the nuclear option — used when server validation fails.
     */
    private static void resyncPlayer(ServerPlayer player) {
        player.containerMenu.broadcastFullState();
        player.inventoryMenu.broadcastFullState();
    }
}

package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.server.EnderChestService;
import com.bettershulker.server.ServerSlots;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.platform.PlatformNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;

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
    /** Keeps track of the last game tick an interaction was processed per player UUID. */
    private static final Map<UUID, Long> lastInteractionTick = new HashMap<>();
    
    /** Rate-limiting count of interactions processed in the current tick per player. */
    private static final Map<UUID, Integer> interactionCountsThisTick = new HashMap<>();

    /** Last server tick on which each player's rejected-interaction warning was logged. */
    private static final Map<UUID, Long> lastInteractionWarningTick = new HashMap<>();
    /** Last server tick on which a dropped interaction triggered a corrective resync per player. */
    private static final Map<UUID, Long> lastInteractionDropResyncTick = new HashMap<>();

    private static final long INTERACTION_WARNING_COOLDOWN_TICKS = 100L;
    /**
     * Maximum allowed container interactions per single game tick (exploit protection).
     * Multi-select extraction can legitimately send up to one packet per shulker slot,
     * so this must be high enough for a full 27-slot batch while still bounding spam.
     */
    private static final int MAX_INTERACTIONS_PER_TICK = 32;

    // =========================================================================
    //  Shared Cache / Validation Utilities

    public static void clearPlayerCaches(UUID uuid) {
        EnderChestService.clearPlayer(uuid);
        lastInteractionTick.remove(uuid);
        interactionCountsThisTick.remove(uuid);
        lastInteractionWarningTick.remove(uuid);
        lastInteractionDropResyncTick.remove(uuid);
    }


    /**
     * Reads the client's cached Ender Chest contents, installed by the client entrypoint.
     *
     * <p>Common code cannot name the client class directly: it lives in the same jar on a
     * dedicated server, where its client-only supertypes are absent, so referencing it risks a
     * {@code NoClassDefFoundError} during verification. Injecting a supplier keeps the reference
     * client-side, and leaves this null on a server rather than failing at load.</p>
     */
    private static volatile Supplier<NonNullList<ItemStack>> clientEnderChestSupplier;

    public static void setClientEnderChestSupplier(Supplier<NonNullList<ItemStack>> supplier) {
        clientEnderChestSupplier = supplier;
    }

    @Nullable
    public static NonNullList<ItemStack> getClientEnderChestContents() {
        Supplier<NonNullList<ItemStack>> supplier = clientEnderChestSupplier;
        return supplier == null ? null : supplier.get();
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
    public static void warnRejectedInteraction(ServerPlayer player, String detail) {
        long currentTick = player.level().getGameTime();
        UUID uuid = player.getUUID();
        Long lastWarningTick = lastInteractionWarningTick.get(uuid);
        if (lastWarningTick == null || currentTick - lastWarningTick >= INTERACTION_WARNING_COOLDOWN_TICKS) {
            lastInteractionWarningTick.put(uuid, currentTick);
            LOGGER.warn("[BetterShulker] Player {} {}", player.getName().getString(), detail);
        }
    }




    public static void handleRateLimitedContainerInteraction(ServerPlayer player, ContainerInteractPayload payload) {
        if (!consumeInteraction(player)) {
            warnRejectedInteraction(player, "exceeded interaction rate limit; dropping packets");
            resyncDroppedInteraction(player);
            return;
        }
        handleContainerInteraction(player, payload);
    }

    /**
     * Corrects the client after the rate limiter discards a packet.
     *
     * <p>The client applies its prediction before sending and never rolls it back on its own, so
     * a silent drop would leave items on screen that the server never moved. A single correction
     * per tick covers every packet that tick discarded while stopping a packet flood from being
     * amplified into an equally large flood of resyncs.</p>
     */
    private static void resyncDroppedInteraction(ServerPlayer player) {
        long currentTick = player.level().getGameTime();
        UUID uuid = player.getUUID();
        Long lastResyncTick = lastInteractionDropResyncTick.get(uuid);
        if (lastResyncTick != null && lastResyncTick == currentTick) {
            return;
        }
        lastInteractionDropResyncTick.put(uuid, currentTick);

        ServerSlots.resyncPlayer(player);

        // broadcastFullState cannot reach the mod's separate Ender Chest cache, so correct it
        // explicitly: a predicted Ender Chest edit must not survive the drop.
        EnderChestService.sendAuthoritativeEnderChestSync(player);
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
            ServerSlots.resyncPlayer(player);
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
        if (containerSlotId != MenuSlotRef.NONE) {
            // Only the exact sentinel means "the carried stack". Any other negative is malformed
            // and must not silently fall through to the carried path.
            if (!MenuSlotRef.isSlot(containerSlotId)) {
                warnRejectedInteraction(player, "sent invalid container slot ID: " + containerSlotId);
                return;
            }
            containerSlot = MenuSlotRef.resolve(containerSlotId, menu, player);
            if (containerSlot == null) {
                warnRejectedInteraction(player, "sent unresolvable container slot ID: " + containerSlotId);
                return;
            }
            if (!ServerSlots.isUsableSlot(containerSlot) || !containerSlot.allowModification(player)) {
                warnRejectedInteraction(player, "referenced non-modifiable container slot: " + containerSlotId);
                ServerSlots.resyncPlayer(player);
                return;
            }
        }

        // Copy the stack so all component changes remain transactional until commit.
        ItemStack containerStack = containerSlot == null ? menu.getCarried().copy() : containerSlot.getItem().copy();

        if (containerStack.isEmpty()) {
            warnRejectedInteraction(player, "referenced empty container");
            ServerSlots.resyncPlayer(player);
            return;
        }
        if (!ContainerHelper.canAccessContainer(containerStack, player)) {
            ServerSlots.resyncPlayer(player);
            return;
        }

        // -- Handle Ender Chest / Shulker Interactions
        if (ContainerHelper.isEnderChest(containerStack)) {
            EnderChestService.handleEnderChestInteraction(player, containerSlot, targetIndex, action, inventorySlotId);
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
        ServerSlots.resyncPlayer(player);
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
                    } else if (ServerSlots.canMergeInto(cursorStack, shulkerStack)) {
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
                    Slot destination = ServerSlots.getPlayerInventorySlot(player, inventorySlotId, "slot sweep extraction");
                    if (destination == null) return;
                    ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                    int originalCount = extracted.getCount();
                    ItemStack remainder = ServerSlots.safeInsertIntoSlot(player, destination, extracted);
                    ServerSlots.restoreExtractedStack(player, contents, targetIndex, remainder);
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









    //  Ender Chest Operations
    // =========================================================================



    // =========================================================================
    //  Synchronization & Resync Utilities
    // =========================================================================


}

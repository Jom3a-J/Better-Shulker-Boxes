package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.platform.PlatformNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * 1. Register all custom payload types (C2S and S2C) via PayloadTypeRegistry
 * 2. Handle server-side Ender Chest sync requests
 * 3. Validate and process all container interaction packets (anti-duplication)
 *
 * Minecraft 26.1 is unobfuscated — all names use Mojang official mappings.
 */
public class BetterShulkerMod implements ModInitializer {

    // =========================================================================
    //  Constants & Fields
    // =========================================================================

    public static final String MOD_ID = "bettershulker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Diffs cache to keep track of the last synced Ender Chest state per player UUID. */
    private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = new HashMap<>();
    
    /** Keeps track of the last game tick an interaction was processed per player UUID. */
    public static final Map<UUID, Long> lastInteractionTick = new java.util.HashMap<>();
    
    /** Rate-limiting count of interactions processed in the current tick per player. */
    public static final Map<UUID, Integer> interactionCountsThisTick = new java.util.HashMap<>();
    
    /**
     * Maximum allowed container interactions per single game tick (exploit protection).
     * Multi-select extraction can legitimately send up to one packet per shulker slot,
     * so this must be high enough for a full 27-slot batch while still bounding spam.
     */
    public static final int MAX_INTERACTIONS_PER_TICK = 32;

    // =========================================================================
    //  Lifecycle & Initialization
    // =========================================================================

    @Override
    public void onInitialize() {
        LOGGER.info("[BetterShulker] Initializing Better Shulker mod for Minecraft 26.1");

        // -- Register Custom Payload Types --
        // C2S: Client requests Ender Chest contents
        PayloadTypeRegistry.serverboundPlay().register(
                EnderChestRequestPayload.TYPE,
                EnderChestRequestPayload.CODEC
        );

        // S2C: Server sends Ender Chest contents back to client
        PayloadTypeRegistry.clientboundPlay().register(
                EnderChestSyncPayload.TYPE,
                EnderChestSyncPayload.CODEC
        );

        // C2S: Client requests a container interaction (insert/extract)
        PayloadTypeRegistry.serverboundPlay().register(
                ContainerInteractPayload.TYPE,
                ContainerInteractPayload.CODEC
        );

        // -- Register Server-Side Packet Handlers --
        registerEnderChestRequestHandler();
        registerContainerInteractHandler();

        // Clean up cached states when players disconnect to prevent memory leaks
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            lastSyncedEnderChest.remove(uuid);
            lastInteractionTick.remove(uuid);
            interactionCountsThisTick.remove(uuid);
        });

        LOGGER.info("[BetterShulker] All payload types and handlers registered successfully");
    }

    // =========================================================================
    //  Network Packet Receivers
    // =========================================================================

    /**
     * Handles C2S ender chest request: reads the player's ender chest inventory
     * and sends all 27 slots back to the client via S2C packet.
     */
    private void registerEnderChestRequestHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
                EnderChestRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();

                    // Access the player's persistent ender chest inventory on the server thread.
                    context.player().level().getServer().execute(() -> {
                        // Verify player has an ender chest item in their inventory to access wireless features!
                        boolean hasEnderChest = false;
                        var inv = player.getInventory();
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            ItemStack stack = inv.getItem(i);
                            if (!stack.isEmpty() && ContainerHelper.isEnderChest(stack)) {
                                hasEnderChest = true;
                                break;
                            }
                        }
                        if (!hasEnderChest) {
                            LOGGER.warn("[BetterShulker] Player {} requested ender chest sync without carrying one in their inventory!", player.getName().getString());
                            return;
                        }

                        // Force clean full sync on initial request by clearing cache entry
                        lastSyncedEnderChest.remove(player.getUUID());
                        PlatformNetworking.sendToPlayer(player, buildEnderChestSyncPayload(player));
                        LOGGER.debug("[BetterShulker] Synced ender chest for player {}", player.getName().getString());
                    });
                }
        );
    }

    /**
     * Handles C2S container interaction packets. This is the critical anti-duplication
     * validation layer — ALL container mutations flow through here for server authority.
     */
    private void registerContainerInteractHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
                ContainerInteractPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    player.level().getServer().execute(() -> {
                        long currentTick = player.level().getGameTime();
                        UUID uuid = player.getUUID();
                        
                        long lastTick = lastInteractionTick.getOrDefault(uuid, -1L);
                        if (lastTick != currentTick) {
                            lastInteractionTick.put(uuid, currentTick);
                            interactionCountsThisTick.put(uuid, 0);
                        }
                        
                        int count = interactionCountsThisTick.get(uuid);
                        if (count >= MAX_INTERACTIONS_PER_TICK) {
                            LOGGER.warn("[BetterShulker] Player {} exceeded interaction rate limit, dropping packet",
                                    player.getName().getString());
                            return;
                        }
                        interactionCountsThisTick.put(uuid, count + 1);
                        
                        handleContainerInteraction(player, payload);
                    });
                }
        );
    }

    // =========================================================================
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
        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;

        // -- Validate Slot Bounds
        if (containerSlotId != -1 && containerSlotId != -2 && (containerSlotId < 0 || containerSlotId >= menu.slots.size())) {
            LOGGER.warn("[BetterShulker] Player {} sent invalid container slot ID: {}",
                    player.getName().getString(), containerSlotId);
            return;
        }

        // -- Parse action type BEFORE validating targetIndex
        ContainerInteractPayload.InteractType action;
        try {
            action = ContainerInteractPayload.InteractType.fromId(payload.action());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[BetterShulker] Player {} sent invalid action ID: {}",
                    player.getName().getString(), payload.action());
            return;
        }

        // -- Validate targetIndex only for actions that use it
        boolean needsTargetIndex = (action != ContainerInteractPayload.InteractType.SWEEP_INSERT
                && action != ContainerInteractPayload.InteractType.INSERT
                && action != ContainerInteractPayload.InteractType.INSERT_ONE
                && action != ContainerInteractPayload.InteractType.RESTOCK
                && action != ContainerInteractPayload.InteractType.DEPOSIT);
        if (needsTargetIndex && (targetIndex < 0 || targetIndex >= 27)) {
            LOGGER.warn("[BetterShulker] Player {} sent invalid target index: {} for action {}",
                    player.getName().getString(), targetIndex, action);
            return;
        }

        int inventorySlotId = payload.inventorySlotId();

        // -- Wireless Ender Chest Bypass
        if (containerSlotId == -2) {
            // Security check: player must carry an Ender Chest item in their inventory to use wireless features!
            boolean hasEnderChest = false;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && ContainerHelper.isEnderChest(stack)) {
                    hasEnderChest = true;
                    break;
                }
            }

            if (hasEnderChest) {
                handleEnderChestInteraction(player, targetIndex, action, inventorySlotId);
            } else {
                LOGGER.warn("[BetterShulker] Player {} tried to use wireless Ender Chest without carrying one in their inventory!", player.getName().getString());
                resyncPlayer(player);
            }
            return;
        }

        // -- Validate Container Item (Copy stack to guarantee client slot sync upon server-side component modification)
        ItemStack containerStack = containerSlotId == -1 ? menu.getCarried().copy() : menu.slots.get(containerSlotId).getItem().copy();

        if (containerStack.isEmpty()) {
            LOGGER.warn("[BetterShulker] Player {} referenced empty container",
                    player.getName().getString());
            resyncPlayer(player);
            return;
        }

        // -- Handle Ender Chest / Shulker Interactions
        if (ContainerHelper.isEnderChest(containerStack)) {
            handleEnderChestInteraction(player, targetIndex, action, inventorySlotId);
            return;
        }

        if (ContainerHelper.isShulkerBox(containerStack)) {
            handleShulkerInteraction(player, containerSlotId, containerStack, targetIndex, action, inventorySlotId);
            return;
        }

        // Item is neither a shulker nor ender chest -- reject
        LOGGER.warn("[BetterShulker] Player {} tried to interact with non-container item: {}",
                player.getName().getString(), containerStack.getItem());
        resyncPlayer(player);
    }

    // =========================================================================
    //  Shulker Box Operations
    // =========================================================================

    /**
     * Processes shulker box insertion/extraction on the server.
     * Reads from DataComponents.CONTAINER, validates, modifies, and writes back.
     */
    private static void handleShulkerInteraction(ServerPlayer player, int containerSlotId, ItemStack containerStack,
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
                // Precision mode: extract exactly 1 item from targetIndex
                ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, true);
                if (!extracted.isEmpty()) {
                    soundStack = extracted;
                    if (inventorySlotId >= 0 && inventorySlotId < player.containerMenu.slots.size()) {
                        // Extract to specific inventory slot (merge)
                        Slot invSlot = player.containerMenu.slots.get(inventorySlotId);
                        if (!(invSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                            LOGGER.warn("[BetterShulker] Player {} tried slot extraction on a non-player-inventory slot: {}",
                                    player.getName().getString(), inventorySlotId);
                            contents.set(targetIndex, extracted);
                            return;
                        }
                        ItemStack invStack = invSlot.getItem();
                        if (invStack.isEmpty()) {
                            invSlot.set(extracted);
                            success = true;
                        } else if (ItemStack.isSameItemSameComponents(invStack, extracted)
                                && invStack.getCount() < invStack.getMaxStackSize()) {
                            invStack.grow(1);
                            success = true;
                        } else {
                            // Can't merge, put back
                            contents.set(targetIndex, extracted);
                            return;
                        }
                    } else if (cursorStack.isEmpty()) {
                        player.containerMenu.setCarried(extracted);
                        success = true;
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                            && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                        cursorStack.grow(1);
                        success = true;
                    } else {
                        // Can't merge, put back
                        contents.set(targetIndex, extracted);
                        return;
                    }
                }
            }
            case SWEEP_INSERT -> {
                if (inventorySlotId < 0 || inventorySlotId >= player.containerMenu.slots.size()) return;
                net.minecraft.world.inventory.Slot targetSlot = player.containerMenu.slots.get(inventorySlotId);
                if (!(targetSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                    LOGGER.warn("[BetterShulker] Player {} tried SWEEP_INSERT on a non-player-inventory slot: {}",
                            player.getName().getString(), inventorySlotId);
                    return;
                }
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                int originalCount = invStack.getCount();
                ItemStack remainder = ContainerHelper.tryInsert(contents, invStack.copy(), false);
                targetSlot.set(remainder);
                if (remainder.getCount() < originalCount) {
                    success = true;
                    isInsert = true;
                    soundStack = invStack;
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
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
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
                    if (inventorySlotId < 0 || inventorySlotId >= player.containerMenu.slots.size()) return;
                    net.minecraft.world.inventory.Slot invSlot = player.containerMenu.slots.get(inventorySlotId);
                    if (!(invSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                        LOGGER.warn("[BetterShulker] Player {} tried slot sweep extraction on a non-player-inventory slot: {}",
                                player.getName().getString(), inventorySlotId);
                        return;
                    }
                    ItemStack invStack = invSlot.getItem();
                    if (invStack.isEmpty()) {
                        ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                        invSlot.set(extracted);
                        success = true;
                    } else if (ItemStack.isSameItemSameComponents(invStack, shulkerStack)) {
                        int canFit = invStack.getMaxStackSize() - invStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            invStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                            success = true;
                        }
                    }
                }
            }
            case RESTOCK -> {
                success = ContainerHelper.restockContents(contents, player.containerMenu.slots);
            }
            case DEPOSIT -> {
                success = ContainerHelper.depositContents(contents, player.containerMenu.slots, containerSlotId);
                if (success) {
                    isInsert = true;
                }
            }
        }

        // Write the modified contents back to the container's data component
        ContainerHelper.setContainerContents(containerStack, contents);

        // Explicitly set the updated stack back into the slot/cursor to trigger setChanged() and full updates
        if (containerSlotId == -1) {
            player.containerMenu.setCarried(containerStack);
        } else if (containerSlotId >= 0 && containerSlotId < player.containerMenu.slots.size()) {
            player.containerMenu.slots.get(containerSlotId).set(containerStack);
        }

        // Force a complete state sync including cursor
        player.containerMenu.broadcastFullState();

        if (success) {
            ContainerHelper.playInteractionSound(player, soundStack, isInsert, 0.3F);
        }
    }

    // =========================================================================
    //  Ender Chest Operations
    // =========================================================================

    /**
     * Processes ender chest insertion/extraction on the server.
     * Reads from the player's EnderChestInventory directly (not from item components).
     */
    private static void handleEnderChestInteraction(ServerPlayer player, int targetIndex,
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

                // Second pass: put into empty slots using smart-merge
                if (!cursorStack.isEmpty()) {
                    while (cursorStack.getCount() > 0) {
                        NonNullList<ItemStack> enderList = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
                        for (int k = 0; k < enderInv.getContainerSize(); k++) {
                            enderList.set(k, enderInv.getItem(k));
                        }
                        int bestSlot = com.bettershulker.util.ContainerHelper.findSmartMergeEmptySlot(enderList, cursorStack);
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
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, singleItem)) {
                        if (existing.getCount() < existing.getMaxStackSize()) {
                            existing.grow(1);
                            inserted = true;
                            break;
                        }
                    }
                }

                // Second pass: put into empty slots using smart-merge
                if (!inserted) {
                    NonNullList<ItemStack> enderList = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
                    for (int k = 0; k < enderInv.getContainerSize(); k++) {
                        enderList.set(k, enderInv.getItem(k));
                    }
                    int bestSlot = com.bettershulker.util.ContainerHelper.findSmartMergeEmptySlot(enderList, singleItem);
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
                soundStack = extracted;
                if (cursorStack.isEmpty()) {
                    player.containerMenu.setCarried(extracted);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        enderInv.setItem(targetIndex, ItemStack.EMPTY);
                    }
                    success = true;
                } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                        && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                    cursorStack.grow(1);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        enderInv.setItem(targetIndex, ItemStack.EMPTY);
                    }
                    success = true;
                }
            }
            case SWEEP_INSERT -> {
                if (inventorySlotId < 0 || inventorySlotId >= player.containerMenu.slots.size()) return;
                net.minecraft.world.inventory.Slot targetSlot = player.containerMenu.slots.get(inventorySlotId);
                if (!(targetSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                    LOGGER.warn("[BetterShulker] Player {} tried SWEEP_INSERT on a non-player-inventory slot: {}",
                            player.getName().getString(), inventorySlotId);
                    return;
                }
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                int originalCount = invStack.getCount();

                // Auto-insert invStack into the ender chest inventory
                // First pass: merge with existing compatible stacks
                for (int i = 0; i < enderInv.getContainerSize(); i++) {
                    ItemStack existing = enderInv.getItem(i);
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

                // Second pass: put into empty slots using smart-merge
                if (!invStack.isEmpty()) {
                    while (invStack.getCount() > 0) {
                        NonNullList<ItemStack> enderList = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
                        for (int k = 0; k < enderInv.getContainerSize(); k++) {
                            enderList.set(k, enderInv.getItem(k));
                        }
                        int bestSlot = com.bettershulker.util.ContainerHelper.findSmartMergeEmptySlot(enderList, invStack);
                        if (bestSlot == -1) break;

                        int toInsert = Math.min(invStack.getMaxStackSize(), invStack.getCount());
                        enderInv.setItem(bestSlot, invStack.copyWithCount(toInsert));
                        invStack.shrink(toInsert);
                    }
                }

                // Update the source inventory slot containing remainder
                player.containerMenu.slots.get(inventorySlotId).set(invStack);
                if (invStack.getCount() < originalCount) {
                    success = true;
                    isInsert = true;
                    soundStack = invStack;
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
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
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
                    if (inventorySlotId < 0 || inventorySlotId >= player.containerMenu.slots.size()) return;
                    net.minecraft.world.inventory.Slot invSlot = player.containerMenu.slots.get(inventorySlotId);
                    if (!(invSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                        LOGGER.warn("[BetterShulker] Player {} tried ender chest slot sweep extraction on a non-player-inventory slot: {}",
                                player.getName().getString(), inventorySlotId);
                        return;
                    }
                    ItemStack invStack = invSlot.getItem();
                    if (invStack.isEmpty()) {
                        enderInv.setItem(targetIndex, ItemStack.EMPTY);
                        invSlot.set(shulkerStack.copy());
                        success = true;
                    } else if (ItemStack.isSameItemSameComponents(invStack, shulkerStack)) {
                        int canFit = invStack.getMaxStackSize() - invStack.getCount();
                        int toAdd = Math.min(canFit, shulkerStack.getCount());
                        if (toAdd > 0) {
                            invStack.grow(toAdd);
                            shulkerStack.shrink(toAdd);
                            if (shulkerStack.isEmpty()) {
                                enderInv.setItem(targetIndex, ItemStack.EMPTY);
                            }
                            success = true;
                        }
                    }
                }
            }
            case RESTOCK -> {
                NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
                for (int i = 0; i < enderInv.getContainerSize(); i++) contents.set(i, enderInv.getItem(i));
                success = ContainerHelper.restockContents(contents, player.containerMenu.slots);
                if (success) {
                    for (int i = 0; i < enderInv.getContainerSize(); i++) enderInv.setItem(i, contents.get(i));
                }
            }
            case DEPOSIT -> {
                NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
                for (int i = 0; i < enderInv.getContainerSize(); i++) contents.set(i, enderInv.getItem(i));
                success = ContainerHelper.depositContents(contents, player.containerMenu.slots, -2);
                if (success) {
                    for (int i = 0; i < enderInv.getContainerSize(); i++) enderInv.setItem(i, contents.get(i));
                    isInsert = true;
                }
            }
        }

        // Broadcast changes and re-sync the ender chest contents to the client
        player.containerMenu.broadcastFullState();
        PlatformNetworking.sendToPlayer(player, buildEnderChestSyncPayload(player));

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

            // Compare stacks
            if (isFullSync || !ItemStack.isSameItemSameComponents(currentStack, lastStack) || currentStack.getCount() != lastStack.getCount()) {
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

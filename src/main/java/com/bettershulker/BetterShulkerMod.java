package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.server.EnderChestSync;
import com.bettershulker.server.EnderChestService;
import com.bettershulker.server.InteractionRateLimiter;
import com.bettershulker.server.ShulkerInteractionHandler;
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
    /**
     * Maximum allowed container interactions per single game tick (exploit protection).
     * Multi-select extraction can legitimately send up to one packet per shulker slot,
     * so this must be high enough for a full 27-slot batch while still bounding spam.
     */
    // =========================================================================
    //  Shared Cache / Validation Utilities

    public static void clearPlayerCaches(UUID uuid) {
        EnderChestSync.clearPlayer(uuid);
        InteractionRateLimiter.clearPlayer(uuid);
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
            InteractionRateLimiter.warnRejectedInteraction(player, "sent invalid action ID: " + payload.action());
            return;
        }

        // -- Validate targetIndex only for actions that use it
        boolean needsTargetIndex = (action != ContainerInteractPayload.InteractType.SWEEP_INSERT
                && action != ContainerInteractPayload.InteractType.INSERT
                && action != ContainerInteractPayload.InteractType.INSERT_ONE
                && action != ContainerInteractPayload.InteractType.RESTOCK
                && action != ContainerInteractPayload.InteractType.DEPOSIT);
        if (needsTargetIndex && (targetIndex < 0 || targetIndex >= 27)) {
            InteractionRateLimiter.warnRejectedInteraction(player, "sent invalid target index: " + targetIndex + " for action " + action);
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
                InteractionRateLimiter.warnRejectedInteraction(player, "sent invalid container slot ID: " + containerSlotId);
                return;
            }
            containerSlot = MenuSlotRef.resolve(containerSlotId, menu, player);
            if (containerSlot == null) {
                InteractionRateLimiter.warnRejectedInteraction(player, "sent unresolvable container slot ID: " + containerSlotId);
                return;
            }
            if (!ServerSlots.isUsableSlot(containerSlot) || !containerSlot.allowModification(player)) {
                InteractionRateLimiter.warnRejectedInteraction(player, "referenced non-modifiable container slot: " + containerSlotId);
                ServerSlots.resyncPlayer(player);
                return;
            }
        }

        // Copy the stack so all component changes remain transactional until commit.
        ItemStack containerStack = containerSlot == null ? menu.getCarried().copy() : containerSlot.getItem().copy();

        if (containerStack.isEmpty()) {
            InteractionRateLimiter.warnRejectedInteraction(player, "referenced empty container");
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
            ShulkerInteractionHandler.handleShulkerInteraction(player, containerSlot, containerStack, targetIndex, action, inventorySlotId);
            // Always correct rejected client prediction, including case-local early returns.
            player.containerMenu.broadcastFullState();
            return;
        }

        // Item is neither a shulker nor ender chest -- reject
        InteractionRateLimiter.warnRejectedInteraction(player, "tried to interact with non-container item: " + containerStack.getItem());
        ServerSlots.resyncPlayer(player);
    }

    // =========================================================================
    //  Shulker Box Operations
    // =========================================================================










    //  Ender Chest Operations
    // =========================================================================



    // =========================================================================
    //  Synchronization & Resync Utilities
    // =========================================================================


}

package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.platform.PlatformNetworking;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeping each player's client copy of their Ender Chest current.
 *
 * <p>Sends are diffed against the last state that player received, so an open preview costs only
 * the slots that actually changed rather than 27 stacks per frame. The record of what they were
 * last sent is therefore load-bearing: clearing it forces the next sync to be a full one, which
 * is what a disconnect or a cache invalidation wants.</p>
 *
 * <p>Requests are throttled to match the client's own cooldown, since the tooltip asks on every
 * frame it is drawn.</p>
 */
public final class EnderChestSync {

    /** Last state synced to each player, so a sync can send only what changed. */
    private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = new HashMap<>();

    /** Last server tick on which each player requested a full Ender Chest tooltip sync. */
    private static final Map<UUID, Long> lastEnderChestSyncRequestTick = new HashMap<>();

    /** Matches the normal client's 500 ms request cooldown at 20 ticks per second. */
    private static final int ENDER_CHEST_SYNC_COOLDOWN_TICKS = 10;

    private EnderChestSync() {}

    /** Forgets a player's cached state, when they disconnect. */
    public static void clearPlayer(UUID uuid) {
        lastSyncedEnderChest.remove(uuid);
        lastEnderChestSyncRequestTick.remove(uuid);
    }

    public static void resetEnderChestSync(UUID uuid) {
        lastSyncedEnderChest.remove(uuid);
    }

    public static void handleEnderChestSyncRequest(ServerPlayer player, int sourceSlotId) {
        UUID uuid = player.getUUID();
        long currentTick = player.level().getGameTime();
        Long lastRequestTick = lastEnderChestSyncRequestTick.get(uuid);
        if (lastRequestTick != null && currentTick - lastRequestTick < ENDER_CHEST_SYNC_COOLDOWN_TICKS) {
            return;
        }
        lastEnderChestSyncRequestTick.put(uuid, currentTick);

        if (!EnderChestAccess.hasAccessibleEnderChestSource(player, sourceSlotId)) {
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

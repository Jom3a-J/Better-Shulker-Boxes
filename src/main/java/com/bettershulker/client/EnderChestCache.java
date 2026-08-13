package com.bettershulker.client;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * The client's copy of the player's Ender Chest.
 *
 * <p>Those contents live on the player rather than in the item, so the client cannot read them
 * and holds only what the server has sent. Null means nothing has arrived yet, which is not the
 * same as empty - callers that would act on the contents have to treat the two differently, or
 * they will decide a chest is full of nothing.</p>
 *
 * <p>Requests are throttled, since the tooltip asks on every frame it is drawn.</p>
 */
public final class EnderChestCache {

    /** Contents received from the server, or null before the first sync arrives. */
    private static NonNullList<ItemStack> enderChestContents = null;

    private static long lastEnderChestRequestTime = 0;
    private static final long ENDER_CHEST_REQUEST_COOLDOWN_MS = 500;
    private static int enderChestTooltipSourceSlot = EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE;

    private EnderChestCache() {}

    /** Clears the cache and its throttle, when the player leaves a world. */
    public static void reset() {
        enderChestContents = null;
        lastEnderChestRequestTime = 0;
        enderChestTooltipSourceSlot = EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE;
    }

    public static void applyEnderChestSync(EnderChestSyncPayload payload) {
        if (enderChestContents == null) {
            enderChestContents = NonNullList.withSize(27, ItemStack.EMPTY);
        }
        for (EnderChestSyncPayload.EnderChestDiff diff : payload.diffs()) {
            int idx = diff.slotIndex();
            if (idx >= 0 && idx < 27) {
                enderChestContents.set(idx, diff.stack().copy());
            }
        }
        BetterShulkerMod.LOGGER.debug(
                "[BetterShulker] Client received ender chest sync diff ({} updates)",
                payload.diffs().size()
        );
    }

    public static NonNullList<ItemStack> getEnderChestContents() {
        return enderChestContents;
    }

    /** Sends C2S ender chest sync payload request to server. */
    public static void requestEnderChestSync() {
        requestEnderChestSync(enderChestTooltipSourceSlot);
    }

    /** Sends a C2S Ender Chest sync request tied to the current tooltip source. */
    public static void requestEnderChestSync(int sourceSlotId) {
        long now = System.currentTimeMillis();
        if (now - lastEnderChestRequestTime >= ENDER_CHEST_REQUEST_COOLDOWN_MS) {
            lastEnderChestRequestTime = now;
            PlatformNetworking.sendToServer(new EnderChestRequestPayload(sourceSlotId));
            BetterShulkerMod.LOGGER.debug("[BetterShulker] Sent ender chest sync request to server");
        }
    }

    public static void setEnderChestTooltipSourceSlot(int sourceSlotId) {
        enderChestTooltipSourceSlot = sourceSlotId;
    }

    public static void clearEnderChestCache() {
        enderChestContents = null;
    }
}

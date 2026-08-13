package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.ContainerInteractPayload;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Caps how many container interactions one player may have processed in a single tick.
 *
 * <p>The cap has to clear a legitimate burst - multi-select extraction sends one packet per
 * marked slot, so a full box is 27 at once - while still bounding a client that sends without
 * limit. Dropping a packet desynchronises whatever the client predicted for it, so a drop also
 * schedules a corrective resync, itself rate-limited so a flood cannot turn into a flood of
 * resyncs.</p>
 */
public final class InteractionRateLimiter {

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

    private InteractionRateLimiter() {}

    /** Forgets a player's counters, when they disconnect. */
    public static void clearPlayer(UUID uuid) {
        lastInteractionTick.remove(uuid);
        interactionCountsThisTick.remove(uuid);
        lastInteractionWarningTick.remove(uuid);
        lastInteractionDropResyncTick.remove(uuid);
    }

    public static boolean consume(ServerPlayer player) {
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
            BetterShulkerMod.LOGGER.warn("[BetterShulker] Player {} {}", player.getName().getString(), detail);
        }
    }

    public static void handleRateLimitedContainerInteraction(ServerPlayer player, ContainerInteractPayload payload) {
        if (!consume(player)) {
            warnRejectedInteraction(player, "exceeded interaction rate limit; dropping packets");
            resyncDroppedInteraction(player);
            return;
        }
        BetterShulkerMod.handleContainerInteraction(player, payload);
    }

    /**
     * Corrects the client after the rate limiter discards a packet.
     *
     * <p>The client applies its prediction before sending and never rolls it back on its own, so
     * a silent drop would leave items on screen that the server never moved. A single correction
     * per tick covers every packet that tick discarded while stopping a packet flood from being
     * amplified into an equally large flood of resyncs.</p>
     */
    public static void resyncDroppedInteraction(ServerPlayer player) {
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
        EnderChestSync.sendAuthoritativeEnderChestSync(player);
    }
}

package com.bettershulker.gametest;

import com.bettershulker.client.EnderChestCache;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.server.EnderChestSync;
import com.bettershulker.server.InteractionRateLimiter;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.clearEnderChest;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.setEnderSlot;

/**
 * The mod's per-tick limits must keep working while {@code /tick freeze} is on.
 *
 * <p>A frozen world stops its game time but the server keeps ticking. Limits counted in game time
 * therefore never reset: the rate limiter would allow 32 interactions for the whole freeze, and an
 * Ender Chest preview would never sync again after the first request.</p>
 */
public class TickFreezeGameTest implements FabricClientGameTest {

    private static final int ENDER_ITEM_SLOT = 0;
    /** More than the rate limiter's 32 per tick, spread over enough ticks to stay under it. */
    private static final int INTERACTIONS = 50;
    private static final int INTERACTIONS_PER_TICK = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            singleplayer.getServer().runCommand("tick freeze");
            context.waitTicks(2);
            boolean frozen = singleplayer.getServer().computeOnServer(server -> server.tickRateManager().isFrozen());
            assertTrue(frozen, "the world should be frozen for these cases to mean anything");

            try {
                rateLimitResetsEachTickWhileFrozen(context, singleplayer);
                enderChestSyncsAgainWhileFrozen(context, singleplayer);
            } finally {
                singleplayer.getServer().runCommand("tick unfreeze");
            }
        }
    }

    /** Ten interactions a tick for five ticks never reaches the per-tick cap, so none is refused. */
    private void rateLimitResetsEachTickWhileFrozen(ClientGameTestContext context, TestSingleplayerContext sp) {
        int allowed = 0;
        for (int tick = 0; tick < INTERACTIONS / INTERACTIONS_PER_TICK; tick++) {
            allowed += sp.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                int granted = 0;
                for (int i = 0; i < INTERACTIONS_PER_TICK; i++) {
                    if (InteractionRateLimiter.consume(player)) granted++;
                }
                return granted;
            });
            context.waitTicks(1);
        }
        assertTrue(allowed == INTERACTIONS,
                "every interaction stayed under the per-tick cap, but only " + allowed + " of " + INTERACTIONS
                        + " were allowed");
    }

    /** A second request, well past the cooldown, must bring the preview up to date. */
    private void enderChestSyncsAgainWhileFrozen(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, 0, new ItemStack(Items.ENDER_CHEST));
        clearEnderChest(sp);
        setEnderSlot(sp, ENDER_ITEM_SLOT, new ItemStack(Items.STONE));
        requestSync(sp);
        context.waitTicks(3);
        String first = cachedItem(context);
        assertTrue(first.contains("stone"), "the first sync should show the stone, cache held " + first);

        setEnderSlot(sp, ENDER_ITEM_SLOT, new ItemStack(Items.DIAMOND));
        // Twice the 10-tick cooldown, so only a cooldown that never expires can refuse this.
        context.waitTicks(20);
        requestSync(sp);
        context.waitTicks(3);
        String second = cachedItem(context);
        assertTrue(second.contains("diamond"),
                "the second sync should show the diamond, but the preview still held " + second);
    }

    private static void requestSync(TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> EnderChestSync.handleEnderChestSyncRequest(
                server.getPlayerList().getPlayers().getFirst(), EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE));
    }

    private static String cachedItem(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var contents = EnderChestCache.getEnderChestContents();
            return contents == null ? "nothing" : contents.get(ENDER_ITEM_SLOT).getItem().toString();
        });
    }
}

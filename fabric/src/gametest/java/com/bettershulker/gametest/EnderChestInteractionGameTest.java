package com.bettershulker.gametest;

import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.EnderChestCache;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.clearCursor;
import static com.bettershulker.gametest.GameTestSupport.clearEnderChest;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.countInEnderChest;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * The same interactions against an Ender Chest, which is a different problem from a Shulker Box.
 *
 * <p>A box carries its contents in a data component, so the client can read them off the item it
 * is already holding. An Ender Chest's contents live on the player: the client has nothing until
 * the server sends a copy, and every change has to travel back. That makes these the tests that
 * matter most - they are the only ones that exercise the request, the sync, and the eight actions
 * that were rewritten out of a single switch.</p>
 */
public class EnderChestInteractionGameTest implements FabricClientGameTest {

    private static final int CHEST_SLOT = 0;
    private static final int ITEM_SLOT = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            singleplayer.getServer().runCommand("gamemode creative");

            contentsReachTheClientBeforeTheTooltipOpens(context, singleplayer);
            rightClickInsertsIntoTheEnderChest(context, singleplayer);
            rightClickExtractsFromTheEnderChest(context, singleplayer);
            aServerSideChangeReachesTheClient(context, singleplayer);
        }
    }

    // =========================================================================
    //  Tests
    // =========================================================================

    /**
     * Nothing else can work until a sync arrives: the tooltip refuses to build while the cache is
     * null, which is the client honestly reporting that it has never been told the contents.
     */
    private void contentsReachTheClientBeforeTheTooltipOpens(ClientGameTestContext context,
                                                             TestSingleplayerContext sp) {
        clearEnderChest(sp);
        GameTestSupport.setEnderSlot(sp, 0, new ItemStack(Items.STONE, 12));
        givePlayer(sp, CHEST_SLOT, new ItemStack(Items.ENDER_CHEST));
        openInventory(context);
        hoverInventorySlot(context, CHEST_SLOT);
        context.waitTicks(20);

        int cached = context.computeOnClient(client -> {
            var contents = EnderChestCache.getEnderChestContents();
            if (contents == null) return -1;
            int total = 0;
            for (ItemStack stack : contents) {
                if (stack.is(Items.STONE)) total += stack.getCount();
            }
            return total;
        });
        assertTrue(cached == 12, "the client should have been sent the 12 stone, saw " + cached);

        boolean active = context.computeOnClient(client -> BetterShulkerClient.isTooltipActive());
        assertTrue(active, "hovering an Ender Chest with contents should activate the tooltip");

        closeScreen(context);
    }

    /** Right-clicking the chest while carrying a stack puts that stack in the player's chest. */
    private void rightClickInsertsIntoTheEnderChest(ClientGameTestContext context,
                                                   TestSingleplayerContext sp) {
        clearEnderChest(sp);
        clearCursor(sp);
        givePlayer(sp, CHEST_SLOT, new ItemStack(Items.ENDER_CHEST));
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.GOLD_INGOT, 32));
        openInventory(context);
        hoverInventorySlot(context, CHEST_SLOT);
        context.waitTicks(15);

        clickInventorySlot(context, ITEM_SLOT, 0);
        clickInventorySlot(context, CHEST_SLOT, 1);
        context.waitTicks(10);

        int stored = countInEnderChest(sp, Items.GOLD_INGOT);
        assertTrue(stored == 32, "the Ender Chest should hold 32 gold, held " + stored);

        closeScreen(context);
        clearCursor(sp);
    }

    /** And right-clicking with an empty cursor takes a stack back out of it. */
    private void rightClickExtractsFromTheEnderChest(ClientGameTestContext context,
                                                    TestSingleplayerContext sp) {
        clearEnderChest(sp);
        clearCursor(sp);
        GameTestSupport.setEnderSlot(sp, 0, new ItemStack(Items.DIAMOND, 9));
        givePlayer(sp, CHEST_SLOT, new ItemStack(Items.ENDER_CHEST));
        givePlayer(sp, ITEM_SLOT, ItemStack.EMPTY);
        openInventory(context);
        hoverInventorySlot(context, CHEST_SLOT);
        context.waitTicks(15);

        clickInventorySlot(context, CHEST_SLOT, 1);
        context.waitTicks(10);

        int left = countInEnderChest(sp, Items.DIAMOND);
        assertTrue(left == 0, "the diamonds should have left the Ender Chest, " + left + " remained");

        closeScreen(context);
        clearCursor(sp);
    }

    /**
     * Syncs are diffed against what a player was last sent, so a stale record would leave the
     * client showing contents that no longer exist.
     */
    private void aServerSideChangeReachesTheClient(ClientGameTestContext context,
                                                   TestSingleplayerContext sp) {
        clearEnderChest(sp);
        GameTestSupport.setEnderSlot(sp, 0, new ItemStack(Items.EMERALD, 4));
        givePlayer(sp, CHEST_SLOT, new ItemStack(Items.ENDER_CHEST));
        openInventory(context);
        hoverInventorySlot(context, CHEST_SLOT);
        context.waitTicks(20);

        // Change it behind the client's back, then keep hovering so a fresh request goes out.
        GameTestSupport.setEnderSlot(sp, 0, new ItemStack(Items.EMERALD, 41));
        context.waitTicks(30);

        int cached = context.computeOnClient(client -> {
            var contents = EnderChestCache.getEnderChestContents();
            if (contents == null) return -1;
            int total = 0;
            for (ItemStack stack : contents) {
                if (stack.is(Items.EMERALD)) total += stack.getCount();
            }
            return total;
        });
        assertTrue(cached == 41, "the client should have caught up to 41 emeralds, saw " + cached);

        closeScreen(context);
    }
}

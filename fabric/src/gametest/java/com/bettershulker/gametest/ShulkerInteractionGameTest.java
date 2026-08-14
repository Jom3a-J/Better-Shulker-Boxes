package com.bettershulker.gametest;

import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.ClientKeybinds;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.boxHolding;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.countInBoxAt;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * Drives a real client through the container interactions this mod adds.
 *
 * <p>These exist because the interesting code sits behind a screen's input handlers: the tooltip
 * only builds while a container slot is hovered, and an insert only happens when a click lands on
 * one. Nothing reachable from an ordinary unit test gets near them, which is why a refactor of
 * this area could previously only be checked by compiling it and hoping.</p>
 *
 * <p>Assertions are made against the server's copy of the box wherever possible. The client
 * predicts every interaction locally, so asserting on the client would pass even if the packet
 * were never sent or the server refused it.</p>
 */
public class ShulkerInteractionGameTest implements FabricClientGameTest {

    /** Player inventory position holding the box under test, and the one holding loose items. */
    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            singleplayer.getServer().runCommand("gamemode creative");

            hoveringABoxOpensTheTooltip(context, singleplayer);
            rightClickInsertsTheCarriedStack(context, singleplayer);
            rightClickExtractsBackOut(context, singleplayer);
            selectionSkipsEmptySlots(context, singleplayer);
            marksDoNotFollowToAnotherBox(context, singleplayer);
        }
    }

    // =========================================================================
    //  Tests
    // =========================================================================

    /**
     * The tooltip is what every other interaction is aimed at, so it is checked first: without it
     * the rest of the failures would all be secondary.
     */
    private void hoveringABoxOpensTheTooltip(ClientGameTestContext context, TestSingleplayerContext sp) {
        // Deliberately not an empty box: an empty one suppresses the preview, so hovering it would
        // prove nothing about whether the tooltip works.
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.STONE, 1), 0));
        openInventory(context);
        hoverInventorySlot(context, BOX_SLOT);

        boolean active = context.computeOnClient(client -> BetterShulkerClient.isTooltipActive());
        assertTrue(active, "hovering a Shulker Box should activate the preview tooltip");

        closeScreen(context);
    }

    /** Right-clicking a box while carrying a stack puts that stack inside it. */
    private void rightClickInsertsTheCarriedStack(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, new ItemStack(Items.SHULKER_BOX));
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.STONE, 64));
        openInventory(context);

        // Pick the stone up, then drop it into the box.
        clickInventorySlot(context, ITEM_SLOT, 0);
        clickInventorySlot(context, BOX_SLOT, 1);
        context.waitTicks(5);

        int stored = countInBoxAt(sp, BOX_SLOT, Items.STONE);
        assertTrue(stored == 64, "the box should hold 64 stone after the insert, held " + stored);

        closeScreen(context);
    }

    /** And right-clicking it with an empty cursor takes the stack back out. */
    private void rightClickExtractsBackOut(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 5), 0));
        givePlayer(sp, ITEM_SLOT, ItemStack.EMPTY);
        openInventory(context);

        hoverInventorySlot(context, BOX_SLOT);
        clickInventorySlot(context, BOX_SLOT, 1);
        context.waitTicks(5);

        int left = countInBoxAt(sp, BOX_SLOT, Items.DIAMOND);
        assertTrue(left == 0, "the diamonds should have left the box, " + left + " remained");

        closeScreen(context);
    }

    /**
     * The selection square only ever names an extraction target, so it steps past empty slots.
     * With one item in slot 13, any number of steps has to land back on 13.
     */
    private void selectionSkipsEmptySlots(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.EMERALD, 1), 13));
        openInventory(context);
        hoverInventorySlot(context, BOX_SLOT);

        context.getInput().scroll(-1.0);
        context.waitTicks(2);
        context.getInput().scroll(-1.0);
        context.waitTicks(2);

        int selected = context.computeOnClient(client -> BetterShulkerClient.getSelectedSlotIndex());
        assertTrue(selected == 13,
                "the square should rest on the only occupied slot, sat on " + selected);

        closeScreen(context);
    }

    /**
     * Marks belong to the box they were made in. Carrying them to another one used to extract
     * whatever happened to sit at those indices there.
     */
    private void marksDoNotFollowToAnotherBox(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 3), 0));
        givePlayer(sp, ITEM_SLOT, boxHolding(new ItemStack(Items.GOLD_INGOT, 7), 0));
        openInventory(context);

        // Mark slot 0 of the first box, then move to the second and ask for an extraction.
        hoverInventorySlot(context, BOX_SLOT);
        context.getInput().pressKey(ClientKeybinds.getSelectSlotKey());
        context.waitTicks(2);
        hoverInventorySlot(context, ITEM_SLOT);
        context.waitTicks(2);
        context.getInput().pressKey(ClientKeybinds.getExtractKey());
        context.waitTicks(5);

        int goldLeft = countInBoxAt(sp, ITEM_SLOT, Items.GOLD_INGOT);
        assertTrue(goldLeft == 7,
                "the second box should be untouched by the first box's marks, held " + goldLeft);

        closeScreen(context);
    }
}

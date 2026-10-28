package com.bettershulker.gametest;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.clearCursor;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.countInBoxAt;
import static com.bettershulker.gametest.GameTestSupport.countInCarriedBox;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * An item that refuses to go inside container items must stay out of Shulker Boxes.
 *
 * <p>Vanilla asks {@code canFitInsideContainerItems()}, which mods override for backpacks and other
 * containers. Letting one into a box nests containers without limit, and the item data can grow
 * until a chunk holding it no longer loads.</p>
 */
public class ContainerRefusalGameTest implements FabricClientGameTest {

    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // The server console is not a player, so the command needs an explicit target.
            singleplayer.getServer().runCommand("gamemode survival @a");
            context.waitTicks(2);

            rightClickingABoxWithTheItemLeavesItOut(context, singleplayer);
            rightClickingTheItemWithABoxLeavesItOut(context, singleplayer);
        }
    }

    /** The item on the cursor, right-clicked onto an empty box in a slot. */
    private void rightClickingABoxWithTheItemLeavesItOut(ClientGameTestContext context,
                                                         TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, new ItemStack(Items.SHULKER_BOX));
        givePlayer(sp, ITEM_SLOT, new ItemStack(TestItems.notForContainers));
        openInventory(context);

        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int inside = countInBoxAt(sp, BOX_SLOT, TestItems.notForContainers)
                + countInCarriedBox(sp, TestItems.notForContainers);
        assertTrue(inside == 0, "the item refuses containers, but the box took " + inside);

        clearCursor(sp);
        closeScreen(context);
    }

    /** An empty box on the cursor, right-clicked onto the item in a slot. */
    private void rightClickingTheItemWithABoxLeavesItOut(ClientGameTestContext context,
                                                         TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, new ItemStack(Items.SHULKER_BOX));
        givePlayer(sp, ITEM_SLOT, new ItemStack(TestItems.notForContainers));
        openInventory(context);

        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int inside = countInCarriedBox(sp, TestItems.notForContainers)
                + countInBoxAt(sp, ITEM_SLOT, TestItems.notForContainers);
        assertTrue(inside == 0, "the item refuses containers, but the box took " + inside);

        clearCursor(sp);
        closeScreen(context);
    }
}

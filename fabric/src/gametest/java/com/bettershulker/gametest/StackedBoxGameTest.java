package com.bettershulker.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.clearCursor;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * A stack of several Shulker Boxes must never be filled as if it were one.
 *
 * <p>Boxes stack when a datapack, a command or a mod raises their {@code max_stack_size}. The
 * contents live in the stack's component, so writing an item into a stack of four writes it into
 * all four boxes at once. Each case counts every diamond the server can see, inside boxes and out,
 * and requires that count to stay at the one it started with.</p>
 *
 * <p>Runs in survival, unlike the other interaction tests: creative never sends the interaction
 * packet, so it could not reach the server path this guards.</p>
 */
public class StackedBoxGameTest implements FabricClientGameTest {

    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;
    private static final int BOXES_IN_STACK = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            singleplayer.getServer().runCommand("gamemode survival");

            rightClickingAStackOfBoxesWithAnItemDoesNotDuplicateIt(context, singleplayer);
            rightClickingWithAStackOfBoxesDoesNotDuplicateTheItem(context, singleplayer);
        }
    }

    /** A carried diamond right-clicked onto four stacked boxes sitting in a slot. */
    private void rightClickingAStackOfBoxesWithAnItemDoesNotDuplicateIt(ClientGameTestContext context,
                                                                        TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, stackedBoxes());
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.DIAMOND, 1));
        openInventory(context);

        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int diamonds = countEverywhere(sp, Items.DIAMOND);
        assertTrue(diamonds == 1, "one diamond went in, but the server now holds " + diamonds);

        clearCursor(sp);
        closeScreen(context);
    }

    /** Four stacked boxes on the cursor, right-clicked onto a slot holding a diamond. */
    private void rightClickingWithAStackOfBoxesDoesNotDuplicateTheItem(ClientGameTestContext context,
                                                                       TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, stackedBoxes());
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.DIAMOND, 1));
        openInventory(context);

        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int diamonds = countEverywhere(sp, Items.DIAMOND);
        assertTrue(diamonds == 1, "one diamond went in, but the server now holds " + diamonds);

        clearCursor(sp);
        closeScreen(context);
    }

    /** Empty Shulker Boxes allowed to stack, as a datapack or mod would make them. */
    private static ItemStack stackedBoxes() {
        ItemStack boxes = new ItemStack(Items.SHULKER_BOX);
        boxes.set(DataComponents.MAX_STACK_SIZE, 64);
        boxes.setCount(BOXES_IN_STACK);
        return boxes;
    }

    /**
     * Every {@code item} the server can see on the player: loose, on the cursor, and inside boxes,
     * each box in a stack counted separately because each one really holds its own copy.
     */
    private static int countEverywhere(TestSingleplayerContext sp, Item item) {
        return sp.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            int total = count(player.containerMenu.getCarried(), item);
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                total += count(inventory.getItem(i), item);
            }
            return total;
        });
    }

    private static int count(ItemStack stack, Item item) {
        if (stack.is(item)) return stack.getCount();
        if (!ContainerHelper.isShulkerBox(stack)) return 0;
        int inside = 0;
        for (ItemStack stored : ContainerHelper.getContainerContents(stack)) {
            if (stored.is(item)) inside += stored.getCount();
        }
        return inside * stack.getCount();
    }
}

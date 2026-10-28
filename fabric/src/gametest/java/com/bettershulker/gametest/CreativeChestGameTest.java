package com.bettershulker.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.boxHolding;
import static com.bettershulker.gametest.GameTestSupport.clearCursor;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.hoverMenuSlot;
import static com.bettershulker.gametest.GameTestSupport.openChest;

/**
 * A creative player using an ordinary container screen, such as a chest.
 *
 * <p>Only the creative inventory keeps its cursor on the client. Every other menu a creative
 * player opens is server-backed, exactly as in survival, so it has to take the validated path.
 * Treating it as the creative inventory wrote the box with a creative packet while the extracted
 * stack sat on a cursor the server never saw, deleting it; and a box inside the chest, which has
 * no inventory position to write, simply ignored the click.</p>
 */
public class CreativeChestGameTest implements FabricClientGameTest {

    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;
    /** The chest's own first slot, which comes first in the menu. */
    private static final int CHEST_FIRST_SLOT = 0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // The server console is not a player, so the command needs an explicit target.
            singleplayer.getServer().runCommand("gamemode creative @a");
            context.waitTicks(2);
            boolean creative = context.computeOnClient(client -> client.player.hasInfiniteMaterials());
            assertTrue(creative, "the player should be in creative for these cases to mean anything");

            extractingFromABoxKeepsTheItems(context, singleplayer);
            insertingIntoABoxInsideTheChestWorks(context, singleplayer);
        }
    }

    /** Right-clicking a box in the inventory part of the chest screen takes its stack out. */
    private void extractingFromABoxKeepsTheItems(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 5), 0));
        givePlayer(sp, ITEM_SLOT, ItemStack.EMPTY);
        openChest(context, sp, ItemStack.EMPTY);

        hoverInventorySlot(context, BOX_SLOT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int diamonds = countEverywhere(sp, Items.DIAMOND);
        assertTrue(diamonds == 5, "5 diamonds were in the box, but the server now holds " + diamonds);
        int inBox = GameTestSupport.countInBoxAt(sp, BOX_SLOT, Items.DIAMOND);
        assertTrue(inBox == 0, "the diamonds should have left the box, " + inBox + " remained");

        clearCursor(sp);
        closeScreen(context);
    }

    /** Right-clicking a box that sits in the chest itself, with a stack on the cursor. */
    private void insertingIntoABoxInsideTheChestWorks(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, ItemStack.EMPTY);
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.DIAMOND, 1));
        openChest(context, sp, new ItemStack(Items.SHULKER_BOX));

        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        hoverMenuSlot(context, CHEST_FIRST_SLOT);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int inBox = sp.getServer().computeOnServer(server -> {
            var menu = server.getPlayerList().getPlayers().getFirst().containerMenu;
            if (!(menu instanceof ChestMenu chest)) return -1;
            return count(chest.getContainer().getItem(CHEST_FIRST_SLOT), Items.DIAMOND);
        });
        assertTrue(inBox == 1, "the diamond should be in the box inside the chest, it held " + inBox);

        clearCursor(sp);
        closeScreen(context);
    }

    /** Every {@code item} the server can see: the cursor, the inventory, the open chest, and boxes. */
    private static int countEverywhere(TestSingleplayerContext sp, Item item) {
        return sp.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            int total = count(player.containerMenu.getCarried(), item);
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                total += count(inventory.getItem(i), item);
            }
            if (player.containerMenu instanceof ChestMenu chest) {
                var container = chest.getContainer();
                for (int i = 0; i < container.getContainerSize(); i++) {
                    total += count(container.getItem(i), item);
                }
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

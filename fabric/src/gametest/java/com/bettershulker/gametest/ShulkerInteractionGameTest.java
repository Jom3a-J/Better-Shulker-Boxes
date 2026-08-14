package com.bettershulker.gametest;

import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.ClientKeybinds;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    /** Vanilla inventory screen dimensions, used to place the cursor over a known slot. */
    private static final int INVENTORY_IMAGE_WIDTH = 176;
    private static final int INVENTORY_IMAGE_HEIGHT = 166;

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

        int stored = countInBox(sp, Items.STONE);
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

        int left = countInBox(sp, Items.DIAMOND);
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

    // =========================================================================
    //  Harness
    // =========================================================================

    /** A Shulker Box holding {@code stack} at {@code index}. */
    private static ItemStack boxHolding(ItemStack stack, int index) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(index, stack);
        ContainerHelper.setContainerContents(box, contents);
        return box;
    }

    private static void givePlayer(TestSingleplayerContext sp, int position, ItemStack stack) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(position, stack.copy());
            player.containerMenu.broadcastChanges();
        });
    }

    private static int countInBox(TestSingleplayerContext sp, net.minecraft.world.item.Item item) {
        return countInBoxAt(sp, BOX_SLOT, item);
    }

    /** Counts an item inside the box at a player-inventory position, read from the server. */
    private static int countInBoxAt(TestSingleplayerContext sp, int position,
                                    net.minecraft.world.item.Item item) {
        return sp.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            ItemStack box = player.getInventory().getItem(position);
            int total = 0;
            for (ItemStack stack : ContainerHelper.getContainerContents(box)) {
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        });
    }

    private static void openInventory(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.waitTicks(2);
    }

    private static void closeScreen(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.gui.screen() != null) client.gui.screen().onClose();
        });
        context.waitTicks(2);
    }

    /**
     * Puts the mouse over a player-inventory position, in window pixels.
     *
     * <p>{@code setCursorPos} talks to the window rather than the scaled GUI, so the slot's screen
     * position is multiplied by the GUI scale. The panel origin is derived from the vanilla
     * inventory's fixed size, the screen's own being protected.</p>
     */
    private static void hoverInventorySlot(ClientGameTestContext context, int position) {
        double[] pos = context.computeOnClient(client -> {
            Slot slot = findSlot(client, position);
            double scale = client.getWindow().getGuiScale();
            int left = (client.gui.screen().width - INVENTORY_IMAGE_WIDTH) / 2;
            int top = (client.gui.screen().height - INVENTORY_IMAGE_HEIGHT) / 2;
            return new double[] {
                    (left + slot.x + 8) * scale,
                    (top + slot.y + 8) * scale
            };
        });
        context.getInput().setCursorPos(pos[0], pos[1]);
        context.waitTicks(2);
    }

    private static void clickInventorySlot(ClientGameTestContext context, int position, int button) {
        hoverInventorySlot(context, position);
        context.getInput().pressMouse(button);
        context.waitTicks(3);
    }

    /** The menu slot exposing a given player-inventory position. */
    private static Slot findSlot(Minecraft client, int position) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.gui.screen();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == client.player.getInventory() && slot.getContainerSlot() == position) {
                return slot;
            }
        }
        throw new AssertionError("no menu slot exposes inventory position " + position);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

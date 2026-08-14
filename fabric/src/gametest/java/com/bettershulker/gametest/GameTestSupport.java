package com.bettershulker.gametest;

import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Driving the inventory screen from a client game test.
 *
 * <p>Reaching this mod's code means reproducing what a player does: open the inventory, put the
 * pointer on a particular slot, click it. Nothing here knows what is being tested - it only knows
 * how to aim at a slot and how to read the result back off the server.</p>
 */
final class GameTestSupport {

    /** Vanilla inventory screen dimensions, used to place the cursor over a known slot. */
    private static final int INVENTORY_IMAGE_WIDTH = 176;
    private static final int INVENTORY_IMAGE_HEIGHT = 166;

    private GameTestSupport() {}

    // =========================================================================
    //  Setting the scene
    // =========================================================================

    /** A Shulker Box holding {@code stack} at {@code index}. */
    static ItemStack boxHolding(ItemStack stack, int index) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(index, stack);
        ContainerHelper.setContainerContents(box, contents);
        return box;
    }

    static void givePlayer(TestSingleplayerContext sp, int position, ItemStack stack) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(position, stack.copy());
            player.containerMenu.broadcastChanges();
        });
    }

    /** Empties the cursor, so a stack left there by one test cannot reach the next. */
    static void clearCursor(TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.containerMenu.broadcastChanges();
        });
    }

    static void setEnderSlot(TestSingleplayerContext sp, int index, ItemStack stack) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getEnderChestInventory().setItem(index, stack.copy());
        });
    }

    static void clearEnderChest(TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getEnderChestInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        });
    }

    // =========================================================================
    //  Reading the result, from the server
    // =========================================================================

    /** Counts an item inside the box at a player-inventory position, read from the server. */
    static int countInBoxAt(TestSingleplayerContext sp, int position, Item item) {
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

    /** Counts an item sitting loose in a player-inventory position, read from the server. */
    static int countInInventorySlot(TestSingleplayerContext sp, int position, Item item) {
        return sp.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            ItemStack stack = player.getInventory().getItem(position);
            return stack.is(item) ? stack.getCount() : 0;
        });
    }

    /** Counts an item in the player's Ender Chest, which only the server holds. */
    static int countInEnderChest(TestSingleplayerContext sp, Item item) {
        return sp.getServer().computeOnServer(server -> {
            var inv = server.getPlayerList().getPlayers().getFirst().getEnderChestInventory();
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        });
    }

    // =========================================================================
    //  Input
    // =========================================================================

    static void openInventory(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.waitTicks(2);
    }

    static void closeScreen(ClientGameTestContext context) {
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
    static void hoverInventorySlot(ClientGameTestContext context, int position) {
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

    static void clickInventorySlot(ClientGameTestContext context, int position, int button) {
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

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

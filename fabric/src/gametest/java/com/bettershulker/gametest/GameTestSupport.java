package com.bettershulker.gametest;

import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Function;

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
    /** A chest screen's height before its rows, which add 18 each. */
    private static final int CHEST_IMAGE_BASE_HEIGHT = 114;

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

    /** Counts an item inside the box on the cursor, read from the server. */
    static int countInCarriedBox(TestSingleplayerContext sp, Item item) {
        return sp.getServer().computeOnServer(server -> {
            ItemStack box = server.getPlayerList().getPlayers().getFirst().containerMenu.getCarried();
            int total = 0;
            for (ItemStack stack : ContainerHelper.getContainerContents(box)) {
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        });
    }

    /** Counts an item sitting loose on the cursor, read from the server. */
    static int countOnCursor(TestSingleplayerContext sp, Item item) {
        return sp.getServer().computeOnServer(server -> {
            ItemStack stack = server.getPlayerList().getPlayers().getFirst().containerMenu.getCarried();
            return stack.is(item) ? stack.getCount() : 0;
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

    /**
     * Opens a server-backed three-row chest holding {@code firstSlot} in its first slot. The
     * container belongs to no block, so it is valid wherever the player stands.
     */
    static void openChest(ClientGameTestContext context, TestSingleplayerContext sp, ItemStack firstSlot) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            SimpleContainer chest = new SimpleContainer(27);
            chest.setItem(0, firstSlot.copy());
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> ChestMenu.threeRows(id, inventory, chest),
                    Component.literal("Test chest")));
        });
        context.waitForScreen(ContainerScreen.class);
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
        hoverSlot(context, client -> findSlot(client, position));
    }

    /** Puts the mouse over a slot by its index in the open menu, for slots the player does not own. */
    static void hoverMenuSlot(ClientGameTestContext context, int menuIndex) {
        hoverSlot(context, client -> ((AbstractContainerScreen<?>) client.gui.screen()).getMenu().getSlot(menuIndex));
    }

    private static void hoverSlot(ClientGameTestContext context, Function<Minecraft, Slot> slotFinder) {
        double[] pos = context.computeOnClient(client -> {
            Slot slot = slotFinder.apply(client);
            double scale = client.getWindow().getGuiScale();
            int[] origin = panelOrigin(client);
            return new double[] {
                    (origin[0] + slot.x + 8) * scale,
                    (origin[1] + slot.y + 8) * scale
            };
        });
        context.getInput().setCursorPos(pos[0], pos[1]);
        context.waitTicks(2);
    }

    /** Top-left of the open screen's panel. A chest's height depends on its row count. */
    private static int[] panelOrigin(Minecraft client) {
        var screen = client.gui.screen();
        int height = screen instanceof ContainerScreen chest
                ? CHEST_IMAGE_BASE_HEIGHT + chest.getMenu().getRowCount() * 18
                : INVENTORY_IMAGE_HEIGHT;
        return new int[] {(screen.width - INVENTORY_IMAGE_WIDTH) / 2, (screen.height - height) / 2};
    }

    static void clickInventorySlot(ClientGameTestContext context, int position, int button) {
        hoverInventorySlot(context, position);
        context.getInput().pressMouse(button);
        context.waitTicks(3);
    }

    /**
     * A click as a real hand makes it: the pointer drifts a pixel while the button is down. That
     * stays inside the slot, but the screen still sees it as a drag.
     */
    static void clickInventorySlotWithJitter(ClientGameTestContext context, int position, int button) {
        hoverInventorySlot(context, position);
        context.getInput().holdMouse(button);
        context.waitTicks(1);
        context.getInput().moveCursor(1.0, 0.0);
        context.waitTicks(1);
        context.getInput().releaseMouse(button);
        context.waitTicks(3);
    }

    /**
     * Presses on the top edge of a hotbar position and drifts up off the slot before letting go.
     * Above the hotbar is the gap to the main inventory, so the pointer ends over no slot at all;
     * that is checked here, since a release that landed on the row above would test nothing.
     */
    static void clickHotbarSlotDriftingIntoTheGap(ClientGameTestContext context, int position, int button) {
        double[] pos = context.computeOnClient(client -> {
            Slot slot = findSlot(client, position);
            double scale = client.getWindow().getGuiScale();
            int[] origin = panelOrigin(client);
            int left = origin[0];
            int top = origin[1];
            double x = slot.x + 8;
            // A slot answers to the pointer from one pixel outside its 16, so y - 0.5 is still on it.
            double pressY = slot.y - 0.5;
            double releaseY = slot.y - 3.5;
            assertTrue(slotUnder(client, x, pressY) == slot,
                    "the press point should be on the slot under test");
            assertTrue(slotUnder(client, x, releaseY) == null,
                    "the release point should be over no slot, found " + slotUnder(client, x, releaseY));
            return new double[] {(left + x) * scale, (top + pressY) * scale, (top + releaseY) * scale};
        });
        context.getInput().setCursorPos(pos[0], pos[1]);
        context.waitTicks(2);
        context.getInput().holdMouse(button);
        context.waitTicks(1);
        context.getInput().setCursorPos(pos[0], pos[2]);
        context.waitTicks(2);
        context.getInput().releaseMouse(button);
        context.waitTicks(3);
    }

    /** Presses on one position, drags straight to another, and lets go there. */
    static void dragBetweenInventorySlots(ClientGameTestContext context, int from, int to, int button) {
        hoverInventorySlot(context, from);
        context.getInput().holdMouse(button);
        context.waitTicks(1);
        hoverInventorySlot(context, to);
        context.getInput().releaseMouse(button);
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

    /** The slot a point in panel coordinates is over, by the same bounds the screen uses. */
    private static Slot slotUnder(Minecraft client, double x, double y) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.gui.screen();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive()
                    && x >= slot.x - 1 && x < slot.x + 17
                    && y >= slot.y - 1 && y < slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

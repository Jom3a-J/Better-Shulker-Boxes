package com.bettershulker.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.bettershulker.platform.PlatformNetworking;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.boxHolding;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.countInBoxAt;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;

/**
 * Shulker Boxes in the creative inventory, on its hotbar row.
 *
 * <p>That screen's cursor lives only on the client, so the mod sends nothing there: it edits the
 * box itself and commits it with vanilla's creative slot packet. Each case checks the server's
 * copy of the box and that nothing was sent. An extract onto an empty cursor would also succeed
 * through the server, so for that case the second check is the one that guards the creative
 * path.</p>
 */
public class CreativeInventoryGameTest implements FabricClientGameTest {

    /** Hotbar positions, which the creative screen shows on every item tab. */
    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // The server console is not a player, so the command needs an explicit target.
            singleplayer.getServer().runCommand("gamemode creative @a");
            context.waitTicks(2);
            boolean creative = context.computeOnClient(client -> client.player.hasInfiniteMaterials());
            assertTrue(creative, "the player should be in creative for these cases to mean anything");

            // Sends are recorded and still delivered, so behaviour is unchanged while the rule that
            // nothing is sent from this screen can be checked.
            context.runOnClient(client -> {
                sent.clear();
                PlatformNetworking.setClientSender(payload -> {
                    sent.add(payload.type().id().toString());
                    ClientPlayNetworking.send(payload);
                });
            });
            try {
                rightClickInsertsTheCarriedStack(context, singleplayer);
                rightClickExtractsOntoTheCursor(context, singleplayer);
            } finally {
                context.runOnClient(client -> PlatformNetworking.setClientSender(ClientPlayNetworking::send));
            }
        }
    }

    /** Payload types sent while the creative inventory was open, written on the client thread. */
    private static final List<String> sent = new ArrayList<>();

    /**
     * The creative path's own rule. A payload from this screen names a cursor the server cannot
     * see, and its rejection resyncs the player, which overwrites the client's cursor.
     */
    private static void assertNothingSent(ClientGameTestContext context) {
        List<String> attempted = context.computeOnClient(client -> List.copyOf(sent));
        assertTrue(attempted.isEmpty(), "the creative inventory should send nothing, but sent " + attempted);
    }

    /** Pick up a stack from the hotbar, right-click the box with it. */
    private void rightClickInsertsTheCarriedStack(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, new ItemStack(Items.SHULKER_BOX));
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.STONE, 10));
        openCreativeInventory(context);

        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int stored = countInBoxAt(sp, BOX_SLOT, Items.STONE);
        assertTrue(stored == 10, "the server's box should hold the 10 stone after the insert, held " + stored);
        assertNothingSent(context);

        closeCreativeInventory(context);
    }

    /** Right-click a box with an empty cursor: its stack goes onto the cursor. */
    private void rightClickExtractsOntoTheCursor(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 5), 0));
        givePlayer(sp, ITEM_SLOT, ItemStack.EMPTY);
        openCreativeInventory(context);

        hoverInventorySlot(context, BOX_SLOT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(5);

        int left = countInBoxAt(sp, BOX_SLOT, Items.DIAMOND);
        int onCursor = context.computeOnClient(client -> {
            ItemStack carried = ((AbstractContainerScreen<?>) client.gui.screen()).getMenu().getCarried();
            return carried.is(Items.DIAMOND) ? carried.getCount() : 0;
        });
        assertTrue(left == 0, "the server's box should be empty after the extract, held " + left);
        assertTrue(onCursor == 5, "the 5 diamonds should be on the creative cursor, found " + onCursor);
        assertNothingSent(context);

        closeCreativeInventory(context);
    }

    private static void openCreativeInventory(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(CreativeModeInventoryScreen.class);
        context.waitTicks(2);
    }

    /** Empties the client-only cursor first, so closing does not throw its stack into the world. */
    private static void closeCreativeInventory(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
                screen.getMenu().setCarried(ItemStack.EMPTY);
                screen.onClose();
            }
        });
        context.waitTicks(2);
    }
}

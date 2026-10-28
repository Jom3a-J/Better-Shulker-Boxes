package com.bettershulker.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.ClientKeybinds;
import com.bettershulker.platform.PlatformNetworking;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.boxHolding;
import static com.bettershulker.gametest.GameTestSupport.clearCursor;
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * A client on a server without the mod must not send it anything, or act as if it had.
 *
 * <p>Anything sent there is dropped, so a click the mod took over would move items on screen that
 * the server never moves, and they would jump back. A real server without the mod cannot run in
 * this test, so the client is told the server cannot receive the mod's payloads, and every send
 * is recorded. The preview reads only the box's own data, so it should keep working.</p>
 */
public class ServerWithoutModGameTest implements FabricClientGameTest {

    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;
    private static final int ENDER_SLOT = 2;

    /** Payload types the client tried to send, written on the client thread. */
    private static final List<String> sent = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // The server console is not a player, so the command needs an explicit target.
            singleplayer.getServer().runCommand("gamemode survival @a");
            context.waitTicks(2);

            context.runOnClient(client -> {
                sent.clear();
                PlatformNetworking.setServerChannelCheck(type -> false);
                PlatformNetworking.setClientSender(payload -> sent.add(payload.type().id().toString()));
            });
            try {
                nothingIsSentAndThePreviewStillWorks(context, singleplayer);
            } finally {
                context.runOnClient(client -> {
                    PlatformNetworking.setServerChannelCheck(ClientPlayNetworking::canSend);
                    PlatformNetworking.setClientSender(ClientPlayNetworking::send);
                });
            }
        }
    }

    private void nothingIsSentAndThePreviewStillWorks(ClientGameTestContext context, TestSingleplayerContext sp) {
        givePlayer(sp, BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 5), 0));
        givePlayer(sp, ITEM_SLOT, new ItemStack(Items.STONE, 10));
        givePlayer(sp, ENDER_SLOT, new ItemStack(Items.ENDER_CHEST));
        openInventory(context);

        // Read-only: the preview needs nothing from the server.
        hoverInventorySlot(context, BOX_SLOT);
        boolean preview = context.computeOnClient(client -> BetterShulkerClient.isTooltipActive());
        assertTrue(preview, "the Shulker Box preview should still show without the mod on the server");

        // Every way the mod would talk to the server: extract, restock, insert, Ender Chest sync.
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        clearCursor(sp);
        context.waitTicks(2);
        hoverInventorySlot(context, BOX_SLOT);
        context.getInput().pressKey(ClientKeybinds.getRestockKey());
        context.waitTicks(2);
        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitTicks(2);
        hoverInventorySlot(context, ENDER_SLOT);
        context.waitTicks(5);

        List<String> attempted = context.computeOnClient(client -> List.copyOf(sent));
        assertTrue(attempted.isEmpty(), "nothing should be sent to a server without the mod, but the client sent "
                + attempted);

        clearCursor(sp);
        closeScreen(context);
    }
}

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
import static com.bettershulker.gametest.GameTestSupport.clickInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * A client on a server without the mod must not send it anything, or act as if it had.
 *
 * <p>Anything sent there is dropped, so a click the mod took over would move items on screen that
 * the server never moves, and they would jump back. A real server without the mod cannot run in
 * this test, so the client is told whether the server can receive the mod's payloads, and every
 * send is recorded instead of delivered.</p>
 *
 * <p>The same actions run twice. First the server is said to have the mod, which proves each
 * action really does send; then it is said not to, and nothing may be sent. Without the first
 * pass, an action that silently did nothing would pass the second for the wrong reason.</p>
 */
public class ServerWithoutModGameTest implements FabricClientGameTest {

    private static final int BOX_SLOT = 0;
    private static final int ITEM_SLOT = 1;
    private static final int ENDER_SLOT = 2;

    private static final String INTERACT = "bettershulker:container_interact";
    private static final String ENDER_REQUEST = "bettershulker:ender_request";

    /** Payload types the client tried to send, written on the client thread. */
    private static final List<String> sent = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            // The server console is not a player, so the command needs an explicit target.
            singleplayer.getServer().runCommand("gamemode survival @a");
            context.waitTicks(2);
            openInventory(context);

            try {
                List<String> withMod = performEveryAction(context, singleplayer, true);
                long interactions = withMod.stream().filter(INTERACT::equals).count();
                assertTrue(interactions == 3 && withMod.contains(ENDER_REQUEST),
                        "with the mod on the server, extract, restock and insert should each send an"
                                + " interaction and the Ender Chest hover a request, but the client sent " + withMod);

                List<String> withoutMod = performEveryAction(context, singleplayer, false);
                assertTrue(withoutMod.isEmpty(),
                        "nothing should be sent to a server without the mod, but the client sent " + withoutMod);
            } finally {
                context.runOnClient(client -> {
                    PlatformNetworking.setServerChannelCheck(ClientPlayNetworking::canSend);
                    PlatformNetworking.setClientSender(ClientPlayNetworking::send);
                });
                closeScreen(context);
            }
        }
    }

    /**
     * Extracts, restocks, inserts and hovers an Ender Chest, putting the inventory back before
     * each one, and returns what the client tried to send. The preview is checked before every
     * Shulker Box action: it reads only the box's own data, so it must work either way.
     */
    private List<String> performEveryAction(ClientGameTestContext context, TestSingleplayerContext sp,
                                            boolean serverHasMod) {
        context.runOnClient(client -> {
            sent.clear();
            PlatformNetworking.setServerChannelCheck(type -> serverHasMod);
            PlatformNetworking.setClientSender(payload -> sent.add(payload.type().id().toString()));
        });

        resetInventory(context, sp);
        hoverBoxAndExpectPreview(context);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);

        resetInventory(context, sp);
        hoverBoxAndExpectPreview(context);
        context.getInput().pressKey(ClientKeybinds.getRestockKey());
        context.waitTicks(2);

        resetInventory(context, sp);
        clickInventorySlot(context, ITEM_SLOT, InputConstants.MOUSE_BUTTON_LEFT);
        hoverBoxAndExpectPreview(context);
        clickInventorySlot(context, BOX_SLOT, InputConstants.MOUSE_BUTTON_RIGHT);

        resetInventory(context, sp);
        hoverInventorySlot(context, ENDER_SLOT);
        context.waitTicks(5);

        List<String> attempted = context.computeOnClient(client -> List.copyOf(sent));
        resetInventory(context, sp);
        return attempted;
    }

    private void hoverBoxAndExpectPreview(ClientGameTestContext context) {
        hoverInventorySlot(context, BOX_SLOT);
        boolean preview = context.computeOnClient(client -> BetterShulkerClient.isTooltipActive());
        assertTrue(preview, "the Shulker Box preview should show before the action");
    }

    /**
     * Puts the box, the stone and the Ender Chest back and empties the cursor. The full resync
     * matters: sends are recorded rather than delivered, so the client's own prediction may have
     * moved items the server never did.
     */
    private static void resetInventory(ClientGameTestContext context, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            inventory.setItem(BOX_SLOT, boxHolding(new ItemStack(Items.DIAMOND, 5), 0));
            inventory.setItem(ITEM_SLOT, new ItemStack(Items.DIAMOND, 10));
            inventory.setItem(ENDER_SLOT, new ItemStack(Items.ENDER_CHEST));
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.containerMenu.broadcastFullState();
        });
        context.waitTicks(3);
    }
}

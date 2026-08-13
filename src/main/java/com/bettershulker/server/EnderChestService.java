package com.bettershulker.server;

import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.platform.PlatformNetworking;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.util.InteractionSounds;
import com.bettershulker.util.ContainerTransfer;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side Ender Chest handling.
 *
 * <p>An Ender Chest's contents live on the player, not in the item, so the client cannot read
 * them and every preview is a copy the server sent. That makes this the authority twice over: it
 * decides whether a player may see the contents at all - which requires an Ender Chest they can
 * actually reach - and it performs every change to them.</p>
 *
 * <p>Syncs are diffed against the last state sent to each player, so an open preview costs only
 * the slots that changed.</p>
 */
public final class EnderChestService {
    private EnderChestService() {}









    public static NonNullList<ItemStack> copyEnderChestContents(ServerPlayer player) {
        var enderInv = player.getEnderChestInventory();
        NonNullList<ItemStack> contents = NonNullList.withSize(enderInv.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < enderInv.getContainerSize(); i++) {
            contents.set(i, enderInv.getItem(i).copy());
        }
        return contents;
    }

    public static void applyEnderChestContents(ServerPlayer player, NonNullList<ItemStack> contents) {
        var enderInv = player.getEnderChestInventory();
        for (int i = 0; i < enderInv.getContainerSize() && i < contents.size(); i++) {
            enderInv.setItem(i, contents.get(i));
        }
    }

    /**
     * Processes ender chest insertion/extraction on the server and reconciles both the normal
     * menu and the separate client-side Ender Chest cache.
     *
     * <p>The reconciliation is always a full snapshot rather than a diff. The client mutates its
     * cache optimistically before this packet arrives, and the server has no record of what that
     * prediction wrote, so a diff cannot describe the correction: rejected actions produce an
     * empty one, and accepted actions only cover the slots the server itself touched. Sending
     * every slot is the only reconciliation that holds regardless of what the client guessed.</p>
     */
    public static void handleEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
                                             ContainerInteractPayload.InteractType action, int inventorySlotId) {
        try {
            performEnderChestInteraction(player, containerSlot, targetIndex, action, inventorySlotId);
        } finally {
            player.containerMenu.broadcastFullState();
            EnderChestSync.sendAuthoritativeEnderChestSync(player);
        }
    }

    /**
     * Applies one interaction to the player's Ender Chest, then makes the sound it earned.
     *
     * <p>Each action lives in {@link EnderChestActions} and reports back rather than assigning to
     * shared locals. A no-op is silent: the eight cases all abort by reporting no success, which
     * is what the original's bare returns did by skipping past the sound below.</p>
     */
    public static void performEnderChestInteraction(ServerPlayer player, Slot containerSlot, int targetIndex,
                                             ContainerInteractPayload.InteractType action, int inventorySlotId) {
        EnderChestActions.Outcome outcome = switch (action) {
            case INSERT -> EnderChestActions.insert(player);
            case INSERT_ONE -> EnderChestActions.insertOne(player);
            case EXTRACT -> EnderChestActions.extract(player, targetIndex);
            case EXTRACT_ONE -> EnderChestActions.extractOne(player, targetIndex, inventorySlotId);
            case SWEEP_INSERT -> EnderChestActions.sweepInsert(player, inventorySlotId);
            case SWEEP_EXTRACT -> EnderChestActions.sweepExtract(player, targetIndex, inventorySlotId);
            case RESTOCK -> EnderChestActions.restock(player);
            case DEPOSIT -> EnderChestActions.deposit(player, containerSlot);
        };

        if (outcome.success()) {
            InteractionSounds.playInteractionSound(player, outcome.soundStack(), outcome.isInsert(), 0.3F);
        }
    }

}

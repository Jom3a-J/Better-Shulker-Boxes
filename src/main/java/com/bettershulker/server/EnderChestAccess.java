package com.bettershulker.server;

import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Whether a player may see or touch their Ender Chest through this mod.
 *
 * <p>The contents are on the player, so the server cannot infer permission from what the client
 * is holding - it has to find the Ender Chest item the request claims to come from and confirm
 * the player can actually reach and unlock it. A request naming no particular source falls back
 * to "any usable one in their inventory", which is what a tooltip outside a menu can offer.</p>
 */
final class EnderChestAccess {

    private EnderChestAccess() {}

    /** Returns whether the player has an Ender Chest item they may actually use. */
    public static boolean hasAccessibleEnderChestInInventory(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return false;

        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()
                    && ContainerHelper.isEnderChest(stack)
                    && ContainerHelper.canAccessContainer(stack, player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the exact source that caused a client to request an Ender Chest preview.
     *
     * <p>Tooltip requests may originate from a normal menu slot, the menu's carried stack,
     * or a context where Minecraft did not expose a menu source. The exact-source paths
     * deliberately use the same active/modifiable slot checks as container interactions;
     * the fallback keeps non-screen tooltip calls compatible with the original inventory
     * authorization.</p>
     */
    public static boolean hasAccessibleEnderChestSource(ServerPlayer player, int sourceSlotId) {
        if (!player.isAlive() || player.isSpectator()) return false;

        AbstractContainerMenu menu = player.containerMenu;
        if (!menu.stillValid(player)) return false;

        if (sourceSlotId == EnderChestRequestPayload.CARRIED_SOURCE_SLOT) {
            ItemStack carried = menu.getCarried();
            return isAccessibleEnderChest(carried, player);
        }

        if (MenuSlotRef.isSlot(sourceSlotId)) {
            Slot sourceSlot = MenuSlotRef.resolve(sourceSlotId, menu, player);
            return sourceSlot != null
                    && ServerSlots.isUsableSlot(sourceSlot)
                    && sourceSlot.allowModification(player)
                    && isAccessibleEnderChest(sourceSlot.getItem(), player);
        }

        if (sourceSlotId == EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE) {
            return hasAccessibleEnderChestInInventory(player);
        }

        return false;
    }

    public static boolean isAccessibleEnderChest(ItemStack stack, ServerPlayer player) {
        return ContainerHelper.isEnderChest(stack)
                && ContainerHelper.canAccessContainer(stack, player);
    }
}

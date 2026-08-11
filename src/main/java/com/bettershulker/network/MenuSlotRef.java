package com.bettershulker.network;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * Wire encoding for "which slot did the client mean".
 *
 * <p>A raw menu index only agrees between client and server when both are looking at the same
 * menu. Screens that build their own client-side menu number their slots their own way while the
 * server still holds the player's ordinary inventory menu, so an index sent from such a screen
 * addresses an unrelated slot or none at all. The creative inventory's item-picker menu is the
 * case that matters in practice: indices from it have been observed resolving to the crafting
 * result slot, and to values past the end of the server's menu.</p>
 *
 * <p>Player-inventory slots are therefore identified by their position inside the player's own
 * inventory, which is identical on both sides no matter which screen is open. Slots belonging to
 * any other container keep using the menu index, which stays correct because those screens share
 * their menu with the server.</p>
 *
 * <p>Negative values keep the sentinel meanings the payloads already assign them, so only
 * non-negative references carry this encoding.</p>
 */
public final class MenuSlotRef {
    /** No slot supplied; also means "the menu's carried stack" where a payload defines it so. */
    public static final int NONE = -1;

    /**
     * Offset marking a reference as "player inventory slot N" rather than "menu slot N". Chosen
     * far above any real menu's slot count so the two spaces cannot overlap.
     */
    private static final int PLAYER_INVENTORY_BASE = 10_000;

    private MenuSlotRef() {}

    /** Encodes a slot so the server can resolve it without sharing the client's menu layout. */
    public static int encode(@Nullable Slot slot, @Nullable Player player) {
        if (slot == null || player == null) return NONE;

        if (slot.container == player.getInventory()) {
            int containerSlot = slot.getContainerSlot();
            if (containerSlot >= 0) {
                return PLAYER_INVENTORY_BASE + containerSlot;
            }
        }
        return slot.index;
    }

    /** Whether a reference names a slot at all, as opposed to a sentinel. */
    public static boolean isSlot(int ref) {
        return ref >= 0;
    }

    /** Resolves a reference against the given menu, or null when it names no slot there. */
    @Nullable
    public static Slot resolve(int ref, @Nullable AbstractContainerMenu menu, @Nullable Player player) {
        if (menu == null || player == null || ref < 0) return null;

        if (ref >= PLAYER_INVENTORY_BASE) {
            int containerSlot = ref - PLAYER_INVENTORY_BASE;
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory() && slot.getContainerSlot() == containerSlot) {
                    return slot;
                }
            }
            return null;
        }

        if (ref >= menu.slots.size()) return null;
        return menu.slots.get(ref);
    }

    /** Resolves against the player's live menu, which is the authority on the server. */
    @Nullable
    public static Slot resolve(int ref, @Nullable Player player) {
        return player == null ? null : resolve(ref, player.containerMenu, player);
    }
}

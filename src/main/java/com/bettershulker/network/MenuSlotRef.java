package com.bettershulker.network;

import net.minecraft.world.Container;
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

    /**
     * Returns the slot's real position inside the player's inventory, or -1 if it is not one.
     *
     * <p>A screen may wrap another menu's slots and pass the wrapper's own index to the Slot
     * constructor, so {@code getContainerSlot()} reports that index instead of an inventory
     * position. The creative inventory tab does exactly this. The declared value is therefore
     * only trusted when it actually points at this slot's stack; otherwise it is treated as an
     * index into the player's inventory menu, whose own slots do carry real positions.</p>
     *
     * <p>Both encoding and resolution go through here so the two stay symmetric. They must: a ref
     * produced by one and matched by the other has to describe the same slot.</p>
     */
    public static int playerInventoryPosition(@Nullable Slot slot, @Nullable Player player) {
        if (slot == null || player == null || slot.container != player.getInventory()) return -1;

        Container inventory = player.getInventory();
        int declared = slot.getContainerSlot();
        if (declared < 0) return -1;

        if (declared < inventory.getContainerSize() && inventory.getItem(declared) == slot.getItem()) {
            return declared;
        }

        // Wrapped slot. Falling back to slot.index is no use here: such screens add their slots
        // directly to the list rather than through addSlot, so index is never assigned and stays
        // 0 for every one of them.
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (declared < inventoryMenu.slots.size()) {
            Slot backing = inventoryMenu.slots.get(declared);
            if (backing.container == inventory && backing.getContainerSlot() >= 0) {
                return backing.getContainerSlot();
            }
        }
        return -1;
    }

    /** Encodes a slot so the server can resolve it without sharing the client's menu layout. */
    public static int encode(@Nullable Slot slot, @Nullable Player player) {
        if (slot == null || player == null) return NONE;

        int position = playerInventoryPosition(slot, player);
        return position >= 0 ? PLAYER_INVENTORY_BASE + position : slot.index;
    }

    /** Whether a reference names a slot at all, as opposed to a sentinel. */
    public static boolean isSlot(int ref) {
        return ref >= 0;
    }

    /** Builds a reference to a player-inventory position captured earlier. */
    public static int forPlayerPosition(int position) {
        return position < 0 ? NONE : PLAYER_INVENTORY_BASE + position;
    }

    /**
     * Whether the slot's inventory position is certain rather than merely probable.
     *
     * <p>{@link #playerInventoryPosition} decides whether the declared value is a real position by
     * checking that it points at this slot's stack. That test cannot separate the two readings
     * when the slot is empty, because every empty slot holds the same stack instance. Callers that
     * would write to the position - rather than just read from it - need to know that, since
     * writing to the wrong one destroys whatever is there.</p>
     */
    public static boolean hasUnambiguousPlayerPosition(@Nullable Slot slot, @Nullable Player player) {
        int position = playerInventoryPosition(slot, player);
        if (position < 0) return false;
        if (!slot.getItem().isEmpty()) return true;

        // Empty: treat the declared value as suspect if reading it as an inventory-menu index
        // would name a different position.
        int declared = slot.getContainerSlot();
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (declared >= 0 && declared < inventoryMenu.slots.size()) {
            Slot backing = inventoryMenu.slots.get(declared);
            if (backing.container == player.getInventory()
                    && backing.getContainerSlot() >= 0
                    && backing.getContainerSlot() != declared) {
                return false;
            }
        }
        return true;
    }

    /** Resolves a reference against the given menu, or null when it names no slot there. */
    @Nullable
    public static Slot resolve(int ref, @Nullable AbstractContainerMenu menu, @Nullable Player player) {
        if (menu == null || player == null || ref < 0) return null;

        if (ref >= PLAYER_INVENTORY_BASE) {
            int position = ref - PLAYER_INVENTORY_BASE;
            for (Slot slot : menu.slots) {
                if (playerInventoryPosition(slot, player) == position) {
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

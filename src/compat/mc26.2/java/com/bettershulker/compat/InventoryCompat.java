package com.bettershulker.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Hands a stack back to a player's inventory, in the spelling this Minecraft version uses.
 *
 * <p>Minecraft 26.3 made {@code Inventory.placeItemBackInInventory} take a {@code Prediction},
 * saying whether the client predicts the change or the server alone applies it. The type does not
 * exist in 26.2, which the mod still builds against on NeoForge, so each loader compiles the copy
 * matching its Minecraft version.</p>
 *
 * <p>This is the Minecraft 26.2 copy.</p>
 */
public final class InventoryCompat {

    private InventoryCompat() {}

    /** Returns a stack to the player's inventory. */
    public static void placeItemBack(Player player, ItemStack stack) {
        player.getInventory().placeItemBackInInventory(stack);
    }
}

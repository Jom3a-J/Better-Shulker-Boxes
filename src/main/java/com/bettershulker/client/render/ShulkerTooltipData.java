package com.bettershulker.client.render;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Tooltip data payload class passed from the mixin container to the tooltip component builder.
 * Implements net.minecraft.world.inventory.tooltip.TooltipComponent.
 * 
 * @param contents          the NonNullList containing the container's items (exactly 27 slots)
 * @param color             the DyeColor of the container block if colored/dyed
 * @param isEnderChest      true if the container is an ender chest
 * @param selectedItemName  the display name of the currently selected/hovered item inside the preview
 * @param containerName     the name of the container stack being previewed
 */
public record ShulkerTooltipData(
        NonNullList<ItemStack> contents,
        DyeColor color,
        boolean isEnderChest,
        String selectedItemName,
        String containerName
) implements TooltipComponent {
}

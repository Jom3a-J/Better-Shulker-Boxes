package com.bettershulker.client.render;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

public record ShulkerTooltipData(
        NonNullList<ItemStack> contents,
        DyeColor color,
        boolean isEnderChest,
        String selectedItemName,
        String containerName
) implements TooltipComponent {
}

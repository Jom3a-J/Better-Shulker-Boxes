package com.bettershulker.client.render;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
 * @param screenTitle       the title the container's own screen would show, which resource-pack
 *                          name rules are matched against; see {@link #screenTitleOf}
 */
public record ShulkerTooltipData(
        NonNullList<ItemStack> contents,
        DyeColor color,
        boolean isEnderChest,
        String selectedItemName,
        String containerName,
        String screenTitle
) implements TooltipComponent {

    /**
     * The box's custom name, or the translated {@code container.shulkerBox} when it has none.
     *
     * <p>That is what a placed box's screen is titled and so what OptiFine and OptiGUI name rules
     * see - an unnamed purple box is "Shulker Box" there, not its item name.</p>
     */
    public static String screenTitleOf(ItemStack stack) {
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        return (customName != null ? customName : Component.translatable("container.shulkerBox")).getString();
    }
}

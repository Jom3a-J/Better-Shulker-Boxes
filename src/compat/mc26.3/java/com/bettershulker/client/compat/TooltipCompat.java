package com.bettershulker.client.compat;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * Draws a tooltip immediately, in the spelling this Minecraft version uses.
 *
 * <p>Minecraft 26.3 gave {@code GuiGraphicsExtractor.tooltip} a trailing flag that 26.2 has no
 * parameter for. Vanilla's own shorter overloads pass {@code false}, so that is what this passes
 * to keep the preview drawing exactly as it did. NeoForge still builds against 26.2, so each
 * loader compiles the copy matching its Minecraft version.</p>
 *
 * <p>This is the Minecraft 26.3 copy.</p>
 */
public final class TooltipCompat {

    private TooltipCompat() {}

    public static void tooltip(GuiGraphicsExtractor context, Font font,
                               List<ClientTooltipComponent> components, int x, int y,
                               ClientTooltipPositioner positioner, Identifier style) {
        context.tooltip(font, components, x, y, positioner, style, false);
    }
}

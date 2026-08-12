package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

import java.util.List;

/**
 * Lets the Modern theme render as a standalone panel instead of sitting inside the vanilla
 * tooltip frame.
 *
 * <p>The frame is drawn by {@code TooltipRenderUtil.extractTooltipBackground}, which receives no
 * information about the components it is framing. So the decision is taken one level up, in
 * {@code GuiGraphicsExtractor.tooltip}, where the component list is still available, and handed
 * down through this one-shot flag.</p>
 *
 * <p>The flag is consumed by the very next background draw, which keeps nested tooltips honest:
 * a tooltip opened from inside our component's rendering sets its own value before its own
 * background runs, so it still gets a normal vanilla frame. Render-thread only; no locking.</p>
 */
public final class ModernTooltipFrame {

    private static boolean suppressNextBackground;

    private ModernTooltipFrame() {}

    /** True when this tooltip is a container preview that paints its own complete panel. */
    public static boolean shouldSuppressFrame(List<? extends ClientTooltipComponent> components) {
        if (!BetterShulkerConfig.tooltipEnabled || components == null) return false;
        for (ClientTooltipComponent component : components) {
            // The component decides: it knows both the style and whether a resource pack has
            // taken over the panel, in which case the pack's texture still needs its frame.
            if (component instanceof ShulkerTooltipComponent shulker && shulker.drawsOwnPanel()) {
                return true;
            }
        }
        return false;
    }

    /** Always assigns, so a background draw that never arrives cannot strand a stale true. */
    public static void setSuppressNextBackground(boolean suppress) {
        suppressNextBackground = suppress;
    }

    /** Reads and clears in one step; the next tooltip must set the flag for itself. */
    public static boolean consumeSuppressNextBackground() {
        boolean suppress = suppressNextBackground;
        suppressNextBackground = false;
        return suppress;
    }
}

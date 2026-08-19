package com.bettershulker.client.render;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Samples the dominant body colour out of a resource pack's container panel texture, so the
 * Modern card can take a pack's palette while keeping its own shape.
 *
 * <p>The storage grid is deliberately cut out of the crop first. On a standard panel the slot
 * squares are 58% of the pixels around the grid, so a straight count over the whole crop returns
 * the pack's slot shade rather than its panel: on vanilla's own texture that is #8B8B8B instead
 * of #C6C6C6. Packs that recolour per dye keep slots much darker than the panel, which pulled
 * every card towards a muddy near-black and left neighbouring dyes hard to tell apart.</p>
 *
 * <p>Quantising to five bits per channel stops a gradient from splitting its own vote across
 * near-identical shades. Results are cached per texture and dropped on resource reload.</p>
 */
public final class ResourcePackPanelColors {

    private static final int MIN_OPAQUE_ALPHA = 200;
    private static final int MAX_CACHE_ENTRIES = 32;

    /**
     * Sampled panel colours, evicted least-recently-used once full.
     *
     * <p>Sampling a texture decodes the whole image to count its pixels, so a miss is far more
     * expensive than the int it stores. Emptying the cache on overflow threw that work away for
     * every texture at once; dropping only the coldest entry keeps the panels in use sampled.</p>
     *
     * <p>Access order means {@code get} mutates the map, which is why the methods below are
     * synchronized where they previously did not need to be - a read is now a write, and the
     * reload listener can clear this from the reload executor while the render thread samples.
     * The two sibling classes in this package already hold the same lock discipline.</p>
     */
    private static final Map<Identifier, Integer> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Identifier, Integer> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    private ResourcePackPanelColors() {
    }

    /**
     * Dominant opaque colour of the panel, or {@code fallback} when the texture cannot be read
     * or holds nothing solid enough to sample.
     */
    public static synchronized int dominantColor(Identifier texture, int fallback) {
        if (texture == null) return fallback;

        Integer cached = CACHE.get(texture);
        if (cached != null) {
            return cached == 0 ? fallback : cached;
        }

        int sampled = sample(texture);
        CACHE.put(texture, sampled);
        return sampled == 0 ? fallback : sampled;
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** Returns the dominant colour, or 0 when the texture is unreadable or fully transparent. */
    private static int sample(Identifier texture) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return 0;

        Optional<Resource> resource = client.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return 0;

        try (InputStream stream = resource.get().open();
             NativeImage image = NativeImage.read(stream)) {
            ResourcePackLayout layout = ResourcePackLayoutProfiles.resolveBase(texture);
            int body = dominantOpaqueColor(image, layout, true);
            // A fully translucent panel body leaves nothing to sample. Rather than drop the
            // pack's palette entirely, take the crop the old way, slots included.
            return body != 0 ? body : dominantOpaqueColor(image, layout, false);
        } catch (Exception ignored) {
            // A pack can ship a texture Minecraft itself would reject; fall back rather than
            // taking the tooltip down with it.
            return 0;
        }
    }

    /**
     * Most frequent opaque colour of the panel crop, or 0 when it holds nothing solid.
     *
     * @param excludeSlots leave the storage grid out of the count, so the panel body wins
     */
    private static int dominantOpaqueColor(NativeImage image, ResourcePackLayout layout,
                                           boolean excludeSlots) {
        int slotLeft = layout.sourceSlotX();
        int slotTop = layout.sourceSlotY();
        int slotRight = slotLeft + layout.columns() * layout.slotSize();
        int slotBottom = slotTop + layout.rows() * layout.slotSize();

        int minX = Math.max(0, layout.sourcePanelX());
        int minY = Math.max(0, layout.sourcePanelY());
        int maxX = Math.min(image.getWidth(), layout.sourcePanelX() + layout.sourcePanelWidth());
        // The panel of a Shulker GUI runs from its top edge to just past the grid; the rest of
        // the file is the player's inventory, which no pack styles as part of this container.
        int maxY = Math.min(Math.min(image.getHeight(), slotBottom + layout.bottomCapHeight()),
                layout.sourcePanelY() + layout.sourcePanelHeight());

        Map<Integer, Integer> counts = new HashMap<>();
        int bestQuantised = 0;
        int bestCount = 0;

        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                if (excludeSlots && x >= slotLeft && x < slotRight && y >= slotTop && y < slotBottom) {
                    continue;
                }
                // getPixel is ARGB already; the ABGR the buffer holds is only reachable through
                // getPixelABGR, which is private. Swapping here painted a red box's card purple.
                int argb = image.getPixel(x, y);
                if (((argb >>> 24) & 0xFF) < MIN_OPAQUE_ALPHA) continue;

                int quantised = argb & 0x00F8F8F8;
                int count = counts.merge(quantised, 1, Integer::sum);
                if (count > bestCount) {
                    bestCount = count;
                    bestQuantised = quantised;
                }
            }
        }

        if (bestCount == 0) return 0;
        // Recentre the quantised bucket so the result is not biased dark by the truncation.
        return 0xFF000000 | (bestQuantised + 0x00040404);
    }
}

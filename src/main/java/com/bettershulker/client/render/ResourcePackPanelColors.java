package com.bettershulker.client.render;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Samples the dominant body colour out of a resource pack's container panel texture, so the
 * Modern card can take a pack's palette while keeping its own shape.
 *
 * <p>The panel body is by far the largest block of opaque pixels in the crop, so the most
 * frequent colour lands on it rather than on slots, shadows or the border. Quantising to five
 * bits per channel first stops a gradient from splitting its own vote across near-identical
 * shades. Results are cached per texture and dropped on resource reload.</p>
 */
public final class ResourcePackPanelColors {

    /** Region of the texture that holds the storage-slot band on a standard 176x68 panel. */
    private static final int SAMPLE_X = 0;
    private static final int SAMPLE_Y = 11;
    private static final int SAMPLE_WIDTH = 176;
    private static final int SAMPLE_HEIGHT = 68;
    private static final int MIN_OPAQUE_ALPHA = 200;
    private static final int MAX_CACHE_ENTRIES = 32;

    private static final Map<Identifier, Integer> CACHE = new HashMap<>();

    private ResourcePackPanelColors() {
    }

    /**
     * Dominant opaque colour of the panel, or {@code fallback} when the texture cannot be read
     * or holds nothing solid enough to sample.
     */
    public static int dominantColor(Identifier texture, int fallback) {
        if (texture == null) return fallback;

        Integer cached = CACHE.get(texture);
        if (cached != null) {
            return cached == 0 ? fallback : cached;
        }

        int sampled = sample(texture);
        if (CACHE.size() >= MAX_CACHE_ENTRIES) CACHE.clear();
        CACHE.put(texture, sampled);
        return sampled == 0 ? fallback : sampled;
    }

    public static void clearCache() {
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
            return dominantOpaqueColor(image);
        } catch (Exception ignored) {
            // A pack can ship a texture Minecraft itself would reject; fall back rather than
            // taking the tooltip down with it.
            return 0;
        }
    }

    private static int dominantOpaqueColor(NativeImage image) {
        int maxX = Math.min(image.getWidth(), SAMPLE_X + SAMPLE_WIDTH);
        int maxY = Math.min(image.getHeight(), SAMPLE_Y + SAMPLE_HEIGHT);

        Map<Integer, Integer> counts = new HashMap<>();
        int bestQuantised = 0;
        int bestCount = 0;

        for (int y = SAMPLE_Y; y < maxY; y++) {
            for (int x = SAMPLE_X; x < maxX; x++) {
                int argb = toArgb(image.getPixel(x, y));
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

    /**
     * NativeImage stores pixels little-endian as ABGR; the rest of the tooltip code works in
     * ARGB, so swap the red and blue channels here rather than at every call site.
     */
    private static int toArgb(int abgr) {
        return (abgr & 0xFF00FF00)
                | ((abgr >> 16) & 0x000000FF)
                | ((abgr << 16) & 0x00FF0000);
    }
}

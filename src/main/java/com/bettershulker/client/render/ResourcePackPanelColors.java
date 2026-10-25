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
 * Samples a resource pack's container panel texture: its dominant body colour, so the Modern
 * card can take a pack's palette while keeping its own shape, whether it is a panel at all, and
 * how its storage section closes off at the bottom.
 *
 * <p>The storage grid is deliberately cut out of the crop first. On a standard panel the slot
 * squares are 58% of the pixels around the grid, so a straight count over the whole crop returns
 * the pack's slot shade rather than its panel: on vanilla's own texture that is #8B8B8B instead
 * of #C6C6C6. Packs that recolour per dye keep slots much darker than the panel, which pulled
 * every card towards a muddy near-black and left neighbouring dyes hard to tell apart.</p>
 *
 * <p>Layouts are in the texture's logical units - 256 across for a container - while an HD pack
 * draws the same panel at 512 or 1024 pixels. Every crop is scaled by the image's real size, or a
 * 32x pack would be sampled across only its top-left quarter.</p>
 *
 * <p>Quantising to five bits per channel stops a gradient from splitting its own vote across
 * near-identical shades. Results are cached per texture and dropped on resource reload.</p>
 */
public final class ResourcePackPanelColors {

    private static final int MIN_OPAQUE_ALPHA = 200;
    /** Alpha below which a pixel draws as nothing at all. */
    private static final int MAX_CLEAR_ALPHA = 16;
    /** Share of the panel that may be clear before it stops counting as a panel. */
    private static final double MAX_CLEAR_SHARE = 0.5;
    private static final int MAX_CACHE_ENTRIES = 32;

    /**
     * What sampling a texture found; a colour of 0 means nothing solid enough to use, and a null
     * bottom edge that the texture could not be read.
     */
    private record Sample(int color, boolean mostlyTransparent, PanelBottomEdge bottomEdge) {
        static final Sample UNREADABLE = new Sample(0, false, null);
    }

    /**
     * Samples, evicted least-recently-used once full.
     *
     * <p>Sampling a texture decodes the whole image to count its pixels, so a miss is far more
     * expensive than what it stores. Emptying the cache on overflow threw that work away for
     * every texture at once; dropping only the coldest entry keeps the panels in use sampled.</p>
     *
     * <p>Access order means {@code get} mutates the map, which is why the methods below are
     * synchronized - a read is a write, and the reload listener can clear this from the reload
     * executor while the render thread samples. The two sibling classes in this package already
     * hold the same lock discipline.</p>
     */
    private static final Map<Identifier, Sample> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Identifier, Sample> eldest) {
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
        int sampled = sampleCached(texture).color();
        return sampled == 0 ? fallback : sampled;
    }

    /**
     * Whether most of the panel is see-through, so recomposing it would draw holes.
     *
     * <p>Immersive Interfaces ships a Shulker panel that is about 70% clear: a picture plus
     * marker pixels its core shaders read, not something to blit. An unreadable texture is not
     * reported as clear; Minecraft will show its own missing texture for that.</p>
     */
    public static synchronized boolean isMostlyTransparent(Identifier texture) {
        return texture != null && sampleCached(texture).mostlyTransparent();
    }

    /**
     * How to close off the bottom of the panel's storage section; see {@link PanelBottomEdge}.
     * An unreadable texture gets the plain repeated row, worked out from {@code layout}.
     */
    public static synchronized PanelBottomEdge bottomEdge(Identifier texture, ResourcePackLayout layout) {
        PanelBottomEdge edge = texture == null ? null : sampleCached(texture).bottomEdge();
        return edge != null ? edge : PanelBottomEdge.repeatBodyRow(layout);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static Sample sampleCached(Identifier texture) {
        Sample cached = CACHE.get(texture);
        if (cached != null) return cached;
        Sample sampled = sample(texture);
        CACHE.put(texture, sampled);
        return sampled;
    }

    private static Sample sample(Identifier texture) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return Sample.UNREADABLE;

        Optional<Resource> resource = client.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return Sample.UNREADABLE;

        try (InputStream stream = resource.get().open();
             NativeImage image = NativeImage.read(stream)) {
            ResourcePackLayout layout = ResourcePackLayoutProfiles.resolveBase(texture);
            return new Sample(dominantColor(image, layout), isMostlyTransparent(image, layout),
                    PanelBottomEdge.analyse(image, layout));
        } catch (Exception ignored) {
            // A pack can ship a texture Minecraft itself would reject; fall back rather than
            // taking the tooltip down with it.
            return Sample.UNREADABLE;
        }
    }

    /**
     * Dominant body colour of an already decoded panel, or 0 when it holds nothing solid.
     * Public so the game tests can hand it an image no pack has to ship.
     */
    public static int dominantColor(NativeImage image, ResourcePackLayout layout) {
        Crop crop = new Crop(image, layout);
        int body = dominantOpaqueColor(image, crop, true);
        // A fully translucent panel body leaves nothing to sample. Rather than drop the
        // pack's palette entirely, take the crop the old way, slots included.
        return body != 0 ? body : dominantOpaqueColor(image, crop, false);
    }

    /** {@link #isMostlyTransparent(Identifier)} for an already decoded panel. */
    public static boolean isMostlyTransparent(NativeImage image, ResourcePackLayout layout) {
        return clearShare(image, new Crop(image, layout)) > MAX_CLEAR_SHARE;
    }

    /**
     * The panel and its storage grid in image pixels.
     *
     * <p>The panel of a Shulker GUI runs from its top edge to just past the grid; the rest of the
     * file is the player's inventory, which no pack styles as part of this container.</p>
     */
    private record Crop(int minX, int minY, int maxX, int maxY,
                        int slotLeft, int slotTop, int slotRight, int slotBottom) {

        Crop(NativeImage image, ResourcePackLayout layout) {
            this(image, layout,
                    image.getWidth() / (double) layout.textureWidth(),
                    image.getHeight() / (double) layout.textureHeight());
        }

        private Crop(NativeImage image, ResourcePackLayout layout, double scaleX, double scaleY) {
            this(
                    Math.max(0, px(layout.sourcePanelX(), scaleX)),
                    Math.max(0, px(layout.sourcePanelY(), scaleY)),
                    Math.min(image.getWidth(), px(layout.sourcePanelX() + layout.sourcePanelWidth(), scaleX)),
                    Math.min(image.getHeight(), px(Math.min(
                            layout.sourceSlotY() + layout.rows() * layout.slotSize() + layout.bottomCapHeight(),
                            layout.sourcePanelY() + layout.sourcePanelHeight()), scaleY)),
                    px(layout.sourceSlotX(), scaleX),
                    px(layout.sourceSlotY(), scaleY),
                    px(layout.sourceSlotX() + layout.columns() * layout.slotSize(), scaleX),
                    px(layout.sourceSlotY() + layout.rows() * layout.slotSize(), scaleY));
        }

        private static int px(int logical, double scale) {
            return (int) Math.round(logical * scale);
        }

        boolean inGrid(int x, int y) {
            return x >= this.slotLeft && x < this.slotRight && y >= this.slotTop && y < this.slotBottom;
        }
    }

    /** Share of the panel crop, slots included, that draws as nothing. */
    private static double clearShare(NativeImage image, Crop crop) {
        long clear = 0;
        long total = 0;
        for (int y = crop.minY(); y < crop.maxY(); y++) {
            for (int x = crop.minX(); x < crop.maxX(); x++) {
                total++;
                if (((image.getPixel(x, y) >>> 24) & 0xFF) < MAX_CLEAR_ALPHA) clear++;
            }
        }
        return total == 0 ? 0 : clear / (double) total;
    }

    /**
     * Most frequent opaque colour of the panel crop, or 0 when it holds nothing solid.
     *
     * @param excludeSlots leave the storage grid out of the count, so the panel body wins
     */
    private static int dominantOpaqueColor(NativeImage image, Crop crop, boolean excludeSlots) {
        Map<Integer, Integer> counts = new HashMap<>();
        int bestQuantised = 0;
        int bestCount = 0;

        for (int y = crop.minY(); y < crop.maxY(); y++) {
            for (int x = crop.minX(); x < crop.maxX(); x++) {
                if (excludeSlots && crop.inGrid(x, y)) continue;
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

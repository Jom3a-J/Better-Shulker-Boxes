package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves optional pack-provided layout metadata and built-in compatibility profiles. */
public final class ResourcePackLayoutProfiles {
    /** Resource packs may add assets/bettershulker/layouts/*.json to describe their GUI geometry. */
    private static final String METADATA_PATH = "bettershulker/layouts";
    private static final ResourcePackLayout STANDARD = ResourcePackLayout.standard();
    /**
     * Safety bound on the layout cache. Keys are textures, so the real ceiling is however many
     * distinct panels a pack ships - around twenty at the high end. This is headroom, not a size
     * eviction is expected to reach.
     */
    private static final int MAX_BASE_CACHE_ENTRIES = 64;

    /**
     * Parsed layout geometry, evicted least-recently-used once full.
     *
     * <p>A miss reparses the pack's layout JSON off disk, so the cache was previously left to
     * grow without a bound at all. Bounding it costs nothing here - the key space is small - and
     * it means a pack shipping an unusual number of panels cannot grow this without limit.</p>
     *
     * <p>Access order means {@code get} mutates the map, so every read has to stay inside this
     * class's synchronized methods - as they all already do.</p>
     */
    private static final Map<Identifier, ResourcePackLayout> BASE_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Identifier, ResourcePackLayout> eldest) {
                    return size() > MAX_BASE_CACHE_ENTRIES;
                }
            };
    private static Object cachedResourceManager;

    /* The OptiGUI pack shown during development uses this exact shulker texture arrangement. */
    private static final ResourcePackLayout OLED_COLOURFUL_CONTAINERS = new ResourcePackLayout(
            "oled-colourful-containers",
            0, 0, 176, 75,
            8, 18, 18, 9, 3,
            176, 8, 7,
            71, 7,
            256, 256
    );

    private ResourcePackLayoutProfiles() {
    }

    public static synchronized ResourcePackLayout resolve(Identifier texture) {
        return resolveBase(texture).withOutputAdjustments(
                BetterShulkerConfig.getResourcePackLayoutOffsetX(),
                BetterShulkerConfig.getResourcePackLayoutOffsetY(),
                BetterShulkerConfig.getResourcePackLayoutBottomCapHeight()
        );
    }

    /** Resolves only the pack/built-in geometry so settings previews can apply unsaved values. */
    public static synchronized ResourcePackLayout resolveBase(Identifier texture) {
        ensureCacheForCurrentResources();
        ResourcePackLayout cached = BASE_CACHE.get(texture);
        if (cached != null) return cached;

        ResourcePackLayout resolved = resolveBaseUncached(texture);
        BASE_CACHE.put(texture, resolved);
        return resolved;
    }

    /** Clears parsed layout metadata after a client resource reload. */
    public static synchronized void clearCache() {
        BASE_CACHE.clear();
        cachedResourceManager = null;
    }

    private static ResourcePackLayout resolveBaseUncached(Identifier texture) {
        ResourcePackLayout metadata = findMetadataProfile(texture);
        ResourcePackLayout selected = metadata != null ? metadata : findBuiltInProfile(texture);
        return selected == null || !selected.isUsable() ? STANDARD : selected;
    }

    private static void ensureCacheForCurrentResources() {
        Object resourceManager = Minecraft.getInstance().getResourceManager();
        if (cachedResourceManager != resourceManager) {
            BASE_CACHE.clear();
            cachedResourceManager = resourceManager;
        }
    }

    private static ResourcePackLayout findBuiltInProfile(Identifier texture) {
        if ("optigui".equals(texture.getNamespace())
                && "gui/shulkers/shulker/shulker.png".equals(texture.getPath())) {
            return OLED_COLOURFUL_CONTAINERS;
        }
        if ("optigui".equals(texture.getNamespace())
                && texture.getPath().startsWith("gui/shulkers/")
                && texture.getPath().endsWith(".png")) {
            return STANDARD;
        }
        return STANDARD;
    }

    private static ResourcePackLayout findMetadataProfile(Identifier texture) {
        try {
            var resources = Minecraft.getInstance().getResourceManager().listResources(
                    METADATA_PATH,
                    id -> id.getPath().endsWith(".json"));
            return resources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                    .map(entry -> parseMetadata(entry.getKey(), entry.getValue(), texture))
                    .filter(profile -> profile != null && profile.isUsable())
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ResourcePackLayout parseMetadata(Identifier metadataId, Resource resource,
                                                    Identifier texture) {
        try (InputStream input = resource.open();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();

            if (!matches(root, texture)) return null;

            JsonObject source = object(root, "source");
            JsonObject slots = object(root, "slots");
            JsonObject output = object(root, "output");

            int sourceX = intValue(source, "x", 0);
            int sourceY = intValue(source, "y", 0);
            int sourceWidth = intValue(source, "width", 176);
            int sourceHeight = intValue(source, "height", 166);
            int sourceSlotX = intValue(slots, "x", 8);
            int sourceSlotY = intValue(slots, "y", 18);
            int slotSize = intValue(slots, "size", 18);
            int columns = intValue(slots, "columns", 9);
            int rows = intValue(slots, "rows", 3);
            int outputWidth = intValue(output, "width", sourceWidth);
            int outputSlotX = intValue(output, "slotX", 8);
            int outputSlotY = intValue(output, "slotY", 7);
            int bottomCapSourceY = intValue(output, "bottomCapSourceY",
                    sourceSlotY + rows * slotSize - 1);
            int bottomCapHeight = intValue(output, "bottomCapHeight", 7);
            int textureWidth = intValue(root, "textureWidth", 256);
            int textureHeight = intValue(root, "textureHeight", 256);
            String id = stringValue(root, "id", metadataId.toString());

            ResourcePackLayout profile = new ResourcePackLayout(
                    id,
                    sourceX, sourceY, sourceWidth, sourceHeight,
                    sourceSlotX, sourceSlotY, slotSize, columns, rows,
                    outputWidth, outputSlotX, outputSlotY,
                    bottomCapSourceY, bottomCapHeight,
                    textureWidth, textureHeight
            );
            return profile.isUsable() ? profile : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matches(JsonObject root, Identifier texture) {
        JsonObject match = object(root, "match");
        String texturePattern = stringValue(match, "texture", null);
        if (texturePattern == null) texturePattern = stringValue(root, "matchTexture", null);
        if (texturePattern == null) return true;
        return wildcardMatches(texturePattern, texture.toString());
    }

    private static boolean wildcardMatches(String pattern, String value) {
        String normalizedPattern = pattern.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedValue = value.toLowerCase(java.util.Locale.ROOT);
        if (normalizedPattern.equals(normalizedValue)) return true;
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char ch = normalizedPattern.charAt(i);
            if (ch == '*') regex.append(".*");
            else if (".[](){}+$^|\\".indexOf(ch) >= 0) regex.append('\\').append(ch);
            else regex.append(ch);
        }
        return normalizedValue.matches(regex.append('$').toString());
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.DyeColor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Resolves the GUI panel a resource pack supplies for Better Shulker previews.
 *
 * <p>The vanilla texture is always the safe base because it is what Minecraft renders when a
 * pack directly replaces a normal Shulker GUI. Packs that ship conventional OptiFine Custom GUI
 * or OptiGUI definitions are resolved for dye-specific Shulker panels. Ender previews intentionally
 * retain Better Shulker's own theme rather than inheriting a placed-container resource-pack GUI.</p>
 */
public final class ResourcePackContainerTextures {
    private static final Identifier SHULKER_PANEL = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final Identifier GENERIC_54_PANEL = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int MAX_PANEL_CACHE_ENTRIES = 64;

    /**
     * Resolved panels, evicted least-recently-used once full.
     *
     * <p>A resolution is keyed partly by the container's name, so the number of distinct keys is
     * whatever a player chooses to rename their boxes to. Discarding the whole cache on overflow
     * made that unbounded input costly: passing a chest of uniquely named boxes threw away every
     * resolution, including the one for the box about to be hovered again, and each miss rereads
     * the pack's OptiFine and OptiGUI definitions off disk. Dropping only the coldest entry keeps
     * the boxes actually in use resolved.</p>
     *
     * <p>Access order means {@code get} mutates the map, so every read has to stay inside this
     * class's synchronized methods - as they all already do.</p>
     */
    private static final Map<ResolutionKey, Panel> PANEL_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ResolutionKey, Panel> eldest) {
                    return size() > MAX_PANEL_CACHE_ENTRIES;
                }
            };
    private static Object cachedResourceManager;
    private static Boolean cachedGuiActive;

    private ResourcePackContainerTextures() {
    }

    /** A complete three-row Shulker panel plus the profile used to recompose its texture. */
    public record Panel(Identifier texture, Identifier bottomCapTexture, boolean suppliedByPack,
                        boolean exactCustomGui, int bottomCapSourceY, int bottomCapHeight,
                        ResourcePackLayout layout) {
    }

    private record ResolutionKey(DyeColor color, boolean enderChest, String containerName,
                                 BetterShulkerConfig.ResourcePackMode mode,
                                 int offsetX, int offsetY, int capHeight) {
    }

    public static synchronized Panel resolve(DyeColor color, boolean enderChest) {
        return resolve(color, enderChest, null);
    }

    /** Resolves a panel while allowing name-based OptiFine rules to be evaluated. */
    public static synchronized Panel resolve(DyeColor color, boolean enderChest, String containerName) {
        return resolveCached(color, enderChest, containerName, BetterShulkerConfig.getResourcePackMode());
    }

    /** Resolves only the currently active external resource-pack GUI, ignoring the saved mode. */
    public static synchronized Panel resolveDetected(DyeColor color, boolean enderChest) {
        return resolveDetected(color, enderChest, null);
    }

    public static synchronized Panel resolveDetected(DyeColor color, boolean enderChest, String containerName) {
        return resolveCached(color, enderChest, containerName, BetterShulkerConfig.ResourcePackMode.AUTO);
    }

    /** Returns whether an external resource pack currently supplies a Shulker GUI asset. */
    public static synchronized boolean isResourcePackGuiActive() {
        ensureCacheForCurrentResources();
        if (cachedGuiActive == null) {
            if (hasExternalResource(SHULKER_PANEL) || hasExternalShulkerDefinition()) {
                cachedGuiActive = true;
            } else {
                Identifier genericTexture = findShulkerTexture(null, null);
                cachedGuiActive = genericTexture != null && hasExternalResource(genericTexture);
                if (!cachedGuiActive) {
                    for (DyeColor color : DyeColor.values()) {
                        Identifier dyedTexture = findShulkerTexture(color, null);
                        if (dyedTexture != null && hasExternalResource(dyedTexture)) {
                            cachedGuiActive = true;
                            break;
                        }
                    }
                }
            }
        }
        return cachedGuiActive;
    }

    /** Clears all pack-derived state after a client resource reload. */
    public static synchronized void clearCache() {
        PANEL_CACHE.clear();
        cachedGuiActive = null;
        cachedResourceManager = null;
        ResourcePackLayoutProfiles.clearCache();
    }

    private static Panel resolveCached(DyeColor color, boolean enderChest, String containerName,
                                       BetterShulkerConfig.ResourcePackMode mode) {
        ensureCacheForCurrentResources();
        ResolutionKey key = new ResolutionKey(
                color,
                enderChest,
                containerName,
                mode,
                BetterShulkerConfig.getResourcePackLayoutOffsetX(),
                BetterShulkerConfig.getResourcePackLayoutOffsetY(),
                BetterShulkerConfig.getResourcePackLayoutBottomCapHeight()
        );
        Panel cached = PANEL_CACHE.get(key);
        if (cached != null) return cached;

        Panel resolved = resolveUncached(color, enderChest, containerName, mode);
        PANEL_CACHE.put(key, resolved);
        return resolved;
    }

    private static void ensureCacheForCurrentResources() {
        Object resourceManager = Minecraft.getInstance().getResourceManager();
        if (cachedResourceManager != resourceManager) {
            PANEL_CACHE.clear();
            cachedGuiActive = null;
            cachedResourceManager = resourceManager;
            ResourcePackLayoutProfiles.clearCache();
        }
    }

    private static Panel resolveUncached(DyeColor color, boolean enderChest, String containerName,
                                         BetterShulkerConfig.ResourcePackMode mode) {
        if (enderChest) {
            // Keep the established Better Shulker Ender presentation independent of resource packs.
            ResourcePackLayout layout = ResourcePackLayout.standard();
            return new Panel(GENERIC_54_PANEL, SHULKER_PANEL, false, false,
                    layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
        }

        if (mode == BetterShulkerConfig.ResourcePackMode.DISABLED) {
            // Keep the normal tooltip geometry when the user turns resource-pack mode off.
            ResourcePackLayout layout = ResourcePackLayout.standard();
            return new Panel(SHULKER_PANEL, SHULKER_PANEL, false, false,
                    layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
        }

        Identifier customTexture = findShulkerTexture(color, containerName);
        boolean customGuiFound = customTexture != null && hasExternalResource(customTexture);
        boolean packGuiFound = customGuiFound || hasExternalResource(SHULKER_PANEL)
                || (color == null && hasExternalShulkerDefinition());
        Identifier texture = customTexture != null ? customTexture : SHULKER_PANEL;
        boolean useResourcePackLayout = mode == BetterShulkerConfig.ResourcePackMode.ENABLED || packGuiFound;
        ResourcePackLayout layout = ResourcePackLayoutProfiles.resolve(texture);
        return new Panel(texture, texture, useResourcePackLayout, customGuiFound,
                layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
    }

    private static Identifier findShulkerTexture(DyeColor color, String containerName) {
        Identifier propertyTexture = findTextureFromCustomGuiProperties(false, color, containerName);
        if (propertyTexture != null) return propertyTexture;
        Identifier optiGuiTexture = findTextureFromOptiGuiIni(color);
        if (optiGuiTexture != null) return optiGuiTexture;
        if (color == null) {
            return firstAvailable("optigui", List.of(
                    "gui/shulkers/shulker/shulker.png",
                    "gui/shulkers/shulker/shulker_box.png"
            ));
        }

        String dye = color.getName();
        Identifier optiGuiDirectTexture = firstAvailable("optigui", List.of(
                "gui/shulkers/" + dye + "/" + dye + ".png",
                "gui/shulkers/" + dye + "/" + dye + "_shulker_box.png"
        ));
        if (optiGuiDirectTexture != null) return optiGuiDirectTexture;

        return firstAvailable(List.of(
                "optifine/gui/container/shulker_box/" + dye + ".png",
                "optifine/gui/container/shulker_box/" + dye + "_shulker_box.png",
                "optifine/gui/container/shulker_box/shulker_box_" + dye + ".png",
                "optifine/gui/container/shulker/" + dye + ".png",
                "textures/gui/container/shulker_box/" + dye + ".png",
                "textures/gui/container/shulker_box/" + dye + "_shulker_box.png",
                "textures/gui/container/shulker/" + dye + ".png"
        ));
    }

    /**
     * Reads OptiGUI's section-based replacement files, for example:
     * <pre>
     * [purple_shulker_box]
     * replacement = purple.png
     * </pre>
    */
    private static Identifier findTextureFromOptiGuiIni(DyeColor color) {
        List<String> sectionNames = new ArrayList<>();
        if (color != null) {
            // Packs often define one generic section for every dyed Shulker Box instead of
            // duplicating sixteen color sections. Try the exact section first, then the generic
            // fallback so the active pack still controls the panel.
            sectionNames.add(color.getName() + "_shulker_box");
        }
        sectionNames.add("shulker_box");
        try {
            var resources = Minecraft.getInstance().getResourceManager().listResources(
                    "gui/shulkers", id -> "optigui".equals(id.getNamespace())
                            && id.getPath().endsWith(".ini"));
            List<Map.Entry<Identifier, Resource>> entries = new ArrayList<>(resources.entrySet());
            entries.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
            for (String sectionName : sectionNames) {
                for (Map.Entry<Identifier, Resource> entry : entries) {
                    Identifier texture = textureFromOptiGuiIni(entry.getKey(), entry.getValue(), sectionName);
                    if (texture != null) return texture;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Identifier textureFromOptiGuiIni(Identifier iniId, Resource resource, String sectionName) {
        try (InputStream input = resource.open();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            boolean matchingSection = false;
            String replacement = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#") || value.startsWith(";")) continue;

                if (value.startsWith("[") && value.endsWith("]")) {
                    matchingSection = sectionName.equalsIgnoreCase(
                            value.substring(1, value.length() - 1).trim());
                    replacement = null;
                    continue;
                }
                if (!matchingSection) continue;

                int separator = value.indexOf('=');
                if (separator <= 0) continue;
                String key = value.substring(0, separator).trim();
                String candidate = value.substring(separator + 1).trim();
                if ("replacement".equalsIgnoreCase(key) || "texture".equalsIgnoreCase(key)) {
                    replacement = candidate;
                }
            }
            return replacement == null || replacement.isBlank()
                    ? null
                    : resolveTexture(iniId, replacement);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reads standard OptiFine Custom GUI definitions from the active resource stack. This supports
     * packs whose texture names are custom rather than one of the common file-name conventions.
     */
    private static Identifier findTextureFromCustomGuiProperties(boolean enderChest, DyeColor color,
                                                                 String containerName) {
        try {
            var resources = Minecraft.getInstance().getResourceManager().listResources(
                    "optifine/gui/container", id -> id.getPath().endsWith(".properties"));
            return resources.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                    .map(entry -> textureFromProperties(entry.getKey(), entry.getValue(), enderChest, color, containerName))
                    .filter(texture -> texture != null)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Identifier textureFromProperties(Identifier propertiesId, Resource resource,
                                                     boolean enderChest, DyeColor color,
                                                     String containerName) {
        try {
            Properties properties = new Properties();
            try (InputStream input = resource.open()) {
                properties.load(input);
            }
            if (!matchesContainer(properties, enderChest, color, containerName)) return null;

            String texture = properties.getProperty("texture");
            if (texture == null || texture.isBlank()) return null;
            return resolveTexture(propertiesId, texture);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matchesContainer(Properties properties, boolean enderChest, DyeColor color,
                                            String containerName) {
        String container = properties.getProperty("container", "").trim();
        if (enderChest || !"shulker_box".equalsIgnoreCase(container)) return false;

        String nameRule = properties.getProperty("name", "").trim();
        if (!nameRule.isEmpty()
                && (containerName == null || !matchesNameRule(nameRule, containerName))) return false;
        String colors = properties.getProperty("colors", "").trim();
        // An Ender Chest has no dye colour, so it may use only the pack's generic Shulker rule.
        if (color == null) return colors.isEmpty();
        if (colors.isEmpty()) return true;
        for (String candidate : colors.split("[\\s,]+")) {
            if (color.getName().equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static boolean matchesNameRule(String rule, String containerName) {
        try {
            if (Pattern.matches(rule, containerName)) return true;
        } catch (RuntimeException ignored) {
            // Some packs use a simple wildcard instead of a Java-compatible expression.
        }
        String normalizedRule = rule.toLowerCase(java.util.Locale.ROOT)
                .replace("*", ".*");
        try {
            return containerName.toLowerCase(java.util.Locale.ROOT).matches(normalizedRule);
        } catch (RuntimeException ignored) {
            return containerName.equalsIgnoreCase(rule);
        }
    }

    private static Identifier resolveTexture(Identifier propertiesId, String textureValue) {
        String raw = textureValue.trim().replace('\\', '/');
        if (raw.isEmpty()) return null;
        if (!raw.endsWith(".png")) raw += ".png";

        String namespace = propertiesId.getNamespace();
        String path = raw;
        int namespaceSeparator = raw.indexOf(':');
        if (namespaceSeparator > 0) {
            namespace = raw.substring(0, namespaceSeparator);
            path = raw.substring(namespaceSeparator + 1);
        }

        if (path.startsWith("assets/")) {
            String assetPath = path.substring("assets/".length());
            int assetNamespaceSeparator = assetPath.indexOf('/');
            if (assetNamespaceSeparator <= 0) return null;
            namespace = assetPath.substring(0, assetNamespaceSeparator);
            path = assetPath.substring(assetNamespaceSeparator + 1);
        }

        path = path.replace("./", "");
        if (path.startsWith("/")) path = path.substring(1);
        if (path.contains("..") || path.contains(":")) return null;

        String baseDirectory = propertiesId.getPath();
        int lastSlash = baseDirectory.lastIndexOf('/');
        baseDirectory = lastSlash >= 0 ? baseDirectory.substring(0, lastSlash + 1) : "";

        List<String> candidates = new ArrayList<>();
        if (!raw.startsWith("/") && namespaceSeparator < 0 && !path.startsWith("assets/")) {
            candidates.add(baseDirectory + path);
        }
        if (path.startsWith("textures/") || path.startsWith("optifine/")) {
            candidates.add(path);
        } else {
            if (path.startsWith("gui/") || path.startsWith("container/")) {
                candidates.add("optifine/" + path);
                candidates.add("textures/" + path);
            }
            candidates.add(path);
        }

        for (String candidate : candidates) {
            Identifier resolved = createIdentifier(namespace, candidate);
            if (resolved != null && hasResource(resolved)) return resolved;
        }
        return null;
    }

    private static Identifier createIdentifier(String namespace, String path) {
        try {
            return Identifier.fromNamespaceAndPath(namespace, path);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Identifier firstAvailable(List<String> paths) {
        return firstAvailable("minecraft", paths);
    }

    private static Identifier firstAvailable(String namespace, List<String> paths) {
        for (String path : paths) {
            Identifier texture = createIdentifier(namespace, path);
            if (texture != null && hasResource(texture)) return texture;
        }
        return null;
    }

    private static boolean hasResource(Identifier texture) {
        try {
            return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasExternalResource(Identifier texture) {
        try {
            return Minecraft.getInstance().getResourceManager().getResourceStack(texture).stream()
                    .anyMatch(ResourcePackContainerTextures::isExternalPackResource);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasExternalShulkerDefinition() {
        try {
            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean optiGui = resourceManager.listResources("gui/shulkers", id ->
                            "optigui".equals(id.getNamespace())
                                    && (id.getPath().endsWith(".ini") || id.getPath().endsWith(".png")))
                    .keySet().stream()
                    .anyMatch(ResourcePackContainerTextures::hasExternalResource);
            if (optiGui) return true;

            return resourceManager.listResources("optifine/gui/container", id -> {
                        String path = id.getPath();
                        return path.contains("/shulker_box/")
                                && (path.endsWith(".properties") || path.endsWith(".png"));
                    }).keySet().stream()
                    .anyMatch(ResourcePackContainerTextures::hasExternalResource);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isExternalPackResource(Resource resource) {
        try {
            Pack pack = Minecraft.getInstance().getResourcePackRepository().getPack(resource.sourcePackId());
            return pack != null && pack.getPackSource() != PackSource.BUILT_IN;
        } catch (Exception ignored) {
            return false;
        }
    }
}

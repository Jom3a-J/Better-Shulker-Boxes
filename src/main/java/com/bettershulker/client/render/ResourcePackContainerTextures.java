package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.DyeColor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves the GUI panel a resource pack supplies for Better Shulker previews.
 *
 * <p>The vanilla texture is always the safe base because it is what Minecraft renders when a
 * pack directly replaces a normal Shulker GUI. Dye- and name-specific panels come from the pack's
 * OptiFine Custom GUI and OptiGUI definitions, parsed by {@link ShulkerGuiRules}. Ender previews
 * intentionally retain Better Shulker's own theme rather than inheriting a placed-container
 * resource-pack GUI.</p>
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
     * resolution, including the one for the box about to be hovered again. Dropping only the
     * coldest entry keeps the boxes actually in use resolved.</p>
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
    /** Every Shulker definition in the active packs, in the order they are tried. */
    private static List<ResolvedRule> cachedRules;

    /**
     * Whether OptiGUI is installed, which decides how a definition without {@code colors} reads.
     * Set once by the Fabric entrypoint; OptiGUI has no NeoForge build.
     */
    private static volatile boolean optiGuiLoaded;

    private ResourcePackContainerTextures() {
    }

    /** A complete three-row Shulker panel plus the profile used to recompose its texture. */
    public record Panel(Identifier texture, Identifier bottomCapTexture, boolean suppliedByPack,
                        boolean exactCustomGui, int bottomCapSourceY, int bottomCapHeight,
                        ResourcePackLayout layout) {
    }

    private record ResolutionKey(DyeColor color, boolean enderChest, String screenTitle,
                                 BetterShulkerConfig.ResourcePackMode mode,
                                 int offsetX, int offsetY, int capHeight) {
    }

    /** A definition whose texture was found, and whether it came from a pack the player added. */
    private record ResolvedRule(ShulkerGuiRules.Rule rule, Identifier texture, boolean external) {
    }

    public static void setOptiGuiLoaded(boolean loaded) {
        optiGuiLoaded = loaded;
    }

    public static synchronized Panel resolve(DyeColor color, boolean enderChest) {
        return resolve(color, enderChest, null);
    }

    /**
     * Resolves a panel while allowing name-based OptiFine and OptiGUI rules to be evaluated.
     *
     * @param screenTitle the title the box's own screen would show - see
     *                    {@link ShulkerTooltipData#screenTitleOf} - or null for an unnamed box
     */
    public static synchronized Panel resolve(DyeColor color, boolean enderChest, String screenTitle) {
        return resolveCached(color, enderChest, screenTitle, BetterShulkerConfig.getResourcePackMode());
    }

    /** Resolves only the currently active external resource-pack GUI, ignoring the saved mode. */
    public static synchronized Panel resolveDetected(DyeColor color, boolean enderChest) {
        return resolveDetected(color, enderChest, null);
    }

    public static synchronized Panel resolveDetected(DyeColor color, boolean enderChest, String screenTitle) {
        return resolveCached(color, enderChest, screenTitle, BetterShulkerConfig.ResourcePackMode.AUTO);
    }

    /** Returns whether an external resource pack currently supplies a Shulker GUI asset. */
    public static synchronized boolean isResourcePackGuiActive() {
        ensureCacheForCurrentResources();
        if (cachedGuiActive == null) {
            if (hasExternalResource(SHULKER_PANEL) || hasExternalShulkerDefinition()) {
                cachedGuiActive = true;
            } else {
                Identifier genericTexture = guessShulkerTexture(null);
                cachedGuiActive = genericTexture != null && hasExternalResource(genericTexture);
                if (!cachedGuiActive) {
                    for (DyeColor color : DyeColor.values()) {
                        Identifier dyedTexture = guessShulkerTexture(color);
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
        cachedRules = null;
        cachedResourceManager = null;
        ResourcePackLayoutProfiles.clearCache();
    }

    private static Panel resolveCached(DyeColor color, boolean enderChest, String screenTitle,
                                       BetterShulkerConfig.ResourcePackMode mode) {
        ensureCacheForCurrentResources();
        ResolutionKey key = new ResolutionKey(
                color,
                enderChest,
                screenTitle != null ? screenTitle : defaultScreenTitle(),
                mode,
                BetterShulkerConfig.getResourcePackLayoutOffsetX(),
                BetterShulkerConfig.getResourcePackLayoutOffsetY(),
                BetterShulkerConfig.getResourcePackLayoutBottomCapHeight()
        );
        Panel cached = PANEL_CACHE.get(key);
        if (cached != null) return cached;

        Panel resolved = resolveUncached(color, enderChest, key.screenTitle(), mode);
        PANEL_CACHE.put(key, resolved);
        return resolved;
    }

    /** What an unnamed box's screen is titled in the current language; packs can blank it. */
    private static String defaultScreenTitle() {
        return Component.translatable("container.shulkerBox").getString();
    }

    private static void ensureCacheForCurrentResources() {
        Object resourceManager = Minecraft.getInstance().getResourceManager();
        if (cachedResourceManager != resourceManager) {
            PANEL_CACHE.clear();
            cachedGuiActive = null;
            cachedRules = null;
            cachedResourceManager = resourceManager;
            ResourcePackLayoutProfiles.clearCache();
        }
    }

    private static Panel resolveUncached(DyeColor color, boolean enderChest, String screenTitle,
                                         BetterShulkerConfig.ResourcePackMode mode) {
        if (enderChest) {
            // Keep the established Better Shulker Ender presentation independent of resource packs.
            ResourcePackLayout layout = ResourcePackLayout.standard();
            return new Panel(GENERIC_54_PANEL, SHULKER_PANEL, false, false,
                    layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
        }

        if (mode == BetterShulkerConfig.ResourcePackMode.DISABLED) {
            if (ResourcePackPanelColors.isMostlyTransparent(SHULKER_PANEL)) return vanillaFallback();
            // Keep the normal tooltip geometry when the user turns resource-pack mode off.
            ResourcePackLayout layout = ResourcePackLayout.standard();
            return new Panel(SHULKER_PANEL, SHULKER_PANEL, false, false,
                    layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
        }

        Identifier customTexture = findShulkerTexture(color, screenTitle);
        Identifier texture = customTexture != null ? customTexture : SHULKER_PANEL;
        // Some packs ship a panel that is not one: Immersive Interfaces leaves most of it clear
        // for its core shaders to paint. Recomposing that draws holes, so use vanilla's instead.
        if (ResourcePackPanelColors.isMostlyTransparent(texture)) return vanillaFallback();

        boolean customGuiFound = customTexture != null && hasExternalResource(customTexture);
        boolean packGuiFound = customGuiFound || hasExternalResource(SHULKER_PANEL)
                || (color == null && hasExternalShulkerDefinition());
        boolean useResourcePackLayout = mode == BetterShulkerConfig.ResourcePackMode.ENABLED || packGuiFound;
        ResourcePackLayout layout = ResourcePackLayoutProfiles.resolve(texture);
        return new Panel(texture, texture, useResourcePackLayout, customGuiFound,
                layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
    }

    /** The bottom-most vanilla panel, as Better Shulker draws when no pack is involved. */
    private static Panel vanillaFallback() {
        Identifier vanilla = VanillaTooltipTextures.shulker();
        ResourcePackLayout layout = ResourcePackLayout.standard();
        return new Panel(vanilla, vanilla, false, false,
                layout.bottomCapSourceY(), layout.bottomCapHeight(), layout);
    }

    private static Identifier findShulkerTexture(DyeColor color, String screenTitle) {
        String boxId = ShulkerGuiRules.boxId(color);
        for (ResolvedRule resolved : rules()) {
            if (resolved.rule().appliesTo(boxId, screenTitle)) return resolved.texture();
        }
        // A guessed file that a definition points at belongs to that definition's conditions: a
        // purple.png kept for boxes named "Loot" must not turn up on every other purple box.
        Identifier guess = guessShulkerTexture(color);
        if (guess != null && rules().stream().anyMatch(resolved -> resolved.texture().equals(guess))) return null;
        return guess;
    }

    /**
     * Common file names, for packs that ship per-dye panels without a definition pointing at them.
     * Every surveyed pack ships definitions, so this is only reached when none applies.
     */
    private static Identifier guessShulkerTexture(DyeColor color) {
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

        return firstAvailable("minecraft", List.of(
                "optifine/gui/container/shulker_box/" + dye + ".png",
                "optifine/gui/container/shulker_box/" + dye + "_shulker_box.png",
                "optifine/gui/container/shulker_box/shulker_box_" + dye + ".png",
                "optifine/gui/container/shulker/" + dye + ".png",
                "textures/gui/container/shulker_box/" + dye + ".png",
                "textures/gui/container/shulker_box/" + dye + "_shulker_box.png",
                "textures/gui/container/shulker/" + dye + ".png"
        ));
    }

    // =========================================================================
    //  Definition index
    // =========================================================================

    private static List<ResolvedRule> rules() {
        if (cachedRules == null) cachedRules = loadRules();
        return cachedRules;
    }

    /**
     * Parses every Shulker definition once per resource reload and puts them in trying order.
     *
     * <p>Each mod puts its own format first. Under OptiGUI that is INI sections, then OptiFine
     * files, each ordered by {@code load.priority} and then path. OptiFine reads only its own
     * files, top pack first and then by path, so INI sections are left for last. A definition
     * whose texture is missing is dropped, as both mods drop it.</p>
     */
    private static List<ResolvedRule> loadRules() {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        boolean optiGui = optiGuiLoaded;
        Map<String, Integer> packOrder = packOrder(resourceManager);

        record Candidate(ShulkerGuiRules.Rule rule, Resource definition, int order) {}
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;
        try {
            for (Map.Entry<Identifier, Resource> entry : sorted(resourceManager.listResources("gui",
                    id -> "optigui".equals(id.getNamespace()) && id.getPath().endsWith(".ini")))) {
                try (InputStream input = entry.getValue().open();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    for (ShulkerGuiRules.Rule rule : ShulkerGuiRules.parseIni(entry.getKey(), reader)) {
                        candidates.add(new Candidate(rule, entry.getValue(), order++));
                    }
                } catch (Exception ignored) {
                    // One unreadable file should not cost the pack its other definitions.
                }
            }
            for (Map.Entry<Identifier, Resource> entry : sorted(resourceManager.listResources(
                    "optifine/gui/container", id -> id.getPath().endsWith(".properties")))) {
                try (InputStream input = entry.getValue().open()) {
                    Properties properties = new Properties();
                    properties.load(input);
                    ShulkerGuiRules.Rule rule = ShulkerGuiRules.parseProperties(entry.getKey(), properties, optiGui);
                    if (rule != null) candidates.add(new Candidate(rule, entry.getValue(), order++));
                } catch (Exception ignored) {
                    // As above.
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }

        Comparator<Candidate> trying = Comparator
                .comparingInt((Candidate c) -> c.rule().ini() == optiGui ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Candidate c) -> c.rule().priority()).reversed());
        if (!optiGui) {
            // OptiFine walks packs from the top; OptiGUI ignores which pack a file came from.
            trying = trying.thenComparing(Comparator.comparingInt((Candidate c) ->
                    -packOrder.getOrDefault(c.definition().sourcePackId(), 0)));
        }
        candidates.sort(trying.thenComparingInt(Candidate::order));

        List<ResolvedRule> rules = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Identifier texture = firstAvailable(candidate.rule().textures());
            if (texture == null) continue;
            boolean external = isExternalPackResource(candidate.definition()) || hasExternalResource(texture);
            rules.add(new ResolvedRule(candidate.rule(), texture, external));
        }
        return List.copyOf(rules);
    }

    private static List<Map.Entry<Identifier, Resource>> sorted(Map<Identifier, Resource> resources) {
        List<Map.Entry<Identifier, Resource>> entries = new ArrayList<>(resources.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        return entries;
    }

    /** Position of each pack in the stack, bottom first, so a higher number wins. */
    private static Map<String, Integer> packOrder(ResourceManager resourceManager) {
        Map<String, Integer> order = new HashMap<>();
        try {
            List<PackResources> packs = resourceManager.listPacks().toList();
            for (int i = 0; i < packs.size(); i++) order.put(packs.get(i).packId(), i);
        } catch (Exception ignored) {
            // Without an order every file ties and path order decides, which is OptiGUI's rule.
        }
        return order;
    }

    // =========================================================================
    //  Resource lookups
    // =========================================================================

    private static Identifier firstAvailable(String namespace, List<String> paths) {
        List<Identifier> textures = new ArrayList<>();
        for (String path : paths) {
            try {
                textures.add(Identifier.fromNamespaceAndPath(namespace, path));
            } catch (IllegalArgumentException ignored) {
                // Not a valid identifier, so no pack can supply it.
            }
        }
        return firstAvailable(textures);
    }

    private static Identifier firstAvailable(List<Identifier> textures) {
        for (Identifier texture : textures) {
            if (hasResource(texture)) return texture;
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

    /** Whether a player-added pack defines any Shulker panel, whatever folder it keeps it in. */
    private static boolean hasExternalShulkerDefinition() {
        return rules().stream().anyMatch(ResolvedRule::external);
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

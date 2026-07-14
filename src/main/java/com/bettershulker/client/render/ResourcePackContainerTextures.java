package com.bettershulker.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.DyeColor;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Resolves the GUI panel a resource pack supplies for Better Shulker previews.
 *
 * <p>The vanilla texture is always the safe base because it is what Minecraft renders when a
 * pack directly replaces a normal Shulker GUI. Packs that ship conventional OptiFine Custom GUI
 * files are resolved for dye-specific Shulker panels. Ender previews intentionally retain Better
 * Shulker's own theme rather than inheriting a placed-container resource-pack GUI.</p>
 */
public final class ResourcePackContainerTextures {
    private static final Identifier SHULKER_PANEL = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final Identifier GENERIC_54_PANEL = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    private ResourcePackContainerTextures() {
    }

    /** A complete three-row Shulker panel plus the source of its matching lower cap. */
    public record Panel(Identifier texture, Identifier bottomCapTexture, boolean suppliedByPack,
                        boolean exactCustomGui, int bottomCapSourceY, int bottomCapHeight) {
    }

    public static Panel resolve(DyeColor color, boolean enderChest) {
        if (enderChest) {
            // Keep the established Better Shulker Ender presentation independent of resource packs.
            return new Panel(GENERIC_54_PANEL, SHULKER_PANEL, false, false, 72, 5);
        }

        Identifier customTexture = findShulkerTexture(color);
        Identifier texture = customTexture != null ? customTexture : SHULKER_PANEL;
        return new Panel(texture, texture, customTexture != null || hasPackOverride(SHULKER_PANEL),
                customTexture != null, 72, 5);
    }

    private static Identifier findShulkerTexture(DyeColor color) {
        Identifier propertyTexture = findTextureFromCustomGuiProperties(false, color);
        if (propertyTexture != null) return propertyTexture;
        if (color == null) return null;

        String dye = color.getName();
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
     * Reads standard OptiFine Custom GUI definitions from the active resource stack. This supports
     * packs whose texture names are custom rather than one of the common file-name conventions.
     */
    private static Identifier findTextureFromCustomGuiProperties(boolean enderChest, DyeColor color) {
        try {
            var resources = Minecraft.getInstance().getResourceManager().listResources(
                    "optifine/gui/container", id -> id.getPath().endsWith(".properties"));
            return resources.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                    .map(entry -> textureFromProperties(entry.getKey(), entry.getValue(), enderChest, color))
                    .filter(texture -> texture != null)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Identifier textureFromProperties(Identifier propertiesId, Resource resource,
                                                     boolean enderChest, DyeColor color) {
        try {
            Properties properties = new Properties();
            try (InputStream input = resource.open()) {
                properties.load(input);
            }
            if (!matchesContainer(properties, enderChest, color)) return null;

            String texture = properties.getProperty("texture");
            if (texture == null || texture.isBlank()) return null;
            Identifier resolved = resolveRelativeTexture(propertiesId, texture);
            return resolved != null && hasResource(resolved) ? resolved : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matchesContainer(Properties properties, boolean enderChest, DyeColor color) {
        String container = properties.getProperty("container", "").trim();
        if (enderChest || !"shulker_box".equals(container)) return false;

        String nameRule = properties.getProperty("name", "").trim();
        if (!nameRule.isEmpty()) return false;
        String colors = properties.getProperty("colors", "").trim();
        // An Ender Chest has no dye colour, so it may use only the pack's generic Shulker rule.
        if (color == null) return colors.isEmpty();
        if (colors.isEmpty()) return true;
        for (String candidate : colors.split("\\s+")) {
            if (color.getName().equals(candidate)) return true;
        }
        return false;
    }

    private static Identifier resolveRelativeTexture(Identifier propertiesId, String textureValue) {
        String texture = textureValue.trim();
        if (!texture.endsWith(".png")) texture += ".png";
        String baseDirectory = propertiesId.getPath().substring(0, propertiesId.getPath().lastIndexOf('/') + 1);
        String path = texture.startsWith("/") ? texture.substring(1) : baseDirectory + texture;
        path = path.replace("./", "");
        if (path.contains("..") || path.contains(":")) return null;
        return Identifier.withDefaultNamespace(path);
    }

    private static Identifier firstAvailable(List<String> paths) {
        for (String path : paths) {
            Identifier texture = Identifier.withDefaultNamespace(path);
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

    private static boolean hasPackOverride(Identifier texture) {
        try {
            return Minecraft.getInstance().getResourceManager().getResourceStack(texture).size() > 1;
        } catch (Exception ignored) {
            return false;
        }
    }
}

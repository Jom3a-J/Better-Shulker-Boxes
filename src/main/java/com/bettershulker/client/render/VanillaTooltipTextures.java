package com.bettershulker.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;

/**
 * Registers private copies of the bottom-most (vanilla) GUI textures for the established Ender
 * tooltip renderer. This leaves resource packs free to style Shulker previews without replacing
 * the mod's Ender theme.
 */
final class VanillaTooltipTextures {
    private static final Identifier VANILLA_GENERIC_54 = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final Identifier VANILLA_SHULKER = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");

    private static Identifier generic54;
    private static Identifier shulker;

    private VanillaTooltipTextures() {
    }

    static Identifier generic54() {
        if (generic54 == null) generic54 = registerVanillaCopy(VANILLA_GENERIC_54, "bettershulker_ender_panel");
        return generic54;
    }

    static Identifier shulker() {
        if (shulker == null) shulker = registerVanillaCopy(VANILLA_SHULKER, "bettershulker_ender_cap");
        return shulker;
    }

    private static Identifier registerVanillaCopy(Identifier source, String name) {
        try {
            Resource vanillaResource = Minecraft.getInstance().getResourceManager().getResourceStack(source).stream()
                    .filter(resource -> "vanilla".equals(resource.sourcePackId()))
                    .findFirst()
                    .orElse(null);
            if (vanillaResource == null) return source;

            try (InputStream input = vanillaResource.open()) {
                NativeImage image = NativeImage.read(input);
                Identifier dynamicId = Identifier.fromNamespaceAndPath("bettershulker", "dynamic/" + name);
                Minecraft.getInstance().getTextureManager().register(dynamicId, new DynamicTexture(() -> name, image));
                return dynamicId;
            }
        } catch (Exception ignored) {
            // The normal Minecraft texture remains a safe fallback if a client reload is in progress.
            return source;
        }
    }
}

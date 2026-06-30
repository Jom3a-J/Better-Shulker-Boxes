package com.bettershulker.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * ModMenu api integration for Better Shulker.
 * Integrates the custom settings screen directly into ModMenu's config list.
 */
public class BetterShulkerModMenuIntegration implements ModMenuApi {

    /**
     * Registers the custom config settings screen factory for ModMenu.
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BetterShulkerClothConfigScreen::create;
    }
}

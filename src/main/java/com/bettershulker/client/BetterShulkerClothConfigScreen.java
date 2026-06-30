package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Cloth Config-powered settings screen for Better Shulker Boxes.
 */
public final class BetterShulkerClothConfigScreen {
    private static final Identifier PREVIEW_SHULKER_PANEL_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final float PREVIEW_PANEL_TEXTURE_U = 0.0F;
    private static final float PREVIEW_PANEL_TEXTURE_V = 11.0F;

    private BetterShulkerClothConfigScreen() {}

    public static Screen create(Screen parent) {
        CustomPreviewState previewState = new CustomPreviewState();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(text("Better Shulker Settings"))
                .setSavingRunnable(BetterShulkerConfig::save)
                .setAfterInitConsumer(screen -> addFixedCustomThemePreview(screen, previewState))
                .setDoesConfirmSave(false);

        ConfigEntryBuilder entry = builder.entryBuilder();

        addGeneralCategory(builder, entry);
        addVisualsCategory(builder, entry);
        addAudioCategory(builder, entry);
        addThemeCategory(builder, entry, previewState);
        addControlsCategory(builder, entry);

        return builder.build();
    }

    private static void addFixedCustomThemePreview(Screen screen, CustomPreviewState previewState) {
        Renderable preview = (graphics, mouseX, mouseY, delta) -> drawFixedCustomThemePreview(graphics, screen, previewState);
        try {
            var method = Screen.class.getDeclaredMethod("addRenderableOnly", Renderable.class);
            method.setAccessible(true);
            method.invoke(screen, preview);
        } catch (Exception ignored) {
            // Preview is optional; never break the settings screen if reflection is blocked.
        }
    }

    private static void drawFixedCustomThemePreview(GuiGraphicsExtractor graphics, Screen screen, CustomPreviewState state) {
        int w = Math.min(300, Math.max(220, screen.width / 4));
        int x = screen.width - w - 12;
        boolean themeCategory = isThemeCategorySelected(screen);
        updateClothListBounds(screen, themeCategory ? x - 10 : screen.width - 32);
        if (!themeCategory || !state.ready()) return;

        var font = Minecraft.getInstance().font;
        int y = 38;
        int h = screen.height - 76;
        PreviewColors colors = resolvePreviewColors(state);
        int nameText = colors.nameText();
        int sel = colors.selection();

        // Integrated preview column: no separate dark modal/card background. Keep it visually part
        // of the Theme & Colors category and use only a subtle divider/title.
        graphics.fill(x - 10, y - 8, x - 9, y + h + 8, 0x55FFFFFF);
        graphics.centeredText(font, text("Live Theme Preview"), x + w / 2, y, 0xFFE6E6E6);

        int fullW = 176;
        int fullH = 68;
        int panelX = x + (w - fullW) / 2;
        int panelY = y + 22;
        drawFullThemePreviewPanel(graphics, colors, panelX, panelY, fullW, fullH, sel);

        int nameY = panelY + fullH + 14;
        drawSelectedNamePreview(graphics, font, colors, x, w, nameY, nameText);

        int compactW = 14 + 5 * 20;
        int compactH = 14 + 20;
        int compactX = x + (w - compactW) / 2;
        int compactY = nameY + 34;
        drawCompactThemePreview(graphics, colors, compactX, compactY, compactW, compactH, sel);
        graphics.text(font, text("64"), compactX + 9, compactY + 18, 0xFFFFFFFF);
    }

    private static void drawFullThemePreviewPanel(GuiGraphicsExtractor graphics, PreviewColors colors,
                                                  int panelX, int panelY, int fullW, int fullH, int sel) {
        if (colors.usesVanillaPanelTexture()) {
            graphics.blit(RenderPipelines.GUI_TEXTURED,
                    PREVIEW_SHULKER_PANEL_TEXTURE,
                    panelX,
                    panelY,
                    PREVIEW_PANEL_TEXTURE_U,
                    PREVIEW_PANEL_TEXTURE_V,
                    fullW,
                    fullH,
                    256,
                    256,
                    0xFFFFFFFF);
            // Match ShulkerTooltipComponent.drawThemeOverlay for the actual full tooltip.
            graphics.fill(panelX + 2, panelY + 2, panelX + fullW - 2, panelY + fullH - 2, colors.fullTint());
            graphics.fill(panelX + 7, panelY + 6, panelX + fullW - 7, panelY + 62, withAlphaStatic(colors.border(), 34));
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int sx = panelX + 8 + col * 18;
                    int sy = panelY + 7 + row * 18;
                    graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, withAlphaStatic(colors.border(), 26));
                }
            }
            int softHighlight = withAlphaStatic(blendColorStatic(colors.border(), 0xFFFFFFFF, 0.45f), 28);
            graphics.fill(panelX + 3, panelY + 3, panelX + fullW - 3, panelY + 5, softHighlight);
            graphics.fill(panelX + 3, panelY + 5, panelX + 5, panelY + fullH - 3, softHighlight);
        } else {
            graphics.fill(panelX, panelY, panelX + fullW, panelY + fullH, colors.background());
            drawStaticFrame(graphics, panelX, panelY, fullW, fullH, colors.border());
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int sx = panelX + 8 + col * 18;
                    int sy = panelY + 7 + row * 18;
                    graphics.fill(sx, sy, sx + 18, sy + 18, withAlphaStatic(colors.border(), 105));
                    graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xAA101010);
                }
            }
        }

        int selectedX = panelX + 8 + 3 * 18;
        int selectedY = panelY + 7 + 1 * 18;
        drawStaticFrame(graphics, selectedX, selectedY, 18, 18, sel);
        graphics.fill(selectedX + 1, selectedY + 1, selectedX + 17, selectedY + 17, withAlphaStatic(sel, 45));
    }

    private static void drawSelectedNamePreview(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                                                PreviewColors colors, int columnX, int columnW, int y, int nameText) {
        String name = "Diamond Pickaxe";
        int nameW = font.width(name) + 12;
        int nameX = columnX + (columnW - nameW) / 2;

        // Match the actual selected-name tab better: it is rendered as a vanilla-style tooltip,
        // with the theme only tinting the bridge/border and the configured name text color.
        int outer = withAlphaStatic(colors.nameBorder(), 230);
        int inner = blendColorStatic(colors.nameBackground(), 0xFF000000, 0.28f);
        int high = withAlphaStatic(blendColorStatic(colors.nameBorder(), 0xFFFFFFFF, 0.45f), 105);
        int low = withAlphaStatic(blendColorStatic(colors.nameBorder(), 0xFF000000, 0.55f), 150);
        graphics.fill(nameX, y, nameX + nameW, y + 14, outer);
        graphics.fill(nameX + 1, y + 1, nameX + nameW - 1, y + 13, inner);
        graphics.fill(nameX + 1, y + 1, nameX + nameW - 1, y + 2, high);
        graphics.fill(nameX + 1, y + 2, nameX + 2, y + 13, high);
        graphics.fill(nameX + 1, y + 12, nameX + nameW - 1, y + 13, low);
        graphics.fill(nameX + nameW - 2, y + 2, nameX + nameW - 1, y + 13, low);
        graphics.text(font, text(name), nameX + 6, y + 3, nameText);
    }

    private static void drawCompactThemePreview(GuiGraphicsExtractor graphics, PreviewColors colors,
                                                int x, int y, int panelW, int panelH, int sel) {
        int compactBase = colors.compactBase();
        boolean glass = colors.glassCompact();
        int bg = glass ? withAlphaStatic(compactBase, 170) : blendColorStatic(compactBase, 0xFF000000, 0.16f);
        int face = glass ? withAlphaStatic(blendColorStatic(compactBase, 0xFFFFFFFF, 0.18f), 92)
                : blendColorStatic(compactBase, 0xFFFFFFFF, 0.10f);
        int edge = withAlphaStatic(colors.border(), 245);
        int light = withAlphaStatic(blendColorStatic(colors.border(), 0xFFFFFFFF, 0.50f), 120);
        int shadow = withAlphaStatic(blendColorStatic(colors.border(), 0xFF000000, 0.55f), 170);

        // Match ShulkerTooltipComponent.drawCompactPanel for the compact preview.
        graphics.fill(x, y, x + panelW, y + panelH, bg);
        graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, face);
        graphics.fill(x, y, x + panelW, y + 1, light);
        graphics.fill(x, y + 1, x + 1, y + panelH, light);
        graphics.fill(x, y + panelH - 1, x + panelW, y + panelH, shadow);
        graphics.fill(x + panelW - 1, y, x + panelW, y + panelH, shadow);
        drawStaticFrame(graphics, x + 1, y + 1, panelW - 2, panelH - 2, edge);

        for (int i = 0; i < 5; i++) {
            int sx = x + 7 + i * 20;
            int sy = y + 7;
            drawCompactSlotPreview(graphics, sx, sy, compactBase, colors.border());
        }
        drawStaticFrame(graphics, x + 7 + 20, y + 7, 20, 20, sel);
        graphics.fill(x + 7 + 21, y + 8, x + 7 + 39, y + 26, withAlphaStatic(sel, 45));
    }

    private static void drawCompactSlotPreview(GuiGraphicsExtractor graphics, int slotX, int slotY, int baseColor, int borderColor) {
        boolean lightBase = getTextColorForBackgroundStatic(baseColor) == 0xFF373737;
        int outer = withAlphaStatic(blendColorStatic(borderColor, baseColor, 0.35f), 210);
        int inner = lightBase
                ? withAlphaStatic(blendColorStatic(baseColor, 0xFFFFFFFF, 0.12f), 238)
                : withAlphaStatic(blendColorStatic(baseColor, 0xFF000000, 0.50f), 238);
        int high = lightBase ? 0x80FFFFFF : 0x45FFFFFF;
        int low = lightBase ? 0x44000000 : 0x70000000;
        int size = 20;
        graphics.fill(slotX, slotY, slotX + size, slotY + size, outer);
        graphics.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1, inner);
        graphics.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + 2, high);
        graphics.fill(slotX + 1, slotY + 2, slotX + 2, slotY + size - 1, high);
        graphics.fill(slotX + 1, slotY + size - 2, slotX + size - 1, slotY + size - 1, low);
        graphics.fill(slotX + size - 2, slotY + 2, slotX + size - 1, slotY + size - 1, low);
    }

    private static PreviewColors resolvePreviewColors(CustomPreviewState state) {
        BetterShulkerConfig.TooltipTheme theme = state.theme == null || state.theme.getValue() == null
                ? BetterShulkerConfig.getTooltipTheme()
                : state.theme.getValue();
        int nameText = state.nameText.color();
        return switch (theme) {
            case ORIGINAL -> new PreviewColors(0xFF2B0B3A, 0xFF8932B8, 0xE0100018, 0xFF8932B8, 0xFFFFD700, nameText, 0x65100018, true, 0xFF6F2D8F, false);
            case CLASSIC -> new PreviewColors(0xFF2D4A1A, 0xFF4A7A25, 0xE02D4A1A, 0xFF4A7A25, 0xFFA7E060, nameText, 0x702D4A1A, true, 0xFF2D4A1A, false);
            case RETRO -> new PreviewColors(0xFF080812, 0xFFFF00FF, 0xE0080812, 0xFFFF00FF, 0xFF00FFFF, nameText, 0x70080812, true, 0xFF1A0028, false);
            case SOLARIZED_DARK -> new PreviewColors(0xFF002B36, 0xFF268BD2, 0xE0002B36, 0xFF268BD2, 0xFFB58900, nameText, 0x76002B36, true, 0xFF002B36, false);
            case SOLARIZED_LIGHT -> new PreviewColors(0xFFFDF6E3, 0xFF268BD2, 0xEEFDF6E3, 0xFF268BD2, 0xFFCB4B16, nameText, 0x88FDF6E3, true, 0xFFFDF6E3, false);
            case HIGH_CONTRAST -> new PreviewColors(0xFF000000, 0xFFFFAA00, 0xF0000000, 0xFFFFAA00, 0xFFFFFF00, nameText, 0x88000000, true, 0xFF000000, false);
            case GLASS -> new PreviewColors(0xDDEAF7FF, 0xB8FFFFFF, 0xDDEAF7FF, 0xB8FFFFFF, 0xFFFFD700, nameText, 0x32FFFFFF, false, 0xFFEAF7FF, true);
            case CUSTOM -> new PreviewColors(
                    state.background.color(),
                    state.border.color(),
                    state.nameBackground.color(),
                    state.nameBorder.color(),
                    state.selection.color(),
                    nameText,
                    normalizePreviewOverlayAlpha(state.background.color(), 112),
                    true,
                    0xFF000000 | (state.background.color() & 0x00FFFFFF),
                    false
            );
        };
    }

    private record PreviewColors(int background, int border, int nameBackground, int nameBorder, int selection,
                                 int nameText, int fullTint, boolean usesVanillaPanelTexture,
                                 int compactBase, boolean glassCompact) {}

    private static boolean isThemeCategorySelected(Screen screen) {
        try {
            Object selected = screen.getClass().getMethod("getSelectedCategory").invoke(screen);
            return selected instanceof Component component && "Theme & Colors".equals(component.getString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void updateClothListBounds(Screen screen, int right) {
        try {
            var listField = screen.getClass().getField("listWidget");
            Object list = listField.get(screen);
            var leftField = findField(list.getClass(), "left");
            var rightField = findField(list.getClass(), "right");
            var widthField = findField(list.getClass(), "width");
            leftField.setAccessible(true);
            rightField.setAccessible(true);
            widthField.setAccessible(true);
            int left = leftField.getInt(list);
            int newRight = Math.max(left + 260, right);
            rightField.setInt(list, newRight);
            widthField.setInt(list, newRight - left);
        } catch (Exception ignored) {
            // Keep the preview optional; never break settings if Cloth internals change.
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void drawStaticFrame(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static int withAlphaStatic(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static int normalizePreviewOverlayAlpha(int color, int fallbackAlpha) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0 || alpha == 255) alpha = fallbackAlpha;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int getTextColorForBackgroundStatic(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.65 ? 0xFF373737 : 0xFFFFFFFF;
    }

    private static int blendColorStatic(int colorA, int colorB, float factor) {
        int rA = (colorA >> 16) & 0xFF;
        int gA = (colorA >> 8) & 0xFF;
        int bA = colorA & 0xFF;
        int rB = (colorB >> 16) & 0xFF;
        int gB = (colorB >> 8) & 0xFF;
        int bB = colorB & 0xFF;
        int r = Math.round(rA + (rB - rA) * factor);
        int g = Math.round(gA + (gB - gA) * factor);
        int b = Math.round(bA + (bB - bA) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void addGeneralCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("General"));
        category.addEntry(entry.startBooleanToggle(text("Tooltip Preview"), BetterShulkerConfig.isTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setTooltipEnabled)
                .setTooltip(text("Show the 9x3 shulker/ender chest preview tooltip."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Precision Mode (Ctrl)"), BetterShulkerConfig.isPrecisionModeEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setPrecisionModeEnabled)
                .setTooltip(text("Use the precision key for one-item insert/extract actions."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Fill Indicator"), BetterShulkerConfig.isFillIndicatorEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setFillIndicatorEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Selection Square"), BetterShulkerConfig.isSecondaryTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSecondaryTooltipEnabled)
                .setTooltip(text("Show and control the selected slot highlight in the tooltip."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Alt Force Tooltip"), BetterShulkerConfig.isAltForceTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setAltForceTooltipEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Selected Item Name"), BetterShulkerConfig.isSelectedItemNameEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSelectedItemNameEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Compact Tooltips"), BetterShulkerConfig.isCompactTooltipEnabled())
                .setDefaultValue(false)
                .setSaveConsumer(BetterShulkerConfig::setCompactTooltipEnabled)
                .build());
    }

    private static void addVisualsCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Visuals & Animations"));
        category.addEntry(entry.startBooleanToggle(text("Selection Glide"), BetterShulkerConfig.isSelectionGlideEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSelectionGlideEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Hover Zoom & Glow"), BetterShulkerConfig.isHoverAnimationsEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setHoverAnimationsEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Rare Item Floating"), BetterShulkerConfig.isRareItemWobbleEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setRareItemWobbleEnabled)
                .build());
    }

    private static void addAudioCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Audio"));
        category.addEntry(entry.startIntSlider(text("Sound Volume"), Math.round(BetterShulkerConfig.getSoundVolume() * 100.0f), 0, 100)
                .setDefaultValue(30)
                .setTextGetter(value -> text(value + "%"))
                .setSaveConsumer(value -> BetterShulkerConfig.setSoundVolume(value / 100.0f))
                .setTooltip(text("0% = muted, 100% = full volume."))
                .build());
        category.addEntry(entry.startEnumSelector(text("Interaction Sound"), BetterShulkerConfig.SoundOption.class, BetterShulkerConfig.getSoundOption())
                .setDefaultValue(BetterShulkerConfig.SoundOption.ITEM_PICKUP)
                .setEnumNameProvider(value -> text(((BetterShulkerConfig.SoundOption) value).getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setSoundOption)
                .build());
    }

    private static void addThemeCategory(ConfigBuilder builder, ConfigEntryBuilder entry, CustomPreviewState previewState) {
        ConfigCategory category = builder.getOrCreateCategory(text("Theme & Colors"));
        previewState.theme = entry.startSelector(text("Tooltip Theme"), BetterShulkerConfig.TooltipTheme.values(), BetterShulkerConfig.getTooltipTheme())
                .setDefaultValue(BetterShulkerConfig.TooltipTheme.ORIGINAL)
                .setNameProvider(value -> text(value.getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setTooltipTheme)
                .setTooltip(text("Use the arrows to choose from the visible theme list."))
                .build();
        category.addEntry(previewState.theme);
        category.addEntry(entry.startTextDescription(text("The live preview on the right updates for every theme before you press Done. Selected Name Text applies to every theme; the other RGB sliders are used by Custom."))
                .build());

        previewState.background = addRgbSliders(category, entry, "Custom Background", BetterShulkerConfig.getCustomBackgroundColor(), 0xFF1A1A1A,
                BetterShulkerConfig::getCustomBackgroundColor, BetterShulkerConfig::setCustomBackgroundColor);
        previewState.border = addRgbSliders(category, entry, "Custom Border", BetterShulkerConfig.getCustomBorderColor(), 0xFF8932B8,
                BetterShulkerConfig::getCustomBorderColor, BetterShulkerConfig::setCustomBorderColor);
        previewState.nameBackground = addRgbSliders(category, entry, "Selected Name Background", BetterShulkerConfig.getCustomNameBgColor(), 0xF0100010,
                BetterShulkerConfig::getCustomNameBgColor, BetterShulkerConfig::setCustomNameBgColor);
        previewState.nameBorder = addRgbSliders(category, entry, "Selected Name Border", BetterShulkerConfig.getCustomNameBorderColor(), 0xFF8932B8,
                BetterShulkerConfig::getCustomNameBorderColor, BetterShulkerConfig::setCustomNameBorderColor);
        previewState.nameText = addRgbSliders(category, entry, "Selected Name Text", BetterShulkerConfig.getCustomNameTextColor(), 0xFFFFFFFF,
                BetterShulkerConfig::getCustomNameTextColor, BetterShulkerConfig::setCustomNameTextColor);
        previewState.selection = addRgbSliders(category, entry, "Selection Square", BetterShulkerConfig.getCustomSelectionSquareColor(), 0xFFFFD700,
                BetterShulkerConfig::getCustomSelectionSquareColor, BetterShulkerConfig::setCustomSelectionSquareColor);
    }

    private static void addControlsCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Controls"));
        category.addEntry(entry.fillKeybindingField(text("Open Settings"), BetterShulkerClient.getSettingsKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Extract Selected Slots"), BetterShulkerClient.getExtractKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Select Tooltip Slot"), BetterShulkerClient.getSelectSlotKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Filter Item"), BetterShulkerClient.getFilterKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Precision Mode"), BetterShulkerClient.getPrecisionKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Alt Force Tooltip"), BetterShulkerClient.getAltForceKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Selection Left"), BetterShulkerClient.getScrollLeftKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Selection Right"), BetterShulkerClient.getScrollRightKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Restock / Deposit"), BetterShulkerClient.getRestockKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Wireless Ender Chest"), BetterShulkerClient.getWirelessEnderChestKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Show Full Tooltip"), BetterShulkerClient.getShowFullTooltipKey()).build());
    }

    private static ColorSliders addRgbSliders(ConfigCategory category, ConfigEntryBuilder entry, String label, int currentColor, int defaultColor,
                                              java.util.function.IntSupplier currentSupplier,
                                              java.util.function.IntConsumer saveConsumer) {
        var sub = entry.startSubCategory(text(label));
        IntegerSliderEntry red = entry.startIntSlider(text("Red"), red(currentColor), 0, 255)
                .setDefaultValue(red(defaultColor))
                .setTextGetter(value -> text("R: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceRed(currentSupplier.getAsInt(), value)))
                .build();
        IntegerSliderEntry green = entry.startIntSlider(text("Green"), green(currentColor), 0, 255)
                .setDefaultValue(green(defaultColor))
                .setTextGetter(value -> text("G: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceGreen(currentSupplier.getAsInt(), value)))
                .build();
        IntegerSliderEntry blue = entry.startIntSlider(text("Blue"), blue(currentColor), 0, 255)
                .setDefaultValue(blue(defaultColor))
                .setTextGetter(value -> text("B: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceBlue(currentSupplier.getAsInt(), value)))
                .build();
        sub.add(red);
        sub.add(green);
        sub.add(blue);
        category.addEntry(sub.setExpanded(false).build());
        return new ColorSliders(red, green, blue);
    }

    private static final class CustomPreviewState {
        private AbstractConfigListEntry<BetterShulkerConfig.TooltipTheme> theme;
        private ColorSliders background;
        private ColorSliders border;
        private ColorSliders nameBackground;
        private ColorSliders nameBorder;
        private ColorSliders nameText;
        private ColorSliders selection;

        private boolean ready() {
            return theme != null && background != null && border != null && nameBackground != null && nameBorder != null && nameText != null && selection != null;
        }
    }

    private record ColorSliders(IntegerSliderEntry red, IntegerSliderEntry green, IntegerSliderEntry blue) {
        int color() {
            return 0xFF000000 | (red.getValue() << 16) | (green.getValue() << 8) | blue.getValue();
        }
    }

    private static int red(int color) { return (color >> 16) & 0xFF; }
    private static int green(int color) { return (color >> 8) & 0xFF; }
    private static int blue(int color) { return color & 0xFF; }
    private static int alpha(int color) { return color & 0xFF000000; }
    private static int replaceRed(int color, int value) { return alpha(color) | (value << 16) | (green(color) << 8) | blue(color); }
    private static int replaceGreen(int color, int value) { return alpha(color) | (red(color) << 16) | (value << 8) | blue(color); }
    private static int replaceBlue(int color, int value) { return alpha(color) | (red(color) << 16) | (green(color) << 8) | value; }

    private static Component text(String value) {
        return Component.literal(value);
    }
}

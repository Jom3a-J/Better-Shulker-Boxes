package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

import static com.bettershulker.client.render.ThemeColorUtil.blendColor;
import static com.bettershulker.client.render.ThemeColorUtil.normalizeOverlayAlpha;
import static com.bettershulker.client.render.ThemeColorUtil.opaqueOrDefault;
import static com.bettershulker.client.render.ThemeColorUtil.withAlpha;

/**
 * Every colour a container preview draws with, derived once per tooltip.
 *
 * <p>Two sources feed it. The Vanilla style takes its accents from the chosen theme, tinted by
 * the container's dye. Modern ignores the theme entirely and builds its whole card from the dye,
 * or from the panel colour an active resource pack supplies with the dye blended back in - which
 * is why the settings screen greys the theme out under that style.</p>
 */
public final class TooltipPalette {

    public static final int ENDER_ACCENT_COLOR = 0xFF00E6C8;
    public static final int ENDER_PURPLE_COLOR = 0xFF34104E;
    public static final int ENDER_DARK_COLOR = 0xFF06120F;

    /** Below this the card is too dark to derive shades by darkening; they are lightened instead. */
    private static final float MODERN_DARK_FILL_LUMINANCE = 0.15f;

    /** How much of the container's own dye stays visible on top of a pack's panel colour. */
    private static final float PACK_DYE_INFLUENCE = 0.30f;

    private final boolean isEnderChest;
    private final DyeColor color;
    private final Identifier packPanel;
    private final boolean modern;
    /** Lazily sampled Modern fill; 0 until {@link #getModernPanelFill()} has run once. */
    private int modernPanelFill;

    /**
     * @param packPanel panel texture the active pack supplies for this container, or null for none
     */
    public TooltipPalette(boolean isEnderChest, DyeColor color, Identifier packPanel) {
        this.isEnderChest = isEnderChest;
        this.color = color;
        this.packPanel = packPanel;
        this.modern = BetterShulkerConfig.getTooltipStyle() == BetterShulkerConfig.TooltipStyle.MODERN;
    }

    public int getCompactPanelBaseColor() {
        BetterShulkerConfig.TooltipTheme theme = BetterShulkerConfig.getTooltipTheme();
        int themeBase = switch (theme) {
            case ORIGINAL -> 0xFF6F2D8F;
            case CLASSIC -> 0xFF2D4A1A;
            case RETRO -> 0xFF60406E;
            case SOLARIZED_DARK -> 0xFF002B36;
            case SOLARIZED_LIGHT -> 0xFFFDF6E3;
            case HIGH_CONTRAST -> 0xFF000000;
            case CUSTOM -> opaqueOrDefault(BetterShulkerConfig.getCustomBackgroundColor(), 0xFF1A1A1A);
            case GLASS -> 0xFFEAF7FF;
        };
        // Modern already derives its face from the dye, so it takes no extra box blend below.
        if (this.modern) {
            return getModernPanelFill();
        }
        int containerColor = getCompactContainerColor();
        if (containerColor == 0 || theme == BetterShulkerConfig.TooltipTheme.HIGH_CONTRAST
                || theme == BetterShulkerConfig.TooltipTheme.GLASS) {
            return themeBase;
        }
        float boxInfluence = theme == BetterShulkerConfig.TooltipTheme.ORIGINAL ? 0.28f : 0.18f;
        if (theme == BetterShulkerConfig.TooltipTheme.RETRO) {
            boxInfluence = 0.24f;
        } else if (theme == BetterShulkerConfig.TooltipTheme.CUSTOM) {
            boxInfluence = 0.10f;
        }
        if (this.isEnderChest) {
            boxInfluence = Math.max(boxInfluence, 0.22f);
        }
        return blendColor(themeBase, containerColor, boxInfluence);
    }

    public int getCompactContainerColor() {
        if (this.isEnderChest) {
            return blendColor(ENDER_PURPLE_COLOR, ENDER_ACCENT_COLOR, 0.18f);
        }
        if (this.color != null) {
            return 0xFF000000 | this.color.getTextureDiffuseColor();
        }
        return 0;
    }

    public int getModernDyeColor() {
        if (this.isEnderChest) {
            return blendColor(ENDER_PURPLE_COLOR, ENDER_ACCENT_COLOR, 0.22f);
        }
        if (this.color != null) {
            return 0xFF000000 | this.color.getTextureDiffuseColor();
        }
        return 0xFF8932B8;
    }

    /** Card face: the pack's own panel colour carrying the dye, or the dye pulled towards neutral. */
    public int getModernPanelFill() {
        if (this.modernPanelFill == 0) {
            this.modernPanelFill = computeModernPanelFill();
        }
        return this.modernPanelFill;
    }

    private int computeModernPanelFill() {
        Identifier packPanel = this.packPanel;
        if (packPanel != null) {
            int sampled = ResourcePackPanelColors.dominantColor(packPanel, 0);
            // A sampled pack colour is already authored to sit behind items, so it sets the tone
            // and is not muted the way a raw dye is. It only carries the dye far enough to keep
            // each box its own: a pack shipping one shared GUI would otherwise paint all sixteen
            // the same colour, and even per-dye packs put neighbouring dyes close together.
            if (sampled != 0) return blendColor(sampled, getModernDyeColor(), PACK_DYE_INFLUENCE);
        }
        return blendColor(getModernDyeColor(), 0xFF6A6A72, 0.55f);
    }

    public int getModernPanelBorder() {
        return shadeModernFill(0.30f);
    }

    public int getModernGridLine() {
        return shadeModernFill(0.20f);
    }

    public int getModernCellFill() {
        return shadeModernFill(0.08f);
    }

    /**
     * Shifts the card fill by {@code factor} in whichever direction still has contrast left.
     *
     * <p>Darkening is multiplicative, so it collapses on a near-black panel: a {@code #141414}
     * fill darkened 20% lands 4/255 away and the lattice becomes invisible. Dark fills are
     * lightened instead, which is the only direction with headroom.</p>
     */
    private int shadeModernFill(float factor) {
        int fill = getModernPanelFill();
        return luminance(fill) < MODERN_DARK_FILL_LUMINANCE
                ? blendColor(fill, 0xFFFFFFFF, factor)
                : blendColor(fill, 0xFF000000, factor);
    }

    /** Perceived luminance of an opaque colour, 0 (black) to 1 (white). */
    private static float luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
    }

    public ThemePalette buildThemePalette() {
        int baseBorder;
        int nameBorder;
        int baseTint;
        int badgeBg;
        int select;
        int multi = 0xFF55FFFF;
        int shadow = 0xFF000000;

        if (this.isEnderChest) {
            baseBorder = 0xFF1D5A3A;
            baseTint = 0x65100018;
            badgeBg = 0xE0100018;
            select = 0xFF00FFDD;
        } else if (this.color != null) {
            int raw = 0xFF000000 | this.color.getTextureDiffuseColor();
            baseBorder = blendColor(raw, 0xFF000000, 0.35f);
            baseTint = withAlpha(raw, 95);
            badgeBg = withAlpha(blendColor(raw, 0xFF000000, 0.55f), 230);
            select = 0xFFFFD700;
        } else {
            baseBorder = 0xFF8932B8;
            baseTint = 0x65100018;
            badgeBg = 0xE0100018;
            select = 0xFFFFD700;
        }
        nameBorder = baseBorder;

        BetterShulkerConfig.TooltipTheme theme = BetterShulkerConfig.getTooltipTheme();
        switch (theme) {
            case ORIGINAL -> {
                // Keep container/ender derived defaults.
            }
            case CLASSIC -> {
                baseBorder = 0xFF4A7A25;
                baseTint = 0x702D4A1A;
                badgeBg = 0xE02D4A1A;
                select = 0xFFA7E060;
            }
            case RETRO -> {
                baseBorder = 0xFFFF00FF;
                baseTint = 0x70080812;
                badgeBg = 0xE0080812;
                select = 0xFF00FFFF;
                multi = 0xFFFF66FF;
            }
            case SOLARIZED_DARK -> {
                baseBorder = 0xFF268BD2;
                baseTint = 0x76002B36;
                badgeBg = 0xE0002B36;
                select = 0xFFB58900;
            }
            case SOLARIZED_LIGHT -> {
                baseBorder = 0xFF268BD2;
                baseTint = 0x88FDF6E3;
                badgeBg = 0xEEFDF6E3;
                select = 0xFFCB4B16;
                shadow = 0xFFEFE6C8;
            }
            case HIGH_CONTRAST -> {
                baseBorder = 0xFFFFAA00;
                baseTint = 0x88000000;
                badgeBg = 0xF0000000;
                select = 0xFFFFFF00;
                multi = 0xFFFFFFFF;
            }
            case CUSTOM -> {
                baseBorder = BetterShulkerConfig.getCustomBorderColor();
                nameBorder = BetterShulkerConfig.getCustomNameBorderColor();
                baseTint = normalizeOverlayAlpha(BetterShulkerConfig.getCustomBackgroundColor(), 112);
                badgeBg = BetterShulkerConfig.getCustomNameBgColor();
                select = BetterShulkerConfig.getCustomSelectionSquareColor();
                multi = blendColor(select, 0xFF55FFFF, 0.45f);
            }
            case GLASS -> {
                baseBorder = 0xB8FFFFFF;
                baseTint = 0x32FFFFFF;
                badgeBg = 0xA8FFFFFF;
                select = 0xFFFFD700;
                multi = 0xFF8EEBFF;
                shadow = 0x80FFFFFF;
            }
        }

        if (this.isEnderChest && theme != BetterShulkerConfig.TooltipTheme.CUSTOM) {
            baseBorder = blendColor(baseBorder, ENDER_ACCENT_COLOR, 0.48f);
            nameBorder = baseBorder;
            baseTint = withAlpha(blendColor(ENDER_PURPLE_COLOR, ENDER_DARK_COLOR, 0.35f), 112);
            badgeBg = withAlpha(blendColor(ENDER_PURPLE_COLOR, ENDER_DARK_COLOR, 0.45f), 230);
            select = ENDER_ACCENT_COLOR;
            multi = 0xFFB35CFF;
        }

        // Modern derives everything from the container's dye and ignores the theme entirely,
        // which is why the settings screen greys the theme selector out under this style.
        if (this.modern) {
            baseBorder = blendColor(getModernPanelFill(), 0xFFFFFFFF, 0.35f);
            nameBorder = getModernPanelBorder();
            baseTint = withAlpha(getModernPanelFill(), 0);
            badgeBg = withAlpha(getModernPanelBorder(), 235);
            select = this.isEnderChest ? ENDER_ACCENT_COLOR : 0xFFFFD700;
            multi = 0xFF7FD8FF;
            shadow = getModernPanelBorder();
        }

        return new ThemePalette(baseBorder, nameBorder, baseTint, badgeBg, select, multi, shadow);
    }

    public record ThemePalette(
            int borderColor,
            int nameBorderColor,
            int tintColor,
            int badgeBgColor,
            int selectionColor,
            int multiSelectColor,
            int panelShadowColor
    ) {}
}

package com.bettershulker;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Central configuration for Better Shulker Plus.
 * Uses a simple .properties file — no external library dependencies.
 */
public class BetterShulkerConfig {

    // =========================================================================
    //  Constants & Config Fields
    // =========================================================================

    // -- Feature Toggles --
    public static boolean tooltipEnabled = true;
    public static boolean precisionModeEnabled = true;
    public static boolean fillIndicatorEnabled = true;
    public static boolean secondaryTooltipEnabled = true;
    public static boolean altForceTooltipEnabled = true;
    public static boolean selectedItemNameEnabled = true;
    public static boolean compactTooltipEnabled = false;
    /** Whether to contact Modrinth once per session to look for a newer release. */
    public static boolean updateCheckEnabled = true;

    // -- Visuals & Animations --
    public static boolean selectionGlideEnabled = true;
    public static boolean hoverAnimationsEnabled = true;
    public static boolean rareItemWobbleEnabled = true;
    
    // -- Audio Configurations --
    public static float soundVolume = 0.3f;
    public static SoundOption soundOption = SoundOption.ITEM_PICKUP;
    
    // -- Themes & Colors --
    public static TooltipTheme tooltipTheme = TooltipTheme.ORIGINAL;
    public static TooltipStyle tooltipStyle = TooltipStyle.VANILLA;
    public static ResourcePackMode resourcePackMode = ResourcePackMode.AUTO;
    /** Output-only adjustments for resource-pack layout profiles; -1 keeps the profile cap. */
    public static int resourcePackLayoutOffsetX = 0;
    public static int resourcePackLayoutOffsetY = 0;
    public static int resourcePackLayoutBottomCapHeight = -1;
    public static int customBackgroundColor = 0xFF1A1A1A;
    public static int customBorderColor = 0xFF8932B8;
    public static int customNameBgColor = 0xF0100010;
    public static int customNameBorderColor = 0xFF8932B8;
    public static int customNameTextColor = 0xFFFFFFFF;
    public static int customSelectionSquareColor = 0xFFFFD700;

    private static final float MIN_SOUND_VOLUME = 0.0f;
    private static final float MAX_SOUND_VOLUME = 1.0f;
    private static final int MIN_LAYOUT_OFFSET = -32;
    private static final int MAX_LAYOUT_OFFSET = 32;
    private static final int MIN_LAYOUT_CAP_HEIGHT = -1;
    private static final int MAX_LAYOUT_CAP_HEIGHT = 16;

    // =========================================================================
    //  Enums
    // =========================================================================

    /** Specifies the active visual layout style theme for container tooltips. */
    public enum TooltipTheme {
        ORIGINAL("Original"),
        CLASSIC("Classic"),
        RETRO("Retro"),
        SOLARIZED_DARK("Solarized Dark"),
        SOLARIZED_LIGHT("Solarized Light"),
        HIGH_CONTRAST("High Contrast"),
        CUSTOM("Custom"),
        GLASS("Glass");

        private final String displayName;
        TooltipTheme(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /**
     * Shape of the tooltip panel, chosen independently of the colour theme.
     *
     * <p>Vanilla builds the panel from the container GUI texture; Modern draws a flat rounded
     * card with a lattice slot grid instead. The theme still supplies selection and filter
     * colours under both.</p>
     */
    public enum TooltipStyle {
        VANILLA("Vanilla"),
        MODERN("Modern");

        private final String displayName;
        TooltipStyle(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** Controls whether the compact resource-pack-aware panel layout is used. */
    public enum ResourcePackMode {
        AUTO("Automatic"),
        ENABLED("Always Enabled"),
        DISABLED("Disabled");

        private final String displayName;
        ResourcePackMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** Specifies available sound effects when performing slot operations. */
    public enum SoundOption {
        ITEM_PICKUP("Item Pickup (Default)", "minecraft:entity.item.pickup"),
        UI_CLICK("UI Button Click", "minecraft:ui.button.click"),
        BUNDLE_INSERT("Bundle Insert", "minecraft:item.bundle.insert"),
        BUNDLE_DROP("Bundle Drop Contents", "minecraft:item.bundle.drop_contents"),
        EXPERIENCE_DING("Experience Orb", "minecraft:entity.experience_orb.pickup"),
        WOOD_CLICK("Wooden Click", "minecraft:block.wooden_button.click_on"),
        STONE_CLICK("Stone Click", "minecraft:block.stone_button.click_on"),
        CONTEXTUAL("Contextual Materials", "contextual");

        private final String displayName;
        private final String soundId;

        SoundOption(String displayName, String soundId) {
            this.displayName = displayName;
            this.soundId = soundId;
        }

        public String getDisplayName() { return displayName; }
        public String getSoundId() { return soundId; }
    }

    // =========================================================================
    //  Getters & Setters (used by UI screens)
    // =========================================================================

    public static boolean isTooltipEnabled() { return tooltipEnabled; }
    public static void setTooltipEnabled(boolean v) { tooltipEnabled = v; }
    
    public static boolean isPrecisionModeEnabled() { return precisionModeEnabled; }
    public static void setPrecisionModeEnabled(boolean v) { precisionModeEnabled = v; }
    
    public static boolean isFillIndicatorEnabled() { return fillIndicatorEnabled; }
    public static void setFillIndicatorEnabled(boolean v) { fillIndicatorEnabled = v; }
    
    public static boolean isSecondaryTooltipEnabled() { return secondaryTooltipEnabled; }
    public static void setSecondaryTooltipEnabled(boolean v) { secondaryTooltipEnabled = v; }
    
    public static boolean isAltForceTooltipEnabled() { return altForceTooltipEnabled; }
    public static void setAltForceTooltipEnabled(boolean v) { altForceTooltipEnabled = v; }
    
    public static boolean isSelectedItemNameEnabled() { return selectedItemNameEnabled; }
    public static void setSelectedItemNameEnabled(boolean v) { selectedItemNameEnabled = v; }

    public static boolean isCompactTooltipEnabled() { return compactTooltipEnabled; }
    public static void setCompactTooltipEnabled(boolean v) { compactTooltipEnabled = v; }

    public static boolean isUpdateCheckEnabled() { return updateCheckEnabled; }
    public static void setUpdateCheckEnabled(boolean v) { updateCheckEnabled = v; }

    public static boolean isSelectionGlideEnabled() { return selectionGlideEnabled; }
    public static void setSelectionGlideEnabled(boolean v) { selectionGlideEnabled = v; }
    
    public static boolean isHoverAnimationsEnabled() { return hoverAnimationsEnabled; }
    public static void setHoverAnimationsEnabled(boolean v) { hoverAnimationsEnabled = v; }
    
    public static boolean isRareItemWobbleEnabled() { return rareItemWobbleEnabled; }
    public static void setRareItemWobbleEnabled(boolean v) { rareItemWobbleEnabled = v; }
    
    public static float getSoundVolume() {
        return Float.isFinite(soundVolume)
                ? clamp(soundVolume, MIN_SOUND_VOLUME, MAX_SOUND_VOLUME)
                : 0.3f;
    }
    public static void setSoundVolume(float v) {
        soundVolume = Float.isFinite(v) ? clamp(v, MIN_SOUND_VOLUME, MAX_SOUND_VOLUME) : 0.3f;
    }
    
    public static SoundOption getSoundOption() {
        return soundOption == null ? SoundOption.ITEM_PICKUP : soundOption;
    }
    public static void setSoundOption(SoundOption s) { soundOption = s == null ? SoundOption.ITEM_PICKUP : s; }
    
    public static TooltipTheme getTooltipTheme() {
        return tooltipTheme == null ? TooltipTheme.ORIGINAL : tooltipTheme;
    }
    public static void setTooltipTheme(TooltipTheme t) { tooltipTheme = t == null ? TooltipTheme.ORIGINAL : t; }

    public static TooltipStyle getTooltipStyle() {
        return tooltipStyle == null ? TooltipStyle.VANILLA : tooltipStyle;
    }
    public static void setTooltipStyle(TooltipStyle s) { tooltipStyle = s == null ? TooltipStyle.VANILLA : s; }

    public static ResourcePackMode getResourcePackMode() {
        return resourcePackMode == null ? ResourcePackMode.AUTO : resourcePackMode;
    }
    public static void setResourcePackMode(ResourcePackMode mode) {
        resourcePackMode = mode == null ? ResourcePackMode.AUTO : mode;
    }

    public static int getResourcePackLayoutOffsetX() {
        return clamp(resourcePackLayoutOffsetX, MIN_LAYOUT_OFFSET, MAX_LAYOUT_OFFSET);
    }
    public static void setResourcePackLayoutOffsetX(int value) {
        resourcePackLayoutOffsetX = clamp(value, MIN_LAYOUT_OFFSET, MAX_LAYOUT_OFFSET);
    }

    public static int getResourcePackLayoutOffsetY() {
        return clamp(resourcePackLayoutOffsetY, MIN_LAYOUT_OFFSET, MAX_LAYOUT_OFFSET);
    }
    public static void setResourcePackLayoutOffsetY(int value) {
        resourcePackLayoutOffsetY = clamp(value, MIN_LAYOUT_OFFSET, MAX_LAYOUT_OFFSET);
    }

    public static int getResourcePackLayoutBottomCapHeight() {
        return clamp(resourcePackLayoutBottomCapHeight, MIN_LAYOUT_CAP_HEIGHT, MAX_LAYOUT_CAP_HEIGHT);
    }
    public static void setResourcePackLayoutBottomCapHeight(int value) {
        resourcePackLayoutBottomCapHeight = clamp(value, MIN_LAYOUT_CAP_HEIGHT, MAX_LAYOUT_CAP_HEIGHT);
    }
    
    public static int getCustomBackgroundColor() { return customBackgroundColor; }
    public static void setCustomBackgroundColor(int v) { customBackgroundColor = v; }
    
    public static int getCustomBorderColor() { return customBorderColor; }
    public static void setCustomBorderColor(int v) { customBorderColor = v; }
    
    public static int getCustomNameBgColor() { return customNameBgColor; }
    public static void setCustomNameBgColor(int v) { customNameBgColor = v; }
    
    public static int getCustomNameBorderColor() { return customNameBorderColor; }
    public static void setCustomNameBorderColor(int v) { customNameBorderColor = v; }

    public static int getCustomNameTextColor() { return customNameTextColor; }
    public static void setCustomNameTextColor(int v) { customNameTextColor = v; }
    
    public static int getCustomSelectionSquareColor() { return customSelectionSquareColor; }
    public static void setCustomSelectionSquareColor(int v) { customSelectionSquareColor = v; }

    // =========================================================================
    //  Configuration Persistence
    // =========================================================================

    private static final Path CONFIG_PATH = Path.of("config", "bettershulker-plus.properties");

    /** Load config from disk (called once during mod initialization). */
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            Properties props = new Properties();
            props.load(reader);
            tooltipEnabled          = bool(props, "tooltipEnabled", tooltipEnabled);
            precisionModeEnabled    = bool(props, "precisionModeEnabled", precisionModeEnabled);
            fillIndicatorEnabled    = bool(props, "fillIndicatorEnabled", fillIndicatorEnabled);
            secondaryTooltipEnabled = bool(props, "secondaryTooltipEnabled", secondaryTooltipEnabled);
            altForceTooltipEnabled  = bool(props, "altForceTooltipEnabled", altForceTooltipEnabled);
            selectedItemNameEnabled = bool(props, "selectedItemNameEnabled", selectedItemNameEnabled);
            compactTooltipEnabled   = bool(props, "compactTooltipEnabled", compactTooltipEnabled);
            updateCheckEnabled      = bool(props, "updateCheckEnabled", updateCheckEnabled);
            selectionGlideEnabled   = bool(props, "selectionGlideEnabled", selectionGlideEnabled);
            hoverAnimationsEnabled  = bool(props, "hoverAnimationsEnabled", hoverAnimationsEnabled);
            rareItemWobbleEnabled   = bool(props, "rareItemWobbleEnabled", rareItemWobbleEnabled);
            setSoundVolume(floatVal(props, "soundVolume", soundVolume));
            setSoundOption(enumVal(props, "soundOption", SoundOption.class, soundOption));
            // Modern used to be a theme. Configs written back then still say tooltipTheme=MODERN,
            // which no longer parses; carry them over to the style option instead of resetting.
            if ("MODERN".equalsIgnoreCase(props.getProperty("tooltipTheme", "").trim())
                    && props.getProperty("tooltipStyle") == null) {
                setTooltipTheme(TooltipTheme.ORIGINAL);
                setTooltipStyle(TooltipStyle.MODERN);
            } else {
                setTooltipTheme(enumVal(props, "tooltipTheme", TooltipTheme.class, tooltipTheme));
                setTooltipStyle(enumVal(props, "tooltipStyle", TooltipStyle.class, tooltipStyle));
            }
            setResourcePackMode(enumVal(props, "resourcePackMode", ResourcePackMode.class, resourcePackMode));
            setResourcePackLayoutOffsetX(intVal(props, "resourcePackLayoutOffsetX", resourcePackLayoutOffsetX));
            setResourcePackLayoutOffsetY(intVal(props, "resourcePackLayoutOffsetY", resourcePackLayoutOffsetY));
            setResourcePackLayoutBottomCapHeight(intVal(props, "resourcePackLayoutBottomCapHeight", resourcePackLayoutBottomCapHeight));
            customBackgroundColor = hexVal(props, "customBackgroundColor", customBackgroundColor);
            customBorderColor     = hexVal(props, "customBorderColor", customBorderColor);
            customNameBgColor     = hexVal(props, "customNameBgColor", customNameBgColor);
            customNameBorderColor = hexVal(props, "customNameBorderColor", customNameBorderColor);
            customNameTextColor   = hexVal(props, "customNameTextColor", customNameTextColor);
            customSelectionSquareColor = hexVal(props, "customSelectionSquareColor", customSelectionSquareColor);
            BetterShulkerMod.LOGGER.info("[BetterShulker] Config loaded from {}", CONFIG_PATH);
        } catch (Exception e) {
            BetterShulkerMod.LOGGER.warn("[BetterShulker] Failed to load config", e);
        }
    }

    /** Save current config properties to disk. */
    public static void save() {
        try {
            // These fields are public for compatibility with older integrations, so normalize
            // them here as well as in the UI setters before serializing the file.
            setSoundVolume(soundVolume);
            setSoundOption(soundOption);
            setTooltipTheme(tooltipTheme);
            setTooltipStyle(tooltipStyle);
            setResourcePackMode(resourcePackMode);
            setResourcePackLayoutOffsetX(resourcePackLayoutOffsetX);
            setResourcePackLayoutOffsetY(resourcePackLayoutOffsetY);
            setResourcePackLayoutBottomCapHeight(resourcePackLayoutBottomCapHeight);
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties props = new Properties();
            props.setProperty("tooltipEnabled", String.valueOf(tooltipEnabled));
            props.setProperty("precisionModeEnabled", String.valueOf(precisionModeEnabled));
            props.setProperty("fillIndicatorEnabled", String.valueOf(fillIndicatorEnabled));
            props.setProperty("secondaryTooltipEnabled", String.valueOf(secondaryTooltipEnabled));
            props.setProperty("altForceTooltipEnabled", String.valueOf(altForceTooltipEnabled));
            props.setProperty("selectedItemNameEnabled", String.valueOf(selectedItemNameEnabled));
            props.setProperty("compactTooltipEnabled", String.valueOf(compactTooltipEnabled));
            props.setProperty("updateCheckEnabled", String.valueOf(updateCheckEnabled));
            props.setProperty("selectionGlideEnabled", String.valueOf(selectionGlideEnabled));
            props.setProperty("hoverAnimationsEnabled", String.valueOf(hoverAnimationsEnabled));
            props.setProperty("rareItemWobbleEnabled", String.valueOf(rareItemWobbleEnabled));
            props.setProperty("soundVolume", String.valueOf(soundVolume));
            props.setProperty("soundOption", soundOption.name());
            props.setProperty("tooltipTheme", tooltipTheme.name());
            props.setProperty("tooltipStyle", tooltipStyle.name());
            props.setProperty("resourcePackMode", resourcePackMode.name());
            props.setProperty("resourcePackLayoutOffsetX", String.valueOf(resourcePackLayoutOffsetX));
            props.setProperty("resourcePackLayoutOffsetY", String.valueOf(resourcePackLayoutOffsetY));
            props.setProperty("resourcePackLayoutBottomCapHeight", String.valueOf(resourcePackLayoutBottomCapHeight));
            props.setProperty("customBackgroundColor", String.format("0x%08X", customBackgroundColor));
            props.setProperty("customBorderColor", String.format("0x%08X", customBorderColor));
            props.setProperty("customNameBgColor", String.format("0x%08X", customNameBgColor));
            props.setProperty("customNameBorderColor", String.format("0x%08X", customNameBorderColor));
            props.setProperty("customNameTextColor", String.format("0x%08X", customNameTextColor));
            props.setProperty("customSelectionSquareColor", String.format("0x%08X", customSelectionSquareColor));
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                props.store(writer, "Better Shulker Plus Configuration");
            }
            BetterShulkerMod.LOGGER.info("[BetterShulker] Config saved to {}", CONFIG_PATH);
        } catch (Exception e) {
            BetterShulkerMod.LOGGER.warn("[BetterShulker] Failed to save config", e);
        }
    }

    // =========================================================================
    //  Internal Parsing Helpers
    // =========================================================================

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v != null ? Boolean.parseBoolean(v) : def;
    }

    private static float floatVal(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            float value = Float.parseFloat(v);
            return Float.isFinite(value) ? value : def;
        } catch (NumberFormatException e) { return def; }
    }

    private static int intVal(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static <E extends Enum<E>> E enumVal(Properties p, String key, Class<E> cls, E def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Enum.valueOf(cls, v); } catch (IllegalArgumentException e) { return def; }
    }

    private static int hexVal(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            String hex = (v.startsWith("0x") || v.startsWith("0X")) ? v.substring(2) : v;
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException e) { return def; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

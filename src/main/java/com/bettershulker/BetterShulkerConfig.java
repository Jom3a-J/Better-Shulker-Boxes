package com.bettershulker;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Central configuration for Better Shulker Plus.
 * Uses a simple .properties file — no external library dependencies.
 */
public class BetterShulkerConfig {

    // ---- Static config fields (accessed directly by mixins & renderers) ----
    public static boolean tooltipEnabled = true;
    public static boolean precisionModeEnabled = true;
    public static boolean fillIndicatorEnabled = true;
    public static boolean secondaryTooltipEnabled = true;
    public static boolean altForceTooltipEnabled = true;
    public static boolean selectedItemNameEnabled = true;
    public static boolean compactTooltipEnabled = false;
    
    // Core Visuals & Animations toggles
    public static boolean selectionGlideEnabled = true;
    public static boolean hoverAnimationsEnabled = true;
    public static boolean rareItemWobbleEnabled = true;
    
    public static float soundVolume = 0.3f;
    public static SoundOption soundOption = SoundOption.ITEM_PICKUP;
    
    // Tooltip themes and custom colors
    public static TooltipTheme tooltipTheme = TooltipTheme.ORIGINAL;
    public static int customBackgroundColor = 0xFF1A1A1A;
    public static int customBorderColor = 0xFF8932B8;
    public static int customNameBgColor = 0xF0100010;
    public static int customNameBorderColor = 0xFF8932B8;
    public static int customSelectionSquareColor = 0xFFFFD700;

    // ---- Enums ----
    public enum TooltipTheme {
        ORIGINAL("Original"),
        CLASSIC("Classic"),
        RETRO("Retro"),
        SOLARIZED_DARK("Solarized Dark"),
        SOLARIZED_LIGHT("Solarized Light"),
        PASTEL_SOFT("Pastel Soft"),
        HIGH_CONTRAST("High Contrast"),
        CUSTOM("Custom"),
        LIGHT("Light"),
        GLASS("Glass");

        private final String displayName;
        TooltipTheme(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

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

    // ---- Getter / Setter helpers (used by the settings screen) ----
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
    
    public static boolean isSelectionGlideEnabled() { return selectionGlideEnabled; }
    public static void setSelectionGlideEnabled(boolean v) { selectionGlideEnabled = v; }
    public static boolean isHoverAnimationsEnabled() { return hoverAnimationsEnabled; }
    public static void setHoverAnimationsEnabled(boolean v) { hoverAnimationsEnabled = v; }
    public static boolean isRareItemWobbleEnabled() { return rareItemWobbleEnabled; }
    public static void setRareItemWobbleEnabled(boolean v) { rareItemWobbleEnabled = v; }
    
    public static float getSoundVolume() { return soundVolume; }
    public static void setSoundVolume(float v) { soundVolume = v; }
    public static SoundOption getSoundOption() { return soundOption; }
    public static void setSoundOption(SoundOption s) { soundOption = s; }
    
    public static TooltipTheme getTooltipTheme() { return tooltipTheme; }
    public static void setTooltipTheme(TooltipTheme t) { tooltipTheme = t; }
    
    public static int getCustomBackgroundColor() { return customBackgroundColor; }
    public static void setCustomBackgroundColor(int v) { customBackgroundColor = v; }
    public static int getCustomBorderColor() { return customBorderColor; }
    public static void setCustomBorderColor(int v) { customBorderColor = v; }
    
    public static int getCustomNameBgColor() { return customNameBgColor; }
    public static void setCustomNameBgColor(int v) { customNameBgColor = v; }
    public static int getCustomNameBorderColor() { return customNameBorderColor; }
    public static void setCustomNameBorderColor(int v) { customNameBorderColor = v; }
    public static int getCustomSelectionSquareColor() { return customSelectionSquareColor; }
    public static void setCustomSelectionSquareColor(int v) { customSelectionSquareColor = v; }

    // ---- Persistence ----
    private static final Path CONFIG_PATH =
            Path.of("config", "bettershulker-plus.properties");

    /** Load config from disk (call once at mod init). */
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
            selectionGlideEnabled   = bool(props, "selectionGlideEnabled", selectionGlideEnabled);
            hoverAnimationsEnabled  = bool(props, "hoverAnimationsEnabled", hoverAnimationsEnabled);
            rareItemWobbleEnabled   = bool(props, "rareItemWobbleEnabled", rareItemWobbleEnabled);
            soundVolume             = floatVal(props, "soundVolume", soundVolume);
            soundOption             = enumVal(props, "soundOption", SoundOption.class, soundOption);
            tooltipTheme            = enumVal(props, "tooltipTheme", TooltipTheme.class, tooltipTheme);
            customBackgroundColor = hexVal(props, "customBackgroundColor", customBackgroundColor);
            customBorderColor     = hexVal(props, "customBorderColor", customBorderColor);
            customNameBgColor     = hexVal(props, "customNameBgColor", customNameBgColor);
            customNameBorderColor = hexVal(props, "customNameBorderColor", customNameBorderColor);
            customSelectionSquareColor = hexVal(props, "customSelectionSquareColor", customSelectionSquareColor);
            System.out.println("[BetterShulker] Config loaded from " + CONFIG_PATH);
        } catch (Exception e) {
            System.err.println("[BetterShulker] Failed to load config: " + e.getMessage());
        }
    }

    /** Save current config to disk. */
    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties props = new Properties();
            props.setProperty("tooltipEnabled", String.valueOf(tooltipEnabled));
            props.setProperty("precisionModeEnabled", String.valueOf(precisionModeEnabled));
            props.setProperty("fillIndicatorEnabled", String.valueOf(fillIndicatorEnabled));
            props.setProperty("secondaryTooltipEnabled", String.valueOf(secondaryTooltipEnabled));
            props.setProperty("altForceTooltipEnabled", String.valueOf(altForceTooltipEnabled));
            props.setProperty("selectedItemNameEnabled", String.valueOf(selectedItemNameEnabled));
            props.setProperty("compactTooltipEnabled", String.valueOf(compactTooltipEnabled));
            props.setProperty("selectionGlideEnabled", String.valueOf(selectionGlideEnabled));
            props.setProperty("hoverAnimationsEnabled", String.valueOf(hoverAnimationsEnabled));
            props.setProperty("rareItemWobbleEnabled", String.valueOf(rareItemWobbleEnabled));
            props.setProperty("soundVolume", String.valueOf(soundVolume));
            props.setProperty("soundOption", soundOption.name());
            props.setProperty("tooltipTheme", tooltipTheme.name());
            props.setProperty("customBackgroundColor", String.format("0x%08X", customBackgroundColor));
            props.setProperty("customBorderColor", String.format("0x%08X", customBorderColor));
            props.setProperty("customNameBgColor", String.format("0x%08X", customNameBgColor));
            props.setProperty("customNameBorderColor", String.format("0x%08X", customNameBorderColor));
            props.setProperty("customSelectionSquareColor", String.format("0x%08X", customSelectionSquareColor));
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                props.store(writer, "Better Shulker Plus Configuration");
            }
            System.out.println("[BetterShulker] Config saved to " + CONFIG_PATH);
        } catch (Exception e) {
            System.err.println("[BetterShulker] Failed to save config: " + e.getMessage());
        }
    }

    // ---- Parsing helpers ----
    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v != null ? Boolean.parseBoolean(v) : def;
    }


    private static float floatVal(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
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
}

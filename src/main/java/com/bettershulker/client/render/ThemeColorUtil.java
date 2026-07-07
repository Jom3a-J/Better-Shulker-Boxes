package com.bettershulker.client.render;

/** Shared color helpers for tooltip rendering and settings previews. */
public final class ThemeColorUtil {
    private ThemeColorUtil() {}

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    public static int normalizeOverlayAlpha(int color, int fallbackAlpha) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0 || alpha == 255) alpha = fallbackAlpha;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static int opaqueOrDefault(int color, int fallback) {
        if ((color & 0x00FFFFFF) == 0 && ((color >>> 24) & 0xFF) == 0) return fallback;
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    public static int blendColor(int colorA, int colorB, float factor) {
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

    public static int getTextColorForBackground(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.65 ? 0xFF373737 : 0xFFFFFFFF;
    }
}

package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.render.ResourcePackContainerTextures;
import com.bettershulker.client.render.ResourcePackLayout;
import com.bettershulker.client.render.ResourcePackLayoutProfiles;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static com.bettershulker.client.render.ThemeColorUtil.blendColor;
import static com.bettershulker.client.render.ThemeColorUtil.getTextColorForBackground;
import static com.bettershulker.client.render.ThemeColorUtil.normalizeOverlayAlpha;
import static com.bettershulker.client.render.ThemeColorUtil.withAlpha;

/**
 * The tooltip preview drawn beside the Theme &amp; Colors category.
 *
 * <p>It reads the settings screen's live entry values rather than the saved config, so a colour
 * shows its effect while the slider is still moving and before Done is pressed. Everything here
 * is a redraw of what {@code ShulkerTooltipComponent} would produce for a purple Shulker Box,
 * against invented contents - the real component needs a container and a screen to render.</p>
 *
 * <p>It attaches itself by reflection and fails silently: a preview is worth having, but never
 * worth breaking the settings screen for.</p>
 */
final class LiveTooltipPreview {

    private static final Identifier PREVIEW_SHULKER_PANEL_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final float PREVIEW_PANEL_TEXTURE_U = 0.0F;
    private static final float PREVIEW_PANEL_TEXTURE_V = 11.0F;
    /** Mirrors ShulkerTooltipComponent's Modern palette for an undyed purple shulker box. */
    private static final int MODERN_PREVIEW_FILL = 0xFF785192;
    private static final int MODERN_PREVIEW_BORDER = 0xFF543966;

    /** Height ModernCardPainter gives its name tabs, and the room the card needs above it. */
    private static final int MODERN_NAME_TAB_HEIGHT = 16;

    /** Stand-ins for the names the real tooltip reads off the container and the selected slot. */
    private static final String PREVIEW_CONTAINER_NAME = "Shulker Box";
    private static final String PREVIEW_SELECTED_NAME = "Diamond Pickaxe";

    private LiveTooltipPreview() {}

    private static Component text(String value) {
        return Component.literal(value);
    }

    public static void attachTo(Screen screen, CustomPreviewState previewState) {
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
        refreshResourcePackPreview(state);
        PreviewColors colors = resolvePreviewColors(state);
        int nameText = colors.nameText();
        int sel = colors.selection();

        // Integrated preview column: no separate dark modal/card background. Keep it visually part
        // of the Theme & Colors category and use only a subtle divider/title.
        graphics.fill(x - 10, y - 8, x - 9, y + h + 8, 0x55FFFFFF);
        graphics.centeredText(font, text("Live Tooltip Preview"), x + w / 2, y, 0xFFE6E6E6);

        BetterShulkerConfig.ResourcePackMode selectedResourcePackMode = state.resourcePackMode == null
                ? BetterShulkerConfig.getResourcePackMode()
                : state.resourcePackMode.getValue();
        boolean useResourcePackPreview = state.resourceReady()
                && (selectedResourcePackMode == BetterShulkerConfig.ResourcePackMode.ENABLED
                || (selectedResourcePackMode == BetterShulkerConfig.ResourcePackMode.AUTO
                && state.resourcePackDetected));
        ResourcePackLayout resourceLayout = useResourcePackPreview
                ? state.resourcePackLayout.withOutputAdjustments(state.resourcePackOffsetX.getValue(),
                        state.resourcePackOffsetY.getValue(), state.resourcePackCapHeight.getValue())
                : null;
        int fullW = resourceLayout == null ? 176 : resourceLayout.panelWidth(resourceLayout.columns());
        int fullH = resourceLayout == null ? 68 : resourceLayout.panelHeight(resourceLayout.rows());
        int panelX = x + (w - fullW) / 2;
        // Modern's tabs sit above the card, so it starts low enough to leave them room under the
        // heading rather than drawing over it.
        int panelY = y + 22 + (colors.modern() ? MODERN_NAME_TAB_HEIGHT : 0);
        if (resourceLayout == null) {
            drawFullThemePreviewPanel(graphics, colors, panelX, panelY, fullW, fullH, sel);
        } else {
            drawResourcePackLayoutPreview(graphics, state, resourceLayout, panelX, panelY, false);
        }
        if (colors.modern() && resourceLayout == null) {
            drawModernNameTabsPreview(graphics, font, colors, panelX, panelY, fullW);
        }

        // Modern colours its own name tabs from the card and ignores the Selected Name sliders
        // entirely, which the settings screen greys out under it. Previewing a badge those
        // sliders drive would advertise a control that does nothing, so the row is dropped and
        // the compact card moves up into the space.
        int belowPanelY = panelY + fullH + 14;
        if (!colors.modern()) {
            drawSelectedNamePreview(graphics, font, colors, x, w, belowPanelY, nameText);
            belowPanelY += 34;
        } else {
            belowPanelY += 10;
        }

        int compactW = 14 + 5 * 18;
        int compactH = 14 + 18;
        int compactX = x + (w - compactW) / 2;
        int compactY = belowPanelY;
        drawCompactThemePreview(graphics, font, colors, compactX, compactY, compactW, compactH, sel);
    }

    private static void drawResourcePackLayoutPreview(GuiGraphicsExtractor graphics,
                                                       CustomPreviewState state,
                                                       ResourcePackLayout layout, int panelX,
                                                       int panelY, boolean showOffsets) {
        int columns = layout.columns();
        int rows = layout.rows();
        int panelW = layout.panelWidth(columns);
        int panelH = layout.panelHeight(rows);
        Identifier texture = state.resourcePackTexture;

        drawResourcePackPreviewSlices(graphics, texture, layout, panelX, panelY,
                layout.sourcePanelY(), layout.outputSlotY(), columns, panelW);
        drawResourcePackPreviewSlices(graphics, texture, layout, panelX,
                panelY + layout.outputSlotY(), layout.sourceSlotY(), rows * layout.slotSize(),
                columns, panelW);
        int capY = panelY + layout.outputSlotY() + rows * layout.slotSize();
        for (int row = 0; row < layout.bottomCapHeight(); row++) {
            drawResourcePackPreviewSlices(graphics, texture, layout, panelX, capY + row,
                    layout.bottomCapSourceY(), 1, columns, panelW);
        }

        int selectedX = panelX + layout.outputSlotX() + 3 * layout.slotSize();
        int selectedY = panelY + layout.outputSlotY() + layout.slotSize();
        graphics.fill(selectedX + 1, selectedY + 1,
                selectedX + layout.slotSize() - 1, selectedY + layout.slotSize() - 1,
                withAlpha(0xFFFFD700, 64));

        if (showOffsets) {
            String offsets = "X " + state.resourcePackOffsetX.getValue()
                    + "  Y " + state.resourcePackOffsetY.getValue()
                    + "  Cap " + (state.resourcePackCapHeight.getValue() < 0
                    ? "Auto" : state.resourcePackCapHeight.getValue());
            graphics.centeredText(Minecraft.getInstance().font, text(offsets),
                    panelX + panelW / 2, panelY + panelH + 4, 0xFFBDBDBD);
        }
    }

    private static void drawResourcePackPreviewSlices(GuiGraphicsExtractor graphics, Identifier texture,
                                                      ResourcePackLayout layout, int panelX, int y,
                                                      int sourceY, int height, int columns, int panelW) {
        int leftW = layout.outputSlotX();
        int slotsW = columns * layout.slotSize();
        int outputRightW = Math.max(0, panelW - leftW - slotsW);
        int sourceRightW = Math.min(outputRightW, layout.sourceRightWidth());
        blitResourcePackPreviewSlice(graphics, texture, layout,
                panelX, y, layout.sourcePanelX(), sourceY, leftW, height);
        blitResourcePackPreviewSlice(graphics, texture, layout,
                panelX + leftW, y, layout.sourceSlotX(), sourceY, slotsW, height);
        blitResourcePackPreviewSlice(graphics, texture, layout,
                panelX + leftW + slotsW, y, layout.sourceRightX(), sourceY, sourceRightW, height);
    }

    private static void blitResourcePackPreviewSlice(GuiGraphicsExtractor graphics, Identifier texture,
                                                     ResourcePackLayout layout, int x, int y,
                                                     int u, int v, int width, int height) {
        if (width <= 0 || height <= 0) return;
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y,
                (float) u, (float) v, width, height,
                layout.textureWidth(), layout.textureHeight(), 0xFFFFFFFF);
    }

    private static void drawFullThemePreviewPanel(GuiGraphicsExtractor graphics, PreviewColors colors,
                                                  int panelX, int panelY, int fullW, int fullH, int sel) {
        if (colors.modern()) {
            drawModernPreviewPanel(graphics, colors, panelX, panelY, fullW, fullH, 9, 3);
            drawSelectedSlotPreview(graphics, panelX + 8 + 3 * 18, panelY + 7 + 18, sel);
            return;
        }

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
            graphics.fill(panelX + 7, panelY + 6, panelX + fullW - 7, panelY + 62, withAlpha(colors.border(), 34));
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int sx = panelX + 8 + col * 18;
                    int sy = panelY + 7 + row * 18;
                    graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, withAlpha(colors.border(), 26));
                }
            }
            int softHighlight = withAlpha(blendColor(colors.border(), 0xFFFFFFFF, 0.45f), 28);
            graphics.fill(panelX + 3, panelY + 3, panelX + fullW - 3, panelY + 5, softHighlight);
            graphics.fill(panelX + 3, panelY + 5, panelX + 5, panelY + fullH - 3, softHighlight);
        } else {
            graphics.fill(panelX, panelY, panelX + fullW, panelY + fullH, colors.background());
            drawStaticFrame(graphics, panelX, panelY, fullW, fullH, colors.border());
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int sx = panelX + 8 + col * 18;
                    int sy = panelY + 7 + row * 18;
                    graphics.fill(sx, sy, sx + 18, sy + 18, withAlpha(colors.border(), 105));
                    graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xAA101010);
                }
            }
        }

        drawSelectedSlotPreview(graphics, panelX + 8 + 3 * 18, panelY + 7 + 1 * 18, sel);
    }

    private static void drawSelectedNamePreview(GuiGraphicsExtractor graphics, Font font,
                                                PreviewColors colors, int columnX, int columnW, int y, int nameText) {
        String name = "Diamond Pickaxe";
        int nameW = font.width(name) + 12;
        int nameX = columnX + (columnW - nameW) / 2;

        // Match the actual selected-name tab better: it is rendered as a vanilla-style tooltip,
        // with the theme only tinting the bridge/border and the configured name text color.
        int outer = withAlpha(colors.nameBorder(), 230);
        int inner = blendColor(colors.nameBackground(), 0xFF000000, 0.28f);
        int high = withAlpha(blendColor(colors.nameBorder(), 0xFFFFFFFF, 0.45f), 105);
        int low = withAlpha(blendColor(colors.nameBorder(), 0xFF000000, 0.55f), 150);
        graphics.fill(nameX, y, nameX + nameW, y + 14, outer);
        graphics.fill(nameX + 1, y + 1, nameX + nameW - 1, y + 13, inner);
        graphics.fill(nameX + 1, y + 1, nameX + nameW - 1, y + 2, high);
        graphics.fill(nameX + 1, y + 2, nameX + 2, y + 13, high);
        graphics.fill(nameX + 1, y + 12, nameX + nameW - 1, y + 13, low);
        graphics.fill(nameX + nameW - 2, y + 2, nameX + nameW - 1, y + 13, low);
        graphics.text(font, text(name), nameX + 6, y + 3, nameText);
    }

    private static void drawModernPreviewPanel(GuiGraphicsExtractor graphics, PreviewColors colors,
                                               int x, int y, int w, int h, int cols, int rows) {
        int fill = colors.background();
        int border = colors.border();
        fillRoundedPreview(graphics, x, y, w, h, border);
        fillRoundedPreview(graphics, x + 2, y + 2, w - 4, h - 4, fill);
        graphics.fill(x + 3, y + 2, x + w - 3, y + 3, blendColor(fill, 0xFFFFFFFF, 0.16f));
        graphics.fill(x + 3, y + h - 3, x + w - 3, y + h - 2, blendColor(fill, 0xFF000000, 0.18f));

        int gridX = x + 8;
        int gridY = y + 7;
        int gridW = cols * 18;
        int gridH = rows * 18;
        graphics.fill(gridX, gridY, gridX + gridW, gridY + gridH, blendColor(fill, 0xFF000000, 0.08f));
        int line = blendColor(fill, 0xFF000000, 0.20f);
        for (int col = 0; col <= cols; col++) {
            int lineX = gridX + col * 18;
            graphics.fill(lineX, gridY, lineX + 1, gridY + gridH + 1, line);
        }
        for (int row = 0; row <= rows; row++) {
            int lineY = gridY + row * 18;
            graphics.fill(gridX, lineY, gridX + gridW + 1, lineY + 1, line);
        }
    }

    private static void fillRoundedPreview(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w <= 4 || h <= 4) {
            graphics.fill(x, y, x + w, y + h, color);
            return;
        }
        graphics.fill(x + 2, y, x + w - 2, y + 1, color);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        graphics.fill(x, y + 2, x + w, y + h - 2, color);
        graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
        graphics.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
    }

    private static void drawCompactThemePreview(GuiGraphicsExtractor graphics, Font font, PreviewColors colors,
                                                int x, int y, int panelW, int panelH, int sel) {
        int compactBase = colors.compactBase();
        boolean glass = colors.glassCompact();

        if (colors.modern()) {
            drawModernPreviewPanel(graphics, colors, x, y, panelW, panelH, 5, 1);
        } else if (colors.usesVanillaPanelTexture() && !glass) {
            // Match ShulkerTooltipComponent's compact path: recompose the normal full tooltip
            // panel texture into compact width, then apply the same full-tooltip overlay.
            int leftW = 8;
            int rightSourceX = 8 + 9 * 18;
            int rightW = 176 - rightSourceX;
            int slotsW = 5 * 18;
            int topH = 7 + 18;
            int bottomH = panelH - topH;
            int bottomSourceY = 7 + 3 * 18;

            blitPreviewPanelSlice(graphics, x, y, 0, 0, leftW, topH);
            blitPreviewPanelSlice(graphics, x + leftW, y, 8, 0, slotsW, topH);
            blitPreviewPanelSlice(graphics, x + leftW + slotsW, y, rightSourceX, 0, rightW, topH);
            if (bottomH > 0) {
                int bottomY = y + topH;
                blitPreviewPanelSlice(graphics, x, bottomY, 0, bottomSourceY, leftW, bottomH);
                blitPreviewPanelSlice(graphics, x + leftW, bottomY, 8, bottomSourceY, slotsW, bottomH);
                blitPreviewPanelSlice(graphics, x + leftW + slotsW, bottomY, rightSourceX, bottomSourceY, rightW, bottomH);
            }

            graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, colors.fullTint());
            graphics.fill(x + 7, y + 6, x + panelW - 7, y + panelH - 6, withAlpha(colors.border(), 34));
            for (int i = 0; i < 5; i++) {
                int sx = x + 8 + i * 18;
                int sy = y + 7;
                graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, withAlpha(colors.border(), 26));
            }
            int softHighlight = withAlpha(blendColor(colors.border(), 0xFFFFFFFF, 0.45f), 28);
            graphics.fill(x + 3, y + 3, x + panelW - 3, y + 5, softHighlight);
            graphics.fill(x + 3, y + 5, x + 5, y + panelH - 3, softHighlight);
        } else {
            int bg = glass ? withAlpha(compactBase, 170) : blendColor(compactBase, 0xFF000000, 0.16f);
            int face = glass ? withAlpha(blendColor(compactBase, 0xFFFFFFFF, 0.18f), 92)
                    : blendColor(compactBase, 0xFFFFFFFF, 0.10f);
            int edge = withAlpha(colors.border(), 245);
            int light = withAlpha(blendColor(colors.border(), 0xFFFFFFFF, 0.50f), 120);
            int shadow = withAlpha(blendColor(colors.border(), 0xFF000000, 0.55f), 170);
            graphics.fill(x, y, x + panelW, y + panelH, bg);
            graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, face);
            graphics.fill(x, y, x + panelW, y + 1, light);
            graphics.fill(x, y + 1, x + 1, y + panelH, light);
            graphics.fill(x, y + panelH - 1, x + panelW, y + panelH, shadow);
            graphics.fill(x + panelW - 1, y, x + panelW, y + panelH, shadow);
            drawStaticFrame(graphics, x + 1, y + 1, panelW - 2, panelH - 2, edge);
            for (int i = 0; i < 5; i++) {
                drawCompactSlotPreview(graphics, x + 8 + i * 18, y + 7, compactBase, colors.border(), 18);
            }
        }

        int[] itemColors = {0xFF9A6A34, 0xFFC08A54, 0xFF51391D, 0xFF15D96A, 0xFF8B5A3B};
        for (int i = 0; i < 5; i++) {
            int itemX = x + 9 + i * 18;
            int itemY = y + 8;
            graphics.fill(itemX + 2, itemY + 2, itemX + 15, itemY + 15, itemColors[i]);
            String count = i == 0 ? "28" : "64";
            graphics.text(font, text(count), itemX + 15 - font.width(count), itemY + 9, 0xFFFFFFFF);
        }
        drawSelectedSlotPreview(graphics, x + 8, y + 7, sel);

        // Named from the actual binding, and dropped when there is none, so the preview shows the
        // row the tooltip will really draw rather than always spelling out "V".
        String keyName = ClientKeybinds.getShowFullTooltipKeyName();
        if (keyName.isEmpty()) return;
        String hint = keyName + ": Full contents";
        int hintX = x + Math.max(4, (panelW - font.width(hint)) / 2);
        graphics.text(font, text(hint), hintX + 1, y + panelH + 3 + 1, 0xAA000000);
        graphics.text(font, text(hint), hintX, y + panelH + 3, 0xFFFFD700);
    }

    private static void blitPreviewPanelSlice(GuiGraphicsExtractor graphics, int x, int y, int u, int v, int w, int h) {
        if (w <= 0 || h <= 0) return;
        graphics.blit(RenderPipelines.GUI_TEXTURED,
                PREVIEW_SHULKER_PANEL_TEXTURE,
                x,
                y,
                PREVIEW_PANEL_TEXTURE_U + u,
                PREVIEW_PANEL_TEXTURE_V + v,
                w,
                h,
                256,
                256,
                0xFFFFFFFF);
    }

    private static void drawCompactSlotPreview(GuiGraphicsExtractor graphics, int slotX, int slotY, int baseColor, int borderColor, int size) {
        boolean lightBase = getTextColorForBackground(baseColor) == 0xFF373737;
        int outer = withAlpha(blendColor(borderColor, baseColor, 0.35f), 210);
        int inner = lightBase
                ? withAlpha(blendColor(baseColor, 0xFFFFFFFF, 0.12f), 238)
                : withAlpha(blendColor(baseColor, 0xFF000000, 0.50f), 238);
        int high = lightBase ? 0x80FFFFFF : 0x45FFFFFF;
        int low = lightBase ? 0x44000000 : 0x70000000;
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
        PreviewColors themeColors = resolveThemePreviewColors(state, theme, nameText);

        BetterShulkerConfig.TooltipStyle style = state.style == null || state.style.getValue() == null
                ? BetterShulkerConfig.getTooltipStyle()
                : state.style.getValue();
        if (style != BetterShulkerConfig.TooltipStyle.MODERN) {
            return themeColors;
        }
        // Modern ignores the theme completely; its palette comes from the container dye.
        return new PreviewColors(MODERN_PREVIEW_FILL, MODERN_PREVIEW_BORDER, MODERN_PREVIEW_BORDER,
                MODERN_PREVIEW_BORDER, 0xFFFFD700, nameText, 0x00000000, false,
                MODERN_PREVIEW_FILL, false, true);
    }

    private static PreviewColors resolveThemePreviewColors(CustomPreviewState state,
                                                           BetterShulkerConfig.TooltipTheme theme,
                                                           int nameText) {
        return switch (theme) {
            case ORIGINAL -> new PreviewColors(0xFF2B0B3A, 0xFF8932B8, 0xE0100018, 0xFF8932B8, 0xFFFFD700, nameText, 0x65100018, true, 0xFF6F2D8F, false, false);
            case CLASSIC -> new PreviewColors(0xFF2D4A1A, 0xFF4A7A25, 0xE02D4A1A, 0xFF4A7A25, 0xFFA7E060, nameText, 0x702D4A1A, true, 0xFF2D4A1A, false, false);
            case RETRO -> new PreviewColors(0xFF080812, 0xFFFF00FF, 0xE0080812, 0xFFFF00FF, 0xFF00FFFF, nameText, 0x70080812, true, 0xFF1A0028, false, false);
            case SOLARIZED_DARK -> new PreviewColors(0xFF002B36, 0xFF268BD2, 0xE0002B36, 0xFF268BD2, 0xFFB58900, nameText, 0x76002B36, true, 0xFF002B36, false, false);
            case SOLARIZED_LIGHT -> new PreviewColors(0xFFFDF6E3, 0xFF268BD2, 0xEEFDF6E3, 0xFF268BD2, 0xFFCB4B16, nameText, 0x88FDF6E3, true, 0xFFFDF6E3, false, false);
            case HIGH_CONTRAST -> new PreviewColors(0xFF000000, 0xFFFFAA00, 0xF0000000, 0xFFFFAA00, 0xFFFFFF00, nameText, 0x88000000, true, 0xFF000000, false, false);
            case GLASS -> new PreviewColors(0xDDEAF7FF, 0xB8FFFFFF, 0xDDEAF7FF, 0xB8FFFFFF, 0xFFFFD700, nameText, 0x32FFFFFF, false, 0xFFEAF7FF, true, false);
            case CUSTOM -> new PreviewColors(
                    state.background.color(),
                    state.border.color(),
                    state.nameBackground.color(),
                    state.nameBorder.color(),
                    state.selection.color(),
                    nameText,
                    normalizeOverlayAlpha(state.background.color(), 112),
                    true,
                    0xFF000000 | (state.background.color() & 0x00FFFFFF),
                    false,
                    false
            );
        };
    }

    public record PreviewColors(int background, int border, int nameBackground, int nameBorder, int selection,
                                 int nameText, int fullTint, boolean usesVanillaPanelTexture,
                                 int compactBase, boolean glassCompact, boolean modern) {}

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

    /**
     * The two name tabs Modern sits on top of its card, as {@code ModernCardPainter.drawNameTabs}
     * draws them: the container's name on the left, the selected item's on the right, each running
     * two pixels into the card so tab and card read as one shape.
     *
     * <p>The real pair share the tooltip's width and give ground to each other when both names are
     * long. Preview names are short and the card is a fixed 176 wide, so only the both-fit case
     * can arise here.</p>
     */
    private static void drawModernNameTabsPreview(GuiGraphicsExtractor graphics, Font font, PreviewColors colors,
                                                  int panelX, int panelY, int panelW) {
        int tabY = panelY - MODERN_NAME_TAB_HEIGHT;
        int tabHeight = panelY + 2 - tabY;
        int containerW = font.width(PREVIEW_CONTAINER_NAME) + 12;
        int selectedW = font.width(PREVIEW_SELECTED_NAME) + 12;

        drawModernTabPreview(graphics, font, colors, panelX, tabY, containerW, tabHeight, PREVIEW_CONTAINER_NAME);
        drawModernTabPreview(graphics, font, colors, panelX + panelW - selectedW, tabY, selectedW, tabHeight,
                PREVIEW_SELECTED_NAME);
    }

    private static void drawModernTabPreview(GuiGraphicsExtractor graphics, Font font, PreviewColors colors,
                                             int x, int y, int w, int h, String label) {
        fillTopRoundedPreview(graphics, x, y, w, h, colors.border());
        fillTopRoundedPreview(graphics, x + 2, y + 2, w - 4, h, colors.background());
        graphics.text(font, text(label), x + 6, y + 4, 0xFFFFFFFF);
    }

    private static void fillTopRoundedPreview(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w <= 4 || h <= 2) {
            graphics.fill(x, y, x + w, y + h, color);
            return;
        }
        graphics.fill(x + 2, y, x + w - 2, y + 1, color);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        graphics.fill(x, y + 2, x + w, y + h, color);
    }

    /**
     * The selected slot as {@code ShulkerTooltipComponent.drawSelectedSlot} draws it: the cell
     * lights up, with no frame around it.
     *
     * <p>Alpha 64 is the midpoint of the pulse the real square breathes through; a still preview
     * has no business animating.</p>
     */
    private static void drawSelectedSlotPreview(GuiGraphicsExtractor graphics, int slotX, int slotY, int sel) {
        graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, withAlpha(sel, 64));
    }

    private static void drawStaticFrame(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static final class CustomPreviewState {
        public AbstractConfigListEntry<BetterShulkerConfig.ResourcePackMode> resourcePackMode;
        public AbstractConfigListEntry<BetterShulkerConfig.TooltipTheme> theme;
        public AbstractConfigListEntry<BetterShulkerConfig.TooltipStyle> style;
        public ColorSliders background;
        public ColorSliders border;
        public ColorSliders nameBackground;
        public ColorSliders nameBorder;
        public ColorSliders nameText;
        public ColorSliders selection;
        public Identifier resourcePackTexture;
        public ResourcePackLayout resourcePackLayout;
        public boolean resourcePackDetected;
        public IntegerSliderEntry resourcePackOffsetX;
        public IntegerSliderEntry resourcePackOffsetY;
        public IntegerSliderEntry resourcePackCapHeight;

        public boolean ready() {
            return theme != null && background != null && border != null && nameBackground != null && nameBorder != null && nameText != null && selection != null;
        }

        public boolean resourceReady() {
            return resourcePackTexture != null && resourcePackLayout != null
                    && resourcePackOffsetX != null && resourcePackOffsetY != null
                    && resourcePackCapHeight != null;
        }
    }

    public record ColorSliders(IntegerSliderEntry red, IntegerSliderEntry green, IntegerSliderEntry blue) {
        public int color() {
            return 0xFF000000 | (red.getValue() << 16) | (green.getValue() << 8) | blue.getValue();
        }
    }

    static void refreshResourcePackPreview(LiveTooltipPreview.CustomPreviewState previewState) {
        if (previewState.resourcePackOffsetX == null
                || previewState.resourcePackOffsetY == null
                || previewState.resourcePackCapHeight == null) {
            return;
        }

        ResourcePackContainerTextures.Panel panel = ResourcePackContainerTextures.resolveDetected(null, false);
        previewState.resourcePackTexture = panel.texture();
        previewState.resourcePackLayout = ResourcePackLayoutProfiles.resolveBase(panel.texture());
        previewState.resourcePackDetected = panel.suppliedByPack();
    }
}

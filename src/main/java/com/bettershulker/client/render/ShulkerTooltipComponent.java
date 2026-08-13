package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;

import java.util.List;
import java.util.Optional;

import static com.bettershulker.client.render.ThemeColorUtil.blendColor;
import static com.bettershulker.client.render.ThemeColorUtil.normalizeOverlayAlpha;
import static com.bettershulker.client.render.ThemeColorUtil.opaqueOrDefault;
import static com.bettershulker.client.render.ThemeColorUtil.withAlpha;

/**
 * Interactive shulker/ender chest tooltip renderer.
 *
 * The base panel is sampled from vanilla container textures so resource packs that recolor
 * shulker/container GUIs can affect the preview. Better Shulker themes are applied as
 * accent/tint/highlight layers instead of replacing the resource-pack look.
 */
public class ShulkerTooltipComponent implements ClientTooltipComponent {

    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 3;
    private static final int COMPACT_MAX_SLOTS = 5;
    private static final int SLOT_COUNT = GRID_COLS * GRID_ROWS;

    /** Vanilla shulker/container textures are 176px wide. We crop the storage slot band. */
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 68;
    private static final int SLOT_START_X = 8;
    private static final int SLOT_START_Y = 7;
    private static final int TOOLTIP_BOTTOM_PADDING = 6;
    private static final int COMPACT_HINT_HEIGHT = 13;
    private static final int NAME_BADGE_HEIGHT = 14;
    private static final int NAME_BADGE_GAP = 2;
    // Modern name tab. Its text sits at tabY + 4, which lands exactly on the vanilla
    // container-name line (panelY - CONTAINER_NAME_LINE_HEIGHT - CONTAINER_NAME_LINE_GAP).
    private static final int NAME_TAB_HEIGHT = 16;
    /** Below this the selected-name tab would be all padding and an ellipsis, so it is dropped. */
    private static final int MIN_SELECTED_TAB_WIDTH = 28;
    private static final int NAME_TAB_GAP = 2;
    /** Below this the card is too dark to derive shades by darkening; they are lightened instead. */
    private static final float MODERN_DARK_FILL_LUMINANCE = 0.15f;
    /** Gap the full 176px card leaves right of its grid: 176 - 8 - 9*18. */
    private static final int MODERN_GRID_RIGHT_MARGIN = 6;
    // ClientTextTooltip is ten pixels high and GuiGraphicsExtractor adds a two-pixel
    // gap after the first text line. Keep the custom name badge aligned with the
    // vanilla selected-item tooltip above the container title.
    private static final int CONTAINER_NAME_LINE_HEIGHT = 10;
    private static final int CONTAINER_NAME_LINE_GAP = 2;

    private static final Identifier SHULKER_PANEL_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final int ENDER_ACCENT_COLOR = 0xFF00E6C8;
    private static final int ENDER_PURPLE_COLOR = 0xFF34104E;
    private static final int ENDER_DARK_COLOR = 0xFF06120F;

    /** Crop selected so vanilla shulker slots line up at y=7 inside our compact preview. */
    private static final float PANEL_TEXTURE_U = 0.0F;
    private static final float PANEL_TEXTURE_V = 11.0F;

    private final NonNullList<ItemStack> contents;
    private final DyeColor color;
    private final boolean isEnderChest;
    private final String selectedItemName;
    private final String containerName;
    private final boolean isContainerEmpty;
    private final boolean compactMode;
    private final ResourcePackContainerTextures.Panel panelTexture;
    private final boolean resourcePackOverridesPanel;
    private final List<Integer> displaySlots;
    private final List<Integer> displayCounts;
    private final int displayCols;
    private final int displayRows;
    private final int panelWidth;
    private final int panelHeight;

    private final int borderColor;
    private final int nameBorderColor;
    private final int tintColor;
    private final int badgeBgColor;
    private final int selectionColor;
    private final int multiSelectColor;
    private final int panelShadowColor;

    /** Merged stacks compact mode had no room to preview, reported in its footer. */
    private final int hiddenCompactStacks;

    private record DisplayLayout(List<Integer> slots, List<Integer> counts, int hiddenStacks) {}

    public ShulkerTooltipComponent(ShulkerTooltipData data) {
        this.contents = data.contents();
        this.color = data.color();
        this.isEnderChest = data.isEnderChest();
        this.selectedItemName = data.selectedItemName();
        this.containerName = data.containerName();

        boolean empty = true;
        for (ItemStack stack : this.contents) {
            if (!stack.isEmpty()) {
                empty = false;
                break;
            }
        }
        this.isContainerEmpty = empty;
        this.compactMode = BetterShulkerClient.isCompactModeActive();
        this.panelTexture = ResourcePackContainerTextures.resolve(this.color, this.isEnderChest, this.containerName);
        this.resourcePackOverridesPanel = this.panelTexture.suppliedByPack();
        DisplayLayout displayLayout = buildDisplayLayout();
        this.displaySlots = displayLayout.slots();
        this.displayCounts = displayLayout.counts();
        this.hiddenCompactStacks = displayLayout.hiddenStacks();
        this.displayCols = this.compactMode
                ? Math.min(COMPACT_MAX_SLOTS, Math.max(1, this.displaySlots.size()))
                : (this.resourcePackOverridesPanel ? this.panelTexture.layout().columns() : GRID_COLS);
        this.displayRows = this.compactMode
                ? Math.max(1, (this.displaySlots.size() + this.displayCols - 1) / this.displayCols)
                : (this.resourcePackOverridesPanel ? this.panelTexture.layout().rows() : GRID_ROWS);
        int compactCellSize = getRenderedSlotSize();
        int compactResourcePackWidth = this.panelTexture.layout().panelWidth(this.displayCols);
        this.panelWidth = this.compactMode
                ? (this.isContainerEmpty ? 0 : (this.resourcePackOverridesPanel
                        ? compactResourcePackWidth
                        : 14 + (this.displayCols * compactCellSize)))
                : (this.resourcePackOverridesPanel
                        ? this.panelTexture.layout().panelWidth(this.displayCols)
                        : PANEL_WIDTH);
        this.panelHeight = this.compactMode
                ? (this.isContainerEmpty ? 0 : (this.resourcePackOverridesPanel
                        ? this.panelTexture.layout().panelHeight(this.displayRows)
                        : 14 + (this.displayRows * compactCellSize)))
                : (this.resourcePackOverridesPanel
                        ? this.panelTexture.layout().panelHeight(this.displayRows)
                        : PANEL_HEIGHT);

        ThemePalette palette = buildThemePalette();
        this.borderColor = palette.borderColor;
        this.nameBorderColor = palette.nameBorderColor;
        this.tintColor = palette.tintColor;
        this.badgeBgColor = palette.badgeBgColor;
        this.selectionColor = palette.selectionColor;
        this.multiSelectColor = palette.multiSelectColor;
        this.panelShadowColor = palette.panelShadowColor;
    }

    private DisplayLayout buildDisplayLayout() {
        List<Integer> slots = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int hidden = 0;
        if (this.compactMode && this.isContainerEmpty) {
            return new DisplayLayout(slots, counts, 0);
        } else if (this.compactMode) {
            for (int i = 0; i < this.contents.size() && i < SLOT_COUNT; i++) {
                ItemStack stack = this.contents.get(i);
                if (stack.isEmpty()) continue;

                int existingIndex = -1;
                for (int displayIndex = 0; displayIndex < slots.size(); displayIndex++) {
                    if (ItemStack.isSameItemSameComponents(this.contents.get(slots.get(displayIndex)), stack)) {
                        existingIndex = displayIndex;
                        break;
                    }
                }
                if (existingIndex >= 0) {
                    counts.set(existingIndex, counts.get(existingIndex) + stack.getCount());
                } else {
                    slots.add(i);
                    counts.add(stack.getCount());
                }
            }

            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) order.add(i);
            List<Integer> mergedSlots = slots;
            List<Integer> mergedCounts = counts;
            order.sort((a, b) -> {
                int countCompare = Integer.compare(mergedCounts.get(b), mergedCounts.get(a));
                return countCompare != 0 ? countCompare : Integer.compare(mergedSlots.get(a), mergedSlots.get(b));
            });

            List<Integer> sortedSlots = new ArrayList<>();
            List<Integer> sortedCounts = new ArrayList<>();
            for (int i = 0; i < order.size() && i < COMPACT_MAX_SLOTS; i++) {
                int index = order.get(i);
                sortedSlots.add(mergedSlots.get(index));
                sortedCounts.add(mergedCounts.get(index));
            }
            hidden = Math.max(0, order.size() - COMPACT_MAX_SLOTS);
            slots = sortedSlots;
            counts = sortedCounts;
        } else {
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots.add(i);
                counts.add(i < this.contents.size() ? this.contents.get(i).getCount() : 0);
            }
        }
        return new DisplayLayout(slots, counts, hidden);
    }

    @Override
    public int getHeight(Font textRenderer) {
        if (this.compactMode) {
            return getPanelHeight() + (showsCompactHint() ? COMPACT_HINT_HEIGHT : 0);
        }
        return getPanelHeight() + TOOLTIP_BOTTOM_PADDING;
    }

    @Override
    public int getWidth(Font textRenderer) {
        if (this.compactMode) {
            int compactWidth = showsCompactHint()
                    ? Math.max(getPanelWidth(), textRenderer.width(getCompactFullHintText()) + 12)
                    : getPanelWidth();
            if (this.containerName != null && !this.containerName.isEmpty()) {
                // Room for the name tab's own padding. Without it the tooltip is only as wide as
                // the bare name line, and the tab has to ellipsise a name that would have fit.
                compactWidth = Math.max(compactWidth, textRenderer.width(this.containerName) + 12);
            }
            return compactWidth;
        }
        int width = getPanelWidth();
        if (this.containerName != null && !this.containerName.isEmpty()) {
            width = Math.max(width, textRenderer.width(this.containerName) + 20);
        }
        return width;
    }

    @Override
    public boolean showTooltipWithItemInHand() {
        return BetterShulkerConfig.altForceTooltipEnabled
                && BetterShulkerClient.isKeyHeld(BetterShulkerClient.getAltForceKey());
    }

    @Override
    public void extractImage(Font textRenderer, int tooltipX, int tooltipY, int width, int height, GuiGraphicsExtractor context) {
        long now = System.currentTimeMillis();

        boolean resourcePackMode = this.resourcePackOverridesPanel;
        boolean hasCompactPreview = this.compactMode && !this.isContainerEmpty && !this.displaySlots.isEmpty();
        // The Modern card is drawn from scratch, so the vanilla-texture aura/overlay/accent
        // passes below would only fight its flat rounded shape. It still honours an active
        // resource pack, by taking that pack's colour and slot grid rather than standing down.
        boolean modernMode = isModernStyle();

        // A compact card is only as wide as its handful of slots, but the tooltip around it is
        // already as wide as the name line and the hint below force it. Stretching the card to
        // fill that width costs no extra space and stops the name tabs, which are sized from the
        // names rather than the slot count, from hanging off both edges of a tiny card.
        boolean modernCompact = modernMode && this.compactMode;
        int cardWidth = modernCompact ? width : getPanelWidth();
        int panelX = modernCompact ? tooltipX : tooltipX + (width - getPanelWidth()) / 2;
        int panelY = tooltipY;

        if (!resourcePackMode && !modernMode && (!this.compactMode || hasCompactPreview)) {
            drawPanelAura(context, panelX, panelY, getPanelWidth(), getPanelHeight(), now);
        }
        if (this.compactMode) {
            if (hasCompactPreview) {
                drawCompactPanel(textRenderer, context, panelX, panelY, cardWidth);
            }
        } else if (modernMode) {
            // displayCols/Rows already carry the pack's grid when one is active, 9x3 otherwise.
            drawModernPanel(context, panelX, panelY, this.displayCols, this.displayRows, cardWidth);
        } else if (isGlassTheme() && !resourcePackMode) {
            drawGlassPanel(context, panelX, panelY);
        } else {
            drawResourcePackPanel(context, panelX, panelY);
        }
        if (modernMode && (!this.compactMode || hasCompactPreview)) {
            drawModernNameTabs(textRenderer, context, tooltipX, panelY, width);
        }
        if (!this.compactMode && !modernMode) {
            drawThemeOverlay(context, panelX, panelY);
            drawEnderChestAccents(context, panelX, panelY);
            drawEnderChestAnimation(context, panelX, panelY, now);
        }

        int hoveredSlot = updateHoveredSlot(panelX, panelY);
        if (!this.compactMode || hasCompactPreview) {
            // Highlights sit above the items: an item sprite covers nearly the whole cell, so a
            // fill underneath it would only show in the few pixels of margin around the edge.
            drawItemsAndSlotOverlays(textRenderer, context, panelX, panelY, hoveredSlot, now);
            drawSelectedSlot(context, panelX, panelY, now);
            drawMultiSelectedSlots(context, panelX, panelY);
            drawHoverHighlight(context, panelX, panelY, hoveredSlot, now);
            drawHoveredAndSelectedTooltips(textRenderer, context, hoveredSlot);
            drawSelectedNameBadge(textRenderer, context, panelX, panelY);
        }

        int extraY = panelY + getPanelHeight() + (this.compactMode ? 0 : TOOLTIP_BOTTOM_PADDING);
        if (this.compactMode) {
            drawCompactFullHint(textRenderer, context, tooltipX, extraY, width);
        }

        // Tiny theme-colored fill strip at the bottom, behind the same Fill Indicator toggle as
        // the in-slot bar it mirrors. Hidden under a resource-pack panel so the pack owns those
        // pixels without extra light lines/artifacts — except under Modern, which draws its own
        // card from scratch either way, ender chests included.
        if (BetterShulkerConfig.fillIndicatorEnabled && !this.compactMode
                && (modernMode || (!resourcePackMode && !this.isEnderChest))) {
            // Modern's bottom two pixels are its rounded border, so the strip moves up into the
            // card's own face, into the gap the grid leaves below itself when there is one.
            int stripInset = modernMode ? 5 : 2;
            int gridBottom = getSlotStartY() + this.displayRows * getRenderedSlotSize() + 1;
            if (getPanelHeight() - stripInset >= gridBottom) {
                drawFillStrip(context, panelX, panelY + getPanelHeight() - stripInset);
            }
        }
    }

    private void drawResourcePackPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        // Resource-pack mode uses the pack's actual panel texture without a fake tint.
        Identifier texture = getPanelTexture();

        int renderColor = getPanelRenderColor();
        if (!this.resourcePackOverridesPanel) {
            context.blit(RenderPipelines.GUI_TEXTURED,
                    texture, panelX, panelY, PANEL_TEXTURE_U, PANEL_TEXTURE_V,
                    PANEL_WIDTH, getPanelHeight(), 256, 256, renderColor);
            return;
        }

        ResourcePackLayout layout = this.panelTexture.layout();
        // The profile keeps the pack's own top edge and storage grid while discarding any
        // full-screen/player-inventory section below it.
        drawResourcePackHorizontalSlices(context, texture, panelX, panelY,
                layout.sourcePanelY(), layout.outputSlotY(), this.displayCols, renderColor);
        drawResourcePackStorageRows(context, texture, panelX,
                panelY + layout.outputSlotY(), this.displayRows, renderColor);
        drawResourcePackRepeatedRow(context, texture, panelX,
                panelY + layout.outputSlotY() + this.displayRows * layout.slotSize(),
                layout.bottomCapHeight(), renderColor);
    }

    private void drawCompactPanel(Font font, GuiGraphicsExtractor context, int panelX, int panelY, int cardWidth) {
        // Modern is checked first: under a pack it recolours its card instead of deferring.
        if (isModernStyle()) {
            drawModernPanel(context, panelX, panelY, this.displayCols, this.displayRows, cardWidth);
            return;
        }
        if (this.resourcePackOverridesPanel) {
            drawResourcePackCompactPanel(context, panelX, panelY);
            return;
        }

        drawFullStyleCompactPanel(context, panelX, panelY);
        drawFullStyleCompactOverlay(context, panelX, panelY);
    }

    private void drawResourcePackCompactPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        Identifier texture = getPanelTexture();
        int renderColor = getPanelRenderColor();

        ResourcePackLayout layout = this.panelTexture.layout();
        // Recompose the resource-pack panel instead of cropping the top-left. This preserves the
        // profile's left edge, slot grid, right cap, and bottom cap at compact widths.
        int leftW = layout.outputSlotX();
        int slotsW = this.displayCols * layout.slotSize();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int topH = layout.outputSlotY() + this.displayRows * layout.slotSize();
        int bottomH = Math.max(0, getPanelHeight() - topH);

        drawResourcePackHorizontalSlices(context, texture, panelX, panelY,
                layout.sourcePanelY(), layout.outputSlotY(), this.displayCols, renderColor);
        drawResourcePackStorageRows(context, texture, panelX,
                panelY + layout.outputSlotY(), this.displayRows,
                leftW, slotsW, rightSourceX, rightW, renderColor);

        if (bottomH > 0) {
            int bottomY = panelY + topH;
            drawResourcePackRepeatedRow(context, texture, panelX, bottomY, bottomH,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawResourcePackStorageRows(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int rows, int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        int leftW = layout.outputSlotX();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int slotsW = this.displayCols * layout.slotSize();
        drawResourcePackStorageRows(context, texture, panelX, y, rows,
                leftW, slotsW, rightSourceX, rightW, renderColor);
    }

    private void drawResourcePackStorageRows(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int rows,
                                             int leftW, int slotsW, int rightSourceX, int rightW,
                                             int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        int height = rows * layout.slotSize();
        int directHeight = Math.min(height, layout.rows() * layout.slotSize());
        if (directHeight > 0) {
            blitResourcePackHorizontalSlices(context, texture, panelX, y,
                    layout.sourceSlotY(), directHeight,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
        for (int row = directHeight; row < height; row++) {
            blitResourcePackHorizontalSlices(context, texture, panelX, y + row,
                    layout.bottomCapSourceY(), 1,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawResourcePackRepeatedRow(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int height, int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        int leftW = layout.outputSlotX();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int slotsW = this.displayCols * layout.slotSize();
        drawResourcePackRepeatedRow(context, texture, panelX, y, height,
                leftW, slotsW, rightSourceX, rightW, renderColor);
    }

    private void drawResourcePackRepeatedRow(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int height,
                                             int leftW, int slotsW, int rightSourceX, int rightW,
                                             int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        for (int row = 0; row < height; row++) {
            blitResourcePackHorizontalSlices(context, texture, panelX, y + row,
                    layout.bottomCapSourceY(), 1,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawResourcePackHorizontalSlices(GuiGraphicsExtractor context, Identifier texture,
                                                   int panelX, int y, int sourceY, int height,
                                                   int displayColumns, int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        int leftW = layout.outputSlotX();
        int slotsW = displayColumns * layout.slotSize();
        int rightW = Math.max(0, getPanelWidth() - leftW - slotsW);
        blitResourcePackHorizontalSlices(context, texture, panelX, y, sourceY, height,
                leftW, slotsW, layout.sourceRightX(), Math.min(rightW, layout.sourceRightWidth()), renderColor);
    }

    private void blitResourcePackHorizontalSlices(GuiGraphicsExtractor context, Identifier texture,
                                                   int panelX, int y, int sourceY, int height,
                                                   int leftW, int slotsW, int rightSourceX, int rightW,
                                                   int renderColor) {
        ResourcePackLayout layout = this.panelTexture.layout();
        blitResourcePackSlice(context, texture, panelX, y,
                layout.sourcePanelX(), sourceY, leftW, height, renderColor);
        blitResourcePackSlice(context, texture, panelX + leftW, y,
                layout.sourceSlotX(), sourceY, slotsW, height, renderColor);
        blitResourcePackSlice(context, texture, panelX + leftW + slotsW, y,
                rightSourceX, sourceY, rightW, height, renderColor);
    }

    private void blitResourcePackSlice(GuiGraphicsExtractor context, Identifier texture, int x, int y,
                                       int u, int v, int w, int h, int renderColor) {
        if (w <= 0 || h <= 0) return;
        context.blit(RenderPipelines.GUI_TEXTURED,
                texture,
                x,
                y,
                (float) u,
                (float) v,
                w,
                h,
                this.panelTexture.layout().textureWidth(),
                this.panelTexture.layout().textureHeight(),
                renderColor);
    }

    private int getCompactPanelBaseColor() {
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
        if (isModernStyle()) {
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

    private int getCompactContainerColor() {
        if (this.isEnderChest) {
            return blendColor(ENDER_PURPLE_COLOR, ENDER_ACCENT_COLOR, 0.18f);
        }
        if (this.color != null) {
            return 0xFF000000 | this.color.getTextureDiffuseColor();
        }
        return 0;
    }

    private void drawFullStyleCompactPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        if (isGlassTheme()) {
            drawGlassCompactPanel(context, panelX, panelY, getPanelWidth(), getPanelHeight(), getCompactPanelBaseColor());
            return;
        }

        Identifier texture = getPanelTexture();
        int renderColor = getPanelRenderColor();

        int leftW = SLOT_START_X;
        int rightSourceX = SLOT_START_X + GRID_COLS * SLOT_SIZE;
        int rightW = PANEL_WIDTH - rightSourceX;
        int slotsW = this.displayCols * SLOT_SIZE;
        int topH = SLOT_START_Y + this.displayRows * SLOT_SIZE;
        int bottomH = Math.max(0, getPanelHeight() - topH);
        int bottomSourceY = SLOT_START_Y + GRID_ROWS * SLOT_SIZE;

        blitFullStyleCompactSlice(context, texture, panelX, panelY,
                0, 0, leftW, topH, renderColor);
        blitFullStyleCompactSlice(context, texture, panelX + leftW, panelY,
                SLOT_START_X, 0, slotsW, topH, renderColor);
        blitFullStyleCompactSlice(context, texture, panelX + leftW + slotsW, panelY,
                rightSourceX, 0, rightW, topH, renderColor);

        if (bottomH > 0) {
            int bottomY = panelY + topH;
            blitFullStyleCompactSlice(context, texture, panelX, bottomY,
                    0, bottomSourceY, leftW, bottomH, renderColor);
            blitFullStyleCompactSlice(context, texture, panelX + leftW, bottomY,
                    SLOT_START_X, bottomSourceY, slotsW, bottomH, renderColor);
            blitFullStyleCompactSlice(context, texture, panelX + leftW + slotsW, bottomY,
                    rightSourceX, bottomSourceY, rightW, bottomH, renderColor);
        }
    }

    private void blitFullStyleCompactSlice(GuiGraphicsExtractor context, Identifier texture, int x, int y,
                                           int u, int v, int w, int h, int renderColor) {
        if (w <= 0 || h <= 0) return;
        context.blit(RenderPipelines.GUI_TEXTURED,
                texture,
                x,
                y,
                PANEL_TEXTURE_U + u,
                PANEL_TEXTURE_V + v,
                w,
                h,
                256,
                256,
                renderColor);
    }

    private void drawFullStyleCompactOverlay(GuiGraphicsExtractor context, int panelX, int panelY) {
        if (isGlassTheme() || isModernStyle() || this.resourcePackOverridesPanel) {
            return;
        }

        int w = getPanelWidth();
        int h = getPanelHeight();
        context.fill(panelX + 2, panelY + 2, panelX + w - 2, panelY + h - 2, this.tintColor);
        context.fill(panelX + 7, panelY + 6, panelX + w - 7, panelY + Math.max(7, h - 6), withAlpha(this.borderColor, 34));

        int slotTint = withAlpha(this.borderColor, 26);
        for (int displayPos = 0; displayPos < this.displaySlots.size(); displayPos++) {
            int slotX = getSlotX(panelX, displayPos);
            int slotY = getSlotY(panelY, displayPos);
            context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, slotTint);
        }

        int softHighlight = withAlpha(blendColor(this.borderColor, 0xFFFFFFFF, 0.45f), 28);
        context.fill(panelX + 3, panelY + 3, panelX + w - 3, panelY + 5, softHighlight);
        context.fill(panelX + 3, panelY + 5, panelX + 5, panelY + h - 3, softHighlight);

        if (this.isEnderChest) {
            drawCompactEnderChestBottomCap(context, panelX, panelY);
        }
    }

    private void drawCompactEnderChestBottomCap(GuiGraphicsExtractor context, int panelX, int panelY) {
        int capHeight = 7;
        int capY = panelY + getPanelHeight() - capHeight;
        int leftW = SLOT_START_X;
        int rightSourceX = SLOT_START_X + GRID_COLS * SLOT_SIZE;
        int rightW = PANEL_WIDTH - rightSourceX;
        int slotsW = this.displayCols * SLOT_SIZE;
        int sourceY = Math.round(PANEL_TEXTURE_V) + PANEL_HEIGHT - capHeight;

        blitCompactCapSlice(context, panelX, capY, 0, sourceY, leftW, capHeight);
        blitCompactCapSlice(context, panelX + leftW, capY, SLOT_START_X, sourceY, slotsW, capHeight);
        blitCompactCapSlice(context, panelX + leftW + slotsW, capY, rightSourceX, sourceY, rightW, capHeight);

        int tint = withAlpha(ENDER_ACCENT_COLOR, 28);
        context.fill(panelX + 7, capY + 1, panelX + getPanelWidth() - 7, capY + capHeight - 2, tint);
        int rim = withAlpha(blendColor(ENDER_ACCENT_COLOR, this.panelShadowColor, 0.75f), 190);
        context.fill(panelX + 6, capY + capHeight - 2, panelX + getPanelWidth() - 6, capY + capHeight - 1, rim);
    }

    private void blitCompactCapSlice(GuiGraphicsExtractor context, int x, int y, int u, int v, int w, int h) {
        if (w <= 0 || h <= 0) return;
        context.blit(RenderPipelines.GUI_TEXTURED,
                this.isEnderChest ? VanillaTooltipTextures.shulker() : SHULKER_PANEL_TEXTURE,
                x,
                y,
                (float) u,
                (float) v,
                w,
                h,
                256,
                256,
                0xFFFFFFFF);
    }

    private void drawGlassCompactPanel(GuiGraphicsExtractor context, int panelX, int panelY, int w, int h, int compactBase) {
        int bg = withAlpha(compactBase, 170);
        int face = withAlpha(blendColor(compactBase, 0xFFFFFFFF, 0.18f), 92);
        int edge = withAlpha(this.borderColor, 245);
        int light = withAlpha(blendColor(this.borderColor, 0xFFFFFFFF, 0.50f), 120);
        int shadow = withAlpha(blendColor(this.borderColor, 0xFF000000, 0.55f), 170);
        context.fill(panelX, panelY, panelX + w, panelY + h, bg);
        context.fill(panelX + 2, panelY + 2, panelX + w - 2, panelY + h - 2, face);
        context.fill(panelX, panelY, panelX + w, panelY + 1, light);
        context.fill(panelX, panelY + 1, panelX + 1, panelY + h, light);
        context.fill(panelX, panelY + h - 1, panelX + w, panelY + h, shadow);
        context.fill(panelX + w - 1, panelY, panelX + w, panelY + h, shadow);
        drawRectFrame(context, panelX + 1, panelY + 1, w - 2, h - 2, edge);
    }

    /**
     * Whether the compact card should carry its footer row.
     *
     * <p>The row holds the "hold this for the full grid" hint and the count of stacks that did
     * not fit the preview. An empty container has neither: there is no fuller view to offer and
     * nothing was left out, and compact mode already hides its panel in that case. Unbinding the
     * key drops the hint too, since it would otherwise advertise whatever placeholder the key
     * mapping reports. With nothing left to say, the row is given back to the tooltip.</p>
     */
    private boolean showsCompactHint() {
        return !this.isContainerEmpty && !getCompactFullHintText().isEmpty();
    }

    private String getCompactFullHintText() {
        String keyName = BetterShulkerClient.getShowFullTooltipKeyName();
        String hint = keyName.isEmpty() ? "" : keyName + ": Full contents";
        if (this.hiddenCompactStacks <= 0) return hint;
        // Compact keeps only the largest few stacks, and used to drop the rest without a word.
        // Saying how many are missing is what tells you the preview is not the whole box.
        String more = "+" + this.hiddenCompactStacks + " more";
        return hint.isEmpty() ? more : more + "  |  " + hint;
    }

    private void drawCompactFullHint(Font font, GuiGraphicsExtractor context, int x, int y, int width) {
        if (!showsCompactHint()) return;
        String hint = getCompactFullHintText();
        int textX = x + Math.max(4, (width - font.width(hint)) / 2);
        int textY = y + 3;
        context.text(font, Component.literal(hint), textX + 1, textY + 1, 0xAA000000);
        context.text(font, Component.literal(hint), textX, textY, 0xFFFFD700);
    }

    private int getPanelWidth() {
        return this.panelWidth;
    }

    private int getPanelHeight() {
        return this.panelHeight;
    }

    private int getPanelRenderColor() {
        // The resource pack owns its own colour treatment. Never apply an artificial dye tint.
        return 0xFFFFFFFF;
    }

    private void drawGlassPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        // True translucent glass mode: do not draw the opaque vanilla panel.
        // Instead draw a frosted Easy-style 9x3 container so world/inventory colors show through.
        context.fill(panelX + 1, panelY + 1, panelX + PANEL_WIDTH - 1, panelY + PANEL_HEIGHT - 1, 0x42FFFFFF);
        context.fill(panelX + 3, panelY + 3, panelX + PANEL_WIDTH - 3, panelY + PANEL_HEIGHT - 3, 0x26FFFFFF);
        context.fill(panelX + 2, panelY + PANEL_HEIGHT - 3, panelX + PANEL_WIDTH - 2, panelY + PANEL_HEIGHT - 1, 0x36000000);
        context.fill(panelX + PANEL_WIDTH - 3, panelY + 2, panelX + PANEL_WIDTH - 1, panelY + PANEL_HEIGHT - 2, 0x30000000);
        context.fill(panelX + 2, panelY + 2, panelX + PANEL_WIDTH - 2, panelY + 3, 0x70FFFFFF);
        context.fill(panelX + 2, panelY + 3, panelX + 3, panelY + PANEL_HEIGHT - 2, 0x55FFFFFF);

        for (int i = 0; i < SLOT_COUNT; i++) {
            int slotX = getSlotX(panelX, i);
            int slotY = getSlotY(panelY, i);
            context.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x45000000);
            context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0x38FFFFFF);
            context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + 2, 0x80FFFFFF);
            context.fill(slotX + 1, slotY + 2, slotX + 2, slotY + SLOT_SIZE - 1, 0x65FFFFFF);
            context.fill(slotX + 1, slotY + SLOT_SIZE - 2, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0x50000000);
            context.fill(slotX + SLOT_SIZE - 2, slotY + 2, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0x45000000);
        }
    }

    // =========================================================================
    //  Modern theme: a flat rounded card with a lattice slot grid, drawn from
    //  scratch instead of blitting the vanilla container texture. Every colour
    //  is derived from the container's own dye so each box keeps its identity.
    // =========================================================================

    /**
     * Panel texture the active pack supplies for this container, or null when it supplies none.
     *
     * <p>Ender chests are asked for separately: they intentionally keep Better Shulker's own
     * panel in the vanilla styles, so their pack texture never shows up as a panel override.</p>
     */
    private Identifier getModernPackPanelTexture() {
        if (this.resourcePackOverridesPanel) return this.panelTexture.texture();
        if (this.isEnderChest) return ResourcePackContainerTextures.packSuppliedEnderPanel();
        return null;
    }

    private int getModernDyeColor() {
        if (this.isEnderChest) {
            return blendColor(ENDER_PURPLE_COLOR, ENDER_ACCENT_COLOR, 0.22f);
        }
        if (this.color != null) {
            return 0xFF000000 | this.color.getTextureDiffuseColor();
        }
        return 0xFF8932B8;
    }

    /** Card face: the pack's own panel colour, or the dye pulled down towards neutral. */
    private int getModernPanelFill() {
        Identifier packPanel = getModernPackPanelTexture();
        if (packPanel != null) {
            // A sampled pack colour is already a panel colour authored to sit behind items, so
            // it is used as-is; muting it the way a raw dye needs would just fight the pack.
            int sampled = ResourcePackPanelColors.dominantColor(packPanel, 0);
            if (sampled != 0) return sampled;
        }
        return blendColor(getModernDyeColor(), 0xFF6A6A72, 0.55f);
    }

    private int getModernPanelBorder() {
        return shadeModernFill(0.30f);
    }

    private int getModernGridLine() {
        return shadeModernFill(0.20f);
    }

    private int getModernCellFill() {
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

    private void drawModernPanel(GuiGraphicsExtractor context, int panelX, int panelY, int cols, int rows, int cardWidth) {
        int w = cardWidth;
        int h = getPanelHeight();
        if (w <= 0 || h <= 0) return;

        int fill = getModernPanelFill();
        fillRounded(context, panelX, panelY, w, h, getModernPanelBorder());
        fillRounded(context, panelX + 2, panelY + 2, w - 4, h - 4, fill);

        // A one-pixel lift on top and a matching shade on the bottom keep the flat
        // card from reading as a plain rectangle.
        context.fill(panelX + 3, panelY + 2, panelX + w - 3, panelY + 3, blendColor(fill, 0xFFFFFFFF, 0.16f));
        context.fill(panelX + 3, panelY + h - 3, panelX + w - 3, panelY + h - 2, blendColor(fill, 0xFF000000, 0.18f));

        drawModernGrid(context, panelX, panelY, gridColumnsFor(cols, w), rows);
    }

    /**
     * Columns of lattice to draw across a card {@code cardWidth} wide.
     *
     * <p>Compact stretches its card to the tooltip width, which is set by the name tab and the
     * hint rather than by the slot count, so drawing only the occupied columns would leave most
     * of the card bare. Empty trailing cells are filled in, matching how the full 9x3 card shows
     * every slot whether or not it holds anything.</p>
     */
    private int gridColumnsFor(int cols, int cardWidth) {
        if (!this.compactMode) return cols;
        int usable = cardWidth - getSlotStartX() - MODERN_GRID_RIGHT_MARGIN;
        return Math.max(cols, usable / getRenderedSlotSize());
    }

    /** Slots are a shared lattice of single-pixel rules rather than 27 embossed wells. */
    private void drawModernGrid(GuiGraphicsExtractor context, int panelX, int panelY, int cols, int rows) {
        // Cell size follows the pack's layout when one is active, so the lattice lands exactly
        // where the items are drawn.
        int cell = getRenderedSlotSize();
        int gridX = panelX + getSlotStartX();
        int gridY = panelY + getSlotStartY();
        int gridW = cols * cell;
        int gridH = rows * cell;

        context.fill(gridX, gridY, gridX + gridW, gridY + gridH, getModernCellFill());

        int line = getModernGridLine();
        for (int col = 0; col <= cols; col++) {
            int lineX = gridX + col * cell;
            context.fill(lineX, gridY, lineX + 1, gridY + gridH + 1, line);
        }
        for (int row = 0; row <= rows; row++) {
            int lineY = gridY + row * cell;
            context.fill(gridX, lineY, gridX + gridW + 1, lineY + 1, line);
        }
    }

    /**
     * Paints the container name and the selected item name into two tabs sharing one row on top
     * of the card: container on the left, selected item on the right.
     *
     * <p>The tooltip framework already drew the container name as a plain text line directly
     * above us, and text is rendered before images, so the left tab covers that line and
     * reprints it in place. The left tab is anchored to the tooltip's own left edge rather than
     * the panel's, because a long custom name widens the tooltip past the panel and re-centres
     * the panel away from that text.</p>
     */
    private void drawModernNameTabs(Font font, GuiGraphicsExtractor context, int tooltipX, int panelY, int tooltipWidth) {
        if (getPanelWidth() <= 0) return;

        int tabY = panelY - NAME_TAB_HEIGHT;
        // Runs two pixels past the card's top border so tab and card read as one shape.
        int tabHeight = panelY + 2 - tabY;

        boolean hasContainerName = this.containerName != null && !this.containerName.isEmpty();
        String selectedName = getModernSelectedTabName();

        int containerTabWidth = 0;
        int selectedTabWidth = 0;
        if (hasContainerName && selectedName != null) {
            int available = tooltipWidth - NAME_TAB_GAP;
            int containerNeed = font.width(this.containerName) + 12;
            int selectedNeed = font.width(selectedName) + 12;

            if (containerNeed + selectedNeed <= available) {
                containerTabWidth = containerNeed;
                selectedTabWidth = selectedNeed;
            } else if (this.compactMode) {
                // A compact row is too narrow to split without reducing both names to an
                // ellipsis. The container name keeps it; the item name is still one hover away.
                containerTabWidth = Math.min(tooltipWidth, containerNeed);
            } else {
                // Neither tab may take more than half unless the other one wants less than that,
                // in which case the leftover goes to whichever is still short.
                int half = available / 2;
                if (containerNeed <= half) {
                    containerTabWidth = containerNeed;
                    selectedTabWidth = available - containerTabWidth;
                } else if (selectedNeed <= half) {
                    selectedTabWidth = selectedNeed;
                    containerTabWidth = available - selectedTabWidth;
                } else {
                    containerTabWidth = half;
                    selectedTabWidth = available - half;
                }
            }

            // A tab squeezed below this is all padding and an ellipsis; give the row back instead.
            if (selectedTabWidth < MIN_SELECTED_TAB_WIDTH) {
                selectedTabWidth = 0;
                containerTabWidth = Math.min(tooltipWidth, containerNeed);
            }
        } else if (hasContainerName) {
            containerTabWidth = Math.min(tooltipWidth, font.width(this.containerName) + 12);
        } else if (selectedName != null) {
            selectedTabWidth = Math.min(tooltipWidth, font.width(selectedName) + 12);
        }

        if (containerTabWidth > 0) {
            drawModernTab(font, context, tooltipX, tabY, containerTabWidth, tabHeight,
                    fitText(font, this.containerName, Math.max(1, containerTabWidth - 12)));
        }
        if (selectedTabWidth > 0) {
            drawModernTab(font, context, tooltipX + tooltipWidth - selectedTabWidth, tabY,
                    selectedTabWidth, tabHeight,
                    fitText(font, selectedName, Math.max(1, selectedTabWidth - 12)));
        }
    }

    /** Name for the right-hand tab, or null when there is nothing to show there. */
    private String getModernSelectedTabName() {
        if (!BetterShulkerConfig.selectedItemNameEnabled) return null;
        ItemStack selectedStack = this.compactMode ? getCompactNameStack() : getSelectedNameStack();
        if (selectedStack.isEmpty()) return null;
        String name = selectedStack.getHoverName().getString();
        return name.isEmpty() ? null : name;
    }

    private void drawModernTab(Font font, GuiGraphicsExtractor context, int x, int y, int w, int h, String label) {
        fillTopRounded(context, x, y, w, h, getModernPanelBorder());
        fillTopRounded(context, x + 2, y + 2, w - 4, h, getModernPanelFill());
        context.text(font, Component.literal(label), x + 6, y + 4, 0xFFFFFFFF);
    }

    private void fillRounded(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w <= 4 || h <= 4) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        context.fill(x + 2, y, x + w - 2, y + 1, color);
        context.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        context.fill(x, y + 2, x + w, y + h - 2, color);
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
        context.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
    }

    private void fillTopRounded(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w <= 4 || h <= 2) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        context.fill(x + 2, y, x + w - 2, y + 1, color);
        context.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        context.fill(x, y + 2, x + w, y + h, color);
    }

    private Identifier getPanelTexture() {
        return this.isEnderChest ? VanillaTooltipTextures.generic54() : this.panelTexture.texture();
    }

    private void drawEnderChestAccents(GuiGraphicsExtractor context, int panelX, int panelY) {
        if (!this.isEnderChest || isGlassTheme()) {
            return;
        }

        int accent = withAlpha(ENDER_ACCENT_COLOR, 165);
        int soft = withAlpha(ENDER_ACCENT_COLOR, 42);
        int purple = withAlpha(ENDER_PURPLE_COLOR, 75);
        int bottomTint = withAlpha(ENDER_ACCENT_COLOR, 34);
        int capHeight = 7;
        int capY = panelY + getPanelHeight() - capHeight;

        context.fill(panelX + 5, panelY + 4, panelX + PANEL_WIDTH - 5, panelY + 5, soft);
        context.fill(panelX + 4, panelY + 5, panelX + 5, panelY + getPanelHeight() - 5, soft);
        context.fill(panelX + PANEL_WIDTH - 5, panelY + 5, panelX + PANEL_WIDTH - 4, panelY + getPanelHeight() - 5, soft);
        context.fill(panelX + 8, panelY + 6, panelX + 22, panelY + 7, accent);
        context.fill(panelX + PANEL_WIDTH - 22, panelY + 6, panelX + PANEL_WIDTH - 8, panelY + 7, accent);
        context.fill(panelX + PANEL_WIDTH / 2 - 12, panelY + 5, panelX + PANEL_WIDTH / 2 + 12, panelY + 6, purple);

        // Existing Ender tooltip cap: reuse the regular Shulker-shaped closure, then lightly tint it.
        context.blit(RenderPipelines.GUI_TEXTURED,
                VanillaTooltipTextures.shulker(),
                panelX,
                capY,
                PANEL_TEXTURE_U,
                PANEL_TEXTURE_V + PANEL_HEIGHT - capHeight,
                PANEL_WIDTH,
                capHeight,
                256,
                256,
                0xFFFFFFFF);
        context.fill(panelX + 7, capY + 1, panelX + PANEL_WIDTH - 7, capY + capHeight - 2, bottomTint);
    }

    private void drawEnderChestAnimation(GuiGraphicsExtractor context, int panelX, int panelY, long now) {
        if (!this.isEnderChest || this.resourcePackOverridesPanel || isGlassTheme()
                || !BetterShulkerConfig.hoverAnimationsEnabled) {
            return;
        }

        float pulse = (float) Math.sin(now / 420.0) * 0.5f + 0.5f;
        int glowAlpha = 32 + Math.round(38 * pulse);
        int purpleGlow = withAlpha(ENDER_PURPLE_COLOR, 28 + Math.round(30 * (1.0f - pulse)));
        int panelBottom = panelY + getPanelHeight();

        // Slow Ender pulse along the top cap only; keep the shulker-matched bottom cap clean.
        context.fill(panelX + 10, panelY + 7, panelX + PANEL_WIDTH - 10, panelY + 8, purpleGlow);

        // Tiny deterministic portal motes around the border for identity without distracting from items.
        for (int i = 0; i < 7; i++) {
            double t = (now / 1000.0) + i * 0.73;
            int x = panelX + 12 + Math.floorMod((int) (i * 29 + now / 80), PANEL_WIDTH - 24);
            int y = (i % 2 == 0)
                    ? panelY + 6 + (int) (Math.sin(t) * 2.0)
                    : panelBottom - 9 + (int) (Math.cos(t) * 2.0);
            int alpha = 75 + (int) (55 * (Math.sin(t * 1.7) * 0.5 + 0.5));
            int color = (i % 3 == 0) ? withAlpha(ENDER_PURPLE_COLOR, alpha) : withAlpha(ENDER_ACCENT_COLOR, alpha);
            context.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawThemeOverlay(GuiGraphicsExtractor context, int panelX, int panelY) {
        if (isGlassTheme() && !this.resourcePackOverridesPanel) {
            return;
        }
        if (this.resourcePackOverridesPanel) {
            // Automatic resource-pack priority: when the active pack overrides the vanilla
            // container texture, let it own the panel/body/slot colors. Keep Better Shulker
            // themes for interactive overlays only.
            return;
        }

        // Theme priority for vanilla/default textures: resource packs provide the base
        // shape/details, and the selected Better Shulker theme tints the actual container face.
        context.fill(panelX + 2, panelY + 2, panelX + PANEL_WIDTH - 2, panelY + PANEL_HEIGHT - 2, this.tintColor);

        int themeBodyColor = withAlpha(this.borderColor, 34);
        context.fill(panelX + 7, panelY + 6, panelX + PANEL_WIDTH - 7, panelY + 62, themeBodyColor);

        int slotTint = withAlpha(this.borderColor, 26);
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slotX = getSlotX(panelX, i);
            int slotY = getSlotY(panelY, i);
            context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, slotTint);
        }

        int softHighlight = withAlpha(blendColor(this.borderColor, 0xFFFFFFFF, 0.45f), 28);
        context.fill(panelX + 3, panelY + 3, panelX + PANEL_WIDTH - 3, panelY + 5, softHighlight);
        context.fill(panelX + 3, panelY + 5, panelX + 5, panelY + PANEL_HEIGHT - 3, softHighlight);
    }

    private void drawPanelAura(GuiGraphicsExtractor context, int x, int y, int w, int h, long now) {
        if (!BetterShulkerConfig.hoverAnimationsEnabled) return;
        float pulse = (float) Math.sin(now / 260.0) * 0.25f + 0.75f;
        int raw = this.borderColor & 0x00FFFFFF;
        int a1 = (int) (24 * pulse);
        int a2 = (int) (12 * pulse);
        context.fill(x - 2, y - 2, x + w + 2, y - 1, (a2 << 24) | raw);
        context.fill(x - 2, y + h + 1, x + w + 2, y + h + 2, (a2 << 24) | raw);
        context.fill(x - 2, y - 1, x - 1, y + h + 1, (a2 << 24) | raw);
        context.fill(x + w + 1, y - 1, x + w + 2, y + h + 1, (a2 << 24) | raw);
        context.fill(x - 1, y - 1, x + w + 1, y, (a1 << 24) | raw);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, (a1 << 24) | raw);
        context.fill(x - 1, y, x, y + h, (a1 << 24) | raw);
        context.fill(x + w, y, x + w + 1, y + h, (a1 << 24) | raw);
    }

    private int updateHoveredSlot(int panelX, int panelY) {
        int mouseX = BetterShulkerClient.getLastMouseX();
        int mouseY = BetterShulkerClient.getLastMouseY();
        int hovered = -1;
        BetterShulkerClient.setHoveredTooltipSlotIndex(-1);

        for (int displayPos = 0; displayPos < this.displaySlots.size(); displayPos++) {
            int actualSlot = this.displaySlots.get(displayPos);
            int slotX = getSlotX(panelX, displayPos);
            int slotY = getSlotY(panelY, displayPos);
            int hitSize = getRenderedSlotSize();
            if (mouseX >= slotX && mouseX < slotX + hitSize && mouseY >= slotY && mouseY < slotY + hitSize) {
                hovered = actualSlot;
                BetterShulkerClient.setHoveredTooltipSlotIndex(actualSlot);
                break;
            }
        }
        return hovered;
    }

    private void drawItemsAndSlotOverlays(Font font, GuiGraphicsExtractor context, int panelX, int panelY, int hoveredSlot, long now) {
        int selectedDisplay = getDisplayIndexForSlot(BetterShulkerClient.getSelectedSlotIndex());
        int hoveredDisplay = hoveredSlot >= 0 ? getDisplayIndexForSlot(hoveredSlot) : -1;
        int animatedDisplay = selectedDisplay >= 0 ? selectedDisplay : hoveredDisplay;
        long lastScaleTime = BetterShulkerClient.getLastSlotScaleUpdateTime();
        float dt = lastScaleTime == 0L ? 0.05f : Math.min(0.05f, (now - lastScaleTime) / 1000f);
        BetterShulkerClient.setLastSlotScaleUpdateTime(now);
        float[] slotScales = BetterShulkerClient.getSlotScales();

        for (int displayPos = 0; displayPos < this.displaySlots.size(); displayPos++) {
            int i = this.displaySlots.get(displayPos);
            int slotX = getSlotX(panelX, displayPos);
            int slotY = getSlotY(panelY, displayPos);
            ItemStack stack = i < this.contents.size() ? this.contents.get(i) : ItemStack.EMPTY;

            if (this.isContainerEmpty && stack.isEmpty()) {
                drawEmptySlotHint(context, slotX, slotY);
            }

            if (!stack.isEmpty()) {
                boolean rare = stack.isEnchanted()
                        || stack.getItem().getDescriptionId().contains("netherite")
                        || stack.getItem().getDescriptionId().contains("diamond")
                        || stack.getItem().getDescriptionId().contains("golden_apple");
                boolean wobble = BetterShulkerConfig.rareItemWobbleEnabled && rare;
                boolean hoverZoom = BetterShulkerConfig.hoverAnimationsEnabled && displayPos == animatedDisplay;
                float targetScale = hoverZoom ? 1.25f : 1.0f;
                if (i >= 0 && i < slotScales.length) {
                    slotScales[i] += (targetScale - slotScales[i]) * Math.min(1.0f, 18f * dt);
                }
                float scale = i >= 0 && i < slotScales.length ? slotScales[i] : targetScale;

                if (wobble || scale > 1.01f) {
                    context.pose().pushMatrix();
                    if (wobble) {
                        context.pose().translate(0.0f, (float) Math.sin(now / 260.0 + i) * 0.8f);
                    }
                    if (scale > 1.01f) {
                        float center = getRenderedSlotSize() / 2.0f;
                        context.pose().translate(slotX + center, slotY + center);
                        context.pose().scale(scale, scale);
                        context.pose().translate(-(slotX + center), -(slotY + center));
                    }
                }

                int itemX = slotX + 1;
                int itemY = slotY + 1;
                context.item(stack, itemX, itemY);
                if (this.compactMode) {
                    int totalCount = getDisplayItemCount(displayPos);
                    if (totalCount > 1) {
                        // Vanilla decorations would print this stack's own count rather than the
                        // merged total, so the stack is decorated as if it were single. That keeps
                        // the durability bar and cooldown overlay, which the merged total used to
                        // replace outright, and the real total is drawn over them.
                        context.itemDecorations(font, stack.copyWithCount(1), itemX, itemY);
                        drawCompactItemCount(font, context, itemX, itemY, totalCount);
                    } else {
                        context.itemDecorations(font, stack, itemX, itemY);
                    }
                } else {
                    context.itemDecorations(font, stack, itemX, itemY);
                }

                if (wobble || scale > 1.01f) {
                    context.pose().popMatrix();
                }
            }

        }
    }

    private int getDisplayItemCount(int displayPos) {
        if (displayPos < 0 || displayPos >= this.displayCounts.size()) return 0;
        return this.displayCounts.get(displayPos);
    }

    private void drawCompactItemCount(Font font, GuiGraphicsExtractor context, int itemX, int itemY, int count) {
        String text = formatMergedCount(count);
        int x = itemX + 16 - font.width(text);
        int y = itemY + 9;
        context.text(font, Component.literal(text), x + 1, y + 1, 0xFF000000);
        context.text(font, Component.literal(text), x, y, 0xFFFFFFFF);
    }

    /**
     * Merged compact totals in at most four characters.
     *
     * <p>A full box of stackables merges to 1728, which the old "999+" cut-off reported as the
     * same number as 1000. One decimal of thousands keeps the magnitude readable instead.</p>
     */
    private static String formatMergedCount(int count) {
        if (count < 1000) return String.valueOf(count);
        if (count < 10000) return (count / 1000) + "." + ((count % 1000) / 100) + "k";
        return (count / 1000) + "k";
    }

    private void drawSelectedSlot(GuiGraphicsExtractor context, int panelX, int panelY, long now) {
        if (!BetterShulkerConfig.secondaryTooltipEnabled) return;
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (selected < 0 || selected >= SLOT_COUNT) return;

        int displayIndex = getDisplayIndexForSlot(selected);
        if (displayIndex < 0) return;
        int cols = this.compactMode ? this.displayCols
                : (this.resourcePackOverridesPanel ? this.panelTexture.layout().columns() : GRID_COLS);
        int col = displayIndex % cols;
        int row = displayIndex / cols;
        float renderCol = col;
        float renderRow = row;

        if (BetterShulkerConfig.selectionGlideEnabled) {
            long lastTime = BetterShulkerClient.getLastHighlightRenderTime();
            float currentCol = BetterShulkerClient.getCurrentSelectedCol();
            float currentRow = BetterShulkerClient.getCurrentSelectedRow();
            if (lastTime == 0L || currentCol == -1f || currentRow == -1f) {
                currentCol = col;
                currentRow = row;
            } else {
                float dt = Math.min(0.05f, (now - lastTime) / 1000f);
                currentCol += (col - currentCol) * Math.min(1.0f, 16f * dt);
                currentRow += (row - currentRow) * Math.min(1.0f, 16f * dt);
            }
            BetterShulkerClient.setCurrentSelectedCol(currentCol);
            BetterShulkerClient.setCurrentSelectedRow(currentRow);
            BetterShulkerClient.setLastHighlightRenderTime(now);
            renderCol = currentCol;
            renderRow = currentRow;
        }

        // Same origin the items, lattice, hover and multi-select all use. Compact used to start
        // a pixel to the left here, which left the fill straddling the edge of its own cell.
        int startX = getSlotStartX();
        int cellSize = getRenderedSlotSize();
        int slotX = panelX + startX + Math.round(renderCol * cellSize);
        int slotY = panelY + getSlotStartY() + Math.round(renderRow * cellSize);
        // Borderless: the cell itself lights up instead of being framed. Kept light because the
        // fill lands on top of the item, and a saturated colour replaces its hue rather than
        // just dimming it.
        int size = getRenderedSlotSize();
        context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1,
                withAlpha(this.selectionColor, 64 + (int) (18 * Math.sin(now / 300.0))));
    }

    /** Borderless hover wash, drawn beneath the item like the selection highlight. */
    private void drawHoverHighlight(GuiGraphicsExtractor context, int panelX, int panelY,
                                    int hoveredSlot, long now) {
        if (hoveredSlot < 0 || hoveredSlot == BetterShulkerClient.getSelectedSlotIndex()) return;
        int displayIndex = getDisplayIndexForSlot(hoveredSlot);
        if (displayIndex < 0) return;

        int slotX = getSlotX(panelX, displayIndex);
        int slotY = getSlotY(panelY, displayIndex);
        int size = getRenderedSlotSize();
        int alpha = BetterShulkerConfig.hoverAnimationsEnabled
                ? 40 + (int) (12 * Math.sin(now / 260.0))
                : 40;
        context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1,
                withAlpha(this.selectionColor, alpha));
    }

    private void drawMultiSelectedSlots(GuiGraphicsExtractor context, int panelX, int panelY) {
        for (int i : BetterShulkerClient.getSelectedSlotsSet()) {
            if (i < 0 || i >= SLOT_COUNT) continue;
            int displayIndex = getDisplayIndexForSlot(i);
            if (displayIndex < 0) continue;
            int slotX = getSlotX(panelX, displayIndex);
            int slotY = getSlotY(panelY, displayIndex);
            int size = getRenderedSlotSize();
            // Kept below the selected slot's alpha so the two stay distinguishable.
            context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1,
                    withAlpha(this.multiSelectColor, 56));
        }
    }

    private void drawHoveredAndSelectedTooltips(Font font, GuiGraphicsExtractor context, int hoveredSlot) {
        if (this.compactMode) return;
        int mouseX = BetterShulkerClient.getLastMouseX();
        int mouseY = BetterShulkerClient.getLastMouseY();
        String hoveredName = null;

        if (hoveredSlot >= 0 && hoveredSlot < this.contents.size()) {
            ItemStack hoveredStack = this.contents.get(hoveredSlot);
            if (!hoveredStack.isEmpty()) {
                hoveredName = hoveredStack.getHoverName().getString();
                context.setTooltipForNextFrame(font, List.of(hoveredStack.getHoverName()), Optional.empty(), mouseX, mouseY);
            }
        }

        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (!BetterShulkerConfig.selectedItemNameEnabled
                && BetterShulkerConfig.secondaryTooltipEnabled
                && selected != hoveredSlot
                && selected >= 0
                && selected < this.contents.size()) {
            ItemStack selectedStack = this.contents.get(selected);
            if (!selectedStack.isEmpty()) {
                String selectedName = selectedStack.getHoverName().getString();
                if (hoveredName == null || !hoveredName.equals(selectedName)) {
                    int tooltipWidth = font.width(selectedStack.getHoverName()) + 12;
                    context.setTooltipForNextFrame(font,
                            List.of(selectedStack.getHoverName()),
                            Optional.empty(),
                            mouseX - tooltipWidth - 12,
                            mouseY - 10);
                }
            }
        }
    }

    private void drawSelectedNameBadge(Font font, GuiGraphicsExtractor context, int panelX, int panelY) {
        if (!BetterShulkerConfig.selectedItemNameEnabled) return;
        // Modern shows the selected name in its own tab beside the container tab, so the
        // vanilla-framed floating label would only duplicate it in a clashing style.
        if (isModernStyle()) return;
        if (!this.compactMode && !hasSelectedNameBadge()) return;

        ItemStack selectedStack = this.compactMode
                ? getCompactNameStack()
                : getSelectedNameStack();
        if (selectedStack.isEmpty()) return;

        int nameColor = this.resourcePackOverridesPanel ? 0xFFFFFF : getReadableThemeNameColor();
        if (this.compactMode) {
            drawCompactSelectedNameTooltip(font, context, panelX, panelY, selectedStack, nameColor);
        } else {
            // Keep the selected name above the container title, matching vanilla tooltip geometry.
            drawVanillaSelectedNameTooltip(font, context, panelX, panelY, selectedStack, nameColor);
        }
    }

    private ItemStack getSelectedNameStack() {
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (selected < 0 || selected >= this.contents.size()) return ItemStack.EMPTY;
        return this.contents.get(selected);
    }

    private ItemStack getCompactNameStack() {
        ItemStack selectedStack = getSelectedNameStack();
        if (!selectedStack.isEmpty()) return selectedStack;

        int hovered = BetterShulkerClient.getHoveredTooltipSlotIndex();
        if (hovered >= 0 && hovered < this.contents.size()) {
            ItemStack hoveredStack = this.contents.get(hovered);
            if (!hoveredStack.isEmpty()) return hoveredStack;
        }

        for (int displaySlot : this.displaySlots) {
            if (displaySlot >= 0 && displaySlot < this.contents.size()) {
                ItemStack stack = this.contents.get(displaySlot);
                if (!stack.isEmpty()) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private void drawCompactSelectedNameTooltip(Font font, GuiGraphicsExtractor context, int panelX, int panelY, ItemStack selectedStack, int nameColor) {
        if (isCustomTheme() && !this.resourcePackOverridesPanel) {
            drawCustomSelectedNameBadge(font, context, panelX, panelY, selectedStack, nameColor);
            return;
        }

        Component name = selectedStack.getHoverName().copy().withStyle(style -> style.withColor(nameColor & 0xFFFFFF));
        ClientTooltipComponent selectedNameTooltip = ClientTooltipComponent.create(name.getVisualOrderText());
        int textWidth = font.width(name.getVisualOrderText());
        int tooltipAnchorX = panelX + getPanelWidth() / 2 - 12 - textWidth / 2;
        int tooltipAnchorY = panelY - 1;
        context.tooltip(font,
                List.of(selectedNameTooltip),
                tooltipAnchorX,
                tooltipAnchorY,
                DefaultTooltipPositioner.INSTANCE,
                selectedStack.get(DataComponents.TOOLTIP_STYLE));
    }

    private void drawVanillaSelectedNameTooltip(Font font, GuiGraphicsExtractor context, int panelX, int panelY, ItemStack selectedStack, int nameColor) {
        if (isCustomTheme() && !this.resourcePackOverridesPanel) {
            drawCustomSelectedNameBadge(font, context, panelX, panelY, selectedStack, nameColor);
            return;
        }

        int panelWidth = getPanelWidth();
        String displayName = fitText(font, selectedStack.getHoverName().getString(), panelWidth - 22);
        Component name = Component.literal(displayName).withStyle(style -> style.withColor(nameColor & 0xFFFFFF));
        ClientTooltipComponent selectedNameTooltip = ClientTooltipComponent.create(name.getVisualOrderText());
        int textWidth = font.width(name.getVisualOrderText());

        int tooltipAnchorX = panelX + panelWidth / 2 - 12 - textWidth / 2;
        int tooltipAnchorY = panelY - 15;
        int bridgeWidth = Math.min(panelWidth - 24, textWidth + 12);
        int bridgeX = panelX + (panelWidth - bridgeWidth) / 2;
        int bridgeColor = this.resourcePackOverridesPanel ? 0x55000000 : withAlpha(this.nameBorderColor, 110);
        int bridgeFill = this.resourcePackOverridesPanel ? 0x33FFFFFF : withAlpha(this.badgeBgColor, 145);
        context.fill(bridgeX, panelY - 2, bridgeX + bridgeWidth, panelY + 1, bridgeColor);
        context.fill(bridgeX + 1, panelY - 1, bridgeX + bridgeWidth - 1, panelY + 1, bridgeFill);

        context.tooltip(font,
                List.of(selectedNameTooltip),
                tooltipAnchorX,
                tooltipAnchorY,
                DefaultTooltipPositioner.INSTANCE,
                selectedStack.get(DataComponents.TOOLTIP_STYLE));
    }

    private void drawCustomSelectedNameBadge(Font font, GuiGraphicsExtractor context,
                                              int panelX, int panelY, ItemStack selectedStack, int nameColor) {
        int panelWidth = getPanelWidth();
        String displayName = fitText(font, selectedStack.getHoverName().getString(), Math.max(1, panelWidth - 22));
        int textWidth = font.width(displayName);
        int badgeWidth = Math.min(panelWidth - 8, textWidth + 12);
        int badgeX = panelX + (panelWidth - badgeWidth) / 2;
        int badgeY = panelY - CONTAINER_NAME_LINE_HEIGHT - CONTAINER_NAME_LINE_GAP
                - NAME_BADGE_HEIGHT - NAME_BADGE_GAP;

        int outer = withAlpha(this.nameBorderColor, 255);
        int inner = normalizeOverlayAlpha(this.badgeBgColor, 230);
        int highlight = withAlpha(blendColor(this.nameBorderColor, 0xFFFFFFFF, 0.45f), 120);
        int shadow = withAlpha(blendColor(this.nameBorderColor, 0xFF000000, 0.55f), 160);
        int textColor = 0xFF000000 | (nameColor & 0x00FFFFFF);

        context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + NAME_BADGE_HEIGHT, outer);
        context.fill(badgeX + 1, badgeY + 1, badgeX + badgeWidth - 1, badgeY + NAME_BADGE_HEIGHT - 1, inner);
        context.fill(badgeX + 1, badgeY + 1, badgeX + badgeWidth - 1, badgeY + 2, highlight);
        context.fill(badgeX + 1, badgeY + 2, badgeX + 2, badgeY + NAME_BADGE_HEIGHT - 1, highlight);
        context.fill(badgeX + 1, badgeY + NAME_BADGE_HEIGHT - 2,
                badgeX + badgeWidth - 1, badgeY + NAME_BADGE_HEIGHT - 1, shadow);
        context.fill(badgeX + badgeWidth - 2, badgeY + 2,
                badgeX + badgeWidth - 1, badgeY + NAME_BADGE_HEIGHT - 1, shadow);
        context.text(font, Component.literal(displayName), badgeX + 6, badgeY + 3, textColor);
    }

    private int getReadableThemeNameColor() {
        return BetterShulkerConfig.getCustomNameTextColor() & 0x00FFFFFF;
    }

    private String fitText(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int allowed = Math.max(0, maxWidth - font.width(ellipsis));
        String result = text;
        while (!result.isEmpty() && font.width(result) > allowed) {
            result = result.substring(0, result.length() - 1);
        }
        return result + ellipsis;
    }

    private boolean hasSelectedNameBadge() {
        return BetterShulkerConfig.selectedItemNameEnabled
                && this.selectedItemName != null
                && !this.selectedItemName.isEmpty();
    }

    private void drawFillStrip(GuiGraphicsExtractor context, int x, int y) {
        int occupied = 0;
        for (ItemStack stack : this.contents) {
            if (!stack.isEmpty()) occupied++;
        }
        int barW = getPanelWidth() - 16;
        int barX = x + 8;
        if (barW <= 0) return;
        context.fill(barX, y, barX + barW, y + 1, 0x66000000);
        if (occupied > 0) {
            // Against the real slot count rather than a hard-coded 27, so a resource-pack layout
            // with its own grid size still reads as full when it is full.
            int slotCount = Math.max(1, this.contents.size());
            int fill = Math.max(1, Math.round((occupied / (float) slotCount) * barW));
            context.fill(barX, y, barX + fill, y + 1, withAlpha(this.selectionColor, 210));
        }
    }

    private void drawEmptySlotHint(GuiGraphicsExtractor context, int slotX, int slotY) {
        int hint = withAlpha(this.borderColor, 18);
        int size = getRenderedSlotSize();
        context.fill(slotX + 3, slotY + 3, slotX + size - 3, slotY + 4, hint);
        context.fill(slotX + 3, slotY + size - 4, slotX + size - 3, slotY + size - 3, hint);
        context.fill(slotX + 3, slotY + 4, slotX + 4, slotY + size - 4, hint);
        context.fill(slotX + size - 4, slotY + 4, slotX + size - 3, slotY + size - 4, hint);
    }

    private void drawRectFrame(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private int getRenderedSlotSize() {
        return this.resourcePackOverridesPanel ? this.panelTexture.layout().slotSize() : SLOT_SIZE;
    }

    private int getSlotX(int panelX, int slot) {
        int cols = this.compactMode ? this.displayCols
                : (this.resourcePackOverridesPanel ? this.panelTexture.layout().columns() : GRID_COLS);
        return panelX + getSlotStartX() + (slot % cols) * getRenderedSlotSize();
    }

    private int getSlotY(int panelY, int slot) {
        int cols = this.compactMode ? this.displayCols
                : (this.resourcePackOverridesPanel ? this.panelTexture.layout().columns() : GRID_COLS);
        return panelY + getSlotStartY() + (slot / cols) * getRenderedSlotSize();
    }

    private int getDisplayIndexForSlot(int actualSlot) {
        for (int i = 0; i < this.displaySlots.size(); i++) {
            if (this.displaySlots.get(i) == actualSlot) return i;
        }
        if (this.compactMode && actualSlot >= 0 && actualSlot < this.contents.size()) {
            ItemStack selectedStack = this.contents.get(actualSlot);
            if (!selectedStack.isEmpty()) {
                for (int i = 0; i < this.displaySlots.size(); i++) {
                    int displaySlot = this.displaySlots.get(i);
                    if (displaySlot >= 0 && displaySlot < this.contents.size()
                            && ItemStack.isSameItemSameComponents(this.contents.get(displaySlot), selectedStack)) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private int getSlotStartX() {
        return this.resourcePackOverridesPanel ? this.panelTexture.layout().outputSlotX() : SLOT_START_X;
    }

    private int getSlotStartY() {
        return this.resourcePackOverridesPanel ? this.panelTexture.layout().outputSlotY() : SLOT_START_Y;
    }


    private boolean isGlassTheme() {
        return BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.GLASS;
    }

    private boolean isCustomTheme() {
        return BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.CUSTOM;
    }

    private boolean isModernStyle() {
        return BetterShulkerConfig.getTooltipStyle() == BetterShulkerConfig.TooltipStyle.MODERN;
    }

    /**
     * True when this tooltip paints a complete panel of its own and needs no vanilla frame.
     *
     * <p>Only the Modern card does that. It holds under a resource pack too, because the card is
     * still drawn there — recoloured from the pack rather than replaced by it.</p>
     */
    public boolean drawsOwnPanel() {
        return isModernStyle();
    }

    private ThemePalette buildThemePalette() {
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
        if (isModernStyle()) {
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


    private record ThemePalette(
            int borderColor,
            int nameBorderColor,
            int tintColor,
            int badgeBgColor,
            int selectionColor,
            int multiSelectColor,
            int panelShadowColor
    ) {}
}

package com.bettershulker.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import static com.bettershulker.client.render.ThemeColorUtil.blendColor;

/**
 * Draws the Modern style: a flat rounded card with a lattice slot grid, built from scratch
 * rather than blitted from a container texture.
 *
 * <p>Its colours come from the container's own dye, or from an active resource pack's panel,
 * so every box keeps its identity without the theme having a say. The card carries the
 * container's name and the selected item's name in two tabs sharing the row above it.</p>
 */
final class ModernCardPainter {

    private static final int NAME_TAB_HEIGHT = 16;
    private static final int NAME_TAB_GAP = 2;
    /** Below this the selected-name tab would be all padding and an ellipsis, so it is dropped. */
    private static final int MIN_SELECTED_TAB_WIDTH = 28;
    /** Gap the full 176px card leaves right of its grid: 176 - 8 - 9*18. */
    private static final int MODERN_GRID_RIGHT_MARGIN = 6;

    private final TooltipPalette palette;
    private final boolean compact;
    private final int slotStartX;
    private final int slotStartY;
    private final int cellSize;

    ModernCardPainter(TooltipPalette palette, boolean compact,
                      int slotStartX, int slotStartY, int cellSize) {
        this.palette = palette;
        this.compact = compact;
        this.slotStartX = slotStartX;
        this.slotStartY = slotStartY;
        this.cellSize = cellSize;
    }

    void drawCard(GuiGraphicsExtractor context, int panelX, int panelY,
                  int cols, int rows, int cardWidth, int h) {
        int w = cardWidth;
        if (w <= 0 || h <= 0) return;

        int fill = this.palette.getModernPanelFill();
        fillRounded(context, panelX, panelY, w, h, this.palette.getModernPanelBorder());
        fillRounded(context, panelX + 2, panelY + 2, w - 4, h - 4, fill);

        // A one-pixel lift on top and a matching shade on the bottom keep the flat
        // card from reading as a plain rectangle.
        context.fill(panelX + 3, panelY + 2, panelX + w - 3, panelY + 3, blendColor(fill, 0xFFFFFFFF, 0.16f));
        context.fill(panelX + 3, panelY + h - 3, panelX + w - 3, panelY + h - 2, blendColor(fill, 0xFF000000, 0.18f));

        drawGrid(context, panelX, panelY, gridColumnsFor(cols, w), rows);
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
        if (!this.compact) return cols;
        int usable = cardWidth - this.slotStartX - MODERN_GRID_RIGHT_MARGIN;
        return Math.max(cols, usable / this.cellSize);
    }

    /** Slots are a shared lattice of single-pixel rules rather than 27 embossed wells. */
    private void drawGrid(GuiGraphicsExtractor context, int panelX, int panelY, int cols, int rows) {
        // Cell size follows the pack's layout when one is active, so the lattice lands exactly
        // where the items are drawn.
        int cell = this.cellSize;
        int gridX = panelX + this.slotStartX;
        int gridY = panelY + this.slotStartY;
        int gridW = cols * cell;
        int gridH = rows * cell;

        context.fill(gridX, gridY, gridX + gridW, gridY + gridH, this.palette.getModernCellFill());

        int line = this.palette.getModernGridLine();
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
    void drawNameTabs(Font font, GuiGraphicsExtractor context, int tooltipX, int panelY,
                      int tooltipWidth, int panelWidth, String containerName, String selectedName) {
        if (panelWidth <= 0) return;

        int tabY = panelY - NAME_TAB_HEIGHT;
        // Runs two pixels past the card's top border so tab and card read as one shape.
        int tabHeight = panelY + 2 - tabY;

        boolean hasContainerName = containerName != null && !containerName.isEmpty();

        int containerTabWidth = 0;
        int selectedTabWidth = 0;
        if (hasContainerName && selectedName != null) {
            int available = tooltipWidth - NAME_TAB_GAP;
            int containerNeed = font.width(containerName) + 12;
            int selectedNeed = font.width(selectedName) + 12;

            if (containerNeed + selectedNeed <= available) {
                containerTabWidth = containerNeed;
                selectedTabWidth = selectedNeed;
            } else if (this.compact) {
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
            containerTabWidth = Math.min(tooltipWidth, font.width(containerName) + 12);
        } else if (selectedName != null) {
            selectedTabWidth = Math.min(tooltipWidth, font.width(selectedName) + 12);
        }

        if (containerTabWidth > 0) {
            drawTab(font, context, tooltipX, tabY, containerTabWidth, tabHeight,
                    TooltipText.fit(font, containerName, Math.max(1, containerTabWidth - 12)));
        }
        if (selectedTabWidth > 0) {
            drawTab(font, context, tooltipX + tooltipWidth - selectedTabWidth, tabY,
                    selectedTabWidth, tabHeight,
                    TooltipText.fit(font, selectedName, Math.max(1, selectedTabWidth - 12)));
        }
    }

    private void drawTab(Font font, GuiGraphicsExtractor context, int x, int y, int w, int h, String label) {
        fillTopRounded(context, x, y, w, h, this.palette.getModernPanelBorder());
        fillTopRounded(context, x + 2, y + 2, w - 4, h, this.palette.getModernPanelFill());
        context.text(font, Component.literal(label), x + 6, y + 4, 0xFFFFFFFF);
    }

    static void fillRounded(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
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

    static void fillTopRounded(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w <= 4 || h <= 2) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        context.fill(x + 2, y, x + w - 2, y + 1, color);
        context.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        context.fill(x, y + 2, x + w, y + h, color);
    }
}

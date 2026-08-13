package com.bettershulker.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Draws a container panel out of an active resource pack's own texture.
 *
 * <p>The pack's panel is recomposed rather than cropped: its left edge, the slot grid, its right
 * cap and its bottom cap are blitted as separate slices, so a preview narrower or shorter than
 * the pack's full screen keeps every edge the pack drew. The profile decides where those slices
 * live; anything below the storage grid, such as a player inventory, is left behind.</p>
 */
final class ResourcePackPanelPainter {

    /** The pack owns its colour treatment; never tint what it supplies. */
    private static final int RENDER_COLOR = 0xFFFFFFFF;

    private final ResourcePackLayout layout;
    private final Identifier texture;
    private final int columns;
    private final int rows;
    private final int panelWidth;
    private final int panelHeight;

    ResourcePackPanelPainter(ResourcePackLayout layout, Identifier texture,
                             int columns, int rows, int panelWidth, int panelHeight) {
        this.layout = layout;
        this.texture = texture;
        this.columns = columns;
        this.rows = rows;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    public void drawFull(GuiGraphicsExtractor context, int panelX, int panelY) {
        // Resource-pack mode uses the pack's actual panel texture without a fake tint.
        Identifier texture = this.texture;

        int renderColor = RENDER_COLOR;

        ResourcePackLayout layout = this.layout;
        // The profile keeps the pack's own top edge and storage grid while discarding any
        // full-screen/player-inventory section below it.
        drawHorizontalSlices(context, texture, panelX, panelY,
                layout.sourcePanelY(), layout.outputSlotY(), this.columns, renderColor);
        drawStorageRows(context, texture, panelX,
                panelY + layout.outputSlotY(), this.rows, renderColor);
        drawRepeatedRow(context, texture, panelX,
                panelY + layout.outputSlotY() + this.rows * layout.slotSize(),
                layout.bottomCapHeight(), renderColor);
    }

    public void drawCompact(GuiGraphicsExtractor context, int panelX, int panelY) {
        Identifier texture = this.texture;
        int renderColor = RENDER_COLOR;

        ResourcePackLayout layout = this.layout;
        // Recompose the resource-pack panel instead of cropping the top-left. This preserves the
        // profile's left edge, slot grid, right cap, and bottom cap at compact widths.
        int leftW = layout.outputSlotX();
        int slotsW = this.columns * layout.slotSize();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int topH = layout.outputSlotY() + this.rows * layout.slotSize();
        int bottomH = Math.max(0, this.panelHeight - topH);

        drawHorizontalSlices(context, texture, panelX, panelY,
                layout.sourcePanelY(), layout.outputSlotY(), this.columns, renderColor);
        drawStorageRows(context, texture, panelX,
                panelY + layout.outputSlotY(), this.rows,
                leftW, slotsW, rightSourceX, rightW, renderColor);

        if (bottomH > 0) {
            int bottomY = panelY + topH;
            drawRepeatedRow(context, texture, panelX, bottomY, bottomH,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawStorageRows(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int rows, int renderColor) {
        ResourcePackLayout layout = this.layout;
        int leftW = layout.outputSlotX();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int slotsW = this.columns * layout.slotSize();
        drawStorageRows(context, texture, panelX, y, rows,
                leftW, slotsW, rightSourceX, rightW, renderColor);
    }

    private void drawStorageRows(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int rows,
                                             int leftW, int slotsW, int rightSourceX, int rightW,
                                             int renderColor) {
        ResourcePackLayout layout = this.layout;
        int height = rows * layout.slotSize();
        int directHeight = Math.min(height, layout.rows() * layout.slotSize());
        if (directHeight > 0) {
            blitHorizontalSlices(context, texture, panelX, y,
                    layout.sourceSlotY(), directHeight,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
        for (int row = directHeight; row < height; row++) {
            blitHorizontalSlices(context, texture, panelX, y + row,
                    layout.bottomCapSourceY(), 1,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawRepeatedRow(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int height, int renderColor) {
        ResourcePackLayout layout = this.layout;
        int leftW = layout.outputSlotX();
        int rightSourceX = layout.sourceRightX();
        int rightW = layout.sourceRightWidth();
        int slotsW = this.columns * layout.slotSize();
        drawRepeatedRow(context, texture, panelX, y, height,
                leftW, slotsW, rightSourceX, rightW, renderColor);
    }

    private void drawRepeatedRow(GuiGraphicsExtractor context, Identifier texture,
                                             int panelX, int y, int height,
                                             int leftW, int slotsW, int rightSourceX, int rightW,
                                             int renderColor) {
        ResourcePackLayout layout = this.layout;
        for (int row = 0; row < height; row++) {
            blitHorizontalSlices(context, texture, panelX, y + row,
                    layout.bottomCapSourceY(), 1,
                    leftW, slotsW, rightSourceX, rightW, renderColor);
        }
    }

    private void drawHorizontalSlices(GuiGraphicsExtractor context, Identifier texture,
                                                   int panelX, int y, int sourceY, int height,
                                                   int displayColumns, int renderColor) {
        ResourcePackLayout layout = this.layout;
        int leftW = layout.outputSlotX();
        int slotsW = displayColumns * layout.slotSize();
        int rightW = Math.max(0, this.panelWidth - leftW - slotsW);
        blitHorizontalSlices(context, texture, panelX, y, sourceY, height,
                leftW, slotsW, layout.sourceRightX(), Math.min(rightW, layout.sourceRightWidth()), renderColor);
    }

    private void blitHorizontalSlices(GuiGraphicsExtractor context, Identifier texture,
                                                   int panelX, int y, int sourceY, int height,
                                                   int leftW, int slotsW, int rightSourceX, int rightW,
                                                   int renderColor) {
        ResourcePackLayout layout = this.layout;
        blitSlice(context, texture, panelX, y,
                layout.sourcePanelX(), sourceY, leftW, height, renderColor);
        blitSlice(context, texture, panelX + leftW, y,
                layout.sourceSlotX(), sourceY, slotsW, height, renderColor);
        blitSlice(context, texture, panelX + leftW + slotsW, y,
                rightSourceX, sourceY, rightW, height, renderColor);
    }

    private void blitSlice(GuiGraphicsExtractor context, Identifier texture, int x, int y,
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
                this.layout.textureWidth(),
                this.layout.textureHeight(),
                renderColor);
    }
}

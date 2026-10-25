package com.bettershulker.client.render;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a pack's panel keeps the rows that close off the bottom of its storage section, and how
 * to draw them under a preview.
 *
 * <p>A container texture has no "bottom of the storage grid": below it comes the player's
 * inventory. Repeating the single body row under the grid was enough for vanilla's flat grey, but
 * it never drew a bottom border, and packs come in two shapes it handled badly:</p>
 * <ul>
 *     <li><b>One tone</b> - vanilla, Default Dark Mode, Faithful. Storage and inventory are one
 *     panel, so the border is the panel's own bottom rows, under the hotbar.</li>
 *     <li><b>Two tone</b> - Recolourful Containers, Shulker UI, Colourful Containers. The
 *     coloured storage box closes itself a few rows under the grid, and below that is a grey
 *     inventory panel. Its bottom rows are the wrong colour, so the storage box's own edge is
 *     used instead. Colourful Containers also lays the inventory's label tab over the left of
 *     those rows; repeating them drew that tab as a white bar under every preview.</li>
 * </ul>
 *
 * <p>Columns covered by such a tab are rebuilt rather than copied: the left border comes from the
 * same columns just above the grid, and the rest from the run of clean columns nearest the right
 * edge, which no label reaches. A texture that fits neither shape keeps the old repeated row.
 * All coordinates are in the layout's logical units.</p>
 *
 * @param bodyRow      a plain row under the grid, repeated to fill the space above the border
 * @param borderTop    first row of the bottom border
 * @param borderHeight rows of bottom border; 0 draws none
 * @param middleX      first column of the clean run tiled across the slot columns
 * @param middleWidth  width of that run
 * @param leftRow      row the left border's body comes from
 * @param leftCovered  whether the left columns are covered below the grid, so the border there
 *                     is tiled from the clean run instead
 * @param coverTop     first grid row the covering reaches; rows from here to {@code bodyRow} are
 *                     redrawn from one slot row higher. {@code bodyRow} when nothing is covered
 */
public record PanelBottomEdge(int bodyRow, int borderTop, int borderHeight,
                              int middleX, int middleWidth, int leftRow, boolean leftCovered,
                              int coverTop) {

    /** Rows allowed between the grid and a two-tone storage box's edge. */
    private static final int MAX_BODY_ROWS = 8;
    /** Rows a bottom border may take; vanilla's is four, counting its corner bevel. */
    private static final int MAX_BORDER_ROWS = 6;
    /** Per-channel difference still read as the same colour, for HD packs' slight noise. */
    private static final int CHANNEL_TOLERANCE = 8;
    private static final int MIN_OPAQUE_ALPHA = 200;

    /** One textured rectangle: destination x/y, source u/v, and its size, all logical. */
    public record Blit(int x, int y, int u, int v, int width, int height) {
    }

    /** The old behaviour: repeat the row under the grid, with no border. */
    public static PanelBottomEdge repeatBodyRow(ResourcePackLayout layout) {
        int row = layout.bottomCapSourceY();
        return new PanelBottomEdge(row, row, 0, layout.sourceSlotX(),
                layout.columns() * layout.slotSize(), row, false, row);
    }

    // =========================================================================
    //  Reading the texture
    // =========================================================================

    public static PanelBottomEdge analyse(NativeImage image, ResourcePackLayout layout) {
        Pixels px = new Pixels(image, layout);
        PanelBottomEdge fallback = repeatBodyRow(layout);

        int bodyRow = layout.bottomCapSourceY();
        int left = layout.sourcePanelX();
        int right = layout.sourcePanelX() + layout.sourcePanelWidth();
        int slotLeft = layout.sourceSlotX();
        int slotRight = layout.sourceRightX();
        int panelBottom = layout.sourcePanelY() + layout.sourcePanelHeight();
        // The last slot column's right gap: inside the panel, and right of any inventory label.
        int refX = slotRight - 1;
        int body = px.at(refX, bodyRow);
        if (!opaque(body) || bodyRow + 1 >= panelBottom) return fallback;

        // One tone: the panel's own bottom rows sit under a row that reads as the body row.
        for (int y = panelBottom - 1; y >= panelBottom - 1 - MAX_BORDER_ROWS && y > bodyRow; y--) {
            if (rowsMatch(px, y, bodyRow, left, right)) {
                int top = y + 1;
                if (top >= panelBottom) break;
                return new PanelBottomEdge(bodyRow, top, panelBottom - top,
                        slotLeft, slotRight - slotLeft, bodyRow, false, bodyRow);
            }
        }

        // Two tone: the storage body runs on a few rows, then a darker edge closes it. The search
        // may run past the profile's panel: the built-in profile for Colourful Containers stops
        // at row 75, which is the very row holding its storage box's outline.
        int scanBottom = layout.textureHeight();
        int edgeTop = bodyRow + 1;
        while (edgeTop <= bodyRow + MAX_BODY_ROWS && edgeTop < scanBottom && same(px.at(refX, edgeTop), body)) {
            edgeTop++;
        }
        if (edgeTop > bodyRow + MAX_BODY_ROWS || edgeTop >= scanBottom) return fallback;
        int edgeBottom = edgeTop;
        while (edgeBottom < edgeTop + MAX_BORDER_ROWS && edgeBottom < scanBottom
                && opaque(px.at(refX, edgeBottom)) && luma(px.at(refX, edgeBottom)) < luma(body)) {
            edgeBottom++;
        }
        if (edgeBottom == edgeTop) return fallback;

        // A rounded corner can start above the edge in the middle; Shulker UI's does.
        int borderTop = edgeTop;
        for (int y = bodyRow + 1; y < edgeTop; y++) {
            if (!rowsMatch(px, y, bodyRow, slotRight, right)) {
                borderTop = y;
                break;
            }
        }

        // The run of columns that read exactly like the reference column, all the way down.
        int runStart = refX;
        while (runStart - 1 >= slotLeft && columnsMatch(px, runStart - 1, refX, bodyRow, edgeBottom)) {
            runStart--;
        }

        // The left border is clean when its body rows read as it does just above the grid. A
        // layout with the grid at the very top has no such row, so the border stays as drawn
        // rather than being rebuilt from outside the panel.
        int aboveGrid = layout.sourceSlotY() - 2;
        boolean leftCovered = false;
        for (int y = bodyRow; y < borderTop && !leftCovered && aboveGrid >= layout.sourcePanelY(); y++) {
            leftCovered = !rowsMatch(px, y, aboveGrid, left, slotLeft);
        }

        // Whatever covers the rows under the grid can reach up into its last rows too; Colourful
        // Containers' tab starts on the last slot row's bottom edge. The grid repeats every slot,
        // so those rows read as they do one slot row higher wherever nothing covers them.
        int coverTop = bodyRow;
        if (leftCovered || runStart > slotLeft) {
            // The repair redraws the grid's last row as body, which is only right when the
            // profile's body row is that last row - as it is for every vanilla-shaped panel.
            if (bodyRow != layout.sourceSlotY() + layout.rows() * layout.slotSize() - 1) return fallback;
            int slot = layout.slotSize();
            while (coverTop - 1 - slot >= layout.sourceSlotY() && coverTop > bodyRow - slot + 1
                    && !rowsMatch(px, coverTop - 1, coverTop - 1 - slot, left, runStart)) {
                coverTop--;
            }
        }

        return new PanelBottomEdge(bodyRow, borderTop, edgeBottom - borderTop,
                runStart, slotRight - runStart, leftCovered ? aboveGrid : bodyRow, leftCovered, coverTop);
    }

    // =========================================================================
    //  Drawing it
    // =========================================================================

    /**
     * The blits that fill {@code height} rows under the grid, at any preview width.
     *
     * <p>The three slices match the rest of the recomposed panel: the left border at
     * {@code leftW}, the slot columns at {@code slotsW}, and the right cap from
     * {@code rightSourceX}. The border keeps its bottom rows when the space is shorter than it,
     * so the outline is the last thing cut.</p>
     *
     * <p>When something covered the rows under the grid, the grid's last rows - already drawn
     * above {@code y} from the texture - are drawn over again from clean sources first.</p>
     */
    public List<Blit> plan(ResourcePackLayout layout, int x, int y, int height,
                           int leftW, int slotsW, int rightSourceX, int rightW) {
        List<Blit> blits = new ArrayList<>();
        int middleDestX = x + leftW;
        int rightDestX = middleDestX + slotsW;

        if (this.leftCovered || this.middleX > layout.sourceSlotX()) {
            int slot = layout.slotSize();
            int coveredSlotsW = Math.min(this.middleX - layout.sourceSlotX(), slotsW);
            for (int row = this.coverTop; row < this.bodyRow; row++) {
                int destY = y - 1 - (this.bodyRow - row);
                add(blits, x, destY, layout.sourcePanelX(), row - slot, leftW, 1);
                add(blits, middleDestX, destY, layout.sourceSlotX(), row - slot, coveredSlotsW, 1);
            }
            addBodyRow(blits, layout, x, y - 1, leftW, middleDestX, slotsW, rightDestX, rightSourceX, rightW);
        }
        if (height <= 0) return blits;

        int border = Math.min(this.borderHeight, height);
        int bodyRows = height - border;
        for (int row = 0; row < bodyRows; row++) {
            addBodyRow(blits, layout, x, y + row, leftW, middleDestX, slotsW, rightDestX, rightSourceX, rightW);
        }

        if (border > 0) {
            int destY = y + bodyRows;
            int sourceY = this.borderTop + this.borderHeight - border;
            if (this.leftCovered) {
                tile(blits, x, destY, leftW, this.middleX, this.middleWidth, sourceY, border);
            } else {
                add(blits, x, destY, layout.sourcePanelX(), sourceY, leftW, border);
            }
            tile(blits, middleDestX, destY, slotsW, this.middleX, this.middleWidth, sourceY, border);
            add(blits, rightDestX, destY, rightSourceX, sourceY, rightW, border);
        }
        return blits;
    }

    private void addBodyRow(List<Blit> blits, ResourcePackLayout layout, int x, int destY, int leftW,
                            int middleDestX, int slotsW, int rightDestX, int rightSourceX, int rightW) {
        add(blits, x, destY, layout.sourcePanelX(), this.leftRow, leftW, 1);
        tile(blits, middleDestX, destY, slotsW, this.middleX, this.middleWidth, this.bodyRow, 1);
        add(blits, rightDestX, destY, rightSourceX, this.bodyRow, rightW, 1);
    }

    private static void tile(List<Blit> blits, int x, int y, int width, int u, int chunk, int v, int height) {
        if (chunk <= 0) return;
        for (int offset = 0; offset < width; offset += chunk) {
            add(blits, x + offset, y, u, v, Math.min(chunk, width - offset), height);
        }
    }

    private static void add(List<Blit> blits, int x, int y, int u, int v, int width, int height) {
        if (width > 0 && height > 0) blits.add(new Blit(x, y, u, v, width, height));
    }

    // =========================================================================
    //  Pixels
    // =========================================================================

    /** Reads the image at logical coordinates, whatever its real resolution. */
    private record Pixels(NativeImage image, double scaleX, double scaleY) {

        Pixels(NativeImage image, ResourcePackLayout layout) {
            this(image, image.getWidth() / (double) layout.textureWidth(),
                    image.getHeight() / (double) layout.textureHeight());
        }

        /** ARGB at a logical position; 0 when it falls outside the image. */
        int at(int x, int y) {
            int px = (int) (x * this.scaleX);
            int py = (int) (y * this.scaleY);
            if (px < 0 || py < 0 || px >= this.image.getWidth() || py >= this.image.getHeight()) return 0;
            return this.image.getPixel(px, py);
        }
    }

    private static boolean rowsMatch(Pixels px, int a, int b, int fromX, int toX) {
        for (int x = fromX; x < toX; x++) {
            if (!same(px.at(x, a), px.at(x, b))) return false;
        }
        return true;
    }

    private static boolean columnsMatch(Pixels px, int a, int b, int fromY, int toY) {
        for (int y = fromY; y < toY; y++) {
            if (!same(px.at(a, y), px.at(b, y))) return false;
        }
        return true;
    }

    private static boolean same(int a, int b) {
        boolean clearA = !opaque(a);
        boolean clearB = !opaque(b);
        if (clearA || clearB) return clearA && clearB;
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > CHANNEL_TOLERANCE) return false;
        }
        return true;
    }

    private static boolean opaque(int argb) {
        return ((argb >>> 24) & 0xFF) >= MIN_OPAQUE_ALPHA;
    }

    private static int luma(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }
}

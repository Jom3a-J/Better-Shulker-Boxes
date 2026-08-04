package com.bettershulker.client.render;

/**
 * Geometry used to recompose a resource-pack container texture into the tooltip panel.
 *
 * <p>Resource packs normally provide only a bitmap, so the renderer needs both the source
 * coordinates of the storage grid and the output coordinates used by the tooltip. A profile can
 * describe a different panel without changing the normal vanilla/custom-theme renderer.</p>
 */
public record ResourcePackLayout(
        String id,
        int sourcePanelX,
        int sourcePanelY,
        int sourcePanelWidth,
        int sourcePanelHeight,
        int sourceSlotX,
        int sourceSlotY,
        int slotSize,
        int columns,
        int rows,
        int outputWidth,
        int outputSlotX,
        int outputSlotY,
        int bottomCapSourceY,
        int bottomCapHeight,
        int textureWidth,
        int textureHeight
) {
    private static final int MAX_LAYOUT_DIMENSION = 16_384;

    public static ResourcePackLayout standard() {
        return new ResourcePackLayout(
                "standard",
                0, 0, 176, 166,
                8, 18, 18, 9, 3,
                176, 8, 7,
                71, 7,
                256, 256
        );
    }

    public int panelWidth(int displayColumns) {
        int rightCapWidth = Math.max(0, this.outputWidth
                - (this.outputSlotX + this.columns * this.slotSize));
        return this.outputSlotX + Math.max(1, displayColumns) * this.slotSize + rightCapWidth;
    }

    public int panelHeight(int displayRows) {
        return this.outputSlotY + Math.max(1, displayRows) * this.slotSize + this.bottomCapHeight;
    }

    public int sourceRightX() {
        return this.sourceSlotX + this.columns * this.slotSize;
    }

    public int sourceRightWidth() {
        return Math.max(0, this.sourcePanelX + this.sourcePanelWidth - this.sourceRightX());
    }

    public ResourcePackLayout withOutputAdjustments(int offsetX, int offsetY, int capHeightOverride) {
        int adjustedSlotX = Math.max(0, this.outputSlotX + offsetX);
        int adjustedSlotY = Math.max(0, this.outputSlotY + offsetY);
        int rightCapWidth = Math.max(0, this.outputWidth
                - (this.outputSlotX + this.columns * this.slotSize));
        int adjustedWidth = adjustedSlotX + this.columns * this.slotSize + rightCapWidth;
        int adjustedCapHeight = capHeightOverride >= 0
                ? Math.min(capHeightOverride, MAX_LAYOUT_DIMENSION)
                : this.bottomCapHeight;
        return new ResourcePackLayout(
                this.id,
                this.sourcePanelX,
                this.sourcePanelY,
                this.sourcePanelWidth,
                this.sourcePanelHeight,
                this.sourceSlotX,
                this.sourceSlotY,
                this.slotSize,
                this.columns,
                this.rows,
                adjustedWidth,
                adjustedSlotX,
                adjustedSlotY,
                this.bottomCapSourceY,
                adjustedCapHeight,
                this.textureWidth,
                this.textureHeight
        );
    }

    public boolean isUsable() {
        long gridWidth = (long) this.columns * this.slotSize;
        long gridHeight = (long) this.rows * this.slotSize;
        return this.sourcePanelX >= 0
                && this.sourcePanelY >= 0
                && this.sourcePanelWidth > 0
                && this.sourcePanelHeight > 0
                && this.sourcePanelWidth <= MAX_LAYOUT_DIMENSION
                && this.sourcePanelHeight <= MAX_LAYOUT_DIMENSION
                && this.sourceSlotX >= this.sourcePanelX
                && this.sourceSlotY >= this.sourcePanelY
                && this.slotSize > 0
                && this.slotSize <= MAX_LAYOUT_DIMENSION
                && this.columns > 0
                && this.rows > 0
                && gridWidth <= MAX_LAYOUT_DIMENSION
                && gridHeight <= MAX_LAYOUT_DIMENSION
                && this.outputWidth > 0
                && this.outputWidth <= MAX_LAYOUT_DIMENSION
                && this.outputSlotX >= 0
                && this.outputSlotY >= 0
                && this.bottomCapSourceY >= 0
                && this.bottomCapHeight >= 0
                && this.bottomCapHeight <= MAX_LAYOUT_DIMENSION
                && this.textureWidth > 0
                && this.textureHeight > 0
                && this.textureWidth <= MAX_LAYOUT_DIMENSION
                && this.textureHeight <= MAX_LAYOUT_DIMENSION
                && rangeWithin(this.sourcePanelX, this.sourcePanelWidth, this.textureWidth)
                && rangeWithin(this.sourcePanelY, this.sourcePanelHeight, this.textureHeight)
                && rangeWithin(this.sourceSlotX, gridWidth, this.sourcePanelX + this.sourcePanelWidth)
                && rangeWithin(this.sourceSlotY, gridHeight, this.sourcePanelY + this.sourcePanelHeight)
                && rangeWithin(this.outputSlotX, gridWidth, this.outputWidth)
                && (long) this.outputSlotY + gridHeight + this.bottomCapHeight <= MAX_LAYOUT_DIMENSION
                && this.sourceRightX() <= this.sourcePanelX + this.sourcePanelWidth
                && this.sourceBottomWithinTexture();
    }

    private boolean sourceBottomWithinTexture() {
        return rangeWithin(this.bottomCapSourceY, Math.max(1, this.bottomCapHeight), this.textureHeight);
    }

    private static boolean rangeWithin(int start, long length, long limit) {
        return start >= 0 && length >= 0 && (long) start + length <= limit;
    }
}

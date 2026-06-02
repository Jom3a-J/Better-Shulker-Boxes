package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class ShulkerTooltipComponent implements ClientTooltipComponent {

    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 3;
    private static final int PADDING = 4;
    private final int topHeight;
    private final int bottomHeight;

    // Classic opaque Minecraft GUI container colors
    private static final int CONTAINER_BG = 0xFFC6C6C6; // Solid, completely opaque light-grey container base
    private static final int CONTAINER_BORDER = 0xFF373737; // Outer charcoal border
    private static final int BORDER_HIGHLIGHT = 0xFFFFFFFF; // Inner top-left white highlight
    private static final int BORDER_SHADOW = 0xFF8B8B8B; // Inner bottom-right grey shadow

    private static final int SLOT_BG_OUTER = 0xFF8B8B8B;
    private static final int SLOT_BG_INNER = 0xFF373737;
    private static final int SELECTION_HIGHLIGHT = 0xFFFFD700;
    private static final int TINT_ALPHA = 0xE0;

    private final NonNullList<ItemStack> contents;
    private final DyeColor color;
    private final boolean isEnderChest;
    private final String selectedItemName;
    private final int tintColor;
    private final int borderColor;
    private final int highlightColor;
    private final int shadowColor;
    private final int textColor;
    private final int badgeBgColor;
    private final int slotOuterColor;
    private final int slotInnerColor;
    private final int selectionHighlightColor;

    // Compact mode fields
    public static class GroupedSlot {
        public final ItemStack displayStack;
        public int totalCount;
        public final List<Integer> originalSlots = new java.util.ArrayList<>();

        public GroupedSlot(ItemStack stack, int slotIndex) {
            this.displayStack = stack.copy();
            this.totalCount = stack.getCount();
            this.originalSlots.add(slotIndex);
        }

        public void addStack(ItemStack stack, int slotIndex) {
            this.totalCount += stack.getCount();
            this.originalSlots.add(slotIndex);
        }
    }

    private final List<GroupedSlot> groupedSlots = new java.util.ArrayList<>();
    private final List<Integer> visibleIndices = new java.util.ArrayList<>();
    private final int gridCols;
    private final int gridRows;
    private int totalWidth;
    private final int totalHeight;
    private final boolean isContainerEmpty;
    private final String containerName;

    public ShulkerTooltipComponent(ShulkerTooltipData data) {
        this.contents = data.contents();
        this.color = data.color();
        this.isEnderChest = data.isEnderChest();
        this.selectedItemName = data.selectedItemName();
        this.containerName = data.containerName();

        boolean empty = true;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) {
                empty = false;
                break;
            }
        }
        this.isContainerEmpty = empty;

        if (BetterShulkerClient.isCompactModeActive()) {
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (!stack.isEmpty()) {
                    GroupedSlot found = null;
                    for (GroupedSlot gs : this.groupedSlots) {
                        if (ItemStack.isSameItemSameComponents(gs.displayStack, stack)) {
                            found = gs;
                            break;
                        }
                    }
                    if (found != null) {
                        found.addStack(stack, i);
                    } else {
                        this.groupedSlots.add(new GroupedSlot(stack, i));
                    }
                }
            }

            for (GroupedSlot gs : this.groupedSlots) {
                this.visibleIndices.add(gs.originalSlots.get(0));
            }
            
            if (this.isContainerEmpty) {
                this.topHeight = 12;
                this.bottomHeight = 0;
                this.gridCols = 4;
                this.gridRows = 1;
            } else {
                this.topHeight = 12;
                this.bottomHeight = 6;
                
                int selected = BetterShulkerClient.getSelectedSlotIndex();
                if (!this.visibleIndices.isEmpty()) {
                    if (!this.visibleIndices.contains(selected)) {
                        BetterShulkerClient.setSelectedSlotIndex(this.visibleIndices.get(0));
                    }
                }
                this.gridCols = Math.min(this.visibleIndices.size(), 9);
                this.gridRows = (this.visibleIndices.size() + this.gridCols - 1) / this.gridCols;
            }
        } else {
            for (int i = 0; i < contents.size(); i++) {
                this.visibleIndices.add(i);
            }
            this.topHeight = 0;
            this.bottomHeight = 0;
            this.gridCols = GRID_COLS;
            this.gridRows = GRID_ROWS;
        }

        int calculatedWidth = (this.gridCols * SLOT_SIZE) + (PADDING * 2) + 2;
        if (BetterShulkerClient.isCompactModeActive()) {
            this.totalWidth = Math.max(82, calculatedWidth);
        } else {
            this.totalWidth = calculatedWidth;
        }

        this.totalHeight = getCurrentHeight();

        int tempTintColor;
        int tempBorderColor;
        int tempHighlightColor;
        int tempShadowColor;
        int tempTextColor;
        int tempBadgeBgColor;
        int tempSlotOuter = SLOT_BG_OUTER;
        int tempSlotInner = SLOT_BG_INNER;

        if (this.isEnderChest) {
            tempTintColor = 0xFF100010;
            tempBorderColor = 0xFF102E20;
            tempHighlightColor = 0xFF1D5A3A;
            tempShadowColor = 0xFF081810;
            tempTextColor = 0xFFFFFFFF;
            tempBadgeBgColor = tempTintColor;
        } else if (this.color != null) {
            int rawColor = 0xFF000000 | this.color.getTextureDiffuseColor();
            tempTintColor = 0xFF100010;
            tempBorderColor = blendColor(rawColor, 0xFF000000, 0.4f);
            tempHighlightColor = blendColor(rawColor, 0xFFFFFFFF, 0.3f);
            tempShadowColor = blendColor(rawColor, 0xFF000000, 0.2f);
            tempTextColor = 0xFFFFFFFF;
            tempBadgeBgColor = tempTintColor;
        } else {
            tempTintColor = 0xFF100010;
            tempBorderColor = 0xFF160415;
            tempHighlightColor = 0xFF481D46;
            tempShadowColor = 0xFF260C25;
            tempTextColor = 0xFFFFFFFF;
            tempBadgeBgColor = tempTintColor;
        }

        // Apply theme overrides — each theme gets a full curated palette
        switch (BetterShulkerConfig.getTooltipTheme()) {
            case ORIGINAL:
                // keep defaults derived from container color
                break;

            case CLASSIC:
                // Deep forest aesthetic
                tempBorderColor = 0xFF4A7A25;
                tempTintColor = 0xE02D4A1A;
                tempTextColor = 0xFFD4E8C0; // soft sage
                tempSlotOuter = 0xFF3D5A28;  // mossy
                tempSlotInner = 0xFF1E2D12;  // deep moss
                break;

            case RETRO:
                // Cyberpunk neon arcade
                tempBorderColor = 0xFFFF00FF;
                tempTintColor = 0xE0080812;  // midnight blue-black
                tempTextColor = 0xFFFF66FF;  // warm neon pink
                tempSlotOuter = 0xFFAA00AA;  // dim magenta
                tempSlotInner = 0xFF1A0028;  // deep purple-black
                break;

            case SOLARIZED_DARK:
                // Canonical Solarized Dark with blue accent
                tempBorderColor = 0xFF268BD2;
                tempTintColor = 0xE0002B36;
                tempTextColor = 0xFF93A1A1;  // solarized base1
                tempSlotOuter = 0xFF073642;  // base02
                tempSlotInner = 0xFF002B36;  // base03
                break;

            case SOLARIZED_LIGHT:
                // Canonical Solarized Light with blue accent
                tempBorderColor = 0xFF268BD2;
                tempTintColor = 0xE0FDF6E3;
                tempTextColor = 0xFF586E75;  // solarized dark text
                tempSlotOuter = 0xFFEEE8D5;  // base2
                tempSlotInner = 0xFFDDD6C1;  // warm cream
                break;

            case PASTEL_SOFT:
                // Dreamy lavender pastel
                tempBorderColor = 0xFFA78BCC;
                tempTintColor = 0xE0E8DDF0;
                tempTextColor = 0xFF4A3660;  // dark plum
                tempSlotOuter = 0xFFD4C4E8;  // light lilac
                tempSlotInner = 0xFFBFA8D9;  // medium lilac
                break;

            case HIGH_CONTRAST:
                // Maximum accessibility — bold amber on pure black
                tempBorderColor = 0xFFFFAA00;
                tempTintColor = 0xF0000000;
                tempTextColor = 0xFFFFFF00;  // pure yellow
                tempSlotOuter = 0xFF333300;  // dark amber
                tempSlotInner = 0xFF1A1A00;  // near-black amber
                break;

            case CUSTOM:
                tempBorderColor = BetterShulkerConfig.getCustomBorderColor();
                tempTintColor = BetterShulkerConfig.getCustomBackgroundColor();
                break;

            case LIGHT:
                // Clean, modern light UI
                tempBorderColor = 0xFFB0B0B0;
                tempTintColor = 0xF0F5F5F5;
                tempTextColor = 0xFF333333;  // dark charcoal
                tempSlotOuter = 0xFFD0D0D0;  // light grey
                tempSlotInner = 0xFFE8E8E8;  // very light grey
                break;

            case GLASS:
                // Translucent frosted glass
                tempBorderColor = 0x80FFFFFF;
                tempTintColor = 0x60FFFFFF;
                tempTextColor = 0xFF2A2A2A;  // dark charcoal
                tempSlotOuter = 0x40FFFFFF;   // ghostly
                tempSlotInner = 0x20FFFFFF;   // very faint
                break;
        }

        // Re-calculate highlights and shadows for non-original themes to match border color
        if (BetterShulkerConfig.getTooltipTheme() != BetterShulkerConfig.TooltipTheme.ORIGINAL) {
            tempHighlightColor = blendColor(tempBorderColor, 0xFFFFFFFF, 0.3f);
            tempShadowColor = blendColor(tempBorderColor, 0xFF000000, 0.2f);

            // Unified theme matching background for name badge
            if (BetterShulkerConfig.getTooltipTheme() != BetterShulkerConfig.TooltipTheme.CUSTOM) {
                tempBadgeBgColor = tempTintColor;
            }
        }

        this.tintColor = tempTintColor;
        this.borderColor = tempBorderColor;
        this.highlightColor = tempHighlightColor;
        this.shadowColor = tempShadowColor;
        this.textColor = tempTextColor;
        this.badgeBgColor = tempBadgeBgColor;
        this.slotOuterColor = tempSlotOuter;
        this.slotInnerColor = tempSlotInner;

        this.selectionHighlightColor = BetterShulkerConfig.getCustomSelectionSquareColor();
    }

    @Override
    public int getHeight(Font textRenderer) {
        int searchHeight = (BetterShulkerClient.isSearchFocused() || !BetterShulkerClient.getSearchQuery().isEmpty()) ? 15 : 0;
        long timeSinceSort = System.currentTimeMillis() - BetterShulkerClient.getLastSortTime();
        boolean showSortBar = timeSinceSort < 2000 && BetterShulkerClient.getCurrentSortMode() != BetterShulkerClient.SortMode.NONE;
        int sortHeight = showSortBar ? 15 : 0;
        int targetHeight = this.totalHeight + searchHeight + sortHeight;

        if (!BetterShulkerConfig.hoverAnimationsEnabled) {
            return targetHeight;
        }

        long now = System.currentTimeMillis();
        long lastTime = BetterShulkerClient.getLastHeightUpdateTime();
        float currentHeight = BetterShulkerClient.getCurrentAnimatedHeight();

        if (lastTime == 0L || currentHeight <= 0f) {
            BetterShulkerClient.setCurrentAnimatedHeight(targetHeight);
            BetterShulkerClient.setLastHeightUpdateTime(now);
            return targetHeight;
        }

        float dt = Math.min(0.05f, (now - lastTime) / 1000f);
        BetterShulkerClient.setLastHeightUpdateTime(now);

        float speed = 10f; // Snappy height transitions
        currentHeight += (targetHeight - currentHeight) * Math.min(1.0f, speed * dt);
        BetterShulkerClient.setCurrentAnimatedHeight(currentHeight);

        return Math.round(currentHeight);
    }

    @Override
    public int getWidth(Font textRenderer) {
        int calculatedWidth = (this.gridCols * SLOT_SIZE) + (PADDING * 2) + 2;
        int minWidth = calculatedWidth;
        if (BetterShulkerClient.isCompactModeActive()) {
            minWidth = Math.max(82, calculatedWidth);
        }
        if (this.containerName != null && !this.containerName.isEmpty()) {
            int nameWidth = textRenderer.width(this.containerName) + 20; // 20px extra padding for nice margins
            this.totalWidth = Math.max(minWidth, nameWidth);
        } else {
            this.totalWidth = minWidth;
        }
        return this.totalWidth;
    }

    private int getCurrentHeight() {
        return (this.gridRows * SLOT_SIZE) + (PADDING * 2) + 2 + this.topHeight + this.bottomHeight;
    }

    @Override
    public void extractImage(Font textRenderer, int tooltipX, int tooltipY, int width, int height, GuiGraphicsExtractor context) {
        getWidth(textRenderer); // Force recalculation based on actual Font width
        int searchHeight = (BetterShulkerClient.isSearchFocused() || !BetterShulkerClient.getSearchQuery().isEmpty()) ? 15 : 0;
        long timeSinceSort = System.currentTimeMillis() - BetterShulkerClient.getLastSortTime();
        boolean showSortBar = timeSinceSort < 2000 && BetterShulkerClient.getCurrentSortMode() != BetterShulkerClient.SortMode.NONE;
        int sortHeight = showSortBar ? 15 : 0;
        int targetHeight = this.totalHeight + searchHeight + sortHeight;

        int currentHeight = targetHeight;
        if (BetterShulkerConfig.hoverAnimationsEnabled) {
            float animHeight = BetterShulkerClient.getCurrentAnimatedHeight();
            if (animHeight > 0) {
                currentHeight = Math.round(animHeight);
            }
        }
        long timeNow = System.currentTimeMillis();

        boolean hasBadge = BetterShulkerConfig.selectedItemNameEnabled && this.selectedItemName != null && !this.selectedItemName.isEmpty();
        int badgeX = 0;
        int badgeWidth = 0;
        int badgeHeight = 14;
        if (hasBadge) {
            int textWidth = textRenderer.width(this.selectedItemName);
            badgeWidth = textWidth + 14;
            badgeX = tooltipX + (this.totalWidth - badgeWidth) / 2;
        }

        // 1a. Breathing Outer Neon Glow (5-layer premium aura)
        float auraPulse = (float) Math.sin(timeNow / 250.0) * 0.25f + 0.75f;
        int rawBorderColor = borderColor & 0x00FFFFFF;

        // Layer 5 (outermost, faintest)
        int a5 = (int) (0x04 * auraPulse);
        int c5 = (a5 << 24) | rawBorderColor;
        context.fill(tooltipX - 5, tooltipY - 5, tooltipX + this.totalWidth + 5, tooltipY - 4, c5);
        context.fill(tooltipX - 5, tooltipY + currentHeight + 4, tooltipX + this.totalWidth + 5, tooltipY + currentHeight + 5, c5);
        context.fill(tooltipX - 5, tooltipY - 4, tooltipX - 4, tooltipY + currentHeight + 4, c5);
        context.fill(tooltipX + this.totalWidth + 4, tooltipY - 4, tooltipX + this.totalWidth + 5, tooltipY + currentHeight + 4, c5);

        // Layer 4
        int a4 = (int) (0x06 * auraPulse);
        int c4 = (a4 << 24) | rawBorderColor;
        context.fill(tooltipX - 4, tooltipY - 4, tooltipX + this.totalWidth + 4, tooltipY - 3, c4);
        context.fill(tooltipX - 4, tooltipY + currentHeight + 3, tooltipX + this.totalWidth + 4, tooltipY + currentHeight + 4, c4);
        context.fill(tooltipX - 4, tooltipY - 3, tooltipX - 3, tooltipY + currentHeight + 3, c4);
        context.fill(tooltipX + this.totalWidth + 3, tooltipY - 3, tooltipX + this.totalWidth + 4, tooltipY + currentHeight + 3, c4);

        // Layer 3
        int a3 = (int) (0x0A * auraPulse);
        int c3 = (a3 << 24) | rawBorderColor;
        context.fill(tooltipX - 3, tooltipY - 3, tooltipX + this.totalWidth + 3, tooltipY - 2, c3);
        context.fill(tooltipX - 3, tooltipY + currentHeight + 2, tooltipX + this.totalWidth + 3, tooltipY + currentHeight + 3, c3);
        context.fill(tooltipX - 3, tooltipY - 2, tooltipX - 2, tooltipY + currentHeight + 2, c3);
        context.fill(tooltipX + this.totalWidth + 2, tooltipY - 2, tooltipX + this.totalWidth + 3, tooltipY + currentHeight + 2, c3);

        // Layer 2
        int a2 = (int) (0x14 * auraPulse);
        int c2 = (a2 << 24) | rawBorderColor;
        context.fill(tooltipX - 2, tooltipY - 2, tooltipX + this.totalWidth + 2, tooltipY - 1, c2);
        context.fill(tooltipX - 2, tooltipY + currentHeight + 1, tooltipX + this.totalWidth + 2, tooltipY + currentHeight + 2, c2);
        context.fill(tooltipX - 2, tooltipY - 1, tooltipX - 1, tooltipY + currentHeight + 1, c2);
        context.fill(tooltipX + this.totalWidth + 1, tooltipY - 1, tooltipX + this.totalWidth + 2, tooltipY + currentHeight + 1, c2);

        // Layer 1 (innermost, strongest)
        int a1 = (int) (0x28 * auraPulse);
        int c1 = (a1 << 24) | rawBorderColor;
        context.fill(tooltipX - 1, tooltipY - 1, tooltipX + this.totalWidth + 1, tooltipY, c1);
        context.fill(tooltipX - 1, tooltipY + currentHeight, tooltipX + this.totalWidth + 1, tooltipY + currentHeight + 1, c1);
        context.fill(tooltipX - 1, tooltipY, tooltipX, tooltipY + currentHeight, c1);
        context.fill(tooltipX + this.totalWidth, tooltipY, tooltipX + this.totalWidth + 1, tooltipY + currentHeight, c1);

        // 1b. Draw solid background
        context.fill(tooltipX, tooltipY, tooltipX + this.totalWidth, tooltipY + currentHeight, tintColor);

        // 1b2. Inner vignette (subtle darkened edges for depth)
        int vignetteColor = 0x10000000;
        context.fill(tooltipX + 1, tooltipY + 1, tooltipX + this.totalWidth - 1, tooltipY + 3, vignetteColor);
        context.fill(tooltipX + 1, tooltipY + currentHeight - 3, tooltipX + this.totalWidth - 1, tooltipY + currentHeight - 1, vignetteColor);
        context.fill(tooltipX + 1, tooltipY + 3, tooltipX + 3, tooltipY + currentHeight - 3, vignetteColor);
        context.fill(tooltipX + this.totalWidth - 3, tooltipY + 3, tooltipX + this.totalWidth - 1, tooltipY + currentHeight - 3, vignetteColor);

        // 1c. Drifting Celestial Stardust (Ender Chest portal particles)
        if (this.isEnderChest) {
            int innerW = this.totalWidth - 4;
            int innerH = currentHeight - 4;
            for (int i = 0; i < 12; i++) {
                double seedX = Math.sin(i * 43758.5453) * 0.5 + 0.5;
                double seedY = Math.cos(i * 12345.6789) * 0.5 + 0.5;
                double driftX = (timeNow / 6000.0 * (i % 2 == 0 ? 1 : -1)) * 0.08;
                double driftY = (timeNow / 9000.0) * 0.06;

                double fx = (seedX + driftX) % 1.0;
                if (fx < 0) fx += 1.0;
                double fy = (seedY + driftY) % 1.0;
                if (fy < 0) fy += 1.0;

                int px = tooltipX + 2 + (int) (fx * innerW);
                int py = tooltipY + 2 + (int) (fy * innerH);

                float twinkle = (float) Math.sin((timeNow / 300.0) + i) * 0.4f + 0.6f;
                int alpha = (int) (twinkle * 180);
                alpha = Math.max(0, Math.min(255, alpha));
                int particleColor = (alpha << 24) | 0x00FFDD;

                context.fill(px, py, px + 1, py + 1, particleColor);
            }
        }

        // 2a. Draw 3D boundary borders (matching Minecraft GUI style perfectly)
        if (hasBadge) {
            context.fill(tooltipX, tooltipY, badgeX + 1, tooltipY + 1, borderColor); // Top Left
            context.fill(badgeX + badgeWidth - 1, tooltipY, tooltipX + this.totalWidth, tooltipY + 1, borderColor); // Top Right
        } else {
            context.fill(tooltipX, tooltipY, tooltipX + this.totalWidth, tooltipY + 1, borderColor); // Top
        }
        context.fill(tooltipX, tooltipY + currentHeight - 1, tooltipX + this.totalWidth, tooltipY + currentHeight, borderColor); // Bottom
        context.fill(tooltipX, tooltipY + 1, tooltipX + 1, tooltipY + currentHeight - 1, borderColor); // Left
        context.fill(tooltipX + this.totalWidth - 1, tooltipY + 1, tooltipX + this.totalWidth, tooltipY + currentHeight - 1, borderColor); // Right

        // 2b. Animated Glossy Sheen (Glassmorphism Sweep)
        if (BetterShulkerConfig.hoverAnimationsEnabled) {
            float sweepProgress = (float) (timeNow % 3500) / 3500f;
            float sweepX = tooltipX - 40 + (sweepProgress * (this.totalWidth + 80));
            int sheenColor = 0x1AFFFFFF; // Elegant ~10% white sweep

            for (int x = tooltipX + 2; x < tooltipX + this.totalWidth - 2; x++) {
                int yMin = tooltipY + 2 + (int)(x - sweepX - 8);
                int yMax = tooltipY + 2 + (int)(x - sweepX + 8);
                
                yMin = Math.max(tooltipY + 2, yMin);
                yMax = Math.min(tooltipY + currentHeight - 2, yMax);
                
                if (yMin < yMax) {
                    context.fill(x, yMin, x + 1, yMax, sheenColor);
                }
            }
        }

        // 2c. RETRO Scanline Overlay (CRT monitor effect)
        if (BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.RETRO) {
            int scanlineColor = 0x12000000; // very subtle dark lines
            for (int sy = tooltipY + 2; sy < tooltipY + currentHeight - 2; sy += 2) {
                context.fill(tooltipX + 2, sy, tooltipX + this.totalWidth - 2, sy + 1, scanlineColor);
            }
        }

        // Inner highlight (Top and Left 1px inset)
        if (hasBadge) {
            context.fill(tooltipX + 1, tooltipY + 1, badgeX + 1, tooltipY + 2, highlightColor); // Inner Top Left
            context.fill(badgeX + badgeWidth - 1, tooltipY + 1, tooltipX + this.totalWidth - 1, tooltipY + 2, highlightColor); // Inner Top Right
        } else {
            context.fill(tooltipX + 1, tooltipY + 1, tooltipX + this.totalWidth - 1, tooltipY + 2, highlightColor); // Inner Top
        }
        context.fill(tooltipX + 1, tooltipY + 2, tooltipX + 2, tooltipY + currentHeight - 2, highlightColor); // Inner Left

        // Inner shadow (Bottom and Right 1px inset)
        context.fill(tooltipX + 1, tooltipY + currentHeight - 2, tooltipX + this.totalWidth - 1, tooltipY + currentHeight - 1, shadowColor); // Inner Bottom
        context.fill(tooltipX + this.totalWidth - 2, tooltipY + 2, tooltipX + this.totalWidth - 1, tooltipY + currentHeight - 2, shadowColor); // Inner Right

        // Corner accent dots (premium decorative detail)
        int cornerColor = (0x50 << 24) | rawBorderColor;
        context.fill(tooltipX + 2, tooltipY + 2, tooltipX + 3, tooltipY + 3, cornerColor);
        context.fill(tooltipX + this.totalWidth - 3, tooltipY + 2, tooltipX + this.totalWidth - 2, tooltipY + 3, cornerColor);
        context.fill(tooltipX + 2, tooltipY + currentHeight - 3, tooltipX + 3, tooltipY + currentHeight - 2, cornerColor);
        context.fill(tooltipX + this.totalWidth - 3, tooltipY + currentHeight - 3, tooltipX + this.totalWidth - 2, tooltipY + currentHeight - 2, cornerColor);

        // Compute centering xOffset for slots grid
        int gridWidth = this.gridCols * SLOT_SIZE;
        int xOffset = (this.totalWidth - 2 - (PADDING * 2) - gridWidth) / 2;

        // Render Item Count Summary Header in compact mode
        if (BetterShulkerClient.isCompactModeActive()) {
            int occupiedCount = 0;
            int totalItemCount = 0;
            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) {
                    occupiedCount++;
                    totalItemCount += stack.getCount();
                }
            }
            int totalSlots = contents.size();
            String headerText = occupiedCount + "/" + totalSlots + " slots • " + totalItemCount + " items";
            if (this.isContainerEmpty) {
                headerText = "Empty Container";
            }
            int textW = textRenderer.width(headerText);
            int textX = tooltipX + (this.totalWidth - textW) / 2;
            int textY = tooltipY + PADDING + 2;
            
            context.text(textRenderer, Component.literal(headerText), textX + 1, textY + 1, 0x50000000);
            context.text(textRenderer, Component.literal(headerText), textX, textY, (textColor & 0x00FFFFFF) | 0xAA000000);
        }

        int hoveredSlotIndex = -1;
        BetterShulkerClient.setHoveredTooltipSlotIndex(-1);

        int mouseX = BetterShulkerClient.getLastMouseX();
        int mouseY = BetterShulkerClient.getLastMouseY();

        // 3. Pre-calculate hovered slot index in grid coordinate system
        for (int idx = 0; idx < this.visibleIndices.size(); idx++) {
            int i = this.visibleIndices.get(idx);
            int col = idx % this.gridCols;
            int row = idx / this.gridCols;
            int slotX = tooltipX + PADDING + 1 + xOffset + col * SLOT_SIZE;
            int slotY = tooltipY + PADDING + 1 + this.topHeight + row * SLOT_SIZE;

            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE - 1
                    && mouseY >= slotY && mouseY < slotY + SLOT_SIZE - 1) {
                hoveredSlotIndex = i;
                BetterShulkerClient.setHoveredTooltipSlotIndex(i);
                break;
            }
        }

        // Slot scaling physics parameters
        long lastSlotScaleTime = BetterShulkerClient.getLastSlotScaleUpdateTime();
        float dt = (lastSlotScaleTime == 0L) ? 0f : (timeNow - lastSlotScaleTime) / 1000f;
        BetterShulkerClient.setLastSlotScaleUpdateTime(timeNow);
        dt = Math.min(0.05f, dt);
        float[] slotScales = BetterShulkerClient.getSlotScales();

        // 4. Render slots and items
        if (BetterShulkerClient.isCompactModeActive() && this.isContainerEmpty) {
            int boxX1 = tooltipX + PADDING + 1;
            int boxY1 = tooltipY + PADDING + 1 + this.topHeight;
            int boxX2 = tooltipX + this.totalWidth - PADDING - 1;
            int boxY2 = tooltipY + currentHeight - PADDING - 1 - this.bottomHeight;
            
            int dashedColor = (0x30 << 24) | (borderColor & 0x00FFFFFF);
            // Draw a subtle border
            context.fill(boxX1, boxY1, boxX2, boxY1 + 1, dashedColor);
            context.fill(boxX1, boxY2 - 1, boxX2, boxY2, dashedColor);
            context.fill(boxX1, boxY1 + 1, boxX1 + 1, boxY2 - 1, dashedColor);
            context.fill(boxX2 - 1, boxY1 + 1, boxX2, boxY2 - 1, dashedColor);

            String emptyText = "Empty";
            int textW = textRenderer.width(emptyText);
            int textX = tooltipX + (this.totalWidth - textW) / 2;
            int textY = boxY1 + (18 - 8) / 2; // Center inside the 18px slots row
            
            int emptyTextColor = (0x50 << 24) | (textColor & 0x00FFFFFF);
            int emptyTextShadow = (0x50 << 24) | 0x000000;
            context.text(textRenderer, Component.literal(emptyText), textX + 1, textY + 1, emptyTextShadow);
            context.text(textRenderer, Component.literal(emptyText), textX, textY, emptyTextColor);
        } else {
            for (int idx = 0; idx < this.visibleIndices.size(); idx++) {
                int i = this.visibleIndices.get(idx);
                int col = idx % this.gridCols;
                int row = idx / this.gridCols;
                int slotX = tooltipX + PADDING + 1 + xOffset + col * SLOT_SIZE;
                int slotY = tooltipY + PADDING + 1 + this.topHeight + row * SLOT_SIZE;

                // Smooth scaling transition for hovered slot background & items
                float targetScale = (BetterShulkerConfig.hoverAnimationsEnabled && i == hoveredSlotIndex) ? 1.08f : 1.0f;
                slotScales[i] += (targetScale - slotScales[i]) * Math.min(1.0f, 15f * dt);
                float scale = slotScales[i];

                boolean hasScale = scale > 1.0f;
                if (hasScale) {
                    context.pose().pushMatrix();
                    float centerX = slotX + (SLOT_SIZE - 1) / 2.0f;
                    float centerY = slotY + (SLOT_SIZE - 1) / 2.0f;
                    context.pose().translate(centerX, centerY);
                    context.pose().scale(scale, scale);
                    context.pose().translate(-centerX, -centerY);
                }

                // Draw slot backgrounds
                if (BetterShulkerClient.isCompactModeActive() && this.isContainerEmpty) {
                    int outlineColor = (0x80 << 24) | (borderColor & 0x00FFFFFF);
                    int innerFillColor = (0x30 << 24) | (borderColor & 0x00FFFFFF);

                    // Draw outline (1px border)
                    context.fill(slotX, slotY, slotX + SLOT_SIZE - 1, slotY + 1, outlineColor); // Top
                    context.fill(slotX, slotY + SLOT_SIZE - 2, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, outlineColor); // Bottom
                    context.fill(slotX, slotY + 1, slotX + 1, slotY + SLOT_SIZE - 2, outlineColor); // Left
                    context.fill(slotX + SLOT_SIZE - 2, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 2, outlineColor); // Right

                    // Draw soft inner fill
                    context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, innerFillColor);
                } else {
                    context.fill(slotX, slotY, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, slotOuterColor);
                    context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, slotInnerColor);

                    // 3D inset highlight on slots (top-left light edge)
                    int slotHL = 0x18FFFFFF;
                    context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 2, slotY + 2, slotHL);
                    context.fill(slotX + 1, slotY + 2, slotX + 2, slotY + SLOT_SIZE - 2, slotHL);

                    // 5a. Empty Slot Blueprint Outline (faint hud grid outline)
                    if (contents.get(i).isEmpty()) {
                        int faintColor = (0x1A << 24) | (borderColor & 0x00FFFFFF);
                        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 2, slotY + 2, faintColor); // Top
                        context.fill(slotX + 1, slotY + SLOT_SIZE - 3, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, faintColor); // Bottom
                        context.fill(slotX + 1, slotY + 2, slotX + 2, slotY + SLOT_SIZE - 3, faintColor); // Left
                        context.fill(slotX + SLOT_SIZE - 3, slotY + 2, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 3, faintColor); // Right
                    }
                }

                // Hover pulsing glow outer border (matching Selection Square color)
                if (BetterShulkerConfig.hoverAnimationsEnabled && i == hoveredSlotIndex) {
                    float pulse = (float) Math.sin(timeNow / 150.0) * 0.5f + 0.5f;
                    int glowAlpha = (int) (60 + 80 * pulse);
                    int glowColor = (glowAlpha << 24) | (this.selectionHighlightColor & 0x00FFFFFF);
                    context.fill(slotX - 1, slotY - 1, slotX + SLOT_SIZE, slotY, glowColor); // Top
                    context.fill(slotX - 1, slotY + SLOT_SIZE - 1, slotX + SLOT_SIZE, slotY + SLOT_SIZE, glowColor); // Bottom
                    context.fill(slotX - 1, slotY, slotX, slotY + SLOT_SIZE - 1, glowColor); // Left
                    context.fill(slotX + SLOT_SIZE - 1, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE - 1, glowColor); // Right
                }

                // Draw items and annotations
                ItemStack stack = contents.get(i);
                if (!stack.isEmpty()) {
                    boolean isRare = stack.isEnchanted() || stack.getItem().getDescriptionId().contains("netherite") || stack.getItem().getDescriptionId().contains("diamond") || stack.getItem().getDescriptionId().contains("golden_apple");
                    boolean doWobble = BetterShulkerConfig.rareItemWobbleEnabled && isRare;

                    if (doWobble) {
                        context.pose().pushMatrix();
                        float wobbleOffset = (float) (Math.sin(timeNow / 250.0 + i * 2.0) * 1.2);
                        context.pose().translate(0.0f, wobbleOffset);
                    }

                    context.item(stack, slotX + 1, slotY + 1);

                    if (BetterShulkerClient.isCompactModeActive()) {
                        GroupedSlot matchedGroup = null;
                        for (GroupedSlot gs : this.groupedSlots) {
                            if (gs.originalSlots.get(0) == i) {
                                matchedGroup = gs;
                                break;
                            }
                        }
                        if (matchedGroup != null && matchedGroup.totalCount > 1) {
                            String countText = String.valueOf(matchedGroup.totalCount);
                            int textX = slotX + 17 - textRenderer.width(countText);
                            int textY = slotY + 9;
                            context.text(textRenderer, Component.literal(countText), textX + 1, textY + 1, 0xFF000000); // Shadow
                            context.text(textRenderer, Component.literal(countText), textX, textY, 0xFFFFFFFF); // White count
                        }
                    } else {
                        context.itemDecorations(textRenderer, stack, slotX + 1, slotY + 1);
                    }

                    if (doWobble) {
                        context.pose().popMatrix();
                    }
                }

                // Filter overlay logic
                ItemStack filterStack = BetterShulkerClient.getFilterItemStack();
                String query = BetterShulkerClient.getSearchQuery();
                boolean isFiltering = !filterStack.isEmpty();
                boolean isSearching = !query.isEmpty();

                boolean matches = true;
                if (isFiltering) {
                    matches = !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, filterStack);
                }
                if (isSearching) {
                    matches = matches && parseAndMatchQuery(stack, query);
                }

                if (isFiltering || isSearching) {
                    if (matches) {
                        int highlightColor = 0xFF55FF55; // Vibrant Green border
                        context.fill(slotX, slotY, slotX + SLOT_SIZE - 1, slotY + 1, highlightColor); // Top
                        context.fill(slotX, slotY + SLOT_SIZE - 2, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, highlightColor); // Bottom
                        context.fill(slotX, slotY + 1, slotX + 1, slotY + SLOT_SIZE - 2, highlightColor); // Left
                        context.fill(slotX + SLOT_SIZE - 2, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 2, highlightColor); // Right
                    } else {
                        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, 0xB0000000); // Dim non-matches
                    }
                }

                if (hasScale) {
                    context.pose().popMatrix();
                }
            }
        }

        // Draw fill percentage bar in compact mode
        if (BetterShulkerClient.isCompactModeActive() && !this.isContainerEmpty) {
            int occupiedCount = 0;
            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) {
                    occupiedCount++;
                }
            }
            float fillRatio = (float) occupiedCount / contents.size();

            int barX = tooltipX + PADDING + 1;
            int barY = tooltipY + PADDING + 1 + this.topHeight + (this.gridRows * SLOT_SIZE) + 2;
            int barWidth = this.totalWidth - (PADDING * 2) - 2;

            // Background track
            context.fill(barX, barY, barX + barWidth, barY + 3, 0x40000000);

            // Colored fill
            int fillWidth = Math.round(fillRatio * barWidth);
            if (fillWidth > 0) {
                int fillBarColor;
                if (fillRatio < 0.5f) {
                    float f = fillRatio / 0.5f;
                    fillBarColor = blendColor(0xFF55FF55, 0xFFFFD700, f);
                } else {
                    float f = (fillRatio - 0.5f) / 0.5f;
                    fillBarColor = blendColor(0xFFFFD700, 0xFFFF5555, f);
                }
                context.fill(barX, barY, barX + fillWidth, barY + 3, fillBarColor);

                // Gloss shine overlay
                context.fill(barX, barY, barX + fillWidth, barY + 1, 0x40FFFFFF);
            }
        }

        // 5. Draw selected slot with fluid gliding animation
        int selectedSlot = BetterShulkerClient.getSelectedSlotIndex();
        int selectedIdx = this.visibleIndices.indexOf(selectedSlot);
        if (selectedIdx >= 0 && BetterShulkerConfig.secondaryTooltipEnabled) {
            int targetCol = selectedIdx % this.gridCols;
            int targetRow = selectedIdx / this.gridCols;

            float renderCol = targetCol;
            float renderRow = targetRow;

            if (BetterShulkerConfig.selectionGlideEnabled) {
                long lastTime = BetterShulkerClient.getLastHighlightRenderTime();
                float currentCol = BetterShulkerClient.getCurrentSelectedCol();
                float currentRow = BetterShulkerClient.getCurrentSelectedRow();

                if (lastTime == 0L || currentCol == -1f || currentRow == -1f) {
                    BetterShulkerClient.setCurrentSelectedCol(targetCol);
                    BetterShulkerClient.setCurrentSelectedRow(targetRow);
                    currentCol = targetCol;
                    currentRow = targetRow;
                } else {
                    float glideDt = Math.min(0.05f, (timeNow - lastTime) / 1000f);
                    float speed = 15f; // Extremely snappy glide speed
                    currentCol += (targetCol - currentCol) * Math.min(1.0f, speed * glideDt);
                    currentRow += (targetRow - currentRow) * Math.min(1.0f, speed * glideDt);
                    BetterShulkerClient.setCurrentSelectedCol(currentCol);
                    BetterShulkerClient.setCurrentSelectedRow(currentRow);
                }
                BetterShulkerClient.setLastHighlightRenderTime(timeNow);
                renderCol = currentCol;
                renderRow = currentRow;
            }

            int selSlotX = tooltipX + PADDING + 1 + xOffset + (int)(renderCol * SLOT_SIZE);
            int selSlotY = tooltipY + PADDING + 1 + this.topHeight + (int)(renderRow * SLOT_SIZE);

            // 5b. Pulsing Golden Highlight Overlay
            float borderPulse = (float) Math.sin(timeNow / 150.0) * 0.15f + 0.85f;
            int pulseR = (int)(((this.selectionHighlightColor >> 16) & 0xFF) * borderPulse);
            int pulseG = (int)(((this.selectionHighlightColor >> 8) & 0xFF) * borderPulse);
            int pulseB = (int)((this.selectionHighlightColor & 0xFF) * borderPulse);
            int currentSelectionHighlight = 0xFF000000 | (pulseR << 16) | (pulseG << 8) | pulseB;

            context.fill(selSlotX, selSlotY, selSlotX + SLOT_SIZE - 1, selSlotY + 1, currentSelectionHighlight); // Top outline
            context.fill(selSlotX, selSlotY + SLOT_SIZE - 2, selSlotX + SLOT_SIZE - 1, selSlotY + SLOT_SIZE - 1, currentSelectionHighlight); // Bottom outline
            context.fill(selSlotX, selSlotY + 1, selSlotX + 1, selSlotY + SLOT_SIZE - 2, currentSelectionHighlight); // Left outline
            context.fill(selSlotX + SLOT_SIZE - 2, selSlotY + 1, selSlotX + SLOT_SIZE - 1, selSlotY + SLOT_SIZE - 2, currentSelectionHighlight); // Right outline

            int goldPulseAlpha = (int) (0x24 + 0x18 * Math.sin(timeNow / 150.0));
            int selectColor = (goldPulseAlpha << 24) | (this.selectionHighlightColor & 0x00FFFFFF);
            context.fill(selSlotX + 1, selSlotY + 1, selSlotX + SLOT_SIZE - 2, selSlotY + SLOT_SIZE - 2, selectColor);
        }

        // Draw multi-select selection highlights (cyan border)
        java.util.Set<Integer> multiSelected = BetterShulkerClient.getSelectedSlotsSet();
        for (int i : multiSelected) {
            int idxInVisible = this.visibleIndices.indexOf(i);
            if (idxInVisible >= 0) {
                int selCol = idxInVisible % this.gridCols;
                int selRow = idxInVisible / this.gridCols;
                int selSlotX = tooltipX + PADDING + 1 + xOffset + selCol * SLOT_SIZE;
                int selSlotY = tooltipY + PADDING + 1 + this.topHeight + selRow * SLOT_SIZE;

                int cyan = 0xFF55FFFF;
                context.fill(selSlotX, selSlotY, selSlotX + SLOT_SIZE - 1, selSlotY + 1, cyan); // Top
                context.fill(selSlotX, selSlotY + SLOT_SIZE - 2, selSlotX + SLOT_SIZE - 1, selSlotY + SLOT_SIZE - 1, cyan); // Bottom
                context.fill(selSlotX, selSlotY + 1, selSlotX + 1, selSlotY + SLOT_SIZE - 2, cyan); // Left
                context.fill(selSlotX + SLOT_SIZE - 2, selSlotY + 1, selSlotX + SLOT_SIZE - 1, selSlotY + SLOT_SIZE - 2, cyan); // Right

                // Premium translucent cyan fill overlay inside the slot
                int cyanFill = (0x26 << 24) | 0x00FFFF; // ~15% opacity cyan
                context.fill(selSlotX + 1, selSlotY + 1, selSlotX + SLOT_SIZE - 2, selSlotY + SLOT_SIZE - 2, cyanFill);
            }
        }

        String hoveredItemName = null;
        if (hoveredSlotIndex >= 0 && hoveredSlotIndex < contents.size()) {
            ItemStack hoveredStack = contents.get(hoveredSlotIndex);
            if (!hoveredStack.isEmpty()) {
                hoveredItemName = hoveredStack.getHoverName().getString();
                List<Component> tooltipLines = List.of(hoveredStack.getHoverName());
                context.setTooltipForNextFrame(textRenderer, tooltipLines,
                        java.util.Optional.empty(), (int) mouseX, (int) mouseY);
            }
        }

        // Secondary tooltip: only show if selected != hovered AND not showing the same item name
        if (BetterShulkerConfig.secondaryTooltipEnabled && selectedSlot != hoveredSlotIndex
                && selectedIdx >= 0) {
            ItemStack selectedStack = contents.get(selectedSlot);
            if (!selectedStack.isEmpty()) {
                String selName = selectedStack.getHoverName().getString();
                // Skip if the hovered item already has the same name (avoids visual duplication)
                if (hoveredItemName == null || !hoveredItemName.equals(selName)) {
                    List<Component> tooltipLines = List.of(selectedStack.getHoverName());
                    int maxTextWidth = 0;
                    for (Component line : tooltipLines) {
                        maxTextWidth = Math.max(maxTextWidth, textRenderer.width(line));
                    }
                    int tooltipWidth = maxTextWidth + 12;
                    int x = (int) mouseX - tooltipWidth - 12;
                    int y = (int) mouseY - 10;
                    context.setTooltipForNextFrame(textRenderer, tooltipLines,
                            java.util.Optional.empty(), x, y);
                }
            }
        }

        // Render premium name badge block (Selected Item Name)
        if (hasBadge) {
            // Position badge above the vanilla text area so the item name
            // sits above the container name, all as one unified block
            int vanillaTextHeight = 15; // vanilla item name line + padding above our component
            int badgeY = tooltipY - vanillaTextHeight - badgeHeight;

            int badgeBg = this.badgeBgColor;
            int badgeBorder = this.borderColor;
            if (BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.CUSTOM) {
                badgeBg = BetterShulkerConfig.getCustomNameBgColor();
                badgeBorder = BetterShulkerConfig.getCustomNameBorderColor();
            }

            // Badge glow aura (compact around badge only)
            int badgeGlow = (0x18 << 24) | (badgeBorder & 0x00FFFFFF);
            context.fill(badgeX, badgeY - 1, badgeX + badgeWidth, badgeY, badgeGlow); // Top
            context.fill(badgeX - 1, badgeY, badgeX, badgeY + badgeHeight, badgeGlow); // Left
            context.fill(badgeX + badgeWidth, badgeY, badgeX + badgeWidth + 1, badgeY + badgeHeight, badgeGlow); // Right
            context.fill(badgeX, badgeY + badgeHeight, badgeX + badgeWidth, badgeY + badgeHeight + 1, badgeGlow); // Bottom

            // Badge background — compact, just the badge area
            context.fill(badgeX + 1, badgeY + 1, badgeX + badgeWidth - 1, badgeY + badgeHeight - 1, badgeBg);

            // Badge border — full box
            context.fill(badgeX + 1, badgeY, badgeX + badgeWidth - 1, badgeY + 1, badgeBorder); // Top
            context.fill(badgeX, badgeY + 1, badgeX + 1, badgeY + badgeHeight - 1, badgeBorder); // Left
            context.fill(badgeX + badgeWidth - 1, badgeY + 1, badgeX + badgeWidth, badgeY + badgeHeight - 1, badgeBorder); // Right
            context.fill(badgeX + 1, badgeY + badgeHeight - 1, badgeX + badgeWidth - 1, badgeY + badgeHeight, badgeBorder); // Bottom

            // Always white text with dark drop shadow for readability
            int textWidth = textRenderer.width(this.selectedItemName);
            int textX = badgeX + (badgeWidth - textWidth) / 2;
            int textY = badgeY + 3;
            context.text(textRenderer, Component.literal(this.selectedItemName), textX + 1, textY + 1, 0xFF000000); // Shadow
            context.text(textRenderer, Component.literal(this.selectedItemName), textX, textY, 0xFFFFFFFF); // White text
        }

        // 6. Dynamic Color-Matched Selected Slot Scroll Indicator (cyberpunk capsule HUD)
        if (BetterShulkerConfig.secondaryTooltipEnabled && this.gridRows > 1) {
            int trackX = tooltipX + this.totalWidth - 4;
            int trackY1 = tooltipY + PADDING + 2;
            int trackY2 = tooltipY + this.totalHeight - PADDING - 2;
            int trackHeight = trackY2 - trackY1;

            float progressRatio = BetterShulkerClient.getCurrentSelectedRow() / (this.gridRows - 1);
            int dotCenterY = trackY1 + (int)(progressRatio * trackHeight);

            // Draw thin vertical track
            int trackColor = 0x3F000000 | (borderColor & 0x00FFFFFF);
            context.fill(trackX, trackY1, trackX + 1, trackY2, trackColor);

            // Draw glowing scroll indicator capsule
            int glowColor = (0x40 << 24) | (borderColor & 0x00FFFFFF);
            int coreColor = 0xFF000000 | (borderColor & 0x00FFFFFF);

            // Soft glow
            context.fill(trackX - 1, dotCenterY - 3, trackX + 2, dotCenterY + 3, glowColor);
            // Hard core
            context.fill(trackX, dotCenterY - 2, trackX + 1, dotCenterY + 2, coreColor);
        }

        int extraY = this.totalHeight;

        // 4a. Sort Bar HUD Panel
        if (showSortBar) {
            float fadeAlpha = 1.0f;
            if (timeSinceSort > 1500) {
                fadeAlpha = 1.0f - (timeSinceSort - 1500) / 500.0f;
                fadeAlpha = Math.max(0.0f, Math.min(1.0f, fadeAlpha));
            }
            int alphaInt = (int)(fadeAlpha * 255);

            float slideProgress = Math.min(1.0f, timeSinceSort / 300.0f);
            int slideOffset = (int) ((1.0f - slideProgress) * -8.0f);

            int panelX1 = tooltipX + PADDING;
            int panelX2 = tooltipX + this.totalWidth - PADDING;
            int panelY1 = tooltipY + extraY + 1;
            int panelY2 = tooltipY + extraY + 13;

            int hudBg = ((int)(alphaInt * 0.4f) << 24) | 0x00000000;
            int hudBorder = ((int)(alphaInt * 0.5f) << 24) | (borderColor & 0x00FFFFFF);

            // Draw HUD box background and borders
            context.fill(panelX1 + 1, panelY1, panelX2 - 1, panelY2, hudBg);
            context.fill(panelX1 + 1, panelY1, panelX2 - 1, panelY1 + 1, hudBorder); // Top
            context.fill(panelX1 + 1, panelY2 - 1, panelX2 - 1, panelY2, hudBorder); // Bottom
            context.fill(panelX1, panelY1 + 1, panelX1 + 1, panelY2 - 1, hudBorder); // Left
            context.fill(panelX2 - 1, panelY1 + 1, panelX2, panelY2 - 1, hudBorder); // Right

            // Draw sort icon (⇅) and text
            int textX = panelX1 + 4 + slideOffset;
            int textY = panelY1 + 2;
            int goldColor = (alphaInt << 24) | 0xFFD700;
            int aquaColor = (alphaInt << 24) | 0x00FFFF;

            context.text(textRenderer, Component.literal("⇅ Sort: "), textX, textY, goldColor);
            int labelWidth = textRenderer.width("⇅ Sort: ");
            context.text(textRenderer, Component.literal(BetterShulkerClient.getCurrentSortMode().getDisplayName()), textX + labelWidth, textY, aquaColor);

            extraY += 15;
        }

        // 4b. Search Bar HUD Panel
        if (searchHeight > 0) {
            int panelX1 = tooltipX + PADDING;
            int panelX2 = tooltipX + this.totalWidth - PADDING;
            int panelY1 = tooltipY + extraY + 1;
            int panelY2 = tooltipY + extraY + 13;

            int hudBg = 0x40000000;
            int hudBorder = BetterShulkerClient.isSearchFocused() ? 0xFFFFAA00 : (0x60 << 24) | (borderColor & 0x00FFFFFF);

            // Draw HUD box background and borders
            context.fill(panelX1 + 1, panelY1, panelX2 - 1, panelY2, hudBg);
            context.fill(panelX1 + 1, panelY1, panelX2 - 1, panelY1 + 1, hudBorder); // Top
            context.fill(panelX1 + 1, panelY2 - 1, panelX2 - 1, panelY2, hudBorder); // Bottom
            context.fill(panelX1, panelY1 + 1, panelX1 + 1, panelY2 - 1, hudBorder); // Left
            context.fill(panelX2 - 1, panelY1 + 1, panelX2, panelY2 - 1, hudBorder); // Right

            // Construct search display text
            String query = BetterShulkerClient.getSearchQuery();
            String cursor = BetterShulkerClient.isSearchFocused() && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";

            int textX = panelX1 + 4;
            int textY = panelY1 + 2;
            int labelColor = BetterShulkerClient.isSearchFocused() ? 0xFFFFAA00 : 0xFFFFD700;
            context.text(textRenderer, Component.literal("🔍 Search: "), textX, textY, labelColor);
            int labelWidth = textRenderer.width("🔍 Search: ");
            context.text(textRenderer, Component.literal(query + cursor), textX + labelWidth, textY, textColor);
        }
    }

    private static int blendColor(int colorA, int colorB, float factor) {
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

    public static int hslToRgb(float h, float s, float l) {
        float q = l < 0.5f ? l * (1.0f + s) : l + s - l * s;
        float p = 2.0f * l - q;
        float r = hueToRgb(p, q, h + 1.0f/3.0f);
        float g = hueToRgb(p, q, h);
        float b = hueToRgb(p, q, h - 1.0f/3.0f);
        return 0xFF000000 | ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0.0f) t += 1.0f;
        if (t > 1.0f) t -= 1.0f;
        if (t < 1.0f/6.0f) return p + (q - p) * 6.0f * t;
        if (t < 1.0f/2.0f) return q;
        if (t < 2.0f/3.0f) return p + (q - p) * (2.0f/3.0f - t) * 6.0f;
        return p;
    }

    private static int brightenColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        r = Math.min(255, (int) (r * 1.3));
        g = Math.min(255, (int) (g * 1.3));
        b = Math.min(255, (int) (b * 1.3));

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static boolean parseAndMatchQuery(ItemStack stack, String query) {
        if (stack.isEmpty()) return false;
        query = query.trim().toLowerCase();
        if (query.isEmpty()) return true;
        return stack.getHoverName().getString().toLowerCase().contains(query);
    }

    public static int getTextColorForBackground(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.65 ? 0xFF373737 : 0xFFFFFFFF;
    }
}

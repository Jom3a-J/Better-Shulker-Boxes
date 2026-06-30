package com.bettershulker.client.render;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;

import net.minecraft.client.Minecraft;
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

import java.util.List;

/**
 * Easy Shulker Boxes-inspired tooltip renderer.
 *
 * The base panel is sampled from vanilla container textures so resource packs that recolor
 * shulker/container GUIs can affect the preview. Better Shulker themes are applied as
 * accent/tint/highlight layers instead of replacing the resource-pack look.
 */
public class ShulkerTooltipComponent implements ClientTooltipComponent {

    private static final int SLOT_SIZE = 18;
    private static final int COMPACT_SLOT_SIZE = 20;
    private static final int COMPACT_ITEM_OFFSET = 2;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 3;
    private static final int COMPACT_MAX_SLOTS = 5;
    private static final int SLOT_COUNT = GRID_COLS * GRID_ROWS;

    /** Vanilla shulker/container textures are 176px wide. We crop the storage slot band. */
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 68;
    private static final int RESOURCE_PACK_PANEL_HEIGHT = 77;
    private static final int SLOT_START_X = 8;
    private static final int SLOT_START_Y = 7;
    private static final int RESOURCE_PACK_SLOT_START_Y = 18;
    private static final int RESOURCE_PACK_SLOT_AREA_BOTTOM = RESOURCE_PACK_SLOT_START_Y + GRID_ROWS * SLOT_SIZE;
    private static final int RESOURCE_PACK_BOTTOM_CAP_HEIGHT = RESOURCE_PACK_PANEL_HEIGHT - RESOURCE_PACK_SLOT_AREA_BOTTOM;
    private static final int TOOLTIP_BOTTOM_PADDING = 6;
    private static final int COMPACT_SLOT_START_X = 7;
    private static final int COMPACT_SLOT_START_Y = 7;
    private static final int COMPACT_OUTSIDE_TOOLTIP_Y_OFFSET = 0;
    private static final int COMPACT_HINT_HEIGHT = 13;
    private static final int NAME_BADGE_HEIGHT = 14;
    private static final int NAME_BADGE_OVERLAP = 4;

    private static final Identifier SHULKER_PANEL_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
    private static final Identifier GENERIC_PANEL_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

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
    private final boolean resourcePackOverridesPanel;
    private final List<Integer> displaySlots;
    private final int displayCols;
    private final int displayRows;
    private final int panelWidth;
    private final int panelHeight;

    private final int borderColor;
    private final int tintColor;
    private final int textColor;
    private final int badgeBgColor;
    private final int selectionColor;
    private final int multiSelectColor;
    private final int matchColor;
    private final int panelShadowColor;

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
        this.resourcePackOverridesPanel = hasResourcePackOverride(getPanelTexture())
                || getPackShulkerPanelTexture() != null;
        this.displaySlots = buildDisplaySlots();
        this.displayCols = this.compactMode
                ? Math.min(COMPACT_MAX_SLOTS, Math.max(1, this.displaySlots.size()))
                : GRID_COLS;
        this.displayRows = this.compactMode
                ? Math.max(1, (this.displaySlots.size() + this.displayCols - 1) / this.displayCols)
                : GRID_ROWS;
        int compactCellSize = this.resourcePackOverridesPanel ? SLOT_SIZE : COMPACT_SLOT_SIZE;
        this.panelWidth = this.compactMode
                ? (this.isContainerEmpty ? 0 : 14 + (this.displayCols * compactCellSize))
                : PANEL_WIDTH;
        this.panelHeight = this.compactMode
                ? (this.isContainerEmpty ? 0 : (this.resourcePackOverridesPanel
                        ? RESOURCE_PACK_SLOT_START_Y + (this.displayRows * compactCellSize) + RESOURCE_PACK_BOTTOM_CAP_HEIGHT
                        : 14 + (this.displayRows * compactCellSize)))
                : (this.resourcePackOverridesPanel ? RESOURCE_PACK_PANEL_HEIGHT : PANEL_HEIGHT);

        ThemePalette palette = buildThemePalette();
        this.borderColor = palette.borderColor;
        this.tintColor = palette.tintColor;
        this.textColor = palette.textColor;
        this.badgeBgColor = palette.badgeBgColor;
        this.selectionColor = palette.selectionColor;
        this.multiSelectColor = palette.multiSelectColor;
        this.matchColor = palette.matchColor;
        this.panelShadowColor = palette.panelShadowColor;
    }

    private List<Integer> buildDisplaySlots() {
        List<Integer> slots = new java.util.ArrayList<>();
        if (this.compactMode && this.isContainerEmpty) {
            return slots;
        } else if (this.compactMode) {
            for (int i = 0; i < this.contents.size() && i < SLOT_COUNT; i++) {
                ItemStack stack = this.contents.get(i);
                if (stack.isEmpty()) continue;
                boolean seen = false;
                for (int existing : slots) {
                    if (ItemStack.isSameItemSameComponents(this.contents.get(existing), stack)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) slots.add(i);
            }
            slots.sort((a, b) -> {
                int countCompare = Integer.compare(countMergedItemsForSlot(b), countMergedItemsForSlot(a));
                return countCompare != 0 ? countCompare : Integer.compare(a, b);
            });
            if (slots.size() > COMPACT_MAX_SLOTS) {
                slots = new java.util.ArrayList<>(slots.subList(0, COMPACT_MAX_SLOTS));
            }
        } else {
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots.add(i);
            }
        }
        return slots;
    }

    private int countMergedItemsForSlot(int actualSlot) {
        if (actualSlot < 0 || actualSlot >= this.contents.size()) return 0;
        ItemStack displayStack = this.contents.get(actualSlot);
        if (displayStack.isEmpty()) return 0;
        int total = 0;
        for (ItemStack stack : this.contents) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(displayStack, stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public int getHeight(Font textRenderer) {
        int searchHeight = 0;
        int sortHeight = 0;
        if (this.compactMode) {
            return getPanelHeight() + COMPACT_HINT_HEIGHT + searchHeight + sortHeight;
        }
        // The selected-item tab is drawn above the component like Easy Shulker Boxes' selected
        // tooltip, so it must not increase the component height or the vanilla tooltip background
        // will stretch downward whenever a selected name appears.
        int targetHeight = getPanelHeight() + TOOLTIP_BOTTOM_PADDING + searchHeight + sortHeight;

        return targetHeight;
    }

    @Override
    public int getWidth(Font textRenderer) {
        if (this.compactMode) {
            return Math.max(getPanelWidth(), textRenderer.width(getCompactFullHintText()) + 12);
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
        int panelX = this.compactMode && getPanelWidth() > 0 ? tooltipX + (width - getPanelWidth()) / 2 : tooltipX + (width - getPanelWidth()) / 2;
        int panelY = this.compactMode ? tooltipY + COMPACT_OUTSIDE_TOOLTIP_Y_OFFSET : tooltipY;
        long now = System.currentTimeMillis();

        int searchHeight = 0;
        boolean showSortBar = false;

        boolean resourcePackMode = this.resourcePackOverridesPanel;
        boolean hasCompactPreview = this.compactMode && !this.isContainerEmpty && !this.displaySlots.isEmpty();

        if (!resourcePackMode && (!this.compactMode || hasCompactPreview)) {
            drawPanelAura(context, panelX, panelY, getPanelWidth(), getPanelHeight(), now);
        }
        if (this.compactMode) {
            if (hasCompactPreview) {
                drawCompactPanel(textRenderer, context, panelX, panelY);
            }
        } else if (isGlassTheme() && !resourcePackMode) {
            drawGlassPanel(context, panelX, panelY);
        } else {
            drawResourcePackPanel(context, panelX, panelY);
        }
        if (!this.compactMode) {
            drawThemeOverlay(context, panelX, panelY);
        }

        int hoveredSlot = updateHoveredSlot(panelX, panelY);
        if (!this.compactMode || hasCompactPreview) {
            drawItemsAndSlotOverlays(textRenderer, context, panelX, panelY, hoveredSlot, now);
            drawSelectedSlot(context, panelX, panelY, now);
            drawMultiSelectedSlots(context, panelX, panelY);
            drawHoveredAndSelectedTooltips(textRenderer, context, hoveredSlot);
            drawSelectedNameBadge(textRenderer, context, panelX, panelY);
        }

        int extraY = panelY + getPanelHeight() + (this.compactMode ? 0 : TOOLTIP_BOTTOM_PADDING);
        if (this.compactMode) {
            drawCompactFullHint(textRenderer, context, tooltipX, extraY, width);
            extraY += COMPACT_HINT_HEIGHT;
        }
        if (showSortBar) {
            drawSortBar(textRenderer, context, panelX, extraY, now);
            extraY += 15;
        }
        if (searchHeight > 0) {
            drawSearchBar(textRenderer, context, panelX, extraY);
        }

        // Tiny theme-colored fill strip at the bottom. Hide it in resource-pack mode so the
        // resource pack owns the panel pixels without extra light lines/artifacts.
        if (!resourcePackMode && !this.compactMode) {
            drawFillStrip(context, panelX, panelY + getPanelHeight() - 2);
        }
    }

    private void drawResourcePackPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        // Resource-pack mode should use the real panel texture for this container, not a fake
        // tinted generic panel. For shulkers this preserves the pack's actual shulker color/style.
        Identifier texture = getPanelTexture();
        Identifier coloredPackTexture = getPackShulkerPanelTexture();
        // Recolourful/OptiGUI packs provide real per-dye shulker GUI textures. Use the exact
        // texture only when it actually exists; otherwise fall back safely so colored shulkers do
        // not render Minecraft's missing-texture magenta/black block.
        boolean usingExactPackColor = this.resourcePackOverridesPanel && coloredPackTexture != null;
        if (usingExactPackColor) {
            texture = coloredPackTexture;
        }

        // In resource-pack mode draw from the real top of the shulker GUI so the colored header
        // is not cut off. Keep the last two rows cropped because this pack puts a stray bright line
        // there when the full GUI is compressed into a compact tooltip.
        int drawHeight = getPanelHeight();
        float textureV = this.resourcePackOverridesPanel ? 0.0F : PANEL_TEXTURE_V;
        int renderColor = usingExactPackColor ? 0xFFFFFFFF : getPanelRenderColor();
        context.blit(RenderPipelines.GUI_TEXTURED,
                texture,
                panelX,
                panelY,
                PANEL_TEXTURE_U,
                textureV,
                PANEL_WIDTH,
                drawHeight,
                256,
                256,
                renderColor);
    }

    private void drawCompactPanel(Font font, GuiGraphicsExtractor context, int panelX, int panelY) {
        if (this.resourcePackOverridesPanel) {
            drawResourcePackCompactPanel(context, panelX, panelY);
            return;
        }

        int w = getPanelWidth();
        int h = getPanelHeight();
        int compactBase = getCompactPanelBaseColor();
        boolean translucentGlass = BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.GLASS;
        int bg = translucentGlass ? withAlpha(compactBase, 170) : blendColor(compactBase, 0xFF000000, 0.16f);
        int face = translucentGlass ? withAlpha(blendColor(compactBase, 0xFFFFFFFF, 0.18f), 92) : blendColor(compactBase, 0xFFFFFFFF, 0.10f);
        int edge = withAlpha(this.borderColor, 245);
        int light = withAlpha(blendColor(this.borderColor, 0xFFFFFFFF, 0.50f), 120);
        int shadow = withAlpha(blendColor(this.borderColor, 0xFF000000, 0.55f), 170);

        // Shulker Box Tooltip-style compact frame: just a colored border and merged item slots.
        // No summary/header/fill bar inside the preview; item counts live on the merged stacks.
        context.fill(panelX, panelY, panelX + w, panelY + h, bg);
        context.fill(panelX + 2, panelY + 2, panelX + w - 2, panelY + h - 2, face);
        context.fill(panelX, panelY, panelX + w, panelY + 1, light);
        context.fill(panelX, panelY + 1, panelX + 1, panelY + h, light);
        context.fill(panelX, panelY + h - 1, panelX + w, panelY + h, shadow);
        context.fill(panelX + w - 1, panelY, panelX + w, panelY + h, shadow);
        drawRectFrame(context, panelX + 1, panelY + 1, w - 2, h - 2, edge);

        for (int displayPos = 0; displayPos < this.displaySlots.size(); displayPos++) {
            int slotX = getSlotX(panelX, displayPos);
            int slotY = getSlotY(panelY, displayPos);
            drawCompactSlotBackground(context, slotX, slotY, compactBase);
        }
    }

    private void drawResourcePackCompactPanel(GuiGraphicsExtractor context, int panelX, int panelY) {
        Identifier texture = getPanelTexture();
        Identifier coloredPackTexture = getPackShulkerPanelTexture();
        boolean usingExactPackColor = coloredPackTexture != null;
        if (usingExactPackColor) {
            texture = coloredPackTexture;
        }

        int renderColor = usingExactPackColor ? 0xFFFFFFFF : getPanelRenderColor();

        // Recompose the resource-pack shulker panel instead of simply cropping the top-left.
        // A raw crop cuts the right/bottom borders in the middle of the full 9x3 GUI.  This keeps
        // the pack's real left edge, first N slot columns, and real right/bottom caps.
        int leftW = SLOT_START_X;
        int rightSourceX = SLOT_START_X + GRID_COLS * SLOT_SIZE;
        int rightW = PANEL_WIDTH - rightSourceX;
        int slotsW = this.displayCols * SLOT_SIZE;
        int topH = RESOURCE_PACK_SLOT_START_Y + this.displayRows * SLOT_SIZE;
        int bottomH = Math.max(0, getPanelHeight() - topH);
        int bottomSourceY = RESOURCE_PACK_SLOT_AREA_BOTTOM;

        blitResourcePackSlice(context, texture, panelX, panelY,
                0, 0, leftW, topH, renderColor);
        blitResourcePackSlice(context, texture, panelX + leftW, panelY,
                SLOT_START_X, 0, slotsW, topH, renderColor);
        blitResourcePackSlice(context, texture, panelX + leftW + slotsW, panelY,
                rightSourceX, 0, rightW, topH, renderColor);

        if (bottomH > 0) {
            int bottomY = panelY + topH;
            blitResourcePackSlice(context, texture, panelX, bottomY,
                    0, bottomSourceY, leftW, bottomH, renderColor);
            blitResourcePackSlice(context, texture, panelX + leftW, bottomY,
                    SLOT_START_X, bottomSourceY, slotsW, bottomH, renderColor);
            blitResourcePackSlice(context, texture, panelX + leftW + slotsW, bottomY,
                    rightSourceX, bottomSourceY, rightW, bottomH, renderColor);
        }
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
                256,
                256,
                renderColor);
    }

    private int getCompactPanelBaseColor() {
        return switch (BetterShulkerConfig.getTooltipTheme()) {
            case ORIGINAL -> {
                if (this.isEnderChest) {
                    yield 0xFF123A2A;
                }
                if (this.color != null) {
                    yield blendColor(0xFF000000 | this.color.getTextureDiffuseColor(), 0xFF000000, 0.18f);                }
                // Undyed/default shulker boxes are purple. The normal tooltip panel was becoming
                // near-black in compact mode because the original palette used translucent dark
                // overlay colors; compact mode needs an opaque base color like ShulkerBoxTooltip.
                yield 0xFF6F2D8F;
            }
            case CLASSIC -> 0xFF2D4A1A;
            case RETRO -> 0xFF1A0028;
            case SOLARIZED_DARK -> 0xFF002B36;
            case SOLARIZED_LIGHT -> 0xFFFDF6E3;
            case HIGH_CONTRAST -> 0xFF000000;
            case CUSTOM -> opaqueOrDefault(BetterShulkerConfig.getCustomBackgroundColor(), 0xFF1A1A1A);
            case GLASS -> 0xFFEAF7FF;
        };
    }

    private void drawCompactSlotBackground(GuiGraphicsExtractor context, int slotX, int slotY, int baseColor) {
        boolean lightBase = getTextColorForBackground(baseColor) == 0xFF373737;
        int outer = withAlpha(blendColor(this.borderColor, baseColor, 0.35f), 210);
        int inner = lightBase
                ? withAlpha(blendColor(baseColor, 0xFFFFFFFF, 0.12f), 238)
                : withAlpha(blendColor(baseColor, 0xFF000000, 0.50f), 238);
        int high = lightBase ? 0x80FFFFFF : 0x45FFFFFF;
        int low = lightBase ? 0x44000000 : 0x70000000;
        int size = COMPACT_SLOT_SIZE;
        context.fill(slotX, slotY, slotX + size, slotY + size, outer);
        context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1, inner);
        context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + 2, high);
        context.fill(slotX + 1, slotY + 2, slotX + 2, slotY + size - 1, high);
        context.fill(slotX + 1, slotY + size - 2, slotX + size - 1, slotY + size - 1, low);
        context.fill(slotX + size - 2, slotY + 2, slotX + size - 1, slotY + size - 1, low);
    }

    private String getCompactFullHintText() {
        String keyName = "V";
        if (BetterShulkerClient.getShowFullTooltipKey() != null) {
            try {
                keyName = BetterShulkerClient.getShowFullTooltipKey().getTranslatedKeyMessage().getString();
            } catch (Exception ignored) {
                keyName = "V";
            }
        }
        return keyName + ": View full contents";
    }

    private void drawCompactFullHint(Font font, GuiGraphicsExtractor context, int x, int y, int width) {
        String hint = getCompactFullHintText();
        int textX = x + Math.max(4, (width - font.width(hint)) / 2);
        int textY = y + 3;
        context.text(font, Component.literal(hint), textX + 1, textY + 1, 0xAA000000);
        context.text(font, Component.literal(hint), textX, textY, withAlpha(this.textColor, 210));
    }

    private int getPanelWidth() {
        return this.panelWidth;
    }

    private int getPanelHeight() {
        return this.panelHeight;
    }

    private int getPanelRenderColor() {
        if (!this.resourcePackOverridesPanel || this.isEnderChest || this.color == null) {
            return 0xFFFFFFFF;
        }
        // Fallback only for packs that override the normal shulker GUI but do not provide the
        // OptiFine/Recolourful per-color GUI files.
        return 0xFF000000 | this.color.getTextureDiffuseColor();
    }

    private Identifier getPackShulkerPanelTexture() {
        if (this.isEnderChest || this.color == null) return null;

        String colorName = this.color.getName();
        Identifier[] candidates = new Identifier[] {
                Identifier.withDefaultNamespace("optifine/gui/container/shulker_box/" + colorName + ".png"),
                Identifier.withDefaultNamespace("textures/gui/container/shulker_box/" + colorName + ".png"),
                Identifier.withDefaultNamespace("textures/gui/container/shulker_box/" + colorName + "_shulker_box.png")
        };
        for (Identifier candidate : candidates) {
            if (hasResource(candidate)) return candidate;
        }

        try {
            var resources = Minecraft.getInstance().getResourceManager().listResources(
                    "optifine/gui/container/shulker_box",
                    id -> id.getPath().endsWith("/" + colorName + ".png"));
            if (!resources.isEmpty()) {
                return resources.keySet().iterator().next();
            }
        } catch (Exception ignored) {
            // Fall through to safe generic shulker texture fallback.
        }
        return null;
    }

    private static boolean hasResource(Identifier texture) {
        if (texture == null) return false;
        try {
            return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
        } catch (Exception ignored) {
            return false;
        }
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

    private Identifier getPanelTexture() {
        return this.isEnderChest ? GENERIC_PANEL_TEXTURE : SHULKER_PANEL_TEXTURE;
    }

    private static boolean hasResourcePackOverride(Identifier texture) {
        try {
            return Minecraft.getInstance().getResourceManager().getResourceStack(texture).size() > 1;
        } catch (Exception ignored) {
            return false;
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

    private void drawShineAnimation(GuiGraphicsExtractor context, int x, int y, long now) {
        if (!BetterShulkerConfig.hoverAnimationsEnabled) return;

        float progress = (now % 3600L) / 3600.0f;
        float sweepX = x - 48 + progress * (PANEL_WIDTH + 96);
        int sheenColor = 0x20FFFFFF;
        int hotColor = withAlpha(blendColor(this.borderColor, 0xFFFFFFFF, 0.55f), 34);

        for (int px = x + 3; px < x + PANEL_WIDTH - 3; px++) {
            int center = y + 4 + (int) (px - sweepX);
            int y1 = Math.max(y + 3, center - 7);
            int y2 = Math.min(y + PANEL_HEIGHT - 3, center + 7);
            if (y1 < y2) {
                context.fill(px, y1, px + 1, y2, sheenColor);
            }

            int y3 = Math.max(y + 3, center - 2);
            int y4 = Math.min(y + PANEL_HEIGHT - 3, center + 2);
            if (y3 < y4) {
                context.fill(px, y3, px + 1, y4, hotColor);
            }
        }
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
        ItemStack filterStack = BetterShulkerClient.getFilterItemStack();
        boolean isFiltering = !filterStack.isEmpty();
        boolean isSearching = false;
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

                if (hoverZoom) {
                    drawHoverGlow(context, slotX, slotY, now);
                }

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

                int itemX = slotX + (this.compactMode ? getCompactItemOffset() : 1);
                int itemY = slotY + (this.compactMode ? getCompactItemOffset() : 1);
                context.item(stack, itemX, itemY);
                if (this.compactMode) {
                    int totalCount = getGroupedItemCount(i);
                    if (totalCount > 1) {
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

            boolean matches = true;
            if (isFiltering) {
                matches = !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, filterStack);
            }
            if (isFiltering || isSearching) {
                if (matches) {
                    drawSlotOutline(context, slotX, slotY, this.matchColor, true);
                } else {
                    int size = getRenderedSlotSize();
                    context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1, 0xB0000000);
                }
            }

            if (displayPos == animatedDisplay && BetterShulkerConfig.hoverAnimationsEnabled) {
                int hover = withAlpha(this.selectionColor, 150 + (int) (70 * Math.sin(now / 140.0)));
                drawSlotOutline(context, slotX, slotY, hover, true);
            }
        }
    }

    private void drawHoverGlow(GuiGraphicsExtractor context, int slotX, int slotY, long now) {
        int pulse = 155 + (int) (70 * (Math.sin(now / 120.0) * 0.5f + 0.5f));
        int hot = withAlpha(this.selectionColor, pulse);
        int soft = withAlpha(this.selectionColor, Math.max(45, pulse / 3));
        int size = getRenderedSlotSize();
        context.fill(slotX - 3, slotY - 3, slotX + size + 3, slotY - 2, soft);
        context.fill(slotX - 3, slotY + size + 2, slotX + size + 3, slotY + size + 3, soft);
        context.fill(slotX - 3, slotY - 2, slotX - 2, slotY + size + 2, soft);
        context.fill(slotX + size + 2, slotY - 2, slotX + size + 3, slotY + size + 2, soft);
        context.fill(slotX - 1, slotY - 1, slotX + size + 1, slotY, hot);
        context.fill(slotX - 1, slotY + size, slotX + size + 1, slotY + size + 1, hot);
        context.fill(slotX - 1, slotY, slotX, slotY + size, hot);
        context.fill(slotX + size, slotY, slotX + size + 1, slotY + size, hot);
    }

    private int getGroupedItemCount(int actualSlot) {
        if (actualSlot < 0 || actualSlot >= this.contents.size()) return 0;
        ItemStack displayStack = this.contents.get(actualSlot);
        if (displayStack.isEmpty()) return 0;
        int total = 0;
        if (this.compactMode) {
            for (ItemStack stack : this.contents) {
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(displayStack, stack)) {
                    total += stack.getCount();
                }
            }
            return total;
        }
        return displayStack.getCount();
    }

    private void drawCompactItemCount(Font font, GuiGraphicsExtractor context, int itemX, int itemY, int count) {
        String text = count > 999 ? "999+" : String.valueOf(count);
        int x = itemX + 16 - font.width(text);
        int y = itemY + 9;
        context.text(font, Component.literal(text), x + 1, y + 1, 0xFF000000);
        context.text(font, Component.literal(text), x, y, 0xFFFFFFFF);
    }

    private void drawSelectedSlot(GuiGraphicsExtractor context, int panelX, int panelY, long now) {
        if (!BetterShulkerConfig.secondaryTooltipEnabled) return;
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (selected < 0 || selected >= SLOT_COUNT) return;

        int displayIndex = getDisplayIndexForSlot(selected);
        if (displayIndex < 0) return;
        int cols = this.compactMode ? this.displayCols : GRID_COLS;
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

        int startX = this.compactMode ? (this.resourcePackOverridesPanel ? SLOT_START_X : COMPACT_SLOT_START_X) : SLOT_START_X;
        int cellSize = getRenderedSlotSize();
        int slotX = panelX + startX + Math.round(renderCol * cellSize);
        int slotY = panelY + getSlotStartY() + Math.round(renderRow * cellSize);
        int pulse = withAlpha(this.selectionColor, 185 + (int) (45 * Math.sin(now / 150.0)));
        drawSlotOutline(context, slotX, slotY, pulse, true);
        int size = getRenderedSlotSize();
        context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1,
                withAlpha(this.selectionColor, 34 + (int) (12 * Math.sin(now / 160.0))));
    }

    private void drawMultiSelectedSlots(GuiGraphicsExtractor context, int panelX, int panelY) {
        for (int i : BetterShulkerClient.getSelectedSlotsSet()) {
            if (i < 0 || i >= SLOT_COUNT) continue;
            int displayIndex = getDisplayIndexForSlot(i);
            if (displayIndex < 0) continue;
            int slotX = getSlotX(panelX, displayIndex);
            int slotY = getSlotY(panelY, displayIndex);
            drawSlotOutline(context, slotX, slotY, this.multiSelectColor, true);
            int size = getRenderedSlotSize();
            context.fill(slotX + 1, slotY + 1, slotX + size - 1, slotY + size - 1, withAlpha(this.multiSelectColor, 38));
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
                context.setTooltipForNextFrame(font, List.of(hoveredStack.getHoverName()), java.util.Optional.empty(), mouseX, mouseY);
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
                            java.util.Optional.empty(),
                            mouseX - tooltipWidth - 12,
                            mouseY - 10);
                }
            }
        }
    }

    private void drawSelectedNameBadge(Font font, GuiGraphicsExtractor context, int panelX, int panelY) {
        if (!BetterShulkerConfig.selectedItemNameEnabled) return;
        if (!this.compactMode && !hasSelectedNameBadge()) return;

        ItemStack selectedStack = this.compactMode
                ? getCompactNameStack()
                : getSelectedNameStack();
        if (selectedStack.isEmpty()) return;

        int nameColor = this.resourcePackOverridesPanel ? 0xFFFFFF : getReadableThemeNameColor();
        if (this.compactMode) {
            drawCompactSelectedNameTooltip(font, context, panelX, panelY, selectedStack, nameColor);
        } else {
            // Use the Easy Shulker Boxes approach for both vanilla/theme and resource-pack modes:
            // draw the selected name as a separate tooltip above the preview. This avoids the broken
            // custom tab path and prevents the main tooltip background from stretching.
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
        int panelWidth = getPanelWidth();
        String displayName = fitText(font, selectedStack.getHoverName().getString(), panelWidth - 22);
        Component name = Component.literal(displayName).withStyle(style -> style.withColor(nameColor & 0xFFFFFF));
        ClientTooltipComponent selectedNameTooltip = ClientTooltipComponent.create(name.getVisualOrderText());
        int textWidth = font.width(name.getVisualOrderText());

        int tooltipAnchorX = panelX + panelWidth / 2 - 12 - textWidth / 2;
        int tooltipAnchorY = panelY - 15;
        int bridgeWidth = Math.min(panelWidth - 24, textWidth + 12);
        int bridgeX = panelX + (panelWidth - bridgeWidth) / 2;
        int bridgeColor = this.resourcePackOverridesPanel ? 0x55000000 : withAlpha(this.borderColor, 110);
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

    private void drawSortBar(Font font, GuiGraphicsExtractor context, int x, int y, long now) {
        long timeSinceSort = System.currentTimeMillis() - BetterShulkerClient.getLastSortTime();
        float fade = 1.0f;
        if (timeSinceSort > 1500) {
            fade = Math.max(0.0f, 1.0f - (timeSinceSort - 1500) / 500.0f);
        }
        int alpha = (int) (fade * 255);
        int bg = ((int) (alpha * 0.45f) << 24) | (this.panelShadowColor & 0x00FFFFFF);
        int border = ((int) (alpha * 0.75f) << 24) | (this.borderColor & 0x00FFFFFF);
        drawHudBar(context, x, y, bg, border);

        int slide = (int) ((1.0f - Math.min(1.0f, timeSinceSort / 250.0f)) * -6.0f);
        int textX = x + 7 + slide;
        int textY = y + 3;
        context.text(font, Component.literal("⇅ Sort: "), textX, textY, (alpha << 24) | (this.selectionColor & 0x00FFFFFF));
        int labelWidth = font.width("⇅ Sort: ");
        context.text(font, Component.literal(BetterShulkerClient.getCurrentSortMode().getDisplayName()), textX + labelWidth, textY,
                (alpha << 24) | (this.textColor & 0x00FFFFFF));
    }

    private void drawSearchBar(Font font, GuiGraphicsExtractor context, int x, int y) {
        int bg = 0x66000000;
        int border = BetterShulkerClient.isSearchFocused() ? this.selectionColor : withAlpha(this.borderColor, 150);
        drawHudBar(context, x, y, bg, border);

        String query = BetterShulkerClient.getSearchQuery();
        String cursor = BetterShulkerClient.isSearchFocused() && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
        int textX = x + 7;
        int textY = y + 3;
        context.text(font, Component.literal("Search: "), textX, textY, this.selectionColor);
        int labelWidth = font.width("Search: ");
        context.text(font, Component.literal(query + cursor), textX + labelWidth, textY, this.textColor);
    }

    private void drawHudBar(GuiGraphicsExtractor context, int x, int y, int bg, int border) {
        int w = getPanelWidth();        context.fill(x + 3, y + 1, x + w - 3, y + 14, bg);
        drawRectFrame(context, x + 3, y + 1, w - 6, 13, border);
    }

    private void drawFillStrip(GuiGraphicsExtractor context, int x, int y) {
        int occupied = 0;
        for (ItemStack stack : this.contents) {
            if (!stack.isEmpty()) occupied++;
        }
        int barW = getPanelWidth() - 16;        int barX = x + 8;
        context.fill(barX, y, barX + barW, y + 1, 0x66000000);
        if (occupied > 0) {
            int fill = Math.round((occupied / 27.0f) * barW);
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

    private void drawSlotOutline(GuiGraphicsExtractor context, int x, int y, int color, boolean doubleLine) {
        int size = getRenderedSlotSize();
        context.fill(x, y, x + size, y + 1, color);
        context.fill(x, y + size - 1, x + size, y + size, color);
        context.fill(x, y + 1, x + 1, y + size - 1, color);
        context.fill(x + size - 1, y + 1, x + size, y + size - 1, color);
        if (doubleLine) {
            int inner = withAlpha(color, Math.min(255, ((color >>> 24) & 0xFF) / 2));
            context.fill(x + 1, y + 1, x + size - 1, y + 2, inner);
            context.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, inner);
            context.fill(x + 1, y + 2, x + 2, y + size - 2, inner);
            context.fill(x + size - 2, y + 2, x + size - 1, y + size - 2, inner);
        }
    }

    private void drawRectFrame(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private int getRenderedSlotSize() {
        if (!this.compactMode) return SLOT_SIZE;
        return this.resourcePackOverridesPanel ? SLOT_SIZE : COMPACT_SLOT_SIZE;
    }

    private int getCompactItemOffset() {
        return this.resourcePackOverridesPanel ? 1 : COMPACT_ITEM_OFFSET;
    }

    private int getSlotX(int panelX, int slot) {
        int cols = this.compactMode ? this.displayCols : GRID_COLS;
        int startX = this.compactMode ? (this.resourcePackOverridesPanel ? SLOT_START_X : COMPACT_SLOT_START_X) : SLOT_START_X;
        return panelX + startX + (slot % cols) * getRenderedSlotSize();
    }

    private int getSlotY(int panelY, int slot) {
        int cols = this.compactMode ? this.displayCols : GRID_COLS;
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

    private int getSlotStartY() {
        if (this.compactMode) return this.resourcePackOverridesPanel ? RESOURCE_PACK_SLOT_START_Y : COMPACT_SLOT_START_Y;
        return this.resourcePackOverridesPanel ? RESOURCE_PACK_SLOT_START_Y : SLOT_START_Y;
    }


    private boolean isGlassTheme() {
        return BetterShulkerConfig.getTooltipTheme() == BetterShulkerConfig.TooltipTheme.GLASS;
    }

    private ThemePalette buildThemePalette() {
        int baseBorder;
        int baseTint;
        int baseText = 0xFFFFFFFF;
        int badgeBg;
        int select;
        int multi = 0xFF55FFFF;
        int match = 0xFF55FF55;
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

        switch (BetterShulkerConfig.getTooltipTheme()) {
            case ORIGINAL -> {
                // Keep container/ender derived defaults.
            }
            case CLASSIC -> {
                baseBorder = 0xFF4A7A25;
                baseTint = 0x702D4A1A;
                badgeBg = 0xE02D4A1A;
                baseText = 0xFFD4E8C0;
                select = 0xFFA7E060;
                match = 0xFF7DFF6A;
            }
            case RETRO -> {
                baseBorder = 0xFFFF00FF;
                baseTint = 0x70080812;
                badgeBg = 0xE0080812;
                baseText = 0xFFFF66FF;
                select = 0xFF00FFFF;
                multi = 0xFFFF66FF;
                match = 0xFF39FF14;
            }
            case SOLARIZED_DARK -> {
                baseBorder = 0xFF268BD2;
                baseTint = 0x76002B36;
                badgeBg = 0xE0002B36;
                baseText = 0xFF93A1A1;
                select = 0xFFB58900;
            }
            case SOLARIZED_LIGHT -> {
                baseBorder = 0xFF268BD2;
                baseTint = 0x88FDF6E3;
                badgeBg = 0xEEFDF6E3;
                baseText = 0xFF586E75;
                select = 0xFFCB4B16;
                shadow = 0xFFEFE6C8;
            }
            case HIGH_CONTRAST -> {
                baseBorder = 0xFFFFAA00;
                baseTint = 0x88000000;
                badgeBg = 0xF0000000;
                baseText = 0xFFFFFF00;
                select = 0xFFFFFF00;
                multi = 0xFFFFFFFF;
                match = 0xFF00FF00;
            }
            case CUSTOM -> {
                baseBorder = BetterShulkerConfig.getCustomBorderColor();
                baseTint = normalizeOverlayAlpha(BetterShulkerConfig.getCustomBackgroundColor(), 112);
                badgeBg = BetterShulkerConfig.getCustomNameBgColor();
                baseText = BetterShulkerConfig.getCustomNameTextColor();
                select = BetterShulkerConfig.getCustomSelectionSquareColor();
                multi = blendColor(select, 0xFF55FFFF, 0.45f);
                match = blendColor(select, 0xFF55FF55, 0.45f);
            }
            case GLASS -> {
                baseBorder = 0xB8FFFFFF;
                baseTint = 0x32FFFFFF;
                badgeBg = 0xA8FFFFFF;
                baseText = 0xFF2A2A2A;
                select = 0xFFFFD700;
                multi = 0xFF8EEBFF;
                match = 0xFFA0FFA0;
                shadow = 0x80FFFFFF;
            }
        }

        return new ThemePalette(baseBorder, baseTint, baseText, badgeBg, select, multi, match, shadow);
    }

    private static int normalizeOverlayAlpha(int color, int fallbackAlpha) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0 || alpha == 255) alpha = fallbackAlpha;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int opaqueOrDefault(int color, int fallback) {
        if ((color & 0x00FFFFFF) == 0 && ((color >>> 24) & 0xFF) == 0) return fallback;
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
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

    private record ThemePalette(
            int borderColor,
            int tintColor,
            int textColor,
            int badgeBgColor,
            int selectionColor,
            int multiSelectColor,
            int matchColor,
            int panelShadowColor
    ) {}
}

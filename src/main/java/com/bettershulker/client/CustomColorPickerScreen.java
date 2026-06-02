package com.bettershulker.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import com.bettershulker.BetterShulkerConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Color picker screen for the CUSTOM theme. Allows editing background and border colors via RGB sliders
 * with a real-time live tooltip preview and tabbed controls for main tooltip and name badge.
 */
public class CustomColorPickerScreen extends Screen {
    private final Screen parent;
    private final int[] bgColor = new int[3]; // R,G,B
    private final int[] borderColor = new int[3];
    private final int[] nameBgColor = new int[3]; // R,G,B
    private final int[] nameBorderColor = new int[3]; // R,G,B
    private final int[] selSquareColor = new int[3]; // R,G,B

    private int activeTab = 0; // 0 = Main Tooltip, 1 = Name Badge, 2 = Selection Square

    public CustomColorPickerScreen(Screen parent) {
        super(Component.literal("Custom Theme Colors"));
        this.parent = parent;

        // Load current colors from config (ARGB -> ignore alpha)
        int bg = BetterShulkerConfig.getCustomBackgroundColor();
        bgColor[0] = (bg >> 16) & 0xFF;
        bgColor[1] = (bg >> 8) & 0xFF;
        bgColor[2] = bg & 0xFF;

        int br = BetterShulkerConfig.getCustomBorderColor();
        borderColor[0] = (br >> 16) & 0xFF;
        borderColor[1] = (br >> 8) & 0xFF;
        borderColor[2] = br & 0xFF;

        int nBg = BetterShulkerConfig.getCustomNameBgColor();
        nameBgColor[0] = (nBg >> 16) & 0xFF;
        nameBgColor[1] = (nBg >> 8) & 0xFF;
        nameBgColor[2] = nBg & 0xFF;

        int nBr = BetterShulkerConfig.getCustomNameBorderColor();
        nameBorderColor[0] = (nBr >> 16) & 0xFF;
        nameBorderColor[1] = (nBr >> 8) & 0xFF;
        nameBorderColor[2] = nBr & 0xFF;

        int sel = BetterShulkerConfig.getCustomSelectionSquareColor();
        selSquareColor[0] = (sel >> 16) & 0xFF;
        selSquareColor[1] = (sel >> 8) & 0xFF;
        selSquareColor[2] = sel & 0xFF;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int startY = this.height / 2 - 70;
        int sliderWidth = 150, sliderHeight = 20;
        int leftX = cx - 160;

        // 1. Tab buttons
        Button tab0Btn = Button.builder(Component.literal("Tooltip"), btn -> {
            activeTab = 0;
            this.clearWidgets();
            this.init();
        }).bounds(leftX, startY - 26, 48, 20).build();
        tab0Btn.active = (activeTab != 0);

        Button tab1Btn = Button.builder(Component.literal("Badge"), btn -> {
            activeTab = 1;
            this.clearWidgets();
            this.init();
        }).bounds(leftX + 51, startY - 26, 48, 20).build();
        tab1Btn.active = (activeTab != 1);

        Button tab2Btn = Button.builder(Component.literal("Square"), btn -> {
            activeTab = 2;
            this.clearWidgets();
            this.init();
        }).bounds(leftX + 102, startY - 26, 48, 20).build();
        tab2Btn.active = (activeTab != 2);

        this.addRenderableWidget(tab0Btn);
        this.addRenderableWidget(tab1Btn);
        this.addRenderableWidget(tab2Btn);

        // 2. Active Tab Sliders
        if (activeTab == 0) {
            // Background Sliders
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                this.addRenderableWidget(new ColorSlider(leftX, startY + 10 + i * 22, sliderWidth, sliderHeight,
                        bgColor[i] / 255.0, "BG " + "RGB".charAt(i), value -> bgColor[idx] = (int) (value * 255)));
            }
            // Border Sliders
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                this.addRenderableWidget(new ColorSlider(leftX, startY + 86 + i * 22, sliderWidth, sliderHeight,
                        borderColor[i] / 255.0, "Border " + "RGB".charAt(i), value -> borderColor[idx] = (int) (value * 255)));
            }
        } else if (activeTab == 1) {
            // Name Badge BG Sliders
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                this.addRenderableWidget(new ColorSlider(leftX, startY + 10 + i * 22, sliderWidth, sliderHeight,
                        nameBgColor[i] / 255.0, "Badge BG " + "RGB".charAt(i), value -> nameBgColor[idx] = (int) (value * 255)));
            }
            // Name Badge Border Sliders
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                this.addRenderableWidget(new ColorSlider(leftX, startY + 86 + i * 22, sliderWidth, sliderHeight,
                        nameBorderColor[i] / 255.0, "Badge Border " + "RGB".charAt(i), value -> nameBorderColor[idx] = (int) (value * 255)));
            }
        } else {
            // Selection Square Color Sliders
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                this.addRenderableWidget(new ColorSlider(leftX, startY + 10 + i * 22, sliderWidth, sliderHeight,
                        selSquareColor[i] / 255.0, "Square " + "RGB".charAt(i), value -> selSquareColor[idx] = (int) (value * 255)));
            }
        }

        // 3. Save & Cancel Buttons at the bottom
        this.addRenderableWidget(Button.builder(Component.literal("Save").withStyle(ChatFormatting.GREEN), btn -> {
            int newBg = (0xFF << 24) | (bgColor[0] << 16) | (bgColor[1] << 8) | bgColor[2];
            int newBr = (0xFF << 24) | (borderColor[0] << 16) | (borderColor[1] << 8) | borderColor[2];
            int newNameBg = (0xFF << 24) | (nameBgColor[0] << 16) | (nameBgColor[1] << 8) | nameBgColor[2];
            int newNameBr = (0xFF << 24) | (nameBorderColor[0] << 16) | (nameBorderColor[1] << 8) | nameBorderColor[2];
            int newSel = (0xFF << 24) | (selSquareColor[0] << 16) | (selSquareColor[1] << 8) | selSquareColor[2];

            BetterShulkerConfig.setCustomBackgroundColor(newBg);
            BetterShulkerConfig.setCustomBorderColor(newBr);
            BetterShulkerConfig.setCustomNameBgColor(newNameBg);
            BetterShulkerConfig.setCustomNameBorderColor(newNameBr);
            BetterShulkerConfig.setCustomSelectionSquareColor(newSel);
            BetterShulkerConfig.save();
            Minecraft.getInstance().setScreen(parent);
        }).bounds(leftX, startY + 160, 74, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel").withStyle(ChatFormatting.RED), btn -> {
            Minecraft.getInstance().setScreen(parent);
        }).bounds(leftX + 76, startY + 160, 74, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xC01A1A2E);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, Component.literal("Custom Theme Colors").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), this.width / 2, 12, 0xFFFFFFFF);

        int cx = this.width / 2;
        int startY = this.height / 2 - 70;
        int leftX = cx - 160;

        if (activeTab == 0) {
            graphics.text(this.font, Component.literal("Main Background Color:").withStyle(ChatFormatting.GRAY), leftX, startY - 2, 0xFFAAAAAA);
            graphics.text(this.font, Component.literal("Main Border Color:").withStyle(ChatFormatting.GRAY), leftX, startY + 74, 0xFFAAAAAA);
        } else if (activeTab == 1) {
            graphics.text(this.font, Component.literal("Name Badge Background Color:").withStyle(ChatFormatting.GRAY), leftX, startY - 2, 0xFFAAAAAA);
            graphics.text(this.font, Component.literal("Name Badge Border Color:").withStyle(ChatFormatting.GRAY), leftX, startY + 74, 0xFFAAAAAA);
        } else {
            graphics.text(this.font, Component.literal("Selection Square Color:").withStyle(ChatFormatting.GRAY), leftX, startY - 2, 0xFFAAAAAA);
        }

        int previewX = cx + 15;
        int previewY = startY + 10;
        int previewWidth = 140;
        int previewHeight = 110;

        int customBg = (0xFF << 24) | (bgColor[0] << 16) | (bgColor[1] << 8) | bgColor[2];
        int customBr = (0xFF << 24) | (borderColor[0] << 16) | (borderColor[1] << 8) | borderColor[2];
        int customNameBg = (0xFF << 24) | (nameBgColor[0] << 16) | (nameBgColor[1] << 8) | nameBgColor[2];
        int customNameBr = (0xFF << 24) | (nameBorderColor[0] << 16) | (nameBorderColor[1] << 8) | nameBorderColor[2];

        // Draw a gorgeous frame for the preview area
        graphics.fill(previewX - 4, previewY - 24, previewX + previewWidth + 4, previewY + previewHeight + 4, 0xFF111116);
        graphics.fill(previewX - 5, previewY - 25, previewX + previewWidth + 5, previewY - 24, 0xFF9B59B6);
        graphics.fill(previewX - 5, previewY + previewHeight + 4, previewX + previewWidth + 5, previewY + previewHeight + 5, 0xFF9B59B6);
        graphics.fill(previewX - 5, previewY - 24, previewX - 4, previewY + previewHeight + 4, 0xFF9B59B6);
        graphics.fill(previewX + previewWidth + 4, previewY - 24, previewX + previewWidth + 5, previewY + previewHeight + 4, 0xFF9B59B6);

        graphics.centeredText(this.font, Component.literal("LIVE PREVIEW").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD, ChatFormatting.UNDERLINE), previewX + previewWidth / 2, previewY - 20, 0xFFFFFFFF);

        // Draw custom main tooltip background inside preview
        graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, customBg);

        // Draw custom borders inside preview
        graphics.fill(previewX, previewY, previewX + previewWidth, previewY + 1, customBr); // Top
        graphics.fill(previewX, previewY + previewHeight - 1, previewX + previewWidth, previewY + previewHeight, customBr); // Bottom
        graphics.fill(previewX, previewY + 1, previewX + 1, previewY + previewHeight - 1, customBr); // Left
        graphics.fill(previewX + previewWidth - 1, previewY + 1, previewX + previewWidth, previewY + previewHeight - 1, customBr); // Right

        // Draw inner borders highlights/shadows
        int customHighlight = blendColor(customBr, 0xFFFFFFFF, 0.3f);
        int customShadow = blendColor(customBr, 0xFF000000, 0.2f);
        graphics.fill(previewX + 1, previewY + 1, previewX + previewWidth - 1, previewY + 2, customHighlight);
        graphics.fill(previewX + 1, previewY + 2, previewX + 2, previewY + previewHeight - 2, customHighlight);
        graphics.fill(previewX + 1, previewY + previewHeight - 2, previewX + previewWidth - 1, previewY + previewHeight - 1, customShadow);
        graphics.fill(previewX + previewWidth - 2, previewY + 2, previewX + previewWidth - 1, previewY + previewHeight - 2, customShadow);

        // Draw simulated classic grey slot grids inside preview
        int slotXSize = 18;
        int gridX = previewX + 8;
        int gridY = previewY + 8;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 7; c++) {
                int sx = gridX + c * 18;
                int sy = gridY + r * 18;
                graphics.fill(sx, sy, sx + 17, sy + 17, 0xFF8B8B8B);
                graphics.fill(sx + 1, sy + 1, sx + 16, sy + 16, 0xFF373737);
            }
        }

        // Draw a simulated selection highlight slot in preview using current custom color
        int selSlotX = gridX + 2 * 18;
        int selSlotY = gridY + 1 * 18;
        int selectionColor = (0xFF << 24) | (selSquareColor[0] << 16) | (selSquareColor[1] << 8) | selSquareColor[2];
        graphics.fill(selSlotX, selSlotY, selSlotX + 17, selSlotY + 1, selectionColor);
        graphics.fill(selSlotX, selSlotY + 16, selSlotX + 17, selSlotY + 17, selectionColor);
        graphics.fill(selSlotX, selSlotY + 1, selSlotX + 1, selSlotY + 16, selectionColor);
        graphics.fill(selSlotX + 16, selSlotY + 1, selSlotX + 17, selSlotY + 16, selectionColor);
        int innerSelColor = (0x30 << 24) | (selectionColor & 0x00FFFFFF);
        graphics.fill(selSlotX + 1, selSlotY + 1, selSlotX + 16, selSlotY + 16, innerSelColor);

        // Draw simulated selected name badge inside preview
        String previewBadgeName = "Shulker Box";
        int badgeTextWidth = this.font.width(previewBadgeName);
        int badgeWidth = badgeTextWidth + 10;
        int badgeHeight = 12;
        int badgeX = previewX + (previewWidth - badgeWidth) / 2;
        int badgeY = previewY + previewHeight + 8;

        graphics.fill(badgeX + 1, badgeY + 1, badgeX + badgeWidth - 1, badgeY + badgeHeight, customNameBg);
        graphics.fill(badgeX + 1, badgeY, badgeX + badgeWidth - 1, badgeY + 1, customNameBr);
        graphics.fill(badgeX, badgeY + 1, badgeX + 1, badgeY + badgeHeight, customNameBr);
        graphics.fill(badgeX + badgeWidth - 1, badgeY + 1, badgeX + badgeWidth, badgeY + badgeHeight, customNameBr);

        int previewTextCol = getTextColorForBackground(customNameBg);
        graphics.text(this.font, Component.literal(previewBadgeName), badgeX + 5, badgeY + 2, previewTextCol);
    }

    public static int getTextColorForBackground(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.65 ? 0xFF373737 : 0xFFFFFFFF;
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

    private static class ColorSlider extends AbstractSliderButton {
        private final java.util.function.Consumer<Double> setter;
        private final String label;

        public ColorSlider(int x, int y, int width, int height, double value, String label, java.util.function.Consumer<Double> setter) {
            super(x, y, width, height, Component.empty(), value);
            this.setter = setter;
            this.label = label;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) (this.value * 255);
            this.setMessage(Component.literal(label + ": " + pct));
        }

        @Override
        protected void applyValue() {
            setter.accept(this.value);
        }
    }
}

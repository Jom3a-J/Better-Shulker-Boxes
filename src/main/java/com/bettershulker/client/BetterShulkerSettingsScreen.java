package com.bettershulker.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import com.bettershulker.BetterShulkerConfig;

public class BetterShulkerSettingsScreen extends Screen {
    private static final int BG_COLOR = 0xC01A1A2E;
    private static final int ACCENT_COLOR = 0xFF9B59B6;

    private final Screen lastScreen;

    public BetterShulkerSettingsScreen(Screen lastScreen) {
        super(Component.literal("Better Shulker Settings"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int startY = this.height / 2 - 70;
        int bw = 160, bh = 20;
        int leftX = cx - 165;
        int rightX = cx + 5;

        // Left Column - Toggles
        addToggle("Tooltip", BetterShulkerConfig.isTooltipEnabled(),
                leftX, startY, bw, bh,
                v -> BetterShulkerConfig.setTooltipEnabled(v));

        addToggle("Precision Mode (Ctrl)", BetterShulkerConfig.isPrecisionModeEnabled(),
                leftX, startY + 24, bw, bh,
                v -> BetterShulkerConfig.setPrecisionModeEnabled(v));

        addToggle("Fill Indicator", BetterShulkerConfig.isFillIndicatorEnabled(),
                leftX, startY + 48, bw, bh,
                v -> BetterShulkerConfig.setFillIndicatorEnabled(v));

        addToggle("Selection Square", BetterShulkerConfig.isSecondaryTooltipEnabled(),
                leftX, startY + 72, bw, bh,
                v -> BetterShulkerConfig.setSecondaryTooltipEnabled(v));

        addToggle("Alt Force Tooltip", BetterShulkerConfig.isAltForceTooltipEnabled(),
                leftX, startY + 96, bw, bh,
                v -> BetterShulkerConfig.setAltForceTooltipEnabled(v));

        // Edit Colors button (opens custom color picker - always active)
        Button editColorsBtn = Button.builder(
                Component.literal("Edit Colors"),
                btn -> Minecraft.getInstance().setScreenAndShow(new CustomColorPickerScreen(this)))
                .bounds(rightX, startY + 120, bw, bh).build();
        editColorsBtn.active = true;

        // Tooltip Theme cycle (left column)
        this.addRenderableWidget(Button.builder(
                Component.literal("Theme: " + BetterShulkerConfig.getTooltipTheme().getDisplayName()),
                btn -> {
                    var values = BetterShulkerConfig.TooltipTheme.values();
                    int next = (BetterShulkerConfig.getTooltipTheme().ordinal() + 1) % values.length;
                    BetterShulkerConfig.setTooltipTheme(values[next]);
                    btn.setMessage(Component.literal("Theme: " + values[next].getDisplayName()));
                }
        ).bounds(leftX, startY + 120, bw, bh).build());

        this.addRenderableWidget(editColorsBtn);

        // Visuals & Animations (left column bottom)
        this.addRenderableWidget(Button.builder(
                Component.literal("Visuals & Animations"),
                btn -> Minecraft.getInstance().setScreenAndShow(new BetterShulkerVisualsScreen(this)))
                .bounds(leftX, startY + 144, bw, bh).build());

        // Right Column - Toggles and Sound controls
        addToggle("Selected Item Name", BetterShulkerConfig.isSelectedItemNameEnabled(),
                rightX, startY, bw, bh,
                v -> BetterShulkerConfig.setSelectedItemNameEnabled(v));

        addToggle("Compact Tooltips", BetterShulkerConfig.isCompactTooltipEnabled(),
                rightX, startY + 24, bw, bh,
                v -> BetterShulkerConfig.setCompactTooltipEnabled(v));

        // Volume Slider (using nested VolumeSlider class)
        this.addRenderableWidget(new VolumeSlider(
                rightX, startY + 48, bw, bh,
                BetterShulkerConfig.getSoundVolume(),
                val -> BetterShulkerConfig.setSoundVolume(val.floatValue())
        ));

        // Custom Sound Cycle Button (right column)
        this.addRenderableWidget(Button.builder(
                soundLabel(),
                btn -> {
                    var values = BetterShulkerConfig.SoundOption.values();
                    int nextOrd = (BetterShulkerConfig.getSoundOption().ordinal() + 1) % values.length;
                    BetterShulkerConfig.setSoundOption(values[nextOrd]);
                    btn.setMessage(soundLabel());
                    playPreviewSound();
                }
        ).bounds(rightX, startY + 72, bw, bh).build());

        // Configure Controls button (right column bottom)
        this.addRenderableWidget(Button.builder(
                Component.literal("Configure Controls"),
                btn -> Minecraft.getInstance().setScreenAndShow(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this, Minecraft.getInstance().options)))
                .bounds(rightX, startY + 96, bw, bh).build());

        // Center Done button at bottom — full width, well below all buttons
        this.addRenderableWidget(Button.builder(
                Component.literal("Done").withStyle(ChatFormatting.WHITE),
                btn -> {
                    BetterShulkerConfig.save();
                    Minecraft.getInstance().setScreenAndShow(this.lastScreen);
                }
        ).bounds(cx - 100, startY + 180, 200, bh).build());
    }

    private void addToggle(String name, boolean initial, int x, int y, int w, int h,
                           java.util.function.Consumer<Boolean> setter) {
        this.addRenderableWidget(Button.builder(
                toggleLabel(name, initial),
                btn -> {
                    boolean next = !btn.getMessage().getString().contains("ON");
                    setter.accept(next);
                    btn.setMessage(toggleLabel(name, next));
                }
        ).bounds(x, y, w, h).build());
    }

    private Component toggleLabel(String name, boolean value) {
        MutableComponent status = Component.literal(value ? "ON" : "OFF")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD);
        return Component.literal(name + "  ").append(status);
    }

    private Component soundLabel() {
        return Component.literal("Sound: ")
                .append(Component.literal(BetterShulkerConfig.getSoundOption().getDisplayName())
                        .withStyle(ChatFormatting.YELLOW));
    }

    private void playPreviewSound() {
        var mc = Minecraft.getInstance();
        if (mc.player != null && BetterShulkerConfig.getSoundVolume() > 0.0f) {
            try {
                if (BetterShulkerConfig.getSoundOption() == BetterShulkerConfig.SoundOption.CONTEXTUAL) {
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, BetterShulkerConfig.getSoundVolume(), 1.0F);
                    return;
                }
                String[] split = BetterShulkerConfig.getSoundOption().getSoundId().split(":", 2);
                var soundLoc = net.minecraft.resources.Identifier.fromNamespaceAndPath(split[0], split[1]);
                var soundHolderOpt = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(soundLoc);
                if (soundHolderOpt.isPresent()) {
                    var soundEvent = soundHolderOpt.get().value();
                    mc.player.playSound(soundEvent, BetterShulkerConfig.getSoundVolume(), 1.0F);
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, BG_COLOR);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        MutableComponent title = Component.literal("Better Shulker")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        graphics.centeredText(this.font, title, this.width / 2, 12, 0xFFFFFFFF);

        MutableComponent subtitle = Component.literal("Customize your container experience")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        graphics.centeredText(this.font, subtitle, this.width / 2, 24, 0xFFAAAAAA);

        graphics.fill(this.width / 2 - 100, 32, this.width / 2 + 100, 33, ACCENT_COLOR);
    }

    // Nested Volume Slider class
    private static class VolumeSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
        private final java.util.function.Consumer<Double> setter;

        public VolumeSlider(int x, int y, int width, int height, double initialValue, java.util.function.Consumer<Double> setter) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.setter = setter;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) (this.value * 100);
            this.setMessage(Component.literal("Volume: " + pct + "%"));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(this.value);
        }
    }
}

/**
 * Sub-screen for visual animations and customizations.
 */
class BetterShulkerVisualsScreen extends Screen {
    private final Screen lastScreen;

    public BetterShulkerVisualsScreen(Screen lastScreen) {
        super(Component.literal("Better Shulker Visuals"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int startY = this.height / 2 - 60;
        int bw = 160, bh = 20;
        int leftX = cx - 165;
        int rightX = cx + 5;

        // Visual Toggles - Left Column
        addToggle("Selection Glide", BetterShulkerConfig.isSelectionGlideEnabled(),
                leftX, startY, bw, bh,
                v -> BetterShulkerConfig.setSelectionGlideEnabled(v));

        addToggle("Hover Zoom & Glow", BetterShulkerConfig.isHoverAnimationsEnabled(),
                leftX, startY + 24, bw, bh,
                v -> BetterShulkerConfig.setHoverAnimationsEnabled(v));

        // Visual Toggles - Right Column
        addToggle("Rare Item Floating", BetterShulkerConfig.isRareItemWobbleEnabled(),
                rightX, startY, bw, bh,
                v -> BetterShulkerConfig.setRareItemWobbleEnabled(v));

        // Center Done button at bottom
        this.addRenderableWidget(Button.builder(
                Component.literal("Back").withStyle(ChatFormatting.WHITE),
                btn -> {
                    BetterShulkerConfig.save();
                    Minecraft.getInstance().setScreenAndShow(this.lastScreen);
                }
        ).bounds(cx - 100, startY + 60, 200, bh).build());
    }

    private void addToggle(String name, boolean initial, int x, int y, int w, int h,
                           java.util.function.Consumer<Boolean> setter) {
        this.addRenderableWidget(Button.builder(
                toggleLabel(name, initial),
                btn -> {
                    boolean next = !btn.getMessage().getString().contains("ON");
                    setter.accept(next);
                    btn.setMessage(toggleLabel(name, next));
                }
        ).bounds(x, y, w, h).build());
    }

    private Component toggleLabel(String name, boolean value) {
        MutableComponent status = Component.literal(value ? "ON" : "OFF")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD);
        return Component.literal(name + "  ").append(status);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xC01A1A2E); // Beautiful custom background color
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        MutableComponent title = Component.literal("Visuals & Animations")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        graphics.centeredText(this.font, title, this.width / 2, 12, 0xFFFFFFFF);

        MutableComponent subtitle = Component.literal("Customize tooltip visual feedback")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        graphics.centeredText(this.font, subtitle, this.width / 2, 24, 0xFFAAAAAA);

        graphics.fill(this.width / 2 - 100, 32, this.width / 2 + 100, 33, 0xFF9B59B6);
    }
}

package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.render.ResourcePackContainerTextures;
import com.bettershulker.client.render.ResourcePackLayout;
import com.bettershulker.client.render.ResourcePackLayoutProfiles;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static com.bettershulker.client.render.ThemeColorUtil.blendColor;
import static com.bettershulker.client.render.ThemeColorUtil.getTextColorForBackground;
import static com.bettershulker.client.render.ThemeColorUtil.normalizeOverlayAlpha;
import static com.bettershulker.client.render.ThemeColorUtil.withAlpha;

/**
 * Cloth Config-powered settings screen for Better Shulker Boxes.
 */
public final class BetterShulkerClothConfigScreen {
    private BetterShulkerClothConfigScreen() {}

    public static Screen create(Screen parent) {
        LiveTooltipPreview.CustomPreviewState previewState = new LiveTooltipPreview.CustomPreviewState();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(text("Better Shulker Settings"))
                .setSavingRunnable(BetterShulkerConfig::save)
                .setAfterInitConsumer(screen -> LiveTooltipPreview.attachTo(screen, previewState))
                .setDoesConfirmSave(false);

        ConfigEntryBuilder entry = builder.entryBuilder();

        addGeneralCategory(builder, entry);
        addVisualsCategory(builder, entry);
        addAudioCategory(builder, entry);
        addThemeCategory(builder, entry, previewState);
        addControlsCategory(builder, entry);

        return builder.build();
    }




















    private static void addGeneralCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("General"));
        category.addEntry(entry.startBooleanToggle(text("Tooltip Preview"), BetterShulkerConfig.isTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setTooltipEnabled)
                .setTooltip(text("Show the 9x3 shulker/ender chest preview tooltip."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Precision Mode (Ctrl)"), BetterShulkerConfig.isPrecisionModeEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setPrecisionModeEnabled)
                .setTooltip(text("Use the precision key for one-item insert/extract actions."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Fill Indicator"), BetterShulkerConfig.isFillIndicatorEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setFillIndicatorEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Selection Square"), BetterShulkerConfig.isSecondaryTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSecondaryTooltipEnabled)
                .setTooltip(text("Show and control the selected slot highlight in the tooltip."))
                .build());
        category.addEntry(entry.startBooleanToggle(text("Alt Force Tooltip"), BetterShulkerConfig.isAltForceTooltipEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setAltForceTooltipEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Selected Item Name"), BetterShulkerConfig.isSelectedItemNameEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSelectedItemNameEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Compact Tooltips"), BetterShulkerConfig.isCompactTooltipEnabled())
                .setDefaultValue(false)
                .setSaveConsumer(BetterShulkerConfig::setCompactTooltipEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Check for Updates"), BetterShulkerConfig.isUpdateCheckEnabled())
                .setDefaultValue(true)
                .setTooltip(text("Contacts modrinth.com once per session, the first time you join a world,"
                        + " to see whether a newer release exists."))
                .setSaveConsumer(BetterShulkerConfig::setUpdateCheckEnabled)
                .build());
    }

    private static void addVisualsCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Visuals & Animations"));
        category.addEntry(entry.startBooleanToggle(text("Selection Glide"), BetterShulkerConfig.isSelectionGlideEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setSelectionGlideEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Hover Zoom & Glow"), BetterShulkerConfig.isHoverAnimationsEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setHoverAnimationsEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Rare Item Floating"), BetterShulkerConfig.isRareItemWobbleEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setRareItemWobbleEnabled)
                .build());
        category.addEntry(entry.startBooleanToggle(text("Container Bounce"), BetterShulkerConfig.isContainerBounceEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(BetterShulkerConfig::setContainerBounceEnabled)
                .setTooltip(text("Bounce a Shulker Box in its slot while you carry an item that can be dropped into it."))
                .build());
    }

    private static void addAudioCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Audio"));
        category.addEntry(entry.startIntSlider(text("Sound Volume"), Math.round(BetterShulkerConfig.getSoundVolume() * 100.0f), 0, 100)
                .setDefaultValue(30)
                .setTextGetter(value -> text(value + "%"))
                .setSaveConsumer(value -> BetterShulkerConfig.setSoundVolume(value / 100.0f))
                .setTooltip(text("0% = muted, 100% = full volume."))
                .build());
        category.addEntry(entry.startEnumSelector(text("Interaction Sound"), BetterShulkerConfig.SoundOption.class, BetterShulkerConfig.getSoundOption())
                .setDefaultValue(BetterShulkerConfig.SoundOption.ITEM_PICKUP)
                .setEnumNameProvider(value -> text(((BetterShulkerConfig.SoundOption) value).getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setSoundOption)
                .build());
    }

    private static void addThemeCategory(ConfigBuilder builder, ConfigEntryBuilder entry, LiveTooltipPreview.CustomPreviewState previewState) {
        ConfigCategory category = builder.getOrCreateCategory(text("Theme & Colors"));
        previewState.resourcePackMode = entry.startSelector(text("Resource Pack GUI Mode"),
                        BetterShulkerConfig.ResourcePackMode.values(), BetterShulkerConfig.getResourcePackMode())
                .setDefaultValue(BetterShulkerConfig.ResourcePackMode.AUTO)
                .setNameProvider(value -> text(value.getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setResourcePackMode)
                .setTooltip(text("Automatic follows detected resource-pack GUI textures; Always Enabled shows the resource-pack layout even without a pack; Disabled keeps the normal tooltip layout."))
                .build();
        category.addEntry(previewState.resourcePackMode);
        addResourcePackLayoutSettings(category, entry, previewState);
        previewState.style = entry.startSelector(text("Tooltip Style"), BetterShulkerConfig.TooltipStyle.values(), BetterShulkerConfig.getTooltipStyle())
                .setDefaultValue(BetterShulkerConfig.TooltipStyle.MODERN)
                .setNameProvider(value -> text(value.getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setTooltipStyle)
                .setTooltip(text("Modern draws a self-contained rounded card coloured by the container's own dye, or by an active resource pack. Vanilla builds the panel from the container GUI texture and is coloured by the theme and sliders below, which Modern greys out."))
                .build();
        category.addEntry(previewState.style);

        // Modern derives every colour itself, so nothing below it applies while it is selected.
        Requirement vanillaStyle = Requirement.isValue(previewState.style,
                BetterShulkerConfig.TooltipStyle.VANILLA);

        previewState.theme = entry.startSelector(text("Tooltip Theme"), BetterShulkerConfig.TooltipTheme.values(), BetterShulkerConfig.getTooltipTheme())
                .setDefaultValue(BetterShulkerConfig.TooltipTheme.ORIGINAL)
                .setNameProvider(value -> text(value.getDisplayName()))
                .setSaveConsumer(BetterShulkerConfig::setTooltipTheme)
                .setTooltip(text("Use the arrows to choose from the visible theme list. Only applies to the Vanilla tooltip style."))
                .setRequirement(vanillaStyle)
                .build();
        category.addEntry(previewState.theme);
        category.addEntry(entry.startTextDescription(text("The live preview on the right updates for every theme before you press Done. Selected Name Text applies to every theme; the other RGB sliders are used by Custom. The theme and all sliders are greyed out under the Modern style, which colours itself."))
                .build());

        previewState.background = addRgbSliders(category, entry, "Custom Background", BetterShulkerConfig.getCustomBackgroundColor(), 0xFF1A1A1A,
                BetterShulkerConfig::getCustomBackgroundColor, BetterShulkerConfig::setCustomBackgroundColor, vanillaStyle);
        previewState.border = addRgbSliders(category, entry, "Custom Border", BetterShulkerConfig.getCustomBorderColor(), 0xFF8932B8,
                BetterShulkerConfig::getCustomBorderColor, BetterShulkerConfig::setCustomBorderColor, vanillaStyle);
        previewState.nameBackground = addRgbSliders(category, entry, "Selected Name Background", BetterShulkerConfig.getCustomNameBgColor(), 0xF0100010,
                BetterShulkerConfig::getCustomNameBgColor, BetterShulkerConfig::setCustomNameBgColor, vanillaStyle);
        previewState.nameBorder = addRgbSliders(category, entry, "Selected Name Border", BetterShulkerConfig.getCustomNameBorderColor(), 0xFF8932B8,
                BetterShulkerConfig::getCustomNameBorderColor, BetterShulkerConfig::setCustomNameBorderColor, vanillaStyle);
        previewState.nameText = addRgbSliders(category, entry, "Selected Name Text", BetterShulkerConfig.getCustomNameTextColor(), 0xFFFFFFFF,
                BetterShulkerConfig::getCustomNameTextColor, BetterShulkerConfig::setCustomNameTextColor, vanillaStyle);
        previewState.selection = addRgbSliders(category, entry, "Selection Square", BetterShulkerConfig.getCustomSelectionSquareColor(), 0xFFFFD700,
                BetterShulkerConfig::getCustomSelectionSquareColor, BetterShulkerConfig::setCustomSelectionSquareColor, vanillaStyle);
    }

    private static void addResourcePackLayoutSettings(ConfigCategory category, ConfigEntryBuilder entry,
                                                      LiveTooltipPreview.CustomPreviewState previewState) {
        ResourcePackContainerTextures.Panel panel = ResourcePackContainerTextures.resolveDetected(null, false);
        previewState.resourcePackTexture = panel.texture();
        previewState.resourcePackLayout = ResourcePackLayoutProfiles.resolveBase(panel.texture());
        previewState.resourcePackDetected = panel.suppliedByPack();

        var sub = entry.startSubCategory(text("Resource Pack Layout"));
        previewState.resourcePackOffsetX = entry.startIntSlider(text("Slot X Offset"), BetterShulkerConfig.getResourcePackLayoutOffsetX(), -32, 32)
                .setDefaultValue(0)
                .setTextGetter(value -> text(value + " px"))
                .setSaveConsumer(BetterShulkerConfig::setResourcePackLayoutOffsetX)
                .setTooltip(text("Moves the detected resource-pack slot grid horizontally inside the tooltip."))
                .build();
        previewState.resourcePackOffsetY = entry.startIntSlider(text("Slot Y Offset"), BetterShulkerConfig.getResourcePackLayoutOffsetY(), -32, 32)
                .setDefaultValue(0)
                .setTextGetter(value -> text(value + " px"))
                .setSaveConsumer(BetterShulkerConfig::setResourcePackLayoutOffsetY)
                .setTooltip(text("Moves the detected resource-pack slot grid vertically inside the tooltip."))
                .build();
        previewState.resourcePackCapHeight = entry.startIntSlider(text("Bottom Cap Height"), BetterShulkerConfig.getResourcePackLayoutBottomCapHeight(), -1, 16)
                .setDefaultValue(-1)
                .setTextGetter(value -> text(value < 0 ? "Auto" : value + " px"))
                .setSaveConsumer(BetterShulkerConfig::setResourcePackLayoutBottomCapHeight)
                .setTooltip(text("Auto uses the selected layout profile. Override this only when the pack's lower edge needs more or less space."))
                .build();
        sub.add(previewState.resourcePackOffsetX);
        sub.add(previewState.resourcePackOffsetY);
        sub.add(previewState.resourcePackCapHeight);
        category.addEntry(sub.setExpanded(false).build());
    }


    private static void addControlsCategory(ConfigBuilder builder, ConfigEntryBuilder entry) {
        ConfigCategory category = builder.getOrCreateCategory(text("Controls"));
        category.addEntry(entry.fillKeybindingField(text("Open Settings"), ClientKeybinds.getSettingsKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Extract Selected Slots"), ClientKeybinds.getExtractKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Select Tooltip Slot"), ClientKeybinds.getSelectSlotKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Precision Mode"), ClientKeybinds.getPrecisionKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Alt Force Tooltip"), ClientKeybinds.getAltForceKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Selection Left"), ClientKeybinds.getScrollLeftKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Selection Right"), ClientKeybinds.getScrollRightKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Restock / Deposit"), ClientKeybinds.getRestockKey()).build());
        category.addEntry(entry.fillKeybindingField(text("Show Full Tooltip"), ClientKeybinds.getShowFullTooltipKey()).build());
    }

    private static LiveTooltipPreview.ColorSliders addRgbSliders(ConfigCategory category, ConfigEntryBuilder entry, String label, int currentColor, int defaultColor,
                                              IntSupplier currentSupplier,
                                              IntConsumer saveConsumer,
                                              Requirement enableRequirement) {
        var sub = entry.startSubCategory(text(label));
        IntegerSliderEntry red = entry.startIntSlider(text("Red"), red(currentColor), 0, 255)
                .setDefaultValue(red(defaultColor))
                .setTextGetter(value -> text("R: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceRed(currentSupplier.getAsInt(), value)))
                .build();
        IntegerSliderEntry green = entry.startIntSlider(text("Green"), green(currentColor), 0, 255)
                .setDefaultValue(green(defaultColor))
                .setTextGetter(value -> text("G: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceGreen(currentSupplier.getAsInt(), value)))
                .build();
        IntegerSliderEntry blue = entry.startIntSlider(text("Blue"), blue(currentColor), 0, 255)
                .setDefaultValue(blue(defaultColor))
                .setTextGetter(value -> text("B: " + value))
                .setSaveConsumer(value -> saveConsumer.accept(replaceBlue(currentSupplier.getAsInt(), value)))
                .build();
        sub.add(red);
        sub.add(green);
        sub.add(blue);
        // Set on the group rather than each slider, so the whole colour folds out grey together.
        category.addEntry(sub.setRequirement(enableRequirement).setExpanded(false).build());
        return new LiveTooltipPreview.ColorSliders(red, green, blue);
    }



    private static int red(int color) { return (color >> 16) & 0xFF; }
    private static int green(int color) { return (color >> 8) & 0xFF; }
    private static int blue(int color) { return color & 0xFF; }
    private static int alpha(int color) { return color & 0xFF000000; }
    private static int replaceRed(int color, int value) { return alpha(color) | (value << 16) | (green(color) << 8) | blue(color); }
    private static int replaceGreen(int color, int value) { return alpha(color) | (red(color) << 16) | (value << 8) | blue(color); }
    private static int replaceBlue(int color, int value) { return alpha(color) | (red(color) << 16) | (green(color) << 8) | value; }

    private static Component text(String value) {
        return Component.literal(value);
    }
}

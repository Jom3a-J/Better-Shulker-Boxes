package com.bettershulker.gametest;

import com.bettershulker.client.render.PanelBottomEdge;
import com.bettershulker.client.render.ResourcePackLayout;
import com.bettershulker.client.render.ResourcePackPanelColors;
import com.bettershulker.client.render.ShulkerGuiRules;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;

/**
 * Checks that resource-pack Shulker definitions are read the way OptiFine and OptiGUI read them.
 *
 * <p>Each case is a definition copied in shape from a real pack that the old parser got wrong:
 * OptiGUI INI files outside {@code gui/shulkers/}, headers naming several boxes, Shulker UI's
 * {@code name=regex:} rules, and an HD panel whose colour was sampled from the wrong quarter.
 * The definitions are handed to the parser directly, so no pack has to be installed.</p>
 */
public class PackDefinitionGameTest implements FabricClientGameTest {

    private static final Identifier INI = Identifier.fromNamespaceAndPath("optigui", "gui/shulker_box/shulker_box.ini");
    private static final Identifier PROPERTIES = Identifier.withDefaultNamespace(
            "optifine/gui/container/shulkers/lime.properties");

    @Override
    public void runTest(ClientGameTestContext context) {
        iniSectionsAnywhereUnderGuiAreRead();
        iniSectionsForOtherTexturesOrBadPrioritiesAreDropped();
        nameRulesFollowOptiFineSyntax();
        aFileWithoutColorsReadsAsEachModReadsIt();
        texturePathsResolveLikeTheMods();
        context.runOnClient(client -> unnamedBoxesAreMatchedByTheirScreenTitle());
        hdPanelsAreSampledAcrossTheWholePanel();
        mostlyClearPanelsAreNotPanels();
        aCoveringInventoryTabNeverReachesThePreview();
        context.runOnClient(client -> vanillaPanelsGetTheirBottomBorder());
        theBottomEdgeNeverReadsOutsideThePanel();
    }

    /**
     * OreUI-Recreation keeps sixteen sections in {@code gui/shulker_box/shulker_box.ini}, and
     * headers may name several boxes and carry a {@code #} tag that only tells duplicates apart.
     */
    private void iniSectionsAnywhereUnderGuiAreRead() {
        List<ShulkerGuiRules.Rule> rules = ini("""
                [purple_shulker_box minecraft:black_shulker_box #2]
                replacement = dark.png

                [chest]
                replacement = chest.png
                """);

        assertTrue(rules.size() == 1, "expected one Shulker section, got " + rules.size());
        ShulkerGuiRules.Rule rule = rules.getFirst();
        assertTrue(rule.appliesTo("purple_shulker_box", "Shulker Box"), "the first header id was not read");
        assertTrue(rule.appliesTo("black_shulker_box", "Shulker Box"), "a namespaced header id was not read");
        assertTrue(!rule.appliesTo("shulker_box", "Shulker Box"), "the section leaked to the undyed box");
        assertTrue(rule.textures().getFirst().equals(
                        Identifier.fromNamespaceAndPath("optigui", "gui/shulker_box/dark.png")),
                "the replacement was not resolved beside its INI file: " + rule.textures());
    }

    private void iniSectionsForOtherTexturesOrBadPrioritiesAreDropped() {
        List<ShulkerGuiRules.Rule> rules = ini("""
                [red_shulker_box]
                replacement = red.png
                interaction.texture = minecraft:textures/gui/container/generic_54.png

                [blue_shulker_box]
                replacement = blue.png
                load.priority = high

                [green_shulker_box]
                replacement = green.png
                interaction.texture = textures/gui/container/shulker_box.png
                load.priority = 4
                """);

        assertTrue(rules.size() == 1 && rules.getFirst().appliesTo("green_shulker_box", "Shulker Box"),
                "only the green section should survive, got " + rules.size() + " rules");
        assertTrue(rules.getFirst().priority() == 4, "load.priority was not read");
    }

    /** Shulker UI keeps one panel for unnamed boxes and one for named ones. */
    private void nameRulesFollowOptiFineSyntax() {
        ShulkerGuiRules.NameMatcher unnamed = ShulkerGuiRules.NameMatcher.optiFine("regex:^$");
        assertTrue(unnamed.matches(""), "regex:^$ does not match the blanked title");
        assertTrue(!unnamed.matches("Loot"), "regex:^$ matches a named box");

        ShulkerGuiRules.NameMatcher named = ShulkerGuiRules.NameMatcher.optiFine("regex:^.+$");
        assertTrue(named.matches("Loot") && !named.matches(""), "regex:^.+$ is not a full match");

        ShulkerGuiRules.NameMatcher wildcard = ShulkerGuiRules.NameMatcher.optiFine("pattern:Box?");
        assertTrue(wildcard.matches("Box1") && !wildcard.matches("Box") && !wildcard.matches("box1"),
                "pattern: should read ? as exactly one character, case-sensitively");

        ShulkerGuiRules.NameMatcher negated = ShulkerGuiRules.NameMatcher.optiFine("!ipattern:*ORE*");
        assertTrue(!negated.matches("iron ore") && negated.matches("Tools"),
                "! should negate a case-insensitive wildcard");

        assertTrue(ShulkerGuiRules.NameMatcher.optiFine("Loot").matches("Loot")
                        && !ShulkerGuiRules.NameMatcher.optiFine("Loot").matches("loot"),
                "a plain name should be an exact, case-sensitive match");
        assertTrue(!ShulkerGuiRules.NameMatcher.optiFine("regex:(").matches("("),
                "an expression that does not compile should never match");

        ShulkerGuiRules.Rule rule = properties("container=shulker_box\ncolors=lime\ntexture=lime\nname=regex:^$\n", false);
        assertTrue(rule != null && rule.appliesTo("lime_shulker_box", ""), "the unnamed lime rule did not apply");
        assertTrue(!rule.appliesTo("lime_shulker_box", "Loot"), "the unnamed lime rule applied to a named box");
        assertTrue(!rule.appliesTo("red_shulker_box", ""), "the lime rule applied to a red box");

        // Recolourful Containers: an empty name= is the unnamed panel, a missing one the fallback.
        ShulkerGuiRules.Rule blank = properties("container=shulker_box\ncolors=lime\ntexture=lime\nname=\n", false);
        assertTrue(blank.appliesTo("lime_shulker_box", "") && !blank.appliesTo("lime_shulker_box", "Loot"),
                "an empty name= should match only the blanked title");
        List<ShulkerGuiRules.Rule> unnamedIni = ini("[lime_shulker_box]\nname=\nreplacement = lime.png\n");
        assertTrue(unnamedIni.getFirst().appliesTo("lime_shulker_box", "")
                        && !unnamedIni.getFirst().appliesTo("lime_shulker_box", "Loot"),
                "an empty INI name= should match only the blanked title");
    }

    /** OptiFine applies a file without colors to every box; OptiGUI only to the undyed one. */
    private void aFileWithoutColorsReadsAsEachModReadsIt() {
        String file = "container=shulker_box\ntexture=all\n";
        ShulkerGuiRules.Rule optiFine = properties(file, false);
        ShulkerGuiRules.Rule optiGui = properties(file, true);

        assertTrue(optiFine.appliesTo("shulker_box", "") && optiFine.appliesTo("red_shulker_box", ""),
                "under OptiFine rules a file without colors should apply to every box");
        assertTrue(optiGui.appliesTo("shulker_box", "") && !optiGui.appliesTo("red_shulker_box", ""),
                "under OptiGUI rules a file without colors should apply to the undyed box only");

        ShulkerGuiRules.Rule dyed = properties("container=shulker_box\ncolors=grey nonsense\ntexture=x\n", false);
        assertTrue(dyed == null, "no valid dye was named, so the file should not apply anywhere");
        ShulkerGuiRules.Rule other = properties("container=chest\ntexture=x\n", false);
        assertTrue(other == null, "a chest definition was read as a Shulker one");
    }

    private void texturePathsResolveLikeTheMods() {
        assertTexture("./lime.png", "minecraft:optifine/gui/container/shulkers/lime.png");
        assertTexture("lime", "minecraft:optifine/gui/container/shulkers/lime.png");
        assertTexture("../shared/lime.png", "minecraft:optifine/gui/container/shared/lime.png");
        assertTexture("~/gui/lime.png", "minecraft:optifine/gui/lime.png");
        assertTexture("optigui:gui/shulker_boxes/lime.png", "optigui:gui/shulker_boxes/lime.png");
    }

    private void assertTexture(String value, String expected) {
        ShulkerGuiRules.Rule rule = properties("container=shulker_box\ncolors=lime\ntexture=" + value + "\n", false);
        assertTrue(rule != null, "texture=" + value + " was rejected");
        List<Identifier> textures = rule.textures();
        // The first candidate that exists wins, and none exist here, so the expected path has to
        // be the first one tried that names a .png.
        Identifier first = textures.stream().filter(id -> id.getPath().endsWith(".png")).findFirst().orElseThrow();
        assertTrue(first.toString().equals(expected),
                "texture=" + value + " resolved to " + first + " first, expected " + expected);
    }

    /** OptiFine matches the screen title, which for an unnamed purple box is "Shulker Box". */
    private void unnamedBoxesAreMatchedByTheirScreenTitle() {
        ItemStack purple = new ItemStack(Items.DYED_SHULKER_BOX.pick(DyeColor.PURPLE));
        String expected = Component.translatable("container.shulkerBox").getString();
        assertTrue(ShulkerTooltipData.screenTitleOf(purple).equals(expected), String.format(
                "an unnamed purple box should be titled %s, got %s", expected,
                ShulkerTooltipData.screenTitleOf(purple)));

        purple.set(DataComponents.CUSTOM_NAME, Component.literal("Loot"));
        assertTrue(ShulkerTooltipData.screenTitleOf(purple).equals("Loot"), "a renamed box lost its name");
    }

    /**
     * Faithful 32x draws its panel at 512 pixels. Sampling in the layout's 256 units read only the
     * top-left quarter, which here holds a darker title band, so the card took the band's colour.
     */
    private void hdPanelsAreSampledAcrossTheWholePanel() {
        int body = 0xFF3060C0;
        int slot = 0xFF101010;
        int band = 0xFFC03030;
        try (NativeImage image = new NativeImage(512, 512, true)) {
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    int color = 0;
                    if (x < 352 && y < 332) color = body;
                    if (x >= 16 && x < 340 && y >= 36 && y < 144) color = slot;
                    if (x < 176 && y < 36) color = band;
                    image.setPixel(x, y, color);
                }
            }
            int sampled = ResourcePackPanelColors.dominantColor(image, ResourcePackLayout.standard());
            assertTrue(Math.abs(((sampled >> 16) & 0xFF) - 0x30) <= 8 && Math.abs((sampled & 0xFF) - 0xC0) <= 8,
                    String.format("the HD panel's body is #%06X but it sampled as #%06X",
                            body & 0xFFFFFF, sampled & 0xFFFFFF));
        }
    }

    /** Immersive Interfaces leaves most of its panel clear for core shaders to paint. */
    private void mostlyClearPanelsAreNotPanels() {
        try (NativeImage clear = new NativeImage(256, 256, true);
             NativeImage solid = new NativeImage(256, 256, true)) {
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    clear.setPixel(x, y, (x < 40 && y < 40) ? 0xFF808080 : 0x00000000);
                    solid.setPixel(x, y, (x < 176 && y < 166) ? 0xFFC6C6C6 : 0x00000000);
                }
            }
            assertTrue(ResourcePackPanelColors.isMostlyTransparent(clear, ResourcePackLayout.standard()),
                    "a mostly clear panel was accepted as a panel");
            assertTrue(!ResourcePackPanelColors.isMostlyTransparent(solid, ResourcePackLayout.standard()),
                    "a solid panel was rejected as clear");
        }
    }

    /**
     * A one-tone panel closes with its own bottom rows. Repeating the row under the grid, as the
     * painter used to, ended the preview on plain body colour with no outline at all.
     */
    private void vanillaPanelsGetTheirBottomBorder() {
        Identifier id = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
        try (var stream = net.minecraft.client.Minecraft.getInstance().getResourceManager().open(id);
             NativeImage image = NativeImage.read(stream)) {
            int[][] cap = drawBottom(image);
            int last = cap.length - 1;
            assertTrue((cap[last][88] & 0xFFFFFF) == 0x000000, String.format(
                    "the preview should end on vanilla's black outline, but its last row is #%06X",
                    cap[last][88] & 0xFFFFFF));
            assertTrue((cap[last - 1][88] & 0xFFFFFF) == 0x555555, String.format(
                    "vanilla's shadow row is missing above the outline: #%06X", cap[last - 1][88] & 0xFFFFFF));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read vanilla's Shulker panel", e);
        }
    }

    /**
     * Colourful Containers lays the inventory's grey label tab over the left of the rows under
     * the grid, and over the grid's last row. Copying those rows drew the tab as a white bar.
     */
    private void aCoveringInventoryTabNeverReachesThePreview() {
        int outline = 0xFF000000;
        int body = 0xFF7426A9;
        int light = 0xFFA06AC5;
        int dark = 0xFF3E105D;
        int slot = 0xFF5E1F99;
        int grey = 0xFFC6C6C6;
        int white = 0xFFFFFFFF;
        try (NativeImage image = new NativeImage(256, 256, true)) {
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    int color = 0;
                    if (x < 176 && y < 166) color = y <= 75 ? body : (y == 76 ? white : grey);
                    if (x < 176 && y == 75) color = outline;
                    if (x < 176 && y < 75 && (x == 0 || y == 0)) color = outline;
                    if (x < 176 && y < 75 && (x == 1 || x == 2)) color = light;
                    image.setPixel(x, y, color);
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int x0 = 7 + col * 18;
                    int y0 = 17 + row * 18;
                    for (int y = y0; y < y0 + 18; y++) {
                        for (int x = x0; x < x0 + 18; x++) {
                            boolean topLeft = x == x0 || y == y0;
                            boolean bottomRight = x == x0 + 17 || y == y0 + 17;
                            image.setPixel(x, y, topLeft ? dark : bottomRight ? light : slot);
                        }
                    }
                }
            }
            // The tab: a black top edge on the last slot row's bottom edge, grey beneath.
            for (int y = 70; y <= 76; y++) {
                for (int x = 1; x < 64; x++) image.setPixel(x, y, y == 70 ? outline : y == 71 ? white : grey);
            }

            // The built-in Colourful Containers profile cuts its panel at row 75, the outline row.
            ResourcePackLayout standard = ResourcePackLayout.standard();
            ResourcePackLayout cut = new ResourcePackLayout("cut", 0, 0, 176, 75, 8, 18, 18, 9, 3,
                    176, 8, 7, 71, 7, 256, 256);
            for (ResourcePackLayout layout : List.of(standard, cut)) {
                int[][] cap = drawBottom(image, layout);
                // From the grid's last two rows down to the end of the preview.
                for (int y = cap.length - 9; y < cap.length; y++) {
                    for (int x = 0; x < 176; x++) {
                        int rgb = cap[y][x] & 0xFFFFFF;
                        assertTrue(rgb != (grey & 0xFFFFFF) && rgb != (white & 0xFFFFFF), String.format(
                                "the inventory tab reached the %s preview: #%06X at %d,%d", layout.id(), rgb, x, y));
                    }
                }
                assertTrue((cap[cap.length - 1][100] & 0xFFFFFF) == 0,
                        "the storage box's outline was not drawn under the " + layout.id() + " layout");
                assertTrue((cap[cap.length - 1][10] & 0xFFFFFF) == 0,
                        "the outline stops short under the tab with the " + layout.id() + " layout");
            }
        }
    }

    /**
     * A pack's layout file may put the grid at the very top of its panel, leaving no row above
     * it to rebuild a covered left border from. The left border then has to stay as drawn: the
     * row "above" the grid is outside the panel, and at slots.y = 1 outside the texture.
     */
    private void theBottomEdgeNeverReadsOutsideThePanel() {
        int body = 0xFF7426A9;
        int grey = 0xFFC6C6C6;
        // Grid from row 1, so the body row under it is 54 and the storage edge is row 58.
        ResourcePackLayout layout = new ResourcePackLayout("top-grid", 0, 0, 176, 166, 8, 1, 18, 9, 3,
                176, 8, 7, 54, 7, 256, 256);
        try (NativeImage image = new NativeImage(256, 256, true)) {
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    int color = 0;
                    if (x < 176 && y < 166) color = y < 58 ? body : (y == 58 ? 0xFF000000 : grey);
                    if (x < 64 && y >= 53 && y < 60 && x > 0) color = grey;
                    image.setPixel(x, y, color);
                }
            }
            PanelBottomEdge edge = PanelBottomEdge.analyse(image, layout);
            for (PanelBottomEdge.Blit blit : edge.plan(layout, 0, 61, 7, 8, 162, 170, 6)) {
                assertTrue(blit.v() >= layout.sourcePanelY()
                                && blit.v() + blit.height() <= layout.textureHeight(),
                        "the bottom edge reads rows " + blit.v() + "-" + (blit.v() + blit.height() - 1)
                                + ", outside the panel; " + edge);
            }
        }
    }

    /**
     * The last grid rows and the bottom cap of a standard-layout preview, laid out as the
     * painter lays them: grid source rows 18-71 then seven cap rows, 176 wide.
     */
    private static int[][] drawBottom(NativeImage image) {
        return drawBottom(image, ResourcePackLayout.standard());
    }

    private static int[][] drawBottom(NativeImage image, ResourcePackLayout layout) {
        int capY = layout.outputSlotY() + layout.rows() * layout.slotSize();
        int[][] canvas = new int[capY + layout.bottomCapHeight()][176];
        for (int y = layout.outputSlotY(); y < capY; y++) {
            for (int x = 0; x < 176; x++) {
                canvas[y][x] = image.getPixel(x, layout.sourceSlotY() + y - layout.outputSlotY());
            }
        }
        PanelBottomEdge edge = PanelBottomEdge.analyse(image, layout);
        for (PanelBottomEdge.Blit blit : edge.plan(layout, 0, capY, layout.bottomCapHeight(),
                layout.outputSlotX(), layout.columns() * layout.slotSize(),
                layout.sourceRightX(), layout.sourceRightWidth())) {
            for (int j = 0; j < blit.height(); j++) {
                for (int i = 0; i < blit.width(); i++) {
                    canvas[blit.y() + j][blit.x() + i] = image.getPixel(blit.u() + i, blit.v() + j);
                }
            }
        }
        return canvas;
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static List<ShulkerGuiRules.Rule> ini(String text) {
        try {
            return ShulkerGuiRules.parseIni(INI, new BufferedReader(new StringReader(text)));
        } catch (Exception e) {
            throw new AssertionError("could not parse the INI fixture", e);
        }
    }

    private static ShulkerGuiRules.Rule properties(String text, boolean optiGuiRules) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(text));
            return ShulkerGuiRules.parseProperties(PROPERTIES, properties, optiGuiRules);
        } catch (Exception e) {
            throw new AssertionError("could not parse the properties fixture", e);
        }
    }
}

package com.bettershulker.gametest;

import com.bettershulker.client.render.ResourcePackContainerTextures;
import com.bettershulker.client.render.ResourcePackLayoutProfiles;
import com.bettershulker.client.render.ResourcePackPanelColors;
import com.bettershulker.client.render.TooltipPalette;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;
import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * Checks that the Modern card still tells one dyed box from another while a resource pack that
 * recolours the Shulker GUI per dye is active.
 *
 * <p>The card takes its colour from the pack's panel. Sampling that panel used to count the
 * storage slots too, and they are the larger part of the crop, so every card came back as the
 * pack's slot shade: a dark, desaturated tone that put red next to brown and green next to lime.
 * The boxes were still drawn correctly - only the tooltip lost track of which one it belonged
 * to.</p>
 *
 * <p>This needs a pack supplying per-dye Shulker panels in the run directory's
 * {@code resourcepacks} folder, selected in {@code options.txt}; it was written against
 * Recolourful Containers. The run directory is wiped before each run unless the task is invoked
 * with {@code -x :fabric:deleteGameTestRunDir}, so without that the pack is not there and the
 * checks below have nothing to say - they are skipped rather than failed, and the log says
 * which.</p>
 */
public class ModernTooltipPackGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("bettershulker-test");

    /** The box under test always sits here, so every screenshot frames the card the same way. */
    private static final int BOX_SLOT = 0;

    /** Two dyes a recolouring pack keeps close together, and two it keeps apart. */
    private static final DyeColor[] DYES = {
            DyeColor.RED,
            DyeColor.BROWN,
            DyeColor.BLUE,
            DyeColor.YELLOW
    };

    /**
     * Closest two cards are allowed to sit, as a distance in RGB.
     *
     * <p>Red and brown were 30 apart under the old sampling, which is close enough that the two
     * tooltips read as the same colour.</p>
     */
    private static final double MIN_CARD_SEPARATION = 40.0;

    /** Card face left after the lattice and the items are drawn over it, in screen pixels. */
    private static final int MIN_CARD_PIXELS = 500;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            singleplayer.getServer().runCommand("gamemode creative");

            evictionNeverHandsBackTheWrongPanel(context);
            evictionNeverHandsBackTheWrongPanelColour(context);
            evictionNeverHandsBackTheWrongLayout(context);

            if (!aPackSuppliesAPanelForEveryDye(context)) return;

            Map<DyeColor, Integer> cards = everyCardColour(context, singleplayer);
            cardsKeepTheirDyesHue(cards);
            cardsStayTellableApart(cards);
            theEnderCardIgnoresThePack(context, singleplayer);
        }
    }

    // =========================================================================
    //  Tests
    // =========================================================================

    /**
     * A resolution survives being pushed out of the panel cache.
     *
     * <p>That cache is keyed partly by the container's name and now evicts its coldest entry
     * rather than emptying itself, so a renamed box can push an earlier resolution out. Eviction
     * is only ever allowed to cost a recomputation, never to change the answer, which is what
     * this pins.</p>
     *
     * <p>The two probes are an Ender Chest and a Shulker Box, because those resolve to different
     * panels whether or not a pack is installed - dyes only diverge under one, and an earlier
     * version of this test compared two keys that a packless run resolves identically, so it
     * passed against a cache rigged to return the wrong entry. The check that the probes differ
     * at all is what keeps that from going unnoticed again.</p>
     */
    private void evictionNeverHandsBackTheWrongPanel(ClientGameTestContext context) {
        String shulkerBefore = context.computeOnClient(client ->
                ResourcePackContainerTextures.resolve(DyeColor.RED, false, "Eviction probe").toString());
        String enderBefore = context.computeOnClient(client ->
                ResourcePackContainerTextures.resolve(null, true, "Eviction probe").toString());

        assertTrue(!shulkerBefore.equals(enderBefore),
                "the eviction probes resolve to the same panel, so this test cannot detect a"
                        + " cache handing back the wrong one");

        // Comfortably past the 64-entry ceiling, so neither probe can have survived in the cache.
        context.computeOnClient(client -> {
            for (int i = 0; i < 200; i++) {
                ResourcePackContainerTextures.resolve(DyeColor.RED, false, "Filler " + i);
            }
            return null;
        });

        String shulkerAfter = context.computeOnClient(client ->
                ResourcePackContainerTextures.resolve(DyeColor.RED, false, "Eviction probe").toString());
        String enderAfter = context.computeOnClient(client ->
                ResourcePackContainerTextures.resolve(null, true, "Eviction probe").toString());

        assertTrue(shulkerBefore.equals(shulkerAfter), String.format(
                "the Shulker panel changed after being evicted:%n  before %s%n  after  %s",
                shulkerBefore, shulkerAfter));
        assertTrue(enderBefore.equals(enderAfter), String.format(
                "the Ender panel changed after being evicted:%n  before %s%n  after  %s",
                enderBefore, enderAfter));
    }

    /**
     * A sampled panel colour survives being pushed out of its cache.
     *
     * <p>{@link ResourcePackPanelColors} evicts least-recently-used too, and its misses are the
     * expensive kind - a miss decodes the whole texture to count pixels. The probes are a real
     * texture, which gets sampled, against a missing one, which falls back, so the two answers
     * differ by construction and a swapped entry is visible.</p>
     */
    private void evictionNeverHandsBackTheWrongPanelColour(ClientGameTestContext context) {
        Identifier real = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
        Identifier missing = Identifier.fromNamespaceAndPath("bettershulker", "textures/gui/absent_panel.png");
        int fallback = 0x00FACADE;

        int realBefore = context.computeOnClient(client ->
                ResourcePackPanelColors.dominantColor(real, fallback));
        int missingBefore = context.computeOnClient(client ->
                ResourcePackPanelColors.dominantColor(missing, fallback));

        assertTrue(realBefore != missingBefore,
                "the colour probes agree, so this test cannot detect a cache handing back the"
                        + " wrong entry");

        // Missing textures fail fast, so the cache can be overrun without decoding 200 images.
        context.computeOnClient(client -> {
            for (int i = 0; i < 200; i++) {
                ResourcePackPanelColors.dominantColor(Identifier.fromNamespaceAndPath(
                        "bettershulker", "textures/gui/filler_" + i + ".png"), fallback);
            }
            return null;
        });

        int realAfter = context.computeOnClient(client ->
                ResourcePackPanelColors.dominantColor(real, fallback));
        int missingAfter = context.computeOnClient(client ->
                ResourcePackPanelColors.dominantColor(missing, fallback));

        assertTrue(realBefore == realAfter, String.format(
                "a sampled panel colour changed after eviction: before %08X, after %08X",
                realBefore, realAfter));
        assertTrue(missingBefore == missingAfter, String.format(
                "a fallback panel colour changed after eviction: before %08X, after %08X",
                missingBefore, missingAfter));
    }

    /**
     * A resolved layout survives being pushed out of its cache.
     *
     * <p>{@link ResourcePackLayoutProfiles} was previously unbounded and is now an LRU, so this
     * is the first time eviction can happen there at all. The probes lean on the built-in OptiGUI
     * profile, which is the one texture that resolves to something other than the standard
     * geometry without a pack having to supply anything.</p>
     */
    private void evictionNeverHandsBackTheWrongLayout(ClientGameTestContext context) {
        Identifier profiled = Identifier.fromNamespaceAndPath("optigui", "gui/shulkers/shulker/shulker.png");
        Identifier standard = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");

        String profiledBefore = context.computeOnClient(client ->
                ResourcePackLayoutProfiles.resolveBase(profiled).toString());
        String standardBefore = context.computeOnClient(client ->
                ResourcePackLayoutProfiles.resolveBase(standard).toString());

        assertTrue(!profiledBefore.equals(standardBefore),
                "the layout probes resolve alike, so this test cannot detect a cache handing back"
                        + " the wrong entry");

        context.computeOnClient(client -> {
            for (int i = 0; i < 200; i++) {
                ResourcePackLayoutProfiles.resolveBase(Identifier.fromNamespaceAndPath(
                        "bettershulker", "textures/gui/filler_" + i + ".png"));
            }
            return null;
        });

        String profiledAfter = context.computeOnClient(client ->
                ResourcePackLayoutProfiles.resolveBase(profiled).toString());
        String standardAfter = context.computeOnClient(client ->
                ResourcePackLayoutProfiles.resolveBase(standard).toString());

        assertTrue(profiledBefore.equals(profiledAfter), String.format(
                "a layout changed after eviction:%n  before %s%n  after  %s",
                profiledBefore, profiledAfter));
        assertTrue(standardBefore.equals(standardAfter), String.format(
                "a layout changed after eviction:%n  before %s%n  after  %s",
                standardBefore, standardAfter));
    }

    /**
     * Whether there is a pack to test against at all, and that it gives each dye its own panel.
     *
     * <p>A missing pack is a missing fixture rather than a broken mod, so it stops the run here
     * instead of failing it. A pack that hands one dye another dye's panel is a real fault and
     * does fail.</p>
     */
    private boolean aPackSuppliesAPanelForEveryDye(ClientGameTestContext context) {
        for (DyeColor color : DYES) {
            String panel = context.computeOnClient(client ->
                    ResourcePackContainerTextures.resolve(color, false).exactCustomGui()
                            ? ResourcePackContainerTextures.resolve(color, false).texture().toString()
                            : null);
            if (panel == null) {
                LOGGER.warn("Skipping the Modern card colour checks: no resource pack supplies a {} "
                        + "Shulker panel. Put one in the run directory's resourcepacks folder, select "
                        + "it in options.txt, and run with -x :fabric:deleteGameTestRunDir so it "
                        + "survives.", color.getName());
                return false;
            }
            assertTrue(panel.contains(color.getName()),
                    "the pack panel for " + color.getName() + " is " + panel + ", which is another dye's");
        }
        return true;
    }

    /**
     * Every card must lean the same way its dye does.
     *
     * <p>Distance alone cannot see this: a card whose red and blue channels are exchanged is still
     * far from every other card, it is just the wrong colour. Ordering the channels the way the
     * dye orders them is what catches a red box painted purple.</p>
     */
    private void cardsKeepTheirDyesHue(Map<DyeColor, Integer> cards) {
        cards.forEach((color, card) -> {
            int dye = color.getTextureDiffuseColor();
            assertTrue(channelOrder(card).equals(channelOrder(dye)), String.format(
                    "the %s card is #%06X, whose channels run %s while the dye's #%06X run %s",
                    color.getName(), card & 0xFFFFFF, channelOrder(card),
                    dye & 0xFFFFFF, channelOrder(dye)));
        });
    }

    /** Channels from strongest to weakest, which is a colour's hue stripped of its brightness. */
    private static String channelOrder(int color) {
        record Channel(String name, int value) {}
        List<Channel> channels = List.of(
                new Channel("r", (color >> 16) & 0xFF),
                new Channel("g", (color >> 8) & 0xFF),
                new Channel("b", color & 0xFF));
        return channels.stream()
                .sorted((a, b) -> b.value() - a.value())
                .map(Channel::name)
                .reduce("", String::concat);
    }

    /**
     * An Ender Chest keeps its own colours no matter what the pack does.
     *
     * <p>Its screen is an ordinary six-row chest, so the only panel a pack has for it is the one
     * every chest and barrel shares. Taking that put a wooden card behind an Ender Chest under a
     * pack that paints chests wooden.</p>
     */
    private void theEnderCardIgnoresThePack(ClientGameTestContext context,
                                            TestSingleplayerContext singleplayer) {
        GameTestSupport.clearEnderChest(singleplayer);
        GameTestSupport.setEnderSlot(singleplayer, 0, new ItemStack(Items.STONE, 12));
        givePlayer(singleplayer, BOX_SLOT, new ItemStack(Items.ENDER_CHEST));
        openInventory(context);
        hoverInventorySlot(context, BOX_SLOT);
        context.waitTicks(20);

        int fill = context.computeOnClient(client ->
                new TooltipPalette(true, null, null).getModernPanelFill());
        Path screenshot = context.takeScreenshot("modern-card-ender");
        assertTrue(countPixelsNear(screenshot, fill) >= MIN_CARD_PIXELS, String.format(
                "the Ender card should be Better Shulker's own #%06X, but that colour is barely on "
                        + "screen in %s; a pack panel has been let in", fill & 0xFFFFFF,
                screenshot.getFileName()));

        closeScreen(context);
    }

    /** Every card must stay far enough from every other to read as its own box. */
    private void cardsStayTellableApart(Map<DyeColor, Integer> cards) {
        List<Map.Entry<DyeColor, Integer>> entries = new ArrayList<>(cards.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Map.Entry<DyeColor, Integer> a = entries.get(i);
                Map.Entry<DyeColor, Integer> b = entries.get(j);
                double distance = colorDistance(a.getValue(), b.getValue());
                assertTrue(distance >= MIN_CARD_SEPARATION, String.format(
                        "the %s and %s cards are only %.1f apart (#%06X against #%06X); they read as "
                                + "the same colour",
                        a.getKey().getName(), b.getKey().getName(), distance,
                        a.getValue() & 0xFFFFFF, b.getValue() & 0xFFFFFF));
            }
        }
    }

    // =========================================================================
    //  Driving the client
    // =========================================================================

    /**
     * Hovers each box in turn and returns the colour its card was painted with.
     *
     * <p>The colour is read back off the palette, then found again in a screenshot of the frame
     * that palette produced, so a card that never reached the screen cannot pass.</p>
     */
    private Map<DyeColor, Integer> everyCardColour(ClientGameTestContext context,
                                                   TestSingleplayerContext singleplayer) {
        Map<DyeColor, Integer> cards = new LinkedHashMap<>();
        for (DyeColor color : DYES) {
            ItemStack stack = boxHolding(Items.DYED_SHULKER_BOX.pick(color), new ItemStack(Items.STONE, 1));

            givePlayer(singleplayer, BOX_SLOT, stack);
            openInventory(context);
            hoverInventorySlot(context, BOX_SLOT);

            int fill = context.computeOnClient(client -> modernCardFill(stack));
            Path screenshot = context.takeScreenshot("modern-card-" + color.getName());
            assertTrue(countPixelsNear(screenshot, fill) >= MIN_CARD_PIXELS, String.format(
                    "the %s card's colour #%06X is barely on screen in %s",
                    color.getName(), fill & 0xFFFFFF, screenshot.getFileName()));

            cards.put(color, fill);
            closeScreen(context);
        }
        return cards;
    }

    /**
     * Colour the Modern card is painted with for a box, taken from the palette the tooltip builds.
     *
     * <p>Mirrors {@code ShulkerTooltipComponent#getModernPackPanelTexture} for a dyed box: the
     * pack's panel is handed to the palette only when the pack actually supplies one.</p>
     */
    private static int modernCardFill(ItemStack box) {
        DyeColor color = ContainerHelper.getShulkerColor(box);
        ResourcePackContainerTextures.Panel panel =
                ResourcePackContainerTextures.resolve(color, false);
        return new TooltipPalette(false, color, panel.suppliedByPack() ? panel.texture() : null)
                .getModernPanelFill();
    }

    /** A dyed Shulker Box holding one item, so the preview is not suppressed as empty. */
    private static ItemStack boxHolding(Item box, ItemStack content) {
        ItemStack stack = new ItemStack(box);
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(0, content);
        ContainerHelper.setContainerContents(stack, contents);
        return stack;
    }

    // =========================================================================
    //  Reading the screenshot
    // =========================================================================

    /** Pixels within a channel or two of {@code color}, which covers any rounding on the way out. */
    private static int countPixelsNear(Path screenshot, int color) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            int found = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (colorDistance(image.getRGB(x, y), color) <= 3.0) found++;
                }
            }
            return found;
        } catch (Exception e) {
            throw new AssertionError("could not read screenshot " + screenshot, e);
        }
    }

    private static double colorDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}

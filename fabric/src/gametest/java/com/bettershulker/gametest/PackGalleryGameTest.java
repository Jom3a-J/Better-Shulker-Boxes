package com.bettershulker.gametest;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.render.ResourcePackContainerTextures;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.bettershulker.gametest.GameTestSupport.closeScreen;
import static com.bettershulker.gametest.GameTestSupport.givePlayer;
import static com.bettershulker.gametest.GameTestSupport.hoverInventorySlot;
import static com.bettershulker.gametest.GameTestSupport.openInventory;

/**
 * Screenshots every Shulker tooltip under every resource pack in the run directory.
 *
 * <p>Nothing here asserts: what a pack's panel should look like is a judgement made by looking.
 * The test turns each pack on alone, hovers an unnamed and a renamed dyed box, another dye and an
 * undyed box in both tooltip styles, and logs which texture each resolved to. With no packs in
 * the run directory's {@code resourcepacks} folder it does nothing, so the normal suite is
 * unaffected; copy packs there and run with {@code -x :fabric:deleteGameTestRunDir}.</p>
 */
public class PackGalleryGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("bettershulker-gallery");
    /** Top row of the main inventory, so the whole tooltip fits on screen below the cursor. */
    private static final int BOX_SLOT = 9;

    private record Box(String label, ItemStack stack) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        String[] packs = context.computeOnClient(client -> {
            File[] files = new File(client.gameDirectory, "resourcepacks").listFiles(
                    file -> file.getName().endsWith(".zip"));
            return files == null ? new String[0] : Arrays.stream(files).map(File::getName).sorted().toArray(String[]::new);
        });
        if (packs.length == 0) {
            LOGGER.info("No resource packs in the run directory; skipping the pack gallery.");
            return;
        }

        BetterShulkerConfig.TooltipStyle savedStyle = BetterShulkerConfig.getTooltipStyle();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            singleplayer.getServer().runCommand("gamemode creative");

            capturePack(context, singleplayer, null);
            for (String pack : packs) capturePack(context, singleplayer, pack);
        } finally {
            BetterShulkerConfig.setTooltipStyle(savedStyle);
            selectOnly(context, null);
        }
    }

    private void capturePack(ClientGameTestContext context, TestSingleplayerContext singleplayer, String pack) {
        selectOnly(context, pack);
        String packLabel = pack == null ? "none" : pack.replace(".zip", "");
        List<Box> boxes = context.computeOnClient(client -> boxes());

        for (BetterShulkerConfig.TooltipStyle style : BetterShulkerConfig.TooltipStyle.values()) {
            BetterShulkerConfig.setTooltipStyle(style);
            for (Box box : boxes) {
                givePlayer(singleplayer, BOX_SLOT, box.stack());
                openInventory(context);
                hoverInventorySlot(context, BOX_SLOT);
                context.waitTicks(5);
                String resolved = context.computeOnClient(client -> {
                    DyeColor color = ContainerHelper.getShulkerColor(box.stack());
                    ResourcePackContainerTextures.Panel panel = ResourcePackContainerTextures.resolve(
                            color, false, ShulkerTooltipData.screenTitleOf(box.stack()));
                    return panel.texture() + " pack=" + panel.suppliedByPack() + " exact=" + panel.exactCustomGui()
                            + " title='" + ShulkerTooltipData.screenTitleOf(box.stack()) + "'";
                });
                if (style == BetterShulkerConfig.TooltipStyle.VANILLA) {
                    LOGGER.info("[gallery] {} | {} -> {}", packLabel, box.label(), resolved);
                }
                context.takeScreenshot("gallery-" + packLabel + "-" + style.name().toLowerCase() + "-" + box.label());
                closeScreen(context);
            }
        }
    }

    private static List<Box> boxes() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(new Box("purple", filled(new ItemStack(Items.DYED_SHULKER_BOX.pick(DyeColor.PURPLE)))));
        ItemStack named = filled(new ItemStack(Items.DYED_SHULKER_BOX.pick(DyeColor.PURPLE)));
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Loot"));
        boxes.add(new Box("purple-named", named));
        boxes.add(new Box("lime", filled(new ItemStack(Items.DYED_SHULKER_BOX.pick(DyeColor.LIME)))));
        boxes.add(new Box("undyed", filled(new ItemStack(Items.SHULKER_BOX))));
        return boxes;
    }

    private static ItemStack filled(ItemStack box) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(0, new ItemStack(Items.DIAMOND, 12));
        contents.set(4, new ItemStack(Items.IRON_INGOT, 64));
        contents.set(13, new ItemStack(Items.OAK_LOG, 32));
        contents.set(26, new ItemStack(Items.ENDER_PEARL, 16));
        ContainerHelper.setContainerContents(box, contents);
        return box;
    }

    /** Leaves the built-in and mod packs selected and adds one folder pack, or none. */
    private static void selectOnly(ClientGameTestContext context, String pack) {
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            PackRepository repository = client.getResourcePackRepository();
            repository.reload();
            List<String> ids = new ArrayList<>(repository.getSelectedIds().stream()
                    .filter(id -> !id.startsWith("file/")).toList());
            if (pack != null) ids.add("file/" + pack);
            repository.setSelected(ids);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone(), 2400);
        context.waitTicks(20);
        if (pack != null) {
            boolean active = context.computeOnClient(client ->
                    client.getResourcePackRepository().getSelectedIds().contains("file/" + pack));
            LOGGER.info("[gallery] pack {} active={}", pack, active);
        }
    }
}

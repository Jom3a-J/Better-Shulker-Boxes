package com.bettershulker.gametest;

import com.bettershulker.client.interact.ContainerActions;
import com.bettershulker.util.ContainerHelper;
import com.bettershulker.util.ContainerTransfer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static com.bettershulker.gametest.GameTestSupport.assertTrue;

/**
 * Pins the fast container queries to the slow ones they replaced.
 *
 * <p>{@link ContainerHelper#canInsertInto}, {@link ContainerHelper#hasAnyContents} and
 * {@link ContainerHelper#countOccupiedSlots} read a box's component directly, because the paths
 * that ask them run for every box on screen on every frame and the old route built all 27 stacks
 * to answer. Reading the component is only worth doing while it gives the same answer, so each
 * case below is put to both implementations and the two are required to agree.</p>
 *
 * <p>These are pure functions over item stacks, but they need a live item registry to compare
 * components at all, so they run inside the client rather than as an ordinary unit test.</p>
 */
public class ContainerCapacityGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();

            String failure = context.computeOnClient(client -> checkEveryCase());
            assertTrue(failure == null, failure);
        }
    }

    // =========================================================================
    //  Cases
    // =========================================================================

    /** Runs every case, returning the first disagreement or null when all of them agree. */
    private static String checkEveryCase() {
        ItemStack stone = new ItemStack(Items.STONE, 1);
        ItemStack renamedStone = new ItemStack(Items.STONE, 1);
        renamedStone.set(DataComponents.CUSTOM_NAME, Component.literal("Not just stone"));

        List<String> failures = new ArrayList<>();

        // A box that has never been filled carries no component at all - the case the fast path
        // short-circuits, and the one a freshly crafted box is actually in.
        check(failures, "untouched box", new ItemStack(Items.SHULKER_BOX), stone);
        check(failures, "box with an empty component", boxOf(), stone);
        check(failures, "one loose item", boxOf(new ItemStack(Items.DIRT, 1)), stone);

        // A partial stack of the carried item: the fast path has to build this entry to compare
        // its components, so it is the case that proves the component check still happens.
        check(failures, "room in a matching stack", full(63, Items.STONE), stone);
        check(failures, "matching stack at its limit", full(64, Items.STONE), stone);
        check(failures, "full box of something else", full(64, Items.DIRT), stone);

        // Same item, different components. These must not be treated as stackable, which is the
        // mistake a comparison on the item alone would make.
        check(failures, "full box of renamed stone", full(63, renamedStone), stone);
        check(failures, "carrying renamed stone", full(63, Items.STONE), renamedStone);

        // Unstackable entries: their limit comes from the stored entry, not from the item.
        check(failures, "full box of swords", full(1, Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_SWORD));

        // A hole partway through the component, rather than only trailing empties.
        NonNullList<ItemStack> gapped = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < 27; i++) {
            if (i != 13) gapped.set(i, new ItemStack(Items.DIRT, 64));
        }
        check(failures, "full box with a gap", boxFrom(gapped), stone);

        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    /** Puts one box and one carried stack to both implementations of all three queries. */
    private static void check(List<String> failures, String name, ItemStack box, ItemStack carried) {
        NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(box);

        boolean fastInsert = ContainerHelper.canInsertInto(box, carried);
        boolean slowInsert = ContainerTransfer.canInsert(contents, carried);
        if (fastInsert != slowInsert) {
            failures.add(name + ": canInsertInto said " + fastInsert + ", canInsert said " + slowInsert);
        }

        boolean fastAny = ContainerHelper.hasAnyContents(box);
        boolean slowAny = false;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) {
                slowAny = true;
                break;
            }
        }
        if (fastAny != slowAny) {
            failures.add(name + ": hasAnyContents said " + fastAny + ", the copy said " + slowAny);
        }

        int fastCount = ContainerHelper.countOccupiedSlots(box);
        int slowCount = ContainerActions.countNonNullSlots(contents);
        if (fastCount != slowCount) {
            failures.add(name + ": countOccupiedSlots said " + fastCount + ", the copy said " + slowCount);
        }
    }

    // =========================================================================
    //  Building boxes
    // =========================================================================

    private static ItemStack boxOf(ItemStack... stacks) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length && i < 27; i++) {
            contents.set(i, stacks[i]);
        }
        return boxFrom(contents);
    }

    /** All 27 slots filled with {@code count} of the same thing. */
    private static ItemStack full(int count, net.minecraft.world.item.Item item) {
        return full(count, new ItemStack(item));
    }

    private static ItemStack full(int count, ItemStack template) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < 27; i++) {
            contents.set(i, template.copyWithCount(count));
        }
        return boxFrom(contents);
    }

    private static ItemStack boxFrom(NonNullList<ItemStack> contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ContainerHelper.setContainerContents(box, contents);
        return box;
    }
}

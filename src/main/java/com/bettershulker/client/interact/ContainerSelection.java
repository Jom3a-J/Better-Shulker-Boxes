package com.bettershulker.client.interact;

import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.EnderChestCache;
import com.bettershulker.client.ClientKeybinds;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.core.NonNullList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Which slot of a container preview the selection square names, and how it moves.
 *
 * <p>The square is the target every extraction reads, so it only ever rests on a slot holding
 * something, and it belongs to one container at a time - both it and the multi-select set are
 * bare indices, and applying one box's indices to another extracts the wrong stacks.</p>
 */
public final class ContainerSelection {

    /** No container under the tooltip; distinct from every MenuSlotRef value. */
    public static final int NO_ACTIVE_CONTAINER = Integer.MIN_VALUE;

    /** Every carried container shares one reference; there is no slot to tell them apart by. */
    public static final int CARRIED_CONTAINER = Integer.MIN_VALUE + 1;

    /** Which container the selection currently belongs to, so a switch can be noticed. */
    private static int lastActiveContainerRef = NO_ACTIVE_CONTAINER;

    private ContainerSelection() {}

    /** Forgets which container the selection belonged to, when the screen closes. */
    public static void reset() {
        lastActiveContainerRef = NO_ACTIVE_CONTAINER;
    }

        public static NonNullList<ItemStack> contentsOf(ItemStack containerStack) {
        if (ContainerHelper.isShulkerBox(containerStack)) {
            return ContainerHelper.getContainerContents(containerStack);
        }
        if (ContainerHelper.isEnderChest(containerStack)) {
            NonNullList<ItemStack> cached = EnderChestCache.getEnderChestContents();
            if (cached != null) return cached;
        }
        return NonNullList.withSize(27, ItemStack.EMPTY);
    }

        public static int nextSlot(int current, int delta, ItemStack containerStack) {
        if (ClientKeybinds.isCompactModeActive()) {
            NonNullList<ItemStack> contents = contentsOf(containerStack);
            List<Integer> visibleIndices = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (!stack.isEmpty()) {
                    boolean found = false;
                    for (int idx : visibleIndices) {
                        if (ItemStack.isSameItemSameComponents(contents.get(idx), stack)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        visibleIndices.add(i);
                    }
                }
            }
            if (visibleIndices.isEmpty()) {
                visibleIndices.add(0);
            } else {
                visibleIndices.sort((a, b) -> {
                    int countCompare = Integer.compare(
                            mergedCount(contents, b),
                            mergedCount(contents, a));
                    return countCompare != 0 ? countCompare : Integer.compare(a, b);
                });
                if (visibleIndices.size() > 5) {
                    visibleIndices = new ArrayList<>(visibleIndices.subList(0, 5));
                }
            }

            int idx = visibleIndices.indexOf(current);
            if (idx == -1) {
                // If current slot index is not a primary slot index of any group,
                // find the group that contains this slot and start from its primary slot!
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack stack = contents.get(i);
                    if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, contents.get(current))) {
                        for (int primaryIdx : visibleIndices) {
                            if (ItemStack.isSameItemSameComponents(contents.get(primaryIdx), stack)) {
                                idx = visibleIndices.indexOf(primaryIdx);
                                break;
                            }
                        }
                        break;
                    }
                }
                if (idx == -1) {
                    idx = 0;
                }
            }
            int nextIdx = Math.floorMod(idx + delta, visibleIndices.size());
            return visibleIndices.get(nextIdx);
        } else {
            int size = slotCount(containerStack);
            if (size <= 0) return current;
            // Wrap, rather than snap to an end. A vertical step of +-9 that left the grid used to
            // land on slot 26 or slot 0, throwing the square into a far corner; wrapping carries
            // it down its own column, which is what an arrow key promises.
            int plainMove = Math.floorMod(current + delta, size);

            // Empty slots are not worth stopping on: the square only ever names an extraction
            // target, so a box holding three items used to need two dozen presses to cross. Step
            // by the same delta until something occupied turns up, which keeps a vertical move
            // inside its column and a horizontal one along its row order.
            NonNullList<ItemStack> contents = contentsOf(containerStack);
            int candidate = plainMove;
            while (candidate != current) {
                if (candidate < contents.size() && !contents.get(candidate).isEmpty()) {
                    return candidate;
                }
                candidate = Math.floorMod(candidate + delta, size);
            }

            // Nothing else along that path holds anything - an empty column, or an empty box.
            // Stay on the current slot while it still has an item, so a lone stack cannot be
            // stepped off; otherwise fall back to the plain grid move.
            return current >= 0 && current < contents.size() && !contents.get(current).isEmpty()
                    ? current
                    : plainMove;
        }
    }

        public static int mergedCount(NonNullList<ItemStack> contents, int slot) {
        if (slot < 0 || slot >= contents.size()) return 0;
        ItemStack displayStack = contents.get(slot);
        if (displayStack.isEmpty()) return 0;
        int total = 0;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(displayStack, stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

        public static int slotCount(ItemStack containerStack) {
        return ContainerHelper.isContainer(containerStack) ? 27 : 0;
    }

        public static boolean hasContents(ItemStack containerStack) {
        for (ItemStack stack : contentsOf(containerStack)) {
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

        public static boolean hasMatchingItem(ItemStack containerStack, ItemStack target) {
        return findMatchingIndex(containerStack, target) != -1;
    }

        public static int findMatchingIndex(ItemStack containerStack, ItemStack target) {
        return ContainerHelper.findMatchingItem(contentsOf(containerStack), target);
    }

    /**
     * Re-points the selection at the container the tooltip now belongs to.
     *
     * <p>Both the square and the multi-select set are plain slot indices with no memory of which
     * box they were chosen in, so moving to another one used to carry them over: the square could
     * sit on an empty slot, and {@code E} would extract whatever happened to occupy the marked
     * indices in the new box rather than the stacks that were picked.</p>
     *
     * <p>Keyed on the slot the container sits in rather than the stack, because inserting or
     * extracting rewrites the stack's contents component and would otherwise read as a switch
     * mid-interaction.</p>
     */
        public static void retargetTo(int activeRef, ItemStack container, boolean dragging) {
        if (activeRef == NO_ACTIVE_CONTAINER || container.isEmpty()) return;
        if (activeRef == lastActiveContainerRef) return;
        // A drag pulls from the carried box while the pointer sweeps across the inventory, so it
        // passes over other boxes on the way. Re-pointing the square there would change which
        // stack the rest of the drag extracts, mid-gesture.
        if (dragging) return;

        BetterShulkerClient.clearSelectedSlotsSet();

        NonNullList<ItemStack> contents = contentsOf(container);
        int firstOccupied = -1;
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) {
                firstOccupied = i;
                break;
            }
        }
        if (firstOccupied < 0 && ContainerHelper.isEnderChest(container)) {
            // An Ender Chest reads as empty until its first sync lands. Leave the reference unset
            // so the selection is placed against real contents once they arrive.
            return;
        }
        lastActiveContainerRef = activeRef;

        // Hold the position when the new box has something there too, which keeps the square
        // where the eye left it between two similar boxes.
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (selected >= 0 && selected < contents.size() && !contents.get(selected).isEmpty()) return;
        BetterShulkerClient.setSelectedSlotIndex(Math.max(0, firstOccupied));
    }

        public static int extractionIndex(ItemStack containerStack) {
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (slotHasItem(containerStack, selected)) return selected;
        return findExtractionIndex(containerStack);
    }

        public static boolean slotHasItem(ItemStack containerStack, int slot) {
        if (slot < 0) return false;
        var contents = contentsOf(containerStack);
        return slot < contents.size() && !contents.get(slot).isEmpty();
    }

        public static int findExtractionIndex(ItemStack containerStack) {
        var contents = contentsOf(containerStack);
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) return i;
        }
        return -1;
    }
}

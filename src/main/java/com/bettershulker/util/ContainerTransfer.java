package com.bettershulker.util;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moving items into and out of a container's contents.
 *
 * <p>Every method works on a plain slot list rather than a container item, so the same logic
 * serves a Shulker Box, whose contents live in a data component, and an Ender Chest, whose
 * contents live on the player. Nothing here reads or writes the world - the caller decides
 * where the modified list goes, and whether it goes anywhere at all.</p>
 *
 * <p>Insertion picks its empty slot by proximity to items of the same kind, so a box stays
 * roughly sorted as it fills instead of scattering a stack wherever the first gap happens to
 * be.</p>
 */
public final class ContainerTransfer {

    private ContainerTransfer() {}

    /**
     * Attempts to insert an item stack into the container contents.
     *
     * <p><b>Insertion strategy (matches vanilla hopper/bundle behavior):</b></p>
     * <ol>
     *   <li><b>Merge pass</b> — Scan existing stacks for compatible items that
     *       are not yet at max stack size. Fill them up first to avoid
     *       fragmenting inventory.</li>
     *   <li><b>Empty-slot pass</b> — If items remain after merging, place them
     *       into the first available empty slot.</li>
     * </ol>
     *
     * <p><b>Anti-exploit:</b> Shulker boxes cannot be inserted into shulker
     * boxes (prevents infinite recursive nesting). This check runs before any
     * insertion attempt and immediately returns the full stack unchanged.</p>
     *
     * @param contents   the container's mutable slot list (will be modified in place)
     * @param toInsert   the stack to insert (will NOT be modified — caller should
     *                   use the return value to determine what remains)
     * @param singleItem if {@code true}, only insert a single item regardless
     *                   of stack size (used for right-click / precision mode)
     * @return the remaining items that could not be inserted. Returns
     *         {@link ItemStack#EMPTY} if everything was inserted.
     */
    public static ItemStack tryInsert(NonNullList<ItemStack> contents, ItemStack toInsert, boolean singleItem) {
        if (toInsert.isEmpty()) return ItemStack.EMPTY;
        if (ContainerHelper.isShulkerBox(toInsert)) return toInsert.copy();

        int insertCount = singleItem ? 1 : toInsert.getCount();
        int maxStackSize = toInsert.getMaxStackSize();
        int originalCount = toInsert.getCount();

        for (int i = 0; i < contents.size() && insertCount > 0; i++) {
            ItemStack existing = contents.get(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, toInsert)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            int transfer = Math.min(insertCount, space);
            existing.grow(transfer);
            insertCount -= transfer;
        }

        while (insertCount > 0) {
            int bestSlot = findSmartMergeEmptySlot(contents, toInsert);
            if (bestSlot == -1) break;
            int place = Math.min(insertCount, maxStackSize);
            contents.set(bestSlot, toInsert.copyWithCount(place));
            insertCount -= place;
        }

        int inserted = (singleItem ? 1 : originalCount) - insertCount;
        if (inserted <= 0) return toInsert.copy();
        int remaining = originalCount - inserted;
        if (remaining <= 0) return ItemStack.EMPTY;
        return toInsert.copyWithCount(remaining);
    }

    /**
     * Whether these contents have room for at least one of {@code toInsert}.
     *
     * <p>Mirrors the two places {@link #tryInsert} can put something — a matching stack that still
     * has room, or an empty slot — without touching anything, so callers that only need to know
     * whether a drop would land (a hover affordance, a click that should not be consumed) can ask
     * cheaply.</p>
     *
     * <p>Capacity only. Whether the item is <em>allowed</em> in this particular container is the
     * caller's to decide: a Shulker Box may not hold another Shulker Box, but an Ender Chest may.</p>
     */
    public static boolean canInsert(NonNullList<ItemStack> contents, ItemStack toInsert) {
        if (toInsert.isEmpty()) return false;
        for (ItemStack existing : contents) {
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, toInsert)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts items from a specific slot in the container contents.
     *
     * @param contents   the container's mutable slot list (will be modified in place)
     * @param index      the slot index (0–26) to extract from
     * @param singleItem if {@code true}, extract only 1 item from the stack
     *                   (used for right-click / precision mode)
     * @return the extracted item stack, or {@link ItemStack#EMPTY} if the slot
     *         was empty or the index is out of range
     */
    public static ItemStack tryExtract(NonNullList<ItemStack> contents, int index, boolean singleItem) {
        // ── Bounds check ──
        if (index < 0 || index >= contents.size()) {
            return ItemStack.EMPTY;
        }

        ItemStack existing = contents.get(index);

        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (singleItem) {
            // Extract exactly 1 item, leaving the rest in the slot
            ItemStack extracted = existing.copyWithCount(1);
            existing.shrink(1);

            // If the slot is now empty, replace with EMPTY to keep list clean
            if (existing.isEmpty()) {
                contents.set(index, ItemStack.EMPTY);
            }

            return extracted;
        } else {
            // Extract the entire stack
            ItemStack extracted = existing.copy();
            contents.set(index, ItemStack.EMPTY);
            return extracted;
        }
    }

    /**
     * Finds the first slot index in the container that matches the given target stack.
     *
     * @param contents the container's slot list
     * @param target   the item stack to match
     * @return the index of the matching slot, or -1 if not found
     */
    public static int findMatchingItem(NonNullList<ItemStack> contents, ItemStack target) {
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty() && ItemStack.isSameItemSameComponents(contents.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Categorizes item stacks to support category-based proximity heuristics.
     */
    private static String getCategoryKey(ItemStack stack) {
        var item = stack.getItem();
        String itemPath = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        if (ContainerHelper.containsAny(itemPath, "sword", "bow", "trident")) return "1_weapon";
        if (ContainerHelper.containsAny(itemPath, "helmet", "chestplate", "leggings", "boots")) return "2_armor";
        if (ContainerHelper.containsAny(itemPath, "pickaxe", "axe", "shovel", "hoe", "shears", "flint_and_steel", "brush",
                "fishing_rod", "compass", "clock")) return "3_tool";
        if (stack.has(DataComponents.FOOD)) return "4_food";
        if (item instanceof BlockItem) return "5_block";
        return "6_other";
    }

    private static boolean containsSameItem(Iterable<ItemStack> stacks, ItemStack target) {
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameComponents(stack, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the best empty slot index (0..26) inside the contents list to place the given item stack,
     * using Smart-Merge Proximity Heuristics:
     * 1. Smallest Manhattan distance (in a 9x3 grid) to a slot containing the exact same item type.
     * 2. Smallest Manhattan distance to a slot containing an item of the same category.
     * 3. Fallback to the first available empty slot (lowest index).
     *
     * @param contents the current items in the container (27 slots)
     * @param toInsert the item stack we want to insert
     * @return the best empty slot index, or -1 if no empty slots exist
     */
    public static int findSmartMergeEmptySlot(List<ItemStack> contents, ItemStack toInsert) {
        int size = contents.size();
        int bestSlot = -1;
        int bestSameItemDist = 999;
        int bestCategoryDist = 999;

        String targetCategory = getCategoryKey(toInsert);

        // Classify every occupied slot once up front. Both predicates are independent of which
        // empty slot is being scored, and getCategoryKey costs a registry lookup plus a chain of
        // substring scans. Evaluating them inside the scan below repeated that work for every
        // empty/occupied slot pair, which is hot enough to matter on the server: DEPOSIT and
        // RESTOCK reach this method once per inventory slot they touch.
        int occupiedCount = 0;
        int[] occupiedIndices = new int[size];
        boolean[] occupiedSameItem = new boolean[size];
        boolean[] occupiedSameCategory = new boolean[size];
        for (int j = 0; j < size; j++) {
            ItemStack jStack = contents.get(j);
            if (jStack.isEmpty()) continue;

            occupiedIndices[occupiedCount] = j;
            occupiedSameItem[occupiedCount] = ItemStack.isSameItemSameComponents(jStack, toInsert);
            occupiedSameCategory[occupiedCount] = getCategoryKey(jStack).equals(targetCategory);
            occupiedCount++;
        }

        for (int i = 0; i < size; i++) {
            if (!contents.get(i).isEmpty()) continue;

            // This slot i is empty! Evaluate its score.
            int minSameItemDist = 999;
            int minCategoryDist = 999;

            int colE = i % 9;
            int rowE = i / 9;

            for (int o = 0; o < occupiedCount; o++) {
                int j = occupiedIndices[o];
                int colJ = j % 9;
                int rowJ = j / 9;
                int dist = Math.abs(colE - colJ) + Math.abs(rowE - rowJ);

                // Check if exact same item
                if (occupiedSameItem[o] && dist < minSameItemDist) {
                    minSameItemDist = dist;
                }

                // Check if same category
                if (occupiedSameCategory[o] && dist < minCategoryDist) {
                    minCategoryDist = dist;
                }
            }

            // Compare with our best slot found so far
            if (bestSlot == -1) {
                bestSlot = i;
                bestSameItemDist = minSameItemDist;
                bestCategoryDist = minCategoryDist;
            } else {
                // Priority 1: same item distance
                if (minSameItemDist < bestSameItemDist) {
                    bestSlot = i;
                    bestSameItemDist = minSameItemDist;
                    bestCategoryDist = minCategoryDist;
                } else if (minSameItemDist == bestSameItemDist) {
                    // Priority 2: same category distance
                    if (minCategoryDist < bestCategoryDist) {
                        bestSlot = i;
                        bestSameItemDist = minSameItemDist;
                        bestCategoryDist = minCategoryDist;
                    } else if (minCategoryDist == bestCategoryDist) {
                        // Priority 3: fallback to smaller index
                        if (i < bestSlot) {
                            bestSlot = i;
                            bestSameItemDist = minSameItemDist;
                            bestCategoryDist = minCategoryDist;
                        }
                    }
                }
            }
        }

        return bestSlot;
    }

    /**
     * Pulls items from the player's hotbar slots and merges them into the container contents.
     * Returns true if any changes were made.
     */
    public static boolean restockContents(NonNullList<ItemStack> contents, Iterable<Slot> slots, Player player) {
        boolean success = false;
        for (Slot slot : slots) {
            if (!ContainerHelper.isPlayerInventorySlot(slot, player, 9) || !slot.allowModification(player)) continue;

            ItemStack originalStack = slot.getItem();
            if (originalStack.isEmpty()) continue;

            ItemStack updatedStack = originalStack.copy();
            int maxStack = slot.getMaxStackSize(updatedStack);
            if (updatedStack.getCount() >= maxStack) continue;

            int needed = maxStack - updatedStack.getCount();
            boolean slotChanged = false;
            for (int i = 0; i < contents.size() && needed > 0; i++) {
                ItemStack boxStack = contents.get(i);
                if (!boxStack.isEmpty() && ItemStack.isSameItemSameComponents(boxStack, updatedStack)) {
                    int toTake = Math.min(needed, boxStack.getCount());
                    boxStack.shrink(toTake);
                    updatedStack.grow(toTake);
                    needed -= toTake;
                    if (boxStack.isEmpty()) {
                        contents.set(i, ItemStack.EMPTY);
                    }
                    slotChanged = true;
                    success = true;
                }
            }
            if (slotChanged) {
                slot.setByPlayer(updatedStack, originalStack);
            }
        }
        return success;
    }

    /**
     * Deposits items from player's inventory slots (0..35) that match item types already in the container.
     * Returns true if any changes were made.
     */
    public static boolean depositContents(NonNullList<ItemStack> contents, Iterable<Slot> slots,
                                          @Nullable Slot excludedSlot, Player player) {
        boolean success = false;
        Set<ItemStack> distinctTypes = new HashSet<>();
        for (ItemStack boxStack : contents) {
            if (!boxStack.isEmpty() && !containsSameItem(distinctTypes, boxStack)) {
                distinctTypes.add(boxStack.copy());
            }
        }

        if (!distinctTypes.isEmpty()) {
            for (Slot slot : slots) {
                if (!ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                        || slot == excludedSlot
                        || !slot.allowModification(player)) {
                    continue;
                }

                ItemStack invStack = slot.getItem();
                if (invStack.isEmpty() || !containsSameItem(distinctTypes, invStack)) continue;

                int originalCount = invStack.getCount();
                ItemStack remainder = tryInsert(contents, invStack.copy(), false);
                if (remainder.getCount() < originalCount) {
                    slot.setByPlayer(remainder, invStack);
                    success = true;
                }
            }
        }
        return success;
    }
}

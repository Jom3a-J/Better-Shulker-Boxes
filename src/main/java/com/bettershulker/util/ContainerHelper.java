package com.bettershulker.util;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.Nullable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Central utility class for all container-related operations in Better Shulker.
 *
 * <p>This class provides static helpers to identify container items (shulker boxes
 * and ender chests), read/write their contents via {@link DataComponents#CONTAINER},
 * and perform validated insert/extract operations with full exploit prevention
 * (e.g. blocking nested shulker boxes).</p>
 *
 * <p>All methods are stateless and thread-safe — they operate purely on the
 * provided arguments with no side effects beyond mutating the passed-in
 * {@link NonNullList} or {@link ItemStack} as documented.</p>
 */
public final class ContainerHelper {

    /** Number of inventory slots in a shulker box (3 rows × 9 columns). */
    public static final int SHULKER_SLOT_COUNT = 27;

    // Non-instantiable utility class
    private ContainerHelper() {
        throw new UnsupportedOperationException("ContainerHelper is a static utility class");
    }

    // ─────────────────────────────────────────────────────────────
    //  Type identification
    // ─────────────────────────────────────────────────────────────

    /**
     * Checks whether the given stack is any variant of shulker box.
     *
     * <p>This covers the uncolored shulker box as well as all 16 dyed variants
     * by testing if the item is a {@link BlockItem} whose block is an instance
     * of {@link ShulkerBoxBlock}. This single instanceof check catches every
     * color because all shulker box blocks extend {@code ShulkerBoxBlock}.</p>
     *
     * @param stack the item stack to test
     * @return {@code true} if the stack is a shulker box of any color
     */
    public static boolean isShulkerBox(ItemStack stack) {
        // Pattern matching: BlockItem holds a reference to its Block.
        // ShulkerBoxBlock is the parent class for ALL shulker variants
        // (undyed + 16 dyed colors), so a single instanceof suffices.
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * Checks whether the given stack is an ender chest.
     *
     * @param stack the item stack to test
     * @return {@code true} if the stack is an ender chest
     */
    public static boolean isEnderChest(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.ENDER_CHEST);
    }

    /**
     * Checks whether the given stack is any supported container type
     * (shulker box or ender chest).
     *
     * @param stack the item stack to test
     * @return {@code true} if the stack is a container we can interact with
     */
    public static boolean isContainer(ItemStack stack) {
        return isShulkerBox(stack) || isEnderChest(stack);
    }

    // ─────────────────────────────────────────────────────────────
    //  Container metadata
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the dye color of a shulker box, or {@code null} for uncolored
     * shulker boxes and ender chests.
     *
     * @param stack the item stack to inspect
     * @return the {@link DyeColor} of the shulker box, or {@code null}
     */
    @Nullable
    public static DyeColor getShulkerColor(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock shulkerBlock) {
            // ShulkerBoxBlock.getColor() returns null for the undyed variant
            return shulkerBlock.getColor();
        }
        // Not a shulker box (could be ender chest or anything else)
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    //  Contents read / write
    // ─────────────────────────────────────────────────────────────

    /**
     * Reads the container contents from the stack's {@link DataComponents#CONTAINER}
     * component and returns them as a {@link NonNullList} of exactly
     * {@value #SHULKER_SLOT_COUNT} slots.
     *
     * <p>If the component is absent (e.g. a freshly crafted shulker box that has
     * never been opened), an empty list filled with {@link ItemStack#EMPTY} is
     * returned. This matches vanilla behavior where missing components mean
     * "default/empty".</p>
     *
     * @param stack the container item stack to read from
     * @return a mutable {@link NonNullList} of 27 item stacks
     */
    public static NonNullList<ItemStack> getContainerContents(ItemStack stack) {
        // Allocate a 27-slot list pre-filled with EMPTY stacks.
        // This ensures callers always get a consistently-sized list regardless
        // of how many items are actually stored.
        NonNullList<ItemStack> contents = NonNullList.withSize(SHULKER_SLOT_COUNT, ItemStack.EMPTY);

        // Read the CONTAINER component. Returns null if absent.
        ItemContainerContents containerContents = stack.get(DataComponents.CONTAINER);
        if (containerContents != null) {
            // copyInto populates the target list from the stored contents,
            // preserving slot indices. Slots beyond what was stored remain EMPTY.
            containerContents.copyInto(contents);
        }

        return contents;
    }

    /**
     * Writes the given contents back into the stack's
     * {@link DataComponents#CONTAINER} component.
     *
     * <p>This overwrites any previously stored contents. The caller is
     * responsible for ensuring the list is exactly {@value #SHULKER_SLOT_COUNT}
     * entries.</p>
     *
     * @param stack the container item stack to write to
     * @param items the list of items to store (must be size 27)
     */
    public static void setContainerContents(ItemStack stack, NonNullList<ItemStack> items) {
        // ItemContainerContents.fromItems creates the component from a List<ItemStack>.
        // This is the inverse of getContainerContents — it serializes the full
        // inventory state back into the data component.
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    // ─────────────────────────────────────────────────────────────
    //  Insertion logic
    // ─────────────────────────────────────────────────────────────

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
        if (isShulkerBox(toInsert)) return toInsert.copy();

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

    // ─────────────────────────────────────────────────────────────
    //  Extraction logic
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  Capacity check
    // ─────────────────────────────────────────────────────────────

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
    public static String getCategorySortString(ItemStack stack) {
        var item = stack.getItem();
        String itemPath = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        if (itemPath.contains("sword")
                || itemPath.contains("bow")
                || itemPath.contains("trident")) {
            return "1_weapon";
        }
        if (itemPath.contains("helmet")
                || itemPath.contains("chestplate")
                || itemPath.contains("leggings")
                || itemPath.contains("boots")) {
            return "2_armor";
        }
        if (itemPath.contains("pickaxe")
                || itemPath.contains("axe")
                || itemPath.contains("shovel")
                || itemPath.contains("hoe")
                || itemPath.contains("shears")
                || itemPath.contains("flint_and_steel")
                || itemPath.contains("brush")
                || itemPath.contains("fishing_rod")
                || itemPath.contains("compass")
                || itemPath.contains("clock")) {
            return "3_tool";
        }
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
            return "4_food";
        }
        if (item instanceof net.minecraft.world.item.BlockItem) {
            return "5_block";
        }
        return "6_other";
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
    public static int findSmartMergeEmptySlot(java.util.List<ItemStack> contents, ItemStack toInsert) {
        int bestSlot = -1;
        int bestSameItemDist = 999;
        int bestCategoryDist = 999;

        String targetCategory = getCategorySortString(toInsert);

        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) continue;

            // This slot i is empty! Evaluate its score.
            int minSameItemDist = 999;
            int minCategoryDist = 999;

            int colE = i % 9;
            int rowE = i / 9;

            for (int j = 0; j < contents.size(); j++) {
                ItemStack jStack = contents.get(j);
                if (jStack.isEmpty()) continue;

                int colJ = j % 9;
                int rowJ = j / 9;
                int dist = Math.abs(colE - colJ) + Math.abs(rowE - rowJ);

                // Check if exact same item
                if (ItemStack.isSameItemSameComponents(jStack, toInsert)) {
                    if (dist < minSameItemDist) {
                        minSameItemDist = dist;
                    }
                }

                // Check if same category
                if (getCategorySortString(jStack).equals(targetCategory)) {
                    if (dist < minCategoryDist) {
                        minCategoryDist = dist;
                    }
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
     * Maps DyeColor to the corresponding Shulker Box block/item.
     */
    public static net.minecraft.world.item.Item getShulkerBoxByColor(DyeColor color) {
        if (color == null) return Items.SHULKER_BOX;
        return Items.DYED_SHULKER_BOX.pick(color);
    }

    /**
     * Returns the contextual sound for an ItemStack based on its material.
     */
    public static SoundEvent getContextualSound(ItemStack stack, boolean isInsert) {
        if (stack == null || stack.isEmpty()) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }
        var item = stack.getItem();
        String itemPath = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        // 1. Valuables & Gems
        if (itemPath.contains("diamond")
                || itemPath.contains("emerald")
                || itemPath.contains("nether_star")
                || itemPath.contains("amethyst_shard")) {
            return isInsert ? SoundEvents.AMETHYST_CLUSTER_PLACE : SoundEvents.AMETHYST_CLUSTER_HIT;
        }

        // 2. Magic & Cosmic
        if (itemPath.contains("ender")
                || itemPath.contains("eye")
                || itemPath.contains("totem")
                || itemPath.contains("echo_shard")
                || itemPath.contains("beacon")
                || itemPath.contains("nether_brick")
                || itemPath.contains("quartz")) {
            return isInsert ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.AMETHYST_BLOCK_HIT;
        }

        // 3. Glass & Crystals
        if (itemPath.contains("glass")
                || itemPath.contains("glowstone")
                || itemPath.contains("lantern")
                || itemPath.contains("spyglass")
                || itemPath.contains("amethyst_cluster")
                || itemPath.contains("amethyst_bud")) {
            return isInsert ? SoundEvents.GLASS_PLACE : SoundEvents.GLASS_HIT;
        }

        // 4. Liquids & Fluids (Potions, Splash Potions, Buckets of Fluid, Bottles)
        if (itemPath.contains("potion")
                || itemPath.contains("bottle")
                || itemPath.contains("bucket_of")
                || itemPath.contains("milk_bucket")
                || itemPath.contains("honey_bottle")
                || itemPath.contains("dragon_breath")) {
            return isInsert ? SoundEvents.BOTTLE_FILL : SoundEvents.BOTTLE_EMPTY;
        }

        // 5. Weapons, Combat & Armor
        if (itemPath.contains("sword")
                || itemPath.contains("bow")
                || itemPath.contains("crossbow")
                || itemPath.contains("shield")
                || itemPath.contains("trident")
                || itemPath.contains("helmet")
                || itemPath.contains("chestplate")
                || itemPath.contains("leggings")
                || itemPath.contains("boots")
                || itemPath.contains("arrow")
                || itemPath.contains("horse_armor")) {
            return isInsert ? SoundEvents.ARMOR_EQUIP_IRON.value() : SoundEvents.ARMOR_EQUIP_CHAIN.value();
        }

        // 6. Metals & Ingots
        if (itemPath.contains("iron")
                || itemPath.contains("gold")
                || itemPath.contains("netherite")
                || itemPath.contains("copper")
                || itemPath.contains("metal")
                || itemPath.contains("chain")
                || itemPath.contains("bucket")
                || itemPath.contains("anvil")
                || itemPath.contains("rail")
                || itemPath.contains("minecart")) {
            return isInsert ? SoundEvents.METAL_PLACE : SoundEvents.METAL_HIT;
        }

        // 7. Heavy Blocks & Stone
        if (itemPath.contains("stone")
                || itemPath.contains("cobblestone")
                || itemPath.contains("obsidian")
                || itemPath.contains("deepslate")
                || itemPath.contains("brick")
                || itemPath.contains("granite")
                || itemPath.contains("diorite")
                || itemPath.contains("andesite")
                || itemPath.contains("sandstone")
                || itemPath.contains("basalt")
                || itemPath.contains("ore")) {
            return isInsert ? SoundEvents.STONE_PLACE : SoundEvents.STONE_HIT;
        }

        // 8. Wood & Timber
        if (itemPath.contains("wood")
                || itemPath.contains("plank")
                || itemPath.contains("log")
                || itemPath.contains("stick")
                || itemPath.contains("door")
                || itemPath.contains("fence")
                || itemPath.contains("chest")
                || itemPath.contains("sign")
                || itemPath.contains("boat")
                || itemPath.contains("sapling")
                || itemPath.contains("crafting_table")) {
            return isInsert ? SoundEvents.WOOD_PLACE : SoundEvents.WOOD_HIT;
        }

        // 9. Ground & Earth (Sand, Gravel, Dirt, Snow)
        if (itemPath.contains("sand")
                || itemPath.contains("gravel")
                || itemPath.contains("dirt")
                || itemPath.contains("clay")
                || itemPath.contains("snow")
                || itemPath.contains("mud")
                || itemPath.contains("soul_sand")
                || itemPath.contains("mycelium")
                || itemPath.contains("podzol")) {
            return isInsert ? SoundEvents.SAND_PLACE : SoundEvents.SAND_HIT;
        }

        // 10. Soft & Organic & Food
        if (itemPath.contains("seed")
                || itemPath.contains("crop")
                || itemPath.contains("wheat")
                || itemPath.contains("carrot")
                || itemPath.contains("potato")
                || itemPath.contains("apple")
                || itemPath.contains("food")
                || itemPath.contains("leaf")
                || itemPath.contains("leaves")
                || itemPath.contains("paper")
                || itemPath.contains("wool")
                || itemPath.contains("leather")
                || itemPath.contains("feather")
                || itemPath.contains("egg")
                || itemPath.contains("string")
                || itemPath.contains("flower")
                || itemPath.contains("grass")
                || itemPath.contains("sugar_cane")
                || itemPath.contains("bamboo")
                || itemPath.contains("bread")
                || itemPath.contains("cookie")
                || itemPath.contains("beef")
                || itemPath.contains("pork")
                || itemPath.contains("chicken")
                || itemPath.contains("mutton")
                || itemPath.contains("rabbit")
                || itemPath.contains("fish")
                || itemPath.contains("stew")) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }

        // 11. Fallback
        return isInsert ? SoundEvents.ITEM_PICKUP : SoundEvents.ITEM_PICKUP;
    }
}

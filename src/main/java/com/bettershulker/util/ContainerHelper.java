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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

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

    // =========================================================================
    //  Constants & Constructors
    // =========================================================================

    /** Number of inventory slots in a shulker box (3 rows × 9 columns). */
    public static final int SHULKER_SLOT_COUNT = 27;

    // Non-instantiable utility class
    private ContainerHelper() {
        throw new UnsupportedOperationException("ContainerHelper is a static utility class");
    }

    // =========================================================================
    //  Type Identification
    // =========================================================================

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

    public static boolean isPlayerInventorySlot(Slot slot, int slotLimit) {
        return slot.container instanceof net.minecraft.world.entity.player.Inventory
                && slot.getContainerSlot() < slotLimit;
    }

    // =========================================================================
    //  Container Metadata
    // =========================================================================

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

    // =========================================================================
    //  Contents Read / Write
    // =========================================================================

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

    // =========================================================================
    //  Insertion Logic
    // =========================================================================

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

    // =========================================================================
    //  Extraction Logic
    // =========================================================================

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

    // =========================================================================
    //  Capacity & Proximity Checks
    // =========================================================================

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
        String itemPath = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        if (containsAny(itemPath, "sword", "bow", "trident")) return "1_weapon";
        if (containsAny(itemPath, "helmet", "chestplate", "leggings", "boots")) return "2_armor";
        if (containsAny(itemPath, "pickaxe", "axe", "shovel", "hoe", "shears", "flint_and_steel", "brush",
                "fishing_rod", "compass", "clock")) return "3_tool";
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return "4_food";
        if (item instanceof net.minecraft.world.item.BlockItem) return "5_block";
        return "6_other";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    // =========================================================================
    //  Smart-Merge Proximity Heuristic
    // =========================================================================

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

        String targetCategory = getCategoryKey(toInsert);

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
                if (getCategoryKey(jStack).equals(targetCategory)) {
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

    // =========================================================================
    //  Contextual Sound Selection
    // =========================================================================

    /**
     * Returns the contextual sound for an ItemStack based on its material.
     */
    public static SoundEvent getContextualSound(ItemStack stack, boolean isInsert) {
        if (stack == null || stack.isEmpty()) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }
        var item = stack.getItem();
        String itemPath = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        if (containsAny(itemPath, "diamond", "emerald", "nether_star", "amethyst_shard")) {
            return isInsert ? SoundEvents.AMETHYST_CLUSTER_PLACE : SoundEvents.AMETHYST_CLUSTER_HIT;
        }
        if (containsAny(itemPath, "ender", "eye", "totem", "echo_shard", "beacon", "nether_brick", "quartz")) {
            return isInsert ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.AMETHYST_BLOCK_HIT;
        }
        if (containsAny(itemPath, "glass", "glowstone", "lantern", "spyglass", "amethyst_cluster", "amethyst_bud")) {
            return isInsert ? SoundEvents.GLASS_PLACE : SoundEvents.GLASS_HIT;
        }
        if (containsAny(itemPath, "potion", "bottle", "bucket_of", "milk_bucket", "honey_bottle", "dragon_breath")) {
            return isInsert ? SoundEvents.BOTTLE_FILL : SoundEvents.BOTTLE_EMPTY;
        }
        if (containsAny(itemPath, "sword", "bow", "crossbow", "shield", "trident", "helmet", "chestplate",
                "leggings", "boots", "arrow", "horse_armor")) {
            return isInsert ? SoundEvents.ARMOR_EQUIP_IRON.value() : SoundEvents.ARMOR_EQUIP_CHAIN.value();
        }
        if (containsAny(itemPath, "iron", "gold", "netherite", "copper", "metal", "chain", "bucket", "anvil",
                "rail", "minecart")) {
            return isInsert ? SoundEvents.METAL_PLACE : SoundEvents.METAL_HIT;
        }
        if (containsAny(itemPath, "stone", "cobblestone", "obsidian", "deepslate", "brick", "granite", "diorite",
                "andesite", "sandstone", "basalt", "ore")) {
            return isInsert ? SoundEvents.STONE_PLACE : SoundEvents.STONE_HIT;
        }
        if (containsAny(itemPath, "wood", "plank", "log", "stick", "door", "fence", "chest", "sign", "boat",
                "sapling", "crafting_table")) {
            return isInsert ? SoundEvents.WOOD_PLACE : SoundEvents.WOOD_HIT;
        }
        if (containsAny(itemPath, "sand", "gravel", "dirt", "clay", "snow", "mud", "soul_sand", "mycelium", "podzol")) {
            return isInsert ? SoundEvents.SAND_PLACE : SoundEvents.SAND_HIT;
        }
        if (containsAny(itemPath, "seed", "crop", "wheat", "carrot", "potato", "apple", "food", "leaf", "leaves",
                "paper", "wool", "leather", "feather", "egg", "string", "flower", "grass", "sugar_cane",
                "bamboo", "bread", "cookie", "beef", "pork", "chicken", "mutton", "rabbit", "fish", "stew")) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }
        return SoundEvents.ITEM_PICKUP;
    }

    // =========================================================================
    //  Shared Action Operations (Restock, Deposit)

    /**
     * Pulls items from the player's hotbar slots and merges them into the container contents.
     * Returns true if any changes were made.
     */
    public static boolean restockContents(NonNullList<ItemStack> contents, Iterable<net.minecraft.world.inventory.Slot> slots) {
        boolean success = false;
        for (net.minecraft.world.inventory.Slot slot : slots) {
            if (isPlayerInventorySlot(slot, 9)) {
                ItemStack hotbarStack = slot.getItem();
                if (hotbarStack.isEmpty()) continue;
                int maxStack = hotbarStack.getMaxStackSize();
                if (hotbarStack.getCount() < maxStack) {
                    int needed = maxStack - hotbarStack.getCount();
                    boolean slotChanged = false;
                    for (int i = 0; i < contents.size() && needed > 0; i++) {
                        ItemStack boxStack = contents.get(i);
                        if (!boxStack.isEmpty() && ItemStack.isSameItemSameComponents(boxStack, hotbarStack)) {
                            int toTake = Math.min(needed, boxStack.getCount());
                            boxStack.shrink(toTake);
                            hotbarStack.grow(toTake);
                            needed -= toTake;
                            if (boxStack.isEmpty()) {
                                contents.set(i, ItemStack.EMPTY);
                            }
                            slotChanged = true;
                            success = true;
                        }
                    }
                    if (slotChanged) {
                        slot.set(hotbarStack);
                        slot.setChanged();
                    }
                }
            }
        }
        return success;
    }

    /**
     * Deposits items from player's inventory slots (0..35) that match item types already in the container.
     * Returns true if any changes were made.
     */
    public static boolean depositContents(NonNullList<ItemStack> contents, Iterable<net.minecraft.world.inventory.Slot> slots, int containerSlotId) {
        boolean success = false;
        java.util.Set<ItemStack> distinctTypes = new java.util.HashSet<>();
        for (ItemStack boxStack : contents) {
            if (!boxStack.isEmpty()) {
                boolean exists = false;
                for (ItemStack t : distinctTypes) {
                    if (ItemStack.isSameItemSameComponents(t, boxStack)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    distinctTypes.add(boxStack.copy());
                }
            }
        }

        if (!distinctTypes.isEmpty()) {
            for (net.minecraft.world.inventory.Slot slot : slots) {
                if (isPlayerInventorySlot(slot, 36)) {
                    if (slot.index == containerSlotId) continue;

                    ItemStack invStack = slot.getItem();
                    if (invStack.isEmpty()) continue;

                    boolean matches = false;
                    for (ItemStack t : distinctTypes) {
                        if (ItemStack.isSameItemSameComponents(t, invStack)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        int originalCount = invStack.getCount();
                        ItemStack remainder = tryInsert(contents, invStack.copy(), false);
                        slot.set(remainder);
                        if (remainder.getCount() < originalCount) {
                            success = true;
                        }
                    }
                }
            }
        }
        return success;
    }

    // =========================================================================
    //  Audio Utility Wrap
    // =========================================================================

    /**
     * Plays an interaction sound for the given player.
     * Handles contextual sound selection and volume/pitch randomization.
     */
    public static void playInteractionSound(Player player, ItemStack stack, boolean isInsert, float volume) {
        if (player == null || volume <= 0.0f) return;

        SoundEvent soundEvent = SoundEvents.ITEM_PICKUP;
        if (com.bettershulker.BetterShulkerConfig.soundOption == com.bettershulker.BetterShulkerConfig.SoundOption.CONTEXTUAL) {
            soundEvent = getContextualSound(stack, isInsert);
        } else {
            try {
                String[] split = com.bettershulker.BetterShulkerConfig.soundOption.getSoundId().split(":", 2);
                var soundLoc = net.minecraft.resources.Identifier.fromNamespaceAndPath(split[0], split[1]);
                var soundHolderOpt = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(soundLoc);
                if (soundHolderOpt.isPresent()) {
                    soundEvent = soundHolderOpt.get().value();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        if (soundEvent != null) {
            float pitch = isInsert
                    ? 0.9F + player.level().getRandom().nextFloat() * 0.2F
                    : 0.65F + player.level().getRandom().nextFloat() * 0.15F;

            player.level().playSound(player, player.getX(), player.getY(), player.getZ(), soundEvent, SoundSource.PLAYERS, volume, pitch);
        }
    }
}

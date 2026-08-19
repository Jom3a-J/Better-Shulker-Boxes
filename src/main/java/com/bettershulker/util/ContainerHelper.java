package com.bettershulker.util;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.network.MenuSlotRef;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.Nullable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** Respects vanilla's lock component before exposing portable container interactions. */
    public static boolean canAccessContainer(ItemStack stack, @Nullable Player player) {
        if (player == null) return false;
        LockCode lock = stack.get(DataComponents.LOCK);
        return lock == null || lock.canUnlock(player);
    }

    /**
     * Whether the slot exposes one of the player's own inventory positions below {@code slotLimit}.
     *
     * <p>The position comes from {@link MenuSlotRef}, not from {@code getContainerSlot()} directly:
     * a screen may wrap another menu's slots and report that menu's index there instead of a real
     * inventory position, which would misjudge which slots belong to the player. Server-side menus
     * never wrap, so this resolves to the same answer as before for them.</p>
     */
    public static boolean isPlayerInventorySlot(Slot slot, Player player, int slotLimit) {
        int position = MenuSlotRef.playerInventoryPosition(slot, player);
        return position >= 0
                && position < slotLimit
                && slot.isActive()
                && !slot.isFake();
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



    // =========================================================================
    //  Extraction Logic
    // =========================================================================


    // =========================================================================
    //  Capacity & Proximity Checks
    // =========================================================================

    /**
     * Whether the container holds anything, read straight from its component.
     *
     * <p>Reading through {@link #getContainerContents} answers the same question, but it builds
     * all 27 stacks to do it and every one of those runs {@code validateStrict}. The screen asks
     * this once a frame for the box under the cursor, so that copy is pure waste.</p>
     */
    public static boolean hasAnyContents(ItemStack stack) {
        ItemContainerContents stored = stack.get(DataComponents.CONTAINER);
        return stored != null && stored.nonEmptyItems().iterator().hasNext();
    }

    /** How many of the container's slots hold something, without building their stacks. */
    public static int countOccupiedSlots(ItemStack stack) {
        ItemContainerContents stored = stack.get(DataComponents.CONTAINER);
        if (stored == null) return 0;

        int occupied = 0;
        for (ItemStackTemplate ignored : stored.nonEmptyItems()) {
            occupied++;
        }
        return occupied;
    }

    /**
     * Whether {@code toInsert} would fit in the container, answered from its component.
     *
     * <p>Same result as {@link ContainerTransfer#canInsert} over the container's contents, but
     * this is asked for every Shulker Box on screen on every frame - the bounce and the plus
     * badge both hang off it - so it never builds the 27 stacks that answer would need. A stored
     * entry is only turned into a real stack once its item and its headroom already match, which
     * is the one case left where the components have to be compared in full.</p>
     */
    public static boolean canInsertInto(ItemStack containerStack, ItemStack toInsert) {
        if (toInsert.isEmpty()) return false;

        ItemContainerContents stored = containerStack.get(DataComponents.CONTAINER);
        // No component at all means the box has never been filled: every slot is free.
        if (stored == null) return true;

        int occupied = 0;
        for (ItemStackTemplate template : stored.nonEmptyItems()) {
            occupied++;
            if (template.item().value() != toInsert.getItem()
                    || template.count() >= templateMaxStackSize(template)) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(template.create(), toInsert)) {
                return true;
            }
        }
        return occupied < SHULKER_SLOT_COUNT;
    }

    /** Stack limit of a stored entry, defaulting to unstackable when the component is missing. */
    private static int templateMaxStackSize(ItemStackTemplate template) {
        Integer max = template.get(DataComponents.MAX_STACK_SIZE);
        return max == null ? 1 : max;
    }



    public static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }


    // =========================================================================
    //  Smart-Merge Proximity Heuristic
    // =========================================================================


    // =========================================================================
    //  Contextual Sound Selection
    // =========================================================================


    // =========================================================================
    //  Shared Action Operations (Restock, Deposit)



    // =========================================================================
    //  Audio Utility Wrap
    // =========================================================================

}

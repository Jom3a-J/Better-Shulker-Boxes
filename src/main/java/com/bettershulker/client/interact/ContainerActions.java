package com.bettershulker.client.interact;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The moves a container preview can make: extracting to a slot, inserting from the cursor,
 * emptying the marked slots, and the restock/deposit pair.
 *
 * <p>Each one decides locally whether it is possible - a click that cannot land must not be
 * swallowed, and a sound must not play for nothing - then hands the work to
 * {@link ContainerPrediction}, which applies it and tells the server.</p>
 */
public final class ContainerActions {

    private ContainerActions() {}

    public record ActiveContainer(ItemStack stack, int slotId) {}

    public static boolean canModifyContainerSlot(Slot slot) {
        var player = Minecraft.getInstance().player;
        return player != null
                && player.isAlive()
                && !player.isSpectator()
                && slot.isActive()
                && !slot.isFake()
                && slot.allowModification(player)
                && ContainerHelper.canAccessContainer(slot.getItem(), player);
    }

    public static boolean extractFromSlotToInventory(AbstractContainerScreen<?> self, Slot containerSlot) {
        int extractionIndex = ContainerSelection.extractionIndex(containerSlot.getItem());
        if (extractionIndex == -1) return false;
        ItemStack extractedStack = ContainerSelection.contentsOf(containerSlot.getItem()).get(extractionIndex);
        boolean ctrlHeld = InputKeys.isCtrlDown();
        ContainerPrediction.sendInteractPayload(self, 
            MenuSlotRef.encode(containerSlot, Minecraft.getInstance().player), extractionIndex,
            ctrlHeld ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), -1);
        playClientSound(extractedStack, false);
        return true;
    }

    public static boolean insertFromCursorToContainer(AbstractContainerScreen<?> self, Slot containerSlot, ItemStack cursorStack) {
        boolean ctrlHeld = InputKeys.isCtrlDown();
        if (cursorStack.isEmpty()) return false;

        // Use a copy for the capacity check so a full/invalid container does not consume
        // the vanilla right-click. Ender chest contents may be unavailable before sync;
        // in that case the empty preview is only a best-effort client-side check.
        NonNullList<ItemStack> preview = ContainerSelection.contentsOf(containerSlot.getItem());
        NonNullList<ItemStack> previewCopy = NonNullList.withSize(preview.size(), ItemStack.EMPTY);
        for (int i = 0; i < preview.size(); i++) {
            previewCopy.set(i, preview.get(i).copy());
        }
        ItemStack remainder = ContainerHelper.tryInsert(previewCopy, cursorStack.copy(), ctrlHeld);
        if (remainder.getCount() >= cursorStack.getCount()) return false;

        ContainerPrediction.sendInteractPayload(self, 
            MenuSlotRef.encode(containerSlot, Minecraft.getInstance().player), -1,
            ctrlHeld ? ContainerInteractPayload.InteractType.INSERT_ONE.toId() : ContainerInteractPayload.InteractType.INSERT.toId(), -1);
        playClientSound(cursorStack, true);
        return true;
    }

    public static boolean tapExtractToSlot(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack carried = self.getMenu().getCarried();
        boolean ctrlHeld = InputKeys.isCtrlDown();
        int extractionIndex = ContainerSelection.extractionIndex(carried);
        if (extractionIndex == -1) return false;
        ItemStack extractedStack = ContainerSelection.contentsOf(carried).get(extractionIndex);
        ItemStack destinationStack = ctrlHeld ? extractedStack.copyWithCount(1) : extractedStack;
        if (!canInsertIntoPlayerSlot(slot, destinationStack)) return false;
        int action = ctrlHeld
            ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId()
            : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId();
        ContainerPrediction.sendInteractPayload(self, -1, extractionIndex, action,
            MenuSlotRef.encode(slot, Minecraft.getInstance().player));
        playClientSound(extractedStack, false);
        return true;
    }

    public static boolean canInsertIntoPlayerSlot(Slot slot, ItemStack stack) {
        var player = Minecraft.getInstance().player;
        if (player == null || stack.isEmpty()
                || !ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                || !slot.allowModification(player)
                || !slot.mayPlace(stack)) {
            return false;
        }

        ItemStack existing = slot.getItem();
        if (existing.isEmpty()) return true;
        if (!ItemStack.isSameItemSameComponents(existing, stack)) {
            return false;
        }

        return existing.getCount() < Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(stack));
    }

    public static void playClientSound(ItemStack stack, boolean isInsert) {
        ContainerHelper.playInteractionSound(Minecraft.getInstance().player, stack, isInsert,
                BetterShulkerConfig.getSoundVolume());
    }

    public static int countNonNullSlots(NonNullList<ItemStack> contents) {
        int count = 0;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) count++;
        }
        return count;
    }

    public static int findVirtualInventorySlot(NonNullList<Slot> slots, ItemStack stack, Map<Integer, ItemStack> virtualInv) {
        var player = Minecraft.getInstance().player;
        if (player == null) return -1;

        // Keyed on the encoded reference throughout, both because that is what the payload
        // carries and because slot.index is not distinct on every screen.
        // First pass: try to merge with existing slots that have room
        for (Slot slot : slots) {
            if (!ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                    || !slot.allowModification(player)
                    || !slot.mayPlace(stack)) continue;

            int slotRef = MenuSlotRef.encode(slot, player);
            ItemStack virtualStack = virtualInv.get(slotRef);
            if (virtualStack != null && !virtualStack.isEmpty()) {
                if (ItemStack.isSameItemSameComponents(virtualStack, stack)
                    && virtualStack.getCount() < virtualStack.getMaxStackSize()) {
                    int canFit = virtualStack.getMaxStackSize() - virtualStack.getCount();
                    // A SWEEP_EXTRACT packet has only one destination slot. If the selected
                    // stack does not fully fit in this partial stack, the server will extract
                    // only part of it and leave the rest in the shulker. Skip partial fits here
                    // so batch/multi-select extraction uses an empty slot when available.
                    if (canFit >= stack.getCount()) {
                        virtualStack.grow(stack.getCount());
                        return slotRef;
                    }
                }
            }
        }

        // Second pass: put into the first empty slot
        for (Slot slot : slots) {
            if (!ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                    || !slot.allowModification(player)
                    || !slot.mayPlace(stack)) continue;

            int slotRef = MenuSlotRef.encode(slot, player);
            ItemStack virtualStack = virtualInv.get(slotRef);
            if (virtualStack == null || virtualStack.isEmpty()) {
                virtualInv.put(slotRef, stack.copy());
                return slotRef;
            }
        }

        return -1;
    }

    public static void multiSelectExtract(AbstractContainerScreen<?> self, ActiveContainer active) {
        ItemStack containerStack = active.stack();
        if (containerStack.isEmpty()) return;

        NonNullList<ItemStack> contents = ContainerSelection.contentsOf(containerStack);
        Set<Integer> selectedSet = BetterShulkerClient.getSelectedSlotsSet();
        
        // Build virtual inventory state map for player inventory slots
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        Map<Integer, ItemStack> virtualInv = new HashMap<>();
        for (Slot slot : self.getMenu().slots) {
            if (ContainerHelper.isPlayerInventorySlot(slot, player, 36)) {
                virtualInv.put(MenuSlotRef.encode(slot, player), slot.getItem().copy());
            }
        }

        ItemStack firstExtracted = ItemStack.EMPTY;
        for (int targetIdx : selectedSet) {
            if (targetIdx < 0 || targetIdx >= contents.size()) continue;
            ItemStack shulkerStack = contents.get(targetIdx);
            if (shulkerStack.isEmpty()) continue;
            if (firstExtracted.isEmpty()) {
                firstExtracted = shulkerStack;
            }

            int targetSlotIdx = findVirtualInventorySlot(self.getMenu().slots, shulkerStack, virtualInv);
            if (targetSlotIdx != -1) {
                ContainerPrediction.sendInteractPayload(self, 
                    active.slotId(), targetIdx, ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), targetSlotIdx);
            }
        }

        BetterShulkerClient.clearSelectedSlotsSet();
        playClientSound(firstExtracted, false);
    }

    public static void restockOrDeposit(AbstractContainerScreen<?> self, ActiveContainer active, boolean deposit) {
        if (active.stack().isEmpty()) return;

        var actionType = deposit ? ContainerInteractPayload.InteractType.DEPOSIT : ContainerInteractPayload.InteractType.RESTOCK;

        ContainerPrediction.sendInteractPayload(self, 
            active.slotId(), -1, actionType.toId(), -1);
    }
}

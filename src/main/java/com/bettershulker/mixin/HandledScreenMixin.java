package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Mixin for AbstractContainerScreen to handle client-side container UI interactions.
 * 
 * <p>Responsibilities:
 * 1. Capture drag/click mouse events to perform inserts/extractions.
 * 2. Intercept mouse scroll wheel inputs to cycle selected container slots.
 * 3. Render shulker box/ender chest preview tooltips and highlight overlays.
 * 4. Maintain short-term prediction states for smooth client-side inventory updates.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {

    // =========================================================================
    //  Mixin Shadowed Fields
    // =========================================================================

    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected abstract void slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput clickType);



    // =========================================================================
    //  State Tracking Fields
    // =========================================================================

    @Unique
    private static boolean bettershulker$isDragging = false;

    @Unique
    private static int bettershulker$dragButton = -1;

    @Unique
    private static boolean bettershulker$dragDidWork = false;

    @Unique
    private static boolean bettershulker$dragFired = false;

    @Unique
    private static boolean bettershulker$tapHandled = false;

    @Unique
    private static boolean bettershulker$selectKeyWasDown = false;

    @Unique
    private static long bettershulker$lastTooltipScrollTime = 0L;

    @Unique
    private static final long bettershulker$TOOLTIP_SCROLL_COOLDOWN_MS = 85L;

    @Unique
    private static final Set<Integer> bettershulker$processedDragSlots = new HashSet<>();

    @Unique
    private boolean bettershulker$wigglePushed = false;

    protected HandledScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Unique
    private static boolean bettershulker$isKeyHeld(KeyMapping key) {
        if (key == null || key.isUnbound()) return false;
        try {
            var boundKey = InputConstants.getKey(key.saveString());
            if (boundKey.getType() == InputConstants.Type.KEYSYM) {
                return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
            }
        } catch (Exception e) {
            // fallback to isDown if saveString parsing fails
        }
        return key.isDown();
    }

    @Unique
    private static boolean bettershulker$isCtrlDown() {
        if (!BetterShulkerConfig.precisionModeEnabled) return false;
        return bettershulker$isKeyHeld(BetterShulkerClient.getPrecisionKey());
    }

    @Unique
    private static boolean bettershulker$consumeTooltipScrollStep() {
        long now = System.currentTimeMillis();
        if (now - bettershulker$lastTooltipScrollTime < bettershulker$TOOLTIP_SCROLL_COOLDOWN_MS) {
            return false;
        }
        bettershulker$lastTooltipScrollTime = now;
        return true;
    }

    @Unique
    private static boolean bettershulker$isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Unique
    private static boolean bettershulker$isAltDown() {
        if (!BetterShulkerConfig.altForceTooltipEnabled) return false;
        var window = Minecraft.getInstance().getWindow();
        return GLFW.glfwGetKey(window.handle(), GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window.handle(), GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private AbstractContainerScreen<? extends AbstractContainerMenu> bettershulker$self() {
        return (AbstractContainerScreen<? extends AbstractContainerMenu>) (Object) this;
    }

    @Unique
    private NonNullList<ItemStack> bettershulker$getContents(ItemStack containerStack) {
        if (ContainerHelper.isShulkerBox(containerStack)) {
            return ContainerHelper.getContainerContents(containerStack);
        }
        if (ContainerHelper.isEnderChest(containerStack)) {
            NonNullList<ItemStack> cached = BetterShulkerClient.getEnderChestContents();
            if (cached != null) return cached;
        }
        return NonNullList.withSize(27, ItemStack.EMPTY);
    }

    @Unique
    private void bettershulker$resetDragState() {
        bettershulker$isDragging = false;
        bettershulker$dragButton = -1;
        bettershulker$dragDidWork = false;
        bettershulker$dragFired = false;
        bettershulker$processedDragSlots.clear();
    }

    // =========================================================================
    //  mouseClicked — Record left/right button, consume event if carrying container
    // =========================================================================

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void bettershulker$onMouseClicked(MouseButtonEvent event, boolean handled, CallbackInfoReturnable<Boolean> ci) {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();



        // Right-click on a container in inventory while carrying nothing → extract selected item
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT
            && carried.isEmpty()
            && this.hoveredSlot != null
            && this.hoveredSlot.hasItem()
            && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
            bettershulker$extractFromSlotToInventory(self, this.hoveredSlot);
            bettershulker$tapHandled = true;
            ci.setReturnValue(true);
            ci.cancel();
            return;
        }

        // Right-click on a container in inventory while carrying items → insert cursor items into container
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT
            && !carried.isEmpty()
            && this.hoveredSlot != null
            && this.hoveredSlot.hasItem()
            && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
            
            // Safety check: Prevent nesting a Shulker Box inside another Shulker Box
            if (ContainerHelper.isShulkerBox(carried) && ContainerHelper.isShulkerBox(this.hoveredSlot.getItem())) {
                return;
            }

            bettershulker$insertFromCursorToContainer(self, this.hoveredSlot, carried);
            bettershulker$tapHandled = true;
            ci.setReturnValue(true);
            ci.cancel();
            return;
        }

        if (!ContainerHelper.isContainer(carried)) return;

        bettershulker$isDragging = true;
        bettershulker$dragButton = event.button();
        bettershulker$dragDidWork = false;
        bettershulker$dragFired = false;
        bettershulker$processedDragSlots.clear();

        ci.setReturnValue(true);
        ci.cancel();
    }

    // =========================================================================
    //  mouseReleased — End drag, play sound if work was done, clean up
    // =========================================================================

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void bettershulker$onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (bettershulker$tapHandled) {
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                bettershulker$tapHandled = false;
                ci.setReturnValue(true);
                ci.cancel();
            }
            return;
        }
        if (!bettershulker$isDragging) return;

        int button = event.button();
        if (button != bettershulker$dragButton) return;

        var self = bettershulker$self();

        if (!bettershulker$dragDidWork) {
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                // Right-click tap → extract selected item to hovered slot. Tiny mouse jitter can fire
                // mouseDragged before release, so treat any no-work drag as a normal tap.
                if (this.hoveredSlot != null && this.hoveredSlot.isActive()) {
                    bettershulker$tapExtractToSlot(self, this.hoveredSlot);
                }
            } else if (this.hoveredSlot != null && this.hoveredSlot.isActive()) {
                // Left-click tap → simulate vanilla click to grab/place the carried container.
                // Previously this was skipped after even a 1px accidental drag, swallowing the click
                // and making users click twice to pick up or release the item.
                this.slotClicked(this.hoveredSlot, this.hoveredSlot.index, button, ContainerInput.PICKUP);
            }
        }

        bettershulker$resetDragState();
        this.setDragging(false);

        ci.setReturnValue(true);
        ci.cancel();
    }

    // =========================================================================
    //  mouseDragged — Left drag over occupied = insert, Right drag over empty = extract
    // =========================================================================

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void bettershulker$onMouseDragged(MouseButtonEvent event, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> ci) {
        if (!bettershulker$isDragging) return;
        if (event.button() != bettershulker$dragButton) return;

        bettershulker$dragFired = true;

        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        if (!ContainerHelper.isContainer(carried)) {
            bettershulker$resetDragState();
            return;
        }

        Slot slot = this.hoveredSlot;
        if (slot != null && slot.isActive() && !bettershulker$processedDragSlots.contains(slot.index)) {
            if (bettershulker$dragButton == InputConstants.MOUSE_BUTTON_LEFT) {
                bettershulker$tryDragInsert(self, slot);
            } else if (bettershulker$dragButton == InputConstants.MOUSE_BUTTON_RIGHT) {
                bettershulker$tryDragExtract(self, slot);
            }
        }

        ci.setReturnValue(true);
        ci.cancel();
    }

    @Unique
    private void bettershulker$tryDragInsert(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack slotStack = slot.getItem();
        if (slotStack.isEmpty()) return;

        ItemStack carried = self.getMenu().getCarried();
        boolean ctrlHeld = bettershulker$isCtrlDown();

        if (ContainerHelper.isContainer(carried)) {
            // Safety check: Prevent nesting a Shulker Box inside another Shulker Box
            if (ContainerHelper.isShulkerBox(carried) && ContainerHelper.isShulkerBox(slotStack)) {
                return;
            }

            bettershulker$processedDragSlots.add(slot.index);
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, -1, ctrlHeld ? ContainerInteractPayload.InteractType.INSERT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_INSERT.toId(), slot.index);
            bettershulker$playClientSound(slotStack, true);
        }
    }

    @Unique
    private void bettershulker$tryDragExtract(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack carried = self.getMenu().getCarried();
        ItemStack slotStack = slot.getItem();
        boolean ctrlHeld = bettershulker$isCtrlDown();
        // Detect Shift for batch extraction
        boolean shiftHeld = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);

        if (!ContainerHelper.isContainer(carried)) return;

        // Right-click drag over empty slot → extract (use scroll‑selected slot)
        if (slotStack.isEmpty()) {
            // If Shift is held, extract *all* items from the carried container
            if (shiftHeld) {
                ItemStack firstItem = ItemStack.EMPTY;
                NonNullList<ItemStack> contents = bettershulker$getContents(carried);
                for (int i = 0; i < contents.size(); i++) {
                    if (!contents.get(i).isEmpty()) {
                        if (firstItem.isEmpty()) {
                            firstItem = contents.get(i);
                        }
                        bettershulker$processedDragSlots.add(slot.index);
                        bettershulker$dragDidWork = true;
                        bettershulker$sendInteractPayload(
                            -1, i, ContainerInteractPayload.InteractType.EXTRACT_ONE.toId(), slot.index);
                    }
                }
                // Play a single sound after batch extraction
                bettershulker$playClientSound(firstItem, false);
                return;
            }
            int extractionIndex = bettershulker$getExtractionIndex(carried);
            if (extractionIndex == -1) return;
            ItemStack extractedStack = bettershulker$getContents(carried).get(extractionIndex);
            bettershulker$processedDragSlots.add(slot.index);
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, extractionIndex, ctrlHeld ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), slot.index);
            bettershulker$playClientSound(extractedStack, false);
        } else if (ctrlHeld && bettershulker$hasMatchingItem(carried, slotStack)) {
            // Right-click drag over occupied slot with matching item + precision mode → extract one matching
            int matchingIndex = bettershulker$findMatchingIndex(carried, slotStack);
            if (matchingIndex == -1) return;
            ItemStack extractedStack = bettershulker$getContents(carried).get(matchingIndex);
            bettershulker$processedDragSlots.add(slot.index);
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, matchingIndex, ContainerInteractPayload.InteractType.EXTRACT_ONE.toId(), slot.index);
            bettershulker$playClientSound(extractedStack, false);
        }
    }

    // =========================================================================
    //  Tap extraction — right-click tap extracts selected item to hovered slot
    // =========================================================================
    //  Container-in-slot extraction — right-click on container in inventory extracts selected item
    // =========================================================================

    @Unique
    private void bettershulker$extractFromSlotToInventory(AbstractContainerScreen<?> self, Slot containerSlot) {
        int extractionIndex = bettershulker$getExtractionIndex(containerSlot.getItem());
        if (extractionIndex == -1) return;
        ItemStack extractedStack = bettershulker$getContents(containerSlot.getItem()).get(extractionIndex);
        boolean ctrlHeld = bettershulker$isCtrlDown();
        bettershulker$sendInteractPayload(
            containerSlot.index, extractionIndex,
            ctrlHeld ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), -1);
        bettershulker$playClientSound(extractedStack, false);
    }

    @Unique
    private void bettershulker$insertFromCursorToContainer(AbstractContainerScreen<?> self, Slot containerSlot, ItemStack cursorStack) {
        boolean ctrlHeld = bettershulker$isCtrlDown();
        bettershulker$sendInteractPayload(
            containerSlot.index, -1,
            ctrlHeld ? ContainerInteractPayload.InteractType.INSERT_ONE.toId() : ContainerInteractPayload.InteractType.INSERT.toId(), -1);
        bettershulker$playClientSound(cursorStack, true);
    }

    @Unique
    private void bettershulker$tapExtractToSlot(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack carried = self.getMenu().getCarried();
        boolean ctrlHeld = bettershulker$isCtrlDown();
        int extractionIndex = bettershulker$getExtractionIndex(carried);
        if (extractionIndex == -1) return;
        ItemStack extractedStack = bettershulker$getContents(carried).get(extractionIndex);
        int action = ctrlHeld
            ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId()
            : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId();
        bettershulker$sendInteractPayload(-1, extractionIndex, action, slot.index);
        bettershulker$playClientSound(extractedStack, false);
    }

    @Unique
    private void bettershulker$playClientSound(ItemStack stack, boolean isInsert) {
        ContainerHelper.playInteractionSound(Minecraft.getInstance().player, stack, isInsert, BetterShulkerConfig.soundVolume);
    }


    // =========================================================================
    //  Scroll wheel — cycle through tooltip selected item
    // =========================================================================

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void bettershulker$onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> ci) {
        if (!BetterShulkerConfig.secondaryTooltipEnabled) return;

        var self = bettershulker$self();

        boolean handled = false;

        // Scroll when hovering a container in the inventory
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            ItemStack hoveredStack = this.hoveredSlot.getItem();
            if (ContainerHelper.isContainer(hoveredStack)) {
                if (bettershulker$consumeTooltipScrollStep()) {
                    int delta = verticalAmount != 0 ? (int)Math.signum(-verticalAmount) : (int)Math.signum(-horizontalAmount);
                    int oldSlot = BetterShulkerClient.getSelectedSlotIndex();
                    int newSlot = bettershulker$clampScroll(oldSlot, delta, hoveredStack);
                    if (newSlot != oldSlot) {
                        BetterShulkerClient.setSelectedSlotIndex(newSlot);
                        if (bettershulker$isKeyHeld(BetterShulkerClient.getSelectSlotKey())) {
                            BetterShulkerClient.getSelectedSlotsSet().add(newSlot);
                        }
                    }
                }
                handled = true;
            }
        }

        // Scroll when carrying a container
        if (!handled) {
            ItemStack carried = self.getMenu().getCarried();
            if (ContainerHelper.isContainer(carried)) {
                if (bettershulker$consumeTooltipScrollStep()) {
                    int delta = verticalAmount != 0 ? (int)Math.signum(-verticalAmount) : (int)Math.signum(-horizontalAmount);
                    int oldSlot = BetterShulkerClient.getSelectedSlotIndex();
                    int newSlot = bettershulker$clampScroll(oldSlot, delta, carried);
                    if (newSlot != oldSlot) {
                        BetterShulkerClient.setSelectedSlotIndex(newSlot);
                        if (bettershulker$isKeyHeld(BetterShulkerClient.getSelectSlotKey())) {
                            BetterShulkerClient.getSelectedSlotsSet().add(newSlot);
                        }
                    }
                }
                handled = true;
            }
        }

        if (handled) {
            ci.setReturnValue(true);
            ci.cancel();
        }
    }

    @Unique
    private int bettershulker$clampScroll(int current, int delta, ItemStack containerStack) {
        if (com.bettershulker.client.BetterShulkerClient.isCompactModeActive()) {
            NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
            List<Integer> visibleIndices = new java.util.ArrayList<>();
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
                            bettershulker$countMergedItems(contents, b),
                            bettershulker$countMergedItems(contents, a));
                    return countCompare != 0 ? countCompare : Integer.compare(a, b);
                });
                if (visibleIndices.size() > 5) {
                    visibleIndices = new java.util.ArrayList<>(visibleIndices.subList(0, 5));
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
            int size = bettershulker$getContainerSize(containerStack);
            if (size <= 0) return current;
            int newSlot = current + delta;
            if (newSlot < 0) newSlot = size - 1;
            if (newSlot >= size) newSlot = 0;
            return newSlot;
        }
    }

    @Unique
    private int bettershulker$countMergedItems(NonNullList<ItemStack> contents, int slot) {
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

    @Unique
    private int bettershulker$getContainerSize(ItemStack containerStack) {
        return ContainerHelper.isContainer(containerStack) ? 27 : 0;
    }

    @Unique
    private boolean bettershulker$hasContents(ItemStack containerStack) {
        for (ItemStack stack : bettershulker$getContents(containerStack)) {
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

    @Unique
    private boolean bettershulker$hasMatchingItem(ItemStack containerStack, ItemStack target) {
        return bettershulker$findMatchingIndex(containerStack, target) != -1;
    }

    @Unique
    private int bettershulker$findMatchingIndex(ItemStack containerStack, ItemStack target) {
        return ContainerHelper.findMatchingItem(bettershulker$getContents(containerStack), target);
    }

    // =========================================================================
    //  Key press — arrow keys to cycle tooltip item
    // =========================================================================

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void bettershulker$onKeyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> ci) {
        int keyCode = keyEvent.key();

        // 00000. Restock or Deposit via configurable restock key when tooltip is active
        if (BetterShulkerClient.getRestockKey().matches(keyEvent) && BetterShulkerClient.isTooltipActive()) {
            boolean shiftHeld = bettershulker$isShiftDown();
            bettershulker$triggerRestockOrDeposit(shiftHeld);
            ci.setReturnValue(true);
            ci.cancel();
            return;
        }

        // 000. Select/toggle tooltip slots via configurable key.
        // Pressing Space explicitly arms slots for extraction; E only extracts after this selection.
        if (BetterShulkerClient.isTooltipActive()) {
            int targetSlotIdx = BetterShulkerClient.getHoveredTooltipSlotIndex();
            if (targetSlotIdx < 0) {
                targetSlotIdx = BetterShulkerClient.getSelectedSlotIndex();
            }
            if (targetSlotIdx >= 0 && BetterShulkerClient.getSelectSlotKey().matches(keyEvent)) {
                if (!bettershulker$selectKeyWasDown) {
                    bettershulker$selectKeyWasDown = true;
                    BetterShulkerClient.setSelectedSlotIndex(targetSlotIdx);
                    BetterShulkerClient.toggleSelectedSlot(targetSlotIdx);
                }
                ci.setReturnValue(true);
                ci.cancel();
                return;
            }
        }

        // 1. E/extract only acts when the user explicitly selected tooltip slot(s) with Space.
        // If nothing is selected, do not consume the key so Minecraft closes the inventory normally.
        if (BetterShulkerClient.getExtractKey().matches(keyEvent)
                && BetterShulkerClient.isTooltipActive()
                && !BetterShulkerClient.getSelectedSlotsSet().isEmpty()) {
            bettershulker$processMultiSelectExtract();
            ci.setReturnValue(true);
            ci.cancel();
            return;
        }

        // 1. Handle filterKey for item filtering inside container tooltips
        if (BetterShulkerClient.getFilterKey().matches(keyEvent)) {
            ItemStack targetStack = ItemStack.EMPTY;

            // Check if hovering a slot inside the container tooltip
            int hoveredTooltipIdx = BetterShulkerClient.getHoveredTooltipSlotIndex();
            if (hoveredTooltipIdx < 0 && BetterShulkerClient.isTooltipActive()) {
                hoveredTooltipIdx = BetterShulkerClient.getSelectedSlotIndex();
            }
            ItemStack hoveredTooltipContainer = BetterShulkerClient.getActiveContainerStack();
            if (hoveredTooltipIdx >= 0 && !hoveredTooltipContainer.isEmpty()) {
                NonNullList<ItemStack> contents = bettershulker$getContents(hoveredTooltipContainer);
                if (hoveredTooltipIdx < contents.size()) {
                    targetStack = contents.get(hoveredTooltipIdx);
                }
            }

            // If not hovering a tooltip slot, check the hovered slot in the screen itself
            if (targetStack.isEmpty() && this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
                targetStack = this.hoveredSlot.getItem();
            }

            if (!targetStack.isEmpty()) {
                ItemStack currentFilter = BetterShulkerClient.getFilterItemStack();
                if (!currentFilter.isEmpty() && ItemStack.isSameItemSameComponents(currentFilter, targetStack)) {
                    // Toggle off if same item
                    BetterShulkerClient.setFilterItemStack(ItemStack.EMPTY);
                } else {
                    // Set new filter
                    BetterShulkerClient.setFilterItemStack(targetStack.copy());
                }
                ci.setReturnValue(true);
                ci.cancel();
                return;
            } else {
                // If pressing filterKey over nothing, clear the filter!
                if (!BetterShulkerClient.getFilterItemStack().isEmpty()) {
                    BetterShulkerClient.setFilterItemStack(ItemStack.EMPTY);
                    ci.setReturnValue(true);
                    ci.cancel();
                    return;
                }
            }
        }

        // 2. Handle arrow-key movement for the tooltip selection square.
        // Left/Right use the configured scroll keys; Up/Down move one row in the 9x3 grid.
        if (BetterShulkerConfig.secondaryTooltipEnabled && BetterShulkerClient.isTooltipActive()) {
            int scrollDelta = 0;
            if (BetterShulkerClient.getScrollLeftKey().matches(keyEvent) || keyCode == GLFW.GLFW_KEY_LEFT) {
                scrollDelta = -1;
            } else if (BetterShulkerClient.getScrollRightKey().matches(keyEvent) || keyCode == GLFW.GLFW_KEY_RIGHT) {
                scrollDelta = 1;
            } else if (keyCode == GLFW.GLFW_KEY_UP) {
                scrollDelta = -9;
            } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
                scrollDelta = 9;
            }

            if (scrollDelta != 0) {
                var self = bettershulker$self();
                boolean handledKey = false;

                // Keyboard cycle when hovering a container in the inventory
                if (this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
                    ItemStack hoveredStack = this.hoveredSlot.getItem();
                    if (ContainerHelper.isContainer(hoveredStack) && bettershulker$hasContents(hoveredStack)) {
                        int oldSlot = BetterShulkerClient.getSelectedSlotIndex();
                        int newSlot = bettershulker$clampScroll(oldSlot, scrollDelta, hoveredStack);
                        if (newSlot != oldSlot) {
                            BetterShulkerClient.setSelectedSlotIndex(newSlot);
                            if (bettershulker$isKeyHeld(BetterShulkerClient.getSelectSlotKey())) {
                                BetterShulkerClient.getSelectedSlotsSet().add(newSlot);
                            }
                        }
                        handledKey = true;
                    }
                }

                // Keyboard cycle when carrying a container
                if (!handledKey) {
                    ItemStack carried = self.getMenu().getCarried();
                    if (ContainerHelper.isContainer(carried) && bettershulker$hasContents(carried)) {
                        int oldSlot = BetterShulkerClient.getSelectedSlotIndex();
                        int newSlot = bettershulker$clampScroll(oldSlot, scrollDelta, carried);
                        if (newSlot != oldSlot) {
                            BetterShulkerClient.setSelectedSlotIndex(newSlot);
                            if (bettershulker$isKeyHeld(BetterShulkerClient.getSelectSlotKey())) {
                                BetterShulkerClient.getSelectedSlotsSet().add(newSlot);
                            }
                        }
                        handledKey = true;
                    }
                }

                if (handledKey) {
                    ci.setReturnValue(true);
                    ci.cancel();
                }
            }
        }
    }

    // =========================================================================
    //  Render — tooltip + fill overlay
    // =========================================================================

    @Inject(method = "extractTooltip", at = @At("RETURN"))
    private void bettershulker$onExtractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                 CallbackInfo ci) {
        BetterShulkerClient.setLastMouseX(mouseX);
        BetterShulkerClient.setLastMouseY(mouseY);
        boolean hovering = bettershulker$isHoveringContainer();
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        boolean carryingContainer = ContainerHelper.isContainer(carried);
        boolean altDown = bettershulker$isAltDown();
        boolean altForce = altDown && (hovering || carryingContainer);
        boolean tooltipActive = altForce || (hovering && BetterShulkerConfig.tooltipEnabled);
        BetterShulkerClient.setTooltipActive(tooltipActive);

        if (!tooltipActive || !bettershulker$isKeyHeld(BetterShulkerClient.getSelectSlotKey())) {
            bettershulker$selectKeyWasDown = false;
        }

        ItemStack hoveredContainer = ItemStack.EMPTY;
        if (tooltipActive) {
            if (hovering) {
                hoveredContainer = this.hoveredSlot.getItem();
            } else if (carryingContainer) {
                hoveredContainer = carried;
            }
        }
        BetterShulkerClient.setActiveContainerStack(hoveredContainer);

        if (altDown && carryingContainer && !hovering) {
            var mc = Minecraft.getInstance();
            var contents = bettershulker$getContents(carried);
            String selectedItemName = "";
            int selectedIndex = BetterShulkerClient.getSelectedSlotIndex();
            if (selectedIndex >= 0 && selectedIndex < contents.size()) {
                ItemStack selectedStack = contents.get(selectedIndex);
                if (!selectedStack.isEmpty()) {
                    selectedItemName = selectedStack.getHoverName().getString();
                }
            }
            var data = new ShulkerTooltipData(contents,
                ContainerHelper.getShulkerColor(carried),
                ContainerHelper.isEnderChest(carried),
                selectedItemName,
                carried.getHoverName().getString());
            graphics.setTooltipForNextFrame(mc.font,
                List.of(carried.getDisplayName()), Optional.of(data), mouseX, mouseY);
        }
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void bettershulker$onGetTooltipFromContainerItem(ItemStack stack,
                                                               CallbackInfoReturnable<List<Component>> ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;
        if (!ContainerHelper.isContainer(stack)) return;
        List<Component> lines = ci.getReturnValue();
        if (lines.isEmpty()) return;
        ci.setReturnValue(List.of(lines.getFirst()));
    }

    @Inject(method = {"extractContents"}, at = @At("RETURN"))
    private void bettershulker$onExtractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                  float delta, CallbackInfo ci) {
        bettershulker$verifyPredictions();
        bettershulker$renderContainerOverlay(graphics);
        bettershulker$renderRollbackAnimations(graphics);
        if (this.bettershulker$wigglePushed) {
            graphics.pose().popMatrix();
            this.bettershulker$wigglePushed = false;
        }
    }

    // =========================================================================
    //  Utility Helpers
    // =========================================================================

    @Unique
    private int bettershulker$getExtractionIndex(ItemStack containerStack) {
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (bettershulker$slotHasItem(containerStack, selected)) return selected;
        return bettershulker$findExtractionIndex(containerStack);
    }

    @Unique
    private boolean bettershulker$slotHasItem(ItemStack containerStack, int slot) {
        if (slot < 0) return false;
        var contents = bettershulker$getContents(containerStack);
        return slot < contents.size() && !contents.get(slot).isEmpty();
    }

    @Unique
    private int bettershulker$findExtractionIndex(ItemStack containerStack) {
        var contents = bettershulker$getContents(containerStack);
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) return i;
        }
        return -1;
    }

    @Unique
    private void bettershulker$renderContainerOverlay(GuiGraphicsExtractor graphics) {
        try {
            if (!BetterShulkerConfig.fillIndicatorEnabled) return;
            if (this.hoveredSlot == null || !this.hoveredSlot.hasItem()) return;
            ItemStack containerStack = this.hoveredSlot.getItem();
            if (!ContainerHelper.isContainer(containerStack)) return;

            NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
            var player = Minecraft.getInstance().player;
            if (player == null || player.containerMenu.getCarried().isEmpty()) return;

            int usedSlots = bettershulker$countNonNullSlots(contents);
            float fillFraction = (float) usedSlots / Math.max(1, contents.size());

            int slotX = this.leftPos + this.hoveredSlot.x;
            int slotY = this.topPos + this.hoveredSlot.y;

            graphics.fill(slotX + 1, slotY + 15, slotX + 17, slotY + 17, 0x80000000);

            int filledWidth = Math.round(fillFraction * 16);
            if (filledWidth > 0) {
                int r, g;
                if (fillFraction < 0.5f) {
                    float t = fillFraction / 0.5f;
                    r = (int)(0xFF * t);
                    g = 0xFF;
                } else {
                    float t = (fillFraction - 0.5f) / 0.5f;
                    r = 0xFF;
                    g = (int)(0xFF * (1.0f - t));
                }
                int fillColor = 0xFF000000 | (r << 16) | (g << 8);
                graphics.fill(slotX + 1, slotY + 15, slotX + 1 + filledWidth, slotY + 17, fillColor);
            }
        } catch (Exception e) {
            System.out.println("bettershulker$renderContainerOverlay error: " + e);
        }
    }

    @Unique
    private static int bettershulker$countNonNullSlots(NonNullList<ItemStack> contents) {
        int count = 0;
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) count++;
        }
        return count;
    }

    @Unique
    private boolean bettershulker$isHoveringContainer() {
        return this.hoveredSlot != null && this.hoveredSlot.hasItem()
                && ContainerHelper.isContainer(this.hoveredSlot.getItem())
                && bettershulker$hasContents(this.hoveredSlot.getItem());
    }

    @Unique
    private int bettershulker$findVirtualInventorySlot(NonNullList<Slot> slots, ItemStack stack, java.util.Map<Integer, ItemStack> virtualInv) {
        // First pass: try to merge with existing slots that have room
        for (Slot slot : slots) {
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)
                || slot.getContainerSlot() >= 36) continue;
            
            ItemStack virtualStack = virtualInv.get(slot.index);
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
                        return slot.index;
                    }
                }
            }
        }

        // Second pass: put into the first empty slot
        for (Slot slot : slots) {
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)
                || slot.getContainerSlot() >= 36) continue;
            
            ItemStack virtualStack = virtualInv.get(slot.index);
            if (virtualStack == null || virtualStack.isEmpty()) {
                virtualInv.put(slot.index, stack.copy());
                return slot.index;
            }
        }

        return -1;
    }

    @Unique
    private void bettershulker$processMultiSelectExtract() {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        ItemStack containerStack = BetterShulkerClient.getActiveContainerStack();
        if (containerStack.isEmpty()) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem() && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
                containerStack = this.hoveredSlot.getItem();
            } else if (ContainerHelper.isContainer(carried)) {
                containerStack = carried;
            }
        }
        if (containerStack.isEmpty()) return;

        int containerSlotIndex = (this.hoveredSlot != null && this.hoveredSlot.getItem() == containerStack) ? this.hoveredSlot.index : -1;

        NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
        java.util.Set<Integer> selectedSet = BetterShulkerClient.getSelectedSlotsSet();
        
        // Build virtual inventory state map for player inventory slots
        java.util.Map<Integer, ItemStack> virtualInv = new java.util.HashMap<>();
        for (Slot slot : self.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() < 36) {
                virtualInv.put(slot.index, slot.getItem().copy());
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

            int targetSlotIdx = bettershulker$findVirtualInventorySlot(self.getMenu().slots, shulkerStack, virtualInv);
            if (targetSlotIdx != -1) {
                bettershulker$sendInteractPayload(
                    containerSlotIndex, targetIdx, ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), targetSlotIdx);
            }
        }

        BetterShulkerClient.clearSelectedSlotsSet();
        bettershulker$playClientSound(firstExtracted, false);
    }

    @Unique
    private void bettershulker$processSearchExtract() {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        ItemStack containerStack = BetterShulkerClient.getActiveContainerStack();
        if (containerStack.isEmpty()) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem() && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
                containerStack = this.hoveredSlot.getItem();
            } else if (ContainerHelper.isContainer(carried)) {
                containerStack = carried;
            }
        }
        if (containerStack.isEmpty()) return;

        int containerSlotIndex = (this.hoveredSlot != null && this.hoveredSlot.getItem() == containerStack) ? this.hoveredSlot.index : -1;

        NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
        String query = BetterShulkerClient.getSearchQuery();
        if (query.isEmpty()) return;

        // Build virtual inventory state map for player inventory slots
        java.util.Map<Integer, ItemStack> virtualInv = new java.util.HashMap<>();
        for (Slot slot : self.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() < 36) {
                virtualInv.put(slot.index, slot.getItem().copy());
            }
        }

        ItemStack firstExtracted = ItemStack.EMPTY;
        // Search match loop
        for (int targetIdx = 0; targetIdx < contents.size(); targetIdx++) {
            ItemStack shulkerStack = contents.get(targetIdx);
            if (shulkerStack.isEmpty()) continue;

            if (com.bettershulker.client.render.ShulkerTooltipComponent.parseAndMatchQuery(shulkerStack, query)) {
                if (firstExtracted.isEmpty()) {
                    firstExtracted = shulkerStack;
                }
                int targetSlotIdx = bettershulker$findVirtualInventorySlot(self.getMenu().slots, shulkerStack, virtualInv);
                if (targetSlotIdx != -1) {
                    bettershulker$sendInteractPayload(
                        containerSlotIndex, targetIdx, ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), targetSlotIdx);
                }
            }
        }

        BetterShulkerClient.setSearchQuery(""); // Clear search query after batch extraction
        BetterShulkerClient.setSearchFocused(false);
        bettershulker$playClientSound(firstExtracted, false);
    }

    @Unique
    private void bettershulker$processSingleSlotExtract() {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        ItemStack containerStack = BetterShulkerClient.getActiveContainerStack();
        if (containerStack.isEmpty()) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem() && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
                containerStack = this.hoveredSlot.getItem();
            } else if (ContainerHelper.isContainer(carried)) {
                containerStack = carried;
            }
        }
        if (containerStack.isEmpty()) return;

        int containerSlotIndex = (this.hoveredSlot != null && this.hoveredSlot.getItem() == containerStack) ? this.hoveredSlot.index : -1;

        NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
        int targetIdx = bettershulker$getExtractionIndex(containerStack);
        if (targetIdx < 0 || targetIdx >= contents.size()) return;
        ItemStack shulkerStack = contents.get(targetIdx);
        if (shulkerStack.isEmpty()) return;

        // Build virtual inventory state map for player inventory slots
        java.util.Map<Integer, ItemStack> virtualInv = new java.util.HashMap<>();
        for (Slot slot : self.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() < 36) {
                virtualInv.put(slot.index, slot.getItem().copy());
            }
        }

        int targetSlotIdx = bettershulker$findVirtualInventorySlot(self.getMenu().slots, shulkerStack, virtualInv);
        if (targetSlotIdx != -1) {
            bettershulker$sendInteractPayload(
                containerSlotIndex, targetIdx, ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), targetSlotIdx);
            bettershulker$playClientSound(shulkerStack, false);
        }
    }

    @Unique
    private void bettershulker$triggerActualSort() {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        ItemStack containerStack = BetterShulkerClient.getActiveContainerStack();
        if (containerStack.isEmpty()) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem() && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
                containerStack = this.hoveredSlot.getItem();
            } else if (ContainerHelper.isContainer(carried)) {
                containerStack = carried;
            }
        }
        if (containerStack.isEmpty()) return;

        int containerSlotIndex = (this.hoveredSlot != null && this.hoveredSlot.getItem() == containerStack) ? this.hoveredSlot.index : -1;

        // Cycle sort mode on the client to send the new mode to the server
        BetterShulkerClient.cycleSortMode();
        var newMode = BetterShulkerClient.getCurrentSortMode();
        if (newMode == BetterShulkerClient.SortMode.NONE) {
            BetterShulkerClient.cycleSortMode(); // Skip NONE so we always sort when pressing G
            newMode = BetterShulkerClient.getCurrentSortMode();
        }

        BetterShulkerClient.setLastSortTime(System.currentTimeMillis());

        bettershulker$sendInteractPayload(
            containerSlotIndex, newMode.ordinal(), ContainerInteractPayload.InteractType.SORT.toId(), -1);
    }

    @Unique
    private void bettershulker$triggerRestockOrDeposit(boolean deposit) {
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        ItemStack containerStack = BetterShulkerClient.getActiveContainerStack();
        if (containerStack.isEmpty()) {
            if (this.hoveredSlot != null && this.hoveredSlot.hasItem() && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
                containerStack = this.hoveredSlot.getItem();
            } else if (ContainerHelper.isContainer(carried)) {
                containerStack = carried;
            }
        }
        if (containerStack.isEmpty()) return;

        int containerSlotIndex = (this.hoveredSlot != null && this.hoveredSlot.getItem() == containerStack) ? this.hoveredSlot.index : -1;

        var actionType = deposit ? ContainerInteractPayload.InteractType.DEPOSIT : ContainerInteractPayload.InteractType.RESTOCK;

        bettershulker$sendInteractPayload(
            containerSlotIndex, -1, actionType.toId(), -1);
    }


    @Inject(method = "removed", at = @At("HEAD"))
    private void bettershulker$onRemoved(CallbackInfo ci) {
        bettershulker$resetDragState();
        bettershulker$tapHandled = false;
        bettershulker$selectKeyWasDown = false;
        bettershulker$lastTooltipScrollTime = 0L;
        BetterShulkerClient.setFilterItemStack(ItemStack.EMPTY);
        BetterShulkerClient.setSearchFocused(false);
        BetterShulkerClient.setSearchQuery("");
        BetterShulkerClient.clearSelectedSlotsSet();
    }

    @Unique
    private void bettershulker$sendInteractPayload(int containerSlotId, int targetIndex, int actionId, int inventorySlotId) {
        bettershulker$predictAction(containerSlotId, targetIndex, actionId, inventorySlotId);
        ClientPlayNetworking.send(new ContainerInteractPayload(containerSlotId, targetIndex, actionId, inventorySlotId));
    }

    @Unique
    private void bettershulker$predictAction(int containerSlotId, int targetIndex, int actionId, int inventorySlotId) {
        try {
            var self = bettershulker$self();
            ItemStack carried = self.getMenu().getCarried();
            ItemStack containerStack = ItemStack.EMPTY;
            if (containerSlotId == -1) {
                containerStack = carried;
            } else if (containerSlotId >= 0 && containerSlotId < self.getMenu().slots.size()) {
                containerStack = self.getMenu().slots.get(containerSlotId).getItem();
            } else if (containerSlotId == -2) {
                for (Slot slot : self.getMenu().slots) {
                    if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() < 36) {
                        if (ContainerHelper.isEnderChest(slot.getItem())) {
                            containerStack = slot.getItem();
                            break;
                        }
                    }
                }
            }

            NonNullList<ItemStack> ecContents = null;
            if (containerStack.isEmpty() ? (containerSlotId == -2) : ContainerHelper.isEnderChest(containerStack)) {
                ecContents = BetterShulkerClient.getEnderChestContents();
            }

            long txId = BetterShulkerClient.startPrediction(carried, containerStack, containerSlotId, ecContents);

            if (inventorySlotId >= 0 && inventorySlotId < self.getMenu().slots.size()) {
                BetterShulkerClient.addOriginalSlotSnapshot(txId, inventorySlotId, self.getMenu().slots.get(inventorySlotId).getItem());
            }

            ContainerInteractPayload.InteractType action = ContainerInteractPayload.InteractType.fromId(actionId);
            boolean isEnder = containerStack.isEmpty() ? (containerSlotId == -2) : ContainerHelper.isEnderChest(containerStack);

            if (isEnder) {
                bettershulker$predictEnderChest(txId, targetIndex, action, inventorySlotId);
            } else if (ContainerHelper.isShulkerBox(containerStack)) {
                bettershulker$predictShulkerBox(txId, containerSlotId, containerStack, targetIndex, action, inventorySlotId);
            }
        } catch (Exception e) {
            BetterShulkerMod$LOGGER$info("bettershulker$predictAction error: " + e);
        }
    }

    @Unique
    private static void BetterShulkerMod$LOGGER$info(String msg) {
        com.bettershulker.BetterShulkerMod.LOGGER.info("[BetterShulker-ClientPrediction] " + msg);
    }

    @Unique
    private void bettershulker$commitPredictedContainerStack(AbstractContainerScreen<?> self, int containerSlotId, ItemStack containerStack) {
        if (containerStack.isEmpty()) return;

        // Push the predicted component change back into the same UI slot/cursor immediately.
        // Mutating the ItemStack component alone can leave the rendered tooltip waiting for the
        // next server menu sync, which feels like item insertion lag.
        if (containerSlotId == -1) {
            self.getMenu().setCarried(containerStack);
        } else if (containerSlotId >= 0 && containerSlotId < self.getMenu().slots.size()) {
            self.getMenu().slots.get(containerSlotId).set(containerStack);
        }

        BetterShulkerClient.setActiveContainerStack(containerStack);
    }

    @Unique
    private void bettershulker$predictShulkerBox(long txId, int containerSlotId, ItemStack containerStack,
                                                  int targetIndex, ContainerInteractPayload.InteractType action, int inventorySlotId) {
        var self = bettershulker$self();
        NonNullList<ItemStack> contents = ContainerHelper.getContainerContents(containerStack);
        ItemStack cursorStack = self.getMenu().getCarried();

        switch (action) {
            case INSERT -> {
                if (cursorStack.isEmpty()) return;
                ItemStack remainder = ContainerHelper.tryInsert(contents, cursorStack.copy(), false);
                self.getMenu().setCarried(remainder);
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                ItemStack remainder = ContainerHelper.tryInsert(contents, singleItem, true);
                if (remainder.isEmpty()) {
                    cursorStack.shrink(1);
                    self.getMenu().setCarried(cursorStack.isEmpty() ? ItemStack.EMPTY : cursorStack);
                }
            }
            case EXTRACT -> {
                if (!cursorStack.isEmpty()) return;
                ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                if (!extracted.isEmpty()) {
                    self.getMenu().setCarried(extracted);
                }
            }
            case EXTRACT_ONE -> {
                ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, true);
                if (!extracted.isEmpty()) {
                    if (inventorySlotId >= 0 && inventorySlotId < self.getMenu().slots.size()) {
                        Slot invSlot = self.getMenu().slots.get(inventorySlotId);
                        ItemStack invStack = invSlot.getItem();
                        if (invStack.isEmpty()) {
                            invSlot.set(extracted);
                        } else if (ItemStack.isSameItemSameComponents(invStack, extracted)
                                && invStack.getCount() < invStack.getMaxStackSize()) {
                            invStack.grow(1);
                        } else {
                            contents.set(targetIndex, extracted);
                        }
                    } else if (cursorStack.isEmpty()) {
                        self.getMenu().setCarried(extracted);
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                            && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                        cursorStack.grow(1);
                    } else {
                        contents.set(targetIndex, extracted);
                    }
                }
            }
            case SWEEP_INSERT -> {
                if (inventorySlotId < 0 || inventorySlotId >= self.getMenu().slots.size()) return;
                Slot targetSlot = self.getMenu().slots.get(inventorySlotId);
                if (!(targetSlot.container instanceof net.minecraft.world.entity.player.Inventory)) return;
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                ItemStack remainder = ContainerHelper.tryInsert(contents, invStack.copy(), false);
                targetSlot.set(remainder);
            }
            case SWEEP_EXTRACT -> {
                if (targetIndex < 0 || targetIndex >= contents.size()) return;
                ItemStack shulkerStack = contents.get(targetIndex);
                if (shulkerStack.isEmpty()) return;

                if (inventorySlotId == -1) {
                    if (cursorStack.isEmpty()) {
                        ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                        self.getMenu().setCarried(extracted);
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            cursorStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                        }
                    }
                } else {
                    if (inventorySlotId < 0 || inventorySlotId >= self.getMenu().slots.size()) return;
                    Slot invSlot = self.getMenu().slots.get(inventorySlotId);
                    ItemStack invStack = invSlot.getItem();
                    if (invStack.isEmpty()) {
                        ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                        invSlot.set(extracted);
                    } else if (ItemStack.isSameItemSameComponents(invStack, shulkerStack)) {
                        int canFit = invStack.getMaxStackSize() - invStack.getCount();
                        if (canFit > 0) {
                            ItemStack extracted = ContainerHelper.tryExtract(contents, targetIndex, false);
                            int toAdd = Math.min(canFit, extracted.getCount());
                            invStack.grow(toAdd);
                            if (extracted.getCount() > toAdd) {
                                contents.set(targetIndex, extracted.copyWithCount(extracted.getCount() - toAdd));
                            }
                        }
                    }
                }
            }
            case SORT -> {
                ContainerHelper.sortContents(contents, targetIndex);
            }
            case RESTOCK -> {
                ContainerHelper.restockContents(contents, self.getMenu().slots);
            }
            case DEPOSIT -> {
                ContainerHelper.depositContents(contents, self.getMenu().slots, containerSlotId);
            }
        }

        ContainerHelper.setContainerContents(containerStack, contents);
        bettershulker$commitPredictedContainerStack(self, containerSlotId, containerStack);
    }

    @Unique
    private void bettershulker$predictEnderChest(long txId, int targetIndex, ContainerInteractPayload.InteractType action, int inventorySlotId) {
        var self = bettershulker$self();
        NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();
        if (contents == null) return;
        ItemStack cursorStack = self.getMenu().getCarried();

        switch (action) {
            case INSERT -> {
                if (cursorStack.isEmpty()) return;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, cursorStack)) {
                        int canFit = existing.getMaxStackSize() - existing.getCount();
                        int toInsert = Math.min(canFit, cursorStack.getCount());
                        if (toInsert > 0) {
                            existing.grow(toInsert);
                            cursorStack.shrink(toInsert);
                        }
                    }
                    if (cursorStack.isEmpty()) break;
                }
                if (!cursorStack.isEmpty()) {
                    while (cursorStack.getCount() > 0) {
                        int bestSlot = ContainerHelper.findSmartMergeEmptySlot(contents, cursorStack);
                        if (bestSlot == -1) break;
                        int toInsert = Math.min(cursorStack.getMaxStackSize(), cursorStack.getCount());
                        contents.set(bestSlot, cursorStack.copyWithCount(toInsert));
                        cursorStack.shrink(toInsert);
                    }
                }
            }
            case INSERT_ONE -> {
                if (cursorStack.isEmpty()) return;
                ItemStack singleItem = cursorStack.copyWithCount(1);
                boolean inserted = false;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, singleItem)) {
                        if (existing.getCount() < existing.getMaxStackSize()) {
                            existing.grow(1);
                            inserted = true;
                            break;
                        }
                    }
                }
                if (!inserted) {
                    int bestSlot = ContainerHelper.findSmartMergeEmptySlot(contents, singleItem);
                    if (bestSlot != -1) {
                        contents.set(bestSlot, singleItem);
                        inserted = true;
                    }
                }
                if (inserted) {
                    cursorStack.shrink(1);
                }
            }
            case EXTRACT -> {
                if (!cursorStack.isEmpty()) return;
                ItemStack extracted = contents.get(targetIndex).copy();
                if (!extracted.isEmpty()) {
                    contents.set(targetIndex, ItemStack.EMPTY);
                    self.getMenu().setCarried(extracted);
                }
            }
            case EXTRACT_ONE -> {
                ItemStack slotStack = contents.get(targetIndex);
                if (slotStack.isEmpty()) return;
                ItemStack extracted = slotStack.copyWithCount(1);
                if (cursorStack.isEmpty()) {
                    self.getMenu().setCarried(extracted);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                    }
                } else if (ItemStack.isSameItemSameComponents(cursorStack, extracted)
                        && cursorStack.getCount() < cursorStack.getMaxStackSize()) {
                    cursorStack.grow(1);
                    slotStack.shrink(1);
                    if (slotStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                    }
                }
            }
            case SWEEP_INSERT -> {
                if (inventorySlotId < 0 || inventorySlotId >= self.getMenu().slots.size()) return;
                Slot targetSlot = self.getMenu().slots.get(inventorySlotId);
                if (!(targetSlot.container instanceof net.minecraft.world.entity.player.Inventory)) return;
                ItemStack invStack = targetSlot.getItem();
                if (invStack.isEmpty()) return;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack existing = contents.get(i);
                    if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, invStack)) {
                        int canFit = existing.getMaxStackSize() - existing.getCount();
                        int toInsert = Math.min(canFit, invStack.getCount());
                        if (toInsert > 0) {
                            existing.grow(toInsert);
                            invStack.shrink(toInsert);
                        }
                    }
                    if (invStack.isEmpty()) break;
                }
                if (!invStack.isEmpty()) {
                    while (invStack.getCount() > 0) {
                        int bestSlot = ContainerHelper.findSmartMergeEmptySlot(contents, invStack);
                        if (bestSlot == -1) break;
                        int toInsert = Math.min(invStack.getMaxStackSize(), invStack.getCount());
                        contents.set(bestSlot, invStack.copyWithCount(toInsert));
                        invStack.shrink(toInsert);
                    }
                }
                self.getMenu().slots.get(inventorySlotId).set(invStack);
            }
            case SWEEP_EXTRACT -> {
                ItemStack shulkerStack = contents.get(targetIndex);
                if (shulkerStack.isEmpty()) return;

                if (inventorySlotId == -1) {
                    if (cursorStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                        self.getMenu().setCarried(shulkerStack.copy());
                    } else if (ItemStack.isSameItemSameComponents(cursorStack, shulkerStack)) {
                        int canFit = cursorStack.getMaxStackSize() - cursorStack.getCount();
                        int toAdd = Math.min(canFit, shulkerStack.getCount());
                        if (toAdd > 0) {
                            cursorStack.grow(toAdd);
                            shulkerStack.shrink(toAdd);
                            if (shulkerStack.isEmpty()) {
                                contents.set(targetIndex, ItemStack.EMPTY);
                            }
                        }
                    }
                } else {
                    if (inventorySlotId < 0 || inventorySlotId >= self.getMenu().slots.size()) return;
                    Slot invSlot = self.getMenu().slots.get(inventorySlotId);
                    ItemStack invStack = invSlot.getItem();
                    if (invStack.isEmpty()) {
                        contents.set(targetIndex, ItemStack.EMPTY);
                        invSlot.set(shulkerStack.copy());
                    } else if (ItemStack.isSameItemSameComponents(invStack, shulkerStack)) {
                        int canFit = invStack.getMaxStackSize() - invStack.getCount();
                        int toAdd = Math.min(canFit, shulkerStack.getCount());
                        if (toAdd > 0) {
                            invStack.grow(toAdd);
                            shulkerStack.shrink(toAdd);
                            if (shulkerStack.isEmpty()) {
                                contents.set(targetIndex, ItemStack.EMPTY);
                            }
                        }
                    }
                }
            }
            case SORT -> {
                ContainerHelper.sortContents(contents, targetIndex);
            }
            case RESTOCK -> {
                ContainerHelper.restockContents(contents, self.getMenu().slots);
            }
            case DEPOSIT -> {
                ContainerHelper.depositContents(contents, self.getMenu().slots, -2); // containerSlotId for Ender Chest prediction is -2
            }
        }
    }

    @Unique
    private void bettershulker$verifyPredictions() {
        try {
            var self = bettershulker$self();
            ItemStack carried = self.getMenu().getCarried();
            long now = System.currentTimeMillis();
            java.util.List<BetterShulkerClient.PredictionTransaction> txs = BetterShulkerClient.getActiveTransactions();

            for (int idx = txs.size() - 1; idx >= 0; idx--) {
                BetterShulkerClient.PredictionTransaction tx = txs.get(idx);

                // Client prediction is only an instant visual layer; the server still corrects state
                // through the normal menu sync. The old rollback detector compared slots against
                // their pre-prediction values every frame, which caused valid extractions to look
                // like items spawned elsewhere and then slid into the real slot when a transient
                // server sync briefly matched the old state. Treat any observed change as accepted
                // and silently expire unchanged transactions instead of animating false rollbacks.
                boolean accepted = false;

                if (!tx.originalCarried.isEmpty()
                        && (!ItemStack.isSameItemSameComponents(carried, tx.originalCarried)
                        || carried.getCount() != tx.originalCarried.getCount())) {
                    accepted = true;
                }

                if (!accepted) {
                    for (java.util.Map.Entry<Integer, ItemStack> entry : tx.originalSlots.entrySet()) {
                        int slotId = entry.getKey();
                        ItemStack orig = entry.getValue();
                        if (slotId >= 0 && slotId < self.getMenu().slots.size()) {
                            ItemStack current = self.getMenu().slots.get(slotId).getItem();
                            if (!ItemStack.isSameItemSameComponents(current, orig) || current.getCount() != orig.getCount()) {
                                accepted = true;
                                break;
                            }
                        }
                    }
                }

                if (accepted || now - tx.timestamp > 750) {
                    txs.remove(idx);
                }
            }
        } catch (Exception e) {
            BetterShulkerMod$LOGGER$info("bettershulker$verifyPredictions error: " + e);
        }
    }

    @Unique
    private void bettershulker$renderRollbackAnimations(GuiGraphicsExtractor graphics) {
        try {
            long now = System.currentTimeMillis();
            java.util.List<BetterShulkerClient.RollbackAnimation> rollbacks = BetterShulkerClient.getActiveRollbacks();
            for (int idx = rollbacks.size() - 1; idx >= 0; idx--) {
                BetterShulkerClient.RollbackAnimation anim = rollbacks.get(idx);
                long elapsed = now - anim.startTime;
                if (elapsed >= anim.durationMs) {
                    rollbacks.remove(idx);
                    continue;
                }

                double t = (double) elapsed / anim.durationMs;
                double easeT = 1.0 - Math.pow(1.0 - t, 3.0); // cubic ease-out
                double currentX = anim.startX + (anim.endX - anim.startX) * easeT;
                double currentY = anim.startY + (anim.endY - anim.startY) * easeT;

                graphics.item(anim.stack, (int)currentX - 8, (int)currentY - 8);
            }
        } catch (Exception e) {
            BetterShulkerMod$LOGGER$info("bettershulker$renderRollbackAnimations error: " + e);
        }
    }

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void bettershulker$onExtractSlotHead(GuiGraphicsExtractor graphics, Slot slot, int x, int y, CallbackInfo ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        if (!carried.isEmpty() && ContainerHelper.isShulkerBox(slot.getItem()) && !ContainerHelper.isShulkerBox(carried)) {
            if (this.bettershulker$wigglePushed) {
                graphics.pose().popMatrix();
                this.bettershulker$wigglePushed = false;
            }
            // Apply organic figure-8 wiggle translation
            long time = System.currentTimeMillis();
            double angle = (time % 250) * (2 * Math.PI / 250.0);
            float wiggleRange = 0.8f;
            float wobbleX = (float) Math.sin(angle) * wiggleRange;
            float wobbleY = (float) Math.cos(angle * 2) * (wiggleRange * 0.5f);

            graphics.pose().pushMatrix();
            graphics.pose().translate(wobbleX, wobbleY);
            this.bettershulker$wigglePushed = true;
        }
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void bettershulker$onExtractSlotReturn(GuiGraphicsExtractor graphics, Slot slot, int x, int y, CallbackInfo ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;
        var self = bettershulker$self();
        ItemStack carried = self.getMenu().getCarried();
        if (!carried.isEmpty() && ContainerHelper.isShulkerBox(slot.getItem()) && !ContainerHelper.isShulkerBox(carried)) {
            if (this.bettershulker$wigglePushed) {
                // Pop the wiggle translation matrix
                graphics.pose().popMatrix();
                this.bettershulker$wigglePushed = false;
            }

            // Draw a gorgeous pixel-perfect emerald green plus icon with a white center dot and black shadow in top-right corner
            int px = slot.x + 11;
            int py = slot.y + 1;

            // Black shadow (offset 1px down-right)
            graphics.fill(px + 2 + 1, py + 1, px + 3 + 1, py + 5 + 1, 0xFF000000);
            graphics.fill(px + 1, py + 2 + 1, px + 5 + 1, py + 3 + 1, 0xFF000000);

            // Green plus core (emerald/lime green)
            graphics.fill(px + 2, py, px + 3, py + 5, 0xFF55FF55);
            graphics.fill(px, py + 2, px + 5, py + 3, 0xFF55FF55);

            // Center white dot for premium depth
            graphics.fill(px + 2, py + 2, px + 3, py + 3, 0xFFFFFFFF);
        }
    }
}

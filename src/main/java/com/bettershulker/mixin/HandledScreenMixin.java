package com.bettershulker.mixin;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.BetterShulkerClient;
import com.bettershulker.client.interact.ContainerPrediction;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.MenuSlotRef;
import com.bettershulker.util.ContainerHelper;

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
import java.util.ArrayList;
import java.util.HashMap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** Sentinel for "no container under the tooltip", distinct from every {@link MenuSlotRef}. */
    @Unique
    private static final int bettershulker$NO_ACTIVE_CONTAINER = Integer.MIN_VALUE;

    /** Every carried container shares one reference; there is no slot to tell them apart by. */
    @Unique
    private static final int bettershulker$CARRIED_ACTIVE_CONTAINER = Integer.MIN_VALUE + 1;

    /** Which container the selection currently belongs to, so a switch can be noticed. */
    @Unique
    private static int bettershulker$lastActiveContainerRef = bettershulker$NO_ACTIVE_CONTAINER;

    @Unique
    private boolean bettershulker$bouncePushed = false;

    /** Whether the slot being drawn right now can take the carried stack, decided once per slot. */
    @Unique
    private boolean bettershulker$slotAcceptsCarried = false;

    /** One hop of the "drop this in me" bounce, in milliseconds. */
    @Unique
    private static final long BOUNCE_PERIOD_MS = 1500L;

    /** Peak lift of that hop, in GUI pixels. */
    @Unique
    private static final float BOUNCE_HEIGHT = 2.0f;

    protected HandledScreenMixin(Component title) {
        super(title);
    }

    /**
     * Physical held-state of a key mapping.
     *
     * <p>Delegates rather than repeating the GLFW query: this was a byte-identical copy of the
     * client one, so a fix to either had to be remembered twice.</p>
     */
    @Unique
    private static boolean bettershulker$isKeyHeld(KeyMapping key) {
        return BetterShulkerClient.isKeyHeld(key);
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
    private record bettershulker$ActiveContainer(ItemStack stack, int slotId) {}

    @Unique
    private bettershulker$ActiveContainer bettershulker$getActiveContainer() {
        var player = Minecraft.getInstance().player;
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return new bettershulker$ActiveContainer(ItemStack.EMPTY, -1);
        }

        ItemStack carried = bettershulker$self().getMenu().getCarried();
        ItemStack containerStack = ItemStack.EMPTY;
        // Always prefer the live menu stack. Prediction and server menu sync can replace the
        // ItemStack object while the tooltip state still holds the previous frame's copy.
        if (this.hoveredSlot != null && this.hoveredSlot.hasItem()
                && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
            containerStack = this.hoveredSlot.getItem();
        } else if (ContainerHelper.isContainer(carried)) {
            containerStack = carried;
        } else if (!BetterShulkerClient.getActiveContainerStack().isEmpty()) {
            containerStack = BetterShulkerClient.getActiveContainerStack();
        }

        Slot sourceSlot = this.hoveredSlot != null && this.hoveredSlot.hasItem()
                && ContainerHelper.isContainer(this.hoveredSlot.getItem())
                && ItemStack.isSameItemSameComponents(this.hoveredSlot.getItem(), containerStack)
                ? this.hoveredSlot
                : null;
        if (!ContainerHelper.canAccessContainer(containerStack, player)
                || (sourceSlot != null && !bettershulker$canModifyContainerSlot(sourceSlot))) {
            return new bettershulker$ActiveContainer(ItemStack.EMPTY, -1);
        }
        return new bettershulker$ActiveContainer(containerStack, MenuSlotRef.encode(sourceSlot, player));
    }

    @Unique
    private boolean bettershulker$canModifyContainerSlot(Slot slot) {
        var player = Minecraft.getInstance().player;
        return player != null
                && player.isAlive()
                && !player.isSpectator()
                && slot.isActive()
                && !slot.isFake()
                && slot.allowModification(player)
                && ContainerHelper.canAccessContainer(slot.getItem(), player);
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
            && bettershulker$canModifyContainerSlot(this.hoveredSlot)
            && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
            if (!bettershulker$extractFromSlotToInventory(self, this.hoveredSlot)) return;
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
            && bettershulker$canModifyContainerSlot(this.hoveredSlot)
            && ContainerHelper.isContainer(this.hoveredSlot.getItem())) {
            
            // Safety check: Prevent nesting a Shulker Box inside another Shulker Box
            if (ContainerHelper.isShulkerBox(carried) && ContainerHelper.isShulkerBox(this.hoveredSlot.getItem())) {
                return;
            }

            if (!bettershulker$insertFromCursorToContainer(self, this.hoveredSlot, carried)) return;
            bettershulker$tapHandled = true;
            ci.setReturnValue(true);
            ci.cancel();
            return;
        }

        if (!ContainerHelper.isContainer(carried)
                || !ContainerHelper.canAccessContainer(carried, Minecraft.getInstance().player)) return;

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
                    if (!bettershulker$tapExtractToSlot(self, this.hoveredSlot)) {
                        // The custom action had no valid destination or source. The initial
                        // mouseClicked was intercepted to start the drag, so replay the
                        // vanilla click instead of swallowing a no-op right-click.
                        this.slotClicked(this.hoveredSlot, this.hoveredSlot.index, button, ContainerInput.PICKUP);
                    }
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
        // Keyed on the encoded reference, not slot.index: a screen with its own menu can leave
        // index degenerate, which would collapse this set and silently drop every slot after the
        // first in a drag.
        if (slot != null && slot.isActive()
                && !bettershulker$processedDragSlots.contains(
                        MenuSlotRef.encode(slot, Minecraft.getInstance().player))) {
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
        var player = Minecraft.getInstance().player;
        // SWEEP_INSERT is defined for player-inventory source slots only. Leave
        // crafting, armor, result, fake, and other menu slots to vanilla instead
        // of marking an invalid custom drag as handled.
        if (player == null
                || !ContainerHelper.isPlayerInventorySlot(slot, player, 36)
                || !slot.allowModification(player)) return;

        if (ContainerHelper.isContainer(carried)) {
            // Safety check: Prevent nesting a Shulker Box inside another Shulker Box
            if (ContainerHelper.isShulkerBox(carried) && ContainerHelper.isShulkerBox(slotStack)) {
                return;
            }

            // A drag crossing a full container used to fire anyway: a sweep sound per slot and a
            // packet the server could only reject, one rate-limiter slot at a time. An Ender Chest
            // whose cache has not arrived reads as empty here, so it stays permissive and lets the
            // server have the final word, the same way the click path does.
            if (!ContainerHelper.canInsert(bettershulker$getContents(carried), slotStack)) {
                return;
            }

            bettershulker$processedDragSlots.add(MenuSlotRef.encode(slot, player));
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, -1, ctrlHeld ? ContainerInteractPayload.InteractType.INSERT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_INSERT.toId(), MenuSlotRef.encode(slot, player));
            bettershulker$playClientSound(slotStack, true);
        }
    }

    @Unique
    private void bettershulker$tryDragExtract(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack carried = self.getMenu().getCarried();
        ItemStack slotStack = slot.getItem();
        boolean ctrlHeld = bettershulker$isCtrlDown();

        if (!ContainerHelper.isContainer(carried)) return;

        // Right-click drag over empty slot → extract (use scroll‑selected slot)
        if (slotStack.isEmpty()) {
            int extractionIndex = bettershulker$getExtractionIndex(carried);
            if (extractionIndex == -1) return;
            ItemStack extractedStack = bettershulker$getContents(carried).get(extractionIndex);
            ItemStack destinationStack = ctrlHeld ? extractedStack.copyWithCount(1) : extractedStack;
            if (!bettershulker$canInsertIntoPlayerSlot(slot, destinationStack)) return;
            int slotRef = MenuSlotRef.encode(slot, Minecraft.getInstance().player);
            bettershulker$processedDragSlots.add(slotRef);
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, extractionIndex, ctrlHeld ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), slotRef);
            bettershulker$playClientSound(extractedStack, false);
        } else if (ctrlHeld && bettershulker$hasMatchingItem(carried, slotStack)) {
            // Right-click drag over occupied slot with matching item + precision mode → extract one matching
            int matchingIndex = bettershulker$findMatchingIndex(carried, slotStack);
            if (matchingIndex == -1) return;
            ItemStack extractedStack = bettershulker$getContents(carried).get(matchingIndex);
            if (!bettershulker$canInsertIntoPlayerSlot(slot, extractedStack.copyWithCount(1))) return;
            int slotRef = MenuSlotRef.encode(slot, Minecraft.getInstance().player);
            bettershulker$processedDragSlots.add(slotRef);
            bettershulker$dragDidWork = true;
            bettershulker$sendInteractPayload(
                -1, matchingIndex, ContainerInteractPayload.InteractType.EXTRACT_ONE.toId(), slotRef);
            bettershulker$playClientSound(extractedStack, false);
        }
    }

    // =========================================================================
    //  Tap extraction — right-click tap extracts selected item to hovered slot
    // =========================================================================
    //  Container-in-slot extraction — right-click on container in inventory extracts selected item
    // =========================================================================

    @Unique
    private boolean bettershulker$extractFromSlotToInventory(AbstractContainerScreen<?> self, Slot containerSlot) {
        int extractionIndex = bettershulker$getExtractionIndex(containerSlot.getItem());
        if (extractionIndex == -1) return false;
        ItemStack extractedStack = bettershulker$getContents(containerSlot.getItem()).get(extractionIndex);
        boolean ctrlHeld = bettershulker$isCtrlDown();
        bettershulker$sendInteractPayload(
            MenuSlotRef.encode(containerSlot, Minecraft.getInstance().player), extractionIndex,
            ctrlHeld ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), -1);
        bettershulker$playClientSound(extractedStack, false);
        return true;
    }

    @Unique
    private boolean bettershulker$insertFromCursorToContainer(AbstractContainerScreen<?> self, Slot containerSlot, ItemStack cursorStack) {
        boolean ctrlHeld = bettershulker$isCtrlDown();
        if (cursorStack.isEmpty()) return false;

        // Use a copy for the capacity check so a full/invalid container does not consume
        // the vanilla right-click. Ender chest contents may be unavailable before sync;
        // in that case the empty preview is only a best-effort client-side check.
        NonNullList<ItemStack> preview = bettershulker$getContents(containerSlot.getItem());
        NonNullList<ItemStack> previewCopy = NonNullList.withSize(preview.size(), ItemStack.EMPTY);
        for (int i = 0; i < preview.size(); i++) {
            previewCopy.set(i, preview.get(i).copy());
        }
        ItemStack remainder = ContainerHelper.tryInsert(previewCopy, cursorStack.copy(), ctrlHeld);
        if (remainder.getCount() >= cursorStack.getCount()) return false;

        bettershulker$sendInteractPayload(
            MenuSlotRef.encode(containerSlot, Minecraft.getInstance().player), -1,
            ctrlHeld ? ContainerInteractPayload.InteractType.INSERT_ONE.toId() : ContainerInteractPayload.InteractType.INSERT.toId(), -1);
        bettershulker$playClientSound(cursorStack, true);
        return true;
    }

    @Unique
    private boolean bettershulker$tapExtractToSlot(AbstractContainerScreen<?> self, Slot slot) {
        ItemStack carried = self.getMenu().getCarried();
        boolean ctrlHeld = bettershulker$isCtrlDown();
        int extractionIndex = bettershulker$getExtractionIndex(carried);
        if (extractionIndex == -1) return false;
        ItemStack extractedStack = bettershulker$getContents(carried).get(extractionIndex);
        ItemStack destinationStack = ctrlHeld ? extractedStack.copyWithCount(1) : extractedStack;
        if (!bettershulker$canInsertIntoPlayerSlot(slot, destinationStack)) return false;
        int action = ctrlHeld
            ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId()
            : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId();
        bettershulker$sendInteractPayload(-1, extractionIndex, action,
            MenuSlotRef.encode(slot, Minecraft.getInstance().player));
        bettershulker$playClientSound(extractedStack, false);
        return true;
    }

    @Unique
    private boolean bettershulker$canInsertIntoPlayerSlot(Slot slot, ItemStack stack) {
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

    @Unique
    private void bettershulker$playClientSound(ItemStack stack, boolean isInsert) {
        ContainerHelper.playInteractionSound(Minecraft.getInstance().player, stack, isInsert,
                BetterShulkerConfig.getSoundVolume());
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
        if (BetterShulkerClient.isCompactModeActive()) {
            NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
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
                            bettershulker$countMergedItems(contents, b),
                            bettershulker$countMergedItems(contents, a));
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
            int size = bettershulker$getContainerSize(containerStack);
            if (size <= 0) return current;
            // Wrap, rather than snap to an end. A vertical step of +-9 that left the grid used to
            // land on slot 26 or slot 0, throwing the square into a far corner; wrapping carries
            // it down its own column, which is what an arrow key promises.
            int plainMove = Math.floorMod(current + delta, size);

            // Empty slots are not worth stopping on: the square only ever names an extraction
            // target, so a box holding three items used to need two dozen presses to cross. Step
            // by the same delta until something occupied turns up, which keeps a vertical move
            // inside its column and a horizontal one along its row order.
            NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
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

        // Handle arrow-key movement for the tooltip selection square.
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

    @Inject(method = "extractTooltip", at = @At("HEAD"))
    private void bettershulker$setEnderChestTooltipSource(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                           CallbackInfo ci) {
        ItemStack carried = bettershulker$self().getMenu().getCarried();
        int sourceSlotId = EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE;
        if (this.hoveredSlot != null
                && this.hoveredSlot.hasItem()
                && ContainerHelper.isEnderChest(this.hoveredSlot.getItem())) {
            sourceSlotId = MenuSlotRef.encode(this.hoveredSlot, Minecraft.getInstance().player);
        } else if (ContainerHelper.isEnderChest(carried)) {
            sourceSlotId = EnderChestRequestPayload.CARRIED_SOURCE_SLOT;
        }
        BetterShulkerClient.setEnderChestTooltipSourceSlot(sourceSlotId);
    }

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

        int activeRef = bettershulker$NO_ACTIVE_CONTAINER;
        if (tooltipActive && hovering) {
            activeRef = MenuSlotRef.encode(this.hoveredSlot, Minecraft.getInstance().player);
        } else if (tooltipActive && carryingContainer) {
            activeRef = bettershulker$CARRIED_ACTIVE_CONTAINER;
        }
        bettershulker$onActiveContainerChanged(activeRef, hoveredContainer);

        if (altDown && carryingContainer && !hovering) {
            var mc = Minecraft.getInstance();
            if (ContainerHelper.isEnderChest(carried)) {
                BetterShulkerClient.requestEnderChestSync(EnderChestRequestPayload.CARRIED_SOURCE_SLOT);
            }
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
            // Cursor/held Alt-Force tooltips bypass ItemStack#getTooltipLines, so add the
            // container name explicitly. The selected-item label is rendered by the custom
            // component and is anchored to the preview panel's upper edge below this line.
            List<Component> textLines = List.of(carried.getDisplayName());
            graphics.setTooltipForNextFrame(mc.font,
                    textLines, Optional.of(data), mouseX, mouseY);
        }

        BetterShulkerClient.setEnderChestTooltipSourceSlot(EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE);
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
    @Unique
    private void bettershulker$onActiveContainerChanged(int activeRef, ItemStack container) {
        if (activeRef == bettershulker$NO_ACTIVE_CONTAINER || container.isEmpty()) return;
        if (activeRef == bettershulker$lastActiveContainerRef) return;
        // A drag pulls from the carried box while the pointer sweeps across the inventory, so it
        // passes over other boxes on the way. Re-pointing the square there would change which
        // stack the rest of the drag extracts, mid-gesture.
        if (bettershulker$isDragging) return;

        BetterShulkerClient.clearSelectedSlotsSet();

        NonNullList<ItemStack> contents = bettershulker$getContents(container);
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
        bettershulker$lastActiveContainerRef = activeRef;

        // Hold the position when the new box has something there too, which keeps the square
        // where the eye left it between two similar boxes.
        int selected = BetterShulkerClient.getSelectedSlotIndex();
        if (selected >= 0 && selected < contents.size() && !contents.get(selected).isEmpty()) return;
        BetterShulkerClient.setSelectedSlotIndex(Math.max(0, firstOccupied));
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void bettershulker$onGetTooltipFromContainerItem(ItemStack stack,
                                                               CallbackInfoReturnable<List<Component>> ci) {
        if (!BetterShulkerConfig.tooltipEnabled) return;
        if (!ContainerHelper.isContainer(stack)) return;
        List<Component> lines = ci.getReturnValue();
        if (lines.isEmpty()) return;
        // Keep the first vanilla line: it is the container's own name (for example,
        // "Shulker Box"). The custom selected-item label is drawn separately and attached
        // to the preview panel edge, so it does not replace this container name.
        ci.setReturnValue(List.of(lines.getFirst()));
    }

    @Inject(method = {"extractContents"}, at = @At("RETURN"))
    private void bettershulker$onExtractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                  float delta, CallbackInfo ci) {
        ContainerPrediction.verifyPredictions(bettershulker$self());
        bettershulker$renderContainerOverlay(graphics);
        if (this.bettershulker$bouncePushed) {
            graphics.pose().popMatrix();
            this.bettershulker$bouncePushed = false;
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
            BetterShulkerMod.LOGGER.warn("[BetterShulker] Failed to render container fill overlay", e);
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
    private int bettershulker$findVirtualInventorySlot(NonNullList<Slot> slots, ItemStack stack, Map<Integer, ItemStack> virtualInv) {
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

    @Unique
    private void bettershulker$processMultiSelectExtract() {
        var self = bettershulker$self();
        bettershulker$ActiveContainer active = bettershulker$getActiveContainer();
        ItemStack containerStack = active.stack();
        if (containerStack.isEmpty()) return;

        NonNullList<ItemStack> contents = bettershulker$getContents(containerStack);
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

            int targetSlotIdx = bettershulker$findVirtualInventorySlot(self.getMenu().slots, shulkerStack, virtualInv);
            if (targetSlotIdx != -1) {
                bettershulker$sendInteractPayload(
                    active.slotId(), targetIdx, ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId(), targetSlotIdx);
            }
        }

        BetterShulkerClient.clearSelectedSlotsSet();
        bettershulker$playClientSound(firstExtracted, false);
    }

    @Unique
    private void bettershulker$triggerRestockOrDeposit(boolean deposit) {
        bettershulker$ActiveContainer active = bettershulker$getActiveContainer();
        if (active.stack().isEmpty()) return;

        var actionType = deposit ? ContainerInteractPayload.InteractType.DEPOSIT : ContainerInteractPayload.InteractType.RESTOCK;

        bettershulker$sendInteractPayload(
            active.slotId(), -1, actionType.toId(), -1);
    }

    /** Sends an interaction, predicting it locally first. See {@link ContainerPrediction}. */
    @Unique
    private void bettershulker$sendInteractPayload(int containerSlotId, int targetIndex, int actionId, int inventorySlotId) {
        ContainerPrediction.sendInteractPayload(bettershulker$self(), containerSlotId, targetIndex, actionId, inventorySlotId);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void bettershulker$onRemoved(CallbackInfo ci) {
        bettershulker$resetDragState();
        bettershulker$tapHandled = false;
        bettershulker$selectKeyWasDown = false;
        bettershulker$lastTooltipScrollTime = 0L;
        bettershulker$lastActiveContainerRef = bettershulker$NO_ACTIVE_CONTAINER;
        BetterShulkerClient.setTooltipActive(false);
        BetterShulkerClient.setActiveContainerStack(ItemStack.EMPTY);
        BetterShulkerClient.setEnderChestTooltipSourceSlot(EnderChestRequestPayload.ANY_ACCESSIBLE_SOURCE);
        BetterShulkerClient.clearSelectedSlotsSet();
    }

    @Unique
    private boolean bettershulker$slotContainerAcceptsCarried(Slot slot, ItemStack carried) {
        if (carried.isEmpty()) return false;
        ItemStack slotStack = slot.getItem();
        if (!ContainerHelper.isShulkerBox(slotStack) || ContainerHelper.isShulkerBox(carried)) return false;
        return ContainerHelper.canInsert(ContainerHelper.getContainerContents(slotStack), carried);
    }

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void bettershulker$onExtractSlotHead(GuiGraphicsExtractor graphics, Slot slot, int x, int y, CallbackInfo ci) {
        // Decided once here and reused on the way out. The test reads the box's contents, which
        // costs a copy of all 27 slots, and this runs for every slot on screen every frame; asking
        // twice doubled that for nothing. It also guarantees the pose push and pop agree, since
        // both now consult the same recorded answer.
        var self = bettershulker$self();
        this.bettershulker$slotAcceptsCarried = BetterShulkerConfig.tooltipEnabled
                && bettershulker$slotContainerAcceptsCarried(slot, self.getMenu().getCarried());

        if (!BetterShulkerConfig.containerBounceEnabled) return;
        if (this.bettershulker$slotAcceptsCarried) {
            if (this.bettershulker$bouncePushed) {
                graphics.pose().popMatrix();
                this.bettershulker$bouncePushed = false;
            }
            // Slow vertical hop. One half-sine per cycle lifts the box and sets it back down,
            // resting at zero between hops; a full sine would drift it up and down forever and
            // read as a hover rather than a bounce.
            long time = System.currentTimeMillis();
            double phase = (time % BOUNCE_PERIOD_MS) * (Math.PI / BOUNCE_PERIOD_MS);
            float bounceY = -(float) Math.sin(phase) * BOUNCE_HEIGHT;

            graphics.pose().pushMatrix();
            graphics.pose().translate(0.0f, bounceY);
            this.bettershulker$bouncePushed = true;
        }
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void bettershulker$onExtractSlotReturn(GuiGraphicsExtractor graphics, Slot slot, int x, int y, CallbackInfo ci) {
        if (this.bettershulker$slotAcceptsCarried) {
            if (this.bettershulker$bouncePushed) {
                // Pop the bounce translation matrix
                graphics.pose().popMatrix();
                this.bettershulker$bouncePushed = false;
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

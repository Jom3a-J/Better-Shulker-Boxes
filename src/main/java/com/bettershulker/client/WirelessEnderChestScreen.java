package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.render.ShulkerTooltipComponent;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.util.ContainerHelper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import net.minecraft.ChatFormatting;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;

public class WirelessEnderChestScreen extends Screen {

    private static final int SCREEN_BG_COLOR = 0xE505050B; // Deep obsidian background with subtle transparency
    private long openTime;

    public WirelessEnderChestScreen() {
        super(Component.literal("Wireless Ender Chest"));
    }

    @Override
    protected void init() {
        super.init();
        openTime = System.currentTimeMillis();
        // Force refresh from server when opening screen
        BetterShulkerClient.requestEnderChestSync();

        // Auditory Immersion: Play Block Ender Chest Open sound
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENDER_CHEST_OPEN,
                    SoundSource.BLOCKS, 0.5F, 0.9F + player.level().getRandom().nextFloat() * 0.15F);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Keep checking if contents are not loaded yet
        if (BetterShulkerClient.getEnderChestContents() == null) {
            BetterShulkerClient.requestEnderChestSync();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 1. Render obsidian space background
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG_COLOR);

        // 2. Render drifting portal particles rising up
        long timeNow = System.currentTimeMillis() - openTime;
        double particleTime = timeNow / 1000.0;
        for (int p = 0; p < 25; p++) {
            long seed = p * 12345L;
            double speedY = 15.0 + ((seed % 50) / 50.0) * 20.0; // 15 to 35 px/sec
            double startX = (((seed / 50) % 100) / 100.0) * this.width;
            double swaySpeed = 1.0 + ((seed % 10) / 10.0) * 1.5;
            double swayWidth = 8.0 + (((seed / 10) % 20) / 20.0) * 12.0;
            
            int x = (int) (startX + Math.sin(particleTime * swaySpeed + seed) * swayWidth);
            int y = (int) (this.height - (particleTime * speedY + (seed % this.height)) % this.height);
            
            int alpha = (int) (40 + 80 * Math.sin(particleTime * 2.0 + seed));
            alpha = Math.max(0, Math.min(255, alpha));
            
            // Nebula Portal Purple (ARGB)
            int pColor = (alpha << 24) | (0x8B << 16) | (0x32 << 8) | 0xB8;
            graphics.fill(x, y, x + 2, y + 2, pColor);
        }

        // Keep updating client coordinates to track hovered tooltip slot index in extractImage
        BetterShulkerClient.setLastMouseX(mouseX);
        BetterShulkerClient.setLastMouseY(mouseY);
        BetterShulkerClient.setTooltipActive(true);

        NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();

        if (contents == null) {
            // Draw premium loading screen inside the screen boundaries
            int cx = this.width / 2;
            int cy = this.height / 2;
            graphics.centeredText(this.font, Component.literal("Connecting to Ender Network...").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC), cx, cy - 6, 0xFFFFFFFF);
            
            // Draw a decorative loading line
            float pulse = (float) Math.sin(timeNow / 200.0) * 0.5f + 0.5f;
            int loadBarColor = (0xFF << 24) | (((int)(0x89 + (0xFF - 0x89) * pulse)) << 16) | (0x32 << 8) | 0xB8;
            graphics.fill(cx - 60, cy + 10, cx + 60, cy + 11, loadBarColor);

            super.extractRenderState(graphics, mouseX, mouseY, delta);
            return;
        }

        // Determine selection name badge text
        String selectedItemName = "";
        int selectedIndex = BetterShulkerClient.getSelectedSlotIndex();
        if (selectedIndex >= 0 && selectedIndex < contents.size()) {
            ItemStack selectedStack = contents.get(selectedIndex);
            if (!selectedStack.isEmpty()) {
                selectedItemName = selectedStack.getHoverName().getString();
            }
        }

        // Create standard component to render inside center coordinates
        ShulkerTooltipData data = new ShulkerTooltipData(contents, null, true, selectedItemName, "Ender Chest");
        ShulkerTooltipComponent tooltipComponent = new ShulkerTooltipComponent(data);

        int tw = tooltipComponent.getWidth(this.font);
        int th = tooltipComponent.getHeight(this.font);
        int tx = (this.width - tw) / 2;
        int ty = (this.height - th) / 2;

        // Render the tooltips and slots using our standard component
        tooltipComponent.extractImage(this.font, tx, ty, tw, th, graphics);

        // Render instruction badge at the bottom of the screen
        int badgeWidth = 260;
        int badgeHeight = 22;
        int badgeX = (this.width - badgeWidth) / 2;
        int badgeY = this.height - 35;
        
        graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, 0xE0100010);
        graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 1, 0xFF8932B8); // Purple top border
        
        Component instructions = Component.literal("Left-Click: Extract | 1-9: Insert | R: Restock | G: Sort")
                .withStyle(ChatFormatting.GRAY);
        graphics.centeredText(this.font, instructions, this.width / 2, badgeY + 6, 0xFFFFFFFF);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent keyEvent) {
        int keyCode = keyEvent.key();

        // 1. Close conditions
        if (keyCode == GLFW.GLFW_KEY_ESCAPE 
                || Minecraft.getInstance().options.keyInventory.matches(keyEvent)
                || BetterShulkerClient.getWirelessEnderChestKey().matches(keyEvent)) {
            this.onClose();
            return true;
        }

        NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();
        if (contents == null) {
            return super.keyPressed(keyEvent);
        }

        // 2. Cycle selected slot via Arrows
        if (keyCode == GLFW.GLFW_KEY_LEFT || BetterShulkerClient.getScrollLeftKey().matches(keyEvent)) {
            bettershulker$scrollSelectedSlot(-1);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_RIGHT || BetterShulkerClient.getScrollRightKey().matches(keyEvent)) {
            bettershulker$scrollSelectedSlot(1);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_UP) {
            bettershulker$scrollSelectedSlot(-9); // Move up a row
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
            bettershulker$scrollSelectedSlot(9); // Move down a row
            return true;
        }

        // 3. Sorting (G key)
        if (BetterShulkerClient.getSortKey().matches(keyEvent)) {
            BetterShulkerClient.cycleSortMode();
            BetterShulkerClient.setLastSortTime(System.currentTimeMillis());
            int modeVal = BetterShulkerClient.getCurrentSortMode().ordinal();
            {
                ClientPlayNetworking.send(new ContainerInteractPayload(
                    -2, // Wireless indicator
                    modeVal, // Sort mode (1=NAME, 2=COUNT, 3=CATEGORY)
                    ContainerInteractPayload.InteractType.SORT.toId(),
                    -1
                ));
                bettershulker$playClientSound(ItemStack.EMPTY, false);

                // Polished Feedback Overlay
                Minecraft.getInstance().gui.hud.setOverlayMessage(
                    Component.literal("Ender Chest Sorted! (" + BetterShulkerClient.getCurrentSortMode().getDisplayName() + ")")
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                    false
                );
            }
            return true;
        }

        // 4. Restock or Deposit (R key / Shift + R)
        if (BetterShulkerClient.getRestockKey().matches(keyEvent)) {
            boolean shiftHeld = isShiftDown();
            ClientPlayNetworking.send(new ContainerInteractPayload(
                -2, // Wireless indicator
                -1,
                shiftHeld ? ContainerInteractPayload.InteractType.DEPOSIT.toId() : ContainerInteractPayload.InteractType.RESTOCK.toId(),
                -1
            ));
            bettershulker$playClientSound(ItemStack.EMPTY, shiftHeld);

            // Polished Feedback Overlay
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                Component.literal(shiftHeld ? "Inventory Items Deposited to Ender Chest!" : "Hotbar Restocked from Ender Chest!")
                    .withStyle(shiftHeld ? ChatFormatting.GREEN : ChatFormatting.GOLD),
                false
            );
            return true;
        }

        // 5. Hotbar Insertions (1-9 keys)
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int hotbarSlot = keyCode - GLFW.GLFW_KEY_1; // 0..8
            int invSlotId = 36 + hotbarSlot; // Slot in inventory menu
            
            var player = Minecraft.getInstance().player;
            if (player != null) {
                ItemStack hotbarStack = player.inventoryMenu.slots.get(invSlotId).getItem();
                if (!hotbarStack.isEmpty()) {
                    boolean ctrlHeld = isCtrlDown();
                    int action = ctrlHeld
                        ? ContainerInteractPayload.InteractType.INSERT_ONE.toId()
                        : ContainerInteractPayload.InteractType.SWEEP_INSERT.toId();
                    ClientPlayNetworking.send(new ContainerInteractPayload(
                        -2, // Wireless indicator
                        -1, // Smart-merge target index
                        action,
                        invSlotId
                    ));
                    bettershulker$playClientSound(hotbarStack, true);

                    // Polished Feedback Overlay
                    Minecraft.getInstance().gui.hud.setOverlayMessage(
                        Component.literal("Inserted hotbar stack into Ender Chest!").withStyle(ChatFormatting.AQUA),
                        false
                    );
                }
            }
            return true;
        }

        // 6. Extract (E / Enter / Space)
        if (BetterShulkerClient.getExtractKey().matches(keyEvent) || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            int targetSlot = BetterShulkerClient.getHoveredTooltipSlotIndex();
            if (targetSlot < 0) {
                targetSlot = BetterShulkerClient.getSelectedSlotIndex();
            }
            if (targetSlot >= 0) {
                triggerExtraction(targetSlot);
                return true;
            }
        }

        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int delta = verticalAmount != 0 ? (int)Math.signum(-verticalAmount) : (int)Math.signum(-horizontalAmount);
        bettershulker$scrollSelectedSlot(delta);
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean handled) {
        int hoveredIdx = BetterShulkerClient.getHoveredTooltipSlotIndex();
        if (hoveredIdx >= 0) {
            NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();
            if (contents != null && hoveredIdx < contents.size()) {
                ItemStack stackToExtract = contents.get(hoveredIdx);
                if (!stackToExtract.isEmpty()) {
                    int invSlotId = findMergeInventorySlot(stackToExtract);
                    if (invSlotId != -1) {
                        // Left click -> extract all, Right click -> extract one
                        int button = event.button();
                        int action = (button == InputConstants.MOUSE_BUTTON_RIGHT)
                            ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId()
                            : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId();
                        ClientPlayNetworking.send(new ContainerInteractPayload(
                            -2, // Wireless indicator
                            hoveredIdx,
                            action,
                            invSlotId
                        ));
                        bettershulker$playClientSound(stackToExtract, false);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, handled);
    }

    @Override
    public void onClose() {
        BetterShulkerClient.setTooltipActive(false);
        BetterShulkerClient.clearSelectedSlotsSet();
        BetterShulkerClient.setFilterItemStack(ItemStack.EMPTY);
        BetterShulkerClient.setSearchQuery("");
        BetterShulkerClient.setSearchFocused(false);

        // Auditory Immersion: Play Block Ender Chest Close sound
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENDER_CHEST_CLOSE,
                    SoundSource.BLOCKS, 0.5F, 0.85F + player.level().getRandom().nextFloat() * 0.1F);
        }

        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Ender Chest is real-time interaction
    }

    // ── Helper Methods ─────────────────────────────────────────────────────────

    private boolean isCtrlDown() {
        if (!BetterShulkerConfig.precisionModeEnabled) return false;
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private boolean isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private void bettershulker$scrollSelectedSlot(int delta) {
        int oldSlot = BetterShulkerClient.getSelectedSlotIndex();
        NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();
        if (contents == null) return;
        int newSlot = bettershulker$clampScroll(oldSlot, delta, contents);
        if (newSlot != oldSlot) {
            BetterShulkerClient.setSelectedSlotIndex(newSlot);
        }
    }

    private int bettershulker$clampScroll(int current, int delta, NonNullList<ItemStack> contents) {
        if (BetterShulkerClient.isCompactModeActive()) {
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
            int size = contents.size();
            if (size <= 0) return current;
            return Math.floorMod(current + delta, size);
        }
    }

    private void bettershulker$playClientSound(ItemStack stack, boolean isInsert) {
        ContainerHelper.playInteractionSound(Minecraft.getInstance().player, stack, isInsert, BetterShulkerConfig.soundVolume);
    }

    private int findMergeInventorySlot(ItemStack stackToMerge) {
        var player = Minecraft.getInstance().player;
        if (player == null) return -1;
        int bestEmpty = -1;
        // Search inventory slots (9 to 44)
        for (int i = 9; i < 45; i++) {
            var slot = player.inventoryMenu.slots.get(i);
            var existing = slot.getItem();
            if (existing.isEmpty()) {
                if (bestEmpty == -1) {
                    bestEmpty = i;
                }
            } else if (ItemStack.isSameItemSameComponents(existing, stackToMerge)) {
                if (existing.getCount() < existing.getMaxStackSize()) {
                    return i;
                }
            }
        }
        return bestEmpty;
    }

    private void triggerExtraction(int targetSlot) {
        NonNullList<ItemStack> contents = BetterShulkerClient.getEnderChestContents();
        if (contents == null || targetSlot < 0 || targetSlot >= contents.size()) return;
        ItemStack stackToExtract = contents.get(targetSlot);
        if (stackToExtract.isEmpty()) return;

        int invSlotId = findMergeInventorySlot(stackToExtract);
        if (invSlotId != -1) {
            boolean ctrlHeld = isCtrlDown();
            int action = ctrlHeld 
                ? ContainerInteractPayload.InteractType.EXTRACT_ONE.toId() 
                : ContainerInteractPayload.InteractType.SWEEP_EXTRACT.toId();
            ClientPlayNetworking.send(new ContainerInteractPayload(
                -2, // Wireless indicator
                targetSlot,
                action,
                invSlotId
            ));
            bettershulker$playClientSound(stackToExtract, false);
        }
    }
}

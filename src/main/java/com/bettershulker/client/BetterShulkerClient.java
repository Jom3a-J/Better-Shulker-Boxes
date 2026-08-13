package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Better Shulker — Client-side entry point.
 *
 * Responsibilities:
 * Maintains client-side state shared by Fabric and NeoForge:
 *    - Cached ender chest contents (populated by S2C packets)
 *    - Selected slot index (controlled by scroll wheel via mixin)
 *    - Tooltip active flag
 *
 * Loader-specific entrypoints register events and call into this class.
 */
public class BetterShulkerClient {

    // =========================================================================
    //  Keybindings
    // =========================================================================
    // =========================================================================
    //  Client State Definitions
    // =========================================================================
    /** Current selected slot index inside the 9x3 grid (controlled by mouse scroll). */
    private static int selectedSlotIndex = 0;

    /** Whether the tooltip preview is currently active. */
    private static boolean tooltipActive = false;

    private static int hoveredTooltipSlotIndex = -1;
    private static ItemStack activeContainerStack = ItemStack.EMPTY;
    private static final Set<Integer> selectedSlotsSet = new HashSet<>();

    /** Cooldown tracking to limit ender chest sync request packets. */
    private static int lastMouseX = 0;
    private static int lastMouseY = 0;
    // =========================================================================
    //  Visual Animations State
    // =========================================================================

    private static float currentSelectedCol = -1f;
    private static float currentSelectedRow = -1f;
    private static long lastHighlightRenderTime = 0L;

    private static final float[] slotScales = new float[27];
    private static long lastSlotScaleUpdateTime = 0L;

    // =========================================================================
    //  Prediction & Rollbacks State Classes
    // =========================================================================

    /**
     * A prediction carries no Ender Chest snapshot on purpose. Rolling one back on the client
     * cannot be done safely: the acceptance test below is deliberately loose, so a valid action
     * could be undone, and restoring a 27-slot snapshot would also discard any newer prediction
     * made while this one was in flight. The server instead reconciles the cache with a full
     * authoritative snapshot after every interaction.
     */
    public static class PredictionTransaction {
        public final long id;
        public final long timestamp;
        public final ItemStack originalCarried;
        public final ItemStack originalContainer;
        public final int containerSlotId;
        public final Map<Integer, ItemStack> originalSlots = new HashMap<>();

        public PredictionTransaction(long id, ItemStack carried, ItemStack container, int containerSlotId) {
            this.id = id;
            this.timestamp = System.currentTimeMillis();
            this.originalCarried = carried.copy();
            this.originalContainer = container.copy();
            this.containerSlotId = containerSlotId;
        }
    }

    private static long nextTransactionId = 1L;
    private static final List<PredictionTransaction> activeTransactions = new ArrayList<>();

    // =========================================================================
    //  Loader-neutral Client Initialization Hooks




    public static void handleClientTick(Minecraft client) {
        KeyMapping settingsKey = ClientKeybinds.getSettingsKey();
        while (settingsKey != null && settingsKey.consumeClick()) {
            if (client.gui.screen() != null || client.level != null) {
                try {
                    client.setScreenAndShow(BetterShulkerClothConfigScreen.create(client.gui.screen()));
                } catch (Exception e) {
                    BetterShulkerMod.LOGGER.error("[BetterShulker] Failed to open settings screen", e);
                }
            }
        }
    }

    //  Public Accessors & Control Methods
    // =========================================================================


    public static int getSelectedSlotIndex() {
        return selectedSlotIndex;
    }

    public static void setSelectedSlotIndex(int index) {
        selectedSlotIndex = Math.floorMod(index, 27);
    }

    public static void scrollSelectedSlot(int delta) {
        setSelectedSlotIndex(selectedSlotIndex + delta);
    }

    public static boolean isTooltipActive() {
        return tooltipActive;
    }

    public static void setTooltipActive(boolean active) {
        tooltipActive = active;
    }





    public static int getHoveredTooltipSlotIndex() {
        return hoveredTooltipSlotIndex;
    }

    public static void setHoveredTooltipSlotIndex(int index) {
        hoveredTooltipSlotIndex = index;
    }

    public static ItemStack getActiveContainerStack() {
        return activeContainerStack;
    }
 
    public static void setActiveContainerStack(ItemStack stack) {
        activeContainerStack = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    public static Set<Integer> getSelectedSlotsSet() {
        return selectedSlotsSet;
    }

    public static void toggleSelectedSlot(int idx) {
        if (selectedSlotsSet.contains(idx)) {
            selectedSlotsSet.remove(idx);
        } else {
            selectedSlotsSet.add(idx);
        }
    }

    public static void clearSelectedSlotsSet() {
        selectedSlotsSet.clear();
    }

    public static int getLastMouseX() {
        return lastMouseX;
    }

    public static int getLastMouseY() {
        return lastMouseY;
    }

    public static void setLastMouseX(int x) {
        lastMouseX = x;
    }

    public static void setLastMouseY(int y) {
        lastMouseY = y;
    }













    public static float getCurrentSelectedCol() { return currentSelectedCol; }
    public static void setCurrentSelectedCol(float v) { currentSelectedCol = v; }
    public static float getCurrentSelectedRow() { return currentSelectedRow; }
    public static void setCurrentSelectedRow(float v) { currentSelectedRow = v; }
    public static long getLastHighlightRenderTime() { return lastHighlightRenderTime; }
    public static void setLastHighlightRenderTime(long v) { lastHighlightRenderTime = v; }

    public static float[] getSlotScales() { return slotScales; }
    public static long getLastSlotScaleUpdateTime() { return lastSlotScaleUpdateTime; }
    public static void setLastSlotScaleUpdateTime(long v) { lastSlotScaleUpdateTime = v; }

    // =========================================================================
    //  Prediction Methods
    // =========================================================================

    public static long startPrediction(ItemStack carried, ItemStack container, int containerSlotId) {
        long id = nextTransactionId++;
        activeTransactions.add(new PredictionTransaction(id, carried, container, containerSlotId));
        return id;
    }

    public static void addOriginalSlotSnapshot(long id, int slotIndex, ItemStack stack) {
        for (PredictionTransaction tx : activeTransactions) {
            if (tx.id == id) {
                tx.originalSlots.put(slotIndex, stack.copy());
                break;
            }
        }
    }

    public static List<PredictionTransaction> getActiveTransactions() {
        return activeTransactions;
    }

    // =========================================================================
    //  State Reset Methods
    // =========================================================================

    /**
     * Resets all client-side state. Called when the player leaves a world/server.
     */
    public static void resetState() {
        EnderChestCache.reset();
        selectedSlotIndex = 0;
        tooltipActive = false;
        hoveredTooltipSlotIndex = -1;
        activeContainerStack = ItemStack.EMPTY;
        selectedSlotsSet.clear();
        lastMouseX = 0;
        lastMouseY = 0;

        // Reset Category 1 visual animation state
        currentSelectedCol = -1f;
        currentSelectedRow = -1f;
        lastHighlightRenderTime = 0L;
        Arrays.fill(slotScales, 1.0f);
        lastSlotScaleUpdateTime = 0L;

        // Reset Category 5 Prediction state
        activeTransactions.clear();
    }

}

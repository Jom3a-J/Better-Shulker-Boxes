package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.render.ShulkerTooltipComponent;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Better Shulker — Client-side entry point.
 *
 * Responsibilities:
 * 1. Register TooltipComponentCallback to map ShulkerTooltipData -> ShulkerTooltipComponent
 * 2. Register client-side packet receiver for EnderChestSyncPayload
 * 3. Maintain client-side state:
 *    - Cached ender chest contents (populated by S2C packets)
 *    - Selected slot index (controlled by scroll wheel via mixin)
 *    - Tooltip active flag
 *
 * All rendering and tooltip logic is client-only (annotated @Environment(EnvType.CLIENT)).
 */
@Environment(EnvType.CLIENT)
public class BetterShulkerClient implements ClientModInitializer {

    // =========================================================================
    //  Keybindings
    // =========================================================================

    private static final KeyMapping.Category CUSTOM_CATEGORY = KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("bettershulker", "keys")
    );
    private static KeyMapping settingsKey = null;
    private static KeyMapping extractKey = null;
    private static KeyMapping selectSlotKey = null;
    private static KeyMapping filterKey = null;
    private static KeyMapping precisionKey = null;
    private static KeyMapping altForceKey = null;
    private static KeyMapping scrollLeftKey = null;
    private static KeyMapping scrollRightKey = null;
    private static KeyMapping restockKey = null;
    private static KeyMapping wirelessEnderChestKey = null;
    private static KeyMapping showFullTooltipKey = null;

    // =========================================================================
    //  Client State Definitions
    // =========================================================================

    /** Cached ender chest contents received from the server via S2C packet. */
    private static NonNullList<ItemStack> enderChestContents = null;

    /** Current selected slot index inside the 9x3 grid (controlled by mouse scroll). */
    private static int selectedSlotIndex = 0;

    /** Whether the tooltip preview is currently active. */
    private static boolean tooltipActive = false;

    private static int hoveredTooltipSlotIndex = -1;
    private static ItemStack activeContainerStack = ItemStack.EMPTY;
    private static ItemStack filterItemStack = ItemStack.EMPTY;
    private static final java.util.Set<Integer> selectedSlotsSet = new java.util.HashSet<>();

    /** Cooldown tracking to limit ender chest sync request packets. */
    private static int lastMouseX = 0;
    private static int lastMouseY = 0;
    private static long lastEnderChestRequestTime = 0;
    private static final long ENDER_CHEST_REQUEST_COOLDOWN_MS = 500;

    // =========================================================================
    //  Visual Animations State
    // =========================================================================

    private static float currentSelectedCol = -1f;
    private static float currentSelectedRow = -1f;
    private static long lastHighlightRenderTime = 0L;

    private static final float[] slotScales = new float[27];
    private static long lastSlotScaleUpdateTime = 0L;
    
    private static float currentAnimatedHeight = -1f;
    private static long lastHeightUpdateTime = 0L;

    // =========================================================================
    //  Prediction & Rollbacks State Classes
    // =========================================================================

    public static class PredictionTransaction {
        public final long id;
        public final long timestamp;
        public final ItemStack originalCarried;
        public final ItemStack originalContainer;
        public final int containerSlotId;
        public NonNullList<ItemStack> originalEnderChest = null;
        public final java.util.Map<Integer, ItemStack> originalSlots = new java.util.HashMap<>();

        public PredictionTransaction(long id, ItemStack carried, ItemStack container, int containerSlotId, NonNullList<ItemStack> enderChest) {
            this.id = id;
            this.timestamp = System.currentTimeMillis();
            this.originalCarried = carried.copy();
            this.originalContainer = container.copy();
            this.containerSlotId = containerSlotId;
            if (enderChest != null) {
                this.originalEnderChest = NonNullList.withSize(27, ItemStack.EMPTY);
                for (int i = 0; i < 27; i++) {
                    this.originalEnderChest.set(i, enderChest.get(i).copy());
                }
            }
        }
    }

    public static class RollbackAnimation {
        public final ItemStack stack;
        public final double startX, startY;
        public final double endX, endY;
        public final long startTime;
        public final long durationMs = 250;

        public RollbackAnimation(ItemStack stack, double startX, double startY, double endX, double endY) {
            this.stack = stack.copy();
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.startTime = System.currentTimeMillis();
        }
    }

    private static long nextTransactionId = 1L;
    private static final java.util.List<PredictionTransaction> activeTransactions = new java.util.ArrayList<>();
    private static final java.util.List<RollbackAnimation> activeRollbacks = new java.util.ArrayList<>();

    // =========================================================================
    //  Mod Lifecycle Initialization
    // =========================================================================

    @Override
    public void onInitializeClient() {
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing client module");

        // Load saved configuration from disk
        BetterShulkerConfig.load();

        // Register the tooltip component factory mapping data payload to actual component
        ClientTooltipComponentCallback.EVENT.register(data -> {
            if (data instanceof ShulkerTooltipData shulkerData) {
                return new ShulkerTooltipComponent(shulkerData);
            }
            return null;
        });

        // Register receiver for server Ender Chest sync packets
        ClientPlayNetworking.registerGlobalReceiver(
                EnderChestSyncPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (enderChestContents == null) {
                            enderChestContents = NonNullList.withSize(27, ItemStack.EMPTY);
                        }
                        for (EnderChestSyncPayload.EnderChestDiff diff : payload.diffs()) {
                            int idx = diff.slotIndex();
                            if (idx >= 0 && idx < 27) {
                                enderChestContents.set(idx, diff.stack());
                            }
                        }
                        BetterShulkerMod.LOGGER.debug(
                                "[BetterShulker] Client received ender chest sync diff ({} updates)",
                                payload.diffs().size()
                        );
                    });
                }
        );

        // Register keybindings
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.settings",
                GLFW.GLFW_KEY_B,
                CUSTOM_CATEGORY
        ));
        extractKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.extract",
                GLFW.GLFW_KEY_E,
                CUSTOM_CATEGORY
        ));
        selectSlotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.select_slot",
                GLFW.GLFW_KEY_SPACE,
                CUSTOM_CATEGORY
        ));
        filterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.filter",
                GLFW.GLFW_KEY_F,
                CUSTOM_CATEGORY
        ));
        precisionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.precision",
                GLFW.GLFW_KEY_LEFT_CONTROL,
                CUSTOM_CATEGORY
        ));
        altForceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.alt_force",
                GLFW.GLFW_KEY_LEFT_ALT,
                CUSTOM_CATEGORY
        ));
        scrollLeftKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.scroll_left",
                GLFW.GLFW_KEY_LEFT,
                CUSTOM_CATEGORY
        ));
        scrollRightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.scroll_right",
                GLFW.GLFW_KEY_RIGHT,
                CUSTOM_CATEGORY
        ));
        restockKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.restock",
                GLFW.GLFW_KEY_R,
                CUSTOM_CATEGORY
        ));
        wirelessEnderChestKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.wireless_ender",
                GLFW.GLFW_KEY_O,
                CUSTOM_CATEGORY
        ));
        showFullTooltipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettershulker.show_full_tooltip",
                GLFW.GLFW_KEY_V,
                CUSTOM_CATEGORY
        ));

        // Tick event to process settings or wireless screen hotkeys
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.consumeClick()) {
                if (client.gui.screen() != null || client.level != null) {
                    try {
                        client.setScreenAndShow(BetterShulkerClothConfigScreen.create(client.gui.screen()));
                    } catch (Exception e) {
                        BetterShulkerMod.LOGGER.error("[BetterShulker] Failed to open settings screen", e);
                    }
                }
            }
            while (wirelessEnderChestKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    if (bettershulker$hasEnderChestInInventory(client.player)) {
                        try {
                            client.setScreenAndShow(new WirelessEnderChestScreen());
                        } catch (Exception e) {
                            BetterShulkerMod.LOGGER.error("[BetterShulker] Failed to open wireless ender chest screen", e);
                        }
                    } else {
                        client.gui.hud.setOverlayMessage(
                            Component.literal("Requires an Ender Chest in your inventory!").withStyle(ChatFormatting.RED),
                            false
                        );
                    }
                }
            }
        });

        // Clear client cache upon server disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetState();
        });

        BetterShulkerMod.LOGGER.info("[BetterShulker] Client module initialized successfully");
    }

    // =========================================================================
    //  Public Accessors & Control Methods
    // =========================================================================

    public static NonNullList<ItemStack> getEnderChestContents() {
        return enderChestContents;
    }

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
        if (!active) {
            currentAnimatedHeight = -1f;
            lastHeightUpdateTime = 0L;
        }
    }

    /** Sends wireless/C2S ender chest sync payload request to server. */
    public static void requestEnderChestSync() {
        long now = System.currentTimeMillis();
        if (now - lastEnderChestRequestTime >= ENDER_CHEST_REQUEST_COOLDOWN_MS) {
            lastEnderChestRequestTime = now;
            PlatformNetworking.sendToServer(new EnderChestRequestPayload());
            BetterShulkerMod.LOGGER.debug("[BetterShulker] Sent ender chest sync request to server");
        }
    }

    public static void clearEnderChestCache() {
        enderChestContents = null;
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
        activeContainerStack = stack;
    }

    public static ItemStack getFilterItemStack() {
        return filterItemStack;
    }

    public static void setFilterItemStack(ItemStack stack) {
        filterItemStack = stack;
    }

    public static java.util.Set<Integer> getSelectedSlotsSet() {
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

    public static KeyMapping getSettingsKey() {
        return settingsKey;
    }

    public static KeyMapping getExtractKey() {
        return extractKey;
    }

    public static KeyMapping getSelectSlotKey() {
        return selectSlotKey;
    }

    public static KeyMapping getFilterKey() {
        return filterKey;
    }

    public static KeyMapping getPrecisionKey() {
        return precisionKey;
    }

    public static KeyMapping getAltForceKey() {
        return altForceKey;
    }

    public static KeyMapping getScrollLeftKey() {
        return scrollLeftKey;
    }

    public static KeyMapping getScrollRightKey() {
        return scrollRightKey;
    }

    public static KeyMapping getRestockKey() {
        return restockKey;
    }

    public static KeyMapping getWirelessEnderChestKey() {
        return wirelessEnderChestKey;
    }

    public static KeyMapping getShowFullTooltipKey() {
        return showFullTooltipKey;
    }

    public static boolean isKeyHeld(KeyMapping key) {
        if (key == null || key.isUnbound()) return false;
        try {
            var boundKey = com.mojang.blaze3d.platform.InputConstants.getKey(key.saveString());
            if (boundKey.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                return GLFW.glfwGetKey(net.minecraft.client.Minecraft.getInstance().getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
            }
        } catch (Exception e) {
            // fallback
        }
        return key.isDown();
    }

    public static boolean isCompactModeActive() {
        if (!BetterShulkerConfig.compactTooltipEnabled) {
            return false;
        }
        if (isKeyHeld(showFullTooltipKey)) {
            return false;
        }
        return true;
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

    public static float getCurrentAnimatedHeight() { return currentAnimatedHeight; }
    public static void setCurrentAnimatedHeight(float v) { currentAnimatedHeight = v; }
    public static long getLastHeightUpdateTime() { return lastHeightUpdateTime; }
    public static void setLastHeightUpdateTime(long v) { lastHeightUpdateTime = v; }

    // =========================================================================
    //  Prediction Methods
    // =========================================================================

    public static long startPrediction(ItemStack carried, ItemStack container, int containerSlotId, NonNullList<ItemStack> enderChest) {
        long id = nextTransactionId++;
        activeTransactions.add(new PredictionTransaction(id, carried, container, containerSlotId, enderChest));
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

    public static java.util.List<PredictionTransaction> getActiveTransactions() {
        return activeTransactions;
    }

    public static java.util.List<RollbackAnimation> getActiveRollbacks() {
        return activeRollbacks;
    }

    public static void triggerRollbackAnimation(ItemStack stack, double startX, double startY, double endX, double endY) {
        activeRollbacks.add(new RollbackAnimation(stack, startX, startY, endX, endY));
    }

    // =========================================================================
    //  State Reset Methods
    // =========================================================================

    /**
     * Resets all client-side state. Called when the player leaves a world/server.
     */
    public static void resetState() {
        enderChestContents = null;
        selectedSlotIndex = 0;
        tooltipActive = false;
        lastEnderChestRequestTime = 0;
        hoveredTooltipSlotIndex = -1;
        activeContainerStack = ItemStack.EMPTY;
        filterItemStack = ItemStack.EMPTY;
        selectedSlotsSet.clear();
        lastMouseX = 0;
        lastMouseY = 0;

        // Reset Category 1 visual animation state
        currentSelectedCol = -1f;
        currentSelectedRow = -1f;
        lastHighlightRenderTime = 0L;
        java.util.Arrays.fill(slotScales, 1.0f);
        lastSlotScaleUpdateTime = 0L;
        currentAnimatedHeight = -1f;
        lastHeightUpdateTime = 0L;

        // Reset Category 5 Prediction state
        activeTransactions.clear();
        activeRollbacks.clear();
    }

    public static boolean bettershulker$hasEnderChestInInventory(net.minecraft.world.entity.player.Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && com.bettershulker.util.ContainerHelper.isEnderChest(stack)) {
                return true;
            }
        }
        return false;
    }
}

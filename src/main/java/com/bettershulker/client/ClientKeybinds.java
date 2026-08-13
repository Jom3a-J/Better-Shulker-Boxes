package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key mappings, registered by each loader's client entrypoint and read from everywhere.
 *
 * <p>Held-state is asked of GLFW directly rather than through {@link KeyMapping#isDown()}, which
 * counts presses and so answers "was it pressed since last asked" - the wrong question for a
 * modifier that has to stay true for as long as it is held.</p>
 */
public final class ClientKeybinds {

    private static final KeyMapping.Category CUSTOM_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("bettershulker", "keys")
    );

    private static KeyMapping settingsKey = null;
    private static KeyMapping extractKey = null;
    private static KeyMapping selectSlotKey = null;
    private static KeyMapping precisionKey = null;
    private static KeyMapping altForceKey = null;
    private static KeyMapping scrollLeftKey = null;
    private static KeyMapping scrollRightKey = null;
    private static KeyMapping restockKey = null;
    private static KeyMapping showFullTooltipKey = null;

    private ClientKeybinds() {}

    public static KeyMapping.Category getCustomCategory() {
        return CUSTOM_CATEGORY;
    }

    public static void setKeyMappings(
            KeyMapping settings,
            KeyMapping extract,
            KeyMapping selectSlot,
            KeyMapping precision,
            KeyMapping altForce,
            KeyMapping scrollLeft,
            KeyMapping scrollRight,
            KeyMapping restock,
            KeyMapping showFullTooltip
    ) {
        settingsKey = settings;
        extractKey = extract;
        selectSlotKey = selectSlot;
        precisionKey = precision;
        altForceKey = altForce;
        scrollLeftKey = scrollLeft;
        scrollRightKey = scrollRight;
        restockKey = restock;
        showFullTooltipKey = showFullTooltip;
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

    public static KeyMapping getShowFullTooltipKey() {
        return showFullTooltipKey;
    }

    /**
     * Display name of the key that reveals the full grid, or an empty string when it has none.
     *
     * <p>Lives here so the tooltip's own hint and the settings screen's preview of that hint name
     * the same key. The preview used to spell out "V" whatever the key was actually bound to.</p>
     */
    public static String getShowFullTooltipKeyName() {
        KeyMapping key = showFullTooltipKey;
        if (key == null || key.isUnbound()) return "";
        try {
            return key.getTranslatedKeyMessage().getString();
        } catch (Exception ignored) {
            return "V";
        }
    }

    public static boolean isKeyHeld(KeyMapping key) {
        if (key == null || key.isUnbound()) return false;
        try {
            var boundKey = InputConstants.getKey(key.saveString());
            if (boundKey.getType() == InputConstants.Type.KEYSYM) {
                return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
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
}

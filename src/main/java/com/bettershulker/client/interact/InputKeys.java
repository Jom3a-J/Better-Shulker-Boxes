package com.bettershulker.client.interact;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.BetterShulkerClient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Modifier keys the container preview reads, each gated on the setting that owns it. */
public final class InputKeys {

    private InputKeys() {}

    public static boolean isCtrlDown() {
        if (!BetterShulkerConfig.precisionModeEnabled) return false;
        return BetterShulkerClient.isKeyHeld(BetterShulkerClient.getPrecisionKey());
    }

    public static boolean isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    public static boolean isAltDown() {
        if (!BetterShulkerConfig.altForceTooltipEnabled) return false;
        var window = Minecraft.getInstance().getWindow();
        return GLFW.glfwGetKey(window.handle(), GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window.handle(), GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}

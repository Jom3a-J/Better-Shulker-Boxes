package com.bettershulker.client.interact;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.ClientKeybinds;

import com.mojang.blaze3d.platform.InputConstants;

/** Modifier keys the container preview reads, each gated on the setting that owns it. */
public final class InputKeys {

    private InputKeys() {}

    public static boolean isCtrlDown() {
        if (!BetterShulkerConfig.precisionModeEnabled) return false;
        return ClientKeybinds.isKeyHeld(ClientKeybinds.getPrecisionKey());
    }

    public static boolean isShiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
            || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    public static boolean isAltDown() {
        if (!BetterShulkerConfig.altForceTooltipEnabled) return false;
        return InputConstants.isKeyDown(InputConstants.KEY_LALT)
            || InputConstants.isKeyDown(InputConstants.KEY_RALT);
    }
}

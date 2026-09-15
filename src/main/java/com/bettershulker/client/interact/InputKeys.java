package com.bettershulker.client.interact;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.client.ClientKeybinds;
import com.bettershulker.client.compat.HeldKeys;

import com.mojang.blaze3d.platform.InputConstants;

/** Modifier keys the container preview reads, each gated on the setting that owns it. */
public final class InputKeys {

    private InputKeys() {}

    public static boolean isCtrlDown() {
        if (!BetterShulkerConfig.precisionModeEnabled) return false;
        return ClientKeybinds.isKeyHeld(ClientKeybinds.getPrecisionKey());
    }

    public static boolean isShiftDown() {
        return HeldKeys.isHeld(InputConstants.KEY_LSHIFT)
            || HeldKeys.isHeld(InputConstants.KEY_RSHIFT);
    }

    public static boolean isAltDown() {
        if (!BetterShulkerConfig.altForceTooltipEnabled) return false;
        return HeldKeys.isHeld(InputConstants.KEY_LALT)
            || HeldKeys.isHeld(InputConstants.KEY_RALT);
    }
}

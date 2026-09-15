package com.bettershulker.client.compat;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Asks whether a keyboard key is physically held down, in the spelling this Minecraft version uses.
 *
 * <p>Minecraft 26.3 swapped GLFW for SDL behind the window. Two things moved with it:
 * {@code InputConstants.isKeyDown} lost the {@link com.mojang.blaze3d.platform.Window} it used to
 * take, and the {@code KEYSYM} key type became {@code KEYBOARD}. The mod still builds against 26.2
 * on NeoForge, which has neither change, so both spellings live in a copy of this class and each
 * loader compiles the one matching its Minecraft version. Shared client code calls these two
 * methods and stays version-agnostic.</p>
 *
 * <p>This is the Minecraft 26.3 copy.</p>
 */
public final class HeldKeys {

    private HeldKeys() {}

    /** True for as long as the key with this code is held. */
    public static boolean isHeld(int keyCode) {
        return InputConstants.isKeyDown(keyCode);
    }

    /** True when the binding is a keyboard key rather than a mouse button. */
    public static boolean isKeyboard(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYBOARD;
    }
}

package com.bettershulker.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-neutral networking bridge used by shared gameplay code.
 *
 * <p>Each loader entrypoint installs its own delegate during initialization.
 * This keeps common logic free of direct Fabric/NeoForge networking imports.</p>
 */
public final class PlatformNetworking {
    public interface Delegate {
        void sendToServer(CustomPacketPayload payload);
        void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    }

    private static Delegate delegate;

    private PlatformNetworking() {}

    public static void setDelegate(Delegate delegate) {
        PlatformNetworking.delegate = delegate;
    }

    public static void sendToServer(CustomPacketPayload payload) {
        if (delegate == null) {
            throw new IllegalStateException("Better Shulker platform networking delegate has not been initialized");
        }
        delegate.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (delegate == null) {
            throw new IllegalStateException("Better Shulker platform networking delegate has not been initialized");
        }
        delegate.sendToPlayer(player, payload);
    }
}

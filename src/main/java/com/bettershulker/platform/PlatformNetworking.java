package com.bettershulker.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Temporary Fabric networking bridge used while preparing the multiloader split.
 *
 * <p>Shared gameplay code should call this class instead of referencing Fabric's
 * send helpers directly. During the multiloader conversion, Fabric and NeoForge
 * will each provide their own implementation with the same public surface.</p>
 */
public final class PlatformNetworking {
    private PlatformNetworking() {}

    public static void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}

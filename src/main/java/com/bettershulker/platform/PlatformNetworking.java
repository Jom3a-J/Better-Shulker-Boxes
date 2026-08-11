package com.bettershulker.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-neutral networking bridge used by shared gameplay code.
 *
 * <p>Each loader entrypoint installs its own senders during initialization.
 * This keeps common logic free of direct Fabric/NeoForge networking imports.</p>
 *
 * <p>The two directions are stored separately on purpose. Sending to the server is a client
 * capability, installed by the client entrypoint; sending to a player is a server capability,
 * installed by the common entrypoint on both distributions. A single combined delegate forced
 * whichever entrypoint ran last to overwrite the other's half. NeoForge builds a mod's
 * entrypoint classes by iterating them in mod-file scan order, so on a client the common
 * entrypoint could replace a working client sender with one that refuses to send, and the mod
 * would work or break depending on class discovery order. Independent slots cannot collide.</p>
 */
public final class PlatformNetworking {
    /** Sends a payload from this client to the server. Absent on a dedicated server. */
    public interface ClientSender {
        void sendToServer(CustomPacketPayload payload);
    }

    /** Sends a payload to a specific player. Present wherever a server is running. */
    public interface ServerSender {
        void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    }

    private static volatile ClientSender clientSender;
    private static volatile ServerSender serverSender;

    private PlatformNetworking() {}

    public static void setClientSender(ClientSender sender) {
        clientSender = sender;
    }

    public static void setServerSender(ServerSender sender) {
        serverSender = sender;
    }

    public static void sendToServer(CustomPacketPayload payload) {
        ClientSender sender = clientSender;
        if (sender == null) {
            throw new IllegalStateException(
                    "Better Shulker has no client-to-server sender; a serverbound payload cannot be"
                            + " sent from the physical server");
        }
        sender.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerSender sender = serverSender;
        if (sender == null) {
            throw new IllegalStateException(
                    "Better Shulker server networking has not been initialized");
        }
        sender.sendToPlayer(player, payload);
    }
}

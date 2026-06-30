package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-Server (C2S) request packet for ender chest contents.
 *
 * <p>When a player hovers over or interacts with an ender chest item in their
 * inventory, the client sends this signal packet to request the server to
 * respond with the player's ender chest contents via {@link EnderChestSyncPayload}.</p>
 *
 * <p>This is a <em>signal packet</em> — it carries no data fields. The server
 * identifies the requesting player from the network handler context. This
 * design avoids sending any player identity over the wire (the server already
 * knows who sent it) and prevents spoofing.</p>
 *
 * <p>Security note: The server handler must validate that the player actually
 * has an ender chest item in their inventory before responding, to prevent
 * information leakage.</p>
 */
public record EnderChestRequestPayload() implements CustomPacketPayload {

    // =========================================================================
    //  Network Registration
    // =========================================================================

    /**
     * Unique packet type identifier for registration with Fabric's networking API.
     * Channel: {@code bettershulker:ender_request}
     */
    public static final Type<EnderChestRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "ender_request")
    );

    /**
     * Codec for serialization/deserialization over the network.
     *
     * <p>{@link StreamCodec#unit(Object)} creates a codec for types with no
     * fields — it writes nothing to the buffer on encode and returns the
     * provided singleton instance on decode. This is the standard pattern
     * for signal/marker packets in Minecraft's networking layer.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, EnderChestRequestPayload> CODEC =
            StreamCodec.unit(new EnderChestRequestPayload());

    // =========================================================================
    //  CustomPacketPayload Overrides
    // =========================================================================

    /**
     * Returns the packet type for Fabric networking dispatch.
     *
     * @return {@link #TYPE}
     */
    @Override
    public Type<EnderChestRequestPayload> type() {
        return TYPE;
    }
}

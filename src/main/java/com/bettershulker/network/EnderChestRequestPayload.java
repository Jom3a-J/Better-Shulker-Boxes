package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server request for the Ender Chest contents exposed by a specific container source. */
public record EnderChestRequestPayload(int sourceSlotId) implements CustomPacketPayload {
    /** The container menu's carried stack, rather than one of its slots. */
    public static final int CARRIED_SOURCE_SLOT = -1;

    /** Fallback for tooltip calls that do not have an active container-screen source. */
    public static final int ANY_ACCESSIBLE_SOURCE = -2;

    public static final Type<EnderChestRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "ender_request")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, EnderChestRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    EnderChestRequestPayload::sourceSlotId,
                    EnderChestRequestPayload::new
            );

    @Override
    public Type<EnderChestRequestPayload> type() {
        return TYPE;
    }
}

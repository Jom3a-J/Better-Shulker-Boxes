package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server signal requesting the player's ender chest contents. */
public record EnderChestRequestPayload() implements CustomPacketPayload {
    public static final Type<EnderChestRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "ender_request")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, EnderChestRequestPayload> CODEC =
            StreamCodec.unit(new EnderChestRequestPayload());

    @Override
    public Type<EnderChestRequestPayload> type() {
        return TYPE;
    }
}

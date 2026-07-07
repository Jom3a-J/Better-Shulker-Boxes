package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server request for validated container preview interactions. */
public record ContainerInteractPayload(
        int containerSlotId,
        int targetIndex,
        int action,
        int inventorySlotId
) implements CustomPacketPayload {
    public static final Type<ContainerInteractPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "container_interact")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerInteractPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::containerSlotId,
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::targetIndex,
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::action,
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::inventorySlotId,
                    ContainerInteractPayload::new
            );

    @Override
    public Type<ContainerInteractPayload> type() {
        return TYPE;
    }

    public enum InteractType {
        INSERT,
        EXTRACT,
        INSERT_ONE,
        EXTRACT_ONE,
        SWEEP_INSERT,
        SWEEP_EXTRACT,
        RESTOCK,
        DEPOSIT;

        private static final InteractType[] VALUES = values();

        public static InteractType fromId(int id) {
            if (id < 0 || id >= VALUES.length) {
                throw new IllegalArgumentException(
                        "Invalid ContainerInteractPayload action ID: " + id
                                + " (expected 0-" + (VALUES.length - 1) + ")"
                );
            }
            return VALUES[id];
        }

        public int toId() {
            return ordinal();
        }
    }
}

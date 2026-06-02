package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-Server (C2S) packet for container interaction requests.
 *
 * <p>When the player clicks on a shulker box or ender chest preview in the
 * inventory tooltip, the client sends this packet to request a server-side
 * mutation. The server validates the request (e.g. ensuring the player owns
 * the container, the slot indices are valid, and the operation is legal)
 * before applying the change.</p>
 *
 * <p><b>All container mutations MUST go through this packet.</b> The client
 * never modifies container contents directly — it only renders the preview
 * and sends interaction requests. This architecture prevents desync and
 * ensures all anti-exploit checks (nested shulkers, stack size limits, etc.)
 * are enforced server-side.</p>
 *
 * @param containerSlotId the slot index in the player's inventory where the
 *                        container item (shulker box or ender chest) is located.
 *                        Used by the server to look up the actual ItemStack.
 * @param targetIndex     the slot index (0–26) inside the container to interact
 *                        with. For INSERT actions, this may be ignored by the
 *                        server (auto-placement). For EXTRACT actions, this
 *                        specifies which slot to pull from.
 * @param action          the numeric action ID — see {@link InteractType} for
 *                        the mapping. Using an int on the wire keeps the packet
 *                        compact and version-resilient.
 */
public record ContainerInteractPayload(
        int containerSlotId,
        int targetIndex,
        int action,
        int inventorySlotId
) implements CustomPacketPayload {

    /**
     * Unique packet type identifier for registration with Fabric's networking API.
     * Channel: {@code bettershulker:container_interact}
     */
    public static final Type<ContainerInteractPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "container_interact")
    );

    /**
     * Codec using {@link StreamCodec#composite} for clean, declarative
     * field-by-field serialization.
     *
     * <p>All three fields are encoded as VarInts, which is compact for the
     * small values we use (slot IDs 0–40, target indices 0–26, action 0–3).</p>
     *
     * <p>The composite pattern maps each field to a codec and an accessor,
     * then uses the record constructor to reassemble on decode. This is the
     * idiomatic approach in Minecraft 26.1's networking layer.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerInteractPayload> CODEC =
            StreamCodec.composite(
                    // Field 1: containerSlotId — the inventory slot holding the container
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::containerSlotId,
                    // Field 2: targetIndex — the slot inside the container (0-26)
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::targetIndex,
                    // Field 3: action — the interaction type as an integer ID
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::action,
                    // Field 4: inventorySlotId — the inventory slot swept over
                    ByteBufCodecs.VAR_INT, ContainerInteractPayload::inventorySlotId,
                    // Constructor reference for decoding
                    ContainerInteractPayload::new
            );

    /**
     * Returns the packet type for Fabric networking dispatch.
     *
     * @return {@link #TYPE}
     */
    @Override
    public Type<ContainerInteractPayload> type() {
        return TYPE;
    }

    // ─────────────────────────────────────────────────────────────
    //  Interaction type enum
    // ─────────────────────────────────────────────────────────────

    /**
     * Enumerates the four possible container interaction types.
     *
     * <p>Each type corresponds to a specific user gesture:</p>
     * <ul>
     *   <li>{@link #INSERT} (0) — Left-click insert: place the entire carried
     *       stack into the container (merge into existing + fill empty slots).</li>
     *   <li>{@link #EXTRACT} (1) — Left-click extract: pull an entire stack
     *       out of the specified container slot onto the cursor.</li>
     *   <li>{@link #INSERT_ONE} (2) — Right-click insert: place exactly 1 item
     *       from the carried stack into the container (precision mode).</li>
     *   <li>{@link #EXTRACT_ONE} (3) — Right-click extract: pull exactly 1 item
     *       from the specified container slot onto the cursor.</li>
     * </ul>
     *
     * <p>The ordinal values are used as wire IDs. The {@link #fromId(int)} and
     * {@link #toId()} methods provide safe conversion with bounds checking.</p>
     */
    public enum InteractType {
        /** Full-stack insertion (left-click with items on cursor). */
        INSERT,
        /** Full-stack extraction (left-click on occupied container slot). */
        EXTRACT,
        /** Single-item insertion (right-click with items on cursor). */
        INSERT_ONE,
        /** Single-item extraction (right-click on occupied container slot). */
        EXTRACT_ONE,
        /** Sweep insertion from an inventory slot into the container. */
        SWEEP_INSERT,
        /** Sweep extraction from the container into an inventory slot. */
        SWEEP_EXTRACT,
        /** Actual sorting on the server. */
        SORT,
        /** Restock hotbar slots from container. */
        RESTOCK,
        /** Deposit matching items from player inventory into the container. */
        DEPOSIT;

        /** Cached values array to avoid allocation on every fromId call. */
        private static final InteractType[] VALUES = values();

        /**
         * Converts a numeric wire ID to an {@link InteractType}.
         *
         * @param id the integer ID (0–3)
         * @return the corresponding {@link InteractType}
         * @throws IllegalArgumentException if the ID is out of range
         */
        public static InteractType fromId(int id) {
            if (id < 0 || id >= VALUES.length) {
                throw new IllegalArgumentException(
                        "Invalid ContainerInteractPayload action ID: " + id
                                + " (expected 0-" + (VALUES.length - 1) + ")"
                );
            }
            return VALUES[id];
        }

        /**
         * Returns the numeric wire ID for this interaction type.
         *
         * @return the ordinal value (0–3)
         */
        public int toId() {
            return ordinal();
        }
    }
}

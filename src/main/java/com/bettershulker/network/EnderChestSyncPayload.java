package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-Client (S2C) synchronization packet for ender chest contents.
 * Optimized to send only changes (diffs) of the container contents.
 */
public record EnderChestSyncPayload(List<EnderChestDiff> diffs) implements CustomPacketPayload {

    public record EnderChestDiff(int slotIndex, ItemStack stack) {}

    public static final Type<EnderChestSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("bettershulker", "ender_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, EnderChestSyncPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public EnderChestSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    int bitmask = buf.readInt();
                    List<Integer> activeSlots = new ArrayList<>();
                    for (int i = 0; i < 27; i++) {
                        if ((bitmask & (1 << i)) != 0) {
                            activeSlots.add(i);
                        }
                    }

                    List<EnderChestDiff> diffs = new ArrayList<>(activeSlots.size());
                    int decodedCount = 0;
                    while (decodedCount < activeSlots.size()) {
                        int runLength = buf.readVarInt();
                        ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        for (int r = 0; r < runLength && decodedCount < activeSlots.size(); r++) {
                            int slotIndex = activeSlots.get(decodedCount);
                            diffs.add(new EnderChestDiff(slotIndex, stack));
                            decodedCount++;
                        }
                    }

                    return new EnderChestSyncPayload(diffs);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, EnderChestSyncPayload payload) {
                    List<EnderChestDiff> diffs = payload.diffs();
                    int bitmask = 0;
                    for (EnderChestDiff diff : diffs) {
                        if (diff.slotIndex() >= 0 && diff.slotIndex() < 27) {
                            bitmask |= (1 << diff.slotIndex());
                        }
                    }
                    buf.writeInt(bitmask);

                    List<EnderChestDiff> sortedDiffs = new ArrayList<>(diffs);
                    sortedDiffs.sort(java.util.Comparator.comparingInt(EnderChestDiff::slotIndex));

                    int index = 0;
                    while (index < sortedDiffs.size()) {
                        ItemStack currentStack = sortedDiffs.get(index).stack();
                        int runLength = 1;
                        while (index + runLength < sortedDiffs.size() 
                                && ItemStack.isSameItemSameComponents(sortedDiffs.get(index + runLength).stack(), currentStack)) {
                            runLength++;
                        }
                        buf.writeVarInt(runLength);
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, currentStack);
                        index += runLength;
                    }
                }
            };

    @Override
    public Type<EnderChestSyncPayload> type() {
        return TYPE;
    }
}

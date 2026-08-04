package com.bettershulker.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server-to-client diff sync for ender chest contents. */
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
                        int remainingSlots = activeSlots.size() - decodedCount;
                        if (runLength <= 0 || runLength > remainingSlots) {
                            throw new IllegalArgumentException("Invalid Ender Chest sync run length: " + runLength);
                        }

                        ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        for (int r = 0; r < runLength; r++) {
                            // Each cache slot must own its stack: client prediction mutates these values.
                            diffs.add(new EnderChestDiff(activeSlots.get(decodedCount), stack.copy()));
                            decodedCount++;
                        }
                    }

                    return new EnderChestSyncPayload(diffs);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, EnderChestSyncPayload payload) {
                    // Normalize before writing. The bitmask represents unique slots, so writing
                    // invalid or duplicate entries alongside it would make the decoder consume a
                    // different number of RLE values and corrupt the packet stream.
                    Map<Integer, ItemStack> normalized = new TreeMap<>();
                    if (payload.diffs() != null) {
                        for (EnderChestDiff diff : payload.diffs()) {
                            if (diff == null || diff.slotIndex() < 0 || diff.slotIndex() >= 27) continue;
                            normalized.put(diff.slotIndex(),
                                    diff.stack() == null ? ItemStack.EMPTY : diff.stack().copy());
                        }
                    }

                    int bitmask = 0;
                    for (int slotIndex : normalized.keySet()) {
                        bitmask |= (1 << slotIndex);
                    }
                    buf.writeInt(bitmask);

                    List<ItemStack> sortedStacks = new ArrayList<>(normalized.values());

                    int index = 0;
                    while (index < sortedStacks.size()) {
                        ItemStack currentStack = sortedStacks.get(index);
                        int runLength = 1;
                        while (index + runLength < sortedStacks.size()
                                // RLE is valid only when the full serialized stack, including count, matches.
                                && ItemStack.matches(sortedStacks.get(index + runLength), currentStack)) {
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

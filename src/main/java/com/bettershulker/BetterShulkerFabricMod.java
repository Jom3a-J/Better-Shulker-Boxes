package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Fabric common/server entrypoint. Keeps Fabric networking/event APIs out of shared logic.
 */
public final class BetterShulkerFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing Fabric module for Minecraft 26.2");

        PlatformNetworking.setDelegate(new PlatformNetworking.Delegate() {
            @Override
            public void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                throw new IllegalStateException("Cannot send serverbound Better Shulker payload from the physical server");
            }

            @Override
            public void sendToPlayer(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                ServerPlayNetworking.send(player, payload);
            }
        });

        PayloadTypeRegistry.serverboundPlay().register(
                EnderChestRequestPayload.TYPE,
                EnderChestRequestPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                EnderChestSyncPayload.TYPE,
                EnderChestSyncPayload.CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                ContainerInteractPayload.TYPE,
                ContainerInteractPayload.CODEC
        );

        registerEnderChestRequestHandler();
        registerContainerInteractHandler();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                BetterShulkerMod.clearPlayerCaches(handler.player.getUUID())
        );

        BetterShulkerMod.LOGGER.info("[BetterShulker] Fabric payload types and handlers registered successfully");
    }

    private static void registerEnderChestRequestHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
                EnderChestRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    context.player().level().getServer().execute(() -> {
                        if (!BetterShulkerMod.hasEnderChestInInventory(player)) {
                            BetterShulkerMod.LOGGER.warn(
                                    "[BetterShulker] Player {} requested ender chest sync without carrying one in their inventory!",
                                    player.getName().getString()
                            );
                            return;
                        }

                        BetterShulkerMod.resetEnderChestSync(player.getUUID());
                        PlatformNetworking.sendToPlayer(player, BetterShulkerMod.buildEnderChestSyncPayload(player));
                        BetterShulkerMod.LOGGER.debug("[BetterShulker] Synced ender chest for player {}", player.getName().getString());
                    });
                }
        );
    }

    private static void registerContainerInteractHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
                ContainerInteractPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    player.level().getServer().execute(() -> {
                        long currentTick = player.level().getGameTime();
                        UUID uuid = player.getUUID();

                        long lastTick = BetterShulkerMod.lastInteractionTick.getOrDefault(uuid, -1L);
                        if (lastTick != currentTick) {
                            BetterShulkerMod.lastInteractionTick.put(uuid, currentTick);
                            BetterShulkerMod.interactionCountsThisTick.put(uuid, 0);
                        }

                        int count = BetterShulkerMod.interactionCountsThisTick.get(uuid);
                        if (count >= BetterShulkerMod.MAX_INTERACTIONS_PER_TICK) {
                            BetterShulkerMod.LOGGER.warn(
                                    "[BetterShulker] Player {} exceeded interaction rate limit, dropping packet",
                                    player.getName().getString()
                            );
                            return;
                        }
                        BetterShulkerMod.interactionCountsThisTick.put(uuid, count + 1);

                        BetterShulkerMod.handleContainerInteraction(player, payload);
                    });
                }
        );
    }
}

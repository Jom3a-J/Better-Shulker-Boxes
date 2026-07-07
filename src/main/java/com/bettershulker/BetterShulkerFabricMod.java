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
                    context.player().level().getServer().execute(() -> BetterShulkerMod.handleEnderChestSyncRequest(player));
                }
        );
    }

    private static void registerContainerInteractHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
                ContainerInteractPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    player.level().getServer().execute(() -> BetterShulkerMod.handleRateLimitedContainerInteraction(player, payload));
                }
        );
    }
}

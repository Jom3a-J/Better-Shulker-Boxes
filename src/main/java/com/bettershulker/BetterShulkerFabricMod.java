package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.server.EnderChestService;
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

        // Only the server direction. The client entrypoint installs the other half, and neither
        // can clobber the other regardless of which initializer runs first.
        PlatformNetworking.setServerSender(ServerPlayNetworking::send);

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
                    context.player().level().getServer().execute(
                            () -> EnderChestService.handleEnderChestSyncRequest(player, payload.sourceSlotId()));
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

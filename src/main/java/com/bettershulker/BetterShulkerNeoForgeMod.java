package com.bettershulker;

import com.bettershulker.network.ContainerInteractPayload;
import com.bettershulker.network.EnderChestRequestPayload;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge common/server entrypoint.
 */
@Mod(BetterShulkerMod.MOD_ID)
public final class BetterShulkerNeoForgeMod {
    public BetterShulkerNeoForgeMod(IEventBus modBus) {
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing NeoForge module for Minecraft 26.2");

        PlatformNetworking.setDelegate(new PlatformNetworking.Delegate() {
            @Override
            public void sendToServer(CustomPacketPayload payload) {
                throw new IllegalStateException("Cannot send serverbound Better Shulker payload from the physical server");
            }

            @Override
            public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        });

        modBus.addListener(this::registerPayloadHandlers);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    private void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(EnderChestRequestPayload.TYPE, EnderChestRequestPayload.CODEC, this::handleEnderChestRequest);
        registrar.playToServer(ContainerInteractPayload.TYPE, ContainerInteractPayload.CODEC, this::handleContainerInteract);
        registrar.playToClient(EnderChestSyncPayload.TYPE, EnderChestSyncPayload.CODEC);
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BetterShulkerMod.clearPlayerCaches(event.getEntity().getUUID());
    }

    private void handleEnderChestRequest(EnderChestRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BetterShulkerMod.handleEnderChestSyncRequest(player);
    }

    private void handleContainerInteract(ContainerInteractPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        BetterShulkerMod.handleRateLimitedContainerInteraction(player, payload);
    }
}

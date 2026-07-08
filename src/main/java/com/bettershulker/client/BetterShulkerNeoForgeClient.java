package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.render.ShulkerTooltipComponent;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge client entrypoint.
 */
@Mod(value = BetterShulkerMod.MOD_ID, dist = Dist.CLIENT)
public final class BetterShulkerNeoForgeClient {
    public BetterShulkerNeoForgeClient(IEventBus modBus, ModContainer modContainer) {
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing NeoForge client module");

        BetterShulkerConfig.load();

        PlatformNetworking.setDelegate(new PlatformNetworking.Delegate() {
            @Override
            public void sendToServer(CustomPacketPayload payload) {
                ClientPacketDistributor.sendToServer(payload);
            }

            @Override
            public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        });

        IConfigScreenFactory configScreenFactory = (container, parent) -> BetterShulkerClothConfigScreen.create(parent);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);

        modBus.addListener(this::registerTooltipFactories);
        modBus.addListener(this::registerKeyMappings);
        modBus.addListener(this::registerClientPayloadHandlers);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onClientLoggingOut);
    }

    private void registerTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(ShulkerTooltipData.class, ShulkerTooltipComponent::new);
    }

    private void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(EnderChestSyncPayload.TYPE, (payload, context) -> BetterShulkerClient.applyEnderChestSync(payload));
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(BetterShulkerClient.getCustomCategory());
        BetterShulkerClient.setKeyMappings(
                registerKey(event, "key.bettershulker.settings", GLFW.GLFW_KEY_B),
                registerKey(event, "key.bettershulker.extract", GLFW.GLFW_KEY_E),
                registerKey(event, "key.bettershulker.select_slot", GLFW.GLFW_KEY_SPACE),
                registerKey(event, "key.bettershulker.filter", GLFW.GLFW_KEY_F),
                registerKey(event, "key.bettershulker.precision", GLFW.GLFW_KEY_LEFT_CONTROL),
                registerKey(event, "key.bettershulker.alt_force", GLFW.GLFW_KEY_LEFT_ALT),
                registerKey(event, "key.bettershulker.scroll_left", GLFW.GLFW_KEY_LEFT),
                registerKey(event, "key.bettershulker.scroll_right", GLFW.GLFW_KEY_RIGHT),
                registerKey(event, "key.bettershulker.restock", GLFW.GLFW_KEY_R),
                registerKey(event, "key.bettershulker.show_full_tooltip", GLFW.GLFW_KEY_V)
        );
    }

    private static KeyMapping registerKey(RegisterKeyMappingsEvent event, String translationKey, int defaultKey) {
        KeyMapping key = new KeyMapping(
                translationKey,
                defaultKey,
                BetterShulkerClient.getCustomCategory()
        );
        event.register(key);
        return key;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        BetterShulkerClient.handleClientTick(Minecraft.getInstance());
    }

    private void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BetterShulkerClient.resetState();
    }
}

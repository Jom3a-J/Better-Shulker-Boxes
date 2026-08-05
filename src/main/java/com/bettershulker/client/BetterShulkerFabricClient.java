package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.render.ShulkerTooltipComponent;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.client.render.ResourcePackCacheReloader;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric client entrypoint. Keeps Fabric APIs out of the shared client state class.
 */
@Environment(EnvType.CLIENT)
public final class BetterShulkerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing Fabric client module");

        BetterShulkerConfig.load();
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                ResourcePackCacheReloader.ID, ResourcePackCacheReloader.create());

        PlatformNetworking.setDelegate(new PlatformNetworking.Delegate() {
            @Override
            public void sendToServer(CustomPacketPayload payload) {
                ClientPlayNetworking.send(payload);
            }

            @Override
            public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
            }
        });

        ClientTooltipComponentCallback.EVENT.register(data -> {
            if (data instanceof ShulkerTooltipData shulkerData) {
                return new ShulkerTooltipComponent(shulkerData);
            }
            return null;
        });

        ClientPlayNetworking.registerGlobalReceiver(
                EnderChestSyncPayload.TYPE,
                (payload, context) -> context.client().execute(() -> BetterShulkerClient.applyEnderChestSync(payload))
        );

        BetterShulkerClient.setKeyMappings(
                registerKey("key.bettershulker.settings", GLFW.GLFW_KEY_B),
                registerKey("key.bettershulker.extract", GLFW.GLFW_KEY_E),
                registerKey("key.bettershulker.select_slot", GLFW.GLFW_KEY_SPACE),
                registerKey("key.bettershulker.filter", GLFW.GLFW_KEY_F),
                registerKey("key.bettershulker.precision", GLFW.GLFW_KEY_LEFT_CONTROL),
                registerKey("key.bettershulker.alt_force", GLFW.GLFW_KEY_LEFT_ALT),
                registerKey("key.bettershulker.scroll_left", GLFW.GLFW_KEY_LEFT),
                registerKey("key.bettershulker.scroll_right", GLFW.GLFW_KEY_RIGHT),
                registerKey("key.bettershulker.restock", GLFW.GLFW_KEY_R),
                registerKey("key.bettershulker.show_full_tooltip", GLFW.GLFW_KEY_V)
        );

        ClientTickEvents.END_CLIENT_TICK.register(BetterShulkerClient::handleClientTick);
        String currentVersion = FabricLoader.getInstance()
                .getModContainer(BetterShulkerMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                UpdateChecker.checkForUpdates(client, currentVersion));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BetterShulkerClient.resetState();
            UpdateChecker.onDisconnect();
        });

        BetterShulkerMod.LOGGER.info("[BetterShulker] Fabric client module initialized successfully");
    }

    private static KeyMapping registerKey(String translationKey, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                translationKey,
                defaultKey,
                BetterShulkerClient.getCustomCategory()
        ));
    }
}

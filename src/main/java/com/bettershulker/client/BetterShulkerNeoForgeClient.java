package com.bettershulker.client;

import com.bettershulker.BetterShulkerConfig;
import com.bettershulker.BetterShulkerMod;
import com.bettershulker.client.render.ShulkerTooltipComponent;
import com.bettershulker.client.render.ShulkerTooltipData;
import com.bettershulker.client.render.ResourcePackCacheReloader;
import com.bettershulker.network.EnderChestSyncPayload;
import com.bettershulker.platform.PlatformNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge client entrypoint.
 */
@Mod(value = BetterShulkerMod.MOD_ID, dist = Dist.CLIENT)
public final class BetterShulkerNeoForgeClient {
    private final ModContainer modContainer;

    public BetterShulkerNeoForgeClient(IEventBus modBus, ModContainer modContainer) {
        this.modContainer = modContainer;
        BetterShulkerMod.LOGGER.info("[BetterShulker] Initializing NeoForge client module");

        BetterShulkerConfig.load();

        // Only the client direction. BetterShulkerNeoForgeMod installs the server sender, on this
        // dist too, which is what the integrated server uses.
        PlatformNetworking.setClientSender(payload -> ClientPacketDistributor.sendToServer(payload));
        BetterShulkerMod.setClientEnderChestSupplier(EnderChestCache::getEnderChestContents);

        IConfigScreenFactory configScreenFactory = (container, parent) -> BetterShulkerClothConfigScreen.create(parent);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);

        modBus.addListener(this::registerTooltipFactories);
        modBus.addListener(this::registerKeyMappings);
        modBus.addListener(this::registerClientPayloadHandlers);
        modBus.addListener(this::registerClientReloadListeners);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onClientLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onClientLoggingOut);
    }

    private void registerTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(ShulkerTooltipData.class, ShulkerTooltipComponent::new);
    }

    private void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(EnderChestSyncPayload.TYPE, (payload, context) -> EnderChestCache.applyEnderChestSync(payload));
    }

    private void registerClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(ResourcePackCacheReloader.ID, ResourcePackCacheReloader.create());
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(ClientKeybinds.getCustomCategory());
        ClientKeybinds.setKeyMappings(
                registerKey(event, "key.bettershulker.settings", GLFW.GLFW_KEY_B),
                registerKey(event, "key.bettershulker.extract", GLFW.GLFW_KEY_E),
                registerKey(event, "key.bettershulker.select_slot", GLFW.GLFW_KEY_SPACE),
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
                ClientKeybinds.getCustomCategory()
        );
        event.register(key);
        return key;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        BetterShulkerClient.handleClientTick(Minecraft.getInstance());
    }

    private void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        UpdateChecker.checkForUpdates(
                Minecraft.getInstance(),
                modContainer.getModInfo().getVersion().toString()
        );
    }

    private void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BetterShulkerClient.resetState();
        UpdateChecker.onDisconnect();
    }
}

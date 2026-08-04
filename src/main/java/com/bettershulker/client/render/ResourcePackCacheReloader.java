package com.bettershulker.client.render;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Clears pack-derived GUI data after Minecraft finishes reloading client resources. */
public final class ResourcePackCacheReloader {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            "bettershulker", "resource_pack_cache");

    private ResourcePackCacheReloader() {
    }

    public static PreparableReloadListener create() {
        return new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(SharedState sharedState,
                                                   Executor preparationExecutor,
                                                   PreparationBarrier barrier,
                                                   Executor reloadExecutor) {
                CompletableFuture<Void> prepared = barrier.wait(null);
                return prepared.thenRunAsync(ResourcePackContainerTextures::clearCache, reloadExecutor);
            }

            @Override
            public String getName() {
                return "Better Shulker resource-pack cache";
            }
        };
    }
}

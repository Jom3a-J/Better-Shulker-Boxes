package com.bettershulker.gametest;

import net.fabricmc.api.ModInitializer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Items the tests need that vanilla does not have.
 *
 * <p>Registered at startup because items cannot be created once the registry is frozen.</p>
 */
public final class TestItems implements ModInitializer {

    private static final ResourceKey<Item> NOT_FOR_CONTAINERS_KEY = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("bettershulker-test", "not_for_containers"));

    /**
     * An item that refuses to go inside container items, as a modded backpack does. In vanilla only
     * Shulker Boxes refuse, so this is the only way to test the rule apart from them.
     */
    public static Item notForContainers;

    @Override
    public void onInitialize() {
        notForContainers = Registry.register(BuiltInRegistries.ITEM, NOT_FOR_CONTAINERS_KEY,
                new Item(new Item.Properties().setId(NOT_FOR_CONTAINERS_KEY)) {
                    @Override
                    public boolean canFitInsideContainerItems() {
                        return false;
                    }
                });
    }
}

package com.bettershulker.util;

import com.bettershulker.BetterShulkerConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The sound an insert or extract makes.
 *
 * <p>Either the one fixed option the player chose, or - under Contextual Materials - one picked
 * from what the item is made of, so putting stone away sounds like stone. Pitch is randomised
 * slightly, and extraction is pitched below insertion so the two directions are audibly
 * different without needing separate sounds.</p>
 */
public final class InteractionSounds {

    private InteractionSounds() {}

    /**
     * Returns the contextual sound for an ItemStack based on its material.
     */
    public static SoundEvent getContextualSound(ItemStack stack, boolean isInsert) {
        if (stack == null || stack.isEmpty()) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }
        var item = stack.getItem();
        String itemPath = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase();

        if (ContainerHelper.containsAny(itemPath, "diamond", "emerald", "nether_star", "amethyst_shard")) {
            return isInsert ? SoundEvents.AMETHYST_CLUSTER_PLACE : SoundEvents.AMETHYST_CLUSTER_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "ender", "eye", "totem", "echo_shard", "beacon", "nether_brick", "quartz")) {
            return isInsert ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.AMETHYST_BLOCK_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "glass", "glowstone", "lantern", "spyglass", "amethyst_cluster", "amethyst_bud")) {
            return isInsert ? SoundEvents.GLASS_PLACE : SoundEvents.GLASS_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "potion", "bottle", "bucket_of", "milk_bucket", "honey_bottle", "dragon_breath")) {
            return isInsert ? SoundEvents.BOTTLE_FILL : SoundEvents.BOTTLE_EMPTY;
        }
        if (ContainerHelper.containsAny(itemPath, "sword", "bow", "crossbow", "shield", "trident", "helmet", "chestplate",
                "leggings", "boots", "arrow", "horse_armor")) {
            return isInsert ? SoundEvents.ARMOR_EQUIP_IRON.value() : SoundEvents.ARMOR_EQUIP_CHAIN.value();
        }
        if (ContainerHelper.containsAny(itemPath, "iron", "gold", "netherite", "copper", "metal", "chain", "bucket", "anvil",
                "rail", "minecart")) {
            return isInsert ? SoundEvents.METAL_PLACE : SoundEvents.METAL_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "stone", "cobblestone", "obsidian", "deepslate", "brick", "granite", "diorite",
                "andesite", "sandstone", "basalt", "ore")) {
            return isInsert ? SoundEvents.STONE_PLACE : SoundEvents.STONE_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "wood", "plank", "log", "stick", "door", "fence", "chest", "sign", "boat",
                "sapling", "crafting_table")) {
            return isInsert ? SoundEvents.WOOD_PLACE : SoundEvents.WOOD_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "sand", "gravel", "dirt", "clay", "snow", "mud", "soul_sand", "mycelium", "podzol")) {
            return isInsert ? SoundEvents.SAND_PLACE : SoundEvents.SAND_HIT;
        }
        if (ContainerHelper.containsAny(itemPath, "seed", "crop", "wheat", "carrot", "potato", "apple", "food", "leaf", "leaves",
                "paper", "wool", "leather", "feather", "egg", "string", "flower", "grass", "sugar_cane",
                "bamboo", "bread", "cookie", "beef", "pork", "chicken", "mutton", "rabbit", "fish", "stew")) {
            return isInsert ? SoundEvents.BUNDLE_INSERT : SoundEvents.BUNDLE_DROP_CONTENTS;
        }
        return SoundEvents.ITEM_PICKUP;
    }

    /**
     * Plays an interaction sound for the given player.
     * Handles contextual sound selection and volume/pitch randomization.
     */
    public static void playInteractionSound(Player player, ItemStack stack, boolean isInsert, float volume) {
        if (player == null || volume <= 0.0f) return;

        BetterShulkerConfig.SoundOption configuredSound = BetterShulkerConfig.getSoundOption();
        SoundEvent soundEvent = configuredSound == BetterShulkerConfig.SoundOption.CONTEXTUAL
                ? getContextualSound(stack, isInsert)
                : resolveConfiguredSound(configuredSound);

        if (soundEvent != null) {
            float pitch = isInsert
                    ? 0.9F + player.level().getRandom().nextFloat() * 0.2F
                    : 0.65F + player.level().getRandom().nextFloat() * 0.15F;

            player.level().playSound(player, player.getX(), player.getY(), player.getZ(), soundEvent, SoundSource.PLAYERS, volume, pitch);
        }
    }

    /** One resolved option, held as a pair so a reader can never see a mismatched cache. */
    private record ResolvedSound(BetterShulkerConfig.SoundOption option, SoundEvent event) {}

    private static volatile ResolvedSound resolvedSound;

    /**
     * Looks up the sound a fixed option names, remembering the last one.
     *
     * <p>The ids are compile-time constants, so this parsed a string and queried the registry to
     * reach the same answer every time — once per slot crossed during a drag.</p>
     */
    private static SoundEvent resolveConfiguredSound(BetterShulkerConfig.SoundOption option) {
        ResolvedSound cached = resolvedSound;
        if (cached != null && cached.option() == option) {
            return cached.event();
        }

        SoundEvent resolved = SoundEvents.ITEM_PICKUP;
        try {
            String[] split = option.getSoundId().split(":", 2);
            var soundLoc = Identifier.fromNamespaceAndPath(split[0], split[1]);
            var soundHolderOpt = BuiltInRegistries.SOUND_EVENT.get(soundLoc);
            if (soundHolderOpt.isPresent()) {
                resolved = soundHolderOpt.get().value();
            }
        } catch (Exception e) {
            // A malformed or absent id keeps the pickup default, and caching that avoids
            // repeating the failed lookup on every interaction.
        }
        resolvedSound = new ResolvedSound(option, resolved);
        return resolved;
    }
}

# Better Shulker Boxes Multiloader Port Plan

Goal: keep Fabric and NeoForge in one branch and build two loader-specific jars from shared code.

## Target layout

```text
common/      shared Minecraft/mod logic with no Fabric/NeoForge imports
fabric/      Fabric entrypoints, networking, key registration, Mod Menu metadata
neoforge/    NeoForge @Mod entrypoints, networking, key registration, mod-list config hook
```

## Safe migration order

1. Add multiloader folders and platform API stubs without changing the current Fabric build.
2. Move pure shared code to `common` only after the current jar still builds.
3. Introduce a tiny platform layer for send/open/config hooks.
4. Convert Fabric loader code to use the shared code through that platform layer.
5. Add NeoForge build metadata and entrypoints.
6. Port NeoForge networking/keybind/tooltip/config events.
7. Build Fabric jar and NeoForge jar separately, then merge back to `main` only after both work.

## Loader-specific API replacements

| Current Fabric area | NeoForge replacement |
|---|---|
| `ModInitializer` / `ClientModInitializer` | `@Mod("bettershulker")` and client-side event registration |
| `PayloadTypeRegistry` | `RegisterPayloadHandlersEvent` + `PayloadRegistrar` |
| `ServerPlayNetworking.send` | `PacketDistributor.sendToPlayer` |
| `ClientPlayNetworking.send` | `ClientPacketDistributor.sendToServer` |
| `ClientTooltipComponentCallback` | `RegisterClientTooltipComponentFactoriesEvent` |
| `KeyMappingHelper` | `RegisterKeyMappingsEvent` |
| `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| `ServerPlayConnectionEvents.DISCONNECT` | `PlayerEvent.PlayerLoggedOutEvent` |
| `ClientPlayConnectionEvents.DISCONNECT` | `ClientPlayerNetworkEvent.LoggingOut` |
| Mod Menu config factory | `ModContainer.registerExtensionPoint(IConfigScreenFactory.class, ...)` |

## Versions researched

- Minecraft: `26.2`
- Java: `25`
- NeoForge: `26.2.0.7-beta`
- Cloth Config NeoForge: `26.2.155`

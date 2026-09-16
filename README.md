# Better Shulker Boxes

![banner](https://cdn.modrinth.com/data/cached_images/2462097c51baa015a7cf92b4a802b77d53dddf55.png)

Better Shulker Boxes is a highly interactive UI/UX mod that revolutionizes how you interact with Shulker Boxes in Minecraft. Instead of the tedious cycle of placing down a Shulker Box, opening it, interacting with it, breaking it, and picking it back up, this mod allows you to interact with Shulker Boxes and Ender Chests directly from your inventory using an advanced interactive tooltip!

---

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Java | 25 or newer |
| Fabric | Fabric Loader 0.19.0+, Fabric API, Cloth Config 26.3.158+ |
| NeoForge | NeoForge 26.3.0.1-beta+, Cloth Config 26.3.158+ |
| Quilt | Quilt Loader 0.31.0-beta.4+, Fabric API, Cloth Config 26.3.158+ |

Mod Menu is optional on Fabric and Quilt; the settings screen is reachable from the in-game keybind
either way.

---

## Key Features

### The Modern Tooltip

![modern tooltip](https://cdn.modrinth.com/data/cached_images/0dbbfaf62e654e1f1bfd4bc73ed36cb92d306bae.jpeg)

A flat rounded card, coloured by the box's own dye, with the container name and the selected item's
name in tabs along its top edge. It is the default style as of 1.5.0, and the previous look is still
available as the **Vanilla** style.

- **Modern** — a dye-coloured card, no vanilla tooltip frame
- **Vanilla** — built from the container's GUI texture, as before
- Every dye has its own card colour, and Ender Chests have theirs
- Theme and colour sliders apply to the Vanilla style; Modern colours itself

---

### Full & Compact Tooltips

![full and compact tooltip](https://cdn.modrinth.com/data/cached_images/c55e87bd2ac2973e13dbbaef430df2c417817215.gif)

Quickly check what is inside a Shulker Box without placing it down. Use the full 9×3 preview when you want to see every slot, or compact mode when you only need a small summary of the most important contents.

- Full 27-slot preview for complete box contents
- Compact mode with merged duplicate stacks
- Total item counts in compact view
- Empty compact containers stay hidden to reduce clutter

---

### Fast Insertion & Extraction

![extraction and insertion](https://cdn.modrinth.com/data/cached_images/b31cf6c3d34afe7c2b45155c08251a5b028b5d96.gif)

Move items in and out of Shulker Boxes directly from the tooltip. The mod gives instant visual feedback while still keeping the server authoritative.

- Hold **left-click** and drag to insert and **right-click** and drag to extract
- Insert cursor-held items directly into boxes
- Extract items directly from preview slots
- Smart merging with existing stacks
- Smooth client-side feedback with server-side validation

---

### Select Slots and Extract with `E`

![select and extract](https://cdn.modrinth.com/data/cached_images/a50917e481b3cdfaefdf0cd249e829ba4817837e.gif)

Select specific slots from the tooltip and extract them together. This makes it easy to grab only the items you want without opening or placing the box.

- `Space` selects or toggles tooltip slots
- `E` extracts selected slots
- If no slots are selected, `E` behaves normally and closes the inventory

---

### Keyboard & Scroll Navigation

![arrow and scroll](https://cdn.modrinth.com/data/cached_images/5d2a00a2f961a210f6c2ca81ac9cf40653f17bef.gif)

Navigate tooltip slots with the mouse wheel or arrow keys for faster control, especially when managing boxes with many items.

- Mouse wheel selection movement
- Arrow-key slot navigation
- Stable selected-slot highlight
- Smooth selection behavior while hovering tooltips

---

### Restock & Deposit

![restock and deposit](https://cdn.modrinth.com/data/cached_images/1681e438002b93da14c61e63a49f0425d9b0891c.gif)

Use the **Restock / Deposit** key to quickly move matching items between your inventory and the hovered container. By default this key is **`R`**.

- Press **`R`** while hovering a container preview to restock matching items
- Use the same **shift**+**`R`** key to deposit matching inventory items into storage
- Works with Shulker Boxes and Ender Chests
- The keybind can be changed in Minecraft’s controls menu

---

### Resource Pack Compatibility

![pack compatibility](https://cdn.modrinth.com/data/cached_images/947cfbedf755788a92c104ab6de187f3cb2b65f4.png)

Better Shulker Boxes is designed to respect custom GUI textures from resource packs, so previews can match the style of your current pack instead of looking out of place.

- Supports custom shulker/container GUI textures
- Supports colored shulker GUI styles where available
- Uses automatic layout profiles for supported OptiGUI/OptiFine-style packs
- Supports optional pack metadata at `assets/bettershulker/layouts/*.json` for custom slot layouts
- Provides X/Y slot offsets and bottom-cap controls in the settings screen for unusual packs
- Tested showcase packs include:
  - Recolourful Containers
  - Default Dark Mode
  - GUI Retextures
  - Translucent GUI and HUD

### Custom resource-pack layout metadata

Resource packs that move the storage grid can add a JSON profile under
`assets/bettershulker/layouts/`. The profile format is documented in
`docs/resource-pack-layout-example.json`. The `match.texture` value may use `*` as a wildcard.
If no profile is found, the mod uses a safe standard 9×3 layout and the in-game offsets can be
used for small adjustments.

---

## More Features

- Alt Force tooltip while holding a box
- Colored shulker support
- Selected item name tooltip
- Multiple tooltip themes
- Custom theme colors
- Fill indicator on the slot and along the tooltip's lower edge
- A Shulker Box bounces in its slot while you carry an item it still has room for
- Cloth Config settings screen
- Live Theme & Colors preview
- Sound and volume options

---

## Project structure

One source set in `src/main/java/com/bettershulker` is shared by all three loaders. Each loader
contributes only an entrypoint (`BetterShulkerFabricMod`, `BetterShulkerNeoForgeMod`, and the
Quilt resources) that registers networking and events, then calls into the shared code below.

```
com.bettershulker
├── BetterShulkerMod            mod init, packet dispatch
├── BetterShulkerConfig         settings, loaded from and saved to .properties
├── server/                     server-authoritative container handling
│   ├── EnderChestService         contents live on the player, so the server owns them
│   ├── ShulkerInteractionHandler contents live in the item's data component
│   ├── InteractionRateLimiter    per-tick cap, plus the resync a dropped packet needs
│   └── ServerSlots               verifying a slot the client named
├── client/
│   ├── BetterShulkerClient       tooltip state and the prediction store
│   ├── ClientKeybinds            key mappings and their held-state
│   ├── EnderChestCache           what the server has sent; null is not empty
│   ├── interact/                 what a preview does, on the client side
│   │   ├── ContainerPrediction     applies an interaction locally, then reconciles
│   │   ├── ContainerSelection      where the selection square is and how it moves
│   │   ├── ContainerActions        extract, insert, restock, deposit
│   │   └── InputKeys               modifier keys, each behind its own setting
│   └── render/                   drawing the preview
│       ├── ShulkerTooltipComponent the tooltip itself
│       ├── TooltipPalette          every colour, derived from theme, style and dye
│       ├── ModernCardPainter       the Modern style's card and name tabs
│       ├── ResourcePackPanelPainter recomposes a pack's own panel from slices
│       └── ResourcePack*           detecting and profiling pack GUI textures
├── mixin/                      injection points only; the logic lives in the classes above
├── network/                    payloads, and the slot encoding they address
├── platform/PlatformNetworking loader-neutral send, so shared code imports no loader API
└── util/ContainerHelper        insertion, extraction and container queries shared by both sides
```

Two conventions are worth knowing before changing anything here. The server is authoritative for
every interaction — the client predicts one locally so the cursor moves with the click, and the
server's own view overwrites that prediction when it arrives; a change that only convinces the
client is not a change. And slots are addressed by `MenuSlotRef`, not by raw menu index, because
screens that build their own menu number their slots differently from the server's.

---

## License

This mod is licensed under the **MIT License**

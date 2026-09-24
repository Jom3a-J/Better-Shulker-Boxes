# Changelog

## 1.6.2

### Fixed

- **NeoForge: a dark frame showed around the Modern tooltip.** Vanilla's tooltip frame was drawn
  under the card, sticking out past its right and bottom edges and showing through the gap
  between the title tabs. NeoForge draws item tooltips its own way, which skipped the hook that
  hides the frame. The hook now covers both paths. Fabric and Quilt were not affected.

Tested on NeoForge 26.3.0.16-beta with Cloth Config 26.3.159, the first Cloth Config release for
NeoForge on 26.3.

## 1.6.1

### Fixed

- **A carried Shulker Box would not go back down.** On 26.3, left-clicking an empty slot with a
  box on the cursor took the box's first stack out instead of placing the box. The click reached
  Minecraft with 26.3's new number for the left button, which it read as a right-click. It now
  goes through Minecraft's own conversion first, so left places and right extracts, as before.

A client game test now picks up a filled box, puts it down in an empty slot, and checks on the
server that the box arrived with everything still inside.

## 1.6.0

### New

- **Minecraft 26.3 support**, on Fabric, NeoForge and Quilt alike.

### Fixed

- **Keys and mouse buttons still do what they say.** 26.3 replaced GLFW with SDL behind the
  window, which renumbers every key and mouse button underneath — right-click, for one, moved from
  1 to 3. Each key the mod reads is now asked for by name rather than by number, so the defaults,
  your rebinds and the modifier keys all land on the same keys they did before.

Internally, three calls changed shape under 26.3 and were updated in place: polling whether a key
is held, handing a stack back to a player, and drawing a tooltip. The client game tests run against
26.3 and pass; they also stopped hard-coding mouse button numbers, which is what caught the SDL
renumbering in the first place. Quilt's Mixin and MixinExtras pins now match the versions Quilt
Loader itself ships, and the line each loader logs on startup reads the running Minecraft version
instead of naming one that had been written into the source.

## 1.5.1

### Fixed

- **Box colours came out wrong under a resource pack.** With the Modern style and a pack that
  recolours container GUIs, a red Shulker Box drew a purple card, a yellow one drew teal and a
  blue one maroon. Every colour taken from a pack had its red and blue swapped over.
- **Cards were muddy and hard to tell apart.** The colour was read from the slot squares instead
  of the panel around them, and packs keep slots much darker, so red sat next to brown and green
  next to lime. A card now takes the pack's panel colour and keeps a little of the box's own dye
  on top of it, so no two boxes look alike — including under packs that ship one GUI for all
  sixteen.
- **Ender Chests borrowed a chest's colours.** An Ender Chest opens the same screen as every
  chest and barrel, so under a pack that paints those wooden the Ender preview went wooden too.
  It keeps Better Shulker's own colours now, as it already did under the Vanilla style.

Internally, the panel sampler now reads the pack's panel rather than the slot band, and a client
game test drives the four dyed previews under a real pack and checks each card against its dye.

The inventory screen also stops rebuilding a box's 27 stacks just to ask whether it holds
anything, how full it is, or whether one more item would fit — it reads what the box stores
directly. The three resource-pack caches drop their coldest entry when full instead of emptying
themselves, so passing a chest of renamed boxes no longer throws away every panel it had already
worked out. Neither changes what you see; both are covered by new game tests.

## 1.5.0

### New

- **Modern tooltip style** — a flat rounded card coloured by the box's own dye, with the container
  and selected item names in tabs along the top. It is now the default; the previous look is still
  available as the **Vanilla** style. The theme and colour sliders apply to Vanilla only, and are
  greyed out under Modern, which colours itself.
- The container bounce can be turned off, and is slower than it was.

### Fixed

- **Arrow keys lost your column.** Pressing Up from the top row jumped to the far corner of the
  grid instead of wrapping to the bottom of the same column. Down did the mirror of it.
- **The selection stopped on every empty slot.** Crossing a box holding three items took two dozen
  presses. It now steps straight to the next slot that actually holds something.
- **Marks could empty the wrong box.** Selecting slots with `Space` in one box and then pressing
  the extract key over a different box took items out of *that* box, at the same slot numbers.
  Marks now clear when you move to another container.
- **Full boxes still invited a drop.** A box with no room left kept bouncing and showing the green
  `+` while you carried an item over it, and dragging across one played a sound per slot for
  nothing.
- **Interaction sounds fell back to a generic click** when a whole stack went into, or came out of,
  an Ender Chest. Contextual Materials now picks the right sound for the item in both directions.
- **Compact mode hid stacks silently.** It shows only the five largest, and now says how many it
  left out. Totals above 999 read as `1.7k` rather than being flattened to `999+`, and merged tools
  keep their durability bar.
- **The fill indicator ignored its own setting.** The strip along the bottom of the tooltip stayed
  on with Fill Indicator turned off, sat on the Modern card's border, and never appeared for Ender
  Chests under Modern.
- **The settings preview disagreed with the tooltip.** It spelled out `V` whatever the key was
  really bound to, framed the selected slot in gold when the real one has no frame, and drew a name
  badge under Modern that no Modern tooltip has.

### Removed

- The item filter. It was unfinished and is gone rather than left half-working.

### Internal

- The five largest classes were split into twenty focused ones. No behaviour was meant to change,
  and each move was checked line by line against the original.
- Added a client game test suite — twelve tests that open the inventory, click on slots, and check
  the results against the server. Run with `gradlew :fabric:runClientGameTest`.

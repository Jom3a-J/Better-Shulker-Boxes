# Changelog

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

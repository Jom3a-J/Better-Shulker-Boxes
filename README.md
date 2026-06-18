# Better Shulker Boxes

**Version:** 1.1.0  
**Minecraft Version:** 26.2 "Chaos Cubed"  
**Mod Loader:** Fabric  

## Overview
Better Shulker Boxes is a highly interactive UI/UX mod that revolutionizes how you interact with portable storage in Minecraft. Instead of the tedious cycle of placing down a Shulker Box, opening it, interacting with it, breaking it, and picking it back up, this mod allows you to interact with Shulker Boxes and Ender Chests *directly* from your inventory using an advanced interactive tooltip!

## Key Features
- **Interactive Tooltip:** Hover over any Shulker Box or Ender Chest to see a rich 9x3 tooltip of its contents.
- **Direct Interaction:** 
  - **Right-Click Tap:** Extract the currently selected item into your inventory.
  - **Left-Click Drag:** Insert held items into the container.
  - **Right-Click Drag:** Extract items from the container.
- **Advanced Selection & Scrolling:** Use your scroll wheel (or arrow keys) to cycle through items inside the tooltip.
- **Batch Extraction:** Hold `Shift` while dragging to extract all items of a type.
- **Precision Mode:** Hold `Ctrl` while extracting to pull out exactly one item at a time.
- **Search & Filtering:** Press the Search key to focus a search bar, highlighting matching items. Press the Filter key over an item to highlight only that specific item type.
- **Sorting:** Press the Sort key while hovering over the tooltip to automatically sort the contents of the container.
- **Restock & Deposit:** Quickly move matching items between your inventory and the container using the configurable Restock key.
- **Server-Side Validation:** Fully secure networking ensures no duplication exploits are possible.
- **Ender Chest Support:** Not just for Shulker Boxes! View and interact with your personal Ender Chest inventory on the go (respects rate limits to prevent server lag).

## How to Use

### Basic Controls
1. **View Contents:** Simply hover over a Shulker Box or Ender Chest in your inventory. (Hold `Alt` to force the tooltip if it's hidden).
2. **Select an Item:** Use your Mouse Scroll Wheel (or configured Left/Right arrow keys) while hovering over the container to highlight a specific item inside the tooltip.
3. **Extract Items:** 
   - Right-click on the container to pull the currently highlighted item out.
   - Or, right-click and drag across empty slots in your inventory to drop items from the container.
4. **Insert Items:**
   - Pick up an item with your cursor, then Left-click and drag over the container in your inventory to insert it.

### Advanced Controls
- **Precision Mode (`Ctrl`):** Hold the `Ctrl` key (configurable) while interacting to process exactly 1 item at a time instead of full stacks.
- **Batch Extract (`Shift`):** Hold `Shift` while extracting to grab *all* stacks of the selected item type from the container at once.
- **Multi-Select:** Press the Selection key to "tag" multiple items in the tooltip, then use the Extract key to pull them all out simultaneously.

### Search and Filters
- **Search Key:** Toggles a search text input. Type the name of the item you're looking for, and non-matching items will be greyed out.
- **Filter Key:** While hovering over a specific item in the tooltip (or your inventory), press the Filter key to instantly grey out all other item types.
- **Sort Key:** Automatically sorts the items inside the Shulker Box/Ender Chest.
- **Restock Key:** Quickly moves all matching items from your inventory into the Shulker Box (or vice versa if Shift is held).

## Configuration
Better Shulker Boxes is fully compatible with **Mod Menu** and **Cloth Config**. 
To access the settings:
1. Ensure `Cloth Config API` is installed.
2. If `Mod Menu` is installed, go to your Mods list and click the configuration button next to Better Shulker Boxes.
3. Alternatively, press the dedicated Settings Keybind (default: `B`) while in-game.

In the configuration screen, you can:
- Change all keybinds (Sort, Restock, Search, Filter, Select, Precision Mode).
- Adjust the volume and type of UI sound effects.
- Toggle features like the secondary tooltip or Ender Chest integration.

## Installation Requirements
- **Minecraft:** 26.2
- **Mod Loader:** Fabric Loader (0.19.0+)
- **Dependencies:** Fabric API (Optional: Mod Menu & Cloth Config)

---
*Better Shulker Boxes — Less placing, more playing.*

# Better Shulker Boxes — Adversarial Review Findings

> **Review date:** 2026-06-18  
> **Target:** Better Shulker Boxes v1.0.0 (Minecraft 26.1 / Fabric)  
> **Method:** 3 parallel adversarial reviewers (Correctness, Maintainability, Security)  
> **File:** `E:/main minecraft mod files/‏‏Better-Shulker-Boxes-1.0.0 -copy/`

---

## Table of Contents

1. [🔴 CRITICAL: Crafting Output Slot Duplication via SWEEP_INSERT](#1--critical-crafting-output-slot-duplication-via-sweep_insert)
2. [🔴 CRITICAL: Generalized Computed-Slot Duplication (Anvil, Grindstone, Stonecutter, Smithing)](#2--critical-generalized-computed-slot-duplication)
3. [🟠 HIGH: No Rate Limiting on ContainerInteractPayload](#3--high-no-rate-limiting-on-containerinteractpayload)
4. [🟠 HIGH: Missing Ender Chest Authentication in Request Handler](#4--high-missing-ender-chest-authentication-in-request-handler)
5. [🟡 MEDIUM: Client Prediction Timeout Too Short (800ms)](#5--medium-client-prediction-timeout-too-short-800ms)
6. [🟡 MEDIUM: ~700 Lines of Duplicated Server/Client Action Logic](#6--medium-700-lines-of-duplicated-serverclient-action-logic)
7. [🟡 MEDIUM: WeakHashMap Misuse for Player UUID Keys](#7--medium-weakhashmap-misuse-for-player-uuid-keys)
8. [🟡 MEDIUM: Dead Code — Unused Methods, Class, and Imports](#8--medium-dead-code--unused-methods-class-and-imports)
9. [🟢 LOW: Misleading Field Name `hoveredTooltipContainer`](#9--low-misleading-field-name-hoveredtooltipcontainer)
10. [🟢 LOW: Sound Playing Logic Duplicated Across 4 Files](#10--low-sound-playing-logic-duplicated-across-4-files)
11. [🟢 LOW: ItemMixin Bypasses ContainerInteractPayload Validation Layer](#11--low-itemmixin-bypasses-containerinteractpayload-validation-layer)
12. [🟢 LOW: Shulker Box Dye Copies All Components Without Sanity Check](#12--low-shulker-box-dye-copies-all-components-without-sanity-check)

---

## . 🔴 CRITICAL: Crafting Output Slot Duplication via `SWEEP_INSERT`

### Description

A malicious player can duplicate any craftable item infinitely by sending a forged `SWEEP_INSERT` packet targeting the **crafting output slot** (slot index 0 in a `CraftingMenu`). The server reads the computed result item, inserts it into the shulker/ender chest, and clears the output slot — but **never consumes the input materials** from the crafting grid. The server then recalculates the recipe next tick, causing the output to reappear, ready to be extracted again.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `BetterShulkerMod.java` | 372–381 | Server handler: `handleShulkerInteraction` SWEEP_INSERT case |
| `BetterShulkerMod.java` | 623–666 | Server handler: `handleEnderChestInteraction` SWEEP_INSERT case |
| `HandledScreenMixin.java` | 387–400 | Client: `tryDragInsert` sends the packet |

### Root Cause

The `SWEEP_INSERT` handler reads from `player.containerMenu.slots.get(inventorySlotId)` without checking whether that slot is a **computed result slot** (like crafting output) rather than a real inventory slot. The line:

```java
// BetterShulkerMod.java ~372
ItemStack invStack = player.containerMenu.slots.get(inventorySlotId).getItem();
```

This reads any slot, including slot 0 of a crafting table which is a `ResultSlot`. Result slots hold items computed from recipe ingredients — the actual input items are still in adjacent `CraftingGridSlot` instances and have not been consumed.

### Exploit Steps

1. Player carries a shulker box (or has an ender chest in inventory)
2. Player opens a crafting table, places items for any recipe (e.g., 9 diamonds → diamond block)
3. Player sends `ContainerInteractPayload` with:
   - `containerSlotId = -1` (carried shulker) or `-2` (wireless ender chest)
   - `inventorySlotId = 0` (crafting output slot)
   - `action = SWEEP_INSERT.ordinal()`
4. Server copies the diamond block into the container, sets slot 0 to empty
5. Recipe recalculation next tick: diamond block reappears because the 9 diamonds are still in the grid
6. Repeat → unlimited diamond blocks

### How to Fix

**File:** `BetterShulkerMod.java` — in `handleShulkerInteraction` and `handleEnderChestInteraction`, add a guard at the top of every SWEEP_INSERT case:

```java
case SWEEP_INSERT -> {
    if (inventorySlotId < 0 || inventorySlotId >= player.containerMenu.slots.size()) return;
    
    // FIX: Reject if the target slot is a computed result slot
    Slot targetSlot = player.containerMenu.slots.get(inventorySlotId);
    if (targetSlot instanceof net.minecraft.world.inventory.CraftingResultSlot
        || targetSlot instanceof net.minecraft.world.inventory.AnvilResultSlot
        || targetSlot instanceof net.minecraft.world.inventory.GrindstoneResultSlot
        || targetSlot instanceof net.minecraft.world.inventory.StonecutterResultSlot
        || targetSlot instanceof net.minecraft.world.inventory.SmithingResultSlot) {
        LOGGER.warn("[BetterShulker] Player {} tried SWEEP_INSERT on a result slot, rejected",
            player.getName().getString());
        return;
    }
    
    // More robust: only allow player inventory slots
    if (!(targetSlot.container instanceof net.minecraft.world.entity.player.Inventory)) {
        LOGGER.warn("[BetterShulker] Player {} tried SWEEP_INSERT on a non-inventory slot, rejected",
            player.getName().getString());
        return;
    }
    
    ItemStack invStack = targetSlot.getItem();
    // ... rest of existing logic
}
```

The **more robust approach** is to check `targetSlot.container instanceof PlayerInventory` — this guarantees the item being inserted comes from the player's actual inventory, not a computed or shared container slot. Apply this same check in ALL four SWEEP_INSERT locations (shulker server, ender chest server, shulker client prediction, ender chest client prediction).

---

## 2. 🔴 CRITICAL: Generalized Computed-Slot Duplication

### Description

The same vulnerability described in issue #1 applies to **any** container screen with a computed result slot. This includes:

- **Anvil** (result slot)
- **Grindstone** (result slot)
- **Stonecutter** (result slot)
- **Smithing Table** (result slot)

Each of these screens has a slot where the server places a computed result item without consuming input materials until the player actually takes the result. By using `SWEEP_INSERT` with `inventorySlotId` pointing to any of these result slots, the same duplication exploit works.

### Affected Files

Same as issue #1 — the fix is the same.

### How to Fix

The `instanceof PlayerInventory` check described in issue #1 covers all these cases in one check. The alternative is an explicit instance-of check against each result slot type. Prefer the `PlayerInventory` check for future-proofing.

---

## 3. 🟠 HIGH: No Rate Limiting on `ContainerInteractPayload`

### Description

The `ContainerInteractPayload` handler has **zero rate limiting**. A malicious client can send thousands of packets per tick. Each invocation allocates `ArrayList`, `HashSet`, `NonNullList` objects, iterates over inventory slots, calls `ItemStack.copy()` and `ItemStack.isSameItemSameComponents()`, and sends `broadcastFullState()` on success.

This is a **server-side CPU exhaustion** vector that causes tick lag and potential denial of service.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `BetterShulkerMod.java` | 97–104 | `registerContainerInteractHandler()` — unbounded receiver |

### How to Fix

**File:** `BetterShulkerMod.java`

Add a `Map<UUID, Integer>` tracking packet counts per player per tick, and reject packets exceeding a threshold.

```java
// Add as a class field in BetterShulkerMod
private static final Map<UUID, Integer> interactionCountsThisTick = new HashMap<>();
private static final int MAX_INTERACTIONS_PER_TICK = 10;
private static int currentTick = -1;

// In registerContainerInteractHandler, before calling handleContainerInteraction:
ServerPlayer player = context.player();
int currentTick = player.level().getGameTime();

// Reset counter on new tick
if (BetterShulkerMod.currentTick != currentTick) {
    BetterShulkerMod.currentTick = currentTick;
    interactionCountsThisTick.clear();
}

int count = interactionCountsThisTick.getOrDefault(player.getUUID(), 0);
if (count >= MAX_INTERACTIONS_PER_TICK) {
    LOGGER.warn("[BetterShulker] Player {} exceeded interaction rate limit, dropping packet",
        player.getName().getString());
    return; // or call resyncPlayer
}
interactionCountsThisTick.put(player.getUUID(), count + 1);

// Then proceed with handleContainerInteraction...
```

**Alternative:** Use a token-bucket approach with a cooldown per player (e.g., max 20 actions per 100ms window).

---

## 4. 🟠 HIGH: Missing Ender Chest Authentication in Request Handler

### Description

Any player can send an `EnderChestRequestPayload` at any time from any dimension — without holding an ender chest item — and immediately receive their full ender chest contents via `EnderChestSyncPayload`. The server performs **no authentication check**.

While a player can normally access their own ender chest by placing an ender chest block, this handler removes the requirement to be near one or carry one, and leaks ender chest contents over the network as a side effect.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `BetterShulkerMod.java` | 80–90 | `registerEnderChestRequestHandler()` |

### How to Fix

**File:** `BetterShulkerMod.java`

Add a check before sending the sync payload:

```java
private void registerEnderChestRequestHandler() {
    ServerPlayNetworking.registerGlobalReceiver(
            EnderChestRequestPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                context.player().level().getServer().execute(() -> {
                    // FIX: Verify player has an ender chest item or block access
                    boolean hasEnderChest = false;
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        ItemStack stack = player.getInventory().getItem(i);
                        if (!stack.isEmpty() && ContainerHelper.isEnderChest(stack)) {
                            hasEnderChest = true;
                            break;
                        }
                    }
                    
                    if (!hasEnderChest) {
                        LOGGER.warn("[BetterShulker] Player {} requested ender chest sync without carrying one",
                            player.getName().getString());
                        return;
                    }
                    
                    lastSyncedEnderChest.remove(player.getUUID());
                    ServerPlayNetworking.send(player, buildEnderChestSyncPayload(player));
                });
            }
    );
}
```

---

## 5. 🟡 MEDIUM: Client Prediction Timeout Too Short (800ms)

### Description

Client prediction transactions are discarded **800ms** after creation if no matching server response is received. Under server lag or for players with high ping (e.g., >400ms RTT), the prediction is silently removed with **no rollback animation and no correction**. The client UI remains in its predicted (modified) state — showing wrong item counts on the cursor or in slots — until the next full state sync packet arrives.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `HandledScreenMixin.java` | ~1576 | `verifyPredictions()` timeout check |

### How to Fix

**File:** `HandledScreenMixin.java`

Increase the timeout to account for realistic server/network conditions:

```java
// Change from:
if (now - tx.timestamp > 800) {

// To:
if (now - tx.timestamp > 5000) {  // 5 seconds for high-latency safety
```

**Better approach:** Tie the timeout to a received container sync packet rather than wall-clock time. Add a version counter to the container menu that increments on every server sync, and only evict predictions when the version counter advances past the prediction's version.

---

## 6. 🟡 MEDIUM: ~700 Lines of Duplicated Server/Client Action Logic

### Description

The 9-branch switch-case statements for shulker box and ender chest actions are **copy-pasted** between:

- **Server handler** (`BetterShulkerMod.java` `handleShulkerInteraction` ~230 lines + `handleEnderChestInteraction` ~260 lines)
- **Client prediction** (`HandledScreenMixin.java` `bettershulker$predictShulkerBox` ~180 lines + `bettershulker$predictEnderChest` ~250 lines)

**Total: ~700+ lines of near-identical code.** The SORT, RESTOCK, and DEPOSIT cases in particular are **literal copy-pastes** (~35, ~29, ~85 lines respectively).

When a bug is fixed or a feature is added in one path, the other path **must** be updated manually — there is no compiler warning if they drift apart. This is the #1 source of desync bugs.

### Affected Files

| File | Lines | Duplicated from |
|------|-------|-----------------|
| `BetterShulkerMod.java` | 226–499 (shulker) | — (authoritative source) |
| `BetterShulkerMod.java` | 500–759 (ender) | — (authoritative source) |
| `HandledScreenMixin.java` | 1270–1450 (pred shulker) | `BetterShulkerMod.java` ~226–499 |
| `HandledScreenMixin.java` | 1471–1730 (pred ender) | `BetterShulkerMod.java` ~500–759 |

### How to Fix

**Recommended approach:** Extract a shared `ContainerActionExecutor` utility class that both the server handler and client prediction call:

```java
// New file: com/bettershulker/util/ContainerActionExecutor.java

/**
 * Shared action executor for container operations.
 * Called by BOTH the server-side handler (BetterShulkerMod) and
 * the client-side prediction (HandledScreenMixin) to ensure
 * identical behavior on both sides.
 */
public class ContainerActionExecutor {
    
    @FunctionalInterface
    public interface SlotAccessor {
        ItemStack get(int slotId);
        void set(int slotId, ItemStack stack);
    }
    
    public static void executeShulkerAction(
            NonNullList<ItemStack> contents,
            MutableObject<ItemStack> cursorRef,
            int targetIndex,
            ContainerInteractPayload.InteractType action,
            int inventorySlotId,
            SlotAccessor slotAccessor) {
        
        switch (action) {
            case INSERT -> { /* single implementation */ }
            case INSERT_ONE -> { /* single implementation */ }
            case EXTRACT -> { /* single implementation */ }
            // ... all 9 cases, never duplicated
        }
        
        ContainerHelper.setContainerContents(containerStack, contents);
    }
    
    public static void executeEnderChestAction(
            NonNullList<ItemStack> contents,
            MutableObject<ItemStack> cursorRef,
            int targetIndex,
            ContainerInteractPayload.InteractType action,
            int inventorySlotId,
            SlotAccessor slotAccessor) {
        // Same pattern — single implementation for 9 cases
    }
}
```

Then:
- Server: `ContainerActionExecutor.executeShulkerAction(contents, cursorRef, ...)`
- Client: `ContainerActionExecutor.executeShulkerAction(contents, cursorRef, ...)`

This eliminates all ~700 lines of duplication and guarantees the two sides never drift apart.

---

## 7. 🟡 MEDIUM: WeakHashMap Misuse for Player UUID Keys

### Description

`BetterShulkerMod` uses a `WeakHashMap<UUID, NonNullList<ItemStack>>` to cache ender chest diff states. However, `UUID` objects returned by `player.getUUID()` are typically **strongly referenced** by the player entity and the server's player list. This means the "weak" key behavior of `WeakHashMap` is ineffective — entries are never garbage-collected unless the UUID object itself has no strong references, which doesn't happen during gameplay.

Additionally:
- No size cap — unbounded memory growth on large servers
- No invalidation mechanism if admins modify a player's ender chest via commands

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `BetterShulkerMod.java` | 32, 148 | Field declaration and usage |

### How to Fix

**File:** `BetterShulkerMod.java`

Replace `WeakHashMap` with a `HashMap` with explicit cleanup:

```java
// Option A: Simple HashMap (preferred — explicit cleanup already exists in DISCONNECT)
private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = new HashMap<>();

// Option B: LRU-backed map with size cap (for large servers)
private static final int MAX_CACHED_PLAYERS = 200;
private static final Map<UUID, NonNullList<ItemStack>> lastSyncedEnderChest = 
    new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, NonNullList<ItemStack>> eldest) {
            return size() > MAX_CACHED_PLAYERS;
        }
    };
```

The DISCONNECT handler already removes entries explicitly, so `HashMap` is the correct and faster choice.

---

## 8. 🟡 MEDIUM: Dead Code — Unused Methods, Class, and Imports

### Description

Several methods, one class, and one import are completely unused, adding unnecessary clutter:

| Item | File | Lines | Notes |
|------|------|-------|-------|
| `bettershulker$findInventorySlot()` | `HandledScreenMixin.java` | ~415 | Defined but never called |
| `hslToRgb()` | `ShulkerTooltipComponent.java` | ~1022 | Defined but never called |
| `brightenColor()` | `ShulkerTooltipComponent.java` | ~1040 | Defined but never called |
| `intVal()` | `BetterShulkerConfig.java` | ~193 | Defined but never called |
| `class_437.java` | `net/minecraft/class_437.java` | entire file | Yarn-mapped Screen shim, never referenced |
| `import java.util.List` | `BetterShulkerClient.java` | ~24 | Unused (all references use fully-qualified `java.util.List`) |

### How to Fix

**Simple:** Delete each unused item.

```bash
# Remove dead class file
rm "src/main/java/net/minecraft/class_437.java"

# Remove unused methods
# Edit HandledScreenMixin.java: delete bettershulker$findInventorySlot
# Edit ShulkerTooltipComponent.java: delete hslToRgb and brightenColor
# Edit BetterShulkerConfig.java: delete intVal

# Remove unused import
# Edit BetterShulkerClient.java: remove "import java.util.List"
```

---

## 9. 🟢 LOW: Misleading Field Name `hoveredTooltipContainer`

### Description

The field and accessor `getHoveredTooltipContainer()` / `setHoveredTooltipContainer()` in `BetterShulkerClient.java` store **both** the hovered container AND the carried container (when the player is carrying a container and not hovering). Despite the name "hovered", the setter is called with `hoveredContainer = carried` when `carryingContainer` is true:

```java
// HandledScreenMixin.java ~868
if (hovering) {
    hoveredContainer = this.hoveredSlot.getItem();
} else if (carryingContainer) {
    hoveredContainer = carried;
}
```

This is confusing for any developer reading code that uses `getHoveredTooltipContainer()` — they can't tell whether it represents a hovered or carried container without tracing the setter call sites.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `BetterShulkerClient.java` | 100, 228–234 | Field + getter/setter |
| `HandledScreenMixin.java` | ~865–872 | Setter call sites |

### How to Fix

**File:** `BetterShulkerClient.java`

Rename the field and accessors:

```java
// Change from:
private static ItemStack hoveredTooltipContainer;
public static ItemStack getHoveredTooltipContainer() { return hoveredTooltipContainer; }
public static void setHoveredTooltipContainer(ItemStack container) { hoveredTooltipContainer = container; }

// Change to:
private static ItemStack activeContainerStack;
public static ItemStack getActiveContainerStack() { return activeContainerStack; }
public static void setActiveContainerStack(ItemStack stack) { activeContainerStack = stack; }
```

Then update all references in `HandledScreenMixin.java`, `BetterShulkerClient.java`, and any other files.

---

## 10. 🟢 LOW: Sound Playing Logic Duplicated Across 4 Files

### Description

The same ~20-line sound lookup-and-play pattern exists in 4 separate files:

| File | Method | Lines |
|------|--------|-------|
| `BetterShulkerMod.java` | Inline in `handleShulkerInteraction` / `handleEnderChestInteraction` | ~10 |
| `HandledScreenMixin.java` | `bettershulker$playClientSound()` | ~390–411 |
| `WirelessEnderChestScreen.java` | `bettershulker$playClientSound()` | ~351–375 |
| `ItemMixin.java` | `bettershulker$playLevelSound()` | ~244–266 |

Total: ~80 lines of copy-pasted sound logic.

### How to Fix

**File:** `ContainerHelper.java`

Add a reusable static method:

```java
/**
 * Plays an interaction sound for the given player.
 * Handles contextual sound selection and volume/pitch randomization.
 */
public static void playInteractionSound(Player player, ItemStack stack, boolean isInsert, float volume) {
    if (volume <= 0.0f) return;
    
    SoundEvent soundEvent;
    if (BetterShulkerConfig.soundOption == BetterShulkerConfig.SoundOption.CONTEXTUAL) {
        soundEvent = getContextualSound(stack, isInsert);
    } else {
        try {
            String[] split = BetterShulkerConfig.soundOption.getSoundId().split(":", 2);
            var soundLoc = Identifier.fromNamespaceAndPath(split[0], split[1]);
            var soundHolderOpt = BuiltInRegistries.SOUND_EVENT.get(soundLoc);
            if (soundHolderOpt.isEmpty()) return;
            soundEvent = soundHolderOpt.get().value();
        } catch (Exception e) {
            return;
        }
    }
    
    float pitch = isInsert
        ? 0.9F + player.level().getRandom().nextFloat() * 0.2F
        : 0.65F + player.level().getRandom().nextFloat() * 0.15F;
    
    player.playSound(soundEvent, volume, pitch);
}
```

Then replace all 4 inline copies with:
```java
ContainerHelper.playInteractionSound(player, stack, isInsert, BetterShulkerConfig.soundVolume);
// or for server-side: ContainerHelper.playInteractionSound(player, stack, isInsert, 0.3F);
```

---

## 11. 🟢 LOW: ItemMixin Bypasses ContainerInteractPayload Validation Layer

### Description

The vanilla-style bundle interactions (`overrideStackedOnOther`, `overrideOtherStackedOnMe`) in `ItemMixin.java` modify the ender chest inventory **directly** via `enderInv.removeItemNoUpdate()` and `enderInv.setItem()`, bypassing the `handleContainerInteraction` validation layer entirely. This means:

- Slot bounds checks are not performed
- Anti-nesting rules are not enforced (though this path handles shulker-in-shulker via `tryInsert`)
- Action-type validation is not applied
- Rate limiting is not applied

While these callbacks are part of Minecraft's standard input handling and use the same underlying `ContainerHelper` methods, having two separate code paths to mutate the same data creates a risk of divergent behavior.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `ItemMixin.java` | 32–54 | `overrideStackedOnOther` for ender chest |
| `ItemMixin.java` | 117–145 | `overrideStackedOnOther` ender chest insert |
| `ItemMixin.java` | 162–190 | `overrideOtherStackedOnMe` ender chest insert |

### How to Fix

**Option A (Refactor):** Route all bundle-style interactions through the `ContainerInteractPayload` system by having the mixin send a packet to the server rather than modifying directly. This guarantees a single validation path.

**Option B (Document + Guard):** Add the same validation checks that exist in `handleContainerInteraction` to the `ItemMixin` methods. At minimum, add logging to track when this path is used:

```java
// ItemMixin.java ~116 before ServerPlayer cast
if (!(player instanceof ServerPlayer serverPlayer)) {
    return; // Safety check — should never happen in server context
}
```

---

## 12. 🟢 LOW: Shulker Box Dye Copies All Components Without Sanity Check

### Description

When dyeing a shulker box (right-click with dye on a shulker), `ItemMixin.java` copies **all** data components from the old shulker to the new one via `applyComponents(stack.getComponents())`:

```java
// ItemMixin.java ~115
ItemStack newShulker = new ItemStack(ContainerHelper.getShulkerBoxByColor(dyeColor), stack.getCount());
newShulker.applyComponents(stack.getComponents());
slot.set(newShulker);
other.shrink(1);  // Dye consumed
```

This preserves ALL components including:
- `CONTAINER` (contents — correct, desired)
- `CUSTOM_NAME` (correct)
- `ENCHANTMENTS` (correct)
- **Any modded components** that may not be valid on a shulker box

If a modded component is incompatible with a shulker box, `slot.set(newShulker)` could throw, but `other.shrink(1)` has already been called — **the dye is consumed but no new shulker was placed**, resulting in item loss.

### Affected Files

| File | Lines | Role |
|------|-------|------|
| `ItemMixin.java` | 111–130 | Dye + insert logic in `overrideOtherStackedOnMe` |

### How to Fix

**File:** `ItemMixin.java`

Reorder the operations so the slot update happens BEFORE the dye is consumed:

```java
if (dyeColor != null) {
    DyeColor currentColor = ContainerHelper.getShulkerColor(stack);
    if (currentColor != dyeColor) {
        ItemStack newShulker = new ItemStack(ContainerHelper.getShulkerBoxByColor(dyeColor), stack.getCount());
        newShulker.applyComponents(stack.getComponents());
        
        // FIX: Update slot BEFORE consuming dye
        slot.set(newShulker);
        
        // Only consume dye if slot update succeeded
        other.shrink(1);
        
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(), 
            SoundEvents.DYE_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
        
        ci.setReturnValue(true);
        return;
    }
}
```

---

## Summary Table

| # | Severity | Issue | Effort to Fix | Impact if Unfixed |
|---|----------|-------|---------------|-------------------|
| 1 | 🔴 Critical | Crafting slot duplication | 15 min | Unlimited item duplication |
| 2 | 🔴 Critical | Generalized computed-slot dup | 15 min (same fix) | Unlimited item duplication |
| 3 | 🟠 High | No rate limiting | 30 min | Server CPU exhaustion / DoS |
| 4 | 🟠 High | Missing ender chest auth | 15 min | Unauthenticated ender chest access |
| 5 | 🟡 Medium | Prediction timeout 800ms | 5 min | Client desync under lag |
| 6 | 🟡 Medium | 700 lines duplicated logic | 2–3 hours | Desync bugs from drift |
| 7 | 🟡 Medium | WeakHashMap misuse | 10 min | Unbounded memory on large servers |
| 8 | 🟡 Medium | Dead code | 15 min | Code clutter, confusion |
| 9 | 🟢 Low | Misleading field name | 10 min | Confusion for maintainers |
| 10 | 🟢 Low | Sound logic duplicated 4× | 30 min | Maintenance overhead |
| 11 | 🟢 Low | ItemMixin bypasses validation | 1 hour | Potential behavioral divergence |
| 12 | 🟢 Low | Dye component copy order | 10 min | Rare item loss on dye failure |

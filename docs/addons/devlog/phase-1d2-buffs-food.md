# Phase 1d (part 2) — Buffs + FEP/food/hunger

> **Status:** ✅ Implemented; compile verified (`ant hafen-client` → BUILD SUCCESSFUL), headless
> change-detection test (11/11) + LuaJ parse of the harness. **In-game verification pending** (the
> maintainer verifies before the commit).
> **Design:** [specs/addons/14-widget-tree-reads.md](../../specs/addons/14-widget-tree-reads.md)
> (the mechanism — `BuffsAdapter`/`FepAdapter` rows), [specs/addons/api-reference.md](../../specs/addons/api-reference.md)
> (`hafen.buffs`, `hafen.char.food`, the `Buff` shape), [specs/addons/code-map.md](../../specs/addons/code-map.md)
> (`GameUI.buffs`/`Buff`, `BAttrWnd`).

The **second slice of Phase 1d**. It adds two more surfaces on the [1d-1 widget-tree mechanism](phase-1d1-vitals-widget-tree.md)
(Locator + Adapter + inbound-`uimsg` tap): **buffs/debuffs** (`hafen.buffs.*` + `BuffAdded`/
`BuffRemoved`/`BuffChanged`) and **FEP + hunger** (`hafen.char.food()` + `FepChanged`). Both read
state bound to `GameUI` widget trees, not `Glob` (audit B1).

## Two update paths — and why buffs need a per-tick poll

1d-1 established the **uimsg-driven** path: a targeted server `uimsg` (`IMeter "set"`) marks the
adapter dirty and the tick re-reads + fires the event. That covers **vitals**, and here it covers
**FEP/food** (`BAttrWnd "food"/"glut"` uimsgs) and **buff content changes** (`Buff "ch"/"tt"`).

But **buff add/remove is not a `uimsg`** — a buff appearing is a *widget create* under the
`Bufflist`, and a buff dropping is a `cdestroy`. The inbound-`uimsg` tap never sees those. So 1d-2
adds a second path to the mechanism: a per-tick **`poll()`** (default no-op) on `TreeAdapter`. The
`BuffsAdapter` overrides it to diff `GameUI.buffs.children(Buff.class)` each tick against a cache
keyed by widget identity — new widget → `BuffAdded`, gone → `BuffRemoved`. Content changes still ride
the `uimsg` tap (`refresh()` → `BuffChanged`). `refresh` runs **before** `poll` each tick, so a
brand-new buff surfaces as a single `BuffAdded` (with its `"tt"` content already applied), not
`BuffChanged`-then-`BuffAdded`.

```
buff appears (widget create)  ─▶ [no uimsg]           ─▶ poll() diff ─▶ "BuffAdded"      [UI thread, per tick]
buff "ch"/"tt" (content)      ─▶ onUimsg → mark dirty  ─▶ refresh()   ─▶ "BuffChanged"    [UI thread, on tick]
buff drops (cdestroy / dest)  ─▶ [no uimsg]            ─▶ poll() diff ─▶ "BuffRemoved"    [UI thread, per tick]
```

## Core edits — almost none (one 3-line accessor)

FEP/food is a **zero-`haven`-edit** read: `BAttrWnd` is located directly via the **public**
`CharWnd.battr` field (no tree-walk), and its `feps` (`FoodMeter`) and `glut` (`GlutMeter`) — plus
`FoodMeter.cap`/`els`, `El.res`/`a`/`ev()`, `GlutMeter.glut`/`lbl`/`gmod` — are **all public**. No
`AddonWidgets` accessor needed.

Buffs add **one 3-line method** to the existing `haven`-package accessor `AddonWidgets` (created in
1d-1):

- **`AddonWidgets.buffDest(Buff)`** — reads the `protected Buff.dest` flag (set when the server
  removes a buff and it starts its 0.35 s fade-out). The bridge treats a `dest` buff as already gone,
  so `hafen.buffs.list()` omits it and `BuffRemoved` fires at removal time, not when the fade
  finishes. `dest` is `protected`, so it is package-visible from `haven` — no reflection (D-017).

Everything else is new code in `io.brodgar.addon` (the `poll()` addition, the two adapters, the Lua
facades). The 1d-1 inbound-`uimsg` tap is reused as-is (now feeding three adapters).

## `hafen.buffs.*` — active buffs/debuffs

```lua
for _, b in ipairs(hafen.buffs.list()) do   -- array of Buff snapshots
  -- b = { res, name, amount, cooldown, number }
end
hafen.buffs.has("Drunk")   -- bool: substring match on name OR res, over active buffs
```

`Buff` snapshot:

| field | meaning |
|---|---|
| `res` | resource name — stable identity (Loading-guarded) |
| `name` | display name: the resource tooltip, else a server-pushed Name info (Loading-guarded) |
| `amount` | `0..1` fraction from resource-published `ItemInfo` (`Buff.AMeterInfo`), content-dependent, often nil |
| `cooldown` | `0..1` fraction (`GItem.MeterInfo`), content-dependent, often nil — **NOT seconds** (no buff has a seconds timer; coverage-gaps) |
| `number` | integer overlay (`GItem.NumberInfo`), content-dependent, often nil |

- `amount`/`cooldown`/`number` are all derived from `Buff.info()` (which needs the resource loaded),
  so they are **often absent** and may arrive a moment after `res`/`name`.
- Buffs fading out after removal are **excluded** (`buffDest`).

### `BuffAdded` / `BuffRemoved` / `BuffChanged`

```lua
hafen.events.on("BuffAdded",   function(b) end)   -- b = Buff snapshot
hafen.events.on("BuffRemoved", function(b) end)   -- b = the snapshot as last seen
hafen.events.on("BuffChanged", function(b) end)   -- b = the new snapshot
```

- **`BuffAdded`/`BuffRemoved`** are detected by the per-tick `poll` diff. Buffs the character already
  has re-appear as `BuffAdded` shortly after enter-world (the bar streams in).
- **`BuffChanged`** fires on the buff's `"ch"`/`"tt"` `uimsg` when a snapshot field actually changes
  (a no-op re-read is suppressed). Because buff meters are static server fractions (not per-frame
  countdowns), this does **not** fire every frame.

## `hafen.char.food()` — FEP + hunger

```lua
local f = hafen.char.food()   -- table | nil (nil until the base-attributes tab exists)
-- f = {
--   fep = {
--     cap,                                  -- FEP capacity
--     total,                                -- current total (sum of entries)
--     entries = { { res, name, amount }, … } -- per food-event type
--   },
--   hunger = {
--     level,     -- glut meter value (fractional part = progress within the current hunger level)
--     label,     -- hunger-level name (e.g. "Hungry", "Full and Fed")
--     efficacy,  -- food efficacy 0..1 (how much food still counts at the current satiation)
--   },
-- }
```

This is the one place **absolute FEP/hunger numbers exist** — vitals (1d-1) are bar fractions with no
absolute values or hunger; FEP/hunger live in a *different* widget (`BAttrWnd`, the character sheet's
"Base Attributes" tab), which is created hidden at login and updated live regardless of visibility.

### `FepChanged`

```lua
hafen.events.on("FepChanged", function(f) end)   -- f = same food snapshot
```

Fires whenever the server sends a `BAttrWnd "food"` (FEP) or `"glut"` (hunger) update — i.e. as the
data streams in shortly after enter-world (a few fires at login) and whenever you eat. Each uimsg is a
genuine change, so it fires on each (no change-detection filter).

## Streaming — read on a timer, not in `OnEnterWorld`

Like the 1c map/char/item data and 1d-1 vitals, **the buff bar and the character sheet stream in a
beat after `OnEnterWorld`**: `hafen.buffs.list()` is empty and `hafen.char.food()` is `nil` on the
immediate read, then populate ~seconds later. Read them off a timer/tick or off the events, **not**
synchronously in the `OnEnterWorld` handler.

## Try it from the `:lua` console

```
:lua hafen.buffs.list()
:lua hafen.buffs.has("Well")
:lua hafen.char.food()
```

Before the widgets exist these are `[]` / `false` / `null`, never an error.

## The `hello` example (`addons/hello/main.lua`)

New `readBuffs(tag)` and `readFood(tag)` helpers log `hafen.buffs.list()` and `hafen.char.food()` in
both the `[now]` pass (usually empty/`nil` — streaming) and the `[+3s]` pass (populated). Throttled
`BuffAdded`/`BuffRemoved`/`BuffChanged` and `FepChanged` subscriptions log the first few of each. It
stays our standing regression harness — one login re-checks Phase 0 / 1a / 1b / 1c / 1d-1 **and** this
slice. Bumped to **v0.7.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```
ant run
```

After `[hello] entered the world`, expect (a beat after enter-world, at `[+3s]` and via events):

- `[hello] [+3s] buffs=<n>, first=<name>` — buffs the character carries (may be `buffs=0`),
- `[hello] BuffAdded: <name> (<res>)` — as each existing buff's widget streams in,
- `[hello] [+3s] food: fep=<total>/<cap> (<k> type(s)) hunger=<label> efficacy=<e>`,
- `[hello] FepChanged: fep total=<…> hunger=<…> (n)` — as the FEP/hunger data streams in.

Then poke it live: `:lua hafen.buffs.list()`, `:lua hafen.char.food()`. To see `BuffAdded`/
`BuffRemoved` and `FepChanged` fire on demand: gain/lose a buff (e.g. get/finish "Drunk" or a wound)
and eat something.

## Files

- `src/haven/AddonWidgets.java` — **core edit**: `+ buffDest(Buff)` (reads the `protected` fade flag);
  the single reflection-free accessor surface, now used by vitals **and** buffs.
- `src/io/brodgar/addon/AddonManager.java` — `TreeAdapter.poll()` (new default method) +
  `pollTreeAdapters()` (tick step); `BuffsAdapter` (poll for add/remove, uimsg for change) +
  `FepAdapter` (uimsg-driven); `hafen.buffs.list/has`, `hafen.char.food`; helpers `bufflist`,
  `buffRes`, `buffName`, `buffSnapshot`, `buffEqual`/`luaFieldEq`, `battrwnd`, `readFood`; both
  adapters registered in `init()`.
- `addons/hello/` — example + manifest bumped to **v0.7.0**.

The 1d-1 `UI.java` inbound-`uimsg` tap is reused unchanged.

## Threading & safety

- `poll()` and `refresh()` run on the **UI thread** (the tick). `interested()` runs on a **Loader
  thread** (the `uimsg` tap) but only does an `instanceof`/`equals` check — no cache access, no Lua.
  The `BuffsAdapter` cache is UI-thread-only.
- `bl.children(Buff.class)` and the `FoodMeter.els` copy run under `synchronized(ui)` (the tick and
  widget mutations are both serialized by the UI monitor), so no concurrent tree/list mutation.
- Every read (`buffSnapshot`, `readFood`, per-entry `el.ev()`) swallows `Loading`/null → a partial or
  nil snapshot, never an error into Lua.
- Adapters are **session-scoped** (rebuilt in `init()`), so a relog re-resolves cleanly and the
  `BuffsAdapter` cache resets by re-instantiation. No cross-session leak.

## Limitations / deferred

- **Buff `cooldown` is a 0..1 fraction, never seconds** — no buff carries a seconds timer (only
  `hafen.combat` cooldowns are seconds, via `rtime`). `amount`/`cooldown`/`number` are content-defined
  and frequently nil.
- **`name` is best-effort** — the resource tooltip text, else a server-pushed `ItemInfo.Name`; some
  buffs may expose neither until loaded (then `res` is the identity).
- **FEP per-entry `res`/`name`** are Loading-guarded (skipped while the food-event resource resolves);
  `amount`/`total`/`cap` are always present once `battr` exists.
- **`FepChanged` fires on each `"food"`/`"glut"` uimsg** without deep change-detection (these are
  genuine server updates, and they are infrequent) — an addon that wants to debounce can.
- Still to come in 1d: **study/curiosity + skills** (1d-3), **belt read + equip-change event**
  (1d-4). No sandbox/watchdog yet (Phase 1f).

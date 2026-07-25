# Phase 1d (part 4) — Action bar (read) + equip-change event

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), headless
> change-detection test (19/19) + LuaJ parse of the harness. **In-game verification pending.**
> **Design:** [specs/addons/14-widget-tree-reads.md](../../specs/addons/14-widget-tree-reads.md)
> (the mechanism — `ActionbarAdapter`/`EquipAdapter` rows), [specs/addons/api-reference.md](../../specs/addons/api-reference.md)
> (`hafen.actionbar`), [specs/addons/code-map.md](../../specs/addons/code-map.md) (`GameUI.belt`, `Equipory`).

The **fourth and final read slice of Phase 1d**. It adds two surfaces on the
[1d-1 widget-tree mechanism](phase-1d1-vitals-widget-tree.md): the **action bar / hotbar** via
`hafen.actionbar.slot(n)` + the `ActionbarChanged` event, and an **`EquipChanged`** event over the
existing [1c-3 equipment read](phase-1c3-items-char-party.md). Both read state bound to `GameUI` widget
trees, not `Glob` (audit B1). (Action-bar *use* — actually triggering a slot — is the gated action
tier, Phase 4.)

## Naming — API `actionbar`, engine `belt`

Haven & Hearth's own name for the action bar is the **"belt"** — the client code calls it `GameUI.belt`
(a `BeltSlot[144]`), the server messages are `setbelt`/`setbelt2`, and the two hotbar widgets are
`FKeyBelt`/`NKeyBelt`. That is confusing (you also *wear* a belt, which is unrelated equipment), so the
**addon-facing API deliberately exposes it as `hafen.actionbar`** (clearer, WoW-like). Inside the bridge
the adapter/helpers are named `actionbar*` but read the engine's `belt[]` array — the two names denote
the same thing. When you see `belt` in `haven`, read "action bar".

## Zero core edits — every read is public

Like 1d-3, **1d-4 touches no `haven` file** — everything it reads is already public:

- **Action bar:** `GameUI.belt` (public `BeltSlot[144]`). Each occupied slot is a `ResBeltSlot` (public
  `getres()`, `rdt`) or a `PagBeltSlot` (public `pag`); the pagina exposes `res()`, `button()`, and the
  button's `name()` + `meter` (all public). No `protected`/`private` field, so **no `AddonWidgets`
  accessor and no `UI.java` tap** — only `AddonManager.java` changed.
- **Equipment:** unchanged from 1c-3 — walk `Equipory.children(WItem.class)` (public), read `WItem.item`.

## Slot set/clear is deferred → poll-driven (like buffs/study)

Setting, clearing or dragging a slot IS a `setbelt`/`setbelt2` `uimsg` to `GameUI` — but for the common
resource/pagina cases the client mutates the `belt[]` array inside a **`loader.defer(...)` task** that
runs *after* the message is dispatched (`GameUI.uimsg`, the `setbelt`/`setbelt2` cases). So a synchronous
refresh on the inbound-`uimsg` tap (the 1d-1 path) would **race the deferred write** and read the old
slot. Hence `ActionbarAdapter` uses the **`poll()`** path (added in 1d-2): each tick it diffs the
occupied slots against a per-index cache and fires `ActionbarChanged{n}` whenever a slot's content
changes — regardless of which thread applied it.

```
slot set / cleared / dragged  ─▶ [uimsg, but belt[] set on a deferred loader task]
                              ─▶ poll() diff ─▶ "ActionbarChanged" {n}      [UI thread, per tick]
```

`interested()` returns `false` (the uimsg tap can't be trusted for the deferred cases); `refresh()` is a
no-op; all the work is in `poll()`. Same shape as `StudyAdapter`.

## `hafen.actionbar.*` — the hotbar

```lua
local s = hafen.actionbar.slot(0)     -- slot 0 (F1 on page 0); {res, name, cooldown} or nil if empty
```

Action-bar slot snapshot:

| field | meaning |
|---|---|
| `res` | icon resource name — stable identity (a `ResBeltSlot` item or a `PagBeltSlot` action; Loading-guarded) |
| `name` | display name — the pagina action's name, else the resource tooltip (Loading-guarded) |
| `cooldown` | `0..1` meter fraction, present **only** on a pagina action carrying a meter (e.g. an ability recharging) — **not seconds** |

- **Indexing is the RAW 0-based game index (0..143)**, *not* a 1-based Lua position. Slot 0 is the first
  hotbar cell (F1 / `1` on page 0); the client packs the pages as `page*12 + i` (12 slots × 12 pages), so
  e.g. slot `120` is page 10's first cell. This is the same index the server uses and that **action-bar
  *use* will take in Phase 4** — the slot index is the game's index everywhere (one canonical way,
  [D-013](../../specs/addons/decisions.md)).
- An **empty** slot → `nil`. An occupied slot whose data is still resolving → a partial snapshot
  (`res` only), filling in on a later tick.
- `cooldown` is a live meter; it is **excluded from `ActionbarChanged` change-detection** (see below) so a
  cooling-down ability does not fire an event every frame. Read it live via `hafen.actionbar.slot(n)`.

### `ActionbarChanged`

```lua
hafen.events.on("ActionbarChanged", function(n)   -- n = the slot index that changed (0-based)
  local s = hafen.actionbar.slot(n)               -- read it back for the new content (or nil if cleared)
end)
```

Fires when action-bar slot `n` changes — a set / clear / drag, or the slot's data resolving as the hotbar
streams in after enter-world (a few fires at login). Change-detected on `res` + `name` only (not
`cooldown`), so a no-op re-read or a ticking cooldown is suppressed.

## `EquipChanged` — worn equipment

```lua
hafen.events.on("EquipChanged", function(eq)    -- eq = same array as hafen.items.equipment()
end)
```

Equipping / removing an item is a **widget create/`cdestroy`** under the `Equipory` (not a targeted
`uimsg`), so — like buffs/study — `EquipAdapter` is **poll-driven**: each tick it re-reads the equipment
snapshot and fires `EquipChanged` (with the full array) when the set changes. Change-detected on `slot` +
`res` + `name` + `num` (positional) — **not `wear`**, since a slowly drifting durability is not an equip
change (read live wear via `hafen.items.equipment()`).

`hafen.items.equipment()` is unchanged (same `{res,name,num,wear,pos,slot}` snapshots); its read body was
extracted into a shared `readEquipment()` helper that the facade and the adapter now both use.

## Streaming — read on a timer, not in `OnEnterWorld`

Like everything HUD-bound (vitals/buffs/food/study, char/items), **the action bar and equipment stream in
a beat after `OnEnterWorld`**: `hafen.actionbar.slot(n)` is `nil` and `hafen.items.equipment()` is empty
on the immediate read, then populate ~seconds later (each surfacing as `ActionbarChanged`/`EquipChanged`).
Read them off a timer/tick or off those events, **not** synchronously in the `OnEnterWorld` handler.

## Try it from the `:lua` console

```
:lua hafen.actionbar.slot(0)
:lua hafen.actionbar.slot(1)
```

Before the hotbar exists (or for an empty slot) this is `null`, never an error.

## The `hello` example (`addons/hello/main.lua`)

A new `readActionbar(tag)` helper scans slots `0..143`, logging the occupied count + the first occupied
slot (index, name/res, cooldown) in both the `[now]` pass (usually empty — streaming) and `[+3s]`
(populated). Throttled `ActionbarChanged` (reads the slot back) and `EquipChanged` (count + first item)
subscriptions log the first few. It stays our standing regression harness — one login re-checks Phase 0 /
1a / 1b / 1c / 1d-1 / 1d-2 / 1d-3 **and** this slice. Bumped to **v0.9.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```
ant run
```

After `[hello] entered the world`, expect (a beat after enter-world, at `[+3s]` and via the events):

- `[hello] [+3s] actionbar=<n> slot(s), first[<idx>]=<name>` — `actionbar=0` if the hotbar is empty,
- `[hello] ActionbarChanged: slot <n> -> <name>` — as the hotbar streams in, and whenever you drag an
  item/action onto a slot, right-click to clear one, or drag one off,
- `[hello] EquipChanged: <n> slot(s), first=<item>` — as equipment streams in, and whenever you equip or
  remove a worn item.

Then poke it live: `:lua hafen.actionbar.slot(0)` (and other indices). To see the events fire on demand:
drag an action/item onto a hotbar slot (`ActionbarChanged`), or equip/unequip a piece of gear
(`EquipChanged`).

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: `ActionbarAdapter` +
  `EquipAdapter` (both poll-driven, instance caches) registered in `init()`; the `hafen.actionbar.slot`
  facade; helpers `actionbarSlot`, `actionbarSnapshot`, `actionbarResObj`, `actionbarName`,
  `actionbarCooldown`, `actionbarEqual`, `readEquipment`, `equipEqual`; `hafen.items.equipment`
  refactored onto `readEquipment`; imports `java.util.HashMap`.
- `addons/hello/` — example + manifest bumped to **v0.9.0**.

**No `haven` core edit** — the 1d-1 `UI.java` tap and `AddonWidgets` accessor are untouched (as in 1d-3).

## Threading & safety

- `poll()` runs on the **UI thread** (the tick), under `synchronized(ui)`, so `g.belt[]` and
  `Equipory.children(...)` see no torn read. The belt array elements are reference writes (atomic); the
  deferred loader task that populates a slot is caught by the *next* poll whenever it lands.
- Every read (`actionbarSnapshot` and its helpers, `readEquipment`) swallows `Loading`/null → a partial or
  empty result, never an error into Lua. `PagButton.meter` is an `AttrCache` that already returns `null`
  on `Loading`; `pag.button()`/`pag.res()`/`getres()` are additionally try/caught.
- The `ActionbarAdapter`/`EquipAdapter` caches live in the adapter instances (reset by re-instantiation in
  `init()`), so a relog re-resolves cleanly. No cross-session leak.

## Limitations / deferred

- **Action-bar *use* is Phase 4 (gated actions)** — this slice is read + change events only.
  `hafen.actionbar.use(n)` → `wdgmsg("belt", n, …)` lands behind the write-actions permission (Phase 4).
- **`cooldown` is a `0..1` meter, not seconds** — same honesty rule as the non-existent buff-seconds
  timers and study countdown: the client has no seconds value, so the API does not invent one. `cooldown`
  is present only for pagina slots the server tagged with a meter.
- **No per-slot `EquipChanged` granularity** — the event carries the whole equipment array (the useful
  state), not a per-slot delta; diff it yourself if you need the specific change.
- **Change-detection is positional** for equipment (like study) — adequate because the `Equipory` child
  order is creation-order and stable between equips.
- This **completes Phase 1d** (the widget-tree read surfaces). Next: **1e — saved variables**
  (`hafen.store`). No sandbox/watchdog yet (Phase 1f).

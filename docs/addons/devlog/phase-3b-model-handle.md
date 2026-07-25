# Phase 3b — The `model` handle (`hafen.ui.adopt`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 27/27 headless logic checks
> (handle surface on a bare `Widget`: `:raw`/`:visible`/`:hide`/`:show`/`:items`/`:onItemAdded`/`:onItemRemoved`/
> `:onDestroy`, incl. chaining, `fnOrNull`, and dead-model no-ops; `newModel` arg validation; `pollModels` ui-null
> safety; `teardownModels` drop + ui-null safety) + LuaJ parse of the harness under the sandbox. **In-game verified ✅**
> (adopt at login; Ctrl+B hid/showed the grid; a ground pick-up **while the grid was hidden** fired `onItemAdded` —
> the D-009 headless-model claim confirmed live).
> **Design:** [specs/addons/08-widget-replacement.md](../../specs/addons/08-widget-replacement.md) (the "wrap, don't
> reimplement" golden rule + the proposed `model` methods), decisions **D-009** (wrap, don't reimplement),
> **D-024** (targeting descriptor), **D-010/D-025** (actions are gated, Phase 4), **D-011** (invasiveness allowed).

The **second slice of Phase 3 (Widget replacement)**. 3a let an addon *observe* every server widget as the client
builds it (`hafen.ui.onWidgetCreate` → a `{id,type,place,caption,parentType}` descriptor). 3b lets it **adopt**
one of those widgets **by its `desc.id`** and hold it as a **model** — the real, server-bound widget kept alive
but hidden, so the addon can present its own view over it. This is the heart of "**wrap, don't reimplement**"
(D-009): the model keeps receiving the server protocol; the addon never re-implements it.

- **3a** — *interception + descriptor + observe.* See every server widget the client builds. Observe-only.
- **3b (this)** — *the `model` handle.* Adopt a widget by id; hide/show it; enumerate its items; lifecycle events.
- **3c** — *`hafen.ui.replace` + the bags example + reload un-hide* — the DoD: replace the native inventory with a
  custom view; disabling restores the stock window.

## The API — `hafen.ui.adopt`

```lua
-- Adopt a live SERVER widget by its id (the desc.id an onWidgetCreate observer hands out) as a MODEL.
-- Returns a model handle, or nil if no widget has that id (e.g. it was already destroyed).
local model = hafen.ui.adopt(desc.id)

model:hide()          -- hide the widget (chainable). It STAYS bound to its id -> still a live headless model.
model:show()          -- show it again (chainable).
model:visible()       -- is it currently visible?  (boolean)
model:raw()           -- the server widget id (a WidgetRef) — the facade-safe escape hatch.
model:items()         -- array of Item snapshots {res,name,num,wear,pos} off the widget's WItem children
                      --   (empty for a non-inventory widget). Same shape as hafen.items.inventory().
model:onItemAdded(fn)    -- fn(item) when an item enters   (item = an Item snapshot)
model:onItemRemoved(fn)  -- fn(item) when an item leaves
model:onDestroy(fn)      -- fn() once when the SERVER destroys the widget (the view must die with it)
```

The **golden rule** (verified, spec 08): a hidden server widget **stays bound to its id**, so it keeps receiving
`uimsg`/`addchild` (item adds, count/tooltip updates) and still routes its own `wdgmsg` — **delivery is by id,
not by visibility**. So `:hide()` turns the widget into a perfect *headless model*: invisible + non-interactive,
but fully live. `:items()` and the add/remove events keep working while it is hidden.

Adopt from an **`onWidgetCreate` observer** (which fires as the widget is built — the natural 3a→3b handoff).
Re-finding an **already-open** window by type/descriptor (so it works after `:reload` too) is 3c's
`hafen.ui.replace`; see *Known gaps*.

### ⚠ Item *mutating* actions are deferred to Phase 4 (gated) — a scope refinement

Spec 08's proposed `model` sketch lists delegated item verbs (`take`/`drop`/`transfer`/`use` → `GItem.wdgmsg`).
Those are **outbound gameplay actions**, and the closed design **gates all gameplay actions** behind the Phase-4
`hafen.act.*` tier (**D-010/D-025**; the security spec: *"the engine can ship with the action API absent or
disabled by default, so the 'modding' build never exposes automation"*; and the PLAN's Phase-4 queue explicitly
lists **"item verbs"** among the gated verbs). So 3b intentionally ships the model's **read + lifecycle** surface
only — `:items()` returns **read-only snapshots**, not action-capable item handles. The 3b **DoD requires exactly
this** ("adopt the inventory, hide it, enumerate its items from Lua; un-hide on teardown"). When the Phase-4
actions tier lands, item verbs attach there (one canonical, gated place), not on the model.

## How it works

`hafen.ui.adopt(id)` looks the widget up by its server id via **`UI.getwidget(id)`** (the same id map `uimsg`/
`wdgmsg` route by) and wraps it in a [`LuaModel`](../../src/io/brodgar/addon/LuaModel.java). The model lives in a
**flat global list** (polled each tick) plus the addon's owned-resource registry ([`Addon.models`](../../src/io/brodgar/addon/Addon.java)).

Two per-tick jobs, both in `AddonManager.pollModels()` (UI thread, mirroring the buff/study adapters — an item
add/remove or a widget destroy is **not** a targeted `uimsg`, so it can only be seen by polling):

1. **Server destroy** — if the widget's id no longer maps to it (`UI.getwidget(id) != wdg`), the server destroyed
   it (or reused the id): fire `onDestroy` once, mark the model dead, and drop it from both lists.
2. **Item add/remove** — when a listener is registered, diff the widget's `WItem` children against an
   identity-keyed cache (`IdentityHashMap<WItem,LuaValue>`), firing `onItemAdded`/`onItemRemoved` with the item
   snapshot. `:items()` itself always reads fresh (independent of the cache).

`:hide()`/`:show()` are `Widget.hide()`/`show()`; `:visible()` is `Widget.visible()`; `:raw()` returns the id.
Every Lua call routes through **`AddonManager.callLua`** (watchdog-armed D-018, error-isolated, CPU-accounted).

### Zero `haven` edit

3b is **pure engine** — every backing is already public: `UI.getwidget(int)`, `Widget.hide()`/`show()`/
`visible()`, `Widget.children(Class)` (used since 1c-3), and the 1c-3 item-snapshot helpers (`itemSnapshot`/
`cellPos`). So it needs none of the `UI.java` seams the hook levels added; only `AddonManager.java` + the new
`LuaModel.java` + one owned-list field on `Addon.java`. (3a's two `UI.java` observe seams remain the only Phase-3
core edits.)

### Threading, ownership, teardown

- **Threading.** Every operation — adopt, the handle methods, the per-tick poll, and the teardown un-hide — runs
  on the UI thread while holding the `ui` monitor (the tick/draw/input/hotkey paths all do; an `onWidgetCreate`
  observer runs under `AddWidget.run`'s `synchronized(ui)`). So the widget-tree reads and the `callLua` dispatches
  never race other Lua or the engine's own tree mutations. No new locks, no `holdsLock` gate.
- **Ownership (P2).** The model is bridge-owned: it is dropped when the server destroys the widget (poll → drop)
  or when the addon is reloaded/disabled. Re-adopting a widget makes an independent model.
- **Teardown un-hide (the DoD).** `teardownModels(a)` marks each model dead, drops it, and — critically —
  **un-hides** any widget the addon had `:hide()`d (`hidden` flag), so disabling a UI-replacement addon **restores
  the stock window**. It only un-hides a widget that is *still the live server-bound one* (skips a stale/destroyed
  id), under `synchronized(ui)` like `destroyWidgets`. This correctly fires on `:reload`/disable/auto-disable
  (same session, widget alive) and correctly **skips a relog** (the widget is already gone — `getwidget` on the
  new session won't match).
- **Session-scoped.** Widget ids are per-session, so `init` clears the global `models` list (and the REPL owner's)
  on session rebind, after the per-addon teardown loop has dropped the addons' own.

## Files

**Engine (`io.brodgar.addon`, all package-scoped — zero `haven` edit):**
- **`LuaModel`** *(new)* — the adopted-widget model: `owner`/`id`/`wdg`/`alive`/`hidden`, the three lifecycle
  callbacks, and the identity-keyed item cache.
- **`Addon`** — one owned-resource list: `models` (like `hooks`/`widgetObservers`, P2).
- **`AddonManager`** — the `hafen.ui.adopt` facade + `newModel`/`modelHandle`/`fnOrNull` + `pollModels`/
  `pollModelItems` (wired into `tick` after the tree-adapter poll) + `teardownModels` (wired into `teardown`,
  after `destroyWidgets`); the global `models` field (cleared per session in `init`).

The engine package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut,
LuaGobOverlay, LuaInputHook, LuaActionHook, LuaMarshal, LuaMessageHook, LuaKeyBind, LuaWidgetObserver, LuaModel}` +
`io.brodgar.addon.ui.AddonPanel`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (27/27):** with a bare `haven.Widget` as the model target — `:raw()` returns the id; `:visible()`
  tracks the widget; `:hide()`/`:show()` flip visibility + the `hidden` flag and return the handle (chaining);
  `:items()` is empty for a childless widget; `:onItemAdded/:onItemRemoved/:onDestroy` store the fn (and clear it
  on a non-function via `fnOrNull`); a **dead** model no-ops every method safely. `newModel` rejects a non-number
  id (LuaError) and returns `nil` when no UI is up. `pollModels()` with no UI does not mass-fire `onDestroy` and
  keeps models alive. `teardownModels` drops the addon's models from both lists, marks them dead, and is safe with
  no UI (skips the un-hide) and with an empty list. The **`UI.getwidget`-backed** paths (adopt of a live widget,
  the destroy diff, the actual un-hide of a live hidden widget, the `WItem` add/remove diff) need a GL-backed UI +
  server items, so — like 3a's `Window`-caption branch — they are covered by the **in-game DoD** below.
- **LuaJ parse** of the extended `hello/main.lua` under `Sandbox.create()`.
- **In-game — verified ✅** (adopt at login; Ctrl+B hid/showed the grid; a ground pick-up while the grid was hidden
  fired `onItemAdded` — the headless-model claim). The DoD steps, with `hello` (**v0.21.0**) enabled:
  1. At login a `3b: adopted main inventory (id=…)` line appears (the observer saw `{type="inv", place="inv",
     parentType="GameUI"}` and adopted it), the backpack's existing items fire `onItemAdded` as they stream in
     (the first few are logged; the rest of that initial fill is quiet — like `BuffAdded` for pre-existing buffs),
     and the `+3s` readback logs `bags: N item(s) via model, first=…, grid-visible=true` then `3b: bags ready …`.
  2. **After the `bags ready` line, move an item into/out of your inventory** → `3b: item ADDED/REMOVED …` fires
     (the per-tick model diff).
  3. Open the inventory (Tab), press **Ctrl+B** → the item **grid hides**; the log shows `grid hidden (N items
     still live via the model)`. Move an item in **while hidden** → `3b: item ADDED … [grid hidden -- model still
     live]` still fires (the D-009 headless model: hiding only sets `visible=false`, the WItem is still a child).
     Press Ctrl+B again → the grid **shows**. (A **"Hello"** keybind section now lists `toggle`=Ctrl+H,
     `ping`=None, **`bags`=Ctrl+B**.)
  4. With the grid hidden, `:reload` (or disable `hello`) → the inventory grid **reappears** (teardown un-hid it).
     After a `:reload` the model is not re-adopted (see below) until a relog; Ctrl+B then logs "not adopted yet".

## Known gaps / deferred

- **Item mutating actions are Phase 4 (gated).** `:items()` is read-only snapshots; `take`/`drop`/`transfer`/
  `use` attach to the gated `hafen.act.*` tier (D-010/D-025), not the model. See the ⚠ note above.
- **Re-adoption after `:reload`.** `:reload` rebuilds only the Lua layer; it does **not** recreate existing server
  widgets, so a freshly-registered `onWidgetCreate` observer never fires for the already-open inventory → the
  harness re-adopts only on a **relog**. Re-finding an already-open window by descriptor is exactly **3c's
  `hafen.ui.replace`** (find target → adopt → hide → view); 3b's primitive is deliberately *adopt-by-observed-id*.
- **Hiding the model ≠ hiding its wrapper.** The main inventory is an `Inventory` the engine wraps in a titled
  `Hidewnd` (`GameUI.addchild "inv"`); `desc.id` is the `Inventory` itself, so `:hide()` hides the **item grid**,
  not the wrapper window frame. That is the correct model-level primitive; presenting a full replacement window
  (hiding/annotating the wrapper) is 3c's job.
- **`:items()` is inventory-oriented.** It reads `WItem` children with the inventory-cell `pos`; a non-inventory
  widget yields an empty list, and an `Equipory`'s `pos` would be grid-ish (name/res/num/wear stay correct).
- **No explicit `:remove()`.** The model's lifetime is the widget's lifetime (or addon teardown) — bridge-owned,
  matching the spec's method list. Re-adopting the same id makes an independent model.

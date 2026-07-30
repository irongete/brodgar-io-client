# Lua API (the `hafen.*` SDK) — overview

> **Status:** 🟠 Outline — this is the conceptual overview; the authoritative function list is [the API reference](../../../docs/addons/api/README.md)
> **Related:** [the API reference](../../../docs/addons/api/README.md), [07-ui-and-drawing.md](07-ui-and-drawing.md), [08-widget-replacement.md](08-widget-replacement.md), [09-events-catalog.md](09-events-catalog.md)

> **➡ The detailed, categorized function contract (every executable `hafen.*` function with its
> Java backing) lives in [the API reference](../../../docs/addons/api/README.md).** This document is the conceptual
> overview: what each namespace is and which `haven.*` mechanism it maps to.

This is the **stable facade** addons target ([P1](01-architecture.md)). It maps to `haven.*`
through the bridge, which handles threading, coercion, and error isolation. Signatures below are
a **proposal** to refine during review; each namespace links to the underlying mechanism.

Conventions:
- All reads return plain Lua tables/values (snapshots), not Java handles, except explicit
  **handles** (windows, item models, widget models) which are bridge-owned proxies with methods.
- Coordinates are Lua numbers `x, y` in **world units** unless stated; helpers convert to/from
  tile and screen space.
- Everything runs on the UI thread ([P5](01-architecture.md)); addons never block.

---

## `hafen.world` — objects & terrain
Backed by [`Glob.oc`](src/haven/OCache.java) / [`Glob.map`](src/haven/MCache.java).

```lua
-- NOTE: per-gob reads now live under hafen.gob.*(ref); see the API reference (docs/addons/api/) (D-012/D-013).
hafen.world.gobs(filter)            -- array of gob snapshots (bulk scan)
hafen.gob.info(ref)                 -- one gob snapshot by ref (id or "player"/"target"/"partyN")
hafen.world.nearest(filter)         -- closest gob matching filter (fn or name-substring)
hafen.map.tile(x, y)                -- { id=, name= } tileset at world x,y (nil if not loaded)
```
Gob snapshot shape:
```lua
{ id=, name=,           -- name = Drawable.getres().name, e.g. "gfx/kritter/rabbit/rabbit"
  x=, y=, angle=,       -- rc (Coord2d) + facing
  moving=, speed=,      -- from Moving attr
  hp=,                  -- 0..1 from GobHealth, or nil
  overlays=, }          -- resource names of active overlays (TBD)
```
The bridge iterates under `synchronized(oc)` (copy first), reads each gob under
`synchronized(gob)`, and swallows `Loading`. Prefer **events** (`GobAdded`/`GobRemoved`,
[09](09-events-catalog.md)) over per-frame scanning.

## `hafen.player` — the player
Backed by [`MapView.plgob`/`player()`](src/haven/MapView.java).

```lua
-- player's gob attributes go through the "player" token (D-012): hafen.gob.*("player")
hafen.gob.info("player")            -- the player's gob snapshot, or nil (no character)
hafen.gob.pos("player")             -- { x=, y= } world position, or nil
hafen.gob.facing("player")          -- angle
hafen.player.worldToScreen(x, y)    -- screen coord for overlays, or nil if off-view (player-only helper)
hafen.player.screenToWorld(sx, sy)  -- world coord under a screen point (TBD; needs hittest)
```

## `hafen.items` — inventory & equipment
Backed by [`GameUI.maininv`](src/haven/GameUI.java) / `equwnd` / [`GItem`](src/haven/GItem.java) /
[`ItemInfo`](src/haven/ItemInfo.java).

```lua
hafen.items.inventory()             -- array of item snapshots in the main inventory
hafen.items.equipment()             -- array of equipped item snapshots (by slot)
hafen.items.hand()                  -- the item on the cursor, or nil
```
Item snapshot shape:
```lua
{ name=,                 -- ItemInfo.Name display text
  res=,                  -- resource name (stable identity)
  num=,                  -- stack count (GItem.num), or nil
  meter=,                -- wear/progress % (GItem.meter)
  quality=,              -- best-effort; content-defined (see note)
  slot= / gridpos=, }    -- location
```
> **Quality note.** Quality is not a base-client field; it comes from server-shipped resource
> code or must be parsed from raw tooltip args. Names and counts are stable; quality is
> best-effort. See [Q-006](../DECISIONS.md)-adjacent discussion in
> [01-architecture.md](01-architecture.md) (content-defined data).

Item **handles** (for actions/replacement) are exposed by the widget-replacement API
([08](08-widget-replacement.md)) and by `hafen.act.item` when the actions tier is enabled.

## `hafen.char` — character & party
Backed by [`Glob.getcattr`](src/haven/Glob.java:344), [`CharWnd`](src/haven/CharWnd.java),
[`Glob.party`](src/haven/Party.java).

```lua
hafen.char.attr("str")              -- { base=, comp= } (base vs computed/buffed)
hafen.char.attrs()                  -- table of all known base attributes
hafen.char.lp()                     -- learning points / weight (from CharWnd.exp/enc)
hafen.char.party()                  -- array of { id=, x=, y=, color= } party members
```
> Skills, food/FEP, wounds, quests, buffs live in widget trees, not in `Glob`. Exposing them
> means traversing `GameUI.chrwdg` / `GameUI.buffs` — deferred; surface TBD.

## `hafen.act` — actions (GATED, Phase 4)
Backed by [`Widget.wdgmsg`](src/haven/Widget.java:737). **Disabled unless the actions tier is
explicitly enabled** ([D-010](../decisions/actions-permissions.md), [12-security-and-permissions.md](12-security-and-permissions.md)).

```lua
hafen.act.moveTo(x, y)              -- walk: map click on ground
hafen.act.clickGob(id, button, mods)
hafen.act.menu("dig")              -- GameUI.act(path...) — action by menu path
hafen.act.flower("Harvest")        -- select an open FlowerMenu petal by label
hafen.act.item(itemHandle, verb)   -- "take"/"drop"/"transfer"/"iact"/"itemact"
hafen.act.raw(widgetHandle, msg, ...) -- escape hatch (bound widgets only)
```
Encodings (coord `floor(posres)`, gob clickargs, modflags) are handled by the bridge; see the
action map in [09-events-catalog.md](09-events-catalog.md) and project notes.

## `hafen.ui` — windows, overlays, drawing, replacement
See [07-ui-and-drawing.md](07-ui-and-drawing.md) and [08-widget-replacement.md](08-widget-replacement.md).

```lua
hafen.ui.window{ title=, size=, onDraw=, onClick=, ... }  -- create a LuaWindow handle
hafen.ui.widget{ parent=, onDraw=, onTick=, ... }         -- create a LuaWidget handle
hafen.ui.overlay(fn)                -- HUD-wide overlay painter; returns a handle
hafen.ui.gobOverlay(filter, fn)     -- world-space overlay over matching gobs
hafen.ui.replace(type, opts, fn)    -- intercept/replace a server widget (Phase 3)
```

## `hafen.events` — the event bus
See [09-events-catalog.md](09-events-catalog.md).
```lua
local sub = hafen.events.on(name, fn)   -- subscribe; returns a handle
sub:off()                               -- unsubscribe (also auto-released on teardown)
hafen.events.emit(name, ...)            -- addon-defined custom events (cross-addon) — TBD
```

## `hafen.timer` — scheduling
```lua
hafen.timer.after(seconds, fn)          -- one-shot
hafen.timer.every(seconds, fn)          -- repeating; returns a handle with :cancel()
```
Driven by the tick pump; all callbacks on the UI thread.

## `hafen.store` — saved variables
Per-character JSON persistence ([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).
```lua
hafen.store.settings                    -- a persisted table (name from manifest saved_variables)
hafen.store.flush()                     -- force a write now
```
Access model (proxy table vs explicit get/set) — TBD in review.

## `hafen.log` — logging
```lua
hafen.log(msg)                          -- info to console + per-addon log
hafen.log.warn(msg) / hafen.log.error(msg)
```

## `hafen.key` — hotkeys
Backed by [`KeyBinding`](src/haven/KeyBinding.java) (remappable, persisted, appears in the client
keybind panel).
```lua
hafen.key.bind(id, defaultKey, fn)      -- id namespaced as "addon/<addonid>/<name>"
```
Handlers are addon-owned and cleared on teardown; the `KeyBinding` singleton persists by id.

---

## Namespace ↔ mechanism summary

| Namespace | Underlying `haven.*` |
|---|---|
| `hafen.world` | `Glob.oc` (OCache/Gob/GAttrib), `Glob.map` (MCache) |
| `hafen.player` | `MapView.plgob`/`player()` |
| `hafen.items` | `GameUI.maininv`/`equwnd`, `GItem`, `ItemInfo` |
| `hafen.char` | `Glob.getcattr`, `CharWnd`, `Glob.party` |
| `hafen.act` | `Widget.wdgmsg`, `MapView`, `GameUI.act`, `FlowerMenu`, `GItem` |
| `hafen.ui` | `Widget`/`Window`, `GOut`, `UI.drawafter`, `@RName` factory, `GameUI.addchild` |
| `hafen.events` | synthesized bus + `OCache.callback`, chat, etc. |
| `hafen.timer` | tick pump |
| `hafen.store` | JSON under `savedata/` |
| `hafen.key` | `KeyBinding` |

> This document is an outline. Each namespace will get finalized signatures, return-shape
> tables, and examples before implementation. Open items are marked TBD inline.

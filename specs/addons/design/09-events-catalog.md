# Events Catalog

> **Status:** 🟠 Outline · **Spec:** AddOns
> **Related:** [01-architecture.md](01-architecture.md) (P4), [06-lua-api.md](06-lua-api.md), [11-core-hooks.md](11-core-hooks.md)

Hafen has **no** event bus; the engine synthesizes one ([P4](01-architecture.md)). This document
catalogs the events, each event's source in the client, and whether wiring it needs a core edit.

Subscription: `local sub = hafen.events.on("GobAdded", fn)` → `sub:off()` (auto-released on
teardown). All handlers run on the UI thread; network-thread sources are marshalled first.

## Event catalog

| Event | Payload | Source in client | Core edit? |
|---|---|---|---|
| `OnLoad` | — | engine: after addon files run | none |
| `OnEnterWorld` | — | engine: `GameUI`/session ready (or at Reload) | none |
| `OnDisable` | — | engine: teardown | none |
| `OnUpdate` | `dt` | engine tick pump ([04](04-engine.md)) | none |
| `GobAdded` | gob snapshot | [`OCache.callback(ChangeCallback).added`](src/haven/OCache.java:75) | **none** (public API) |
| `GobRemoved` | gob snapshot | `OCache.callback(...).removed` | **none** |
| `PlayerMoved` | `{x,y}` | derived: player gob position change, per tick | none |
| `ChatMessage` | `{from, text, channel}` | [`ChatUI`](src/haven/ChatUI.java) incoming message | one-liner |
| `SystemMessage` | `{text}` | `RootWidget`/`GameUI` `msg`/`error` uimsg | one-liner |
| `InventoryChanged` | `{inv, item, added}` | [`Inventory.addchild`/`cdestroy`](src/haven/Inventory.java) | one-liner |
| `ItemHovered` | item snapshot | [`WItem`](src/haven/WItem.java) hover/tooltip | one-liner |
| `MenuOpened` | `{petals}` | [`FlowerMenu.added`](src/haven/FlowerMenu.java) | one-liner |
| `MenuChanged` | `{page}` | [`MenuGrid`](src/haven/MenuGrid.java) fill/goto uimsg | one-liner |
| `WidgetCreated` | `{id,type,context}` | [`UI.NewWidget`](src/haven/UI.java:433) / `GameUI.addchild` | one-liner (shared with widget-replacement) |
| `WidgetMessage` | `{id,name,args}` | generic tap in [`UI.wdgmsg`](src/haven/UI.java:665) / `ui.uimsg` | one-liner (power users) |
| `CombatStart`/`CombatEnd` | opponent info | [`Fightview`](src/haven/Fightview.java) create/destroy | one-liner |
| `Keybind:<id>` | mods | [`KeyBinding`](src/haven/KeyBinding.java) via `keybindings:register` | **none** |

"Core edit?" — **none** means the source is a public callback/registry the engine can attach to
without editing `haven`. "One-liner" means a single `Addons.fire(...)` call added at a choke
point; those are enumerated in [11-core-hooks.md](11-core-hooks.md).

## Design notes

- **Prefer events over polling.** e.g. use `GobAdded`/`GobRemoved` (via `OCache.callback`)
  instead of scanning `hafen.world.gobs()` each frame. Cheaper and idiomatic.
- **Marshalling.** `OCache.callback` and any `Transport.Callback`-derived source fire on the
  Connection/Loader threads. The engine enqueues them and dispatches on the next UI tick, so Lua
  only ever sees them on the UI thread ([P5](01-architecture.md)).
- **Snapshots, not handles.** Event payloads are plain Lua tables (a gob snapshot, a message
  table), consistent with the read API.
- **Custom/cross-addon events.** `hafen.events.emit(name, ...)` for addon-defined signals is
  proposed but its cross-addon semantics are TBD.

## Phased delivery

- **Phase 1:** `OnLoad`, `OnEnterWorld`, `OnDisable`, `OnUpdate`, `GobAdded`, `GobRemoved`,
  `PlayerMoved`, `Keybind:*` — all **zero core edits**.
- **Phase 2:** `ChatMessage`, `SystemMessage`, `InventoryChanged`, `MenuOpened`, `WidgetCreated`,
  `WidgetMessage` — the one-liner hooks.
- **Later:** `ItemHovered`, `MenuChanged`, `CombatStart/End`, and content-specific events.

---

## Appendix — Action message reference (client→server)

Verified `wdgmsg` encodings the actions tier (`hafen.act`, [06](06-lua-api.md), gated) wraps.
Everything is a [`Widget.wdgmsg`](src/haven/Widget.java:737) from a **bound** widget. World coords
are `Coord2d.floor(posres)` with `posres = 11/1024` per unit; `mods` = modifier bitfield.

| Action | Sender | Message | Args |
|---|---|---|---|
| Walk to ground | `MapView` | `"click"` | `pc(screen Coord), mc(world), 1, mods` |
| Left/right-click gob | `MapView` | `"click"` | `pc, mc, button, mods, 0/1, gobid, gobRc, olId, meshId` |
| Use held item on target | `MapView` | `"itemact"` | `pc, mc, mods [, gobargs]` |
| Drop held on ground | `MapView` | `"drop"` | `pc, mc, mods` |
| Place building | `MapView` | `"place"` | `rc, angle, button, mods` |
| Area select | `MapView` | `"sel"` | `c1, c2, mods` |
| Menu action by path | `GameUI`/`MenuGrid` | `"act"` | `path tokens…, mods [, mc, clickargs]` (via [`GameUI.act`](src/haven/GameUI.java)) |
| Menu action by id | `MenuGrid` | `"use"` | `pag.id, mods [, mc, clickargs]` |
| Flower petal select | `FlowerMenu` | `"cl"` | `petal.num, mods` (cancel: `"cl", -1`) |
| Item take | `GItem` | `"take"` | `cellCoord` |
| Item drop | `GItem` | `"drop"` | `cellCoord [, n]` (n=-1 = all) |
| Item transfer | `GItem` | `"transfer"` | `cellCoord, n` (n=-1 = all) |
| Item activate | `GItem` | `"iact"` | `cellCoord, mods` |
| Apply held onto item | `GItem` | `"itemact"` | `mods` |
| Bulk transfer inv↔inv | `Inventory` | `"invxf"` | `targetInv.wdgid(), n` |

The bridge builds these args (constructing `Coord`/`Coord2d`, resolving gob clickargs from
[`Gob.GobClick.clickargs`](src/haven/Gob.java)), so addons pass high-level params.

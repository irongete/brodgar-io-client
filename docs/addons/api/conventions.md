# API conventions

Rules that apply across the whole `hafen.*` API. Read this once; every section page assumes it.

## The `hafen` namespace

Every function lives under a namespaced table — `hafen.<section>.<verb>(...)`. There are no flat
globals. A section groups related verbs (`hafen.gob`, `hafen.map`, `hafen.items`, …); this reference
has one page per section.

```lua
local pos = hafen.gob.pos("player")
local n   = hafen.world.count("tree")
hafen.log("hello")
```

## References — how you address things

Functions take an explicit **reference** to the thing they act on (like WoW's `UnitHealth("player")`).
References re-resolve on every call, so they are always fresh. There are three kinds.

### GobRef — a game object

A **gob id** (number) or a **token** (string). Used by `hafen.gob.*`, `hafen.world.*`,
`hafen.act.clickGob`, and anywhere a single gob is addressed.

| Reference | Resolves to |
|---|---|
| `nil` / `"player"` / `"me"` | your own character |
| *(a number)* | the gob with that id |
| `"party1"` … `"partyN"` | party member N, in `Member.seq` order |

An unknown token or a gob that no longer exists resolves to `nil` (the accessor then returns `nil`).

### ItemRef — an inventory/equipment item

Items have no stable content id, so they are addressed by a **handle** = the item's server widget id.
Every [Item snapshot](types.md#item) carries a `handle` field. The gated verb
[`hafen.act.item`](actions.md#hafenactitem) takes the snapshot (or the raw `handle`
number) and re-resolves the live item on each call; a stale/moved/used item no longer resolves and the
verb raises a clear error.

### WidgetRef — a window or widget

A **handle** returned by `hafen.ui.*` (or a widget id from
[`hafen.ui.onWidgetCreate`](ui.md#observing--replacing-the-clients-own-ui)). Not a token.

## Snapshots vs handles

- **Snapshots** are plain Lua tables — point-in-time copies returned by the read APIs
  (`hafen.gob.info`, `hafen.items.inventory`, `hafen.buffs.list`, …). They do **not** update; don't
  cache them across ticks. Re-read to get fresh values. Every snapshot shape is documented in
  [types.md](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui.window`, `hafen.timer.every`,
  `hafen.events.on`, …). They are released automatically when the addon is disabled or reloaded.

## The `filter` argument

Enumerating verbs (`hafen.world.gobs`, `hafen.markers.list`, `hafen.kin.list`,
`hafen.radar.categories`, `hafen.quests.list`, `hafen.wounds.list`, `hafen.fight.maneuvers`, …) take
one optional **filter**, always in the same canonical form:

| `filter` | Keeps |
|---|---|
| `nil` (omitted) | everything |
| a **string** | entries whose `name` contains the string (substring match) |
| a **function** | entries for which `filter(snapshot)` returns truthy |

Use the function form to match on any field other than `name` (e.g. `res`):

```lua
hafen.world.gobs("rabbit")                       -- name contains "rabbit"
hafen.world.gobs(function(g) return g.hp and g.hp < 1 end)  -- injured gobs
```

## Coordinates

Positional arguments and returned positions are Lua numbers in **world units** unless a page says
otherwise. `hafen.map` converts between world, tile, and grid space.

> **There is no global position.** A gob's world position is *session-local* (it starts near the
> origin each login) and is not comparable across players or logins. The only stable, shareable anchor
> is a **grid id** plus a within-grid offset — see [`hafen.map.gridPos`](map.md).
> Map markers anchor on **segment id + segment tile coord** instead (see [markers](markers.md)).

## Missing data returns `nil`

A read returns `nil` (or an empty table for list verbs) when the data isn't available yet — before the
world loads, before a HUD widget streams in, or while a resource is still resolving. Reads never throw
a `Loading` error into Lua; the bridge swallows it. Much character-sheet data (vitals, food, skills,
quests, wounds, …) streams in a beat *after* `OnEnterWorld` — read it on a short timer or subscribe to
the matching [event](events.md).

## Threading

Every `hafen.*` call, every event handler, every timer, and every draw callback runs on the client's
**UI thread**. You never need locks, and you must never block — a long-running handler stalls the
client, and the sandbox's instruction watchdog will abort a runaway one.

## Gating — the `actions` permission

Everything in the API **observes** except one section: [`hafen.act`](actions.md) (and the per-subsystem
write verbs `hafen.speed.set`, `hafen.craft.make`, `hafen.actionbar.use`, `hafen.kin.add`/`remove`/
`forget`/`rename`/`setGroup`), which **drive the character** by sending actions to the server.

A write verb runs only if the addon **declared** `"permissions": ["actions"]` in its manifest and the
user enabled the addon (write addons are disabled by default; enabling one raises a consent dialog).
An undeclared addon calling a write verb gets a clear error. `hafen.act.enabled()` reports the grant
without throwing. See [Actions & permissions](actions.md).

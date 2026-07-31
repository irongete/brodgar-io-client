# API conventions

Rules that apply across the whole `hafen.*` API. Read this once; every section page assumes it.

## The `hafen` namespace

Every function lives under a namespaced table — `hafen.<section>.<verb>(...)`. There are no flat
globals. A section groups related verbs (`hafen.map`, `hafen.items`, …); this reference has one page
per section.

```lua
local n = hafen.world.count("tree")
hafen.log("hello")
```

## References — how you address things

Functions take an explicit **reference** to the thing they act on (like WoW's `UnitHealth("player")`).
References re-resolve on every call, so they are always fresh.

### Gob — a game object

A game object is an **object**: `hafen.gob(id)` (or anything [`hafen.world`](world.md) hands you) gives
you a Gob whose methods read it live, and `hafen.player():gob()` is your own. Every method re-resolves
the gob, so a handle you keep is always fresh and answers `nil` once the gob is gone. Anywhere a single
gob is addressed — `hafen.act.clickGob`, `follow=` in [`hafen.render`](render.md) — you pass the Gob
itself, never an id. See [gob.md](gob.md).

### Kin — a roster entry

A kin is an **object** too: `hafen.kin` is *callable*, and the arity is the verb — `hafen.kin()` is the
roster (a plain array of `Kin`), `hafen.kin(idOrName)` is one of them. Like a Gob, a `Kin` re-reads the
roster on every call, so a handle you keep tracks renames, regroups and online/offline flips, and
`hafen.kin(7) == hafen.kin(7)`. `gob:kin()` and `kin:gob()` cross between the two. See [kin.md](kin.md).

Gob and Kin are the sections that are object-oriented today; every other section is still a flat table
of functions. That mix is deliberate and temporary — the rest follows.

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
  (`gob:info()`, `hafen.items.inventory`, `hafen.buffs.list`, …). They do **not** update; don't
  cache them across ticks. Re-read to get fresh values. Every snapshot shape is documented in
  [types.md](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui.window`, `hafen.timer.every`,
  `hafen.events.on`, …). They are released automatically when the addon is disabled or reloaded.

## The `filter` argument

Enumerating verbs (`hafen.world.gobs`, `hafen.markers.list`, `hafen.kin():list`,
`hafen.radar.categories`, `hafen.quests.list`, `hafen.wounds.list`, `hafen.fight.maneuvers`, …) take
one optional **filter**, always in the same canonical form:

| `filter` | Keeps |
|---|---|
| `nil` (omitted) | everything |
| a **string** | entries whose `name` contains the string (substring match) |
| a **function** | entries for which `filter(entry)` returns truthy |

Use the function form to match on any field other than `name`. The entry is a snapshot table in the flat
sections, and an **object** in the sections that are object-oriented — [`hafen.world`](world.md) hands the
predicate a [Gob](gob.md), [`hafen.kin`](kin.md) a `Kin`:

```lua
hafen.world.gobs("rabbit")                                       -- name contains "rabbit"
hafen.world.gobs(function(g) return (g:health() or 1) < 1 end)   -- injured gobs (a Gob object)
hafen.kin():list(function(k) return k:online() end)              -- online kin (a Kin object)
hafen.markers.list(function(m) return m.type == "player" end)    -- a snapshot elsewhere
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
write verbs `hafen.speed.set`, `hafen.craft.make`, `hafen.actionbar.use`, and the kin verbs
`hafen.kin():add` / `kin:rename`/`setGroup`/`endkin`/`forget`), which **drive the character** by sending
actions to the server.

A write verb runs only if the addon **declared** `"permissions": ["actions"]` in its manifest and the
user enabled the addon (write addons are disabled by default; enabling one raises a consent dialog).
An undeclared addon calling a write verb gets a clear error. `hafen.act.enabled()` reports the grant
without throwing. See [Actions & permissions](actions.md).

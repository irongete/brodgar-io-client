# API conventions

Rules that apply across the whole `hafen.*` API. Read this once; every section page assumes it.

## The `hafen` namespace

Every function lives under a namespaced table — `hafen.<section>.<verb>(...)`. There are no flat
globals. A section groups related verbs (`hafen.map`, `hafen.char`, …); this reference has one page
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

### Slot — an action-bar slot

An action-bar slot is an **object** as well, and `hafen.actionbar` is *callable* on the same pattern —
`hafen.actionbar()` is all 144 slots (a 1-based array of `Slot`), `hafen.actionbar(n)` is the one at the
**raw 0-based game index**. A `Slot` wraps only that index and re-reads the bar every call, so a stashed
one goes `:empty()` the moment the slot is cleared, and `hafen.actionbar(0) == hafen.actionbar(0)`.
`slot:index()` gives the game index back from an array position. See [actionbar.md](actionbar.md).

### Needle-keyed objects — Buff, Meter, Action, Sound

The same callable-namespace pattern, keyed by a **string** instead of an id, and the no-argument call is
always the collection. [`hafen.buff(needle)`](buffs.md) and [`hafen.meter(needle)`](meters.md) are
*substring* lookups — the first object whose resource (for a buff, also its display name) contains the
needle; [`hafen.menugrid(key)`](menugrid.md) and [`hafen.sound(resName)`](audio.md) name one outright.
Either way the strings are **server-published**, not keys the API defines — read them off a live client
with `:res()` rather than trusting a list in this reference. A miss is plain `nil`, and addressing one by
**position** is an error (positions are not addresses — index the collection instead).

The object-oriented sections today are **Gob, Player, Kin, Slot, Buff, Meter, Action (menugrid), Sound,
Asset and Widget**; the rest are still flat tables of functions. That mix is deliberate and temporary —
the migration continues.

### Asset — a file your addon ships

[`hafen.asset`](asset.md) is callable on the same pattern, keyed by an **addon-relative path**:
`hafen.asset(path)` is one asset, `hafen.asset()` is the ones this addon currently holds. It is the one
place a callable namespace hands back an **owned resource** rather than a view of engine state — the type
comes from the file's extension, the handle is interned per path, and it is freed automatically on
reload/disable (or by `:dispose()`, after which the same path re-loads as a *new* object). Wherever a local
file is used — `hafen.render.sprite{image=}`, `object{model=}`, `font=` — you pass the **handle**, never a
path string.

### ItemRef — an inventory/equipment item

Items have no stable content id, so they are addressed by a **handle** = the item's server widget id.
Every [Item snapshot](types.md#item) carries a `handle` field. The gated verb
[`hafen.act.item`](actions.md#hafenactitem) takes the snapshot (or the raw `handle`
number) and re-resolves the live item on each call; a stale/moved/used item no longer resolves and the
verb raises a clear error.

### Widget — a piece of the UI

A widget is an **object**, and there is only one kind: a window you create with `hafen.ui.window{}`, a
native one you find with `hafen.ui.root()`/`node(id)`/`at(x, y)`/`inventory()`, and the one `replace` hands
your callback are all the same [Widget](ui.md#the-widget-object). It is interned per addon, so
`hafen.ui.at(x, y) == hafen.ui.at(x, y)` and `==` is the identity test; it re-reads the tree on every call
and answers `nil`/empty with `:exists()` false once its widget is gone. What you may *write* depends on
whether your addon created it — see [owned vs borrowed](ui.md#owned-vs-borrowed--which-writes-answer).
A **server widget id** (`:id()`, or a `desc.id` from
[`hafen.ui.onWidgetCreate`](ui.md#observing--replacing-the-clients-own-ui)) is the number the gated
[`hafen.act.raw`](actions.md) takes.

## Snapshots vs handles

- **Snapshots** are plain Lua tables — point-in-time copies returned by the read APIs
  (`gob:info()`, `widget:items()`, `buff:info()`, …). They do **not** update; don't
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
a `Loading` error into Lua; the bridge swallows it. Much character-sheet data (meters, food, skills,
quests, wounds, …) streams in a beat *after* `OnEnterWorld` — read it on a short timer or subscribe to
the matching [event](events.md).

## Threading

Every `hafen.*` call, every event handler, every timer, and every draw callback runs on the client's
**UI thread**. You never need locks, and you must never block — a long-running handler stalls the
client, and the sandbox's instruction watchdog will abort a runaway one.

## Gating — the `actions` permission

Everything in the API **observes** except one section: [`hafen.act`](actions.md) (and the per-subsystem
write verbs `hafen.speed.set`, `hafen.craft.make`, `slot:use`, and the kin verbs
`hafen.kin():add` / `kin:rename`/`setGroup`/`endkin`/`forget`), which **drive the character** by sending
actions to the server.

A write verb runs only if the addon **declared** `"permissions": ["actions"]` in its manifest and the
user enabled the addon (write addons are disabled by default; enabling one raises a consent dialog).
An undeclared addon calling a write verb gets a clear error. `hafen.act.enabled()` reports the grant
without throwing. See [Actions & permissions](actions.md).

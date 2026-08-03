# API conventions: the shared vocabulary

The rules that hold across the whole `hafen.*` API: how you address a thing, what a read gives back,
what a write costs you. Read this once — every reference page assumes it.

```lua
local rabbits = hafen.world.gobs("rabbit")      -- a filter, the same everywhere
hafen.log(#rabbits .. " in view")
```

## The hafen namespace

Every function lives under a namespaced table, `hafen.<section>.<verb>(...)`. There are no flat
globals. A section groups the verbs of one subsystem (`hafen.world`, `hafen.char`, …) and owns one
reference page; a section large enough to need several pages owns a folder with a hub instead.

## References: how you address things

A verb takes an explicit **reference** to the thing it acts on. A reference re-resolves on every
call, so a handle you keep is always fresh and never a stale copy.

### Gob: a game object

`hafen.gob(id)` — or anything [`hafen.world`](world.md) hands you — gives a **Gob object** whose
methods read the live game object; `hafen.player():gob()` is your own. Every method re-resolves, so
the object answers `nil` once the gob is gone, while `:id()` still answers. Anywhere a single gob is
addressed — [`hafen.act.clickGob`](act.md), `follow=` in [`hafen.render`](render/README.md) — you pass
the Gob itself, never an id. See [`hafen.gob`](gob.md).

### Kin: a roster entry

A kin is an object too, and `hafen.kin` is **callable**: the arity is the verb. `hafen.kin()` is the
roster, an array of `Kin`; `hafen.kin(idOrName)` is one of them. A `Kin` re-reads the roster on every
call, so a stashed one tracks renames, regroups and online flips, and `hafen.kin(7) == hafen.kin(7)`.
`gob:kin()` and `kin:gob()` cross between the two. See [`hafen.kin`](kin.md).

### Slot: an action-bar slot

Same pattern: `hafen.actionbar()` is all 144 slots, a 1-based array of `Slot`; `hafen.actionbar(n)` is
the one at the **raw 0-based game index**. A `Slot` wraps only that index and re-reads the bar every
call, so a stashed one goes `:empty()` the moment the slot is cleared, and
`hafen.actionbar(0) == hafen.actionbar(0)`. `slot:index()` gives the game index back from an array
position. See [`hafen.actionbar`](actionbar.md).

### Needle-keyed objects: Buff, Meter, Action, Sound

The same callable namespace, keyed by a **string** instead of an id; the no-argument call is always
the collection. [`hafen.buff(needle)`](buff.md) and [`hafen.meter(needle)`](meter.md) are *substring*
lookups — the first object whose resource, or for a buff its display name, contains the needle —
while [`hafen.menugrid(key)`](menugrid.md) and [`hafen.sound(name)`](sound.md) name one outright.
Either way the strings are **server-published**, not keys the API defines: read them off a live client
with `:res()` rather than trusting a list. A miss is plain `nil`, and addressing one by **position**
is an error — a position is not an address, so index the collection instead.

### Asset: a file your addon ships

[`hafen.asset`](asset.md) is callable on the same pattern, keyed by an **addon-relative path**:
`hafen.asset(path)` is one asset, `hafen.asset()` the ones this addon holds. It is the one callable
namespace that hands back an **owned resource** rather than a view of client state — the type comes
from the file's extension, the handle is interned per path, and it is freed on reload or disable, or
by `:dispose()`, after which the same path loads as a *new* object. Wherever a local file is used —
`hafen.render.sprite{image=}`, `object{model=}`, `font=` — you pass the **handle**, never a path.

### ItemRef: an inventory or equipment item

An item has no stable content id, so it is addressed by a **handle**: its server widget id. Every
[Item snapshot](types.md#item) carries a `handle` field. The gated
[`hafen.act.item`](act.md#hafenactitemitem-verb-n) takes the snapshot, or the raw `handle` number, and
re-resolves the live item on each call; an item that has moved, been used or gone no longer resolves,
and the verb raises an error saying so.

### Widget: a piece of the UI

A widget is an object, and there is only one kind. A window you create with `hafen.ui.window{}`, a
native one you name with `hafen.ui(selector)`, `node(id)`, `at(x, y)` or `inventory()`, and the one
[`hafen.ui.on`](ui/replace.md#watching-for-a-widget) hands your callback are all the same
[Widget](ui/widget.md). It is interned per addon, so `hafen.ui.at(x, y) == hafen.ui.at(x, y)` and `==`
is the identity test; it re-reads the tree on every call and answers `nil` or empty, with `:exists()`
false, once its widget is gone. What you may *write* depends on whether your addon created it — see
[owned vs borrowed](ui/widget.md#owned-vs-borrowed). A **server widget id**, `:id()`, is the number the
gated [`hafen.act.raw`](act.md) takes.

Its two write verbs read the **arity as the verb**, like every callable namespace above:
[`w:replace()`](ui/replace.md) reads, `w:replace(view)` installs, `w:replace(nil)` undoes; and
[`w:skin()`](ui/style/README.md#restyle-one-widget) reads your own style, `w:skin{…}` installs it,
`w:skin(nil)` drops it. Each answers for **your** addon: what you wrote comes back unchanged, and what
you drop leaves another addon's alone.

### Selector: naming a piece of the UI

Ids and handles address a thing you already have. A **selector** addresses one you can only
*describe*: a string that names a widget by what it **is**, resolved against the live tree.

```lua
hafen.ui("window[title=Cupboard]")     -- the first match, or nil
hafen.ui.all("inventory")              -- every match, in tree order (empty array, never nil)
```

Three properties make it a convention rather than a lookup helper:

- **One string, three uses.** The same selector names a widget for a lookup, `hafen.ui(sel)`, for a
  listing, `hafen.ui.all(sel)`, and for one that does not exist yet,
  [`hafen.ui.on(sel, "appear", fn)`](ui/replace.md#watching-for-a-widget) — so waiting for a window and
  then reading it are one vocabulary.
- **One string, two resolutions.** The same selector is also the key of a
  [stylesheet](ui/style/README.md): a **role** names a render *site* and restyles it
  ([the site keys](ui/style/surfaces.md)), while every other selector resolves against the live tree.
  `w:role()` reports a widget's role, or an honest `nil`.
- **Arity is the verb**, as everywhere else: `hafen.ui(sel)` is one widget, `hafen.ui.all(sel)` is all
  of them, and `hafen.ui()` with no argument is the root of the tree.

The grammar, the role table and the two rules worth knowing before you write one — `[title=]` resolves
against the *enclosing window*, and you hold your result rather than re-selecting every frame — are in
[selectors](ui/selectors.md). You never have to guess a role: the bundled **`widgetstack`** addon
[tells you by hovering](ui/selectors.md#the-inspector).

## Snapshots vs handles

- **Snapshots** are plain Lua tables, point-in-time copies returned by the escape-hatch readers
  (`gob:info()`, `widget:items()`, `buff:info()`, …). They do **not** update, so re-read rather than
  caching one across ticks. Every snapshot shape is in [data types](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui.window`, `hafen.timer.every`,
  `hafen.events.on`, …), released for you when the addon is disabled or reloaded.

## The filter argument

Every enumerating verb — `hafen.world.gobs`, `hafen.map.markers.list`, `hafen.kin():list`,
`hafen.map.icons`, `hafen.quests.list`, `hafen.wounds.list`, `hafen.fight.maneuvers`, … — takes
one optional **filter**, always in the same form:

| `filter` | Keeps |
|---|---|
| `nil` (omitted) | everything |
| a **string** | entries whose `name` contains the string (substring match) |
| a **function** | entries for which `filter(entry)` returns truthy |

Use the function form to match on any field other than `name`. The entry your predicate receives is a
snapshot table in the flat sections and an **object** in the object-oriented ones:
[`hafen.world`](world.md) hands it a [Gob](gob.md), [`hafen.kin`](kin.md) a `Kin`.

```lua
hafen.world.gobs("rabbit")                                       -- name contains "rabbit"
hafen.world.gobs(function(g) return (g:health() or 1) < 1 end)   -- injured gobs (a Gob object)
hafen.kin():list(function(k) return k:online() end)              -- online kin (a Kin object)
hafen.map.markers.list(function(m) return m.type == "player" end)  -- a snapshot elsewhere
```

## Coordinates

Positional arguments and returned positions are Lua numbers in **world units** unless a page says
otherwise. [`hafen.world`](world.md#terrain-and-coordinates) converts between world, tile and grid space.

> **There is no global position.** A gob's world position is session-local — it starts near the origin
> each login — and is not comparable across players or logins. The stable, shareable anchor is a
> **grid id** plus a within-grid offset, [`hafen.world.gridPos`](world.md#saving-a-world-position-across-sessions) — the id comes from the
> server, so it means the same thing to every player. Map markers anchor on segment id plus segment tile
> coord instead; see [`hafen.map.markers`](map.md#markers).

## Colours

A colour is a table of **0..255 components**, written either way:

```lua
{ 200, 210, 220 }                                   -- positional: r, g, b, and a if you want it
{ r = 200, g = 210, b = 220, a = 255 }              -- keyed — what every reader hands back
```

Both are accepted everywhere a colour goes in: `hafen.ui.skin{…}`'s `color`, `g:text{color=…}`,
`hafen.map.markers.add`, a ghost or sprite `tint`, `font:derive{color=…}`. So a colour you *read* —
`kin:color()`, `meter:color()` — passes straight back. Alpha defaults to `255`, and a component
outside `0..255` is clamped rather than refused.

## Missing data returns nil

A read returns `nil`, or an empty table for a list verb, when the data is not available yet: before
the world loads, before a HUD widget streams in, or while a resource is still resolving. Reads never
throw a loading error into Lua — the bridge swallows it. Much character-sheet data (meters, food,
skills, quests, wounds, …) streams in a beat *after* `OnEnterWorld`, so read it on a short timer or
subscribe to the matching [event](events.md).

## Threading

Every `hafen.*` call, every event handler, every timer and every draw callback runs on the client's
**UI thread**. You never need locks, and you must never block: a long-running handler stalls the
client, and the sandbox's instruction watchdog aborts a runaway one.

## Gating: the actions permission

Everything in the API observes except one section, [`hafen.act`](act.md), which **drives the
character** by sending actions to the server. The same gate covers the per-subsystem write verbs that
do the same thing from their own page: `hafen.speed.set`, `hafen.craft.make`, `slot:use`, `slot:set`,
and the kin verbs `hafen.kin():add`, `kin:rename`, `kin:setGroup`, `kin:endkin` and `kin:forget`.

A gated verb runs only if the addon **declared** `"permissions": ["actions"]` in its manifest and the
user enabled the addon — such an addon is disabled by default, and enabling it raises a consent
dialog. An undeclared addon calling one gets an error naming the verb. `hafen.act.enabled()` reports
the grant without throwing.

Writing is not the same as being gated. A verb that changes something **client-local** — a map marker,
a minimap icon flag, a sound, your own window — sends nothing to the server and needs no permission;
its page says so on the group heading. [`hafen.http`](http.md) has a gate of its own, a `network`
host allowlist in the manifest.

## See also

- [data types](types.md) — every snapshot shape the readers return
- [events](events.md) — the bus, and what each event hands your handler
- [`hafen.act`](act.md) — the gated tier, and the permission itself
- [`hafen.ui`](ui/README.md) — where selectors, widgets and the stylesheet are documented in full

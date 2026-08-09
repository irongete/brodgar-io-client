# API conventions: the shared vocabulary

The rules that hold across the whole `hafen.*` API: how you address a thing, what a read gives back,
what a write costs you. Read this once — every reference page assumes it.

```lua
hafen.timer():every(5, function()
  hafen.log():write("still here")
end)
```

## The grammar

One shape, learned once: **a section is called, everything after it is a colon verb, and arity is the
verb.** A section groups one subsystem and owns one reference page; a section large enough to need
several pages owns a folder with a hub instead.

### Sections: you call one

`hafen.time` is the section. `hafen.time()` is the **section object**, and every verb of that
subsystem is a colon call on it, as in `hafen.time():clock()`. The object is the same one every time,
so `hafen.time() == hafen.time()` and calling a section inside a draw callback allocates nothing.

A section takes no arguments. Where a section holds exactly one thing, the section object **is** that
thing rather than a wrapper around it: `hafen.timer()` is the collection of your timers, and
`hafen.player()` is your character.

### Verbs: arity is the verb

A verb with no argument **reads**. The same verb with one **writes**, and hands the object back, so
writes chain.

```lua
w:title()                          -- reads
w:title("Scout"):size(180, 48)     -- writes, and chains
```

There is one name per property: no `getX`, no `setX`, no `clearX`. A boolean is a property like any other,
so a window is shown with `w:visible(true)` rather than a second verb. `coll:get(key)` is no exception: it
addresses a member rather than reading a property.

### Collections: the noun is the kind, the verb is how many

A set you can address into is reached by the **singular** kind name and hands back a collection
object, never a bare array. The plural belongs to the verb.

| Verb | Gives you |
|---|---|
| `:list(filter)` | a plain array of members, empty rather than `nil` |
| `:count(filter)` | how many |
| `:find(filter)` | the first member that matches, or `nil` |
| `:get(key)` | one member by its key, or `nil`, where the members have keys |
| `:add(...)` | a new member, where the collection can create one |
| `:remove(keyOrMember)` | the collection, so removals chain, where it can destroy one |

A distinguished member is a verb on its collection rather than a second accessor: `:current()`,
`:selected()`, `:leader()`, `:available()`.

**A section's collection is one object; a thing's collection is a view.** `hafen.map():marker()` is the
same object on every call. `gob:overlay()` is re-derived from the gob, so two calls are not `==` and
neither one outlives it. Identity lives on the **members**: `gob:overlay():get("tag")` is one overlay.

> **A collection is an object, not a sequence.** `#coll`, `coll[1]` and `ipairs(coll)` are refused,
> naming what to write instead. Two ways to enumerate one thing is the ambiguity this API does not
> have: `coll:list()` is the array, and you index that.

### Objects, and the snapshot hatch

A read hands back a **live object** rather than a copy. It re-resolves on every call, answers `nil`
once the thing it names is gone, and reports `:exists()`. Objects are interned per addon, so `==` is
the identity test and one works as a table key. A point-in-time copy is what `:info()` gives you, and
nothing else does; every shape it returns is in [data types](types.md).

### nil is an error unless it means something

An explicit `nil` argument raises. Arity is the verb, so a value you meant to write but that arrived
as `nil` would otherwise turn the write into a read, silently. Two meanings are documented, each on
the page that carries it: **undo your layer** (`w:position(nil)`, `w:size(nil)`, `w:replace(nil)`) and
**none** (a `tint(nil)`). Everywhere else it is an accident, and there is nothing to undo.

```lua
w:position(x, y)          -- with an x you forgot to compute, this raises
w:position()              -- the read is the same verb with no argument
```

The bridge separates the two cases by counting arguments, and it is exact for a value you pass
directly, a table field included: `w:size(cfg.width, cfg.height)` with a missing key raises. One gap
is inherent to it: `f(g())` where `g` returns *nothing* arrives as no argument at all and is read as
`f()`. A `g` that returns an explicit `nil` is refused like any other value.

### A table is a value, never named arguments

A table you pass in is **data**: a colour, a coordinate, a document to encode. A thing you build is
constructed bare and configured by chained setters instead of by a table of named arguments, so the
configuration reads in the order it happens and a setter can refuse what it cannot do. The boundary is
deliberate rather than missing: it is why a request carries `req:header(name, value)` rather than an
options table, and it does not reach what a verb *returns* — a `:list()` array and an `:info()` table are
ordinary Lua tables you index normally.

### A retired name says what replaced it

A spelling this API has replaced does not read as `nil`. It raises, at the line that wrote it, naming
what to write instead. A name that was never part of this API still reads as plain `nil`, so testing
whether something exists still works.

## References: how you address things

A verb takes an explicit **reference** to the thing it acts on, and it re-resolves on every call.

### Gob: a game object

`hafen.world():gob()` is the collection of loaded game objects and everything on it hands back a
[**Gob**](gob.md) whose methods read the live one; `hafen.player():gob()` is your own. Every method
re-resolves, so it answers `nil` once the gob is gone while `:id()` still answers. Anywhere a single gob
is addressed — [`hafen.act():clickGob`](act.md), [`gob:overlay()`](gob.md#overlays) — you pass the Gob
itself, never an id.

### Kin: a roster entry

A kin is an object too, and `hafen.kin()` **is** the roster collection: `:list(filter)` is the array of
`Kin`, `:get(idOrName)` one of them. A `Kin` re-reads the roster on every call, so a stashed one tracks
renames, regroups and online flips, and `hafen.kin():get(7) == hafen.kin():get(7)`. `gob:kin()` and
`kin:gob()` cross between the two. See [`hafen.kin`](kin.md).

### Slot: an action-bar slot

Same pattern: `hafen.actionbar():list()` is all 144 slots, a 1-based array of `Slot`, and `:get(n)` is
the one at the **raw 0-based game index**, with `slot:index()` giving that index back from an array
position. A stashed `Slot` goes `:empty()` the moment the slot is cleared. See
[`hafen.actionbar`](actionbar.md).

### Named, and nameless: Menugrid, Sound, Buff, Meter

[`hafen.menugrid():get(key)`](menugrid.md) names one action — a `/` makes the key a resource name,
anything else a display name — and [`hafen.sound():get(name)`](sound.md) one clip; the strings are
**server-published**, so read them off a live client with `:res()` rather than trusting a list.
[`hafen.buff()`](buff.md) and [`hafen.meter()`](meter.md) carry **no `:get`** at all, because their
members have no key: several bars can share one resource. There a name is a *search*, `:find(needle)`,
and `:get` raises an error naming it — a miss is `nil`, and a **position** is an error.

### Asset: a file your addon ships

[`hafen.asset()`](asset.md) is a collection keyed by an **addon-relative path**: `:get(path)` is one
asset, `:list(filter)` the ones this addon holds. It is the one collection that hands back an **owned
resource** rather than a view of client state — the type comes from the file's extension, the handle is
interned per path, and it is freed on reload or disable, or by `:dispose()`, after which the same path
loads as a *new* object. Wherever a local file is used — a sprite's `:add(image)`, an object's
`:add(model)`, a widget's `:font(h)` — you pass the **handle**, never a path.

### Item: a thing in a container

An item has no stable content id, so an [`Item`](ui/items.md#the-item-object) is interned on the item
itself and **not** on `:handle()`, the server widget id it is addressed by on the wire: that number is
re-used, so a reference built on it would quietly stop naming this item and start naming its
replacement. One you keep therefore answers *the same item* or *gone*, and the gated
[`hafen.act():item`](act.md#hafenactitemitem-verb-n) takes the object rather than the number.

### Widget: a piece of the UI

A widget is an object, and there is only one kind. A window you create with `hafen.ui():window()`, a
native one you name with `hafen.ui():find(selector)`, `node(id)`, `at(x, y)` or `inventory()`, and the one
[`hafen.ui():on`](ui/replace.md#watching-for-a-widget) hands your callback are all the same
[Widget](ui/widget.md). It is interned per addon, so `hafen.ui():at(x, y) == hafen.ui():at(x, y)` and `==`
is the identity test; it re-reads the tree on every call and answers `nil` or empty, with `:exists()`
false, once its widget is gone. What you may *write* depends on whether your addon created it — see
[owned vs borrowed](ui/widget.md#owned-vs-borrowed). A **server widget id**, `:id()`, is the number the
gated [`hafen.act():raw`](act.md) takes.

Its write verbs answer for **your** addon: what you wrote comes back unchanged, and what you drop
leaves another addon's alone. [`w:replace(view)`](ui/replace.md) installs a stand-in and
`w:replace(nil)` undoes it; [`w:rule()`](ui/style/README.md#restyle-one-widget) is your own level of the
style cascade, and `w:rule():remove()` drops it.

### Selector: naming a piece of the UI

Ids and handles address a thing you already have. A **selector** addresses one you can only
*describe*: a string that names a widget by what it **is**, resolved against the live tree.

```lua
hafen.ui():find("window[title=Cupboard]")     -- the first match, or nil
hafen.ui():all("inventory")              -- every match, in tree order (empty array, never nil)
```

Three properties make it a convention rather than a lookup helper:

- **One string, three uses.** The same selector names a widget for a lookup, `hafen.ui():find(sel)`, for a
  listing, `hafen.ui():all(sel)`, and for one that does not exist yet,
  [`hafen.ui():on(sel, "appear", fn)`](ui/replace.md#watching-for-a-widget) — so waiting for a window and
  then reading it are one vocabulary.
- **One string, two resolutions.** The same selector is also the key of a
  [stylesheet](ui/style/README.md): a **role** names a render *site* and restyles it
  ([the site keys](ui/style/surfaces.md)), while every other selector resolves against the live tree.
  `w:role()` reports a widget's role, or an honest `nil`.
- **The verb says how many**: `hafen.ui():find(sel)` is one widget, `hafen.ui():all(sel)` is all of them,
  and `hafen.ui():root()` is the root of the tree.

The grammar, the role table and the two rules worth knowing first — `[title=]` resolves against the
*enclosing window*, and you hold your result rather than re-selecting every frame — are in
[selectors](ui/selectors.md), where the bundled **`widgetstack`** addon also
[names one by hovering](ui/selectors.md#the-inspector).

## Snapshots vs handles

- **Snapshots** are plain Lua tables, point-in-time copies from the escape-hatch `:info()` readers
  (`gob:info()`, `item:info()`, …). They do **not** update, so re-read rather than caching one across
  ticks. Every snapshot shape is in [data types](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui():window()`, `hafen.timer():every`,
  `hafen.event():on`, …), released for you when the addon is disabled or reloaded. So is every **object**
  a read hands you: it re-resolves rather than holding a value, so one you keep tracks what it names.

## The filter argument

Every enumerating verb — `hafen.world():gob():list`, `hafen.kin():list`, `hafen.map():icon():list`,
`hafen.fight():maneuver():list`, … — takes one optional **filter**, always in the same form:

| `filter` | Keeps |
|---|---|
| `nil` (omitted) | everything |
| a **string** | entries whose `name` contains the string (substring match) |
| a **function** | entries for which `filter(entry)` returns truthy |

Use the function form to match on any field other than `name` — and on a set whose members have none at
all, such as a party member, a segment or a timer, where a string is refused naming the forms that do
work. A member whose name has simply **not arrived yet** does not match, and does not spoil the call. The
entry your predicate receives is always the **object**, never a snapshot: read it with its own verbs.

```lua
local gobs = hafen.world():gob()
gobs:list("rabbit")                                        -- name contains "rabbit"
gobs:list(function(g) return (g:health() or 1) < 1 end)    -- injured gobs (a Gob object)
hafen.kin():list(function(k) return k:online() end)        -- online kin (a Kin object)
hafen.map():marker():list(function(m) return m:type() == "player" end)   -- a Marker object
```

## Coordinates

A place in the world is a **[Position](world.md#the-position-type)**, not a pair of numbers: one type,
carried by every spatial verb, and the only thing `position()` ever answers. It is computable —
`p:offset(dx, dy)` moves it in world units and the engine crosses grid boundaries for you — and durable,
so it goes into [`hafen.store`](store.md) and comes back unchanged. Everything else that counts is a
**lattice** and keeps its own name: tile, grid and segment coords are indices, not places, and screen
pixels are plain `{x, y}` numbers.

> **There is no global position.** A world coordinate is this session's answer, re-based whenever the
> server drops the map — a login, a walk into a cave. What a Position saves is a **grid id** plus an
> offset inside it, and that id comes from the **server**, so it means the same thing to everyone. A map
> marker records its place differently, in ids this client invented and a map merge rewrites, so one you
> want to keep is stored as its [`marker:position()`](map/markers.md#the-marker-object); see
> [storing a place](map/grids.md#storing-a-place).

## Colours

A colour is a table of **0..255 components**, written either way:

```lua
{ 200, 210, 220 }                                   -- positional: r, g, b, and a if you want it
{ r = 200, g = 210, b = 220, a = 255 }              -- keyed — what every reader hands back
```

Both are accepted everywhere a colour goes in: `rule:color(…)`, `g:text{color=…}`, `marker:color(…)`,
a ghost or sprite `:tint(…)`, `font:color(…)`. So a colour you *read* — `kin:color()`, `meter:color()` —
passes straight back. Alpha defaults to `255`, and a component outside `0..255` is clamped.

## Missing data returns nil

A read returns `nil`, or an empty table for a list verb, when the data is not available yet: before the
world loads, before a HUD widget streams in, or while a resource is still resolving. Reads never throw a
loading error — the bridge swallows it. Much character-sheet data (meters, food, skills, quests, wounds)
streams in a beat *after* `EnterWorld`, so read it on a timer or subscribe to its [event](event.md).

## Threading

Every `hafen.*` call, every event handler, every timer and every draw callback runs on the client's **UI
thread**. You never need locks, and you must never block: a long-running handler stalls the client, and
the sandbox's instruction watchdog aborts a runaway one.

## Gating: the actions permission

Everything in the API observes except one section, [`hafen.act()`](act.md), which **drives the
character** by sending actions to the server. The same gate covers the per-subsystem write verbs that
do the same thing from their own page: `hafen.speed():current(n)`, `craft:make`, `slot:use`, `slot:res(name)`,
and the kin verbs `hafen.kin():add`, `kin:rename`, `kin:group(g)`, `kin:endKin` and `kin:forget`.

A gated verb runs only if the addon **declared** `"permissions": ["actions"]` in its manifest and the
user enabled the addon — such an addon is disabled by default, and enabling it raises a consent
dialog. An undeclared addon calling one gets an error naming the verb. `hafen.act():enabled()` reports
the grant without throwing.

Writing is not the same as being gated. A verb that changes something **client-local** — a map marker,
a minimap icon flag, a sound, your own window — sends nothing to the server and needs no permission;
its page says so on the group heading. [`hafen.http`](http.md) has a gate of its own, a `network`
host allowlist in the manifest.

## See also

- [data types](types.md) — every snapshot shape the readers return
- [events](event.md) — the bus, and what each event hands your handler
- [`hafen.act`](act.md) — the gated tier, and the permission itself
- [the Position type](world.md#the-position-type) — the one place type every spatial verb takes
- [`hafen.ui`](ui/README.md) — where selectors, widgets and the stylesheet are documented in full

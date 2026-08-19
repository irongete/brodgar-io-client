# API conventions: the shared vocabulary

The rules that hold across the whole `hafen.*` API: how it is spelled, what a read gives back, what a
write costs you. Read this once — every reference page assumes it. The catalogue of things a verb can
be handed — a Gob, a kin, an asset, a selector — is [references](references.md).

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
`hafen.session()` is the collection of the logins the client holds.

**Not every subsystem hangs off `hafen`.** What names one character's state hangs off the
[Session](session.md) that names that character instead, and reads the same way one call further in:
`s:world()`, `s:player()`. Such a section is reached rather than mounted, and everything about it — one
object per session, colon verbs, a closed vocabulary — is the rule above unchanged.

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
as `nil` would otherwise turn the write into a read, silently. The meanings it does carry are
documented on the page that carries each: **undo your layer** (`w:position(nil)`, `w:size(nil)`,
`w:replace(nil)`), **none** (a `tint(nil)`) and **the root screen** (`pag:parent(nil)`). Everywhere
else it is an accident, and there is nothing to undo.

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

## Several logins, one screen

The client can hold more than one account logged in at once, and draws one of them. Each is whole —
connected, ticked, answering the server, with a character and a world of its own — and which one is on
screen changes whenever the player tabs between them.

[`hafen.session()`](session.md) is the collection of those logins, and a **Session** is the object you
name one by. It wraps the **account**, which is what survives a character switch, a relogin and the
session ending: `s:user()` answers for a session that is over, and `s:exists()` is the liveness test.
`hafen.session():current()` is the session on screen, `nil` on the login screen, and a different object
after the player tabs — so take it inside your handler rather than keeping one.

**A Session is also the address.** What is one character's is reached through it — [`s:world()`](world.md),
[`s:player()`](player.md), [`s:kin()`](kin.md) — so a read says which character it is about instead of
meaning whichever is drawn. What belongs to the **screen** rather than to a character stays where it was:
there is one pointer and one scene however many logins are live.

Your own addon is the client's, not a login's: it is loaded once, runs beside every session the client
holds, and nothing of yours is torn down or rebuilt when the screen moves.

## Snapshots vs handles

- **Snapshots** are plain Lua tables, point-in-time copies from the escape-hatch `:info()` readers
  (`gob:info()`, `item:info()`, …). They do **not** update, so re-read rather than caching one across
  ticks. Every snapshot shape is in [data types](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui():window()`, `hafen.timer():every`,
  `hafen.event():on`, …), released for you when the addon is disabled or reloaded. So is every **object**
  a read hands you: it re-resolves rather than holding a value, so one you keep tracks what it names.

## The filter argument

Every enumerating verb — `s:world():gob():list`, `s:kin():list`, `hafen.map():icon():list`,
`s:fight():maneuver():list`, … — takes one optional **filter**, always in the same form:

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
local s = hafen.session():current()
local gobs = s:world():gob()
gobs:list("rabbit")                                        -- name contains "rabbit"
gobs:list(function(g) return (g:health() or 1) < 1 end)    -- injured gobs (a Gob object)
s:kin():list(function(k) return k:online() end)            -- online kin (a Kin object)
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
streams in a beat *after* `SessionEnteredWorld`, so read it on a timer or subscribe to its
[event](event/bus.md).

## Threading

Every `hafen.*` call, every event handler, every timer and every draw callback runs on the client's **UI
thread**. You never need locks, and you must never block: a long-running handler stalls the client, and
the sandbox's instruction watchdog aborts a runaway one.

## The permission model

A verb that **starts an action the player could have performed** is **protected**: it runs only
if **your** addon declared that verb's own permission key in its
[manifest](../runtime.md#the-manifest) and the user enabled it. **A key names the action, not the
character**: the player could have tabbed to any of their logins and performed it there, so one grant
covers every character the client holds. Such an addon is disabled the first time
the client sees it and enabling it raises a consent dialog; one that never declared the key gets an error
naming the verb and the key it needs, before anything is sent.

A key is named `<section>.<verb>` after the section its verb lives on — `gob.click`, `item.transfer` — and a
`<prefix>.*` entry asks for the family under that prefix in one line. There is no key that grants the tier
as a whole.

**A protected verb lives with the thing it changes**, never in a section of its own: walking is on the
character, clicking is on the gob, moving an item is on the item — so the page you look a verb up on is
where you meet the permission, under a heading reading **Write (protected)**, with the key beside the verb.
The catalogue of keys, what the permission does not buy and how to write an addon that acts are in
[permissions](../guides/permissions.md).

Everything else observes, or writes **client-local** only — a map marker, an icon flag, a sound — and
needs no permission, so its group heading says `(unprotected)`. Nor does replacing an action the client
is already sending. [`hafen.http`](http.md) declares separately, a `network` host allowlist in the manifest.

## See also

- [references](references.md) — every kind of thing a verb takes, and how you name one
- [data types](types.md) — every snapshot shape the readers return
- [events](event/bus.md) — the bus, and what each event hands your handler
- [permissions](../guides/permissions.md) — the protected tier in full
- [the Position type](world.md#the-position-type) — the one place type every spatial verb takes

# API conventions: the shared vocabulary

The rules that hold across the whole `hafen.*` API: how it is spelled, what a read gives back, what a
write costs you. Read this once — every reference page assumes it. The catalogue of things a verb can
be handed — a Gob, a kin, an asset, a selector — is [references](references.md), and what a plain table
of numbers looks like is [shapes](shapes.md).

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
thing rather than a wrapper around it: `hafen.timer()` is the collection of your timers,
`hafen.slash()` the collection of your console commands, and `hafen.session()` the collection of the
logins the client holds.

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
so a window is shown with `w:visible(true)` rather than a second verb.

**Arity binds a verb that names a property.** Three families take arguments without being writes:

| Family | What the argument is | Examples |
|---|---|---|
| addressing | which member you want | `coll:get(key)`, `req:header(name)`, `s:world():grid():at(p)` |
| actions | how the doing is done | `item:drop(n)`, `s:world():place(p, angle, button, mods)`, `sound:play(volume)` |
| conversions | what is being converted | `p:offset(dx, dy)`, `p:distance(other)`, `grid:tile(c)` |

### Collections: the noun is the kind, the verb is how many

A set you can address into is reached by the **singular** kind name and hands back a collection
object, never a bare array. The plural belongs to the verb.

| Verb | Gives you |
|---|---|
| `:list(filter)` | a plain array of members, empty rather than `nil` |
| `:count(filter)` | how many |
| `:find(filter)` | the first member that matches, or `nil` |
| `:get(key)` | one member by its key, where the members have keys — a miss is [below](#get-what-a-key-that-names-nothing-answers) |
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

### get: what a key that names nothing answers

`:get(key)` **addresses** a member, so what it does with a key nothing answers to belongs to the
collection and is declared by it:

| A miss gives you | Which collections |
|---|---|
| `nil` | every collection not named below |
| an object, so [`:exists()`](#objects-and-the-snapshot-hatch) is the question | `hafen.session()`, `hafen.sound()`, `s:world():gob()`, `s:kin():get(id)`, `s:actionbar()`, `keybindings():binding()` |
| an error naming the keys there are | `hafen.asset()`, `hafen.font()`, `s:char():attr()`, `hafen.map():overlay()` |

**A collection whose members have no key has no `:get`, and says what to reach for instead.** Two buffs
can share a resource; a marker's only id is one this client mints; a timer is only ever the one you were
handed. So the address that does not exist is a search, and asking for it hands you the verb that is:

```lua
hafen.timer():get(1)
-- hafen.timer() has no verb 'get' — a timer has no key: hafen.timer():after(s, fn) and
-- hafen.timer():every(s, fn) hand you the timer they make, and hafen.timer():list() is every
-- one of yours
```

### Objects, and the snapshot hatch

A read hands back a **live object** rather than a copy. It re-resolves on every call, answers `nil`
once the thing it names is gone, and reports `:exists()`. Objects are interned per addon, so `==` is
the identity test and one works as a table key. A point-in-time copy is what `:info()` gives you, and
nothing else does; every shape it returns is in [data types](types.md).

### nil is an error unless it means something

An explicit `nil` argument raises: arity is the verb, so a value that arrived as `nil` would otherwise turn
the write into a read, silently. Every meaning it does carry is here, and anywhere else it is an accident:

| `nil` means | Where |
|---|---|
| undo your layer, back to the client's own | `w:position(nil)`, `w:size(nil)`, `w:text(nil)`, `w:title(nil)`, `w:replace(nil)`; a [font](font.md) variant's `h:size(nil)` and `h:aa(nil)` |
| end the hold | `slot:pagina(nil)` |
| none | a [vr entity](vr/README.md)'s `:tint(nil)` |
| the root screen | `pag:parent(nil)` |
| everything | a [filter](#the-filter-argument): `coll:list(nil)`, `:count(nil)`, `:find(nil)` |

The bridge separates the two cases by counting arguments, and it is exact for a value you pass
directly, a table field included: `w:size(cfg.width, cfg.height)` with a missing key raises. One gap
is inherent to it: `f(g())` where `g` returns *nothing* arrives as no argument at all and is read as
`f()`. A `g` that returns an explicit `nil` is refused like any other value.

### A number is not a string, and a numeric string is not a number

What is checked is an argument's **type**, never what Lua would convert it to: `s:kin():add(1234)` is refused
because a hearth secret is a string, while `entry:value("42")` is taken and a radio row may be labelled
`"061.8"`, a string that happens to scan as a number being an ordinary string. The refusal names the verb, the
parameter and the conversion you meant — `tostring(n)` one way, `tonumber(s)` the other.

### A table is a value, never named arguments

A table you pass in is **data**: a colour, a coordinate, a document to encode. A thing you build is
constructed bare and configured by chained setters instead of by a table of named arguments, so the
configuration reads in the order it happens and a setter can refuse what it cannot do. The boundary is
deliberate rather than missing: it is why a request carries `req:header(name, value)` rather than an
options table, and it does not reach what a verb *returns* — a `:list()` array and an `:info()` table are
ordinary Lua tables you index normally.

**A document names a file by path; a Lua call takes the handle.** A [stylesheet](ui/style/README.md) is a
document rather than a call, so `{asset = "img/panel.png"}` and `{asset = "fonts/Inter.ttf", size = 12}`
name a file your addon ships and load it through [`hafen.asset()`](asset.md)'s own door, interning to the
very object `:get(path)` hands you. That is the one place a path string stands for a file: everywhere else
— a [sprite](vr/sprites.md), an [object](vr/models.md), a [draw verb](ui/drawing.md) — a path is refused
and the handle is what goes in.

### A retired name says what replaced it, and an unknown one says what exists

A spelling this API has replaced does not read as `nil`. It raises, at the line that wrote it, naming
what to write instead.

A name that was never part of this API raises too, **on an object** — `gob:pozition()` and
`gob.pozition` alike, because a field read and a colon call are the same lookup. The message names the
receiver and the verbs it does answer:

```lua
gob:pozition()   -- gob has no verb 'pozition' — a gob is one thing in the world: it answers :id()
                 -- :exists() :sessions() :info() :position() :facing() :name() … and :distance()
```

An object's vocabulary is **closed**, so a name outside it is a typo and is said to be one. On the
`hafen` table and on a section a miss still reads as plain `nil`, because that is where a feature probe
asks — `if hafen.something then` keeps working.

## Several logins, one screen

The client can hold more than one account logged in at once, and draws one of them. Each is whole —
connected, ticked, answering the server, with a character and a world of its own — and which one is on
screen changes whenever the player tabs between them, and whenever an addon writes it with
[`hafen.session():current(s)`](session.md#write-unprotected).

[`hafen.session()`](session.md) is the collection of those logins, and a **Session** is the object you
name one by. It wraps the **account**, which is what survives a character switch, a relogin and the
session ending: `s:user()` answers for a session that is over, and `s:exists()` is the liveness test.
`hafen.session():current()` is the session on screen, `nil` on the login screen, and a different object
after the screen moves — so take it inside your handler rather than keeping one.

**A Session is also the address.** What is one character's is reached through it — [`s:world()`](world.md),
[`s:player()`](player.md), [`s:kin()`](kin.md) — so a read says which character it is about instead of
meaning whichever is drawn. What belongs to the **screen** rather than to a character stays where it was:
there is one pointer and one scene however many logins are live.

**A namespace can be on both sides.** [`ui`](ui/README.md) is: the client's widgets stand in the tree of the
character they were put up for, so `s:ui():find(selector)` is addressed — while the windows your addon
*builds* are yours, live in a layer above every session, and stay `hafen.ui():window()`. Your window and the
client's window are two different things, and the door you come through says which you mean.
[`store`](store.md) is the other: a character's saved variables are that character's own folder, so
`s:store():get(name)` is addressed, while an account's are your addon's single file and are reached without
naming anyone.

Your own addon is the client's, not a login's: it is loaded once, runs beside every session the client
holds, and nothing of yours is torn down or rebuilt when the screen moves.

## Snapshots vs handles

- **Snapshots** are plain Lua tables, point-in-time copies from the escape-hatch `:info()` readers
  (`gob:info()`, `item:info()`, …). They do **not** update, so re-read rather than caching one across
  ticks. Every snapshot shape is in [data types](types.md).
- **Handles** are live, bridge-owned proxies with methods (`hafen.ui():window()`, `hafen.timer():every`,
  `hafen.event():on`, …), released for you when the addon is disabled or reloaded. So is every **object**
  a read hands you: it re-resolves rather than holding a value, so one you keep tracks what it names.
  Every one of them is **userdata with a closed vocabulary**: a name it does not answer raises naming what
  it does, nothing can be written onto it — so nothing can delete a handle's own `:cancel()` — and
  `tostring(h)` names the thing, `Timer(every 5s)`, `Options(video)`, `Sub(GobAdded)`.
- **A table the bridge owns and you write into** is the third kind, and
  [`hafen.store():get(name)`](store.md#read-and-write) is where you meet it. It is neither a copy nor a
  proxy: it is the table that goes to disk, so assigning into it is the whole of saving, and it is the one
  place in this API where a typo on a key is silent — and then persisted.

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
work. The entry your predicate receives is always the **object**, never a snapshot: read it with its own
verbs. A member whose name has simply **not arrived yet** does not match and does not spoil the call; a
**mistake** inside your predicate is not that, and raises out of the verb that called it.

```lua
local s = hafen.session():current()
local gobs = s:world():gob()
gobs:list("rabbit")                                        -- name contains "rabbit"
gobs:list(function(g) return (g:health() or 1) < 1 end)    -- injured gobs (a Gob object)
hafen.map():marker():list(function(m) return m:type() == "player" end)   -- a Marker object
```

## Missing data returns nil

A read returns `nil`, or an empty table for a list verb, when the data is not available yet: before the
world loads, before a HUD widget streams in, or while a resource is still resolving. Reads never throw a
loading error — the bridge swallows it. Much character-sheet data (meters, food, skills, quests, wounds)
streams in a beat *after* `SessionEnteredWorld`, so read it on a timer or subscribe to its
[event](event/bus.md).

## Threading

Every `hafen.*` call, every event handler, every timer and every draw callback runs on the client's **UI
thread**, with one exception: a handler on the [inbound message stream](event/streams.md) runs on the
thread that applies the server update, holding the very lock the UI thread takes to tick and to draw. In
both places yours is the only Lua running and the frame is waiting on it. You never need locks, and you
must never block: a long-running handler stalls the client, and the sandbox's instruction watchdog
aborts a runaway one.

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
- [shapes](shapes.md) — what a plain table of numbers looks like: places, sizes, colours, units
- [data types](types.md) — every snapshot shape the readers return
- [events](event/bus.md) — the bus, and what each event hands your handler
- [permissions](../guides/permissions.md) — the protected tier in full

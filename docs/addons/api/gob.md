# Gob: game objects

A **Gob** is one thing in the world — a tree, a boulder, an animal, another player's body. A gob lives in a
world, so that is where you address one: [`s:world():gob()`](world.md#objects) is the collection of what one
of your characters can see, `:get(id)` names one by id, and the rest of that collection finds the ones you do
not have an id for.

```lua
local s = hafen.session():current()
local tree = s and s:world():gob():nearest("terobjs/tree")
if tree then
  hafen.log():write(string.format("%s is %.1f away", tree:name(), tree:distance()))
end
```

A Gob wraps **an id and the session you read it through**. Every method re-resolves the object against that
character's live object cache, so a handle you keep in a variable is always fresh: it tracks a gob as it
moves, and its methods return `nil` once the gob is gone. Nothing is cached and nothing goes stale — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

`:get(id)` always returns a Gob, even for an id that character has not loaded or that never existed. That is
what lets you anchor to a gob before it streams in; `:exists()` is the liveness test. A non-number argument
raises an error.

## Getting a Gob

| Expression | Returns |
|---|---|
| `s:world():gob():get(id)` | the Gob for that id, as that character sees it — never `nil` |
| `s:player():gob()` | that character's own Gob, or `nil` before its session is in the world — see [`session:player`](player.md) |
| `s:world():gob():list(filter)` | an array of Gobs |
| `s:world():gob():nearest(filter)` | the nearest Gob to that character, or `nil` |
| `s:world():gob():within(radius, filter)` | an array of Gobs |
| a `GobAdded` or `GobRemoved` handler | the Gob that spawned or despawned, read through the session that saw it — see [events](event/bus.md#world) |
| `member:gob()` on a [party](party.md) member, `target:gob()` on the [combat](fight.md) target | that creature's Gob |

## Read

Every method answers `nil` once the gob is gone, except `:id()` and `:exists()`, which always answer.
None of them throws.

| Method | Returns | Description |
|---|---|---|
| `gob:id()` | number | the gob id — answers even after the gob is gone |
| `gob:exists()` | bool | whether the gob is currently loaded |
| `gob:position()` | [Position](world.md#the-position-type) \| nil | where it is: a place you can offset, measure and save |
| `gob:facing()` | number \| nil | facing angle, radians |
| `gob:name()` | string \| nil | resource identity, not a display name |
| `gob:health()` | number \| nil | remaining object integrity, `0..1`, where `1` is undamaged |
| `gob:moving()` | bool \| nil | whether it is moving |
| `gob:speed()` | number \| nil | movement speed, `nil` when it is not moving |
| `gob:speech()` | string \| nil | the floating speech text above it |
| `gob:icon()` | string \| nil | minimap icon category name |
| `gob:isPlayer()` | bool \| nil | whether it is a player body |
| `gob:kin()` | [`Kin`](kin.md) \| nil | the kin standing here, if this gob is one of your kin |
| `gob:distance(other)` | number \| nil | world distance to `other`, a Gob read through the same session; defaults to that character |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:isPlayer()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

`gob:distance(other)` measures inside **one** character's frame. Two sessions' coordinates are each relative
to where that session logged in, so there is no distance between a gob read through one and a gob read
through another, and handing it one raises saying so. Read both through the same session.

## Write (protected)

### `gob:click(button, mods)`

Click the object — exactly the click a left- or right-click on it sends, so the server sees what it would
have seen from the player. Returns the Gob, so a click chains. It needs the `gob.click`
[permission key](../guides/permissions.md) declared in your manifest; without it the call raises an error
naming that key, before anything is sent.

`button` is optional and defaults to `1` (left: select, interact); `3` is right, the one that opens the
[radial menu](flowermenu.md). `mods` is optional and defaults to `0`: Shift = 1, Ctrl = 2, Alt = 4, added
together. It aims at the **whole object** rather than at a part of it, so a composite body part or a
specific sub-mesh is not addressable.

Unlike every read here, a gob that is **gone raises** — as does one that has no position yet, and a call
made before that session is in the world. A click is a message about one specific object, and there is
nothing honest to send about an object that has left; nothing goes out in either case.

It is the **drawn** character's click. A gob read through a session that is not on screen raises naming
`hafen.session():current()`: walking is the whole of what a character you are not looking at will take, and
interacting with an object is its own business. See [`move`](player.md#write-protected).

## Size (unprotected)

`gob:scale(k)` draws the object `k` times its size — the herb you keep walking past, the boar you want to
see coming, the cupboard you are lining up. It is the same read/write pair every
[thing you stand in the world](vr/README.md) answers, so one number is the whole of it.

| Method | Returns | Description |
|---|---|---|
| `gob:scale()` | number \| nil | how big it is drawn; `1` for an object nobody resized |
| `gob:scale(k)` | the Gob | draw it `k` times its size |

```lua
local boar = hafen.session():current():world():gob():nearest("kritter/boar")
if boar then
  boar:scale(2)          -- twice the size, and it hands the Gob back, so this chains
  boar:scale(1)          -- back to the size the game draws it at
end
```

Both halves answer for whichever session you read the gob through, the drawn one or not: a size set on a
character's world is applied to the object it names there, and is already in place when you tab to it.

It is **client-local and purely visual**, on the same footing as an [overlay](overlay.md): only you see
it, the size is applied in place so the object's feet stay where they were, and it still turns, moves
and takes a click exactly as it did — the pick follows the drawn size. Nothing about what the object
*is* changes: its footprint, what it collides with and what a click sends are the game's, untouched. A
few special resource types reset their own transform — the same ones that ignore a ghost's rotation —
and those ignore scale too.

`k` must be a number greater than zero, and finite. `0` collapses the object to a point and a negative one
turns it inside out, so both raise naming the rule; `gob:scale(1)` is the original size and leaves nothing
behind. Once the gob is gone the read answers `nil` and a write does nothing, bar the click above.

> **The size ends with the loaded object.** Walk far enough away for it to unload and it comes back the
> size the game draws it at. Re-apply it from [`GobAdded`](event/bus.md#world) if you want it kept — and a
> `:reload` or a disable puts back everything you resized, so nothing is left distorted behind you.

## Overlays

Everything drawn at a gob — the game's own, the labels and painters you attach, and whatever you have
[stood in the world](vr/README.md) anchored to it — is the collection
[`gob:overlay()`](overlay.md), and it is unprotected.

An overlay is **drawn**, and the client draws one scene: a session it is not drawing has no scene for one to
appear in, so a gob read through any other session raises naming `hafen.session():current()`.

## Kin

`gob:kin()` answers the [`Kin`](kin.md) this gob belongs to, and `kin:gob()` goes back the other way.

```lua
local g = hafen.session():current():world():gob():nearest(function(g) return g:isPlayer() end)
local k = g and g:kin()
hafen.log():write(k and ("that is " .. k:name()) or "nobody you know")
```

The link is **server-side**: the game marks a kinned player's gob for you, so `gob:kin()` is a single
attribute read and never guesses from a name. A kin's **hearth fire** carries the mark too, so
`gob:kin()` answers on that as well.

> `nil` from `gob:kin()` is ambiguous. The player may not be on your roster, or the gob may not be a
> player at all — you cannot tell which.

## Identity

**Within one session, two Gobs for the same id are the same object**, so equality and table keys work
directly:

```lua
local gobs = hafen.session():current():world():gob()
gobs:get(4711) == gobs:get(4711)                          --> true
hafen.session():current():player():gob() == gobs:get(myId) --> true

local seen = {}
for _, g in ipairs(gobs:list()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

**Across two sessions the same id is two Gobs, and `gob:id()` is what crosses them.** A gob id comes from the
**server** and names one object in every character that has loaded it — but each character holds its own copy,
placed against its own map, so `s:world():gob():get(id)` and `other:world():gob():get(id)` are two handles
that answer about the same thing from two points of view. Compare `:id()` when you need to know it is the same
object; compare the handles when you need to know it is the same *reading*.

```lua
local a, b = hafen.session():get("main"), hafen.session():get("alt")
local ga, gb = a:world():gob():get(4711), b:world():gob():get(4711)
ga == gb                                      --> false: two sessions, two readings
ga:id() == gb:id()                            --> true: one object
ga:exists() ~= gb:exists()                    --> quite possibly: only one of them may see it
```

One object per `(session, id)` pair is the only arrangement whose two equalities cannot disagree: Lua table
keys compare objects directly and ignore `__eq`, so a single handle with a cleverer `==` would have counted
one gob twice as a key while claiming to be one.

Identity is also per addon: your Gob objects are yours, never shared with another addon.

A Gob object is immutable: `gob.foo = 1` raises an error, and `gob.position` is the method itself, so call
it with a colon: `gob:position()`. `tostring(gob)` gives `Gob(<id>)` — the id alone, because that is the half
that means the same thing everywhere.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id: `me:overlay():add("tag"):text("here")`,
`s:player():hand():use(tree)`, `hafen.vr():sprite():add(icon, me):offset(0, 0, 18)`.

## See also

- [`session:world`](world.md) — finding the gobs you want to read
- [Overlay](overlay.md) — everything drawn at a gob, and the labels and painters you add
- [`hafen.kin`](kin.md) — the roster side of `gob:kin()`
- [`session:player`](player.md#write-protected) — walking to a gob, and the cursor you aim at one
- [`GobInfo`](types.md#gobinfo) — the shape `:info()` returns
- [events](event/bus.md#world) — reacting to gobs appearing and leaving instead of polling

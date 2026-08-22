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

A Gob wraps **the gob id and nothing else**, because a gob id comes from the **server** and names one object
however many of your characters are looking at it. Every method re-resolves the object against a live object
cache, so a handle you keep in a variable is always fresh: it tracks a gob as it moves, and its methods return
`nil` once the gob is gone. Nothing is cached and nothing goes stale — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

`:get(id)` always returns a Gob, even for an id no character has loaded or that never existed. That is what
lets you anchor to a gob before it streams in; `:exists()` is the liveness test. A non-number argument raises
an error.

## Which character does the reading

Each of your characters holds its own copy of the object, placed against its own map, so a read still has to
be computed by one of them. **The character on screen does it when it can see the object, and otherwise
whichever of your characters can** — one rule, for every read on this page. With one character logged in it
is that character, and [`gob:sessions()`](#gobsessions) is how you see the choice being made.

The answers do not depend on which one it was. `:name()`, `:health()` and the rest read the **server's**
object, and `:position()` hands back a [Position](position.md) anchored on a **server** grid
id, so two characters looking at one tree compute the same place out of two different frames.

**The two writes go the other way**: [`gob:scale(k)`](#size-unprotected) and
[`gob:overlay()`](overlay.md)`:add(key)` change how the object *looks*, and one object looks one way — so
they are written to every character that can see it, not to the one that would have done a read.

### `gob:sessions()`

The [sessions](session.md) that can see this object right now, as an array, in the order they logged in.
Unprotected.

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
for _, s in ipairs(tree:sessions()) do
  hafen.log():write(s:user() .. " can see it")
end
```

It is a **live read**: the object caches are asked at the moment of the call and nothing is remembered, so a
character that logs out is simply not in the next answer. An object none of your characters can see gives the
**empty array**, never `nil` — which is what it gives inside a [`GobRemoved`](event/bus.md#world) handler.

## Getting a Gob

| Expression | Returns |
|---|---|
| `s:world():gob():get(id)` | the Gob for that id — never `nil` |
| `s:player():gob()` | that character's own Gob, or `nil` before its session is in the world — see [`session:player`](player.md) |
| `s:world():gob():list(filter)` | an array of Gobs |
| `s:world():gob():nearest(filter)` | the nearest Gob to that character, or `nil` |
| `s:world():gob():within(radius, filter)` | an array of Gobs |
| a `GobAdded` or `GobRemoved` handler | the Gob that spawned or despawned — see [events](event/bus.md#world) |
| `member:gob()` on a [party](party.md) member, `target:gob()` on the [combat](fight.md) target | that creature's Gob |

## Read

Every method answers `nil` once the gob is gone, except `:id()`, `:exists()` and `:sessions()`, which always
answer. None of them throws.

| Method | Returns | Description |
|---|---|---|
| `gob:id()` | number | the gob id — answers even after the gob is gone |
| `gob:exists()` | bool | whether any of your characters has it loaded |
| `gob:sessions()` | collection | which of them — see [above](#gobsessions) |
| `gob:position()` | [Position](position.md) \| nil | where it is: a place you can offset, measure and save |
| `gob:facing()` | number \| nil | facing angle, radians |
| `gob:name()` | string \| nil | resource identity, not a display name |
| `gob:health()` | number \| nil | remaining object integrity, `0..1`, where `1` is undamaged |
| `gob:moving()` | bool \| nil | whether it is moving |
| `gob:speed()` | number \| nil | movement speed, `nil` when it is not moving |
| `gob:speech()` | string \| nil | the floating speech text above it |
| `gob:icon()` | string \| nil | minimap icon category name |
| `gob:player()` | bool \| nil | whether it is a player body |
| `gob:kin()` | [`Kin`](kin.md) \| nil | the kin standing here, if the reading character has them on its roster |
| `gob:party()` | [`PartyMember`](party.md) \| nil | the party member standing here, in the party of the character that read it |
| `gob:distance(other)` | number \| nil | world distance to another Gob; defaults to the reading character |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:player()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

`gob:distance(other)` measures inside **one** character's world. Each character's coordinates are relative to
where it logged in, so a pair is measured by a character that can see both, and two objects no single
character of yours can see together answer `nil`.

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

The size is written on the **object**, so every character that can see the boar sees the same boar: tab
between two standing together and it is the size you set on both, **and so does one that walks up to it
afterwards**. The write is not a list of who is looking now — it is recorded against the object, and a
character that loads it later draws it the size you asked for.

It is **client-local and purely visual**, on the same footing as an [overlay](overlay.md): only you see
it, the size is applied in place so the object's feet stay where they were, and it still turns, moves
and takes a click exactly as it did — the pick follows the drawn size. Nothing about what the object
*is* changes: its footprint, what it collides with and what a click sends are the game's, untouched. A
few special resource types reset their own transform — the same ones that ignore a ghost's rotation —
and those ignore scale too.

`k` must be a number greater than zero, and finite. `0` collapses the object to a point and a negative one
turns it inside out, so both raise naming the rule; `gob:scale(1)` is the original size and leaves nothing
behind. Once the gob is gone the read answers `nil` and a write does nothing.

> **The size ends with the object, not with a copy of it.** It is dropped when the object leaves its
> **last** character's view — the moment [`GobRemoved`](event/bus.md#world) fires — so walking far enough
> away for it to unload and coming back gives the size the game draws it at, while another character still
> having it in view keeps it. Re-apply it from [`GobAdded`](event/bus.md#world) if you want it kept across
> the unload — and a `:reload` or a disable puts back everything you resized, in every character's view,
> so nothing is left distorted behind you anywhere.

## Overlays

Everything drawn at a gob — the game's own, the labels and painters you attach, and whatever you have
[stood in the world](vr/README.md) anchored to it — is the collection
[`gob:overlay()`](overlay.md), and it is unprotected.

An overlay is attached to the **object**, and every character that can see the object draws it: it appears
whichever of them is on screen, and a `:reload` or a disable takes it off all of them.

## Clicking one

Clicking an object is something a **character** does, so it is not on this page: a Gob names the object, and
an object has nobody to send a click as. The verb is
[`s:world():click(gob, button, mods)`](world.md#sworldclickgob-button-mods), on the world of the character
making the gesture — the shape [`s:player():move(p)`](player.md#write-protected) already has.

## Kin

`gob:kin()` answers the [`Kin`](kin.md) this gob belongs to, on the roster of the character that read it, and
`kin:gob()` goes back the other way. A buddy id counts inside one roster, so ask a character you name with
[`s:kin()`](kin.md) when you mean a particular one.

```lua
local g = hafen.session():current():world():gob():nearest(function(g) return g:player() end)
local k = g and g:kin()
hafen.log():write(k and ("that is " .. k:name()) or "nobody you know")
```

The link is **server-side**: the game marks a kinned player's gob for that character, so `gob:kin()` is a
single attribute read and never guesses from a name. A kin's **hearth fire** carries the mark too, so
`gob:kin()` answers on that as well.

> `nil` from `gob:kin()` is ambiguous. The player may not be on that character's roster, or the gob may
> not be a player at all — you cannot tell which.

## Identity

**Two Gobs for the same id are the same object**, however you reached them, so equality and table keys work
directly:

```lua
local a, b = hafen.session():get("main"), hafen.session():get("alt")

a:world():gob():get(4711) == a:world():gob():get(4711)   --> true
a:world():gob():get(4711) == b:world():gob():get(4711)   --> true: one object, one handle
a:player():gob() == a:world():gob():get(myId)            --> true

local seen = {}
for _, g in ipairs(a:world():gob():list()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

That is what lets two characters standing together count one tree once: the handle each of them finds is the
same value, so a set keyed by Gobs is a set of objects rather than a set of viewings. `gob:id()` is the same
identity written as a number, for when you need to put it in a file or a message.

One object per id is also the only arrangement whose two equalities cannot disagree: Lua table keys compare
objects directly and ignore `__eq`, so two handles with a cleverer `==` would have counted one gob twice as a
key while claiming to be one.

Identity is per addon: your Gob objects are yours, never shared with another addon.

A Gob object is immutable: `gob.foo = 1` raises an error, and `gob.position` is the method itself, so call
it with a colon: `gob:position()`. `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id: `me:overlay():add("tag"):text("here")`,
`s:player():hand():use(tree)`, `hafen.vr():sprite():add(icon, me):offset(0, 0, 18)`.

## See also

- [`session:world`](world.md) — finding the gobs you want to read, and clicking one
- [Overlay](overlay.md) — everything drawn at a gob, and the labels and painters you add
- [`session:kin`](kin.md) — the roster side of `gob:kin()`
- [`session:player`](player.md#write-protected) — walking to a gob, and the cursor you aim at one
- [`GobInfo`](types.md#gobinfo) — the shape `:info()` returns
- [events](event/bus.md#world) — reacting to gobs appearing and leaving instead of polling

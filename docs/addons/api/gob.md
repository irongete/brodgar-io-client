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

**Where a gob is, is where it is drawn.** The server names a place a few times a second and the client
interpolates between those messages at frame rate, so every spatial read here — `gob:position()`,
`:distance()`, `:hitbox()`, the ranking `:nearest` and `:within` do, and the coordinate
[`s:world():click(gob)`](world.md#write-protected) sends — takes the drawn point, and moves through a walk
instead of stepping once a message. On ground that has not streamed in it is the last place the server
named, because the drawn point needs the terrain under it.

`:get(id)` always returns a Gob, even for an id no character has loaded or that never existed. That is what
lets you anchor to a gob before it streams in; `:exists()` is the liveness test. An id that is not a
[whole, finite number](conventions.md#a-number-is-finite-and-an-index-is-whole) raises: an unknown id is a
Gob that does not exist yet, while `1.5` is not an id at all.

## Which character does the reading

Each of your characters holds its own copy of the object, placed against its own map, so a read still has to
be computed by one of them. **A Gob answers in the login you asked through** — the character whose door
handed it to you — for every read on this page. `s:world():gob():get(id)` and `s:player():gob()` answer in
`s`; `member:gob()` in the character whose party you read; a `GobAdded` payload in the character the object
came into view for. With one character logged in it is that character, and
[`gob:sessions()`](#gobsessions) says which others can see it.

So `:exists()` is the question "can **that** character see it", and it goes `false` the moment the object
leaves that character's view — even while another of yours is still looking straight at it. Every other read
answers `nil` in the same moment, exactly as it does for an object that has despawned.

The answers do not otherwise depend on which character did the reading. `:name()`, `:health()` and the rest
read the **server's** object, and `:position()` hands back a [Position](position.md) anchored on a **server**
grid id, so two characters looking at one tree compute the same place out of two different frames.

**The writes go the other way**: [`gob:scale(k)`, `gob:visible(b)`, `gob:tint(c)`](look.md) and
[`gob:overlay()`](overlay.md)`:add(key)` change how the object *looks*, and one object looks one way — so
they are written to every character that can see it, not to the one that did the read.

### `gob:sessions()`

The [sessions](session.md) that can see this object right now, as an array, in the order they logged in.
Unprotected.

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
for _, s in ipairs(tree:sessions():list()) do
  hafen.log():write(s:user() .. " can see it")
end
```

It is a **live read**: the object caches are asked at the moment of the call and nothing is remembered, so a
character that logs out is simply not in the next answer. An object none of your characters can see gives the
**empty array**, never `nil` — which is what it gives inside a [`GobRemoved`](event/bus/world.md#world)
handler.

## Getting a Gob

| Expression | Returns |
|---|---|
| `s:world():gob():get(id)` | the Gob for that id — never `nil` |
| `s:player():gob()` | that character's own Gob, or `nil` before its session is in the world — see [`session:player`](player.md) |
| `s:world():gob():list(filter)` | an array of Gobs |
| `s:world():gob():nearest(filter)` | the nearest Gob to that character, or `nil` |
| `s:world():gob():within(radius, filter)` | an array of Gobs |
| a `GobAdded` or `GobRemoved` handler | the Gob that spawned or despawned — see [events](event/bus/world.md#world) |
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
| `gob:sdt()` | number[] \| nil | state bytes the server sent with its resource — [see below](#state) |
| `gob:pose()` | string[] \| nil | the animation poses its composed body is in — [see below](#the-poses-it-is-in) |
| `gob:hitbox()` | Position[][] \| nil | the ground it stands on — [see below](#the-ground-it-stands-on) |
| `gob:info()` | [`GobInfo`](types/world.md#gobinfo) \| nil | a plain snapshot of what is read above, **`hitbox` excepted** |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:player()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

> `:info()` carries no `hitbox` field. A snapshot holds no objects, only numbers and strings, and a
> footprint rebuilt on every `:info()` call would be paid by every sweep that only wanted a name or a
> position. Read `gob:hitbox()` itself when you want the footprint.

`gob:distance(other)` measures inside **one** character's world. Each character's coordinates are relative to
where it logged in, so a pair is measured by a character that can see both, and two objects no single
character of yours can see together answer `nil`.

## State

`gob:sdt()` answers the raw state bytes the **server** sent with the gob's resource — a crop's stage, a
gate's leaf, a stockpile's count — as a 1-based array of `0..255` numbers. What the bytes *mean* belongs
to that resource's own published code, so the client never decodes them: the honest read is the bytes
themselves, and turning one into a name is guessing this API does not do.

```lua
local crop = hafen.session():current():world():gob():nearest("gfx/terobjs/plants/carrot")
local bytes = crop and crop:sdt()
if bytes then
  hafen.log():write(#bytes .. " state byte(s)")
end
```

`gob:sdt()` is `nil` once the gob is gone, and also for a gob whose body is **composed** rather than
drawn from a resource — a player, most notably, which carries no state bytes at all. A resource-drawn
gob the server sent no state for answers the **empty array**, not `nil`: the two are different facts,
and [`gob:info().sdt`](types/world.md#gobinfo) carries whichever the live read does.

Reading it on every frame to catch the moment it moves is a sweep —
[`GobSdtChanged`](event/bus/world.md#the-state-changing) is the edge instead: it fires when the bytes
actually change, once for the object however many of your characters see it, and for the first state a
gob is given as well as every one after.

## The poses it is in

`gob:pose()` answers the **animation poses** in force on a composed body — a player, an animal — as an
array of the pose resources' names.

```lua
local me = hafen.session():current():player():gob()
for _, pose in ipairs(me:pose() or {}) do
  hafen.log():write(pose)                      -- "gfx/borka/idle", "gfx/borka/walking", ...
end
```

**It is the exact counterpart of [`gob:sdt()`](#state), and neither answers for the other's kind.** A body
the client *composes* out of a skeleton and a set of poses has no state bytes; an object *drawn from a
resource* — a tree, a crop, a gate — has bytes and no poses. So a tree answers `nil` here and an array
there, a player the other way round, and between the two verbs every object's drawing state has exactly one
door. `nil` also once the gob is gone. The **empty array** is a composed body whose poses have not arrived
yet, and that is not the same answer as `nil`.

**A one-shot wins while it plays.** The server sends two kinds: the standing set, which is what the body
does until told otherwise, and a *transient* — a swing, a bite, a bow — laid over it for a fixed time. The
client draws the transient in place of the standing set and puts the set back when it ends, so this verb
names **what the eye sees**: the transient while one runs, the standing set otherwise.

Unlike the bytes, a pose **is** a name and this verb hands it to you as one. What that name means is the
pose resource's own business, and the client is not decoding anything to answer: these are the very
resources the server named.

> A pose that has not resolved yet is **left out** rather than reported as a hole — the array is what the
> body is drawing, and a name that arrives a frame later belongs to the next read. So an array can be
> shorter than the set the server sent, for the moment it takes a resource to load.

There is no `GobPoseChanged`: reading it is a live read like [`gob:name()`](#read), and a body that is
animating changes pose often enough that an edge would fire more than a frame does. Read it where you
draw.

## The ground it stands on

`gob:hitbox()` answers the shape the object occupies on the ground — a set of polygons in the world,
rotated by the object's own facing and placed where the object stands. It is an array of polygons, each
an array of [Positions](position.md), **one ring per shape the resource carries and every one of them**:
a tree's trunk, a fence's whole run, a gate's leaf and its post both.

```lua
local wall = hafen.session():current():world():gob():nearest("gfx/terobjs/arch/fencing/wattle")
local box = wall and wall:hitbox()
if box then
  hafen.log():write(#box .. " polygon(s), " .. #box[1] .. " point(s) in the first")
end
```

Every point is a real place in the world: `p:x()`, `p:distance()` and
[`s:world():worldToScreen(p)`](world.md#the-screen-and-the-world) all answer for it, and it sits on the object
rather than at the frame's origin. That is what lets a ring go straight into
[`hafen.virtual():patch()`](virtual/patches.md), which lays it on the terrain itself under whatever stands
there.

`gob:hitbox()` is `nil` once the gob is gone, before its resource has resolved, and for an object none of
the three sources below has a shape for — a decoration, most flooring, anything nothing walks into and
nothing marks the ground under either.

> **Three different facts share this one answer, and the verb does not say which you got.** Usually the
> rings are the resource's **collision** shapes, what the server collides against. But a resource may
> carry no collision shape and still carry a plain **click-box**, and a felled log
> (`gfx/terobjs/log`) is exactly that: nothing stops you walking through one, yet it plainly lies
> somewhere — an 18×4 rectangle, as it happens. And an object may carry no shape of its own at all and be
> sent one **per object by the server**, which is [a building site](#a-shape-the-server-sent). `gob:hitbox()`
> answers all three rather than `nil`, because the question this verb is asked is *where is this thing*, and
> a `nil` there only ever meant "we found nothing to draw a box from". **So never read a shape coming back
> as proof that the object blocks movement.** What the three have in common is the only thing promised:
> world units, turned by the object's facing, placed where it stands.
>
> One ring is left out on purpose — a buildable resource's `build` box, which is the clearance a
> placement ghost checks before you may put one down, not the footprint of the thing that ends up there.

### A shape the server sent

A **building site** — the stakes and the string that stand where a building is going up — has no footprint
of its own to read. Its resource (`gfx/terobjs/consobj`) is the same one for every building in the game, so
the shape cannot live there: what is going up on that spot is sent **with the object**, in the
[state bytes](#state), and the stakes you see are the resource's own published code drawing that shape.

`gob:hitbox()` reads it, so a site answers the outline of the building it will become, and needs nothing
from you that a tree does not.

It is the **one** place this API takes a meaning out of `gob:sdt()`, and it does not break the rule beside
it: what parses those bytes is not the client guessing but the very library the site's own code parses them
with, held here at the version the server serves. Two things follow, and both are facts rather than
apologies. A resource is read this way only when it **declares that library** as its own code's, so nothing
else is decoded on a hunch. And a shape that does not come out of the bytes cleanly is dropped rather than
drawn, because a box in the wrong place is worse than no box.

The ghost itself has the same verb: [`placing:hitbox()`](placing.md#the-footprint) answers in this shape and
these units for the thing on your cursor, so what you are about to place wears the same box as what it
becomes.

```lua
local log = hafen.session():current():world():gob():nearest("gfx/terobjs/log")
local box = log and log:hitbox()   -- not nil: its click-box, not a collision shape it doesn't have
```

> **The footprint is not what `gob:scale(k)` draws.** Scale changes how big the object *looks*; the
> footprint is the game's own and does not move with it — see [Look](look.md#size-unprotected). Walk into
> the boar you doubled and you still collide with the boar's own size.

## How it is drawn

How big the object is drawn, whether it is drawn at all, and what colour is laid over it are the three
writes on [Look](look.md) — `gob:scale(k)`, `gob:visible(b)` and `gob:tint(c)` — and what stands at it is
the collection [`gob:overlay()`](overlay.md). All of them are unprotected, all of them are written on the
object rather than a character, and none of them changes anything on this page: a resized, hidden or
tinted object still answers every read above.

**`gob:visible(false)` withholds the model alone.** What stands *at* the object is not part of it, so a
hidden object goes on drawing your [overlays](overlay.md) and the game's own — its name label, its health
bar — over ground with nothing in it. The click goes through to that ground, which is the verb's whole
meaning; the label does not.

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

**Two Gobs for the same id read through the same character are the same object**, however you reached them,
so equality and table keys work directly:

```lua
local a, b = hafen.session():get("main"), hafen.session():get("alt")

a:world():gob():get(4711) == a:world():gob():get(4711)   --> true
a:player():gob() == a:world():gob():get(myId)            --> true
a:world():gob():get(4711) == b:world():gob():get(4711)   --> false: two characters, two answers

local seen = {}
for _, g in ipairs(a:world():gob():list()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

That is what lets one sweep of one character count a tree once: the handle is the same value wherever that
character reaches it, so a set keyed by Gobs is a set of objects rather than a set of readings. Across two
characters the two handles are two answers about two frames, and **`gob:id()` is what compares them** — the
same identity written as a number, which is also the form to put in a file or a message.

One handle per id per login is also the only arrangement whose two equalities cannot disagree: Lua table keys
compare objects directly and ignore `__eq`, so two handles with a cleverer `==` would have counted one gob
twice as a key while claiming to be one.

Identity is per addon: your Gob objects are yours, never shared with another addon.

A Gob object is immutable: `gob.foo = 1` raises an error, and `gob.position` is the method itself, so call
it with a colon: `gob:position()`. `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id: `me:overlay():add("tag"):text("here")`,
`s:player():hand():use(tree)`, `hafen.virtual():sprite():add(icon, me):offset(0, 0, 18)`.

## See also

- [`session:world`](world.md) — finding the gobs you want to read, and clicking one
- [Look](look.md) — how it is drawn: its size, whether it is drawn, and the colour laid over it
- [Overlay](overlay.md) — everything drawn at a gob, and the labels and painters you add
- [`session:kin`](kin.md) — the roster side of `gob:kin()`
- [`session:player`](player.md#write-protected) — walking to a gob, and the cursor you aim at one
- [`GobInfo`](types/world.md#gobinfo) — the shape `:info()` returns
- [events](event/bus/world.md#world) — reacting to gobs appearing and leaving instead of polling

# hafen.gob: game objects

A **Gob** is one thing in the world — a tree, a boulder, an animal, another player's body. Reach for
`hafen.gob(id)` when you have an id and want to read or act on that one object; to find objects in the
first place, use [`hafen.world`](world.md).

```lua
local tree = hafen.world.nearest("terobjs/tree")
if tree then
  hafen.log(string.format("%s is %.1f away", tree:name(), tree:distance()))
end
```

A Gob wraps **only the id**. Every method re-resolves the object against the client's live object
cache, so a handle you keep in a variable is always fresh: it tracks a gob as it moves, and its methods
return `nil` once the gob is gone. Nothing is cached and nothing goes stale — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

`hafen.gob(id)` always returns a Gob, even for an id that is not loaded or never existed. That is what
lets you anchor to a gob before it streams in; `:exists()` is the liveness test. A non-number argument
raises an error.

## Getting a Gob

| Expression | Returns |
|---|---|
| `hafen.gob(id)` | the Gob for that id — never `nil` |
| `hafen.player():gob()` | your own Gob, or `nil` before you are in the world — see [`hafen.player`](player.md) |
| `hafen.world.gobs(filter)` | an array of Gobs |
| `hafen.world.nearest(filter)` | the nearest Gob, or `nil` |
| `hafen.world.within(radius, filter)` | an array of Gobs |
| a `GobAdded` or `GobRemoved` handler | the Gob that spawned or despawned — see [events](events.md#world) |
| `hafen.gob(m.id)` for a [party](party.md) member `m` | that member's Gob |

## Read

Every method answers `nil` once the gob is gone, except `:id()` and `:exists()`, which always answer.
None of them throws.

| Method | Returns | Description |
|---|---|---|
| `gob:id()` | number | the gob id — answers even after the gob is gone |
| `gob:exists()` | bool | whether the gob is currently loaded |
| `gob:pos()` | `{x, y}` \| nil | world position |
| `gob:facing()` | number \| nil | facing angle, radians |
| `gob:name()` | string \| nil | resource identity, not a display name |
| `gob:health()` | number \| nil | remaining object integrity, `0..1`, where `1` is undamaged |
| `gob:moving()` | bool \| nil | whether it is moving |
| `gob:speed()` | number \| nil | movement speed, `nil` when it is not moving |
| `gob:speech()` | string \| nil | the floating speech text above it |
| `gob:icon()` | string \| nil | minimap icon category name |
| `gob:isplayer()` | bool \| nil | whether it is a player body |
| `gob:kin()` | [`Kin`](kin.md) \| nil | the kin standing here, if this gob is one of your kin |
| `gob:distance(other)` | number \| nil | world distance to `other`, another Gob; defaults to the player |
| `gob:overlay(…)` | see [Overlays](#overlays) | attach something to this gob, or read what is attached |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:isplayer()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

## Overlays

An **overlay** is a thing attached to a game object — the game's own (a fire's flame, a crop's growth
stage) and yours alike. `gob:overlay` is the one way to attach one and the one way to read what is
attached; the arity is the verb, and the key is your own name for it.

| Call | Returns | Description |
|---|---|---|
| `gob:overlay()` | `Overlay[]` \| nil | every overlay on the gob: yours first, then the game's own |
| `gob:overlay(key)` | `Overlay` \| nil | that one |
| `gob:overlay(key, spec)` | `Overlay` | attach it, or replace what that key already named |
| `gob:overlay(key, nil)` | the gob | remove it |

```lua
me:overlay("hp", { text = "hurt", color = {255, 90, 90}, offset = {x = 0, y = -6} })
me:overlay("ring", { draw = function(g, gob, sx, sy) g:frect(sx - 2, sy - 2, 4, 4) end })
me:overlay("mark", { image = hafen.asset("icon.png"), scale = 2, offset = { z = 18 } })
me:overlay("hp", nil)
```

### One spec table, two spaces

The **spec says what to draw**, and it must name exactly **one** of five things. Two of them paint on
the screen at the gob's projected point; three of them stand in the 3D world, anchored to the gob:

| Field | Space | Meaning |
|---|---|---|
| `draw = fn` | screen | `fn(g, gob, sx, sy)` runs every frame at the gob's projected point, just above the head |
| `text = "…"` | screen | a label at that point — drawn by the engine, so it costs no Lua at the draw |
| `image = <asset>` | world | a [`hafen.asset`](asset.md) image standing in the world, like a [sprite](render/sprites.md) |
| `model = <asset>` | world | a [`hafen.asset`](asset.md) glTF mesh, like an [object](render/models.md) |
| `ghost = "<res>"` | world | a game `.res` model, like a [ghost](ghost.md) |

The rest of the table depends on which space you are in:

| Field | Space | Meaning |
|---|---|---|
| `color = {r,g,b[,a]}` | screen | the label's colour (with `text`) |
| `offset = {x =, y =}` | screen | **screen pixels** from the projected anchor point |
| `offset = {x =, y =, z =}` | world | **world units** from the gob, `z` being up — `{z = 18}` floats it overhead |
| `scale`, `alpha`, `tint`, `a` | world | the same look and facing options the world builders take |
| `billboard = true` | world | with `image`: a camera-facing blit instead of an upright quad |

A spec naming none of the five is an error naming them all, and one naming two is an error naming both:
an overlay that draws nothing is never what was meant, and picking a winner by table order is how one of
them silently stops meaning anything. A spec is read **once**, at attach.

`clickable`/`onClick` are **not** overlay properties and raise. The thing under an overlay is the gob,
and clicking a gob is the client's own — [`hafen.act.clickGob`](act.md).

### The verbs on a world overlay

A world-space overlay carries the look and facing verbs its entity already had. They **chain** (each
answers the overlay), they are refused on a screen-space one naming the kinds, and they are quiet no-ops
once the overlay is gone. There is no `:move` — an overlay's position **is** its gob's, and the only
thing you set is the `offset`, by re-attaching under the same key.

| Method | Description |
|---|---|
| `ov:tint(color)` \| `ov:tint(nil)` | colour overlay `{r,g,b[,a]}`, or clear it |
| `ov:alpha(a)` | opacity `0..1` (1 = opaque) |
| `ov:scale(s)` | uniform scale (1 = original size) |
| `ov:rotate(a)` | its **own** facing in radians — independent of the gob's |
| `ov:pos()` | `{x, y, a, scale}` — where it actually is: the gob's live point plus your offset |

```lua
local ov = tree:overlay("mark", { image = icon, offset = { z = 20 } })
ov:tint{ 255, 90, 90 }:alpha(0.7):scale(2)
```

**Keys are per addon.** Two addons using `"tag"` on one gob do not collide, and neither can see the
other's — `gob:overlay()` lists yours and the game's, never a third party's. Attaching the same key
twice leaves **one** overlay, the second spec's.

**The game's own overlays are read-only.** They come back from the same read with `native = true`, keyed
by their **resource name**, and both an attach onto such a key and a remove of one **raise**, naming the
key — never a silent no-op.

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | what it answers to; the resource name for a native one |
| `ov:gob()` | Gob | the gob it hangs on |
| `ov:native()` | bool | is this the game's own rather than yours? |
| `ov:kind()` | string \| nil | `"draw"`/`"text"`/`"image"`/`"model"`/`"ghost"`; `nil` for a native one |
| `ov:res()` | string \| nil | what it is drawn from — the resource name, the asset path; `nil` for a screen-space one, which draws Lua |
| `ov:count()` | number \| nil | how many engine overlays this one entity stands for |
| `ov:exists()` | bool | still attached? |
| `ov:info()` | table \| nil | `{key, native, count, world, res?, kind?}` as a plain snapshot |

`:count()` is there because **a native overlay is a union**. A gob may carry several overlays of one
resource — on a live world 13 of 33 gobs carrying overlays did — and the resource name is the only part
of one a name can address, so they collapse to a single Overlay and the multiplicity is published here
instead of lost. Yours always count 1. (`gob:info().overlays` is the raw list of resource names, one entry
per engine overlay, for when you want the uncollapsed view — and, like every other
[`GobInfo`](types.md#gobinfo) field, it is **absent** rather than empty when the gob carries none.)

An Overlay object is **interned on the key**, so `gob:overlay(key)` hands back the same object every
time and a replace leaves the handle you were holding naming the *new* record. `:exists()` goes false
when the overlay is removed, or when its gob is gone.

**An overlay dies with its gob.** The record lives on the game object, so a felled tree takes yours with
it and nothing is kept in case it comes back — a gob that returns is bare, and re-attaching is your own
call from [`GobAdded`](events.md#world). A `:reload` or a disable likewise removes every overlay you
attached and leaves the game's untouched. For a **world-space** overlay that is not just bookkeeping:
its visual is a client-only object of its own, and it is destroyed with the gob rather than left
floating where the target used to stand.

Both halves of this read are also **events**:
[`GobOverlayAdded`/`GobOverlayRemoved`](events.md#overlays-coming-and-going) fire for what you attach and
for what the game attaches, so you can watch a gob become decorated instead of polling it.

> There is no filter form. "Every player gets a label" is a [`GobAdded`](events.md#world) handler plus a
> loop over [`hafen.world.gobs()`](world.md) — you name the gob, so nothing is searched per frame.

> A `draw` callback runs inside the client's draw pass, which is **outside** the per-tick CPU budget.
> Keep it short; a `text` overlay never enters Lua at all and is the cheaper way to put a label up.

## Kin

`gob:kin()` answers the [`Kin`](kin.md) this gob belongs to, and `kin:gob()` goes back the other way.

```lua
local g = hafen.world.nearest(function(g) return g:isplayer() end)
local k = g and g:kin()
hafen.log(k and ("that is " .. k:name()) or "nobody you know")
```

The link is **server-side**: the game marks a kinned player's gob for you, so `gob:kin()` is a single
attribute read and never guesses from a name. A kin's **hearth fire** carries the mark too, so
`gob:kin()` answers on that as well.

> `nil` from `gob:kin()` is ambiguous. The player may not be on your roster, or the gob may not be a
> player at all — you cannot tell which.

## Identity

Two Gobs for the same id are **the same object**, so equality and table keys work directly:

```lua
hafen.gob(4711) == hafen.gob(4711)            --> true
hafen.player():gob() == hafen.gob(myId)       --> true

local seen = {}
for _, g in ipairs(hafen.world.gobs()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

Identity is per addon: your Gob objects are yours, never shared with another addon.

A Gob is read-only. `gob.foo = 1` raises an error, and `gob.pos` is the method itself, so call it with
a colon: `gob:pos()`. `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id:

```lua
hafen.act.clickGob(tree, 3)
me:overlay("tag", { text = "here" })
me:overlay("mark", { image = icon, offset = { z = 18 } })
```

## See also

- [`hafen.world`](world.md) — finding the gobs you want to read
- [`hafen.kin`](kin.md) — the roster side of `gob:kin()`
- [`hafen.act`](act.md) — clicking a gob and walking to it
- [`GobInfo`](types.md#gobinfo) — the shape `:info()` returns
- [events](events.md#world) — reacting to gobs appearing and leaving instead of polling

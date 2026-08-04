# Gob: game objects

A **Gob** is one thing in the world — a tree, a boulder, an animal, another player's body. A gob lives in
the world, so that is where you address one: [`hafen.world():gob()`](world.md#objects) is the collection,
`:get(id)` names one by id, and the rest of that collection finds the ones you do not have an id for.

```lua
local tree = hafen.world():gob():nearest("terobjs/tree")
if tree then
  hafen.log():write(string.format("%s is %.1f away", tree:name(), tree:distance()))
end
```

A Gob wraps **only the id**. Every method re-resolves the object against the client's live object
cache, so a handle you keep in a variable is always fresh: it tracks a gob as it moves, and its methods
return `nil` once the gob is gone. Nothing is cached and nothing goes stale — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

`:get(id)` always returns a Gob, even for an id that is not loaded or never existed. That is what lets you
anchor to a gob before it streams in; `:exists()` is the liveness test. A non-number argument raises an
error.

## Getting a Gob

| Expression | Returns |
|---|---|
| `hafen.world():gob():get(id)` | the Gob for that id — never `nil` |
| `hafen.player():gob()` | your own Gob, or `nil` before you are in the world — see [`hafen.player`](player.md) |
| `hafen.world():gob():list(filter)` | an array of Gobs |
| `hafen.world():gob():nearest(filter)` | the nearest Gob, or `nil` |
| `hafen.world():gob():within(radius, filter)` | an array of Gobs |
| a `GobAdded` or `GobRemoved` handler | the Gob that spawned or despawned — see [events](event.md#world) |
| `hafen.world():gob():get(m.id)` for a [party](party.md) member `m` | that member's Gob |

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
| `gob:distance(other)` | number \| nil | world distance to `other`, another Gob; defaults to the player |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:isPlayer()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

## Overlays

An **overlay** is a thing attached to a game object — the game's own (a fire's flame, a crop's growth
stage) and yours alike. `gob:overlay()` is the **collection** of everything attached to one gob: the one
way to attach, the one way to read what is attached, and the key is your own name for it.

It is **ungated**, the attach included: what you hang on a gob is your own drawing, and it changes nothing
the server, the client or another addon owns — the same footing as
[a HUD overlay](ui/custom.md#overlays).

| Call | Returns | Description |
|---|---|---|
| `gob:overlay():list(filter)` | `Overlay[]` | every overlay on the gob: yours first, then the game's own |
| `gob:overlay():count(filter)` | number | how many |
| `gob:overlay():get(key)` | `Overlay` \| nil | that one |
| `gob:overlay():find(filter)` | `Overlay` \| nil | the first one matching |
| `gob:overlay():add(key)` | `Overlay` | attach a bare one, or replace what that key already named |
| `gob:overlay():remove(key)` | the collection | remove it |

A string `filter` matches the **key** as a substring. Once the gob is gone the collection is empty, `:get`
answers `nil` and `:remove` is inert — an overlay died with the gob, so there is nothing left to remove —
while `:add` raises, because there is nothing left to attach it to.

```lua
me:overlay():add("hp"):text("hurt"):color(255, 90, 90):offset(0, -6)
me:overlay():add("ring"):draw(function(g, gob, sx, sy) g:frect(sx - 2, sy - 2, 4, 4) end)
me:overlay():add("mark"):image(hafen.asset("icon.png")):scale(2):offset(0, 0, 18)
me:overlay():remove("hp")
```

### What it draws: one overlay, two spaces

`:add(key)` attaches a **bare** overlay, and the setters below say what it draws. Each answers the overlay,
so one statement configures the whole thing — and until it names a kind it draws nothing, so a
half-configured overlay never paints. It says exactly **one** thing: a second, different kind raises naming
the first, because picking a winner is how one of them silently stops meaning anything.

| Setter | Space | Meaning |
|---|---|---|
| `ov:draw(fn)` | screen | `fn(g, gob, sx, sy)` runs every frame at the gob's projected point, just above the head |
| `ov:text(s)` | screen | a label at that point — drawn by the engine, so it costs no Lua at the draw |
| `ov:image(asset)` | world | a [`hafen.asset`](asset.md) image standing in the world, like a [sprite](render/sprites.md) |
| `ov:model(asset)` | world | a [`hafen.asset`](asset.md) glTF mesh, like an [object](render/models.md) |
| `ov:ghost(res)` | world | a game `.res` model, like a [ghost](ghost.md) |

The rest depends on which space you are in. Every one of them has a bare read of the same name, so what you
wrote is what you can read back, and the configuration is the overlay's own state: `ov:text("healed")`
relabels a live overlay rather than replacing it.

| Setter | Space | Meaning |
|---|---|---|
| `ov:color(r, g, b, a)` | screen | the label's colour, `0..255`; `a` is optional |
| `ov:offset(x, y)` | screen | **screen pixels** from the projected anchor point |
| `ov:offset(x, y, z)` | world | **world units** from the gob, `z` being up — `18` floats it overhead |
| `ov:scale(s)` `ov:alpha(a)` `ov:tint(c)` `ov:rotate(a)` | world | the look and facing, live — see below |
| `ov:billboard(b)` | world | with an image: a camera-facing blit instead of an upright quad |
| `ov:spawnData(sdt)` | world | with a ghost: spawn-data bytes picking a resource variant, as [`hafen.ghost.new`](ghost.md) takes |

`ov:billboard` and `ov:spawnData` choose how the visual is **built**, so setting one after the overlay
already stands rebuilds it; set them in the same statement as the kind and it is built once. There is no
`ov:clickable` and no `ov:onClick`: the thing under an overlay is the gob, and clicking a gob is the
client's own — [`hafen.act():clickGob`](act.md).

### The verbs on a world overlay

A world-space overlay carries the look and facing its entity already had, live: setting one moves or
recolours the thing where it stands. The setters **chain** — each answers the overlay — and each reads back
bare. All of them are refused on a screen-space overlay, naming the kinds, and they are quiet no-ops once
the overlay is gone. There is no `:move`: an overlay's position **is** its gob's, and what you set is where
it sits relative to the gob.

| Method | Description |
|---|---|
| `ov:tint(color)` \| `ov:tint(nil)` | colour overlay, positional `0..255` components, or `nil` to clear |
| `ov:alpha(a)` | opacity `0..1` (1 = opaque) |
| `ov:scale(s)` | uniform scale (1 = original size) |
| `ov:rotate(a)` | its **own** facing in radians — independent of the gob's |
| `ov:offset(x, y, z)` | where it sits relative to the gob, in world units, `z` up |
| `ov:position()` | [Position](world.md#the-position-type) \| nil — where it actually is: the gob's live point plus your offset |

```lua
local ov = tree:overlay():add("mark"):image(icon):offset(0, 0, 20)
ov:tint(255, 90, 90):alpha(0.7):scale(2)
```

**Keys are per addon.** Two addons using `"tag"` on one gob do not collide, and neither can see the
other's — the collection answers yours and the game's, never a third party's. `:add` on a key that is
already there **replaces** it, leaving **one** overlay.

**The game's own overlays are read-only.** They come back from the same read with `native = true`, keyed
by their **resource name**. An `:add` onto such a key **raises**, so does a `:remove` of one, and so does
each of the verbs above — always naming the key, never a silent no-op.

An attach raises in two more places, both of them about *when*. A gob the client cannot draw yet takes no
overlay at all: attach from [`GobAdded`](event.md#world) or a timer instead. A **world-space** kind needs
the 3D scene, so it raises while there is no map view — that is what naming one before you are in the
world looks like. Both errors name what to do instead. A setter that raises leaves the overlay exactly as
it was, drawing exactly what it drew before: the new visual is milled before the old one is let go, and a
kind that never landed leaves a bare overlay, which draws nothing.

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | what it answers to; the resource name for a native one |
| `ov:gob()` | Gob | the gob it hangs on |
| `ov:native()` | bool | is this the game's own rather than yours? |
| `ov:kind()` | string \| nil | `"draw"`/`"text"`/`"image"`/`"model"`/`"ghost"`; `nil` for a native one, and for one that has not said yet |
| `ov:res()` | string \| nil | what it is drawn from — the resource name, the asset path; `nil` for a screen-space one, which draws Lua |
| `ov:count()` | number \| nil | how many engine overlays this one entity stands for |
| `ov:exists()` | bool | still attached? |
| `ov:info()` | table \| nil | a plain snapshot; the shape follows what the overlay is |

`:count()` is there because **a native overlay is a union**. A gob may carry several overlays of one
resource, and the resource name is the only part of one a name can address, so they collapse to a single
Overlay and the multiplicity is published here instead of lost. Yours always count 1.
(`gob:info().overlays` is the raw list of resource names, one entry per engine overlay, for when you want
the uncollapsed view — and, like every other [`GobInfo`](types.md#gobinfo) field, it is **absent** rather
than empty when the gob carries none.)

`:info()` is the snapshot escape hatch, and it hands back **two shapes**. Both carry `key`, `native` and
`count`. **Yours** adds `kind` and `world` — `world` is `true` for `image`, `model` and `ghost`, `false`
for `draw` and `text`, and both are absent while the overlay has not said what it draws — plus `res` when
what it stands is named by a resource or an asset path. **A native one** adds `res`, which is the key
itself, and carries neither `kind` nor `world`: the game's overlays say only that they are the game's.

An Overlay object is **interned on the key**, so `gob:overlay():get(key)` hands back the same object every
time and a replace leaves the handle you were holding naming the *new* record. `:exists()` goes false
when the overlay is removed, or when its gob is gone. `tostring(ov)` gives `Overlay(<key>@<gobid>)`, with
a `*` in front of the key on the game's own.

**An overlay dies with its gob.** The record lives on the game object, so a felled tree takes yours with
it and nothing is kept in case it comes back — a gob that returns is bare, and re-attaching is your own
call from [`GobAdded`](event.md#world). A `:reload` or a disable likewise removes every overlay you
attached and leaves the game's untouched. For a **world-space** overlay that is not just bookkeeping:
its visual is a client-only object of its own, and it is destroyed with the gob rather than left
floating where the target used to stand.

Both halves of this read are also **events**:
[`GobOverlayAdded`/`GobOverlayRemoved`](event.md#overlays-coming-and-going) fire for what you attach and
for what the game attaches, so you can watch a gob become decorated instead of polling it.

> There is no filter form. "Every player gets a label" is a [`GobAdded`](event.md#world) handler plus a
> loop over [`hafen.world():gob():list()`](world.md#objects) — you name the gob, so nothing is searched
> per frame.

> A `draw` callback runs inside the client's draw pass, which is **outside** the per-tick CPU budget.
> Keep it short; a `text` overlay never enters Lua at all and is the cheaper way to put a label up.

## Kin

`gob:kin()` answers the [`Kin`](kin.md) this gob belongs to, and `kin:gob()` goes back the other way.

```lua
local g = hafen.world():gob():nearest(function(g) return g:isPlayer() end)
local k = g and g:kin()
hafen.log():write(k and ("that is " .. k:name()) or "nobody you know")
```

The link is **server-side**: the game marks a kinned player's gob for you, so `gob:kin()` is a single
attribute read and never guesses from a name. A kin's **hearth fire** carries the mark too, so
`gob:kin()` answers on that as well.

> `nil` from `gob:kin()` is ambiguous. The player may not be on your roster, or the gob may not be a
> player at all — you cannot tell which.

## Identity

Two Gobs for the same id are **the same object**, so equality and table keys work directly:

```lua
local gobs = hafen.world():gob()
gobs:get(4711) == gobs:get(4711)              --> true
hafen.player():gob() == gobs:get(myId)        --> true

local seen = {}
for _, g in ipairs(gobs:list()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

Identity is per addon: your Gob objects are yours, never shared with another addon.

A Gob is read-only. `gob.foo = 1` raises an error, and `gob.position` is the method itself, so call it
with a colon: `gob:position()`. `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id:

```lua
hafen.act():clickGob(tree, 3)
me:overlay():add("tag"):text("here")
me:overlay():add("mark"):image(icon):offset(0, 0, 18)
```

## See also

- [`hafen.world`](world.md) — finding the gobs you want to read
- [`hafen.kin`](kin.md) — the roster side of `gob:kin()`
- [`hafen.act`](act.md) — clicking a gob and walking to it
- [`GobInfo`](types.md#gobinfo) — the shape `:info()` returns
- [events](event.md#world) — reacting to gobs appearing and leaving instead of polling

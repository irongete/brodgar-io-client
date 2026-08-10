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
| `gob:distance(other)` | number \| nil | world distance to `other`, another Gob; defaults to the player |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

> `gob:name()` is the **type** resource — `"gfx/borka/body"` for any player body — not a character's
> display name. Display names are not available for arbitrary gobs; `gob:isPlayer()` is the test for a
> player body.

`:info()` is the snapshot escape hatch: use it for logging, serialising, or passing gob data around as
data, since [`hafen.json`](json.md) can encode a plain table and a Gob object cannot. For reading,
prefer the methods — they are always fresh, while a snapshot is frozen at the moment you took it.

## Write (protected: `actions`)

### `gob:click(button, mods)`

Click the object — exactly the click a left- or right-click on it sends, so the server sees what it would
have seen from the player. Returns the Gob, so a click chains.

`button` is optional and defaults to `1` (left: select, interact); `3` is right, the one that opens the
[radial menu](flowermenu.md). `mods` is optional and defaults to `0`: Shift = 1, Ctrl = 2, Alt = 4, added
together. It aims at the **whole object** rather than at a part of it, so a composite body part or a
specific sub-mesh is not addressable.

Unlike every read here, a gob that is **gone raises** — as does one that has no position yet, and a call
made before you are in the world. A click is a message about one specific object, and there is nothing
honest to send about an object that has left; nothing goes out in either case.

## Size (unprotected)

`gob:scale(k)` draws the object `k` times its size — the herb you keep walking past, the boar you want to
see coming, the cupboard you are lining up. It is the same read/write pair every
[thing you stand in the world](vr/README.md) answers, so one number is the whole of it.

| Method | Returns | Description |
|---|---|---|
| `gob:scale()` | number \| nil | how big it is drawn; `1` for an object nobody resized |
| `gob:scale(k)` | the Gob | draw it `k` times its size |

```lua
local boar = hafen.world():gob():nearest("kritter/boar")
if boar then
  boar:scale(2)          -- twice the size, and it hands the Gob back, so this chains
  boar:scale(1)          -- back to the size the game draws it at
end
```

It is **client-local and purely visual**, on the same footing as the overlays below: only you see it, the
size is applied in place so the object's feet stay where they were, and it still turns, moves and takes a
click exactly as it did — the pick follows the drawn size. Nothing about what the object *is* changes: its
footprint, what it collides with and what a click sends are the game's, untouched. A few special resource
types reset their own transform — the same ones that ignore a ghost's rotation — and those ignore scale too.

`k` must be a number greater than zero, and finite. `0` collapses the object to a point and a negative one
turns it inside out, so both raise naming the rule; `gob:scale(1)` is the original size and leaves nothing
behind. Once the gob is gone the read answers `nil` and a write does nothing, bar the click above.

> **The size ends with the loaded object.** Walk far enough away for it to unload and it comes back the
> size the game draws it at. Re-apply it from [`GobAdded`](event.md#world) if you want it kept — and a
> `:reload` or a disable puts back everything you resized, so nothing is left distorted behind you.

## Overlays

`gob:overlay()` answers one question: **what is drawn at this gob?** It is the collection of everything
painted there — the game's own (a fire's flame, a crop's growth stage), your own screen-space painters, and
whatever you have [standing in the world](vr/README.md) anchored to it — and the key is your own name for
one of yours.

It is **unprotected**, the attach included: what you paint at a gob is your own drawing, and it changes
nothing the server, the client or another addon owns — the same footing as
[a HUD overlay](ui/custom.md#overlays).

| Call | Returns | Description |
|---|---|---|
| `gob:overlay():list(filter)` | `Overlay[]` | everything drawn at the gob: yours, then what you stood there, then the game's own |
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
me:overlay():remove("hp")
```

### What an overlay of yours draws

`:add(key)` attaches a **bare** overlay, and the two setters below say what it draws, at the gob's
**projected screen point**, just above the head. Each answers the overlay, so one statement configures the
whole thing — and until it names a kind it draws nothing, so a half-configured overlay never paints. It says
exactly **one** thing: a second, different kind raises naming the first, because picking a winner is how one
of them silently stops meaning anything.

| Setter | Meaning |
|---|---|
| `ov:draw(fn)` | `fn(g, gob, sx, sy)` runs every frame at that point, painting with [`g`](ui/drawing.md) |
| `ov:text(s)` | a label at that point — drawn by the engine, so it costs no Lua at the draw |

Every one of them has a bare read of the same name, so what you wrote is what you can read back, and the
configuration is the overlay's own state: `ov:text("healed")` relabels a live overlay rather than replacing
it.

| Setter | Meaning |
|---|---|
| `ov:color(r, g, b, a)` | the label's colour, `0..255`; `a` is optional |
| `ov:offset(x, y)` | **screen pixels** from the projected anchor point |

**`ov:offset` means exactly one thing: pixels.** An overlay is painted at a projected point, so that is the
only unit it could be in, and a third argument raises. There is no `ov:clickable` and no `ov:onClick`: the
thing under an overlay is the gob, and clicking a gob is [`gob:click`](#gobclickbutton-mods) above. There
is no `ov:move` either: an overlay's position **is** its gob's, and what you set is the offset.

### A thing you stood at the gob is listed here, read-only

Anything you [stood in the world](vr/README.md) anchored to this gob — a
[sprite](vr/sprites.md), an [object](vr/models.md), a [ghost](vr/ghosts.md) — is drawn at the gob, so it is
listed here too, under a generated key. It is **read-only from here**: every setter above raises on such an
entry, naming the collection that owns it.

```lua
hafen.vr():sprite():add(icon, tree):offset(0, 0, 20)     -- stood in the world, anchored to the gob
for _, ov in ipairs(tree:overlay():list()) do
  hafen.log():write(ov:key() .. " " .. tostring(ov:kind()))   -- ... and listed here: "vr#7 sprite"
end
```

That keeps **one complete answer** to "what is drawn at this gob?" without a second door onto the same
thing: you read it here, and you address it through `hafen.vr():sprite()` / `:object()` / `:ghost()` — the
handle `:add` gave you, or one out of that collection's `:list()`. Its `:native()` is `false`, because it is
yours; it is simply not written from here.

**Keys are per addon.** Two addons using `"tag"` on one gob do not collide, and neither can see the
other's — the collection answers yours and the game's, never a third party's. `:add` on a key that is
already there **replaces** it, leaving **one** overlay.

**The game's own overlays are read-only.** They come back from the same read with `native = true`, keyed
by their **resource name**. An `:add` onto such a key **raises**, so does a `:remove` of one, and so does
each setter — always naming the key, never a silent no-op.

An attach raises in one more place, about *when*: a gob the client cannot draw yet takes no overlay at all,
so attach from [`GobAdded`](event.md#world) or a timer instead. A setter that raises leaves the overlay
exactly as it was, drawing exactly what it drew before, and a kind that never landed leaves a bare overlay,
which draws nothing.

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | what it answers to; the resource name for a native one, a generated one for a thing standing there |
| `ov:gob()` | Gob | the gob it hangs on |
| `ov:native()` | bool | is this the game's own rather than yours? |
| `ov:kind()` | string \| nil | `"draw"`/`"text"` for one of yours, `"ghost"`/`"sprite"`/`"object"` for one you stood there; `nil` for a native one, and for one that has not said yet |
| `ov:res()` | string \| nil | what it is drawn from — the resource name, the asset path; `nil` for a painter of yours, which draws Lua |
| `ov:count()` | number \| nil | how many engine overlays this one entry stands for |
| `ov:exists()` | bool | still there? |
| `ov:info()` | table \| nil | a plain snapshot; the shape follows what the entry is |

`:count()` is there because **a native overlay is a union**. A gob may carry several overlays of one
resource, and the resource name is the only part of one a name can address, so they collapse to a single
Overlay and the multiplicity is published here instead of lost. Yours always count 1.
(`gob:info().overlays` is the raw list of resource names, one entry per engine overlay, for when you want
the uncollapsed view — and, like every other [`GobInfo`](types.md#gobinfo) field, it is **absent** rather
than empty when the gob carries none.)

`:info()` is the snapshot escape hatch, and it hands back **two shapes**. Both carry `key`, `native` and
`count`. **Yours** adds `kind` and `world` — `world` is `true` for a thing standing in the world and `false`
for a painter, and both are absent while a bare overlay has not said what it draws — plus `res` when what it
draws is named by a resource or an asset path. **A native one** adds `res`, which is the key itself, and
carries neither `kind` nor `world`: the game's overlays say only that they are the game's.

An Overlay object is **interned on the key**, so `gob:overlay():get(key)` hands back the same object every
time and a replace leaves the handle you were holding naming the *new* record. `:exists()` goes false
when the overlay is removed, or when its gob is gone. `tostring(ov)` gives `Overlay(<key>@<gobid>)`, with
a `*` in front of the key on the game's own.

**An overlay dies with its gob.** The record lives on the game object, so a felled tree takes yours with
it and nothing is kept in case it comes back — a gob that returns is bare, and re-attaching is your own
call from [`GobAdded`](event.md#world). A `:reload` or a disable likewise removes every overlay you
attached and leaves the game's untouched. A thing you stood in the world and anchored here
[dies with the gob too](vr/README.md#the-anchor-is-an-argument), rather than being left floating where the
target used to stand.

Both halves of this read are also **events**:
[`GobOverlayAdded`/`GobOverlayRemoved`](event.md#overlays-coming-and-going) fire for what you attach and
for what the game attaches, so you can watch a gob become decorated instead of polling it.

There is no filter form. "Every player gets a label" is a [`GobAdded`](event.md#world) handler plus a
loop over [`hafen.world():gob():list()`](world.md#objects) — you name the gob, so nothing is searched
per frame.

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

A Gob object is immutable: `gob.foo = 1` raises an error, and `gob.position` is the method itself, so call
it with a colon: `gob:position()`. `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id: `me:overlay():add("tag"):text("here")`,
`hafen.player():hand():use(tree)`, `hafen.vr():sprite():add(icon, me):offset(0, 0, 18)`.

## See also

- [`hafen.world`](world.md) — finding the gobs you want to read
- [`hafen.vr`](vr/README.md) — standing something in the world and anchoring it to a gob
- [`hafen.kin`](kin.md) — the roster side of `gob:kin()`
- [`hafen.player`](player.md#write-protected-actions) — walking to a gob, and the cursor you aim at one
- [`GobInfo`](types.md#gobinfo) — the shape `:info()` returns
- [events](event.md#world) — reacting to gobs appearing and leaving instead of polling

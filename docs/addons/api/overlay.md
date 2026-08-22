# Overlay: what is drawn at a gob

`gob:overlay()` answers one question: **what is drawn at this gob?** It is the collection of everything
painted there — the game's own (a fire's flame, a crop's growth stage), your own screen-space painters,
and whatever you have [standing in the world](vr/README.md) anchored to it — and the key is your own name
for one of yours.

```lua
local s = hafen.session():current()      -- the character on screen
local me = s and s:player():gob()        -- nil until that session is in the world
if me then me:overlay():add("mark"):text("here"):color{255, 90, 90} end
```

It is **unprotected**, the attach included: what you paint at a gob is your own drawing, and it changes
nothing the server, the client or another addon owns — the same footing as
[a HUD overlay](ui/custom.md#overlays).

**An overlay hangs on the object, not on a character.** Attach one and every character of yours that can
see the gob draws it, **including one that loads the object afterwards** — so the label is there whichever
of them you tab to, and one `:remove(key)` takes it off all of them. It ends with the object rather than
with a copy of it: when the last character loses sight of it the record goes, which is the moment
[`GobRemoved`](event/bus.md#world) fires.

## The collection

The verbs below are the standard
[collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) shape, keyed by your
own name for an overlay.

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
me:overlay():add("hp"):text("hurt"):color{255, 90, 90}:offset(0, -6)
me:overlay():add("ring"):draw(function(g, gob, sx, sy) g:frect(sx - 2, sy - 2, 4, 4) end)
me:overlay():remove("hp")
```

## What an overlay of yours draws

`:add(key)` attaches a **bare** overlay, and the two setters below say what it draws, at the gob's
**projected screen point**, just above the head. Each answers the overlay, so one statement configures the
whole thing — and until it names a kind it draws nothing, so a half-configured overlay never paints. It says
exactly **one** thing: a second, different kind raises naming the first, because picking a winner is how one
of them silently stops meaning anything.

| Setter | Meaning |
|---|---|
| `ov:draw(fn)` | `fn(g, gob, sx, sy)` runs every frame at that point, painting with [`g`](ui/drawing.md); `sx, sy` is in [design pixels](ui/pixels.md), like everything `g` takes |
| `ov:text(s)` | a label at that point — drawn by the engine, so it costs no Lua at the draw |

Every one of them has a bare read of the same name, so what you wrote is what you can read back, and the
configuration is the overlay's own state: `ov:text("healed")` relabels a live overlay rather than replacing
it.

| Setter | Meaning |
|---|---|
| `ov:color(c)` | the label's [colour](shapes.md#colours), `{200, 210, 220}` or `{r=, g=, b=[, a=]}` |
| `ov:offset(x, y)` | **screen pixels** from the projected anchor point, in [design pixels](ui/pixels.md) |

**`ov:offset` means exactly one thing: pixels.** An overlay is painted at a projected point, so that is the
only unit it could be in — the same design pixel the `sx, sy` beside it is in — and a third argument raises. There is no `ov:clickable` and no `ov:onClick`:
the thing under an overlay is the gob, and clicking a gob is
[`s:world():click`](world.md#write-protected). There is no `ov:move` either: an overlay's position **is**
its gob's, and what you set is the offset.

## A thing you stood at the gob is listed here, read-only

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
so attach from [`GobAdded`](event/bus.md#world) or a timer instead. A setter that raises leaves the overlay
exactly as it was, drawing exactly what it drew before, and a kind that never landed leaves a bare overlay,
which draws nothing.

## The Overlay object

One entry of the collection — one of yours, one the game put there, or something you stood at the gob.
The reads below answer on every kind; what a kind has nothing to say about comes back `nil`.

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
call from [`GobAdded`](event/bus.md#world). The object leaving one character's view is not that moment: it
dies when the **last** of your characters that can see it loses it, and that is when
[`GobOverlayRemoved`](event/bus.md#overlays-coming-and-going) fires. A `:reload` or a disable likewise
removes every overlay you attached, from every character that can see it, and leaves the game's untouched.
A thing you stood in the world and anchored here
[dies with the gob too](vr/README.md#the-anchor-is-an-argument), rather than being left floating where the
target used to stand.

Both halves of this read are also **events**:
[`GobOverlayAdded`/`GobOverlayRemoved`](event/bus.md#overlays-coming-and-going) fire for what you attach and
for what the game attaches, so you can watch a gob become decorated instead of polling it.

There is no filter form. "Every player gets a label" is a [`GobAdded`](event/bus.md#world) handler plus a
loop over [`s:world():gob():list()`](world.md#objects) — you name the gob, so nothing is searched
per frame.

> A `draw` callback runs inside the client's draw pass, which is **outside** the per-tick CPU budget.
> Keep it short; a `text` overlay never enters Lua at all and is the cheaper way to put a label up.

## See also

- [Gob](gob.md) — the object an overlay hangs on, and everything else it answers
- [`hafen.vr`](vr/README.md) — standing a sprite, a model or a ghost at a gob instead
- [drawing](ui/drawing.md) — the `g` wrapper a `draw` callback paints with
- [events](event/bus.md#overlays-coming-and-going) — watching one arrive instead of polling for it
- [custom UI](ui/custom.md#overlays) — the screen-space overlay that is not anchored to anything

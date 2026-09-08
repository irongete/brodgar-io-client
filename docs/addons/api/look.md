# Look: how a gob is drawn

The **look** of a [Gob](gob.md) is what the client draws for it: how big, whether at all, in what colour,
and what stands at it. The three verbs on this page change that and nothing else — the object the server
knows is untouched, and so is every read on the Gob page. All three are **unprotected**: the change is
client-local and purely visual, on the same footing as an [overlay](overlay.md).

```lua
local s = hafen.session():current()
local boar = s and s:world():gob():nearest("kritter/boar")
if boar then
  boar:tint{255, 0, 0, 96}:scale(2)    -- a red-washed boar, twice its size; each write hands the Gob back
end
```

Every write here is written on the **object**, so every character that can see it sees the same thing,
and one that walks up to it afterwards draws it the same. Every one of them ends with the loaded object,
and a `:reload` or a disable of your addon puts back everything you changed, in every character's view.
[Size](#size-unprotected) states those rules once; the other two point at it.

## Size (unprotected)

`gob:scale(k)` draws the object `k` times its size — the herb you keep walking past, the boar you want to
see coming, the cupboard you are lining up. It is the same read/write pair every
[thing you stand in the world](virtual/README.md) answers, so one number is the whole of it.

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
*is* changes: [its footprint](gob.md#the-ground-it-stands-on), what it collides with and what a click
sends are the game's, untouched. A few special resource types reset their own transform — the same ones
that ignore a ghost's rotation — and those ignore scale too.

`k` must be a number greater than zero, and finite. `0` collapses the object to a point and a negative one
turns it inside out, so both raise naming the rule; `gob:scale(1)` is the original size and leaves nothing
behind. Once the gob is gone the read answers `nil` and a write does nothing.

> **The size ends with the object, not with a copy of it.** It is dropped when the object leaves its
> **last** character's view — the moment [`GobRemoved`](event/bus/world.md#world) fires — so walking far
> enough away for it to unload and coming back gives the size the game draws it at, while another character
> still having it in view keeps it. Re-apply it from [`GobAdded`](event/bus/world.md#world) if you want it
> kept across the unload — and a `:reload` or a disable puts back everything you resized, in every
> character's view, so nothing is left distorted behind you anywhere.

## Drawn or not (unprotected)

`gob:visible(b)` says whether the client draws the object at all — the tree standing between you and what
you are working on, the clutter over a spot you are lining up. It is the read/write pair
[`gob:scale(k)`](#size-unprotected) is, with a boolean where that one has a number.

| Method | Returns | Description |
|---|---|---|
| `gob:visible()` | bool \| nil | whether the client draws it; `true` for an object nobody hid |
| `gob:visible(b)` | the Gob | draw it, or stop drawing it |

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
if tree then
  tree:visible(false)     -- out of the scene, and it hands the Gob back, so this chains
  tree:visible(true)      -- and back into it, at whatever size it was being drawn
end
```

> **An object you hide is not there to be clicked.** A click is resolved against the scene as it is drawn,
> and an object with no model in it draws nothing — so a click where it stood reaches the ground behind
> it. That is the verb's whole meaning: it will not hide a model and keep the click.

Hiding withholds the object's own model, and nothing else. It is still where it was, still moving, and it
still answers every read on the [Gob](gob.md) page; what is attached at it goes on drawing — the game's own
overlays and [the ones you attached](overlay.md) both, standing where the model was. It also composes with
the size and the colour: `boar:tint(c):scale(2):visible(false):visible(true)` is a boar still tinted and
still twice its size.

`b` must be `true` or `false`, and anything else raises naming the argument — a number most of all, since
in Lua `0` is a true value and would quietly show an object you meant to hide. Once the gob is gone the
read answers `nil` and a write does nothing.

Where the write lands and how long it lasts are [a size's rules exactly](#size-unprotected) — the object
rather than a character, and the loaded object's own lifetime — and a `:reload` or a disable puts back
everything you hid, so nothing is left missing from the world behind you.

## Tint (unprotected)

`gob:tint(c)` lays a [colour](shapes.md#colours) over the object's model — the ripe crop green, the full
box blue, the boar to avoid red — shaded as the model is shaded, so the eye reads the object itself rather
than a label beside it. It is the same read/write pair a [thing you stand in the world](virtual/README.md)
answers to `:tint`, with the same meaning: the colour's `a` is the blend strength, and `nil` is none.

| Method | Returns | Description |
|---|---|---|
| `gob:tint()` | colour \| nil | the colour laid over it, keyed; `nil` for an object nobody tinted |
| `gob:tint(c)` | the Gob | lay `c` over it |
| `gob:tint(nil)` | the Gob | draw it in its own colours again |

```lua
local box = hafen.session():current():world():gob():nearest("terobjs/chest")
if box then
  box:tint{0, 200, 80, 96}        -- a green wash: the lit and shaded sides stay distinct
  local c = box:tint()            -- {r = 0, g = 200, b = 80, a = 96}
  box:tint(nil)                   -- and plain again
end
```

**The blend is the one the game's own damage wash uses.** Each pixel of the model is mixed towards your
colour by `a / 255`: `{255, 0, 0, 96}` reads as a red-washed box with its shading kept, and `{255, 0, 0}`
— `a` defaulting to `255` — is a flat red silhouette with the lighting gone. A wash that still reads as
the object sits around `96`.

It composes with everything else drawn there. A damaged object keeps its cracks and its red under your
colour: the two are separate stages of the same blend, not one slot two writers fight over. Your colour
covers the whole model and nothing else about the object changes —
[its footprint](gob.md#the-ground-it-stands-on), its click and what it sends are the game's, untouched.

The read is the keyed table every colour reader answers, so `.r` answers and `[1]` is `nil`, and
[`gob:info()`](types/world.md#gobinfo) carries the same table under `tint` — and no `tint` key at all for an
object nobody tinted. `gob:tint(nil)` is legal and leaves nothing behind: the object is drawn exactly as
before. Anything that is not a colour — a number, a string, a boolean, a table that is not one — raises
naming both spellings of a colour. Once the gob is gone the read answers `nil` and a write does nothing.

Where the write lands and how long it lasts are [a size's rules exactly](#size-unprotected): every
character that can see the object, one that loads it afterwards, the loaded object's own lifetime, and
last write wins between two addons — and a `:reload` or a disable puts back everything you coloured, so
nothing is left painted behind you.

## Overlays

Everything drawn at a gob — the game's own, the labels and painters you attach, and whatever you have
[stood in the world](virtual/README.md) anchored to it — is the collection
[`gob:overlay()`](overlay.md), and it is unprotected.

An overlay is attached to the **object**, and every character that can see the object draws it: it appears
whichever of them is on screen, and a `:reload` or a disable takes it off all of them.

## See also

- [Gob](gob.md) — the object itself: finding one, and everything you read off it
- [Overlay](overlay.md) — the labels, painters and models you attach at a gob
- [Colours](shapes.md#colours) — the table `gob:tint` reads and writes
- [virtual entities](virtual/README.md) — `:scale`, `:visible` and `:tint` on a thing you stood there
- [threading](threading.md) — why these writes are safe from a `Draw` handler

# hafen.virtual: the pieces a patch is made of

A **piece** is one convex ring of a [patch](patches.md), and a patch is drawn as the **union** of its pieces.
So a shape that is not convex — an L, a stroke, a field with a bite out of it — is *one* patch of several
pieces rather than several patches: one handle, one place, one look, and no line across the join where two of
them meet.

`hafen.virtual():patch():add(ring, anchor)` lays a patch of one piece. `patch:piece()` is the collection of
that patch's pieces, and `:add(ring)` lays another into the same shape:

```lua
local s = hafen.session():current()
local here = s:player():gob():position()
local across = { here:offset(-9, -3), here:offset(9, -3),      -- the foot of the L
                 here:offset(9, 3),   here:offset(-9, 3) }
local down   = { here:offset(3, -3),  here:offset(9, -3),      -- the upright, overlapping it
                 here:offset(9, 15),  here:offset(3, 15) }
local patch = hafen.virtual():patch():add(across, here):tint{40, 200, 120}
patch:piece():add(down)                            -- one shape now, drawn as the two together
patch:border({255, 255, 255}, 0)                   -- ...and outlined round the L, not round each quad
```

## The collection

| Method | Permission | Description |
|---|---|---|
| `patch:piece():add(ring)` | unprotected | lay one more convex ring into this shape, and hand back the piece |
| `patch:piece():list(filter)` | unprotected | every live piece, in the order they were laid |
| `patch:piece():count(filter)` | unprotected | how many, without building the array |
| `patch:piece():find(filter)` | unprotected | the first one that matches |

It is the whole [collection shape](README.md#the-collections-unprotected) minus the two verbs a piece has no
answer for. `filter` is the canonical [filter](../conventions.md#the-filter-argument), except that **a piece
is a region and not a picture of something**, so it has no name: a string raises naming the two forms that
work, exactly as a string on `hafen.virtual():patch()` does. There is no `:get(key)` either — a piece has
nothing to be addressed by, and the refusal says so.

**`:add(ring)` takes no anchor.** A piece is held as offsets from the *patch's* own
[anchor](README.md#the-anchor-is-an-argument), which is what makes the shape one thing: `patch:position(p)`,
`patch:offset(x, y)`, `patch:rotate(a)` and `patch:scale(k)` move every piece of it together, and a piece laid
into a patch that has already been turned lands where the turned shape is. Laying a piece into a patch that
is gone raises, naming `patch:exists()`.

## The ring

`ring` is an array of [Position](../position.md)s, in order around the shape, wound either way. Each point is
a real place in the world, so the rings [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) hands back go in
**unchanged** — no projection, no conversion:

```lua
local me = hafen.session():current():player():gob()
local mine
for _, ring in ipairs(me:hitbox() or {}) do            -- nil until the object's resource resolves
  if mine then mine:piece():add(ring)                  -- every further ring, into the one shape
  else mine = hafen.virtual():patch():add(ring, me):tint{255, 80, 80} end
end
```

What a patch keeps is each ring as **offsets from its anchor**, not as points, which is why the same ring
means the same shape at a point and on an object, and why it survives
[the numbers moving under it](README.md#the-ground-under-one-that-stands-still).

A ring cannot be changed afterwards: it is what the piece was made from, and a different ring is a different
piece. There is no `:ring()` verb — `piece:info().ring` reads back the shape it holds.

## What a ring may be

Each rule below is a **refusal** naming itself, because a ring drawn as some other shape is the one outcome a
drawing verb must not have. They hold for the ring `hafen.virtual():patch():add` takes and for the ring
`patch:piece():add` takes, in the same words.

| The ring | What `:add` does |
|---|---|
| fewer than three points | raises: a ring of two is a line, and a line has no ground under it |
| three or more points enclosing nothing — all the same place, or all on one line | raises, naming how many edges it actually found |
| concave | raises: the silhouette is the intersection of the ring's edge half-planes, so a concave ring would be drawn as its hull |
| more than **32** edges | raises, naming that number — refused rather than truncated to the wrong shape |
| an element that is not a Position | raises, naming which one and the three verbs that make a place |
| a point with no way to reach the anchor — another grid, and neither end located this session | raises: a ring one point short is the wrong shape, not a smaller one |

```lua
local ok, err = pcall(function()
  hafen.virtual():patch():add({ here, here:offset(6, 0) }, here)   -- two points
end)
hafen.log():write(tostring(err))                              -- ...says a line has no ground under it
```

**Concave is not a wall, it is the instruction.** Split the shape into convex rings and lay one piece each;
that is what this collection is for, and the refusal says so.

## The budget: 32 to a piece, 128 to a patch

Two numbers, and each is a length declared in the fragment stage:

| Limit | What it counts |
|---|---|
| **32** edges | one ring. A 32-gon is a circle to the eye, and a shape that wants more detail is more pieces |
| **128** edges | one patch, across every piece it holds. `:add` raises naming that number and what the patch already carries |

An edge is a *segment*, so a repeated point costs nothing: a ring of four points that visits one of them
twice has three edges. A shape past 128 is a second patch — the pieces of one are the pieces that share a
place, a look and a border, and nothing else binds them.

## Pieces overlap; they do not abut

Two pieces laid edge to edge, sharing a boundary exactly, draw a **faint hairline** along it: the shape's
distance is zero all along that seam, and zero is what the silhouette antialiases across. Overlap them
instead — even by a fraction of a world unit — and the join carries nothing at all, which is what the L above
does.

The same is true of the [border](patches.md#the-border): it is a band off the *union's* own edge, so it runs
round the outside of the whole shape and lays no line where two pieces meet.

## The piece

| Method | Permission | Description |
|---|---|---|
| `piece:info()` | unprotected | the whole state as a plain table — the [one snapshot](../conventions.md) |
| `piece:exists()` | unprotected | is this piece still part of its patch? |

| Key | What `info()` holds |
|---|---|
| `exists` | whether the patch still holds it |
| `ring` | the shape, as an array of the `{gridId, x, y}` tables a [Position](../position.md) answers with |

`ring` is absent while the character on screen cannot locate the ground the piece lies on, exactly as
[`patch:info()`](patches.md#the-snapshot)'s is — present means known.

**Two verbs, and they are the whole of a piece.** Where it is, how big it is, what colour it is, whether it
is drawn and whether the world may hide it are all the *patch's*: a piece is part of one shape, and a second
set of the same verbs on the part would be two owners of one look. A verb no piece has raises naming `piece`
and listing the two it does have; `tostring(piece)` is `Piece`.

## See also

- [patches](patches.md) — the shape these are the pieces of: its place, its look, its border, its clicks
- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) — the rings this collection takes unchanged
- [Position](../position.md) — the place every point of a ring is, and the offset verb that builds one

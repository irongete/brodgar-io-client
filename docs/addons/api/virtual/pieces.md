# hafen.virtual: The Pieces a Patch Is Made Of

A piece is one convex ring of a [patch](patches.md), and a patch is drawn as the union of its pieces. An L, a stroke or a field with a notch is one patch of several pieces. One handle, one place, one look, no line across the join. `hafen.virtual():patch():add(ring, anchor)` lays a patch of one piece. `patch:piece():add(ring)` lays another into the same shape.

```lua
local session = hafen.session():current()
local here = session:player():gob():position()
local across = { here:offset(-9, -3), here:offset(9, -3),      -- the foot of the L
                 here:offset(9, 3),   here:offset(-9, 3) }
local down   = { here:offset(3, -3),  here:offset(9, -3),      -- the upright, overlapping it
                 here:offset(9, 15),  here:offset(3, 15) }
local patch = hafen.virtual():patch():add(across, here):tint{40, 200, 120}
patch:piece():add(down)                            -- one shape now, drawn as the two together
patch:border({255, 255, 255}, 0)                   -- ...and outlined round the L, not round each quad
```

---

## The collection

| Method | Returns | Permission | Description |
|---|---|---|---|
| `patch:piece():add(ring)` | the piece | Unprotected | Lay one more convex ring into this shape. |
| `patch:piece():list(filter)` | piece`[]` | Unprotected | Every live piece, in laid order. |
| `patch:piece():count(filter)` | `number` | Unprotected | How many, without building the array. |
| `patch:piece():find(filter)` | piece `\| nil` | Unprotected | The first that matches. |
| `patch:piece():remove(piece)` | the collection | Unprotected | [Take one piece up](#taking-one-up). |

| Rule | Detail |
|---|---|
| No `:get(key)` | A piece has nothing to be addressed by. The refusal says so. |
| `filter` | The canonical [filter](../conventions.md#the-filter-argument), except a piece is a region with no name: a string raises naming the two forms that work, as on `hafen.virtual():patch()`. |
| `:add(ring)` takes no anchor | A piece is held as offsets from the patch's own [anchor](README.md#the-anchor-is-an-argument). `patch:position(position)`, `patch:offset(x, y)`, `patch:rotate(angle)` and `patch:scale(k)` move every piece together. A piece laid into a turned patch lands where the turned shape is. Laying into a patch that is gone raises naming `patch:exists()`. |

## The ring

`ring` is an array of [Position](../position.md)s in order around the shape, wound either way. Each point is a real place, so the rings [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) hands back go in unchanged.

```lua
local my_gob = hafen.session():current():player():gob()
local footprint
for _, ring in ipairs(my_gob:hitbox() or {}) do        -- nil until the object's resource resolves
  if footprint then footprint:piece():add(ring)        -- every further ring, into the one shape
  else footprint = hafen.virtual():patch():add(ring, my_gob):tint{255, 80, 80} end
end
```

| Rule | Detail |
|---|---|
| Kept as offsets from the anchor | Not as points. That is why the same ring means the same shape at a point and on an object. It is why it survives [the numbers moving under it](README.md#the-ground-under-one-that-stands-still). |
| Immutable | A different ring is a different piece. There is no `:ring()` verb. `piece:info().ring` reads the shape back. |

## What a ring may be

Each rule is a refusal naming itself, for the ring `hafen.virtual():patch():add` takes and the ring `patch:piece():add` takes alike.

| The ring | `:add` |
|---|---|
| Fewer than three points | Raises: a ring of two is a line, and a line has no ground under it. |
| Three or more points enclosing nothing (all one place, or all on one line) | Raises, naming how many edges it found. |
| Concave | Raises: the silhouette is the intersection of the ring's edge half-planes, so a concave ring would draw as its hull. Split it into convex rings, one piece each. |
| Crossing itself (a star, a bow-tie) | Raises as concave does: the half-planes carve the small shape in the middle. A star is five triangles and a pentagon. The point order decides which. |
| More than 32 edges | Raises naming the number, rather than truncating to the wrong shape. |
| An element that is not a Position | Raises, naming which one and the verbs that make a place. |
| A point with no way to reach the anchor (another grid, neither end located this session) | Raises: a ring one point short is the wrong shape. |

```lua
local ok, error_message = pcall(function()
  hafen.virtual():patch():add({ here, here:offset(6, 0) }, here)   -- two points
end)
hafen.log():write(tostring(error_message))                        -- ...says a line has no ground under it
```

## The budget

| Limit | Counts |
|---|---|
| 32 edges | One ring: a 32-gon is a circle to the eye, and more detail is more pieces. |
| 128 edges | One patch, across every piece. `:add` raises naming that number and what the patch already carries. |

An edge is a segment, so a repeated point costs nothing: a ring of four points visiting one twice has three edges. A shape past 128 is a second patch.

## Pieces overlap; they do not abut

Two pieces laid edge to edge draw a faint hairline along the seam. The shape's distance is zero there, and zero is what the silhouette antialiases across. Overlap them, even by a fraction of a world unit, and the join carries nothing, as the L above does. The [border](patches.md#the-border) is a band off the union's edge, so it lays no line where two pieces meet.

## Taking one up

`patch:piece():remove(piece)` takes one piece out and leaves the rest drawn. It takes the piece `:add` handed back (a piece has no key) and hands the collection back.

```lua
local middle = patch:piece():list()[2]
patch:piece():remove(middle)                      -- the shape is the other pieces now
hafen.log():write(tostring(middle:exists()))      -- false
```

| Rule | Detail |
|---|---|
| A taken-up piece goes on answering | `piece:exists()` reads `false`. [`piece:info()`](#the-piece) reads back the ring and where it stands. A piece of a patch that has ended reads the same `false`. |
| Raises | A value that is not a piece. A piece of another patch (a shape is taken apart only by the patch that holds it). One already let go. A patch that is gone, naming `patch:exists()`. |
| A patch with no pieces is still a patch | It holds its place, tint, border and everything else, and draws nothing: [`patch:drawn()`](patches.md#the-patch) is `false` until a piece is laid back, [`patch:info().pieces`](patches.md#the-snapshot) is empty. Ending the patch is `hafen.virtual():patch():remove(patch)` ([the ending is the collection's](../conventions.md#endings-the-receivers-kind-picks-the-word)). |
| Re-cuts the ground | The set of pieces decides which tiles the shape masks, so laying or taking up one re-lays them. A look (tint, [border](patches.md#the-border), turn, scale) costs no terrain work. Laying and taking up every frame costs what [moving a patch](patches.md#the-patch) costs. |

## The piece

| Method | Returns | Permission | Description |
|---|---|---|---|
| `piece:info()` | `table` | Unprotected | `{exists=, ring=}`. `ring` is the shape as an array of the `{gridId, x, y}` tables a [Position](../position.md) answers with. It is absent while the character on screen cannot locate the ground, as [`patch:info()`](patches.md#the-snapshot)'s is. |
| `piece:exists()` | `boolean` | Unprotected | Whether the patch still holds it. |

Those verbs are the whole of a piece. Where it is, how big, what colour, whether drawn, whether the world may hide it and [what a click means](patches.md#clickability) are the patch's. A verb no piece has raises naming `piece` and listing what it answers. `tostring(piece)` is `Piece`.

---

## See Also

- [Patches](patches.md) — the shape these are the pieces of: its place, its look, its border, its clicks.
- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) — the rings this collection takes unchanged.
- [Position](../position.md) — the place every point of a ring is, and the offset verb that builds one.

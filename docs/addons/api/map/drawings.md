# hafen.map: Minimap Drawings

The square the corner minimap paints for a piece of ground. A grid renders itself, and what you get back is an ordinary image handle, the thing [`hafen.asset`](../asset/README.md) hands you for a PNG. So everything that draws an image draws a map: `graphics:image` in your widget, [a world sprite](../virtual/sprites.md), a stylesheet's `bg = { image = … }`.

```lua
local here = hafen.session():current():player():gob():position()
local grid = hafen.map():grid():get(here:info().gridId)
local window = hafen.ui():window():title("Here"):size(100, 100)
window:on("Draw", function(draw_event)
  local image = grid:image(0)                    -- nil while it renders; ask again next frame
  if image then draw_event:g():image(image, 0, 0) end
end)
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `grid:image(level)` | image `\| nil` | Unprotected | The recorded ground at zoom `level`, `0` (default) to `8`. |
| `grid:overlayImage(tag)` | image `\| nil` | Unprotected | One recorded [mask](overlays.md#the-recorded-masks) drawn in the overlay's own colour. `nil` for a tag this grid does not carry. |

## The first call renders, and answers nil

Drawing a grid reads every one of its 10 000 tiles out of the tileset art, which takes milliseconds. So it happens off the frame, where the client renders its own minimap. The [nil rule](README.md) applies: the first call starts the render and returns `nil`, a later one the handle. A draw callback that re-asks every frame is the intended shape and costs nothing once the picture is there. The same `(grid, level)` hands back the same handle.

| `grid:image(0)` | `grid:info().failed` | Meaning |
|---|---|---|
| `nil` | `false` | Not yet: rendering, ask again next frame. |
| An image | `false` | There it is. |
| `nil` | `true` | Never: tried and given up on. |

A failed render is tried again three times. A render cancelled because another thread wanted the picture, a resource mid-swap, a segment rewritten under a merge are transient, not final. After three failures in a row the picture stops being asked for. The flag is for saying why nothing came, not for branching the draw.

## A level is a scale, not a size

Every drawing is 100×100 pixels at every level. The level changes how much ground fits.

| `level` | One pixel is | The square covers |
|---|---|---|
| `0` | One tile. | 100×100 tiles, one grid. Drawn through the ground around it, so tile transitions blend across the border as on the corner minimap. |
| `1` | 2×2 tiles. | 200×200 tiles, four grids. |
| `n` | 2ⁿ×2ⁿ tiles. | Four times the ground of `n-1`. |

Neighbouring grids share a drawing above level 0: the four grids under one level-1 square hand back the identical handle.

## The picture is yours, and it is bounded

| Method | Returns | Permission | Description |
|---|---|---|---|
| `image:dispose()` | the handle | Unprotected | Free its texture now. Also automatic on reload, disable and logout. |
| `image:info()` | `table` | Unprotected | `{source = "map", what, size = {w=, h=}, disposed}`. |

| Rule | Detail |
|---|---|
| An owned resource | `image:dispose()` frees the texture. A `:reload`, a disable or a logout frees everything held. A disposed handle stays inert: it answers `:size()`, draws nothing, and the next `grid:image(level)` renders a fresh one. |
| The ending is on the handle | An [asset](../asset/collection.md#the-collection) is freed by its collection. A drawing belongs to no collection, so `hafen.asset():remove(image)` says so. |
| The cache manages it | The most recently asked-for drawings are kept and what falls off the end is disposed. A panel scrolling across a continent frees the ground behind it. Re-ask each frame rather than stashing a handle: asking keeps a picture alive. |
| Not an asset | Never in `hafen.asset()`. Its `:path()` is a description, not a loadable file. |
| An object | As [every other handle](../asset/handles.md#every-asset): nothing can be written to it, an unknown name raises naming the ones it has. `tostring` is `Asset(image, map:<gridId>@<level>)` for the ground and `Asset(image, overlay:<tag>@<gridId>)` for a mask. Beside `:size()`, `:type()` and `:path()` it answers the verbs above. |

A minimap panel is these pages end to end. The picture comes from `grid:image`, the pins from `segment:markers()`. The handle goes to the stylesheet as `bg = { image = … }`, so the client paints it with no draw callbacks of your own.

---

## See Also

- [Segments and grids](grids.md) — the `Grid` that renders itself.
- [Overlays](overlays.md) — the recorded masks `grid:overlayImage` paints.
- [`hafen.asset`](../asset/README.md) — the image handle a drawing is one of, and its `:size()`.
- [`hafen.virtual`](../virtual/sprites.md) — standing one of these in the 3D world.

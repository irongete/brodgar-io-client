# hafen.map: minimap drawings

The picture a player recognises: the square the corner minimap paints for a piece of ground. A grid can
render itself, and what you get back is an ordinary **image handle** — the same thing
[`hafen.asset`](../asset.md) hands you for a PNG of your own.

| Call | Returns | Description |
|---|---|---|
| `grid:image(level)` | image \| nil | the recorded ground at zoom `level` — `0` (the default) up to `8` |
| `grid:overlayImage(tag)` | image \| nil | one recorded [mask](overlays.md#the-recorded-masks) drawn in the overlay's own colour; `nil` for a tag this grid does not carry |

Because it is an image handle, everything that already draws an image draws a map: `g:image` in your own
widget, [a world sprite](../vr/sprites.md), and a stylesheet's `bg = { image = … }`.

```lua
local here = hafen.session():current():player():gob():position()
local g = hafen.map():grid():get(here:info().gridId)
local win = hafen.ui():window():title("Here"):size(100, 100)
win:on("Draw", function(ev)
  local img = g:image(0)                    -- nil while it renders; ask again next frame
  if img then ev:g():image(img, 0, 0) end
end)
```

## The first call renders, and answers nil

Drawing a grid means reading every one of its 10 000 tiles out of the tileset art — milliseconds, not
microseconds. So it happens **off the frame**, exactly where the client renders its own minimap, and the
[nil rule](README.md#reads-answer-nil-until-the-disk-answers) covers it: the first call starts the render
and returns `nil`, a later one returns the handle. A draw callback that re-asks every frame is the
intended shape, and it costs nothing once the picture is there — the same `(grid, level)` hands back the
*same* handle, never a new render.

## A level is a scale, not a size

**Every drawing is 100×100 pixels, at every level.** What the level changes is how much ground fits in
that square:

| `level` | One pixel is | The square covers |
|---|---|---|
| `0` | one tile | 100×100 tiles — one grid |
| `1` | 2×2 tiles | 200×200 tiles — four grids |
| `n` | 2ⁿ×2ⁿ tiles | four times the ground of `n-1` |

Which is why neighbouring grids **share** a drawing above level 0: the four grids under one level-1 square
are one picture, and all four hand back the identical handle. Level 0 is drawn through the ground around
it, so tile transitions blend across the grid border just as they do on the corner minimap.

## The picture is yours, and it is bounded

A drawing is an **owned resource** like a loaded image: `img:dispose()` frees its texture now, and a
`:reload`, a disable or a logout frees everything you were holding. A disposed handle stays inert rather
than becoming an error — it still answers `:size()`, drawing it simply draws nothing, and the next
`grid:image(level)` renders a fresh one.

You do not have to manage it. The cache keeps the most recently asked-for drawings and **disposes what
falls off the end**, so a panel that scrolls across a continent frees the ground behind it by itself. That
is also why a panel should re-ask each frame rather than stash a handle for later: asking is what keeps a
picture alive.

> **It is not an asset.** A map drawing never appears in `hafen.asset()` and its `:path()` is a
> description, not a file you could load. An asset is a file your addon shipped; this is a picture the
> client drew of the database.

A minimap panel is these pages end to end: its picture comes from `grid:image`, its pins from
`seg:markers()`, and handing the handle to the stylesheet as `bg = { image = … }` makes the **engine**
paint it — `0` draw callbacks of your own while a map is on the screen.

## See also

- [segments and grids](grids.md) — the `Grid` that renders itself
- [overlays](overlays.md) — the recorded masks `grid:overlayImage` paints
- [`hafen.asset`](../asset.md) — the image handle a drawing is one of, and its `:size()`/`:dispose()`
- [`hafen.vr`](../vr/sprites.md) — standing one of these in the 3D world

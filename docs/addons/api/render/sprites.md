# hafen.render: an image in the world

`hafen.render.sprite{…}` stands a PNG **in the 3D world**, on the same client-only world-entity core a
[ghost](../ghost.md) uses: a game object with no server id, so nothing reaches the server and nothing here
is gated. Two forms, chosen by `billboard`:

- **fixed** (`billboard = false`, the default) — a textured **quad** standing upright in the world, facing
  `a`, about a tile tall, drawn double-sided. It is true world geometry, so world-rotate and world-scale
  apply.
- **[billboard](#billboard)** (`billboard = true`) — a screen-space blit that always faces the camera at a
  constant screen size. Position and gizmo-move apply; world-rotate and world-scale do not.

```lua
local icon = hafen.asset("icon.png")
local p = hafen.player():gob():pos()
local s = hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }  -- ~3 tiles tall, at your feet
s:rotate(math.pi / 2):alpha(0.8)     -- face 90 degrees, slightly translucent
s:destroy()                          -- or just let reload or disable clean it up
```

## Create (ungated)

`hafen.render.sprite(opts)` returns a [sprite handle](#sprite-handle), or `nil` if you are not in the world
yet, since there is no map view to stand it in.

| Option | Default | Meaning |
|---|---|---|
| `image` | *required* | a [`hafen.asset`](../asset.md) **image handle** — [handle-only](README.md#handle-only); a path string is an error |
| `x`, `y` | *required* | world coordinates, like [`gob:pos()`](../gob.md) |
| `a` | `0` | facing angle in **radians**; a billboard ignores it, because it faces the camera |
| `scale` | `1` | uniform scale. A **fixed** sprite is about a tile tall at `1`, width following the image aspect; a **billboard** is its native pixel size times `scale` |
| `alpha` | `1` | opacity `0..1`, combined with the PNG's own transparency |
| `tint` | *none* | colour overlay `{r=, g=, b=, a=}`, `0..255`, where `a` is blend strength |
| `billboard` | `false` | `false` is a fixed upright quad, `true` a [camera-facing](#billboard) screen blit |
| `clickable` | `false` | opt into [the click event](#clickability) — **fixed sprites only** |
| `onClick` | *none* | `fn(s, button, x, y)` fired on click, also delivered as the [`SpriteClicked`](../events.md#world-ghosts-and-sprites) event |

## Sprite handle

The same transform surface as a [ghost handle](../ghost.md#the-ghost-handle), with `:image()` in place of
`:res()`. Every verb except `:pos()` and `:image()` returns the handle, so calls chain:
`s:move(x, y):rotate(a):scale(2)`.

| Method | Description |
|---|---|
| `s:move(x, y, a)` | reposition in world coords, optionally re-facing |
| `s:rotate(a)` | set facing in radians, keeping position; a billboard faces the camera, so this has no visible effect |
| `s:scale(k)` | uniform scale — world size when fixed, a screen-size multiplier when a billboard |
| `s:alpha(a)` | opacity `0..1` |
| `s:tint(color)` | colour overlay `{r=, g=, b=, a=}`; `nil` clears it |
| `s:clickable(bool)` | toggle the pick surface — [fixed sprites only](#clickability) |
| `s:show()` / `s:hide()` | add to or remove from the scene, keeping the sprite |
| `s:pos()` | `{x, y, a, scale}` |
| `s:image()` | the addon-relative image path |
| `s:destroy()` | remove it now; also automatic on reload, disable and relogin |

## Billboard

Pass `billboard = true` for a sprite that **always faces the camera** at a **constant screen size** — a
screen-space blit anchored at the sprite's world point, so rotating the camera or zooming leaves it
square-on and the same number of pixels. It is the ergonomic, gob-anchored version of drawing an image at
[`hafen.player():worldToScreen`](../player.md) inside a [`hafen.ui.overlay`](../ui/custom.md#overlays), and
it draws **on top** of the 3D scene, with no depth occlusion.

```lua
local b = hafen.render.sprite{ image = icon, x = p.x, y = p.y, billboard = true, scale = 2 }
```

A billboard is drawn at the image's **native pixel size**, DPI-scaled like the HUD, times `scale`, and
bottom-centred on its world point, so it "stands" there.
Because it is a flat 2D image, `:rotate` is stored but has no visible effect and `:scale` acts as a
screen-size multiplier. `:alpha` and `:tint` work as they do on a fixed sprite. It has no world mesh, so it
is never [clickable](#clickability); select it by other means, such as your own list.

## Clickability

A **fixed** sprite can be made clickable — `clickable = true` at create, or `s:clickable(true)` later —
exactly like a [ghost](../ghost.md#clickability). It gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing reaches
the server. Both the per-sprite `onClick(s, button, x, y)` and the owner-scoped
[`SpriteClicked`](../events.md#world-ghosts-and-sprites) event fire; `SpriteClicked` reaches only *your*
addon, since a sprite is private to the addon that made it.

```lua
local s = hafen.render.sprite{
  image = icon, x = wx, y = wy,
  clickable = true,
  onClick = function(s, button, x, y)          -- 1 = left, 3 = right; x, y = the clicked world point
    hafen.log(("clicked my sprite (button %d)"):format(button))
  end,
}
```

> **Billboards are click-through.** A billboard has no world geometry, so it never wins a pick:
> `clickable` and `onClick` on one are harmless no-ops. Use a fixed sprite when you need click selection.

## An image on a gob is an overlay

`hafen.render.sprite` stands an image at a **fixed world point**. To hang one on a *game object* so it
moves with it every frame, the verb is [`gob:overlay`](../gob.md#overlays): it keys the thing per addon,
reads back through `gob:overlay()`, carries the same `scale`/`alpha`/`tint`/`billboard` options, and
**dies with the gob**, so a felled tree takes the image on it with it.

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.asset("marker.png")
local prey = hafen.world.nearest(function(g) return (g:name() or ""):find("rabbit") end)
if prey then
  prey:overlay("hunt", { image = icon, scale = 1.5, offset = { z = 14 } })
end
```

The overlay keeps its **own** facing and scale, so `ov:rotate` and `ov:scale` work on it; its position is
the gob's, and the only thing you set is the `offset`.

`hafen.render.sprite` takes no anchor of its own: a `follow` or an `offset` key in its table **raises**,
naming `gob:overlay`, rather than standing the image somewhere you did not ask for.

> **Gizmo.** A sprite is transformable by the [gizmo](../ghost.md#the-transform-gizmo) for free: it exposes
> the same `:move`, `:rotate` and `:scale` handle a ghost does, and the gizmo drives any such handle.

## See also

- [models](models.md) — the same thing with a glTF mesh instead of an image
- [`hafen.asset`](../asset.md) — loading the PNG a sprite takes
- [`hafen.ghost`](../ghost.md) — the game's own props, and the gizmo that moves any of these handles
- [drawing](../ui/drawing.md) — the same image drawn on screen instead of in the world
- [events](../events.md#world-ghosts-and-sprites) — `SpriteClicked`

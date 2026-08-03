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
| `x`, `y` | *required* | world coordinates, like [`gob:pos()`](../gob.md); optional when `follow` is given, since the gob supplies the position |
| `a` | `0` | facing angle in **radians**; a billboard ignores it, because it faces the camera |
| `scale` | `1` | uniform scale. A **fixed** sprite is about a tile tall at `1`, width following the image aspect; a **billboard** is its native pixel size times `scale` |
| `alpha` | `1` | opacity `0..1`, combined with the PNG's own transparency |
| `tint` | *none* | colour overlay `{r=, g=, b=, a=}`, `0..255`, where `a` is blend strength |
| `billboard` | `false` | `false` is a fixed upright quad, `true` a [camera-facing](#billboard) screen blit |
| `clickable` | `false` | opt into [the click event](#clickability) — **fixed sprites only** |
| `onClick` | *none* | `fn(s, button, x, y)` fired on click, also delivered as the [`SpriteClicked`](../events.md#world-ghosts--sprites) event |
| `follow` | *none* | **anchor to a gob** so the sprite tracks it every frame — see [anchoring](#anchoring-to-a-gob) |
| `offset` | *none* | fixed world offset `{x=, y=, z=}` from the followed gob, `z` being up |

## Sprite handle

The same transform surface as a [ghost handle](../ghost.md#the-ghost-handle), with `:image()` in place of
`:res()`. Every verb except `:pos()` and `:image()` returns the handle, so calls chain:
`s:move(x, y):rotate(a):scale(2)`.

| Method | Description |
|---|---|
| `s:move(x, y, a)` | reposition in world coords, optionally re-facing — **detaches** any follow anchor |
| `s:rotate(a)` | set facing in radians, keeping position; a billboard faces the camera, so this has no visible effect |
| `s:scale(k)` | uniform scale — world size when fixed, a screen-size multiplier when a billboard |
| `s:alpha(a)` | opacity `0..1` |
| `s:tint(color)` | colour overlay `{r=, g=, b=, a=}`; `nil` clears it |
| `s:clickable(bool)` | toggle the pick surface — [fixed sprites only](#clickability) |
| `s:show()` / `s:hide()` | add to or remove from the scene, keeping the sprite |
| `s:follow(gob, offset)` | [anchor](#anchoring-to-a-gob) to a gob and auto-follow it; `s:follow(nil)` detaches |
| `s:offset{x=, y=, z=}` | move it relative to the followed gob, and keep following |
| `s:pos()` | `{x, y, a, scale}`, plus `following` — the anchored gob id — when there is one |
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
bottom-centred on its world point, so it "stands" there; a `follow` offset with a `z` floats it overhead.
Because it is a flat 2D image, `:rotate` is stored but has no visible effect and `:scale` acts as a
screen-size multiplier. `:alpha` and `:tint` work as they do on a fixed sprite. It has no world mesh, so it
is never [clickable](#clickability); select it by other means, such as your own list.

## Clickability

A **fixed** sprite can be made clickable — `clickable = true` at create, or `s:clickable(true)` later —
exactly like a [ghost](../ghost.md#clickability). It gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing reaches
the server. Both the per-sprite `onClick(s, button, x, y)` and the owner-scoped
[`SpriteClicked`](../events.md#world-ghosts--sprites) event fire; `SpriteClicked` reaches only *your* addon,
since a sprite is private to the addon that made it.

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

## Anchoring to a gob

A sprite can be **anchored to a gob** so it moves with it automatically, every frame, with no per-tick code
of your own — the world-space analog of a [`hafen.ui.gobOverlay`](../ui/custom.md#overlays). Give a `follow`
target, a [Gob object](../gob.md), and an optional world `offset` where `z` is up.

| Call | Does |
|---|---|
| `hafen.render.sprite{ image =, follow = gob, offset = {…} }` | create it already anchored; `x`/`y` are not needed |
| `s:follow(gob, offset)` | anchor an existing sprite |
| `s:follow(nil)` | detach — it stays where it currently is |
| `s:offset{x=, y=, z=}` | change the offset while it keeps following |
| `s:move(x, y)` | a manual move **detaches** the follow: you take control |

The sprite keeps its **own** facing and scale while anchored, so `:rotate` and `:scale` still work. The
target is re-resolved each frame, so it survives the gob unloading and reloading, and holds position while
the gob is gone.

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.asset("marker.png")
local prey = hafen.world.nearest(function(g) return (g:name() or ""):find("rabbit") end)
if prey then
  hafen.render.sprite{ image = icon, scale = 1.5, follow = prey, offset = { z = 14 } }
end
```

> **Gizmo.** A sprite is transformable by the [gizmo](../ghost.md#the-transform-gizmo) for free: it exposes
> the same `:move`, `:rotate` and `:scale` handle a ghost does, and the gizmo drives any such handle.

## See also

- [models](models.md) — the same thing with a glTF mesh instead of an image
- [`hafen.asset`](../asset.md) — loading the PNG a sprite takes
- [`hafen.ghost`](../ghost.md) — the game's own props, and the gizmo that moves any of these handles
- [drawing](../ui/drawing.md) — the same image drawn on screen instead of in the world
- [events](../events.md#world-ghosts--sprites) — `SpriteClicked`

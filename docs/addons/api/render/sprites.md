# hafen.render: an image in the world

`hafen.render():sprite():add(asset, p)` stands a PNG **in the 3D world**, on the same client-only world-entity
core a [ghost](../ghost.md) uses: a game object with no server id, so nothing reaches the server and nothing
here is gated. Two forms, chosen by `:billboard`:

- **fixed** (`:billboard(false)`, the default) — a textured **quad** standing upright in the world, facing
  the sprite's own angle, about a tile tall, drawn double-sided. It is true world geometry, so world-rotate
  and world-scale apply.
- **[billboard](#billboard)** (`:billboard(true)`) — a screen-space blit that always faces the camera at a
  constant screen size. Position and gizmo-move apply; world-rotate and world-scale do not.

```lua
local icon = hafen.asset():get("icon.png")
local p = hafen.player():gob():position()
local s = hafen.render():sprite():add(icon, p):scale(3)          -- ~3 tiles tall, at your feet
s:rotate(math.pi / 2):alpha(0.8)             -- face 90 degrees, slightly translucent
hafen.render():sprite():remove(s)            -- or just let reload or disable clean it up
```

## Place one (ungated)

`hafen.render():sprite():add(image, p)` stands the sprite and hands it back, ready to configure. `image` is
a [`hafen.asset`](../asset.md) **image handle** — [handle-only](README.md#handle-only), so a path string is
an error — and `p` is a [Position](../world.md#the-position-type). Both are required: the scene resolves the
tile under a sprite as it enters it, so a sprite with no place cannot be built at all. It **raises** if you
are not in the world yet, since there is no map view to stand it in.

Everything else is a setter on what comes back, and every setter returns the sprite, so a whole placement is
one chain. A new sprite has scale `1`, full opacity, no tint, is not clickable and is not a billboard.

## The sprite

Every property is one name: calling it bare **reads**, calling it with a value **writes**.

| Method | Description |
|---|---|
| `s:position()` | where it stands, as a [Position](../world.md#the-position-type) |
| `s:position(p, a)` | stand it at `p`, optionally setting facing |
| `s:rotate()` / `s:rotate(a)` | facing in radians, keeping position; a billboard faces the camera, so this has no visible effect |
| `s:scale()` / `s:scale(k)` | uniform scale — world size when fixed, a screen-size multiplier when a billboard |
| `s:alpha()` / `s:alpha(a)` | opacity `0..1`, combined with the PNG's own transparency |
| `s:tint()` / `s:tint(r, g, b, a)` | colour overlay `0..255`, the fourth component being blend strength; `nil` clears it |
| `s:billboard()` / `s:billboard(b)` | a fixed upright quad, or a [camera-facing](#billboard) screen blit |
| `s:visible()` / `s:visible(b)` | whether it is in the 3D scene; `false` takes it out and keeps the sprite |
| `s:clickable()` / `s:clickable(b)` | the pick surface — [fixed sprites only](#clickability) |
| `s:onClick()` / `s:onClick(fn)` | `fn(s, button, x, y)` fired on click, also delivered as [`SpriteClicked`](../event.md#world-ghosts-and-sprites) |
| `s:image()` | the addon-relative image path |
| `s:exists()` | is it still in the world? `false` once the collection removed it |

A sprite's **image** is read-only: the texture is sampled when the sprite is placed, so another image is
another sprite. A fixed sprite is about a tile tall at scale `1`, its width following the image aspect.

## Billboard

`s:billboard(true)` makes a sprite **always face the camera** at a **constant screen size** — a screen-space
blit anchored at the sprite's world point, so rotating the camera or zooming leaves it square-on and the
same number of pixels. It is the ergonomic, gob-anchored version of drawing an image at
[`hafen.player():worldToScreen`](../player.md) inside a [HUD overlay](../ui/custom.md#overlays), and it
draws **on top** of the 3D scene, with no depth occlusion.

```lua
local b = hafen.render():sprite():add(icon, p):billboard(true):scale(2)
```

A billboard is drawn at the image's **native pixel size**, DPI-scaled like the HUD, times the scale, and
bottom-centred on its world point, so it "stands" there. Because it is a flat 2D image, `:rotate` is stored
but has no visible effect and `:scale` acts as a screen-size multiplier. `:alpha` and `:tint` work as they
do on a fixed sprite. It has no world mesh, so it is never [clickable](#clickability); select it by other
means, such as your own list.

> **`:billboard` rebuilds the visual.** It is the one property that decides *which* thing is drawn, so
> writing it re-mills the sprite in place — same sprite, same position, same look, a different picture.
> Everything else is applied live.

## Clickability

A **fixed** sprite can be made clickable with `s:clickable(true)`, exactly like a
[ghost](../ghost.md#clickability). It gains a pick surface, and a click on it is detected **client-side**
and **consumed** before any server click, so you never walk or interact and nothing reaches the server. Both
the per-sprite `:onClick(fn)` and the owner-scoped [`SpriteClicked`](../event.md#world-ghosts-and-sprites)
event fire; `SpriteClicked` reaches only *your* addon, since a sprite is private to the addon that made it.

```lua
local s = hafen.render():sprite():add(icon, p)
  :clickable(true)
  :onClick(function(s, button, x, y)     -- 1 = left, 3 = right; x, y = the world point
    hafen.log():write(("clicked my sprite (button %d)"):format(button))
  end)
```

> **Billboards are click-through.** A billboard has no world geometry, so it never wins a pick:
> `:clickable` and `:onClick` on one are harmless no-ops. Use a fixed sprite when you need click selection.

## An image on a gob is an overlay

`hafen.render():sprite()` stands an image at a **fixed world point**. To hang one on a *game object* so it
moves with it every frame, the verb is [`gob:overlay()`](../gob.md#overlays): it keys the thing per addon,
reads back through that same collection, carries the same `:scale`/`:alpha`/`:tint`/`:billboard` setters,
and **dies with the gob**, so a felled tree takes the image on it with it.

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.asset():get("marker.png")
local prey = hafen.world():gob():nearest(function(g) return (g:name() or ""):find("rabbit") end)
if prey then
  prey:overlay():add("hunt"):image(icon):scale(1.5):offset(0, 0, 14)
end
```

The overlay keeps its **own** facing and scale, so `ov:rotate` and `ov:scale` work on it; its position is
the gob's, and what you set is `ov:offset`, which moves it where it stands.

A sprite of this collection takes no anchor of its own, and there is nowhere to pass one: anchoring to a
game object is the gob's own collection, and standing at a fixed point is this one.

> **Gizmo.** A sprite is transformable by the [gizmo](../ghost.md#the-transform-gizmo) for free: it exposes
> the same `:position`, `:rotate` and `:scale` a ghost does, and the gizmo drives anything that does.

## See also

- [models](models.md) — the same thing with a glTF mesh instead of an image
- [`hafen.asset`](../asset.md) — loading the PNG a sprite takes
- [`hafen.ghost`](../ghost.md) — the game's own props, and the gizmo that moves any of these
- [drawing](../ui/drawing.md) — the same image drawn on screen instead of in the world
- [events](../event.md#world-ghosts-and-sprites) — `SpriteClicked`

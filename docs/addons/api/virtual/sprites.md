# hafen.virtual: an image in the world

`hafen.virtual():sprite():add(asset, anchor)` stands a PNG **in the 3D world**, on the same client-only
world-entity core a [ghost](ghosts.md) uses: a game object with no server id, so nothing reaches the server
and nothing here is protected.

```lua
local icon = hafen.asset():get("icon.png")
local s = hafen.session():current()                           -- the character on screen
local p = s:player():gob():position()
local sprite = hafen.virtual():sprite():add(icon, p):scale(3) -- ~3 tiles tall, at its feet
sprite:rotate(math.pi / 2):alpha(0.8)                         -- 90 degrees, slightly translucent
hafen.virtual():sprite():remove(sprite)                       -- or let reload or disable clear it
```

`asset` is a [`hafen.asset`](../asset/README.md) **image handle** —
[handle-only](README.md#the-anchor-is-an-argument), so a path string is an error — and the
[anchor](README.md#the-anchor-is-an-argument) is a [Position](../position.md) to stand it
at a point or a [Gob](../gob.md) to make it follow one.
Everything else is a setter on what comes back, and every setter returns the sprite, so a whole placement is
one chain. A new sprite has scale `1`, full opacity, no tint, faces `"fixed"` and is not clickable.

## The sprite

The [shared vocabulary](README.md#one-vocabulary-every-kind) — `:position`, `:offset`, `:rotate`, `:scale`,
`:alpha`, `:tint`, `:visible`, `:clickable`, `:onClick`, `:exists` — plus the two verbs only a sprite has.

| Method | Description |
|---|---|
| `s:facing()` / `s:facing(mode)` | how it meets the viewer: `"fixed"`, `"camera"` or `"screen"` — see [facing](#facing) |
| `s:image()` | the addon-relative image path |

A sprite's **image** is read-only: the texture is sampled when the sprite is placed, so another image is
another sprite. A `"fixed"` or `"camera"` sprite is about a tile tall at scale `1`, its width following the
image aspect, and `:scale` is its world size; a `"screen"` one is drawn at native pixel size and `:scale`
multiplies that.

## Facing

A sprite is a flat picture, so how it meets the viewer is a property of its own, `s:facing(mode)`:

| Mode | What it draws |
|---|---|
| `"fixed"` | a textured **quad** standing upright in the world at the sprite's own angle, about a tile tall, drawn double-sided |
| `"camera"` | the same world quad, **turned to the viewer** in yaw and pitch, keeping its world size |
| `"screen"` | a **screen-space blit** that always faces the camera at a constant screen size |

`"fixed"` and `"camera"` are both true world geometry, so world-scale applies and each occludes and is
occluded like anything else in the scene. The difference is where the angle comes from: `"fixed"` uses the
sprite's own `:rotate`, and `"camera"` turns to face you, so a picture stays square-on and readable from
wherever you are looking without giving up its world size or its place in the depth of the scene —
`:rotate` is stored but unused while it does. Any other mode raises, naming the three.

```lua
local b = hafen.virtual():sprite():add(icon, p):facing("screen"):scale(2)
local c = hafen.virtual():sprite():add(icon, prey):facing("camera"):offset(0, 0, 14)
```

> **A camera-facing quad rises along the camera's own *up* axis.** Tilt all the way to a top-down view and
> that axis is horizontal, so the picture lies in the horizontal plane through its anchor — and at ground
> level that is the terrain's own plane, which swallows it. Anchor it to a game object and lift it with
> `:offset(0, 0, z)`; one standing at a **point** has no lift of its own, so give it a gob anchor or keep the
> camera tilted.

A `"screen"` sprite is the ergonomic, world-anchored version of drawing an image at
[`s:world():worldToScreen`](../world.md#the-screen-and-the-world) inside a [HUD overlay](../ui/overlay.md). It
is drawn at the image's own size in [design pixels](../ui/pixels.md), times the scale, and bottom-centred on
its world point so it "stands" there — and it draws **on top** of the 3D scene, with no depth occlusion.
Because it is a flat 2D image, `:rotate` is stored but has no visible effect and `:scale` acts as a
screen-size multiplier. `:alpha` and `:tint` work exactly as they do on a `"fixed"` one.

> **`:facing` rebuilds the visual.** It is the one property that decides *which* thing is drawn, so writing
> it re-mills the sprite in place — same sprite, same position, same look, a different picture. Everything
> else is applied live.

## Clickability

A sprite with world geometry — `"fixed"` or `"camera"` — can be made clickable with `s:clickable(true)`,
exactly like a [ghost](ghosts.md#clickability). It gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing
reaches the server. Both the per-sprite `:onClick(fn)` and the owner-scoped
[`SpriteClicked`](../event/bus/world.md#world-ghosts-and-sprites) event fire; `SpriteClicked` reaches only
*your* addon, since a sprite is private to the addon that made it.

```lua
local s = hafen.virtual():sprite():add(icon, p)
  :clickable(true)
  :onClick(function(s, button, x, y)     -- 1 = left, 3 = right; x, y = the world point
    hafen.log():write(("clicked my sprite (button %d)"):format(button))
  end)
```

> **A `"screen"` sprite is click-through.** It has no world geometry, so it never wins a pick: `:clickable`
> and `:onClick` on one are harmless no-ops. Use a `"fixed"` or `"camera"` sprite when you need click
> selection.

## Following a game object

Pass a [Gob](../gob.md) as the anchor and the sprite tracks it every frame, wherever it goes, and
`s:offset(x, y, z)` says where it sits relative to that gob in world units with `z` up.

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.asset():get("marker.png")
local w = hafen.session():current():world()
local prey = w:gob():nearest(function(g) return (g:name() or ""):find("rabbit") end)
if prey then
  hafen.virtual():sprite():add(icon, prey):scale(1.5):offset(0, 0, 14)
end
```

It keeps its **own** facing and scale, so `:rotate` and `:scale` still work on it, and it **dies with the
gob**: a felled tree takes the image on it with it. It is listed at that gob by
[`gob:overlay():list()`](../overlay.md) as a read-only entry, so "what is drawn at this gob?" has one
complete answer — but you address it through this collection, which is the one that placed it.

A sprite exposes the same `:position`, `:rotate` and `:scale` a ghost does, so a drag handle written for one
drives the other unchanged.

## See also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [models](models.md) — the same thing with a glTF mesh instead of an image
- [widgets](widgets.md) — the same three facing modes, on a whole window standing in the world
- [`hafen.asset`](../asset/README.md) — loading the PNG a sprite takes
- [drawing](../ui/drawing.md) — the same image drawn on screen instead of in the world
- [events](../event/bus/world.md#world-ghosts-and-sprites) — `SpriteClicked`

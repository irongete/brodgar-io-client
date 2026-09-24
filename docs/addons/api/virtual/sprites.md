# hafen.virtual: An Image in the World

`hafen.virtual():sprite():add(asset, anchor)` stands a PNG in the 3D world on the client-only core a [ghost](ghosts.md) uses: no server id, nothing reaches the server, nothing protected.

```lua
local icon = hafen.asset():get("icon.png")
local session = hafen.session():current()                           -- the character on screen
local position = session:player():gob():position()
local sprite = hafen.virtual():sprite():add(icon, position):scale(3) -- ~3 tiles tall, at its feet
sprite:rotate(math.pi / 2):alpha(0.8)                               -- 90 degrees, slightly translucent
hafen.virtual():sprite():remove(sprite)                             -- or let reload or disable clear it
```

---

| Rule | Detail |
|---|---|
| `asset` | A [`hafen.asset`](../asset/README.md) image handle, [handle-only](README.md#the-anchor-is-an-argument): a path string raises. |
| The anchor | A [Position](../position.md) to stand it at a point or a [Gob](../gob.md) to follow one ([the anchor](README.md#the-anchor-is-an-argument)). |
| Defaults | Scale `1`, full opacity, no tint, facing `"fixed"`, not clickable. Every setter returns the sprite. |

## The sprite

The [shared vocabulary](README.md#one-vocabulary-every-kind) plus its own.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `sprite:facing()` / `sprite:facing(mode)` | the sprite | Unprotected | How it meets the viewer: `"fixed"`, `"camera"` or `"screen"` ([facing](#facing)). |
| `sprite:image()` | `string` | Unprotected | The addon-relative image path. Read-only: the texture is sampled when placed, so another image is another sprite. |

| Rule | Detail |
|---|---|
| Size | A `"fixed"` or `"camera"` sprite is about a tile tall at scale `1`, width following the image aspect, and `:scale` is its world size. A `"screen"` one draws at native pixel size and `:scale` multiplies that. |

## Facing

| Mode | Draws |
|---|---|
| `"fixed"` | A textured quad standing upright at the sprite's own angle (`:rotate`), about a tile tall, double-sided. |
| `"camera"` | The same world quad turned to the viewer in yaw and pitch, keeping its world size. `:rotate` is stored but unused. |
| `"screen"` | A screen-space blit facing the camera at a constant screen size. |

```lua
local prey = session:world():gob():nearest("rabbit")
local badge = hafen.virtual():sprite():add(icon, position):facing("screen"):scale(2)
local marker = hafen.virtual():sprite():add(icon, prey):facing("camera"):offset(0, 0, 14)
```

| Rule | Detail |
|---|---|
| World geometry | `"fixed"` and `"camera"` are true geometry: world-scale applies, and each occludes and is occluded like anything in the scene. Any other mode raises naming the three. |
| A camera-facing quad rises along the camera's up axis | Tilted to a top-down view that axis is horizontal. The picture lies in the plane through its anchor, which at ground level the terrain swallows. Anchor it to a game object and lift it with `:offset(0, 0, z)`. One at a point has no lift, so give it a gob anchor or keep the camera tilted. |
| `"screen"` | The world-anchored version of drawing an image at [`session:world():worldToScreen`](../world.md#the-screen-and-the-world) in a [HUD overlay](../ui/overlay.md). The image is its own size in [design pixels](../ui/pixels.md) times the scale, bottom-centred on its world point. It is drawn on top of the scene with no depth occlusion. `:rotate` has no visible effect. `:alpha` and `:tint` work as on `"fixed"`. No [`:outline`](README.md#one-vocabulary-every-kind) ring is drawn. |
| `:facing` rebuilds the visual | The one property that decides which thing is drawn: writing it re-mills the sprite in place, same position and look. Everything else applies live. |

## Clickability

A sprite with world geometry (`"fixed"`, `"camera"`) is made clickable with `sprite:clickable(true)`, as a [ghost](ghosts.md#clickability) is. It gains a pick surface. A click is detected client-side and consumed before any server click.

```lua
local sprite = hafen.virtual():sprite():add(icon, position)
  :clickable(true)
  :onClick(function(clicked_sprite, button, world_x, world_y)     -- 1 = left, 3 = right; then the clicked world point
    hafen.log():write(("clicked my sprite (button %d)"):format(button))
  end)
```

| Rule | Detail |
|---|---|
| Both fire | The per-sprite `:onClick(fn)` and the owner-scoped [`SpriteClicked`](../event/bus/world.md#world-ghosts-and-sprites) event, which reaches only your addon. |
| A `"screen"` sprite is click-through | No world geometry, so it never wins a pick. `:clickable` and `:onClick` on one are no-ops. Use `"fixed"` or `"camera"` for selection. |

## Following a game object

A [Gob](../gob.md) anchor makes the sprite track it every frame. `sprite:offset(x, y, z)` says where it sits relative to the gob in world units, `z` up.

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.asset():get("marker.png")
local world = hafen.session():current():world()
local prey = world:gob():nearest(function(gob) return (gob:name() or ""):find("rabbit") end)
if prey then
  hafen.virtual():sprite():add(icon, prey):scale(1.5):offset(0, 0, 14)
end
```

| Rule | Detail |
|---|---|
| Its own facing and scale | `:rotate` and `:scale` still work on it. |
| Dies with the gob | A felled tree takes the image on it. |
| Listed at the gob | [`gob:overlay():list()`](../overlay.md) shows it as a read-only entry. Address it through this collection. |
| One drag handle | The same `:position`, `:rotate` and `:scale` a ghost has, so a handle written for one drives the other. |

---

## See Also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [Models](models.md) — the same thing with a glTF mesh instead of an image.
- [Widgets](widgets.md) — the same facing modes, on a whole window standing in the world.
- [`hafen.asset`](../asset/README.md) — loading the PNG a sprite takes.
- [Drawing](../ui/drawing.md) — the same image drawn on screen instead of in the world.
- [Events](../event/bus/world.md#world-ghosts-and-sprites) — `SpriteClicked`.

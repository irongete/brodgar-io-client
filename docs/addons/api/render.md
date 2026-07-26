# hafen.render — custom images & models (non-`.res`)

Render **custom assets that ship with your addon** — not engine `.res` resources. This is the sibling of
[`hafen.ghost`](ghost.md) (which places the game's own `.res` models in the world): `hafen.render` is for
**your own files**. It loads **PNG images** and draws them on screen, and stands them **in the world** as fixed
or camera-facing **sprites**; custom 3D models (glTF) join them later on the same handle model.

> **Safe-tier — not gated.** A custom image is a client-side texture that never reaches the server and grants
> no gameplay advantage, so it needs **no** `actions` permission — exactly like [`hafen.ui.overlay`](ui.md#overlays)
> or a [ghost](ghost.md). ([D-034](../../../specs/addons/decisions.md).)

Under the hood a `.res` file is *already* a PNG the client wraps in a GPU texture; `hafen.render` just exposes
that substrate directly, skipping the `.res` container. Everything here is **bridge-owned**: every image your
addon loads is disposed automatically on reload / disable / relogin, so it never leaks a GPU texture.

## Loading an image

| Function | Returns | Description |
|---|---|---|
| `hafen.render.image(path)` | [image handle](#image-handle) | load a PNG from your addon's folder into a cached, bridge-owned texture |

`path` is **relative to your addon's own folder** (e.g. `"icon.png"`, `"img/sign.png"`). Absolute paths and
`..` escapes are **rejected** — an addon reads only its own assets ([D-017](../../../specs/addons/decisions.md)).
PNG (with alpha) is the recommended format; anything `ImageIO` decodes (JPG/GIF/BMP) also works.

Loading is **cached**: repeated `hafen.render.image` of the same path returns the **same handle** (one texture
per path). Call it from **setup code** — `OnLoad`, `OnEnterWorld`, or a command — **not** from inside a draw
callback (v1 decodes the file synchronously). A bad path or an undecodable file raises a clear error.

## Image handle

| Method | Description |
|---|---|
| `img:size()` | `{w, h}` — the image's pixel dimensions |
| `img:dispose()` | free the GPU texture now (also automatic on reload/disable/relogin) |

You rarely need `:dispose()` — teardown frees every image for you. Use it only to release a large image early.
A disposed handle simply draws nothing thereafter.

## Drawing on screen

Inside any draw callback ([`onDraw`](ui.md#custom-windows--widgets), [`hafen.ui.overlay`](ui.md#overlays),
[`hafen.ui.gobOverlay`](ui.md#overlays)), draw an image through the [`g` wrapper](ui.md#the-g-draw-wrapper):

| Method | Description |
|---|---|
| `g:image(img, x, y)` | draw at native size, top-left at `(x, y)` |
| `g:image(img, x, y, w, h)` | draw scaled into a `w × h` box |
| `g:aimage(img, x, y, ax, ay)` | anchored — `ax`/`ay` `0..1` pick which point of the image sits at `(x, y)` (mirrors [`g:atext`](ui.md#the-g-draw-wrapper)) |

A `nil`, wrong-type, or disposed handle **draws nothing** (the draw verbs are forgiving — they never throw).

```lua
local icon                                     -- upvalue for the draw callbacks below

hafen.events.on("OnLoad", function()
  icon = hafen.render.image("icon.png")        -- load once from addons/<me>/icon.png
  local s = icon:size()
  hafen.log(("icon is %dx%d"):format(s.w, s.h))
end)

hafen.ui.window{
  title = "My addon", size = { 120, 60 },
  onDraw = function(g, w, h)
    if icon then
      g:image(icon, 4, 4)                       -- native size
      g:image(icon, 4, 40, 16, 16)              -- the same image scaled to 16×16
    end
  end,
}

hafen.ui.overlay(function(g, w, h)
  if icon then g:aimage(icon, w - 6, 6, 1.0, 0.0) end   -- top-right corner (right-anchored)
end)
```

> **Alpha works.** A PNG's transparency is preserved, so an icon with a transparent background composites over
> whatever is behind it — the same as the client's own art.

## Standing an image in the world

`hafen.render.sprite{…}` stands a PNG **in the 3D world** — the non-`.res` sibling of a [ghost](ghost.md), built
on the **same client-only world-entity core**. So it is a full transform handle (and
[gizmo](ghost.md#transform-gizmo-move--rotate--scale)-compatible), and it is **safe-tier, not gated**
([D-034](../../../specs/addons/decisions.md)): a `Gob` with no server id, exactly like a ghost — nothing reaches
the server. Two forms, chosen by `billboard`:

- **fixed** (`billboard = false`, default) — a textured **quad** standing **upright** in the world, facing `a`,
  ~1 tile tall, drawn double-sided. It is true world geometry: world-rotate and world-scale apply.
- **[billboard](#billboard-camera-facing)** (`billboard = true`) — a screen-space blit that always **faces the
  camera** and is a constant screen size. Position and gizmo-move apply; world-rotate/scale do not.

| Function | Returns | Description |
|---|---|---|
| `hafen.render.sprite{image=, x, y [, a] [, scale] [, alpha] [, tint] [, billboard] [, clickable] [, onClick]}` | [sprite handle](#sprite-handle) | stand a PNG in the world at world coords `(x, y)`, facing `a` |

Options:

| Option | Default | Meaning |
|---|---|---|
| `image` | *(required)* | a [`hafen.render.image`](#loading-an-image) handle **or** an addon-relative path string (auto-loaded + cached) |
| `x`, `y` | *(required¹)* | world coordinates, like [`hafen.gob.pos`](gob.md) |
| `a` | `0` | facing angle in **radians** (a billboard ignores it — it faces the camera) |
| `scale` | `1` | uniform scale; a **fixed** sprite is ~1 tile tall at `1` (width follows the image aspect), a **billboard** is its native pixel size × `scale` |
| `alpha` | `1` | opacity `0..1`; combines with the PNG's own transparency |
| `tint` | *(none)* | colour overlay `{r=, g=, b=[, a=]}` `0..255` (`a` = blend strength) |
| `billboard` | `false` | `false` = a fixed upright quad; `true` = a [camera-facing](#billboard-camera-facing) screen blit |
| `clickable` | `false` | opt into the [click event](#clickability--the-spriteclicked-event) — **fixed sprites only** (a billboard has no world mesh, so it is never picked) |
| `onClick` | *(none)* | `fn(s, button, x, y)` fired on click (also delivered as the [`SpriteClicked`](events.md#world-ghosts--sprites) event) |
| `follow` | *(none)* | **anchor to a gob** — a gob id, `"player"`, or `"me"`: the sprite tracks it every frame (see [Anchoring](#anchoring-to-a-gob)) |
| `offset` | *(none)* | fixed world offset `{x=, y=, z=}` from the followed gob (`z` = up) |

¹ `x`/`y` are optional when `follow` is given — the gob supplies the position.

Returns `nil` if you are not in the world yet (no map view).

### Sprite handle

The same transform surface as a [ghost handle](ghost.md), with `:image()` in place of `:res()`:

| Method | Description |
|---|---|
| `s:move(x, y [, a])` | reposition (world coords), optionally re-facing — **detaches** any `:follow` anchor |
| `s:rotate(a)` | set facing (radians), keeping position (a **billboard** faces the camera, so this has no visible effect) |
| `s:scale(k)` | uniform scale (fixed: `1` = ~1 tile tall; billboard: screen-size multiplier) |
| `s:alpha(a)` | opacity `0..1` |
| `s:tint(color\|nil)` | colour overlay `{r=,g=,b=[,a=]}`; `nil` clears |
| `s:clickable(bool)` | toggle the pick surface (**fixed sprites only** — see [Clickability](#clickability--the-spriteclicked-event)) |
| `s:show()` / `s:hide()` | add / remove from the scene (keeps the sprite) |
| `s:follow(gob [, {x=,y=,z=}])` | **anchor** to a gob and auto-follow it; `s:follow(nil)` detaches (see [Anchoring](#anchoring-to-a-gob)) |
| `s:offset{x=, y=, z=}` | move it relative to the followed gob (keeps following) |
| `s:pos()` | `{x, y, a, scale}` (+ `following` = the anchored gob id, if any) — the live transform |
| `s:image()` | the addon-relative image path |
| `s:destroy()` | remove now (also automatic on reload/disable/relogin) |

The verbs **chain** (each returns the handle): `s:move(x, y):rotate(a):scale(2)`.

```lua
local icon = hafen.render.image("icon.png")
local p = hafen.gob.pos("player")
local s = hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }  -- ~3 tiles tall, at your feet
s:rotate(math.pi / 2):alpha(0.8)     -- face 90°, slightly translucent
-- ... later
s:destroy()                          -- or just let reload/disable clean it up
```

> **Gizmo.** A sprite is gizmo-transformable for free — it exposes the same `:move`/`:rotate`/`:scale` handle a
> ghost does, and the [transform gizmo](ghost.md#transform-gizmo-move--rotate--scale) drives any such handle.

### Billboard (camera-facing)

Pass `billboard = true` for a sprite that **always faces the camera** and is a **constant screen size** — a
screen-space blit anchored at the sprite's world point (rotate the camera or zoom and it stays square-on and the
same number of pixels). It is the ergonomic, gob-anchored version of drawing an image at
[`hafen.player.worldToScreen`](player.md) in a [`hafen.ui.overlay`](ui.md#overlays); it draws **on top** of the 3D
scene (no depth occlusion).

```lua
local icon = hafen.render.image("icon.png")
local p = hafen.gob.pos("player")
local b = hafen.render.sprite{ image = icon, x = p.x, y = p.y, billboard = true, scale = 2 }  -- 2× native px, faces you
-- b:move(x, y) still works (and the gizmo moves it); b:scale(k) resizes it on screen.
```

A billboard is drawn at the image's **native pixel size** (DPI-scaled like the HUD) × `scale`, bottom-centred on
its world point (so it "stands" there; a `follow` `offset = {z=…}` floats it overhead). Because it is a flat 2D
image, **`:rotate` and `:scale`-as-world-size do not apply** — `:rotate` is stored but has no visible effect, and
`:scale` acts as a screen-size multiplier. `:alpha` and `:tint` work (opacity + a colour multiply). It has no
world mesh, so it is **not** [clickable](#clickability--the-spriteclicked-event) (nothing to pick) — select it in
an editor by other means (e.g. your own list, or the `planner` example addon's `:planner select`).

### Clickability & the `SpriteClicked` event

A **fixed** sprite can be made **clickable** (`clickable = true` at create, or `s:clickable(true)` later), exactly
like a [ghost](ghost.md#clickability--the-ghostclicked-event-v2): it gains a pick surface, and a click on it is
detected **client-side** and **consumed** before any server click — so you never walk/interact, and nothing
reaches the server (still safe-tier). Both the per-sprite `onClick(s, button, x, y)` and the owner-scoped
[`SpriteClicked`](events.md#world-ghosts--sprites) event fire; `SpriteClicked` reaches only *your* addon (a sprite
is private to the addon that made it).

```lua
local s = hafen.render.sprite{
  image = "icon.png", x = wx, y = wy,
  clickable = true,
  onClick = function(s, button, x, y)          -- 1 = left, 3 = right; x,y = clicked world point
    hafen.log(("clicked my sprite (button %d)"):format(button))
  end,
}
```

> **Billboards are click-through.** A billboard has no world geometry, so it never wins a pick — `clickable`/
> `onClick` on a billboard are harmless no-ops. Use a fixed sprite when you need click selection.

### Anchoring to a gob

A sprite can be **anchored to a gob** so it moves with it automatically, every frame, with no per-tick code of
your own — the world-space analog of a [`hafen.ui.gobOverlay`](ui.md#overlays). Give a `follow` target (a gob id
from a read like [`hafen.world.gobs`](world.md), or `"player"`/`"me"`) and an optional world `offset` (`z` is up,
so `{z=10}` floats it above the target's head):

| | |
|---|---|
| `hafen.render.sprite{ image=, follow=gob, offset={x=,y=,z=} }` | create it already anchored (`x`/`y` not needed) |
| `s:follow(gob [, {x=,y=,z=}])` | anchor an existing sprite |
| `s:follow(nil)` | detach — it stays where it currently is |
| `s:offset{x=, y=, z=}` | change the offset while it keeps following |
| `s:move(x, y)` | a manual move **detaches** the follow (you take control) |

The sprite keeps its **own** facing and scale while anchored, so `:rotate`/`:scale` still work. The target is
re-resolved each frame, so it survives the gob unloading/reloading (it holds position if the gob is gone).

```lua
-- a marker that floats above a creature and follows it around
local icon = hafen.render.image("marker.png")
local prey = hafen.world.nearest(function(g) return (g.name or ""):find("rabbit") end)
if prey then
  local s = hafen.render.sprite{ image = icon, scale = 1.5, follow = prey.id, offset = { z = 14 } }
  -- s follows the rabbit; s:offset{ z = 20 } raises it; s:follow(nil) drops it in place; s:destroy() removes it
end
```

## Coming next

The same handle model extends further (all on the shared world-entity core + the
[transform gizmo](ghost.md#transform-gizmo-move--rotate--scale)):

| Planned | What |
|---|---|
| `hafen.render.model(path)` + `hafen.render.object{model=, …}` | a custom **glTF** 3D model placed in the world (**R3**) |

See [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) and
[18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) for the full design.

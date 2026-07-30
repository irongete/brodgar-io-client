# hafen.render — custom images & models (non-`.res`)

Render **custom assets that ship with your addon** — not engine `.res` resources. This is the sibling of
[`hafen.ghost`](ghost.md) (which places the game's own `.res` models in the world): `hafen.render` is for
**your own files**. It loads **PNG images** and draws them on screen, stands them **in the world** as fixed
or camera-facing **sprites**, and places custom **glTF 3D models** in the world — all on the same handle model.

> **Safe-tier — not gated.** A custom image is a client-side texture that never reaches the server and grants
> no gameplay advantage, so it needs **no** `actions` permission — exactly like [`hafen.ui.overlay`](ui.md#overlays)
> or a [ghost](ghost.md). ([D-034](../../../specs/addons/decisions/rendering.md).)

Under the hood a `.res` file is *already* a PNG the client wraps in a GPU texture; `hafen.render` just exposes
that substrate directly, skipping the `.res` container. Everything here is **bridge-owned**: every image your
addon loads is disposed automatically on reload / disable / relogin, so it never leaks a GPU texture.

## Loading an image

| Function | Returns | Description |
|---|---|---|
| `hafen.render.image(path)` | [image handle](#image-handle) | load a PNG from your addon's folder into a cached, bridge-owned texture |

`path` is **relative to your addon's own folder** (e.g. `"icon.png"`, `"img/sign.png"`). Absolute paths and
`..` escapes are **rejected** — an addon reads only its own assets ([D-017](../../../specs/addons/decisions/security-sandbox.md)).
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
([D-034](../../../specs/addons/decisions/rendering.md)): a `Gob` with no server id, exactly like a ghost — nothing reaches
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

## Loading a 3D model

`hafen.render.model(path)` loads a **glTF 2.0 static model** — your own `.glb` (single-file binary, **preferred**)
or `.gltf` + buffers — into a cached, bridge-owned mesh handle, exactly like [`hafen.render.image`](#loading-an-image)
loads a PNG. The parser is pure Java (no native deps), and the model is decoded **synchronously** on the calling
thread, so call it from **setup code** (`OnLoad`/`OnEnterWorld`/a command), never inside a draw.

| Function | Returns | Description |
|---|---|---|
| `hafen.render.model(path)` | [model handle](#model-handle) | load a glTF model from your addon's folder into cached, bridge-owned geometry |

`path` is **relative to your addon's own folder** (e.g. `"chair.glb"`, `"props/tree.glb"`); absolute paths and
`..` escapes are rejected ([D-017](../../../specs/addons/decisions/security-sandbox.md)). External `.gltf` buffer/texture files are
resolved relative to the model and re-checked against your folder. Loading is cached (one parse per path). A
malformed file — or one using an **unsupported feature** — raises a clear error that **names** the feature (never
a crash).

> **The subset (R3a/R3b/R3c — static, textured, lit).** Supported: `.glb`/`.gltf`, triangle meshes (`POSITION` +
> indices), multiple nodes/meshes/primitives with **baked node transforms**, **multiple materials**, and — the
> colour — a **`baseColorTexture`** (its image embedded via a `bufferView`, a `data:` URI, or an external PNG/JPG;
> decoded once into a shared GPU texture) **×** the **`baseColorFactor`** multiply. Per-material **alpha mode**
> (`OPAQUE` / `MASK` alpha-test / `BLEND` translucency) and **`doubleSided`** cull are honoured.
> **Lit (R3c):** the parser bakes per-vertex **`NORMAL`s** (or computes smooth ones when the mesh has none) and each
> material adds a Phong light state, so the model **shades with the world lights** like game geometry — not
> fullbright. The base colour (texture × factor) is the albedo the light modulates; **`emissiveFactor`** areas glow
> at full colour even in shadow. Reflectance uses the engine's neutral defaults (matte, no specular) — fidelity
> *approximates* a PBR viewer, which is fine for props. sRGB needs no handling (the engine does not sRGB-convert
> model textures). **Not yet:** `emissiveTexture`, per-texture sampler wrap/filter, a non-zero
> `baseColorTexture.texCoord` (`TEXCOORD_1`), full PBR (metallic/roughness/occlusion). **Never:** skins, animation,
> morph targets, Draco/meshopt, sparse accessors — a model using one fails with a named error.

### Model handle

| Method | Description |
|---|---|
| `mdl:bounds()` | `{min={x,y,z}, max={x,y,z}, size={x,y,z}}` — the model's axis-aligned bounds in **world units** (after the basis conversion below) |
| `mdl:info()` | `{prims, textured, lit, textures, verts, tris}` — what the parser produced: primitive count, how many are textured, how many are **lit** (carry normals — R3c), distinct decoded textures, and vertex/triangle totals |
| `mdl:dispose()` | drop the geometry **and its shared textures** now (also automatic on reload/disable/relogin) |

> **Sizing tip.** glTF authored units vary wildly (a model may be 1 or 100 "units" tall). Read `mdl:bounds().size.z`
> and pick a `scale` so it stands the height you want — e.g. `scale = (2 * 11) / size.z` for ~2 tiles tall.

## Standing a 3D model in the world

`hafen.render.object{…}` stands a glTF model **in the 3D world** — the mesh sibling of a [sprite](#standing-an-image-in-the-world)
and a [ghost](ghost.md), built on the **same client-only world-entity core**. So it is a full transform handle
(and [gizmo](ghost.md#transform-gizmo-move--rotate--scale)-compatible), and it is **safe-tier, not gated**
([D-034](../../../specs/addons/decisions/rendering.md)): a `Gob` with no server id — nothing reaches the server.

| Function | Returns | Description |
|---|---|---|
| `hafen.render.object{model=, x, y [, a] [, scale] [, alpha] [, tint] [, clickable] [, onClick] [, follow] [, offset]}` | [object handle](#object-handle) | stand a glTF model in the world at world coords `(x, y)`, facing `a` |

Options mirror [`hafen.render.sprite`](#standing-an-image-in-the-world), with `model` in place of `image`:

| Option | Default | Meaning |
|---|---|---|
| `model` | *(required)* | a [`hafen.render.model`](#loading-a-3d-model) handle **or** an addon-relative path string (auto-loaded + cached) |
| `x`, `y` | *(required¹)* | world coordinates, like [`hafen.gob.pos`](gob.md) |
| `a` | `0` | facing angle in **radians** (rotates about the vertical) |
| `scale` | `1` | uniform scale **on top of** the baked model→world size |
| `alpha` | `1` | opacity `0..1` |
| `tint` | *(none)* | colour overlay `{r=, g=, b=[, a=]}` `0..255` (`a` = blend strength) |
| `clickable` | `false` | opt into the [click event](#clickability--the-objectclicked-event) (the mesh renders into the pick surface) |
| `onClick` | *(none)* | `fn(o, button, x, y)` fired on click (also the owner-scoped [`ObjectClicked`](events.md#world-ghosts--sprites) event) |
| `follow` | *(none)* | **anchor to a gob** — a gob id, `"player"`, or `"me"`: the object tracks it every frame |
| `offset` | *(none)* | fixed world offset `{x=, y=, z=}` from the followed gob (`z` = up) |

¹ `x`/`y` are optional when `follow` is given. Returns `nil` if you are not in the world yet (no map view).

### Object handle

The same transform surface as a [sprite](#sprite-handle)/[ghost](ghost.md) handle, with `:mesh()` in place of `:image()`:

| Method | Description |
|---|---|
| `o:move(x, y [, a])` | reposition (world coords), optionally re-facing — **detaches** any `:follow` anchor |
| `o:rotate(a)` | set facing (radians), keeping position |
| `o:scale(k)` | uniform scale on top of the baked size |
| `o:alpha(a)` | opacity `0..1` |
| `o:tint(color\|nil)` | colour overlay `{r=,g=,b=[,a=]}`; `nil` clears |
| `o:clickable(bool)` | toggle the pick surface (see [Clickability](#clickability--the-objectclicked-event)) |
| `o:show()` / `o:hide()` | add / remove from the scene (keeps the object) |
| `o:follow(gob [, {x=,y=,z=}])` | **anchor** to a gob and auto-follow it; `o:follow(nil)` detaches |
| `o:offset{x=, y=, z=}` | move it relative to the followed gob (keeps following) |
| `o:pos()` | `{x, y, a, scale}` (+ `following` = the anchored gob id, if any) |
| `o:mesh()` | the addon-relative model path |
| `o:destroy()` | remove now (also automatic on reload/disable/relogin) |

The verbs **chain** (each returns the handle): `o:move(x, y):rotate(a):scale(2)`.

```lua
local mdl                                          -- upvalue
hafen.events.on("OnLoad", function()
  mdl = hafen.render.model("props/chair.glb")      -- load once from addons/<me>/props/chair.glb
  local b = mdl:bounds()
  hafen.log(("chair is %.1f tall (world units)"):format(b.size.z))
end)

-- later, in the world:
local p = hafen.gob.pos("player")
local o = hafen.render.object{ model = mdl, x = p.x, y = p.y, a = 0, scale = 1 }
o:rotate(math.pi / 4):scale(1.5)                   -- face 45°, 1.5× — chained, gizmo-compatible
-- ... o:destroy()  (or let reload/disable clean it up)
```

> **Coordinate system & size.** glTF is right-handed, **+Y up**, in **metres**; the client's world is **Z up** with
> a tile-based scale. The loader bakes a fixed conversion once, so `+Y` (glTF up) becomes `+Z` (world up) and **1
> glTF metre = 1 tile** (≈ 11 world units) at `scale = 1`. The glTF **origin maps to the gob position**, so author a
> model with its **base at `Y = 0`** and it stands on the ground (like a ghost). Use `:scale` (or the gizmo) to
> resize.

### Clickability & the `ObjectClicked` event

An object can be made **clickable** (`clickable = true` at create, or `o:clickable(true)` later), exactly like a
[clickable sprite](#clickability--the-spriteclicked-event)/[ghost](ghost.md#clickability--the-ghostclicked-event-v2):
its mesh gains a pick surface, and a click on it is detected **client-side** and **consumed** before any server
click — so you never walk/interact, and nothing reaches the server (still safe-tier). Both the per-object
`onClick(o, button, x, y)` and the owner-scoped [`ObjectClicked`](events.md#world-ghosts--sprites) event fire;
`ObjectClicked` reaches only *your* addon.

> **Gizmo.** An object is gizmo-transformable for free — same `:move`/`:rotate`/`:scale` handle a ghost/sprite
> exposes, and the [transform gizmo](ghost.md#transform-gizmo-move--rotate--scale) drives any such handle.

The `planner` example addon puts all of this together: **`:planner object`** stands a shipped `cube.glb`, and the
same code path that handles its ghosts/sprites gives the model **click-select**, the **gizmo** (move/rotate/scale),
and **grid-anchored persistence** (it reloads at the same spot after a relog) — the editor-flow counterpart to
`:hello object`'s render demo.

See [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) and
[18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) for the full design.

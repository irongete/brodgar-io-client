# hafen.render: a glTF model in the world

`hafen.render.object{…}` stands a glTF model **in the 3D world** — the mesh sibling of a
[sprite](sprites.md) and a [ghost](../ghost.md), on the same client-only world-entity core: a game object
with no server id, so nothing reaches the server and nothing here is gated.

```lua
local mdl                                          -- upvalue
hafen.events.on("OnLoad", function()
  mdl = hafen.asset("props/chair.glb")             -- load once from addons/<me>/props/chair.glb
end)

-- later, in the world:
local p = hafen.player():gob():pos()
local o = hafen.render.object{ model = mdl, x = p.x, y = p.y, a = 0, scale = 1 }
o:rotate(math.pi / 4):scale(1.5)                   -- face 45 degrees, 1.5 times bigger; chained
```

## The model

A model is a [`hafen.asset`](../asset.md) **mesh handle** — `hafen.asset("props/chair.glb")` — holding a
glTF 2.0 static model: your own `.glb`, the single-file binary form and the one to prefer, or a `.gltf`
with its buffers beside it. The parser is pure Java with no native dependencies and decodes
**synchronously**, so load it from setup code (`OnLoad`, `OnEnterWorld`, a command) and never from inside a
draw callback. The handle answers [`mdl:bounds()`](../asset.md#mesh) with a world-unit box and
[`mdl:info()`](../asset.md#mesh) with what the parser produced.

> **Sizing.** glTF authored units vary wildly — a model may be one unit tall or a hundred. Read
> `mdl:bounds().size.z` and pick a `scale` that stands it the height you want.

> **Coordinate system.** glTF is right-handed, +Y up, in metres; the client's world is Z up with a
> tile-based scale. The loader bakes a fixed conversion once, so glTF's up becomes world up and **one glTF
> metre is one tile** at `scale = 1`. The glTF **origin maps to the gob position**, so author a model with
> its base at `Y = 0` and it stands on the ground, like a ghost.

### The glTF subset

**Supported.** `.glb` and `.gltf`; triangle meshes with positions and indices; multiple nodes, meshes and
primitives, with node transforms baked in; multiple materials; and, for the colour, a `baseColorTexture`
multiplied by `baseColorFactor`. The texture's image may be embedded through a buffer view, carried as a
`data:` URI, or sit beside the model as a PNG or JPG, and it is decoded once into a shared GPU texture.
Per-material alpha mode is honoured — `OPAQUE`, `MASK` alpha-test and `BLEND` translucency — as is
`doubleSided` culling.

**Lighting.** The parser bakes per-vertex normals, computing smooth ones when the mesh has none, and each
material adds a light state, so a model **shades with the world lights** the way game geometry does rather
than drawing fullbright: it darkens indoors and at night and catches the sun's direction outdoors. The base
colour, texture times factor, is the albedo the lights modulate, and `emissiveFactor` areas glow at full
colour even in shadow. Reflectance uses the engine's neutral defaults, matte and without specular, so the
result *approximates* a PBR viewer rather than matching one — which is what props need. sRGB needs no
handling, because the engine does not sRGB-convert model textures.

**Not supported yet.** `emissiveTexture`, per-texture sampler wrap and filter settings, a non-zero
`baseColorTexture.texCoord`, and full PBR: metallic, roughness and occlusion maps.

**Never.** Skins, animation, morph targets, Draco and meshopt compression, sparse accessors. A model using
one of these fails with an error that names the feature.

## Create (ungated)

`hafen.render.object(opts)` returns an [object handle](#object-handle), or `nil` if you are not in the world
yet. The options mirror [`hafen.render.sprite`](sprites.md), with `model` in place of `image`:

| Option | Default | Meaning |
|---|---|---|
| `model` | *required* | a [`hafen.asset`](../asset.md) **mesh handle** — [handle-only](README.md#handle-only); a path string is an error |
| `x`, `y` | *required* | world coordinates, like [`gob:pos()`](../gob.md) |
| `a` | `0` | facing angle in **radians**, about the vertical |
| `scale` | `1` | uniform scale **on top of** the baked model-to-world size |
| `alpha` | `1` | opacity `0..1` |
| `tint` | *none* | colour overlay `{r=, g=, b=, a=}`, `0..255`, where `a` is blend strength |
| `clickable` | `false` | opt into [the click event](#clickability); the mesh renders into the pick surface |
| `onClick` | *none* | `fn(o, button, x, y)` fired on click, also the owner-scoped [`ObjectClicked`](../events.md#world-ghosts-and-sprites) event |

## Object handle

The same transform surface as a [sprite](sprites.md#sprite-handle) or [ghost](../ghost.md#the-ghost-handle)
handle, with `:mesh()` in place of `:image()`. Every verb except `:pos()` and `:mesh()` returns the handle,
so calls chain.

| Method | Description |
|---|---|
| `o:move(x, y, a)` | reposition in world coords, optionally re-facing |
| `o:rotate(a)` | set facing in radians, keeping position |
| `o:scale(k)` | uniform scale on top of the baked size |
| `o:alpha(a)` | opacity `0..1` |
| `o:tint(color)` | colour overlay `{r=, g=, b=, a=}`; `nil` clears it |
| `o:clickable(bool)` | toggle the pick surface |
| `o:show()` / `o:hide()` | add to or remove from the scene, keeping the object |
| `o:pos()` | `{x, y, a, scale}` |
| `o:mesh()` | the addon-relative model path |
| `o:destroy()` | remove it now; also automatic on reload, disable and relogin |

## Clickability

An object can be made clickable — `clickable = true` at create, or `o:clickable(true)` later — exactly like
a [clickable sprite](sprites.md#clickability). Its mesh gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing reaches
the server. Both the per-object `onClick(o, button, x, y)` and the owner-scoped
[`ObjectClicked`](../events.md#world-ghosts-and-sprites) event fire, and `ObjectClicked` reaches only *your*
addon.

> **Gizmo.** An object is transformable by the [gizmo](../ghost.md#the-transform-gizmo) for free: the same
> `:move`, `:rotate` and `:scale` handle a ghost or a sprite exposes, and the gizmo drives any such handle.

The bundled **`planner`** addon puts all of this together: one command stands a shipped `.glb`, and the
same code path that handles its ghosts and sprites gives the model click-select, the gizmo, and
grid-anchored persistence, so it reloads at the same spot after a relog.

## See also

- [sprites](sprites.md) — an image in the world, and the anchoring both share
- [`hafen.asset`](../asset.md#mesh) — loading a `.glb`, and what `:bounds()` and `:info()` answer
- [`hafen.ghost`](../ghost.md) — the game's own props, and the transform gizmo
- [events](../events.md#world-ghosts-and-sprites) — `ObjectClicked`

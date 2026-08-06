# hafen.render: a glTF model in the world

`hafen.render():object():add(asset, p)` stands a glTF model **in the 3D world** — the mesh sibling of a
[sprite](sprites.md) and a [ghost](../ghost.md), on the same client-only world-entity core: a game object
with no server id, so nothing reaches the server and nothing here is gated.

```lua
local mdl                                          -- upvalue
hafen.event():on("Load", function()
  mdl = hafen.asset():get("props/chair.glb")       -- load once from addons/<me>/props/chair.glb
end)

-- later, in the world:
local p = hafen.player():gob():position()
local o = hafen.render():object():add(mdl, p)
o:rotate(math.pi / 4):scale(1.5)                   -- face 45 degrees, 1.5 times bigger; chained
```

## The model

A model is a [`hafen.asset`](../asset.md) **mesh handle** — `hafen.asset():get("props/chair.glb")` — holding
a glTF 2.0 static model: your own `.glb`, the single-file binary form and the one to prefer, or a `.gltf`
with its buffers beside it. The parser is pure Java with no native dependencies and decodes
**synchronously**, so load it from setup code (`Load`, `EnterWorld`, a command) and never from inside a
draw callback. The handle answers [`mdl:bounds()`](../asset.md#mesh) with a world-unit box and
[`mdl:info()`](../asset.md#mesh) with what the parser produced.

> **Sizing.** glTF authored units vary wildly — a model may be one unit tall or a hundred. Read
> `mdl:bounds().size.z` and pick a `:scale` that stands it the height you want.

> **Coordinate system.** glTF is right-handed, +Y up, in metres; the client's world is Z up with a
> tile-based scale. The loader bakes a fixed conversion once, so glTF's up becomes world up and **one glTF
> metre is one tile** at scale `1`. The glTF **origin maps to the gob position**, so author a model with
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

## Place one (ungated)

`hafen.render():object():add(model, p)` stands the object and hands it back, ready to configure. `model` is
a [`hafen.asset`](../asset.md) **mesh handle** — [handle-only](README.md#handle-only), so a path string is
an error — and `p` is a [Position](../world.md#the-position-type). Both are required: the scene resolves the
tile under an object as it enters it, so one with no place cannot be built at all. It **raises** if you are
not in the world yet, since there is no map view to stand it in. A new object has scale `1`, full opacity,
no tint and is not clickable.

## The object

The same vocabulary as a [sprite](sprites.md#the-sprite) or a [ghost](../ghost.md#the-ghost), with
`:mesh()` in place of `:image()`. Calling a property bare **reads**, calling it with a value **writes** and
returns the object, so calls chain.

| Method | Description |
|---|---|
| `o:position()` | where it stands, as a [Position](../world.md#the-position-type) |
| `o:position(p, a)` | stand it at `p`, optionally setting facing |
| `o:rotate()` / `o:rotate(a)` | facing in radians, keeping position |
| `o:scale()` / `o:scale(k)` | uniform scale on top of the baked size |
| `o:alpha()` / `o:alpha(a)` | opacity `0..1` |
| `o:tint()` / `o:tint(r, g, b, a)` | colour overlay `0..255`; `nil` clears it |
| `o:visible()` / `o:visible(b)` | whether it is in the 3D scene; `false` takes it out and keeps the object |
| `o:clickable()` / `o:clickable(b)` | the pick surface |
| `o:onClick()` / `o:onClick(fn)` | `fn(o, button, x, y)` fired on click, also delivered as [`ObjectClicked`](../event.md#world-ghosts-and-sprites) |
| `o:mesh()` | the addon-relative model path |
| `o:exists()` | is it still in the world? `false` once the collection removed it |

An object's **mesh** is read-only: the geometry is milled when the object is placed, so another model is
another object.

## Clickability

An object can be made clickable with `o:clickable(true)`, exactly like a
[clickable sprite](sprites.md#clickability). Its mesh gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing reaches
the server. Both the per-object `:onClick(fn)` and the owner-scoped
[`ObjectClicked`](../event.md#world-ghosts-and-sprites) event fire, and `ObjectClicked` reaches only *your*
addon.

> **Gizmo.** An object is transformable by the [gizmo](../ghost.md#the-transform-gizmo) for free: the same
> `:position`, `:rotate` and `:scale` a ghost or a sprite exposes, and the gizmo drives anything that does.

The bundled **`planner`** addon puts all of this together: one command stands a shipped `.glb`, and the
same code path that handles its ghosts and sprites gives the model click-select, the gizmo, and persistence,
so it reloads at the same spot after a relog.

## See also

- [sprites](sprites.md) — an image in the world, and the anchoring both share
- [`hafen.asset`](../asset.md#mesh) — loading a `.glb`, and what `:bounds()` and `:info()` answer
- [`hafen.ghost`](../ghost.md) — the game's own props, and the transform gizmo
- [events](../event.md#world-ghosts-and-sprites) — `ObjectClicked`

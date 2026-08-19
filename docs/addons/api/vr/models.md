# hafen.vr: a glTF model in the world

`hafen.vr():object():add(asset, anchor)` stands a glTF model **in the 3D world** — the mesh sibling of a
[sprite](sprites.md) and a [ghost](ghosts.md), on the same client-only world-entity core: a game object with
no server id, so nothing reaches the server and nothing here is protected.

```lua
local mdl                                          -- upvalue
hafen.event():on("Load", function()
  mdl = hafen.asset():get("props/chair.glb")       -- load once from addons/<me>/props/chair.glb
end)

-- later, in the world:
local p = hafen.session():current():player():gob():position()
local o = hafen.vr():object():add(mdl, p)
o:rotate(math.pi / 4):scale(1.5)                   -- face 45 degrees, 1.5 times bigger; chained
```

`asset` is a [`hafen.asset`](../asset.md) **mesh handle** —
[handle-only](README.md#the-anchor-is-an-argument), so a path string is an error — and the
[anchor](README.md#the-anchor-is-an-argument) is a [Position](../position.md) to stand it
at a point or a [Gob](../gob.md) to make it follow one.
A new object has scale `1`, full opacity, no tint and is not clickable.

## The model

A model is a [`hafen.asset`](../asset.md) mesh handle — `hafen.asset():get("props/chair.glb")` — holding a
glTF 2.0 static model: your own `.glb`, the single-file binary form and the one to prefer, or a `.gltf` with
its buffers beside it. The parser is pure Java with no native dependencies and decodes **synchronously**, so
load it from setup code (`Load`, `SessionEnteredWorld`, a command) and never from inside a draw callback.
The handle answers [`mdl:bounds()`](../asset.md#mesh) with a world-unit box and
[`mdl:info()`](../asset.md#mesh) with
what the parser produced.

> **Sizing.** glTF authored units vary wildly — a model may be one unit tall or a hundred. Read
> `mdl:bounds().size.z` and pick a `:scale` that stands it the height you want.

**Coordinate system.** glTF is right-handed, +Y up, in metres; the client's world is Z up with a
tile-based scale. The loader bakes a fixed conversion once, so glTF's up becomes world up and **one glTF
metre is one tile** at scale `1`. The glTF **origin maps to the object's own point**, so author a model
with its base at `Y = 0` and it stands on the ground, like a ghost.

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

## The object

The [shared vocabulary](README.md#one-vocabulary-four-kinds) — `:position`, `:offset`, `:rotate`, `:scale`,
`:alpha`, `:tint`, `:visible`, `:clickable`, `:onClick`, `:exists` — plus the one verb only an object has.

| Method | Description |
|---|---|
| `o:mesh()` | the addon-relative model path |

An object's **mesh** is read-only: the geometry is milled when the object is placed, so another model is
another object. `:scale` is a uniform scale on top of the baked size.

## Clickability

An object can be made clickable with `o:clickable(true)`, exactly like a
[clickable sprite](sprites.md#clickability). Its mesh gains a pick surface, and a click on it is detected
**client-side** and **consumed** before any server click, so you never walk or interact and nothing reaches
the server. Both the per-object `:onClick(fn)` and the owner-scoped
[`ObjectClicked`](../event/bus.md#world-ghosts-and-sprites) event fire, and `ObjectClicked` reaches only
*your* addon.

An object answers the same `:position`, `:rotate` and `:scale` a ghost or a sprite does, so one code path
handles all three: click-select, drag on the ground, and persistence through
[`hafen.store`](../store.md), which reloads it at the same spot after a relog.

## See also

- [`hafen.vr`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [sprites](sprites.md) — an image in the world, and the anchoring both share
- [`hafen.asset`](../asset.md#mesh) — loading a `.glb`, and what `:bounds()` and `:info()` answer
- [events](../event/bus.md#world-ghosts-and-sprites) — `ObjectClicked`

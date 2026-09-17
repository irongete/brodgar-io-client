# hafen.virtual: A glTF Model in the World

`hafen.virtual():object():add(asset, anchor)` stands a glTF model in the 3D world. It is the mesh sibling of a [sprite](sprites.md) and a [ghost](ghosts.md) on the same client-only core: no server id, nothing reaches the server, nothing protected.

```lua
local chair_mesh                                          -- upvalue
hafen.event():on("Load", function()
  chair_mesh = hafen.asset():get("props/chair.glb")       -- load once from addons/<me>/props/chair.glb
end)

-- later, in the world:
local position = hafen.session():current():player():gob():position()
local chair = hafen.virtual():object():add(chair_mesh, position)
chair:rotate(math.pi / 4):scale(1.5)                      -- face 45 degrees, 1.5 times bigger; chained
```

---

| Rule | Detail |
|---|---|
| `asset` | A [`hafen.asset`](../asset/README.md) mesh handle, [handle-only](README.md#the-anchor-is-an-argument): a path string raises. |
| The anchor | A [Position](../position.md) to stand it at a point or a [Gob](../gob.md) to follow one ([the anchor](README.md#the-anchor-is-an-argument)). |
| Defaults | Scale `1`, full opacity, no tint, not clickable. |

## The model

| Rule | Detail |
|---|---|
| The handle | `hafen.asset():get("props/chair.glb")`, a glTF 2.0 static model: a `.glb` (single-file binary, the one to prefer) or a `.gltf` with its buffers beside it. Answers [`mesh:bounds()`](../asset/handles.md#mesh) with a world-unit box and [`mesh:info()`](../asset/handles.md#mesh) with what the parser produced. |
| Synchronous decode | Pure Java, no native dependencies: load from setup code (`Load`, `SessionEnteredWorld`, a command), never inside a draw callback. |
| Sizing | Authored units vary widely (one unit tall or a hundred). Read `mesh:bounds().extent.z` and pick a `:scale` for the height you want. |
| Coordinate system | glTF is right-handed, +Y up, in metres. The world is Z up, tile-scaled. The loader bakes a fixed conversion: glTF up is world up and one glTF metre is one tile at scale `1`. The glTF origin maps to the object's own point: author the base at `Y = 0` and it stands on the ground. |
| 64 at once, per addon | Every object mills its own geometry when stood: a vertex array and an index buffer per primitive, on the thread that called `:add`. So the count is what is bounded. Past it `:add` raises naming the ceiling. `hafen.virtual():object():remove(object)` frees a place. |

### The glTF subset

| Level | Features |
|---|---|
| Supported | `.glb` and `.gltf`. Triangle meshes with positions and indices. Multiple nodes, meshes and primitives with node transforms baked in. Multiple materials. `baseColorTexture` multiplied by `baseColorFactor`. The image is embedded through a buffer view, a `data:` URI, or a PNG/JPG beside the model. It is decoded once into a shared GPU texture. Per-material alpha mode (`OPAQUE`, `MASK` alpha-test, `BLEND` translucency). `doubleSided` culling. |
| Lighting | Per-vertex normals baked (smooth ones computed when the mesh has none) and a light state per material. A model shades with the world lights: darker indoors and at night, lit from the sun's direction outdoors. Base colour (texture times factor) is the albedo. `emissiveFactor` areas glow in shadow. Reflectance uses the client's neutral matte defaults, approximating a PBR viewer. The client does not sRGB-convert model textures. |
| Not supported yet | `emissiveTexture`, per-texture sampler wrap and filter settings, a non-zero `baseColorTexture.texCoord`, full PBR (metallic, roughness, occlusion maps). |
| Never | Skins, animation, morph targets, Draco and meshopt compression, sparse accessors. A model using one fails with an error naming the feature. |

| Rule | Detail |
|---|---|
| Malformed documents | Every bound is read off the document and checked before anything is allocated. An accessor claiming more vertices than the cap, a buffer view naming no buffer, a triangle index outside its primitive's vertices are each refused. Whole-model bounds: `4096` primitives, `4000000` vertices, `128 MiB` per buffer, `64 MiB` per texture image, `64` images. Each refusal names the model file and what is wrong, as an ordinary error you can `pcall`. |
| The node graph | A node has at most one parent. A document reaching the same node twice is refused naming it, so a model loads in time proportional to its node count. A node under two parents is an instance (the same mesh with another baked transform), so skipping it would drop geometry silently. A deep chain stays legal. |

## The object

The [shared vocabulary](README.md#one-vocabulary-every-kind) plus its own.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `object:mesh()` | `string` | Unprotected | The addon-relative model path. Read-only: the geometry is milled when placed, so another model is another object. `:scale` is uniform on top of the baked size. |

## Clickability

`object:clickable(true)` gives the mesh a pick surface, as a [clickable sprite](sprites.md#clickability): a click is detected client-side and consumed before any server click. Both the per-object `:onClick(fn)` and the owner-scoped [`ObjectClicked`](../event/bus/world.md#world-ghosts-and-sprites) event fire. `ObjectClicked` reaches only your addon. An object answers the same `:position`, `:rotate` and `:scale` a ghost or a sprite does. One code path handles click-select, drag on the ground and persistence through [`hafen.store`](../store/README.md).

---

## See Also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [Sprites](sprites.md) — an image in the world, and the anchoring both share.
- [`hafen.asset`](../asset/handles.md#mesh) — loading a `.glb`, and what `:bounds()` and `:info()` answer.
- [Events](../event/bus/world.md#world-ghosts-and-sprites) — `ObjectClicked`.

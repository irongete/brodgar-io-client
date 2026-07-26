# R3a — custom 3D model in the world, glTF static mesh (`hafen.render.model` / `object`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the `.glb`
> asset, **27 headless parser checks** (`.glb` header/chunk parse; `.gltf` + base64 **data-URI** buffer; accessor
> decode — float `VEC3` positions + `ushort` indices; **indexed** *and* **non-indexed** primitives; **node TRS**
> baking + **parent→child hierarchy** composition; the **basis conversion** — a real cube comes out upright with
> `z∈[0,11]` = 1 tile tall, `x/y` centred; `baseColorFactor` + default-white fallback; `doubleSided`; and the error
> paths — truncated / no-meshes / no-`POSITION` / points-only / **sparse** / external-buffer-without-loader, each a
> clear named message) + LuaJ parse of `hello`/`planner`/`gizmo.lua`. The parser is pure math + bytes, so it is
> **fully headless-testable**; `MeshSprite`'s engine-`Model` build is a thin GL wrapper (like R2a's quad), verified
> by the compile + in-game. **Java engine change ⇒ `ant` rebuild + a full client restart before the in-game test.**
> **In-game DoD pending.**
>
> **Design:** [specs/addons/18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) (the glTF
> deep-dive — format, accessor decode, basis conversion, the build pipeline, R3a/b/c slices),
> [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§2 the shared world-entity core, §6 the
> model capability), decisions **D-035** (custom 3D models = glTF 2.0 static subset, pure-Java parser) / **D-034**
> (custom non-`.res` rendering is `hafen.render.*`, safe-tier — not gated) / **D-013** (reuse the shared core) /
> **D-030** (bridge-owned handle).

R3a is the first slice of **R3**, the last of the R-series capabilities: it stands an addon's **own glTF model**
(a `.glb`/`.gltf` that ships with the addon) in the 3D world — the **mesh** sibling of a [sprite](../api/render.md)
(a PNG quad) and a [ghost](../api/ghost.md) (a `.res` game model). It reuses the whole R2 world-entity core
(transform, look, scene lifecycle, teardown, gizmo, pick); the only new work is (1) a **pure-Java glTF parser** and
(2) building engine `Model`s from the decoded geometry. R3a ships the **static, unlit** subset: `POSITION` +
indices + a flat `baseColorFactor` colour, node transforms baked, basis-converted to H&H space. Textures (R3b) and
lighting (R3c) are later slices on this same foundation.

## The API

```lua
local mdl = hafen.render.model("cube.glb")          -- glTF → cached, bridge-owned mesh handle
local b   = mdl:bounds()                            -- {min={x,y,z}, max={x,y,z}, size={x,y,z}} world units
local p   = hafen.gob.pos("player")
local o   = hafen.render.object{ model = mdl, x = p.x, y = p.y, a = 0, scale = 1 }  -- stand it in the world
o:move(p.x+22, p.y):rotate(math.pi/4):scale(3)      -- transform handle, gizmo-compatible (chains)
o:alpha(0.6):tint{ r=120, g=200, b=255 }            -- the shared look states, for free
o:destroy()                                          -- or let reload/disable clean it up
```
`model=` accepts a `hafen.render.model` handle **or** an addon-relative path (auto-loaded + cached). Handle verbs:
`:move`/`:rotate`/`:scale`/`:alpha`/`:tint`/`:clickable`/`:show`/`:hide`/`:pos`(→`{x,y,a,scale}`)/`:mesh`/`:follow`/
`:offset`/`:destroy` — the same surface as a sprite, with `:mesh()` in place of `:image()`.

## The parser — [`Gltf`](../../../src/io/brodgar/addon/Gltf.java) (pure Java, no native deps)

`Gltf.parse(bytes, name, loader)` reads a `.glb` or `.gltf` into a flat list of **baked** primitives. It touches no
GL and no client session — only our [`Json`](../../../src/io/brodgar/addon/Json.java) reader, `haven.Matrix4f`/
`Coord3f` (pure math), and byte arrays — so the whole thing is headless-testable (the geometry of a known model can
be asserted numerically, which the 27 checks do).

- **`.glb` container.** 12-byte header (magic `glTF`, version 2, length), then 4-byte-aligned chunks: the `JSON`
  chunk (parsed by `Json`) and the `BIN\0` chunk (buffer 0). All little-endian, decoded by hand.
- **`.gltf` + buffers.** A JSON text file whose `buffers[]` reference data by URI: a **`data:…;base64,…`** URI
  (decoded via `java.util.Base64`) or an **external file**, resolved by the caller-supplied `Loader` **relative to
  the model file** and re-sandboxed to the addon folder (D-017). (`.glb` is preferred — self-contained.)
- **Accessor decode.** A general `readVecs(accessor, comps)` walks `bufferView.byteOffset + accessor.byteOffset`
  with `byteStride` (or tightly-packed), reading each of the 6 `componentType`s (byte/ubyte/short/ushort/uint/float)
  and applying integer **normalization** → `float[]`. Indices decode to `int[]` (ubyte/ushort/uint). R3a consumes
  `POSITION` (VEC3) + `indices`; `NORMAL`/`TEXCOORD_0` are recognised but decoded in R3b/R3c.
- **Node baking.** The default scene's node tree is walked with a cycle guard; each node's local transform (its
  `matrix`, else composed `T·R·S` from `translation`/`rotation`-quaternion/`scale`) composes down the tree
  (`parentWorld · local`). Every mesh primitive under a node is **baked**: its positions are transformed by
  `BASIS · nodeWorld` **at parse time**, so the decoded geometry is already in H&H model-local space and the whole
  model places as one gob (no per-frame node matrices).
- **The basis conversion (the hard bit, spec §4).** glTF is right-handed, **+Y up, metres**; H&H model space is
  **Z up** (the ghost/sprite convention) with a tile scale. `BASIS` bakes, once, a proper rotation (`+Y`→`+Z`:
  `(x,y,z) → (x, -z, y)`, a +90° turn about X, **determinant +1** so winding survives for the R3b cull work)
  **times** a fixed `MODEL_UNIT` scale (**11 world units per glTF metre = 1 tile**). The glTF origin maps to the gob
  position, so a model authored with its base at `Y=0` stands on the ground. (The unit constant is a single tunable
  in `Gltf`; `:scale` adjusts per-object.)
- **Safety caps (D-018 spirit).** Decode is CPU-heavy and runs on the calling thread, so `MAX_PRIMS` (4096),
  `MAX_VERTS` (4 M), and `MAX_BYTES` (128 MB) bound a pathological asset; exceeding one throws. An unsupported
  feature (sparse accessor, no `POSITION`, non-triangle-only mesh, external buffer with no loader) throws a message
  that **names** it — never a client crash (spec §6).

## The build pipeline — [`MeshSprite`](../../../src/io/brodgar/addon/MeshSprite.java) (the R3a visual)

`MeshSprite` is a resource-free `Sprite` (like `SpriteQuad`) that renders a parsed `Gltf` — the multi-part
[`StaticSprite`](../../../src/haven/StaticSprite.java) pattern (`added(slot){ for(part) slot.add(part); }`):

- **Geometry.** One engine [`Model`](../../../src/haven/render/Model.java) per glTF primitive — a `POSITION`-only
  `VertexArray` (`Homo3D.vertex` VEC3, stride 12) + an index buffer (**UINT16** when the primitive fits `< 65536`
  verts, else UINT32 — both engine-native paths), drawn as `TRIANGLES`. The positions are the already-baked
  H&H-local floats, so — like `SpriteQuad` — the `basic` PView scene's own `Homo3D.state` transforms them and the
  model "just works", exactly what a `.res` mesh is minus the resource wrapper.
- **Material — flat, unlit `baseColorFactor`.** `new Material(new BaseColor(r,g,b,a), Material.nofacecull)`.
  `BaseColor` multiplies the default white fragment, so the surface reads as a **solid single-colour** mesh with no
  light math (deterministic, readable for props); opaque (no blend), a real depth occluder; `nofacecull` makes it
  **double-sided** so a winding/basis quirk never hides a face. (Per-material cull, textures, and lighting are
  R3b/R3c.)
- **Ownership.** Each `LuaObject` builds its **own** `Model`s from the mesh's CPU-side geometry (a fresh GPU upload
  per object, like `SpriteQuad`), so `MeshSprite.dispose()` frees exactly this object's `VertexArray`s/index
  buffers; the `LuaMesh` handle owns no GPU state in R3a. Two objects sharing one mesh never share a GPU buffer, and
  disposing the mesh handle can never dangle a live object.

## The world entity — [`LuaObject`](../../../src/io/brodgar/addon/LuaObject.java) (reuses the R2 core)

`LuaObject extends LuaWorldEntity` is a one-field subclass (the [`LuaMesh`](../../../src/io/brodgar/addon/LuaMesh.java)
it was built from) — the transform, look, `follow` anchor, scene lifecycle, teardown, and gizmo are all inherited
from the shared core. `newObject` mirrors `newSprite` exactly: validate → world check → resolve the model → build a
`GhostGob` + `SprDrawable(MeshSprite.mill(mesh))` → publish under the entity monitor → `addClientGob`. **Synchronous**
create (the geometry is already decoded — no `Loading` to dodge, unlike a ghost's `res.get()`). Because it is a real
`GhostGob`, `:tint`/`:alpha`/`:scale`/`:clickable` work for free (they ride `GhostGob.obstate` / the scene re-add),
and the V2 pick dispatch was generalized one line further (`findEntityIn` now also scans `Addon.objects`), so a
clickable object fires `onClick` + the owner-scoped **`ObjectClicked`** event and consumes the click — still
safe-tier (client-only, no `wdgmsg`).

The mesh asset is loaded/cached like an image: `hafen.render.model(path)` → `newMesh` resolves the addon-relative
path (D-017), reads the bytes, and parses via `Gltf` into a bridge-owned `LuaMesh` (`Addon.meshes`), returning a
handle with `:bounds()` (from the parsed min/max, world units) and `:dispose()`. Teardown order:
`teardownObjects` (destroy the world objects + free their `Model`s) **before** `teardownMeshes` (drop the geometry) —
both wired into `teardown` beside the R1/R2 ones.

## Zero `haven` core edit

R3a reuses the V1 `MapView.addClientGob`/`removeClientGob` seam and the V2 `Click.hit` dispatch; every render
primitive is public (`Homo3D.vertex`, `Model`/`VertexArray`/`Model.Indices`, `Material`/`BaseColor`,
`Material.nofacecull`, `SprDrawable`) and `haven.Matrix4f`/`Coord3f` are pure math. So all new code is
**`io.brodgar.addon`-only** — no new `// addon:` line.

## Files changed

- **`src/io/brodgar/addon/Gltf.java`** (new) — the pure-Java glTF 2.0 static parser: `.glb` chunk read + `.gltf` +
  data-URI/external buffers; accessor/index decode; node-transform baking; the `BASIS` conversion; the safety caps;
  `Prim` (baked positions + indices + `baseColorFactor` + `doubleSided`) and the overall bounds.
- **`src/io/brodgar/addon/LuaMesh.java`** (new) — the bridge-owned model-asset handle (parsed `Gltf` + the opaque-
  userdata round-trip for `render.object`), like `LuaImage`.
- **`src/io/brodgar/addon/MeshSprite.java`** (new) — the resource-free multi-part `Sprite`: one `POSITION`-only
  `Model` + flat `BaseColor` material per primitive; `dispose()` frees this object's `Model`s.
- **`src/io/brodgar/addon/LuaObject.java`** (new) — `extends LuaWorldEntity`; the mesh visual (`mesh`/`meshName`) +
  the `ObjectClicked`/`object` click keys.
- **`src/io/brodgar/addon/AddonManager.java`** — the `hafen.render.model`/`object` facade; `newMesh`/`meshHandle`/
  `vec3Table`/`disposeMesh`/`teardownMeshes` and `newObject`/`objectHandle`/`resolveObjectMesh`/`teardownObjects`
  (wired into `teardown` beside the R1/R2 teardowns); `findEntityIn` also scans `Addon.objects` (V2 pick).
- **`src/io/brodgar/addon/Addon.java`** — the `meshes` + `objects` owned-resource lists.
- **`addons/hello/`** — v0.42.0: ships **`cube.glb`** (a 1×1×1 m tan cube, base at `Y=0`), loads it at `OnLoad`
  (logs `:bounds()`), and **`:hello object`** stands it at your feet (`scale=2`) then 2 s later `:move`s + `:rotate`s
  + `:scale`s it (the transform-handle proof on a mesh); `:hello object` again removes it. Manifest updated.

## Try it in-game (R3a DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload
classes).

1. Log in (the `hello` harness loads `cube.glb` at `OnLoad` — the log reports the baked bounds, ~1 tile tall).
2. Type **`:hello object`** in the console: the tan cube **stands on the ground at your feet**, ~2 tiles across,
   **upright** (base on the ground, not half-buried or lying flat — the basis conversion) and the right size — the
   log reports its `:pos()`.
3. After ~2 s it **moves ~2 tiles east, rotates 45°, and grows ×3** (the transform handle is live + chains) — the
   log line confirms; the cube is **gizmo-movable** via the same handle the `planner` gizmo drives.
4. **`:hello object`** again removes it; **`:reload`** (or disable `hello`) with the object up **leaks nothing**
   (the object's `Model`s are freed, then the mesh geometry dropped).
5. **Regression:** the R1 image (in the 2a window / 2b overlay), the R2 `:hello sprite`/`billboard`/`follow`, and the
   V-series ghosts still work — R3a added only new code on the shared core.

DoD: a `.glb` prop **stands in the world at the right size/orientation**, the handle **transforms** it live
(gizmo-movable); `:reload` leaks nothing.

## Deferred (R3b / R3c / later)

- **R3b — textures + multi-material.** `baseColorTexture` (embedded + external PNG/JPG → `TexI`), `TEXCOORD_0`,
  multiple materials, alpha modes (OPAQUE/MASK/BLEND), per-material double-sided/cull (using the winding the +det
  basis preserves). The `LuaMesh` then owns shared `TexI`s (freed in `disposeMesh`).
- **R3c — lighting polish.** `NORMAL` decode + the engine light state so models shade with the world; sRGB; emissive.
- **`planner` integration** (like R2b did for sprites): a `kind="object"` record + a `:planner object` command so a
  glTF model can be **click-selected and gizmo-driven** in the editor. R3a proves the transform handle is
  gizmo-compatible (it is the identical `:move`/`:rotate`/`:scale` handle the gizmo already drives for ghosts/sprites,
  exercised live by `:hello object`); wiring the planner's select flow rides R3b, alongside textures.
- **Later:** skins + keyframe **animation**; full PBR maps; Draco/meshopt; async decode for large assets;
  billboard/decal model variants; a per-object model swap (`:setModel`).

# Custom 3D Models — glTF 2.0 (non-`.res`)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Series:** R (Render), slice **R3**
> **Related:** [17-custom-rendering.md](17-custom-rendering.md) (parent — `hafen.render.*`),
> [16-virtual-entities.md](16-virtual-entities.md) (the world-entity core reused),
> [DECISIONS.md](../DECISIONS.md) (D-035 — **ratified 2026-07-26**), [../../codebase-map.md](../../codebase-map.md) (R-series seams)

The deep-dive for capability #4 of [17](17-custom-rendering.md): render a **custom 3D model that is NOT a game
`.res`** in the world, via **`hafen.render.object{ model = "chair.glb", … }`**. Format = **glTF 2.0**
([D-035](../decisions/rendering.md), maintainer choice 2026-07-26). The model becomes engine `Model`+`Material` primitives on a
virtual `Gob` (the [17](17-custom-rendering.md) §2 core), so it inherits transform + gizmo + teardown.

## 1. Why glTF (and the v1 scope)

glTF 2.0 is the open, modern runtime model format (Khronos): JSON scene graph + typed binary buffers, PBR
materials, optional skinning/animation. Chosen over OBJ (too limited — no scene/material graph) and FBX
(proprietary, needs a native lib like Assimp/JNI — a hard dependency we reject).

**v1 = the STATIC subset** (keeps it bounded and pure-Java):

| In v1 | Deferred |
|---|---|
| `.glb` (single-file binary; **preferred**) + `.gltf`+external buffers | Draco/meshopt compression |
| Static meshes: `POSITION`, `NORMAL`, `TEXCOORD_0`, indices | Skins / joints, morph targets |
| Multiple nodes/meshes/primitives, baked node transforms | Keyframe **animation** (channels/samplers) |
| PBR metallic-roughness **baseColor** (factor + texture) | Full PBR (metallic/roughness/occlusion maps) |
| Alpha modes OPAQUE / MASK / BLEND; double-sided | Cameras, lights, KHR extensions (beyond basic) |
| Embedded (`.glb` BIN / data-URI) **or** external PNG/JPG textures | Emissive/normal-map shading polish (→ R3c) |

**No new dependency:** JSON via our [`Json`](src/io/brodgar/addon/Json.java); binary buffers decoded by hand
(`ByteBuffer`, little-endian); base64 data-URIs via `java.util.Base64`; textures via `ImageIO` (already used).

## 2. glTF structure (what we consume)

```
glb: [12B header][JSON chunk][BIN chunk]
json: scenes[] → nodes[] (matrix | T/R/S; children) → mesh → primitives[]
      primitive: { attributes{POSITION,NORMAL,TEXCOORD_0}, indices, material, mode(=4 TRIANGLES) }
      accessors[] → bufferViews[] → buffers[]   (accessor: componentType, type, count, byteOffset)
      materials[] → pbrMetallicRoughness{ baseColorFactor[4], baseColorTexture{index} }, alphaMode, doubleSided
      textures[] → images[] (uri | bufferView) + samplers[]
```

- **Accessor decode** — `componentType` 5120/5121/5122/5123/5125/5126 = byte/ubyte/short/ushort/uint/float;
  `type` SCALAR/VEC2/VEC3/VEC4 → 1/2/3/4 components. Read `count × comps` from `buffer[bufferView.byteOffset +
  accessor.byteOffset]`, little-endian, honouring `bufferView.byteStride`. Normalized integer attrs → float.
- **Indices** — usually ushort/uint SCALAR → an engine index buffer.
- **Node transforms** — each node has a `matrix` **or** T/R/S; compose down the tree and **bake** each primitive's
  world transform into a per-object `Location` (or pre-multiply vertices) so the whole model places as one gob.

## 3. Build pipeline → engine primitives

Per glTF **primitive** (all classes public — see [17](17-custom-rendering.md) §5 / [../../codebase-map.md](../../codebase-map.md)):

1. **Geometry** — a [`VertexArray`](src/haven/render/VertexArray.java:41) with a
   [`Layout`](src/haven/render/VertexArray.java:65) mapping `POSITION`→[`Homo3D.vertex`](src/haven/render/Homo3D.java:41)
   (VEC3), `NORMAL`→the engine normal attribute (Homo3D, beside `vertex` — *ref pinned in ../codebase-map.md (R3c)*),
   `TEXCOORD_0`→[`Tex2D.texc`](src/haven/render/Tex2D.java:36) (VEC2); + an index buffer →
   [`Model(Mode.TRIANGLES, va, idx, 0, n)`](src/haven/render/Model.java:45).
2. **Material** — `baseColorTexture` → `ImageIO`→`TexI`→[`TexI.st()`](src/haven/TexI.java:59) (`ColorTex`);
   `baseColorFactor` → a [`BaseColor`](src/haven/render/) multiply; `alphaMode` BLEND →
   `FragColor.blend(new BlendMode())` (+ `States.maskdepth`), MASK → alpha-test; `doubleSided` → cull off. Assemble
   as [`new Material(states…).apply(model)`](src/haven/Material.java:165) → a `RenderTree.Node`.
3. **Sprite** — collect every primitive's node into one `Sprite` subclass (`added(slot){ for(part) slot.add(part); }`,
   the [`StaticSprite`](src/haven/StaticSprite.java:83) pattern); `dispose()` frees all `Model`s + `TexI`s.
4. **Attach** — `gob.setattr(new `[`SprDrawable`](src/haven/SprDrawable.java:40)`(gob, sprite))` on the virtual gob
   → transform/gizmo/teardown from the [17](17-custom-rendering.md) §2 core, no extra code.

## 4. The two hard bits (flag early)

- **Coordinate system & units.** glTF is **right-handed, +Y up, metres**; the H&H world has its own axes and a
  tile-based scale. A fixed **basis conversion** (axis swap/flip + a model→world scale, likely exposed as the
  handle's `scale`) must be applied once (baked into the root `Location`). Getting this wrong = model sideways or
  giant. **Decide the convention in R3a** and document it.
- **PBR → engine shading.** The client is **not** a PBR renderer. v1 maps only **baseColor** (texture × factor) and
  ignores metallic/roughness/occlusion. Two shading targets:
  - **R3a/R3b: unlit / emissive** — draw baseColor straight (no light math). Simplest, deterministic, "reads" fine
    for props/markers; ship this first.
  - **R3c: basic lit** — feed `NORMAL` + the engine's light state so the model shades like world geometry (match
    the client's light model; *the `Light`/shading state classes are pinned in ../codebase-map.md (R3c)*). Fidelity will
    approximate, not match, a real PBR viewer — acceptable for planner props.
  - sRGB: baseColor textures are sRGB; match the engine's texture colour handling to avoid washed-out/dark output.

## 5. Slices (delivered inside [012-custom-rendering](../012-custom-rendering/tasks.md))

- **R3a — glTF parse + static mesh, unlit.** `.glb` reader (header/chunks) + `.gltf`+buffers; accessor/index decode;
  build `Model`s (POSITION only ok) with a flat baseColorFactor `Material`, **unlit**; bake node transforms + the
  basis conversion; `hafen.render.model()`/`object{}` wired onto the [17](17-custom-rendering.md) §2 core; teardown
  disposes meshes. **DoD:** a `.glb` cube/prop stands in the world at the right size/orientation, gizmo-movable;
  `:reload` leaks nothing.
- **R3b — textures + multi-primitive/material.** baseColorTexture (embedded + external PNG/JPG → `TexI`),
  `TEXCOORD_0`, multiple primitives/materials per model, alpha modes (OPAQUE/MASK/BLEND), double-sided. **DoD:** a
  textured multi-material `.glb` renders correctly.
- **R3c — lighting polish.** `NORMAL` + the engine light state so models shade with the world (+ sRGB, emissive).
  **DoD:** a model no longer looks flat/fullbright against lit terrain.
- **Deferred (R4+ / later):** skins + keyframe **animation**; full PBR maps; Draco/meshopt; `.gltf` with many
  external assets.

## 6. Risks / open items

- **Scope creep via extensions** — cap strictly at the §1 table; a model using unsupported features fails with a
  clear addon-facing error (name the missing feature), never a client crash.
- **Big meshes** — decode is CPU-heavy; do it on the load thread, not a draw. Cap vertex/primitive counts with a
  logged limit ([D-018](../decisions/security-sandbox.md) watchdog spirit) so a pathological asset can't hang the client.
- **Exact engine refs to pin in `../../codebase-map.md` during R3:** the NORMAL attribute, the `BaseColor` path, the
  light/shading state classes, and index-buffer construction — left as *verify* here rather than asserted.
- **[D-035](../decisions/rendering.md) ratified** (glTF, static-first — maintainer 2026-07-26); R3a cleared to start.

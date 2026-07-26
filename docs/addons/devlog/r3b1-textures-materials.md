# R3b-1 — textured, multi-material glTF models (`baseColorTexture` + `TEXCOORD_0` + alpha modes)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages, **43 headless
> parser checks** (the real `tank.glb` — 53 prims, all textured, **4 shared images deduped from 53 materials**, all
> PNG, doubleSided, OPAQUE, `TEXCOORD_0` present, `baseColorFactor` 0.64, upright/on-ground/tall; a synthetic
> **data-URI-PNG** triangle — `MASK`/`alphaCutoff`/doubleSided/baseColor/UVs-not-baked/positions-baked; a **BLEND**
> single-sided triangle; an **external-image** triangle via the loader + the no-loader error; the *neither-uri-nor-
> bufferView* error; an **untextured** triangle — `texImage=-1`/`tex=null`/no images; and a **cube basis regression**
> — `min (-5.5,-5.5,0)`/`max (5.5,5.5,11)`) + LuaJ parse of `hello`. The parser is pure bytes/math, so it is fully
> headless-testable; the engine-`Material`/texture build in `MeshSprite` is a thin GL wrapper (like R2a's textured
> quad), verified by the compile + in-game. **Java engine change ⇒ `ant` rebuild + a full client restart before the
> in-game test.** **In-game DoD pending.**
>
> **Design:** [specs/addons/18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) §3/§5 (the
> build pipeline + the R3b slice), [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§2 the
> shared world-entity core), decisions **D-035** (glTF static subset) / **D-034** (safe-tier, not gated) / **D-013**
> (reuse the shared core / one serializer). Builds directly on [r3a-models-world](r3a-models-world.md).

R3b is the second slice of **R3**; **R3b-1** is its engine-rendering half (R3b-2 is the `planner` integration). It
takes R3a's flat, untextured glTF meshes and makes them render **textured, with one material per primitive** — the
headline being the maintainer's own **`tank.glb`** (53 primitives, 4 embedded PNG textures) going from a grey blob
to a textured tank. Still **unlit** (texture × colour, no light math — lighting is R3c).

## What R3b-1 adds

```lua
local mdl = hafen.render.model("tank.glb")          -- now decodes TEXCOORD_0 + baseColorTexture too
local i   = mdl:info()                              -- {prims=53, textured=53, textures=4, verts=13221, tris=…}
local p   = hafen.gob.pos("player")
-- glTF units vary; scale from bounds so it stands ~2 tiles tall regardless of the model:
local s   = (2 * 11) / mdl:bounds().size.z
local o   = hafen.render.object{ model = mdl, x = p.x, y = p.y, scale = s }   -- stands TEXTURED, multi-material
```

The API surface is unchanged from R3a except the new **`mdl:info()`** (primitive/texture/triangle counts) and that
`mdl:dispose()` now also frees the shared textures. Everything an object does — transform, look, `follow`, click,
gizmo — is inherited from the shared world-entity core, unchanged.

## The parser — [`Gltf`](../../../src/io/brodgar/addon/Gltf.java) (still pure, still headless-testable)

R3a decoded `POSITION` + indices + `baseColorFactor`. R3b-1 adds, per primitive, the full material + UVs, keeping
`Gltf` free of GL/`ImageIO` (so the whole thing stays numerically testable — the 43 checks assert it):

- **`TEXCOORD_0`.** Decoded via the existing general `readVecs(accessor, 2)` (so it honours componentType / stride /
  normalization for free), **not** basis-baked — UVs are 2D. Length is guarded to `nvert*2`; a mismatch falls back to
  untextured. Decoded **only** when the material actually references a texture (a mesh may carry UVs it never uses).
- **Material.** `pbrMetallicRoughness.baseColorFactor` (the R3a colour) **×** `baseColorTexture`; plus `alphaMode`
  (`OPAQUE`/`MASK`/`BLEND` → `Gltf.ALPHA_*`), `alphaCutoff` (default 0.5), and `doubleSided`. A `baseColorTexture`
  resolves `index → textures[].source → images[]`; a non-zero `texCoord` (TEXCOORD_1) is deferred (renders untextured).
- **Image extraction (the real new work).** Each referenced glTF `image` is pulled to a **raw byte blob**
  (`Gltf.Image{bytes, mime}`) from one of three sources — a **`bufferView` slice** (embedded, the `.glb` case —
  `tank.glb` uses this), a **`data:` base64 URI**, or an **external file** via the R3a `Loader` (re-sandboxed to the
  addon folder by the caller). Extraction is **lazy + deduped**: `imgRemap[glTFimage] → local index`, so a texture
  reused by 50 materials is pulled **once** (the tank's 53 materials → **4** images — a headless check). Caps
  `MAX_IMAGES` (64) / `MAX_IMAGE_BYTES` (64 MB); a broken reference throws a named error.
- **`Prim` grew** four fields: `tex` (UVs or null), `texImage` (index into `Gltf.images` or -1), `alphaMode`,
  `alphaCutoff`. `Gltf.images` is the deduped blob list.

**Purity kept:** `Gltf` extracts *bytes* only — no `ImageIO`, no `TexI`, no GL — so a known model's material/UV/image
structure is asserted numerically. The caller decodes the blobs.

## The textures — owned by [`LuaMesh`](../../../src/io/brodgar/addon/LuaMesh.java), decoded in `AddonManager`

`newMesh` now, after parsing, decodes each `Gltf.Image` blob to a **shared `TexI`** (`buildMeshTextures`:
`ImageIO.read(new ByteArrayInputStream(bytes))` → **`new TexI(img, false)`**) and hands the `TexI[]` to the
`LuaMesh`, which **owns** them (indexed by `Prim.texImage`). Two points:

- **`new TexI(img, false)` — no power-of-two rounding.** The default `new TexI(img)` rounds to POT (`tdim = nextp2`),
  which would make glTF's `[0,1]` UVs sample into the POT padding on a non-power-of-two image. `round=false`
  (`tdim == sz`) makes `[0,1]` map to the whole image — correct for any dimensions (the tank's textures happen to be
  POT: 1024², 128², 64×128 ×2, but this is right in general). Modern desktop GL handles NPOT + NEAREST fine.
- **Ownership / teardown order.** The `TexI`s are the **first GPU state a mesh owns**, so `disposeMesh` /
  `teardownMeshes` now disposes them. `teardownObjects` (frees each object's own `Model`s) runs **before**
  `teardownMeshes`, so a live object never references a freed texture. (Trade-off vs R3a's "live objects keep
  working": a manual `mesh:dispose()` while an object still draws it now frees the shared textures out from under it —
  dispose a mesh only when unused; teardown always orders it correctly. Documented on the handle.)

## The visual — [`MeshSprite`](../../../src/io/brodgar/addon/MeshSprite.java) (per-material states)

`MeshSprite.mill` now takes the **`LuaMesh`** (geometry + shared `TexI[]`) and builds, per primitive:

- **Geometry.** Textured → a `POSITION`(VEC3)+`TEXCOORD_0`(VEC2) **interleaved** `VertexArray` (stride 20, the R2a
  `SpriteQuad` layout); untextured → `POSITION`-only (stride 12, the R3a path). Drawn as `TRIANGLES`, UINT16/UINT32
  indices.
- **Material.** `texture × baseColorFactor`: **`TexRender.TexDraw`** (samples the shared `TexI`'s sampler — one
  `TexRender` per image, reused across the prims that share it) **and** a **`BaseColor`** of the `baseColorFactor`.
  Both do `FragColor.fragcol().mod(mul, 0)`, so they compose as a multiply → the glTF base-colour semantics. Alpha
  mode: `MASK` → add `TexRender.TexClip` (alpha-discard < 0.5, matching glTF's default `alphaCutoff` — the engine's
  cutoff is fixed at 0.5, so a custom `alphaCutoff` isn't honoured, a documented caveat); `BLEND` → `FragColor.blend`
  + `States.maskdepth` (the same translucent recipe `GhostGob` uses for a ghost's `alpha`). Cull: `doubleSided` →
  `Material.nofacecull`, else an explicit back-face `States.Facecull` (the det-+1 basis preserves glTF's CCW winding).
- **Ownership.** `dispose()` frees only this object's own `Model`s (the `VertexArray`s); the `TexRender`s only
  *reference* the shared samplers (never disposed here, like `SpriteQuad`) — the `TexI`s belong to the mesh.

### The one in-game unknown: UV `v` orientation

glTF UV `v=0` is the image top; the engine uploads a `TexI`'s `BufferedImage` **row 0 (its top)** as texture row 0
(sampled at `v=0`) with no vertical flip (`TexI.st()` pushes the raster buffer directly), so glTF UVs should map
**directly — no flip**. This is reasoned, not yet eyeballed in 3D, so `MeshSprite.TEXV_FLIP` is a single `boolean`
switch: if a texture appears upside-down in-game, flip it to `true` (one line, `v → 1-v`) and rebuild. **This is the
first thing to check in-game.**

## Zero `haven` core edit

R3b-1 reuses the V1 `MapView.addClientGob` seam and the V2 `Click.hit` dispatch (both from R3a). Every texture/
material primitive is public (`TexRender`/`TexDraw`/`TexClip`, `Tex2D.texc`, `BaseColor`, `FragColor.blend`,
`States.maskdepth`/`Facecull`, `Material.nofacecull`, `new TexI(img,false)`, `ImageIO`). So all new code is
**`io.brodgar.addon`-only** — no new `// addon:` line (R3a's zero-edit property is preserved).

## Files changed

- **`src/io/brodgar/addon/Gltf.java`** — `Prim` gains `tex`/`texImage`/`alphaMode`/`alphaCutoff`; new `Gltf.images`
  (deduped `Image{bytes,mime}` blobs); `bakePrim` decodes `TEXCOORD_0` + the full material; new `resolveImage`
  (bufferView / data-URI / external, lazy + deduped, capped) + `alphaMode` parse. Still pure (no GL/ImageIO).
- **`src/io/brodgar/addon/LuaMesh.java`** — owns the shared `TexI[] textures`; doc updated for GPU ownership + the
  teardown-order guarantee.
- **`src/io/brodgar/addon/MeshSprite.java`** — `mill(LuaMesh)`; interleaved textured `VertexArray`; per-material
  states (TexDraw × BaseColor + alpha mode + cull); shared `TexRender` per image; the `TEXV_FLIP` switch.
- **`src/io/brodgar/addon/AddonManager.java`** — `newMesh` decodes the shared textures (`buildMeshTextures`, `new
  TexI(img,false)`, clear per-image decode error); `disposeMesh` frees them; new mesh-handle `:info()`; `newObject`
  passes the `LuaMesh` to `mill`; facade doc updated.
- **`addons/hello/`** — v0.43.0: `:hello object` now scales the tank from `:bounds()` to ~2 tiles tall and reports
  `:info()` (prim/texture counts) at `OnLoad`; comments/manifest updated to R3b (textured).

## Try it in-game (R3b-1 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload).

1. Log in. The `hello` `OnLoad` log now reports `tank.glb — 53 prims (53 textured, 4 textures), … tris; baked size …`.
2. **`:hello object`** — the **tank stands at your feet, TEXTURED** (its camo/detail PNGs, not a flat grey blob),
   ~2 tiles tall and upright. This is the DoD: a textured, multi-material `.glb` rendering correctly.
   - **If the texture looks vertically flipped**, set `MeshSprite.TEXV_FLIP = true`, `ant hafen-client`, restart.
3. After ~2 s it moves +2 tiles E, rotates 45°, grows ×1.5 (the transform handle, live) — textured throughout.
4. **`:hello object`** again removes it; **`:reload`** (or disable `hello`) with it up **leaks nothing** (the
   object's `Model`s freed first, then the shared `TexI`s).
5. **Regression:** the R1 image, R2 `:hello sprite`/`billboard`/`follow`, and the V-series ghosts still work.

DoD: a **textured multi-material `.glb` renders correctly** via `:hello object`; `:reload` leaks nothing.

## Deferred

- **R3b-2 — `planner` integration.** A `kind="object"` record + `:planner object` so a glTF model is
  click-selectable + gizmo-driven + grid-anchor-persisted (like R2b did for sprites). The transform handle is already
  gizmo-compatible (`:hello object` proves it live); wiring the planner's select/persist flow is the next slice.
- **R3c — lighting.** `NORMAL` decode + the engine light state (so models shade with the world) + sRGB baseColor +
  emissive. Also deferred: per-texture sampler wrap/filter, a custom `alphaCutoff` (engine cutoff is fixed 0.5),
  `TEXCOORD_1`, async texture decode for large assets.

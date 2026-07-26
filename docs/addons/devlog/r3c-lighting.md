# R3c — lighting polish (glTF `NORMAL` + engine light state → models shade with the world)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **10 headless parser checks** (a
> synthetic triangle **with** a `NORMAL` attribute + `emissiveFactor [1,0.5,0]` → baked normal `(0,-1,0)` [glTF +Z
> normal through the inverse-transpose basis], emissive decoded, position baked to `(0,0,11)` [1 m = 1 tile],
> unit-length; a synthetic triangle **without** `NORMAL` → the smooth **geometric** fallback yields the *same*
> `(0,-1,0)`, unit-length, emissive defaults black; the two agree; the real **`tank.glb`** — every one of its 53
> prims carries normals (`nrm.length == pos.length`) all unit-length after bake) + LuaJ parse of every addon. The
> parser stays pure bytes/math (fully headless-testable); the `Light.PhongLight` + `NORMAL`-attribute build in
> `MeshSprite` is a thin GL wrapper (like R3b's material build), verified by the compile + in-game. **Java engine
> change ⇒ `ant` rebuild + a full client restart before the in-game test.** **In-game DoD pending.**
>
> **Design:** [specs/addons/18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) §4 (PBR →
> engine shading, "R3c: basic lit") / §5 (the R3c slice), [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md)
> §2 (the shared world-entity core), decisions **D-035** (glTF static subset) / **D-034** (safe-tier, not gated).
> Builds directly on [r3b1-textures-materials](r3b1-textures-materials.md). **Completes R3 + the whole R-series.**

R3c is the final slice of **R3**. R3a/R3b drew our glTF models **unlit** — the baseColor (texture × factor) straight
through, no light math, which reads flat and *fullbright* next to lit world geometry. R3c makes a model **shade with
the world lights** exactly like a game object: bake per-vertex normals, feed them to the engine's normal attribute,
and add a Phong light state to each material. The tank now has light and shadow sides that turn as you rotate it.

## What R3c adds

No new Lua surface — an existing model just renders lit. The only visible API change is one field:

```lua
local mdl = hafen.render.model("tank.glb")
local i   = mdl:info()     -- {prims=53, textured=53, lit=53, textures=4, verts=13221, tris=22392}
--                                            ^^^^^^  R3c: how many prims carry normals → Phong-lit
local o   = hafen.render.object{ model = mdl, x = p.x, y = p.y, scale = (2*11)/mdl:bounds().size.z }
-- o now SHADES with the world lights; rotate/relocate it and the shading turns.
```

## The parser — [`Gltf`](../../../src/io/brodgar/addon/Gltf.java) (still pure, still headless-testable)

Two new pieces of per-primitive data on `Gltf.Prim`, both baked into H&H model-local space so `MeshSprite` uses them
directly (no GL/`ImageIO` in the parser — the 10 checks assert the math numerically):

- **`nrm` — per-vertex normals, never null.** Preferred source is the glTF **`NORMAL`** attribute (decoded by the
  existing general `readVecs(accessor, 3)`, so componentType/stride/normalization come for free). Normals do **not**
  transform like positions — they transform by the **inverse-transpose** of the position matrix, so a non-uniform
  node scale shears the surface normal correctly instead of skewing it. `bakeNormals` computes the normal matrix
  once per primitive as `trim3(transpose(invert(fin)))` (where `fin = BASIS · nodeWorld`), applies its upper 3×3 to
  each normal (ignoring the translation column), and re-normalizes. *Why proper inverse-transpose when `BASIS` and
  most nodes are just rotation + uniform scale (where it reduces to the plain transform after normalization)?*
  Because it costs one 4×4 invert per primitive and is simply correct for the sheared case — cheaper to do right
  than to special-case.

- **The `computeNormals` fallback.** A mesh with no `NORMAL` (older exports, our R3a `cube`) would otherwise draw
  unlit. `computeNormals` builds **smooth, area-weighted** normals from the *baked* triangle geometry: accumulate
  each triangle's un-normalized face normal (`e1 × e2`, so larger faces weigh more) into its three vertices, then
  normalize; a degenerate vertex falls back to H&H up (`+Z`). Because `BASIS` is a proper rotation (det **+1**),
  a baked triangle's winding still yields an *outward* normal consistent with front faces — the same invariant that
  makes R3b's back-face culling correct. The check proves the fallback matches the transformed attribute exactly
  (both `(0,-1,0)` for the +Z test triangle).

- **`emissive` — `emissiveFactor {r,g,b}`** (default black), decoded in the material block beside `baseColorFactor`.
  Fed to the Phong `emi` term so emissive areas glow at full colour even in shadow. `emissiveTexture` is deferred.

`Prim`'s constructor gained `nrm` + `emissive`; `bakePrim` decodes/computes them after positions/indices. Everything
else (positions, UVs, textures, alpha, cull, the caps, cycle guard) is untouched.

## The render — [`MeshSprite`](../../../src/io/brodgar/addon/MeshSprite.java)

- **Normals in the vertex array.** `buildModel` was one-of-two hard-coded layouts (pos, or pos+uv). It is now a
  **generic interleaver**: `POSITION`(VEC3, always) + `NORMAL`(VEC3, when `lit`) + `TEXCOORD_0`(VEC2, when
  `textured`), tightly packed in that order, so the stride is 12/20/24/32 bytes as the flags dictate. The normal
  input targets [`Homo3D.normal`](../../../src/haven/render/Homo3D.java:42) (the engine's `"normal"` VEC3 attribute,
  which its shader transforms to eye space as `mat3(cam)·mat3(wxf)·objn` — i.e. through the gob's world transform,
  exactly what we want since our baked normals live in the same model-local space as the positions).

- **The light state.** When a primitive is lit, the material gains a
  [`Light.PhongLight`](../../../src/haven/Light.java:145) — the same material state a `.res` model's `col` layer
  adds. The engine's `Phong` shader then multiplies the **world lights** (the `Lighting.lights` state the MapView
  scene already applies to every gob in the `basic` scene — our ghost gobs live there too) into the fragment. So the
  albedo (texture × baseColorFactor, unchanged from R3b) is modulated by `amb·ambientLight + dif·NdotL·diffuseLight`,
  and `emissiveFactor` is added on top. Reflectance uses the engine's **neutral defaults** (`PhongLight.defamb`
  0.2 / `defdif` 0.8 / `defspc` 0 / `shine` 0 → matte, no specular) — the same defaults a default-lit material reads;
  fidelity *approximates*, not matches, a PBR viewer, which spec §4 calls acceptable for planner props. Per-fragment
  lighting (`frag=true`) for quality.

## Threading / ownership / core edits

**Zero `haven` core edit.** All of it is `io.brodgar.addon` + the two public engine classes it references
(`Homo3D.normal`, `Light.PhongLight`), reusing the V1 `addClientGob` scene seam and R3b's texture/material path.
No new GPU state to free beyond the geometry `Model`s that `MeshSprite.dispose()` already frees (the light state is
a stateless `Pipe.Op`; emissive/reflectance are plain floats). The parser stays off any client session (pure), so
the normal/emissive decode is still fully headless-testable.

## sRGB — a deliberate no-op

Spec §4 flags sRGB baseColor handling as an R3c concern ("match the engine's texture colour handling to avoid
washed-out/dark output"). The finding: **the engine does not sRGB-convert model textures.** `Texture.srgb` exists
and gates `GL_SRGB8[_ALPHA8]`, but nothing in the model/texture load path ever calls `.srgb()` — game textures load
as plain `UNORM8`. Our `TexI`s (also `UNORM8`, non-srgb) therefore already match world geometry byte-for-byte, so
there is nothing to do; forcing sRGB on *our* textures would make them the odd ones out. Documented rather than
"fixed".

## Regression harness — [`hello`](../../../addons/hello/main.lua) v0.44.0

`hello` already loads `tank.glb` at `OnLoad` and `:hello object` stands it. R3c: the load log now reports
`:info().lit` (`53 LIT`), and the object-stand log notes it is shaded by the world lights (rotate/relocate to see the
shading turn). No new command — the existing textured-tank demo is the R3c demo, now lit. `hello` stays read-only
(declares no permissions), so it remains default-enabled — the standing regression check.

## Deferred

`emissiveTexture`; per-texture sampler wrap/filter; a non-zero `baseColorTexture.texCoord` (`TEXCOORD_1`); full PBR
(metallic/roughness/occlusion maps); a tunable per-object reflectance/shine (the handle could expose it later); cel
shading (`Light.CelShade` exists) if props should match the world's cel look more closely.

## In-game DoD

> **A model no longer looks flat/fullbright against lit terrain.** Rebuild (`ant hafen-client`) + restart the client.
> `:hello object` → the tank stands **shaded** (a clear light side / dark side), not evenly bright; rotate it (the
> 2 s auto-transform, or relocate) and the shading **turns** with the world sun; `:reload`/disable leaves no leak;
> `hello` regression intact (all prior features still work on the one login).

**One task done — stop for the maintainer's in-game verification.**

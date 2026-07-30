# 012-custom-rendering — Plan

> History: this work appears in git history and `learnings/` tagged **R1, R2a, R2b, R3a,
> R3b-1, R3b-2, R3c** (the R-series).

## Approach
- **The substrate insight**: a `.res` image IS PNG bytes + `TexI` — so the custom-image
  loader is `ImageIO.read → new TexI(img)` (lazy GPU upload, headless-constructible), zero
  core edit. Handles carry the Java ref as an **opaque metatable-less userdata** (P1-safe,
  unforgeable — the sandbox has no luajava; no global id map needed). D-017 path sandbox =
  ONE containment check (`normalize` + `startsWith`), not string bans.
- **One world-entity core, pluggable visuals** (the R2a refactor): `LuaWorldEntity` base
  (transform/look/scene/follow/click) with `LuaGhost`/`LuaSprite`/`LuaObject` subclasses;
  `GhostGob` unchanged (already the generic virtual gob). Sprites create synchronously (no
  `res.get()` → no defer). Fixed quad = a 4-vert strip in the gob's local x=0 plane with
  the engine's own `$tex` alpha-CLIP matpart (blend was the early "1% ghost" bug — textured
  surfaces clip, only opt-in alpha<1 blends). Billboard = `Drawable`+`Render2D` (being the
  Drawable also stops the virtual-gob self-remove sweep). Follow = a `Moving` subclass
  whose `getc()` = target + offset — the render tree does the per-frame work.
- **glTF parser 100% pure** (bytes + Json + Matrix4f — no GL/ImageIO): .glb chunks,
  data-URI/external buffers, general accessor decode, node TRS baked at parse (BASIS ·
  nodeWorld into H&H space — det +1 rotation × 11-units/metre), safety caps, named errors.
  Image blobs extracted lazy + deduped (53 materials → 4 images); the CALLER builds `TexI`s
  (`new TexI(img, false)` — POT rounding would break [0,1] UVs). Normals bake via the
  inverse-transpose (correct under shear; cheap to always do right); no-NORMAL meshes get
  smooth area-weighted computed normals off the BAKED geometry. Lighting = `Homo3D.normal`
  attribute + `Light.PhongLight` (neutral defaults) — the scene's own lights do the rest;
  sRGB deliberately a no-op (the engine doesn't sRGB model textures).
- **Ownership split by asset level**: object's own `Model`s vs mesh-owned shared `TexI`s vs
  image-owned screen textures; teardown ordered objects → meshes.

## Files created / modified
- New: `LuaImage`, `LuaWorldEntity`, `LuaSprite`, `SpriteQuad`, `LuaSpriteBillboard`,
  `FollowMoving`, `Gltf`, `LuaMesh`, `MeshSprite`, `LuaObject` (all `io.brodgar.addon`)
- `LuaGOut.java` — `g:image`/`g:aimage`; `LuaGhost.java` — rebased onto the shared core
- `AddonManager.java` (→ `RenderApi`) — image/sprite/model/object facades, follow helpers,
  the `*Ghost`→`*Entity` generalization, teardowns
- `addons/hello/` — v0.38→v0.44 (icon/sprite/billboard/follow/object demos, tank.glb);
  `addons/planner/` — v0.5.0/v0.6.0 (sprite + object kinds, cube.glb)
- **Zero `haven` edits across the whole series** (reuses the V-series seams).

## Risks & gotchas hit (detail: learnings/rendering.md)
- Alpha-clip vs blend for textured world surfaces (the ~1% ghost draft).
- A Drawable-less virtual gob self-removes every ctick — make the billboard the Drawable.
- UV v-orientation was a genuine can't-verify-headlessly item → a one-line `TEXV_FLIP` switch.
- Shared-texture dispose changes `mesh:dispose()` semantics — teardown order matters.
- `planner/cube.glb` was accidentally byte-identical to `hello/tank.glb` (2 MB) at first —
  ship the LIGHTEST asset that proves the flow (940-byte cube).
- Manifest `files` lists only `.lua` sources, not assets.

## Discarded alternatives
- A `.res` wrapper for addon assets — the PNG/glTF substrate skips the container.
- A third-party glTF lib — pure-Java parser keeps zero native deps + full headless tests.
- sRGB conversion for model textures — would mismatch the engine's own un-converted loads.

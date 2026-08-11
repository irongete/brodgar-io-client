# 012-custom-rendering — Spec

## What & why
**Custom, non-`.res` rendering** (D-034, safe-tier): addons draw their OWN assets — PNGs on
screen, PNGs standing in the 3D world (fixed quads + camera-facing billboards), and full
**glTF 2.0 3D models** (static subset, D-035) parsed by a hand-rolled pure-Java parser —
textured, multi-material, and lit by the world lights. Everything rides the shared world-
entity core the V-series built (transform/look/follow/click/gizmo/persistence), so a model
is just "a ghost with a different visual". Design:
[design/17-custom-rendering.md](../design/17-custom-rendering.md),
[design/18-custom-models-gltf.md](../design/18-custom-models-gltf.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.render.image(path)` (D-017 path-contained to the addon folder) + `g:image`/
      `g:aimage` on all three draw surfaces; native + scaled + anchored; alpha composites;
      `:dispose()`/teardown frees the `TexI`.
- [x] `hafen.render.sprite{image,…}` — a PNG standing upright in the world as a fixed quad
      (engine `TexRender.draw/clip` alpha-CLIP material → solid like `.res` art), full
      transform/look handle, gizmo-compatible; `follow`/`:offset` anchors it to a gob
      (a client `Moving` — zero per-tick Lua, floats above the player's head and tracks).
- [x] `billboard=true` — a camera-facing, constant-screen-size blit (the SpeakerIcon
      `Render2D` pattern, as the gob's `Drawable`); fixed sprites are click-selectable
      (`SpriteClicked`); planner places/moves/persists both kinds.
- [x] `hafen.render.model(path)`/`object{model,…}` — a `.glb`/`.gltf` stands in the world
      upright at the right size (basis +Y→+Z, det +1; 1 m = 1 tile), node TRS baked at
      parse; the maintainer's 53-prim `tank.glb` renders **textured** (4 deduped embedded
      PNGs, alpha modes, per-material cull) and — after R3c — **shaded by the world
      lights** (NORMAL baked via inverse-transpose; smooth computed fallback; emissive;
      `PhongLight`), light/shadow sides turning with rotation.
- [x] `:planner object` — click-select + gizmo + grid-anchored persistence for models
      (Lua-only slice; ships the 940-byte `cube.glb`).
- [x] Teardown ordering: objects (own GPU `Model`s) before meshes (shared `TexI`s);
      `:reload` leaks nothing at every level.

## Out of scope
- Skins/keyframe animation, full PBR maps, Draco/meshopt, `emissiveTexture`, `TEXCOORD_1`,
  custom `alphaCutoff` (engine clip fixed at 0.5), async decode, decal variants,
  billboard picking, per-object `:setModel` (→ ROADMAP / design/18 deferred lists).

## Context files
- `design/17-custom-rendering.md`, `design/18-custom-models-gltf.md` — the designs
- `src/io/brodgar/addon/LuaImage.java`, `LuaSprite.java`, `SpriteQuad.java`,
  `LuaSpriteBillboard.java`, `FollowMoving.java`, `Gltf.java`, `LuaMesh.java`,
  `MeshSprite.java`, `LuaObject.java`, `LuaWorldEntity.java` (the shared core),
  `RenderApi.java` (post-split home)
- `src/haven/TexI.java`, `TexRender.java`, `render/Homo3D.java` (vertex/normal),
  `Light.java` (PhongLight), `render/Model.java` — the public backings
- `docs/addons/api/render.md`, `ui.md` (g:image) — shipped surface
- `addons/hello/` (icon.png, tank.glb demos), `addons/planner/` (sprite/object kinds)
- `../011-virtual-entities/` — the world-entity core all of this plugs into

# 012-custom-rendering — Tasks

- [x] 012.1 — Screen images: `hafen.render.image` (sandboxed loader, cached handles) +
      `g:image`/`g:aimage` on all draw surfaces.
- [x] 012.2 — Fixed world sprite: `hafen.render.sprite` (TexI-quad, alpha-clip material);
      the `LuaWorldEntity` shared-core generalization; `follow`/`:offset` gob anchoring.
- [x] 012.3 — Billboard sprite (`billboard=true`, camera-facing Render2D) + sprite click
      dispatch (`SpriteClicked`) + planner sprite integration.
- [x] 012.4 — glTF static mesh: the pure `Gltf` parser (.glb/.gltf, accessor decode, node
      baking, basis conversion, caps) + `MeshSprite` + `hafen.render.model/object` (unlit).
- [x] 012.5 — Textures & materials: TEXCOORD_0, deduped embedded/external images,
      baseColorTexture × factor, alpha modes, per-material cull (the textured tank).
- [x] 012.6 — Planner object integration (`:planner object`, Lua-only; ships cube.glb).
- [x] 012.7 — Lighting: NORMAL baking (inverse-transpose) + computed-normal fallback +
      emissive + `Light.PhongLight` — models shade with the world. Completes the series.

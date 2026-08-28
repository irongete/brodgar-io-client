# 118 — A patch on the ground

## What and why

`hafen.vr()` stands four kinds of client-only thing in the world, and all four stand **up**: a prop, a
picture, a model, a window. Nothing lies **down** on the terrain.

What an addon draws on the ground today is a screen polygon: `gob:hitbox()` hands back an object's
footprint as rings of world [Position](../../docs/addons/api/position.md)s, and an addon projects every
corner with `worldToScreen` into a flat `g:poly`. That polygon knows nothing of the ground under it, and
being a blit on the finished image, nothing occludes it. `specs/ROADMAP.md` states the gap: *World-space
shapes: lines, polylines and filled areas in the world, which nothing can draw but a fan of sprites
(filed: 043).*

This ships **`hafen.vr():patch()`**: a convex ring of Positions lying exactly on the terrain, occluded by
whatever stands on it, and clickable.

It is exact because it does not try to approximate. The drawn terrain surface **cannot be reproduced from
outside** — `MapMesh.MapSurface` is per cut, implements `ConsHooks`, and `MapMesh` keeps a surface per
tiler — so independent geometry cannot land on it: lifted it floats, coplanar it speckles, and a depth
bias big enough to beat the ground at distance also beats the character standing on it. A patch therefore
goes through the engine's **own** ground overlay, which re-lays each masked tile over the ground's own
vertices, and carves its silhouette per fragment rather than from the tile mask.

## Acceptance criteria

Each is verifiable in-game through the owning task's own suite.

1. A patch built from a ring of Positions **lies on the terrain**: no float, no shimmer, no ground breaking
   through, at every camera distance and over slopes, ridges and tile boundaries.
2. Its silhouette is the **ring's own shape with a one-pixel edge**, at every zoom — not a staircase of
   tiles.
3. **`gob:hitbox()`'s rings go in unchanged**, with no projection and no conversion by the caller.
4. Anchored to a **Gob** it follows and dies with it; anchored to a **Position** it stays, and waits whole
   on unstreamed ground rather than drawing part of itself.
5. It answers the **shared `hafen.vr()` vocabulary**, and `:scale`, `:rotate`, `:tint` and `:alpha` change
   it while it is drawn without rebuilding any terrain mesh.
6. `:offset(x, y)` moves it on the ground; a **z raises**, naming why a patch has no height.
7. `:clickable(true)` with `:onClick(fn)` fires on a click **inside the ring and not outside it**, and
   `PatchClicked` fires on the bus for that addon alone, beside its three siblings.
8. A ring that is **concave, shorter than three points, or longer than the stated edge limit** is refused
   naming the limit, rather than drawn as the wrong shape.
9. Unprotected: a patch has no server id and grants nothing.

## Out of scope

- **A patch held above the ground.** That is the other mechanism — flat geometry issued into the scene —
  and letting `z` pick between them is a mode switch hiding behind a number. It buys nothing here either:
  a patch on the ground is already occluded by what stands on it. The other half would be a flat polygon
  at a stated height, as its own kind.
- **Concave rings.** `g:poly`'s own contract is convex, and the carve is the intersection of the ring's
  edge half-planes. Decomposing a concave ring is its own work.
- **Occlusion-aware clicking.** The hit test is the ring projected to the screen, so a patch behind a hill
  still answers a click: the engine's pick pass is asynchronous and cannot answer inside the event.

## Docs impact

Pages written: `docs/addons/api/vr/patches.md` (new), `vr/README.md`, `event/bus/world.md`,
`event/bus/README.md`, `api/README.md`, `api/gob.md`, and `docs/client/world-3d.md` — which is **already
over its 150-line ceiling**, so the task that adds its gotchas splits it, as `specs/ROADMAP.md` states. Task 1
also grew `docs/client/render-gl.md` with the shader-state map it leaned on — the DSL's loop, the array
uniform, and the two states a slot carries — so 118.4 has none of that left to write. Task 3 grew `docs/client/state.md` with the height read a projection
takes — `getcz`/`getzp` and the `LoadingMap` either throws off-stream — for the same reason.

Derived impact set — what a fifth kind makes false, and what no grep for `patch` would find:

```text
grep -rn "one-vocabulary-four-kinds\|GhostClicked\|SpriteClicked\|ObjectClicked" docs/
vr/README.md            heading "## One vocabulary, four kinds" — a COUNT in a heading, and an anchor
vr/ghosts.md:34         links that anchor — breaks when the heading is retitled
event/bus/world.md:155  links that anchor — breaks likewise
event/bus/world.md:145-153, event/bus/README.md:36
                        the clicked-entity key set and its `ev` table, closed at three
event/bus/world.md:150  "a ghost, sprite or object is private" — an enumeration, not a link
```

The two anchor links are re-pointed in the task that retitles the heading.

## Context files

- `src/haven/MCache.java` — `OverlayInfo`, `LocalOverlay`, `add`/`remove`, `olseq`, `tilesz` — 2
- `src/haven/MapMesh.java` — `makeol`, `OLOrder` — 4
- `src/haven/MapView.java` — `oltick`, `oltags`, `Overlay.rematerial`, the `// addon:` mouse hooks and the
  `mousedown` else-chain the patch click branch sits last in — 2, 3, 4
- `src/haven/render/RenderTree.java` — `Slot.add` and the two states it carries (`cstate`/`ostate`) — 2
- `src/haven/render/BaseColor.java` — the `State` + `Uniform` + `shader()` template — 2
- `src/haven/render/Homo3D.java` — `fragmapv` — 2
- `src/haven/render/sl/Cons.java`, `Function.java`, `Uniform.java`, `Array.java`, `Block.java`, `For.java`
  — the shader DSL, and the `Function.Def` a loop has to live in — 2
- `src/haven/render/gl/UniformApplier.java` — which uniform types map from which Java values — 2
- `src/io/brodgar/addon/PatchCarve.java` — the half-planes (`planes` builds them in whichever 2D space its
  points are already in), `convex`, `inside`, the `EDGES` limit, the carve state — 2, 3, 4
- `src/io/brodgar/addon/PatchClick.java` — the hit test: the ring projected, the front-to-back rule, and the
  world point a click met the patch's plane at — 4
- `src/io/brodgar/addon/SurfaceInput.java` — the synchronous pointer test a standing panel already had, and
  the three pure 3×3 helpers the patch's unprojection reuses — 3, 4
- `src/io/brodgar/addon/Eye.java` — the `w > 0` guard every projection in this layer goes through — 3, 4
- `src/io/brodgar/addon/LuaEvent.java` — the CLICKED payload, and the fourth noun `ev:patch()` — 3, 4
- `src/io/brodgar/addon/PatchOverlay.java` — the tile mask and the material — 2
- `src/io/brodgar/addon/LuaPatch.java` — the kind: its ring as offsets, `lay`/`lift`, `drawn`, `infoInto` — 2, 3
- `src/io/brodgar/addon/VrApi.java` — the section, its collections, `makePatch`/`patchHandle`, the three
  `instanceof LuaPatch` scene branches, bridge-owned teardown — 2, 3
- `src/io/brodgar/addon/LuaWorldEntity.java` — the shared vocabulary every kind answers — 2, 3
- `src/io/brodgar/addon/LuaCollection.java` — what a collection's `Source` declares — 2
- `src/io/brodgar/addon/Addon.java` — the per-addon registries, `patches` among them — 2
- `src/io/brodgar/addon/LuaSprite.java`, `GhostGob.java` — a kind, and how one is clicked — 2, 3
- `src/io/brodgar/addon/LuaPosition.java` — the durable place a ring is made of — 2
- `src/io/brodgar/addon/AddonManager.java` — `hitboxRings`, and the bus keys — 2, 3
- `src/io/brodgar/addon/Refusal.java`, `Args.java` — the refusal vocabulary — 2
- `docs/addons/api/vr/README.md`, `vr/patches.md`, `vr/ghosts.md`, `vr/models.md`, `vr/sprites.md`,
  `vr/widgets.md`, `api/README.md`, `api/gob.md`, `event/bus/world.md`, `event/bus/README.md` — 4
- `docs/client/world-3d.md` — split by 118.4 into itself (the scene, the ground overlays, materials)
  plus `docs/client/map-click.md`, `docs/client/terrain-raster.md` and `docs/client/camera.md`; the
  four are indexed in `docs/client/README.md`, and `mapfile.md`, `multi-session.md` and `services.md`
  link into them — 4
- `docs/client/render-gl.md`, `docs/client/state.md`, `DOCUMENTATION.md` — 4
- `tools/docverbs.py` — its `RECEIVERS` map, where `patch` is registered as a vr kind — 4

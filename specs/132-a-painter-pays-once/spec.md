# 132 — a painter pays once

## What and why

**The world has two ways to draw, and one is a painter.** `hafen.virtual():patch()` lays a mask into the
terrain once and costs nothing per frame after. The other is a painter hung on the map view, re-projecting
and re-drawing its rings every frame. `hitboxes` cycles between them, and its header says why the second
cannot be the first: *"a patch that ignored the ground would not be one"*.

The premise is wrong, and the fix is one op. `MCache.OverlayInfo.mat()` returns whatever `Material` its
implementor builds, and a `Material` carries any render state — `MapView.gridmat` is the client's own proof.
`PatchOverlay.set` already passes `States.maskdepth`, which `GLPipeState` answers with `glDepthMask(false)`:
it does not **write** depth and is still **tested**, which is precisely why a wall hides it.
`States.Depthtest.none` puts `null` in that slot, and `GLPipeState` answers `glDisable(GL_DEPTH_TEST)`. A
patch the world cannot hide is one more op in the `Material` that method already builds.

That ends the per-frame cost — not a cheaper painter, but no painter. Measured live, `g:line` alone was
**3.2 % of all client allocation** while `hitboxes` traced with it, and a twelve-point ring asked for
twelve draw calls a frame. Through a patch it allocates nothing per frame and asks for one draw call per
terrain **cut** its mask reaches — the engine lays a second mesh over each of those and each is one slot.
That is a constant of the ground, not of the ring: eight times the points cover the same cuts and cost the
same, which is the whole of what "pays once" means here.

**`PatchOverlay.filter` allocates where the map says it must not.** `world-3d.md` states that
`MCache.olreaches` puts a filter to one cut's tiles *"exactly and with no allocation"*. Ours answers
`b.overlap(tiles) == null`, and `Area.overlap` builds two `Coord` and an `Area` to return a boolean —
**0.8 % of all client allocation** from one expression, because `olreaches` runs per cut, per overlay, per
frame under `MapView.oltick`, the most expensive thing in the frame loop. `tags()` mints a fresh
`Arrays.asList("show")` per call, and `oltick` calls it per overlay per frame.

**`followPatches` does the expensive read unconditionally.** Its own comment promises "nothing at all for a
patch on an object standing still", yet it calls `Gob.getc()` for every follower every frame, and `getc()`
descends to `MCache.getzp` → `getgridt`, the client's largest allocation site.

## Acceptance criteria

1. A patch can be told the world may not hide it, and one so told draws whole through a wall, a hill and a
   house — while the HUD still covers it, since the overlay is drawn inside the map view's own pass.
2. It is a property read and written by arity, defaulting to "the world hides it", so a patch that never
   names it draws exactly as it does today.
3. Naming it re-pushes the material **without re-carving the ring**, the way a colour change already does.
4. Showing a ring that way costs no draw call and no allocation per frame **over showing it the ordinary
   way**: `p:gl().drawCalls` and `p:memory().allocPerFrame` are flat across a run with the flag on and one
   with it off, since the flag is one op in a material that is rebuilt either way. What a patch costs at
   all is the cuts its mask reaches, and that is bounded by the ground rather than by the ring.
5. A patch's `filter` and `tags` allocate nothing, so `olreaches` costs what `world-3d.md` says it costs.
6. A patch anchored to an object that has not moved costs no `Gob.getc()`; one that has moved still follows
   within the frame it moved.
7. Everything a patch already does is unchanged: tint, border, offset, rotation, `drawn()`, and ending
   with its Gob.

## Out of scope

- **A run-of-points verb on `g`.** `drawing.md` can trace a shape only as one `g:line` per edge, and
  `g:rect`/`g:frect` sets a pair that `g:poly`, which fills, contradicts. Not urgent once world shapes go
  through a patch, and not cheap: flipping `g:poly` to the outline is neither a rename nor a reshape but a
  silent change of picture under four live callers, with nothing for a refusal to key on. Its own feature,
  and it must settle that name first.
- **`world:worldToScreen(p)` minting a Lua table per point.** Its `{x=, y=}` is half a documented round
  trip — `screenToWorld(pt, fn)` takes that shape back — so one end alone is unfinished, and both ends is
  nine pages and a hard cut on a pair.
- **The same limitation on the other world surfaces.** A world-scaled sprite and a world panel are occluded
  like anything else, and say so on their own pages; the same op would free them, each with its own
  material and page.
- **What an addon costs the frame outside the world** — style resolution per widget, the chat find's
  parent walk per message, the session recall's grid-cache copy per ctick, the hidden-window scan per
  checkbox. Four surfaces, one feature.

## Docs impact

`docs/addons/api/virtual/patches.md` — the new verb, and what it changes and does not: the HUD still covers
it, the shape still follows the slope. No other page.

Derived impact set — every page stating what the scene does to what an addon puts in it:

```text
$ grep -rln "occlud" docs/addons/ | sort
docs/addons/api/virtual/ghosts.md
docs/addons/api/virtual/patches.md     <- lines 9 and 97, this feature's page
docs/addons/api/virtual/sprites.md
docs/addons/api/virtual/widgets.md
```

The three others say it of their **own** surfaces — untouched here, still true, and said so on the page
rather than left to be inferred.

## Context files

- `src/io/brodgar/addon/PatchOverlay.java` — 1, 2 (`set`, `mat`, `filter`, `tags`, `fill`, `coverage`)
- `src/io/brodgar/addon/VirtualApi.java` — 1, 3 (the `patch:` verbs, `makePatch`, `followPatches`,
  `followPatch`, `setGrounded`, `followers`)
- `src/haven/render/States.java`, `src/haven/render/gl/GLPipeState.java` — 1 (read only: `maskdepth`,
  `Depthtest`, `Depthtest.none`, and what each answers in GL)
- `src/haven/MapView.java` — 1 (read only: `gridmat`, the precedent for a depth-free overlay material)
- `src/haven/Area.java` — 2 (read only: `overlap`, `isects`, `contains`)
- `src/haven/Gob.java` — 3 (read only: `getc`, `getrc`, `DefaultPlace.check`, `rc`)
- `docs/client/world-3d.md` — 1, 2 (read only: the overlay rows, and what `olreaches` may cost)
- `docs/addons/api/virtual/patches.md` — 1 (the page this feature writes)
- `DOCUMENTATION.md` — 1

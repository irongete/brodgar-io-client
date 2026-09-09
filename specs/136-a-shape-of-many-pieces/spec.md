# 136 — a shape of many pieces

## What and why

A patch is **one** convex ring. Its silhouette is carved as the intersection of that ring's edge
half-planes, so a concave shape is refused by name, and a shape that needs more than one convex piece is one
patch each. That is the wrong unit twice over: `PatchOverlay` masks each patch's bounding box, so twenty
pieces mask twenty overlapping boxes and pay the cuts of all of them; and every registered overlay is walked
by `MCache.getols`, copied by `MCache.ctick` and tested per cut by `MapView.oltick`, every frame, for as long
as it is laid.

This makes a patch **a set of convex pieces, drawn as their union, through one overlay**. One piece is
exactly today's patch, unchanged. The engine knows convex pieces and union — the general vocabulary for a
planar region — and nothing about what an addon tessellates into them: a freehand stroke, a polyline, a
concave field, a ring of quads are all the same shape to it.

`hafen.virtual():patch():add(ring, anchor)` keeps its meaning and lays a patch of one piece.
`patch:piece()` is the collection of that patch's pieces — `:add(ring)` lays another and hands it back,
`:remove(p)` takes one up, `:list`/`:count`/`:find` read them — and pieces may be laid and taken up while
the patch is drawn, which is what lets a shape be built under the pointer.

Two decision records defer here. 119 recorded *collapsing many patches into one overlay* as "a real answer
to the same symptom, and a redesign of the carve's single-polygon uniform". 121 recorded *a ring of thin
convex quads* as rejected because `PatchOverlay.coverage` masks a box per shape. `ROADMAP.md`'s candidate
**World-space shapes: lines, polylines and filled areas in the world** (filed: 043) is this ground.

## Acceptance criteria

1. A patch of several convex pieces draws their **union**: a concave shape laid as two overlapping quads is
   drawn as that shape, not as its hull and not as two shapes with a seam between them.
2. `patch:piece()` answers the canonical collection verbs. `:add(ring)` hands back the piece; `:count()`
   moves with each `:add` and each `:remove`; `:list()` is every live piece in the order they were laid.
3. Every ring rule holds **per piece** and names itself: fewer than three points, enclosing nothing, concave,
   not a Position, no route to the anchor, and more than 32 edges in one ring — all refused with the wording
   they have today, so a ring that is refused now is refused the same way.
4. A patch carries a **stated edge budget across all its pieces**, and exceeding it raises naming that
   number. The budget is the length of the fragment stage's own array and is larger than 32.
5. `patch:piece():remove(p)` takes one piece up and leaves the rest drawn; `p:exists()` reads `false` after.
   A patch with no pieces left exists and draws nothing.
6. `patch:info()` carries `pieces` — an array of rings, each an array of `{gridId, x, y}` — and no `ring`.
7. `patch:border(c, w)` bands the **union's** outline, not each piece's, so an internal join carries no line.
8. A click lands on a patch when the point is inside **any** of its pieces, and `PatchClicked` carries the
   world point as it does today.
9. A one-piece patch is unchanged in every respect: it draws, clicks, borders, tints, scales, turns, follows
   and waits for its ground exactly as before, and the four addons under `addons/` that lay patches go on
   working untouched.

## Out of scope

- **Chunking one patch across several overlays.** The budget is one overlay's array, and an addon that wants
  a bigger shape lays another patch — which is the same overlay count Java-side chunking would reach, with
  no second owner of the shape. If a single-handle shape of unbounded size is ever wanted, that is its own
  feature.
- **A shape vocabulary.** No line, stroke, circle, arc or brush verb: the caller hands convex rings.
- **Anything a pencil addon needs beyond the shape** — input, decimation, colour choice, undo, persistence.
  `hafen.ui():mouse():grab()` and `s:world():screenToWorld` already carry the input half.
- **The `hafen.virtual()` shared vocabulary**, unchanged for all five kinds.

## Docs impact

Written: `docs/addons/api/virtual/patches.md` (276/300 lines — the pieces section pushes it over, so it is
split in the task that writes it), `docs/addons/api/virtual/README.md`, `docs/addons/api/types/world.md`
(its `WorldEntity` `kind` row lists four kinds and omits `"patch"` — a gap in the snapshot this feature
reshapes), `docs/addons/api/README.md`.

Derived impact set — `grep -rln "virtual():patch\|patch:" docs/` gives `virtual/patches.md`,
`virtual/README.md`, `client/addon.md`, `gob.md`, `placing.md`; `grep -rn "info().ring\|\`ring\`" docs/`
gives `virtual/patches.md` (lines 30, 46, 127, 129, 130) and `virtual/README.md` (line 168);
`grep -rln "convex" docs/` adds `api/README.md` and `ui/drawing.md` (the latter is `g:poly`, unrelated —
discharged). `PatchClicked` in `conventions.md`, `event/bus/README.md` and `event/bus/world.md` is unchanged
and discharged there. No `docs/client/` page is owed: `world-3d.md`, `terrain-raster.md` and `render-gl.md`
already map every seam this feature reads.

## Context files

- `src/io/brodgar/addon/PatchCarve.java` — 1, 3
- `src/io/brodgar/addon/PatchOverlay.java` — 1
- `src/io/brodgar/addon/LuaPatch.java` — 1, 2, 3
- `src/io/brodgar/addon/PatchClick.java` — 3
- `src/io/brodgar/addon/Eye.java` — 3 (it is what answers `null` for a point behind the eye, which is
  the whole of "a piece with a corner behind the eye is dropped")
- `src/io/brodgar/addon/VirtualApi.java` — 1, 2, 3
- `docs/addons/api/virtual/patches.md` — 1, 2, 3
- `docs/addons/api/virtual/pieces.md` — 2, 3 (split out of `patches.md` by 1: the pieces collection,
  what a ring may be, and the two edge budgets)
- `docs/addons/api/virtual/README.md` — 1, 2
- `docs/addons/api/conventions.md` — 2
- `docs/addons/api/types/world.md` — 2
- `docs/client/world-3d.md` — 1
- `addons/R03-args-refuses-non-numbers.1/main.lua` — 1 (asserts the 32-edge refusal wording)

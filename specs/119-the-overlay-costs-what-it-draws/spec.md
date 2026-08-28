# 119 — The overlay costs what it draws

## What and why

`MCache.olseq` is **one counter for every ground overlay in the client**. `MCache.add`, `MCache.remove`
and `RectOverlay.update` bump it, and `MCache.Grid.getolcut` answers by disposing and clearing *every*
`OverlayInfo`'s cut meshes in that grid — both `Cut.ols` and `Cut.olols`. So registering the Nth overlay
rebuilds the other N-1.

That was a fair trade while overlays were the four the client ships: personal claims, village claims,
provinces and the drag-selection rectangle, changing rarely and never in bulk.
[A patch](../../docs/addons/api/virtual/patches.md) is one overlay **per shape**, so an addon that lays a
patch per object makes dozens in seconds, and each one re-cuts everything already laid. Walking with such
an addon active stalls in proportion to how much is already on the ground — the more it has marked, the
longer the hitch.

The cache is already keyed right: `Cut.ols` is a `Map<OverlayInfo, RenderTree.Node>`. Only the
invalidation is over-broad. A `LocalOverlay`'s mask reaches its own `id()` and no other — `MCache.getol`
reads the grid's recorded masks for that id plus the locals whose `id()` matches — so dropping the rest is
work for nothing.

Two more defects sit in the same path and are fixed with it: `MCache.getols` is quadratic per frame, and
every overlay pays for an outline mesh nobody asked for.

## Acceptance criteria

Each is verifiable in-game through the owning task's own suite.

1. `hafen.client():profiling():render()` answers **`overlayMeshes`** and **`overlayOutlines`**, cumulative
   cut meshes built since client start, never decreasing, and readable with profiling disarmed like the
   counters beside them.
2. **Registering one overlay rebuilds only its own cuts.** Laying one more patch moves `overlayMeshes` by
   the same amount whether five or fifty are already laid.
3. The same holds for **removing** one, and for a `RectOverlay` whose mask moved: each invalidates its own
   `id()` and nothing else.
4. What genuinely means *everything changed* still does: a grid filled from the server, and a cut whose
   ground mesh was rebuilt, both drop that grid's overlay meshes as they do today.
5. An overlay whose **`omat()` is null builds no outline mesh** — `overlayOutlines` does not move when one
   is laid — and one that has an outline material still draws it.
6. `MCache.getols` answers the same overlays in the same order while no longer scanning what it has
   already collected.
7. Nothing about the Lua surface changes but the two counters: a patch is laid, moved, tinted and taken up
   exactly as [its page](../../docs/addons/api/virtual/patches.md) states.

## Out of scope

- **Collapsing many patches into one overlay.** One `OverlayInfo` per shape is what the carve's
  single-polygon uniform forces, and it is a redesign of that shader rather than a fix to invalidation.
  After this, a patch costs its own cuts and no one else's, which is what it should have cost.
- **`MCache.trim`/`trimall` disposing cut meshes while a raster still holds their slots.** A separate
  defect on the same page with a separate cause — the dispose order, not the invalidation breadth.
- **The recorded map's overlays** (`MapWnd.overlays`, the minimap's `realm` masks). A different subsystem
  that shares only the word.

## Docs impact

Pages written: `docs/client/world-3d.md` (the two gotchas below), and
`docs/addons/api/client/profiling/counters.md` (the two counters, in the `render()` table — 262 lines, so
a row fits).

Derived impact set — the prose this makes false, which no grep for the new counter names would reach:

```text
grep -rn "olseq\|whole grid\|every overlay mesh\|re-cut\|tile-laying passes" docs/
world-3d.md:53-58   "Gotcha -- registering a LocalOverlay is a re-cut of the whole grid" -- FALSE after
                    this: it becomes a re-cut of that overlay's own cuts, and the advice built on it
                    ("move one only when it leaves the tiles it covers") loses its reason
world-3d.md:79-85   "Gotcha -- the sheet's two hidden costs" -- BOTH halves go: the whole-sheet rebuild
                    and the unconditional outline mesh
virtual/patches.md:105  "moving one far enough to leave the tiles it covers does re-cut those tiles" --
                    understates today and becomes exactly true; discharged, not rewritten
```

## Context files

- `src/haven/MCache.java` — `olseq`, `ols`, `add`, `remove`, `RectOverlay.update`, `Grid.getolcut`,
  `Grid.getololcut`, `Cut.ols`/`Cut.olols`, `getols`, `getol`, and the `mapdata2` bump — 1, 2, 3
- `src/haven/MapMesh.java` — `makeol`, `makeolol`, `OLOrder` — 1, 3
- `src/haven/MapView.java` — `oltick`, `oltags`, `Overlay.added` and its `outl` grid, `rematerial` — 2, 3
- `src/io/brodgar/addon/ProfHandle.java` — where `render()`'s counters are assembled; the two cumulative
  tallies themselves live in `AddonManager`, beside `gobsHeld` — 1, 4
- `src/io/brodgar/addon/PatchOverlay.java` — the overlay a patch registers, and its null `omat()` — 2, 3
- `docs/client/world-3d.md` — 4
- `docs/addons/api/client/profiling/counters.md`, `docs/addons/api/virtual/patches.md` — 4
- `DOCUMENTATION.md` — 4

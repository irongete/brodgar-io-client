# 119 — plan

## Approach

**The cache is already per overlay; only the invalidation is not.** `Cut.ols` and `Cut.olols` are
`Map<OverlayInfo, RenderTree.Node>`, so a cut already knows which mesh belongs to which overlay.
`MCache.olseq` is one `volatile int` for all of them, and `Grid.getolcut` answers a change to it by
disposing and clearing every entry in every cut of the grid. Replace the one counter with **a sequence per
`OverlayInfo`**, and give each cached entry a **stamp** of the sequence it was built at. `getolcut` then
compares one id's stamp against one id's sequence and drops one entry.

**The thread rule is what the current shape buys, and it must survive.** `MCache.add` is called from Lua
while `getolcut` runs on the tick thread, and `Cut.ols` is a plain `HashMap`. Today that is safe only
because the adding side touches nothing but a `volatile int`, and every mutation of the maps happens
inside `getolcut`. So the per-id sequence lives in a concurrent map that the adding side bumps and the
consuming side reads; **no caller of `add` ever touches a `Cut`.**

**Removal needs a second site, and it is the leak the global flush was hiding.** `getolcut` is only
called for ids `MapView.oltick` still lists, so after `MCache.remove` nothing ever asks for that id again
and its meshes would sit in every cut, undisposed, forever — today any later bump flushes them by
accident. So `remove` enqueues the id for a drop, and **`MCache.ctick` drains that queue**: it already
walks every loaded `Grid` once per client tick, on the same thread `getolcut` runs on, and it already
ticks every `LocalOverlay` beside them. One drain there disposes that id's entries across every grid.

**What still means "everything changed" keeps the global path.** Two bumps are not about one overlay: the
`mapdata2` grid fill, where the server replaced a grid's recorded masks, and `Grid`'s own `olseq = -1`
when a cut's ground mesh was rebuilt and its overlays must be re-laid over new vertices. Those keep a
grid-wide sequence and the flush that goes with it; `add`, `remove` and `RectOverlay.update` stop using it.

**The counters are what makes the claim testable.** `getolcut` is the one place a cut's overlay mesh is
built, so two cumulative counters incremented there — one for `makeol`, one for `makeolol` — are read
through `ProfHandle`'s `render()` table beside `gobsHeld`, which is cumulative in the same way. Laying one
more patch with five down and with fifty down then moves `overlayMeshes` by the same amount, which is the
whole of criterion 2 and needs no stopwatch.

**The two cost defects are one line each.** `getolcut` builds `makeolol(id)` unconditionally while
`MapView.Overlay.added` adds the `outl` grid only when `omat() != null`, so building it under the same
condition costs nothing and saves a full tile-laying pass per cut for every overlay that has no outline.
And `MCache.getols` accumulates into an `ArrayList` and calls `ret.contains(...)` per candidate, once per
frame from `oltick`: a `LinkedHashSet` answers the same overlays in the same order with no scan.

## Files to create and modify

| File | What |
|---|---|
| `src/haven/MCache.java` | the per-id sequence and its concurrent map; `Cut.olstamp`; `add`/`remove`/`RectOverlay.update` bumping one id; the pending-drop queue and its drain in `ctick`; `Grid.getolcut` comparing one stamp and building `makeolol` only when `omat() != null`; `getols` on a `LinkedHashSet` |
| `src/io/brodgar/addon/ProfHandle.java` | `overlayMeshes` and `overlayOutlines` in the `render()` table |
| `docs/client/world-3d.md` | both gotchas rewritten to what is true after this |
| `docs/addons/api/client/profiling/counters.md` | the two counters, in the `render()` table |
| `docs/addons/api/virtual/patches.md` | discharged: its cost line understates today and becomes exact |

## Risks and gotchas

- **The stamp and the entry must be dropped together.** The grid-wide flush clears `Cut.ols` and
  `Cut.olols`; it must clear `Cut.olstamp` with them, or an id whose stamp survived reads as current
  against a mesh that no longer exists.
- **`MCache.remove` is also reached from `RectOverlay`'s deprecated `Overlay.destroy`.** Both go through
  the same drop path, or a mask that was taken up leaves its mesh behind.
- **`getololcut` calls `getolcut` first** and then reads `olols.get(id)`. With the outline built
  conditionally it answers `null` for an overlay with no outline material — which is already legal
  (`TreeSlot` calls `added` only on a node that is there), and is the branch `MapView.Overlay.added` never
  reaches anyway.
- **The per-id map must not grow forever.** An addon that lays and takes up thousands of patches over a
  session mints an `OverlayInfo` each time; the sequence entry is dropped in the same drain that disposes
  the meshes, so the drain is what bounds it and not a sweep of its own.
- **`Grid.ols` is a different field from `MCache.ols`** — the first is the server's `Indir<Resource>[]`
  for that grid, the second the client's `Set<LocalOverlay>`. Only the second is what `add`/`remove` touch.
- **A cumulative counter is read with profiling disarmed**, like the rest of `counters()`: they count
  work the client does anyway, so nothing may gate the increment on the profiler being on.

## Discarded alternatives

- **Dropping the entries from `MCache.add`/`remove` directly** — rejected: `add` arrives on the Lua thread
  and `Cut.ols` is a plain `HashMap` mutated by the tick thread inside `getolcut`; a drop from the caller
  is a data race, and the current `volatile int` exists precisely to avoid one.
- **Batching the adds so a burst costs one bump** — rejected: it narrows the window without closing it,
  since a burst spread over frames is exactly the case that stalls, and it leaves the cost quadratic in
  the number of frames the burst takes.
- **A `WeakHashMap` for the per-id sequences** — rejected: a `Cut` holds the `OverlayInfo` as a map key,
  so nothing becomes weakly reachable until the meshes are dropped anyway, and the drain that drops them
  can remove the sequence in the same pass.
- **Timing the adds in the suite instead of counting meshes** — rejected: a stopwatch turns a machine's
  load into a test result, and the claim is about *how much work is done*, which a counter states exactly.
- **Collapsing many patches into one overlay** — a real answer to the same symptom, and a redesign of the
  carve's single-polygon uniform rather than a fix to invalidation; after this a patch costs its own cuts,
  which is what it should always have cost.

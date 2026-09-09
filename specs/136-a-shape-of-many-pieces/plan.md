# 136 — a shape of many pieces · plan

## Approach

**The carve becomes a union inside the loop it already has.** `PatchCarve`'s half-plane rows are
`{nx, ny, d, 0f}` and the fragment reads `.xy` and `.z` — **`.w` is free**, and it carries the piece index.
The loop keeps two locals: `m`, the current piece's running minimum, and `best`, the maximum folded so far.
An `If` inside the `For` (`haven.render.sl.If`, as `Lighting` and `CloudShadow` use it) folds `m` into `best`
and resets it wherever `.w` rises; one fold after the loop closes the last piece. `best` is the union's
signed distance, and **every expression below it is untouched** — the `fwidth`, the border band, the
silhouette `smoothstep`. The bound is still a uniform, so control flow stays uniform across the quad and
`fwidth` stays defined.

`PatchCarve.of` takes a list of pieces, runs each through **`planes()` unchanged** and concatenates, writing
the index into `[3]`. `planes()` stays space-agnostic: `PatchClick` is its other caller and hands it screen
coordinates.

**Two caps, not one.** `RING_EDGES = 32` is the per-ring cap and keeps its refusal word for word
(`addons/R03-args-refuses-non-numbers.1` asserts "at most 32" on a 40-point ring). `EDGES = 128` is the
array's declared length and the per-patch total. A ground-overlay material is `BaseColor` + `States.maskdepth`
+ `MapMesh.OLOrder` + the carve with **no `Light.PhongLight`**, so that program carries no light array: 128
rows is 512 of the 1024 fragment uniform components GL guarantees, against a couple of matrices and
`FrameInfo`. `PatchCarve`'s own javadoc justifies 32 by "its matrices, **its lights**" and is wrong about
the program it is compiled into; correct it in the same task.

**The mask is per piece.** `PatchOverlay` keeps an `Area[]` of the pieces' boxes (each a tile proud) beside
the `Area` that bounds them all. `filter(b)` answers the bound first — one `Area.isects`, which rejects
nearly everything — then true only if no piece box intersects. `fill` overlaps per box. `set` reports
whether the **box array** moved, which is the re-cut cue `lay()` already reads. `world-3d.md` is explicit
that a generous `filter` costs *cuts*, not merely a mask fill — which is what the second pass buys.

**`LuaPatch` holds pieces of offsets.** `local` becomes a list of `Coord2d[]`, still displacements from the
anchor, so the durable place, the follow poll and `VirtualApi.reground` are untouched. `worldRing()` becomes
`worldPieces()` — the same arithmetic, per piece — and `lay()` keeps its three outcomes verbatim.

**`patch:piece()` is a view**, re-derived from the patch as `gob:overlay()` is: `:add(ring)`, `:list`,
`:count`, `:find`, `:remove(p)`. It carries no `:get` — a piece has no key, so the miss names
`:find`/`:list`, as the keyless collections do. A string filter is refused as the patch collection's is —
a piece is a picture of nothing. A **piece** answers `:exists()` and `:info()` (`{ring = …}`) and nothing
else, so `ring` survives where it names one thing.

**The click is per piece.** `PatchClick.hit` projects and tests each; the patch is hit if any contains the
point, and the depth that orders frontmost is the hit piece's. A corner behind the eye now drops that piece
**alone**, where today it drops the whole patch.

**The border needs no code.** The band comes off `m`, now the union's own distance, so it outlines the union
and lays no line across an internal join — an assertion, not work.

**The docs split is priced by inbound anchors.** `#the-border` (`client/profiling/counters.md`,
`virtual/README.md`), `#clickability` (`event/bus/world.md`) and `#the-patch` (`virtual/README.md`) all stay
put. What moves is `## The ring` and `## What a ring may be`, which nothing links into, joined by the new
material, into `docs/addons/api/virtual/pieces.md`.

## Files to create / modify

- create `docs/addons/api/virtual/pieces.md`; modify `virtual/patches.md`, `virtual/README.md`,
  `api/README.md`, `types/world.md`
- modify `PatchCarve.java`, `PatchOverlay.java`, `LuaPatch.java`, `PatchClick.java`, `VirtualApi.java`
- create `addons/136-a-shape-of-many-pieces.1`, `.2`, `.3`

No `docs/client/` page is owed: `world-3d.md`, `terrain-raster.md` and `render-gl.md` map every seam read.

## Risks & gotchas

- **Map space is world space with `y` negated.** `PatchCarve.of` negates once, at that one point, and the
  per-piece path must keep it there — `planes()` knows no space and must go on knowing none.
- **A uniform is baked at slot construction, never re-read** (`GLDrawList.DrawSlot.getsettings`). A changed
  piece set reaches the screen only as a new `PatchCarve` pushed through `MapView.rematerial`; mutating the
  array propagates nothing.
- **`MCache.add`/`remove` is the only way a changed mask takes effect**, and it bumps that id's own sequence
  (`MCache.olbump`), re-cutting this overlay's cuts alone. `remove` disposes nothing itself: the meshes
  go a tick later, in `MCache.ctick`'s drain.
- **NaN is asked before convexity, per piece.** A NaN edge survives normalisation and makes `inside()`
  answer true for every screen pixel — a patch that swallows every press in the map view.
  `PatchCarve.nonFinite` runs on each piece before its own `planes()`.
- **Two pieces that abut without overlapping leave a seam**: `m` is 0 along the shared edge and the
  silhouette draws a half-alpha hairline. Pieces must overlap, and `pieces.md` says so.
- `LuaPatch.local` is `final` today; `worldRing()` is called under the monitor by `lay()`, `infoInto()` and
  `PatchClick`, and its replacement inherits that.
- Four addons lay patches — `hitboxes`, `session-manager`, `simple-animal-radius`, `simple-gob-hider`. None
  reads `info().ring` and all four call only `:add(ring, anchor)` and `:border`, so all four must go on
  working untouched (criterion 9).

## Discarded alternatives

- **A sixth `hafen.virtual()` kind for a multi-piece shape** — two kinds that both lay a shape on the ground
  is the dual style the grammar bars, and the whole shared vocabulary would have to be written twice.
- **Keeping `patch:info().ring` beside `pieces`** — one concept with two spellings, which every reader then
  handles forever to spare a cut that costs nothing today.
- **A metatable on `patch:info()` that raises for `.ring`** — a snapshot is a plain table, and dumping one
  with `pairs` is documented across the section; one snapshot that is not a table is a worse trap than the
  missing key.
- **Pieces as data (`patch:pieces(list)` plus `:append(ring)`) rather than objects** — removing one then has
  no address, so an eraser resends the whole set, and the grammar's own answer for a set is a collection.
- **Chunking one patch across several overlays in Java** — it reaches the same overlay count an addon
  reaches by laying a second patch, and puts a second owner between the shape and the thing that draws it.
- **A segment-and-width primitive (one capsule per uniform row) instead of convex pieces** — four times
  denser for a line, and it makes the engine know what a line is; every other shape then needs a primitive
  of its own, which is a shape vocabulary rather than a shape.
- **Raising the per-ring cap above 32** — nothing asks for a 33-gon, a live suite asserts the wording, and
  the budget that actually binds is the patch's total.
- **One overlay per piece, the patch merely owning them** — precisely the cost 121 recorded against a ring
  of thin convex quads, and the thing 119 deferred to here.
- **A branchless fold (`step`/`mix`) over the piece boundary** — the loop's control flow is uniform because
  its bound is, so an `If` is legal, reads as the thing it does, and costs a compare the arithmetic pays too.

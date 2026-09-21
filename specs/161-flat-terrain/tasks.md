# 161 — Flat terrain: tasks

Two tasks, in this order: the seam first, because the walls are drawn on the plane it makes. Every
Java edit in `src/haven/**` is tagged `// addon: 161.N`. Every task builds from a clean tree
(`rm -rf build/classes; ant hafen-client` → `BUILD SUCCESSFUL`, then `ant bin`), runs
`python tools/docverbs.py` and `python tools/refusalverbs.py` (both must exit `0`), and ships its
suite at `addons/161-flat-terrain.N/` with `manifest.json` (`"id": "161-flat-terrain.N"`,
`"permissions": ["client.settings"]`) and `main.lua`, registered as `hafen.console():on("t161", …)`
and printing the `[pass]`/`[fail]`/`[manual]`/`[summary]` lines the 160 suites print. A suite
restores every setting it wrote before its `[summary]` line. Java is source level 1.8. No mention of
any other client, anywhere.

- [x] **161.1 — Flat terrain: the switch and the height seam.** The world draws flat; cliffs are
      not walled yet (the next task), so on a flat world a cliff line is, for now, an edge with
      nothing standing on it.
      *The switch.* `Performance`: `public static volatile boolean flatTerrain =
      Utils.getprefb("perf-flatterrain", false);` and `public static void flatTerrain(boolean on)`
      shaped exactly like `transitions(boolean)`: `changed = on != flatTerrain;
      Utils.setprefb("perf-flatterrain", flatTerrain = on); if(changed) groundGeneration++;` — the
      stamp 160.4 put on every cut's mesh re-meshes the drawn cuts lazily. Add it to the class
      javadoc's list. `PerformancePanel`: in the **Ground** section, after the *Tile transitions*
      box, a `CheckBox("Flat terrain")` in the same shape (`{a = Performance.flatTerrain;}`,
      `set(val)` → `Performance.flatTerrain(val); a = val;`, `tick` follows the static), tooltip in
      the panel's voice: draws the whole world at one height so nothing is hidden behind a hill;
      cliffs stand as walls one tile high, water keeps its depth; it is not a performance setting,
      the same ground is drawn at another height; applies live, cut by cut. `PerformanceOptions`:
      `m.set("flatTerrain", new OptionsMethod(owner, handle, "performance:flatTerrain") { onRead →
      LuaValue.valueOf(Performance.flatTerrain); onWrite → Performance.flatTerrain(bool(value,
      "on", "whether the terrain is drawn flat")) })`.
      *The seam.* `MCache`: rename the body of `getfz(Coord tc)` into a new `public double
      getrealfz(Coord tc)` (`Grid g = getgridt(tc); return(g.getz(tc.sub(g.ul)));`) and make
      `getfz` read `double z = getrealfz(tc); return(io.brodgar.perf.Performance.flatTerrain ? 0.0 :
      z);` — the grid is reached **before** the flatten, so `getgridt`'s map request and its
      `LoadingMap` behave exactly as the comment above `getgridt` describes. Add `public double
      getrealcz(double px, double py)`: a copy of `getcz(double, double)` with `getrealfz` in place
      of `getfz` in its four corner reads. `// addon: 161.1` on both. `Tileset.Flavor.Terrain.getfz`:
      `double z = grid.getfz(tc.add(toff)); return(io.brodgar.perf.Performance.flatTerrain ? 0.0 :
      z);` — the terrain a served flavor factory reads agrees with the mesh. `WorldApi`, the
      `height` verb: `mc.getcz(rc.x, rc.y)` becomes `mc.getrealcz(rc.x, rc.y)`, with a comment that
      the API answers the streamed height whatever the panel draws. Touch nothing else: the mesh
      (`MapMesh.MapSurface`), the surfaces (`Tiler.MapZSurface`, `WaterTile.BottomSurface`), the
      placers, the camera, `Sessions.groundz`, the decals and the addon projections all read
      through `getfz`/`getcz`/`getzp` and follow. Confirm that with `grep -rn "getfz(\|getcz(\|getzp("
      src` against the table in `plan.md` before building.
      *Docs.* `docs/addons/api/client/README.md`: the section's first sentence gains *and whether
      its relief is drawn*; the `flatTerrain` row in the `performance()` table (`boolean`, read
      Unprotected / write `client.settings`, *Draw the terrain flat: every tile corner at one
      height, objects standing on that plane, cliffs as walls one tile high, water keeping its
      depth. Default `false`.*); a rule *Flat terrain changes the picture only* — the server's
      heights, the recorded map and `session:world():height` stay real; the click lands on the tile
      under the cursor; it applies live, cut by cut, and objects reach the plane a moment before
      their hill does; every *twelve* counting the verbs becomes *thirteen* or is reworded to count
      nothing; the *Handle | Covers* row gains *flat terrain*. `docs/addons/api/world.md`: a rule
      on `:height(position)` — the streamed height, whatever the Performance panel's flat terrain
      draws; nothing in the API reads the drawn height. Create `docs/client/terrain-height.md`
      (§12; under 150 lines; class and member names, no line numbers): a *What | Where* table for
      **the height chain** — `MCache.Grid.z` and `Grid.getz`/`getfz` (the record's read;
      `MapFile.update` copies `z`, `MapSource` is what the minimap's cliff lines read),
      `MCache.getfz` (the drawn read; fork: flattened after the grid is reached) and `getrealfz`
      (the streamed read; `WorldApi` `height`, `Ridges` detection from 161.2), `getcz`/`getzp` and
      `getrealcz`, `MCache.ZSurface` and `zsurf`, `Tiler.MapZSurface` and `MapMesh.getsurf(id,
      tiler)`, `WaterTile.getsurf` and `BottomSurface`, `Gob.DefaultPlace`/`PlanePlace` and
      `Drawable.placer`'s `place` prop, `MapView.getcc` and the free camera's `Sessions.groundz`,
      `Tileset.Flavor.Terrain` — and the gotchas: the read is on the `Loading` path so it reaches the
      grid first; objects move to the plane a tick before their cut re-meshes; the record must never
      read the drawn height; the drawn surface is the mesh's `ZSurface`, which reads `getfz` at call
      time. Leave the ridge section to 161.2. Add the page's row to `docs/client/README.md`, correct
      `docs/client/state.md`'s *Map / terrain* row (`getfz` is the drawn read, `getrealfz` the
      streamed one, linking the new page), add `perf-flatterrain` to the Performance row of
      `docs/client/prefs-and-options.md`, and a *See also* line in `docs/client/ground-detail.md`.
      *Its suite* declares `client.settings`. It takes `performance =
      hafen.client():options():performance()` and `session = hafen.session():current()`, remembers
      `flatTerrain()`, and checks, one line each: the read is a boolean (`type(performance:flatTerrain())
      == "boolean"`); the round trip and the chain (`performance:flatTerrain(true)` reads `true`,
      `performance:flatTerrain(true):flatTerrain(false)` returns the handle by identity, then reads
      `false`); the refusals (`flatTerrain("no")` and `flatTerrain(0)` raise containing
      `performance:flatTerrain: on must be true or false`, `flatTerrain(nil)` raising containing
      `must not be nil`); the vocabulary (`performance:foo()` raises naming `flatTerrain`); the real
      height (the character's position `local position = session:player():gob():position()`, `local
      before = session:world():height(position)`, write `flatTerrain(true)`, wait one step
      (`hafen.timer():after(0.2, …)`), `session:world():height(position) == before`, `[pass] the API
      height is the streamed one (N)` — with a `[manual]` line first saying the run wants relief
      under the character, so that `before` is not zero by chance); the record (`hafen.map`'s grid
      under the character, `grid:height(cell)` for that cell before and after, equal); the restore
      (write the remembered value back, read equal); and the picture's number (the spec's seventh
      criterion): `render().drawSlots` sampled before any write — the median of ten reads 0.1 s
      apart — against the same sample taken 4 s after the restore, within a tenth, `[pass] the
      relief draws what it drew`. It prints the value read at load first.
      `[manual]`: at `flatTerrain(true)` the hills flatten cut by cut within a few seconds; the
      character, every object and every flavor object stand on the plane; the camera looks over
      what a ridge hid; walking and clicking land under the cursor; a lake keeps its depth, wading
      is fogged below the surface; a claim or a patch overlay lies on the plane; remembered ground
      is flat where the RTS camera looks past the stream, and in RTS mode another character's ground
      merged into the scene is flat too; the minimap and the map window draw their cliff lines as
      before; at `flatTerrain(false)` the relief comes back the same way; `:reload` and `:t161`
      again — expect: `flatTerrain at load:` reports the value that was persisted. Cliffs are
      expected to show as nothing yet.
      <!-- extra context: the comment above MCache.getgridt (the Loading and map-request protocol getfz is part of); specs/160-performance/addons/160-performance.1/main.lua for the refusal sentences; docs/addons/api/map/grids.md for grid:height(cell) and how to reach the grid under a position -->

- [x] **161.2 — Cliffs as standing walls.** On a flat world every cliff keeps its shape and its
      height, standing on the plane, seen from both sides, with plain ground on either side. (The
      maintainer's ruling at the first run, 2026-09-21: the one-tile wall first built read as a fence
      of boards and lost the cliff's line — the cliff keeps its form and height.)
      *The real read.* `Ridges`: a private `double hz(Coord gc) { return(m.map.getrealfz(gc)); }`
      and every `m.map.getfz(` in the class replaced by `hz(` — in `breaks()`, `edgelc`,
      `makeedge`, `tczs` and `isdiag2`; not `brokenp`, whose `MapSource` is the record. With the
      switch off `getrealfz` equals `getfz`, so nothing changes; with it on, cliffs are detected
      where they are while the ground is the plane. `// addon: 161.2`.
      *The wall.* `private final boolean flat = Performance.flatTerrain`, read once in the
      constructor so one cut is modelled one way. Flat, upstream's ridge model runs whole, on the
      plane: `makeedge` puts a column's base at `0` instead of `lo` (jitter, bend and segments
      untouched, the height the real drop); `tczs` answers the complex tile's corners relative to
      their lowest, so its centre column stands on the plane too; the five shapes skip their split
      ground part (`gnd` stays `null`) and `model` answers `!flat`, so `RidgeTile.model` lays plain
      ground and `laygnd` falls through; every ridge part goes through `wall(tc, l, r)` — `connect`'s
      part plus, flat, `connect(tc, back(r), back(l))`, the same columns handed the other way round
      from `back(column)` copies (one per column, an `IdentityHashMap` `backs` dropped by `clean`),
      because `MapView` culls back faces and the plane has no low side for the wall to face. Copies,
      never the front column's own vertices: `MapSurface` averages a vertex's normal over its faces.
      `connect` fills `rcx`, `rcy`, `rn`, `rh`, `ledge` and `uedge` — everything `TexCons.faces` and
      `RPart.mapridges` read — so the wall wears the tileset's cliff texture, tiled by its height.
      *The lip.* The cliff's top lip is a served flavor (`gfx/tiles/flavor/ridge-edge`) built in
      the cut's flavor pass from `Ridges.getrdesc`'s `uedge` rows: the back part's `ledge`/`uedge`
      are emptied so it is drawn once; `Performance.flatTerrain` bumps `flavorGeneration` too; and
      `Cut.fo`'s build calls a new `MCache.Deferred.current()` on the cut's mesh first — `get()`
      that throws the pending rebuild's `NotDoneException` — so the pass reschedules behind the mesh
      it reads instead of the one about to be replaced (found at the second run: the lip floated at
      the old ridge's height, one offset per cut). `// addon: 161.2`.
      *Docs.* `docs/client/terrain-height.md`: the **ridge model** section — `Ridges.RidgeTile`
      and `breakz`, `Ridges.breaks` (an edge is broken when the difference exceeds the threshold of
      both tiles beside it, so a broken edge is always between two ridge tiles), `eo` and which two
      edges a tile owns, `model`'s five shapes (`modelcap`, `modelstraight`, `modeldiag1`,
      `modeldiag2`, `modelcomplex`) and the `edges`/`edgec` columns `makeedge` builds, `connect`
      and the `RPart` fields `TexCons` reads, `laygnd`/`layridge` and the two `RidgeTile.lay`
      overloads, `clean` and `edgeoff`; fork: `hz` and the flat walls — and the gotchas (copies
      not shared vertices; a wall on a cut border is the owning cut's, from heights that may sit in
      the next grid, the `Loading` the model already risks). Keep the page under 150 lines.
      *Its suite* declares `client.settings`. It remembers `flatTerrain()`, writes `true`, and
      samples `hafen.client():profiling():render().drawSlots` (the median of ten reads 0.1 s apart)
      after a 4 s wait for the cuts to rebuild; then `false`, the same wait and sample; checks the
      two samples are within a fifth of each other and neither is zero, `[pass] the flat world draws
      (N flat, M relief)`; checks no `[fail]` came from a refusal on either write; restores. The
      walls themselves are a picture, so:
      `[manual]`: standing at a cliff line at `flatTerrain(true)` — expect: the cliff with its own
      shape and height standing on the plane, in the tileset's cliff texture, flat ground on both
      sides, no ramp; seen from above and, after walking round, from behind; a cliff corner, a cliff end and a diagonal cliff each
      walled with no gap and no doubled wall; a cliff on a cut border walled once; nothing walled
      on ground with no cliff; caves as before; at `flatTerrain(false)` the real cliffs return; no
      `ridge crash` warning on stderr while walking a cliff line either way.
      <!-- extra context: src/haven/Tiler.java MPart (the vararg constructor needs one part at least; mapvertices is the merge hook RPart overrides); src/haven/Surface.java Vertex(float, float, float) and Vertex(Coord3f) -->

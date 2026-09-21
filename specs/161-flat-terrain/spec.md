# 161 — Flat terrain

## What & why

The world has relief: hills, valleys, cliffs. The client draws it, and everything standing on it,
at the height the server streams for every tile corner. Relief hides things — a character behind a
ridge, a boar in a hollow, the far side of a hill — and the camera, bolted to the character, cannot
look over it. **Flat terrain** draws the whole world at one height: every tile corner at zero, every
object standing on that plane, water keeping its depth below it, and every cliff standing on that
plane with its own shape and height so that what cannot be walked across is still seen. Nothing the server knows
changes and nothing a click reaches changes: the terrain is streamed, recorded and answered to
addons at its real height; only the picture is flat.

It is a switch on the **Performance** panel (spec 160), section *Ground*, and a verb on
`hafen.client():options():performance()`: the same store, the same panel, the same grammar and the
same `client.settings` permission as its twelve siblings. It is not a performance setting — the
same triangles are drawn at another height — and the panel says so in its tooltip; it lives there
because that panel is where the client's own picture of the world is dialled, and because it
applies through the same lazy mesh rebuild `groundBlend` and `transitions` already ride.

## The surface

| Verb | Type | Values | Default | What it decides |
|---|---|---|---|---|
| `flatTerrain()` / `flatTerrain(flag)` | boolean | `true` / `false` | `false` | Whether the terrain is drawn flat: every tile corner at height zero, objects on that plane, cliffs as standing walls, water keeping its depth. |

Panel: section **Ground**, box *Flat terrain*, after *Tile transitions*. Preference `perf-flatterrain`.
The grammar is the panel's: a colon call, no argument reads, one argument writes and hands the
handle back; a non-boolean raises naming a switch; an explicit `nil` raises; the verb is part of the
handle's closed vocabulary, so `performance:foo()` names it among the thirteen. A write needs
`client.settings`; a read needs nothing; the handle answers before the world is up.

## How it takes effect

**One seam.** Every height the client *draws* goes through the map cache's tile-corner read: the
ground mesh's vertices, the height surfaces the tilers hand out, the interpolated point reads, and
through those the placers every object stands on, the camera's centre, the ground decals, the
water's fog line, the remembered ground and another session's ground merged into the scene. With
the switch on, that read answers zero — after reaching the grid exactly as before, so the map
request and `Loading` protocol are untouched. Everything drawn agrees by construction, because it
was already reading one number.

**What is not flattened**, each for a reason:

| Read | Stays real | Why |
|---|---|---|
| The grid's own height array and the grid-level read | yes | The map record is written from it, and the record is the world as streamed. |
| `session:world():height(position)` | yes | The API answers what the server says, not what the client draws; it moves to the real read. |
| Cliff detection | yes | Where a cliff is comes from the real heights; it is drawn as a wall instead of a slope. |
| The recorded map's `grid:height(cell)` | yes | Reads the record. |

**Cliffs.** A tile edge whose real height difference exceeds the tileset's cliff threshold is a
cliff. Flat, the ridge tile is laid as plain ground and the cliff is the game's own ridge — its
columns, its shapes and its real height — standing on the plane, textured with the tileset's own
cliff texture and visible from both sides.
No ramps: the ground on either side is the plane. Cave walls need nothing — they stand on the floor
at a fixed height already.

**Water.** The water surface is the plane; the bottom is the plane minus the tile's depth, so lakes
and rivers keep their depth and the fog on what stands in them. Flow shading, which comes from the
slope of the banks, goes still.

**A change shows** the way `groundBlend` does, and **on the ground already on screen first**: the
write bumps the ground generation; the terrain raster asks the cache for every cut in its area on
every tick, so the very next tick every cut being drawn compares its stamp, finds it moved and
schedules its rebuild — the cuts in view, the remembered ground and another session's ground alike.
Nothing waits for new ground to load. The old mesh stays on screen until its replacement is built,
which is seconds for the view. Objects, the camera and the ground decals move to the plane on the next tick
— for the seconds a cut takes to rebuild, an object may stand on the plane while its hill is still
drawn; that resolves cut by cut. Turning the switch off restores the relief the same way.

Two things never change: **what the server knows** (positions, heights, what is walkable) and
**what a click reaches** (the click pass is rendered from the same flat mesh, so the cursor lands on
the tile under it). The recorded map keeps its real heights, and the minimap draws cliffs from them.

## Acceptance criteria

Each is verifiable in-game through the task's own suite, `[manual]` where a program cannot observe.

1. **The verb.** `performance:flatTerrain()` reads `false` on a fresh store; `flatTerrain(true)` reads
   back `true` and chains (`performance:flatTerrain(true):flatTerrain(false)` returns the handle);
   `flatTerrain("no")` and `flatTerrain(0)` raise naming a switch; `flatTerrain(nil)` raises;
   `performance:foo()` raises naming `flatTerrain` among the handle's verbs; the written value
   survives `:reload`.
2. **The API keeps the real height.** With the character standing where the terrain has relief,
   `session:world():height(position)` at the character's position reads the same non-zero number
   before and after `flatTerrain(true)`, and `hafen.map`'s `grid:height(cell)` for that cell reads
   the same before and after.
3. **The picture flattens live.** `[manual]`: at `flatTerrain(true)`, within a few seconds and cut
   by cut, hills and valleys become one plane; the character, every object and every flavor object
   stand on it; the camera looks over what a ridge hid; walking and clicking land where the cursor
   is; a placed patch or claim overlay lies on the plane. At `flatTerrain(false)` the relief comes
   back the same way, with no relogin either way.
4. **Cliffs stand as walls.** `[manual]`: every cliff keeps its shape and its height standing on the
   plane, drawn in the tileset's cliff texture, seen from above and from behind, with flat ground on
   both sides and no ramp; a cliff corner, a cliff end and a diagonal cliff are all walled; nothing is walled where
   there is no cliff. `render.drawSlots` does not fall to zero and no warning is issued while
   walking a cliff line flat.
5. **Water keeps its depth.** `[manual]`: a lake flat is a lake — its bottom below the plane, the
   character wading fogged below the surface, a boat at the surface; the sea's edge and a river
   through a valley keep their depth.
6. **Caves, remembered ground, other sessions.** `[manual]`: cave floors flat with their walls
   standing; remembered ground drawn flat where the camera looks past the stream; in RTS mode
   another character's ground merged into the scene is flat too.
7. **The defaults draw the upstream picture.** With the switch off the scene is unchanged:
   `render.drawSlots` before the suite's writes equals the reading after the switch has been written
   back to `false` and the cuts have rebuilt, within the frame-to-frame jitter; `[manual]`: the
   relief and the cliffs are as they were.
8. **Nothing breaks.** `ant hafen-client` builds from a clean `build/classes`; `python
   tools/docverbs.py` and `python tools/refusalverbs.py` exit `0`; the minimap and the map window
   draw cliffs as before whatever the switch.
9. **The ground already on screen is what changes.** Standing still, with no new grid arriving,
   `flatTerrain(true)` re-meshes the cuts in view: `[manual]` the hills in view flatten within a
   few seconds without moving the character; and a number — with a cliff in view, `render.drawSlots`
   falls once those cuts have rebuilt, because their ridge geometry is no longer built (before the
   walls of the second task) or is fewer parts than the slopes were (after), and rises again at
   `flatTerrain(false)`, the character never having moved.

## Out of scope — the boundary

- **A wall height setting, or hiding cliffs altogether.** The real drop is the wall (ruled at
  161.2's first run, after a one-tile wall read as a fence); a dial over it is a line of a later
  feature if ever wanted, and a cliff that is not drawn is a cliff walked into.
- **Flattening the far ground only, or by distance.** One plane or the relief; a blend of the two is
  a different picture with its own seams.
- **The minimap and the map window.** Two-dimensional already; they draw cliff lines from the record
  and stay as they are.
- **A read of the drawn height** for addons. `session:world():height` is the streamed height by
  contract; nothing in the API reports where the client drew a point vertically, and this feature
  adds no such read.

## Docs impact

**Pages written or extended**

- `docs/addons/api/client/README.md` — the `flatTerrain` row in the `performance()` table, a rule
  (what stays real, walls, water, live), the section's first sentence (*and whether its relief is
  drawn*), the *Handle | Covers* row.
- `docs/addons/api/world.md` — a rule on `:height`: the streamed height, whatever the Performance
  panel's flat terrain draws.
- `docs/client/terrain-height.md` — new engine map: where a height comes from and who reads it
  (`MCache.Grid.z`, `Grid.getz`/`getfz`, `MCache.getfz` as the drawn read and the real read beside
  it, `getcz`/`getzp`, `MCache.ZSurface`, `Tiler.MapZSurface`, `MapMesh.getsurf` and
  `WaterTile.BottomSurface`, `Gob.DefaultPlace`/`PlanePlace`, `MapView.getcc`, `Sessions.groundz`,
  `MapFile`'s record), the ridge model (`Ridges.breaks`, `RidgeTile.breakz`, `Ridges.model` and the
  five shapes it builds, `connect`, `TexCons`, `laygnd`/`layridge`; fork: the standing walls) and
  the gotchas met. None of this has a page today.
- `docs/client/README.md` — its index row.
- `docs/client/state.md` — the *Map / terrain* row corrected: `getfz` is the drawn read, the real
  read is named beside it.
- `docs/client/prefs-and-options.md` — the Performance row names `perf-flatterrain`.
- `docs/client/ground-detail.md` — a *See also* line to the new page.

**Derived impact set** — `grep -rn` over the whole of `docs/` for the prose names of this surface:

```text
grep -rn "getfz\|getcz\|getzp" docs/
  docs/client/state.md:18        "each getfz(Coord) off the Grid" — now the drawn read; corrected in place
  docs/client/mapfile.md         the recorded grid's own getfz — the record, unchanged
  docs/client/world-3d.md:20-21  Gob.getc / the placers — unchanged, they read the drawn surface by design
grep -rn -i "height" docs/addons/api/world.md docs/addons/api/map/*.md
  docs/addons/api/world.md:93    ":height(position) — Terrain height there" — the rule added says which height
  docs/addons/api/map/grids.md:65 "grid:height(cell) — the recorded height" — the record, unchanged
grep -rn -i "cliff\|ridge" docs/
  docs/client/minimap.md         cliff lines on the minimap, from the record — unchanged
grep -rn "twelve" docs/addons/api/client/README.md
  every "twelve" that counts the panel's verbs becomes thirteen, or is reworded to count nothing
```

## Context files

Tagged with the tasks that need them; an untagged line is read by every task.

- `DOCUMENTATION.md`
- `specs/160-performance/addons/160-performance.1/main.lua` — the suite skeleton for a panel verb, and the `refuses` sentences
- `docs/addons/api/client/README.md` — 1
- `docs/addons/api/world.md` — 1
- `docs/addons/api/map/grids.md` — 1
- `docs/client/ground-detail.md` — 1, 2
- `docs/client/terrain-height.md` — 2 (written by 161.1; 161.2 adds its ridge-model section)
- `docs/client/state.md` — 1
- `docs/client/prefs-and-options.md` — 1
- `docs/client/terrain-raster.md` — 1
- `docs/client/README.md` — 1
- `src/io/brodgar/perf/Performance.java` — 1
- `src/io/brodgar/ui/PerformancePanel.java` — 1
- `src/io/brodgar/addon/PerformanceOptions.java` — 1
- `src/io/brodgar/addon/WorldApi.java` — 1
- `src/haven/MCache.java` — 1, 2
- `src/haven/Tileset.java` — 1
- `src/haven/Tiler.java` — 1
- `src/haven/MapMesh.java` — 1, 2
- `src/haven/Gob.java` — 1
- `src/haven/MapFile.java` — 1
- `src/haven/resutil/WaterTile.java` — 1
- `src/haven/resutil/Ridges.java` — 2
- `src/haven/resutil/TerrainTile.java` — 2
- `src/haven/MapSource.java` — 2

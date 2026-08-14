# 068 — Remembered ground

## What and why

The RTS camera pans off the character; the terrain does not follow it. `MapView.MapRaster.tick`
centres the drawn area on `getcc()` — the player's own cut — so a camera panned away arrives at a
void. The client is not missing the data: everything the character has explored is on disk in
`MapFile`, at full tile resolution, with the same `int[] tiles` and `float[] zmap` a live
`MCache.Grid` carries.

This feature draws that recorded ground, in a white-grey wash, wherever the camera looks and the
live map has none: fog of war in the literal sense — you see the shape of what you remember, and
nothing of what is there now.

It reads the map database and never the wire. Asking the server for a grid the character is nowhere
near is exactly what `MCache.getgrid` does on a miss (`request` → `MSG_MAPREQ`, five tries and then
permanent `Loading`), and it is the one thing this must not do — which is why the remembered ground
gets a map cache of its own rather than filling the live one.

The scene half already exists in the fork: `MapView.FleetTerrain` rasterizes ground out of a second
`MCache` and yields every cut the live raster draws. This is that pattern, filled from disk.

**This feature ships no addon.** It adds no Lua surface, so there is nothing a suite could assert
through; `/implement` builds no `addons/068-*` folder and ends each task by handing the maintainer
the by-hand checks that task's entry in `tasks.md` names. The override is the maintainer's, recorded
here because these three files are all `/implement` reads.

## Acceptance criteria

Each is checked in-game by the maintainer, by hand.

1. With `:cam rts` installed and the view panned onto ground the character has walked but that the
   server is no longer streaming, that ground is drawn — its relief, and enough of its tiles to read
   the shape of the place — with no objects, no players and no grass on it.
2. It is drawn in a flat **white-grey wash**, not in the colours live ground wears, so what is
   remembered is told apart from what is seen at a glance and across the seam between the two.
3. **Nothing goes to the server for it.** The live `MCache`'s request set is unchanged by panning,
   and the remembered source never sends: a console command reports its counters (grids read from
   disk, cuts built, requests sent) and the third is always `0`.
4. Where live and remembered ground meet, exactly one is drawn — the live one — with no z-fighting;
   the seam between the two shows no gap and no step in height.
5. The remembered ground follows the camera and is bounded by what the camera can see: cuts outside
   the frustum are not built, and the number of live cuts is what it was.
6. Panning far and back does not grow without bound: the source holds a stated budget of grids and
   cuts, and what leaves the view is released.
7. The command turns it off and on, and with it off — or with any camera but `rts` — the scene is
   exactly what it is today.
8. Ground that cannot be placed is not drawn rather than drawn wrong: inside a cave or a house (a
   different segment), and while `sessloc` is stale after a re-base, nothing appears in the wrong
   place.

## Out of scope

- **Objects of any kind** — gobs, players, flavour objects. A remembered tree is a tree that was
  felled an hour ago, and the bare ground is the fog-of-war cue.
- **Clicking remembered ground.** The pick pass stays live-only: an order needs a session that can
  see the ground it lands on, and the record is not one.
- **`hafen.world()` reading from the record.** Its terrain verbs stay the live half.
- **Standing entities over remembered ground.** `MapView.grounddrawn` stays live-only, because the
  placement a standing entity needs takes its height from `glob.map`, which has none there.
- **The recorded overlay masks** (claims, provinces) and **the map's markers**: the record holds
  both, and both are their own read.
- **Zoom-grid LOD** for far ground.

## Docs impact

Pages written: `docs/client/world-3d.md` (the drawn-ground row gains the second source and the
camera-centred area), `docs/client/mapfile.md` (the recorded grid read back into a live-shaped one).

Derived impact set — `grep -rniE "(terrain|ground).*(is drawn|not drawn|streamed|nil when)" docs/
--include=*.md -n`, whose hits outside the two pages above are:

- `docs/addons/api/vr/README.md:137` and `:168`, `docs/addons/api/vr/widgets.md:206` — the ground
  rule for a standing entity. Revised only if a second raster stops the wording being exact; the
  scope above keeps `grounddrawn` live-only so that it stays exact.
- `docs/addons/api/world.md:161` — terrain reads answering `nil`. Gains one clause: ground you can
  see is not proof the live map has it.
- `docs/addons/api/map/README.md:15`, `docs/addons/api/world.md:106`/`:145` — the recorded/live
  split, untouched here and discharged with that reason.

## Context files

- `src/io/brodgar/rts/Recall.java` — 2, 3, 4
- `src/haven/MCache.java` — 1, 2, 3
- `src/haven/MapFile.java` — 1, 3
- `src/haven/MiniMap.java` — 1, 3
- `src/haven/AddonWidgets.java` — 1
- `src/haven/MapMesh.java` — 1, 2
- `src/haven/MapView.java` — 2, 3
- `docs/client/mapfile.md` — 1, 3
- `docs/client/minimap.md` — 1, 3
- `docs/client/state.md` — 1, 2
- `docs/client/world-3d.md` — 2, 3
- `docs/client/boot-and-loop.md` — 2, 4
- `docs/addons/api/vr/README.md` — 2
- `docs/addons/api/vr/widgets.md` — 2
- `docs/addons/api/world.md` — 2
- `DOCUMENTATION.md` — 1, 2, 3

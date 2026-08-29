# 120 — The ground is there when you look

## What and why

068 ships the remembered ground and it takes about seven seconds to arrive. Neither the disk nor the
CPU is why. A cut's mesh is a few milliseconds on one of `cpus - 1` `Defer` threads, and the 160 it
draws are a fraction of a second's work over that pool. Three constants and one disposal make it
seven:

- `Recall.maxread` is 8 per 0.25 s sweep, and a grid still `Loading` re-consumes a slot on the next
  sweep — so the cap is eight *in flight*, and merely **asking** for the 7×7 square takes 1.75 s.
- `MapView.recallmaxbuild` is 6, counted the same way (a cut still building is not in `Grid.cuts`),
  re-evaluated every 0.2 s. That is 30 cuts a second, so the 160-cut cap takes **5.4 s**.
- `Recall.tick` trims to the radius square **every ctick**, so panning one grid disposes seven grids
  and panning back rebuilds all 112 of their meshes.

This feature makes the arrival the work's own speed, keeps what it built, and hands the user the
dials that are console arguments and constants today: Options ▸ Game ▸ Client gains a
**Remembered ground** section — on/off, a range in grids, and the grey wash — mirrored on
`hafen.client():options():client()`.

The reach stays full detail and bounded. `RTSCam` has no zoom limit (`FreeCam.dist` has a floor of
5 and no ceiling, and `setproj` moves the far plane out with it), so a camera pulled far enough
frames more ground than any client draws at tile resolution. The slider is the user's **maximum**;
the frustum and a stated mesh budget bound the set, nearest to where the camera looks first.

## Acceptance criteria

1. Panning the RTS camera onto recorded ground the server is not streaming draws it **within a
   second** at the shipped range: cuts drawn reach cuts wanted, and no ground is seen growing in.
2. Panning away and back **rebuilds nothing** still in range — the second visit is immediate.
3. Options ▸ Game ▸ Client carries a **Remembered ground** section: a checkbox for the system, a
   slider for the range in grids, a checkbox for the wash — each live, each persisted.
4. `opts:client()` carries `recall()`, `recallRange()` and `recallGrey()`. Arity is the verb, a write
   is gated by `client.settings`, an explicit `nil` is refused, and a range outside its bounds is
   refused naming them. Each reads back what the panel shows, and a write moves an open panel.
5. The settings are the **client's**, not one view's: with two sessions up, a write moves both.
6. What is held and what is drawn are bounded, stated, and readable from outside **with profiling
   disarmed** — grids held, grids read, cuts drawn, cuts wanted, through `:recall` and
   `hafen.client():profiling():render()` — and neither budget is exceeded under a long pan.
7. Nothing is drawn from a disposed mesh: a cut leaves the scene before its mesh is disposed, and a
   grid whose cuts the raster still holds is never trimmed.
8. Nothing goes to the server for it: `:recall`'s request count stays `0` and the live cache's queue
   is unchanged by panning.
9. Everything 068 got right still holds — no objects on it, no step and no z-fighting at the seam
   with live ground, and a re-base drops it rather than drawing it where it never was.

## Out of scope

- **LOD from `ZoomGrid`.** Far ground stays at tile resolution, so a large range buys reach and not
  cheapness. This is the boundary: past a stated mesh budget the farthest ground is not drawn.
- **Overlays, markers and objects** on remembered ground, **clicking it**, reading it through
  `hafen.world()`, and **standing entities** over it — 068's boundaries, unchanged.
- **The `MapFile.update` prio defect** (ROADMAP, filed 068): it flattens the *minimap's* tile-border
  pass, not the 3D mesh this draws.
- **`MCache.trim`'s dispose-while-held defect in general** (ROADMAP, filed 068). This feature keeps
  the recalled source out of it by never trimming a grid the raster holds; the live path is untouched.

## Docs impact

Pages written: `docs/client/terrain-raster.md` (both rows and the trim gotcha — the budgets, the
schedule, the settings), `docs/client/mapfile.md` (the read-back row's new bounding),
`docs/addons/api/client/README.md` (three rows and a section in `client()`),
`docs/addons/api/client/profiling/counters.md` (four rows in `render()`'s table, which stays under
its ceiling).

Derived impact set — `grep -rniE "remembered ground|:recall|recall|greyscale|wash" docs/
--include=*.md -n`, whose hits outside the pages above are:

- `docs/addons/api/world.md:99` — terrain reads answer `nil` over remembered ground. Still exactly
  true; nothing here gives that record a live-map read. Discharged with that reason.
- `docs/addons/api/ui/style/chat.md:86`/`:95`, `docs/addons/api/ui/style/keys.md:45`/`:65`/`:192` —
  `wash` is the chat's own word for a channel's tint. Unrelated, discharged.
- `docs/client/services.md` — its settings table is the two-stores map and is **at** its 150-line
  ceiling; 019's `profiling` pref is not in it either, so a subsystem's prefs are stated on its own
  page. Discharged, nothing written there.

## Context files

- `src/io/brodgar/session/Recall.java` — 1, 2, 4
- `src/haven/MapView.java` — 1, 2, 3, 4
- `src/haven/MCache.java` — 2, 4
- `src/haven/AddonWidgets.java` — 2, 3, 4
- `src/haven/MapFile.java` — 2
- `src/haven/Defer.java` — 3
- `src/io/brodgar/ui/ClientPanel.java` — 1
- `src/io/brodgar/addon/ClientOptions.java` — 1
- `src/io/brodgar/addon/ProfHandle.java` — 1
- `src/io/brodgar/addon/InterfaceOptions.java` — 1
- `src/haven/OptWnd.java` — 1
- `docs/client/terrain-raster.md` — 1, 2, 3, 4, 5
- `docs/client/mapfile.md` — 2, 5
- `docs/client/camera.md` — 3
- `docs/client/ui-panels.md` — 1
- `docs/client/ui-controls.md` — 1
- `docs/addons/api/client/README.md` — 1, 5
- `docs/addons/api/client/profiling/counters.md` — 1, 5
- `docs/addons/api/conventions.md` — 1
- `docs/addons/api/timer.md` — 2, 3, 4, 5
- `DOCUMENTATION.md` — 5

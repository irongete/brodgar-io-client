# 068 — Remembered ground — tasks

> **No suite, by the maintainer's decision.** This feature adds no Lua surface, so nothing an addon
> could assert through. **No task builds an `addons/068-*` folder and none is copied to
> `bin/addons/`.** Each task ends by printing its `[manual]` lines to the maintainer as a numbered
> list, they run them in-game and paste back what they saw — the same round trip a suite's output
> gets, written by hand. A task is done when every one of its lines is answered.

- [x] **068.1 — A recorded grid is read back as a live-shaped one.** Adds
      `io.brodgar.rts.Recall`: an `MCache` of its own on the live `Session`, nothing ticking it and
      nothing calling `sendreqs()`; the session→segment bridge off `MiniMap.sessloc`; the
      `Segment.gridid` + `Segment.grid` read on a `Defer` thread under a `tryLock`; the per-source
      tile remap; and the two seams — `MCache.settileset` and `AddonWidgets.putgrid`. Adds `:recall`
      to `MapView.cmdmap`: bare prints grids read, cuts live and requests sent. Nothing is drawn yet.
      Writes the read-back row in `docs/client/mapfile.md`.
      *No suite.* The command is the oracle: it reads back what was filled, and the request count is
      the criterion that matters most stated as a number rather than as a claim.
      `[manual]`: stand anywhere outdoors, run `:recall` — expect a grid count matching the ground
      you have walked, and **requests sent: 0**.
      `[manual]`: walk into a cave or a house, run `:recall` — expect it to report a different
      segment and no grids, rather than grids from the segment you left.

- [x] **068.2 — The camera looks at remembered ground and finds it.** Adds
      `MapView.RecallTerrain extends MapRaster` over the recalled cache: `area` centred on
      `RTSCam.center()`, `skipcut` yielding every cut the live `Terrain` holds and everything
      `cutvisible` rejects, `ShadowMap.maskshadow` on its slot, a one-grid fill margin so `dotrans`
      has its neighbours. Installed in `basic` with the RTS camera and removed with it; `:recall
      off`/`on` toggles it. Ground appears in its own colours — the wash is the next task. Writes
      the drawn-ground row in `docs/client/world-3d.md`.
      *No suite.* What proves it is the overlap and the seam, not the appearance: two rasters over
      one piece of ground is z-fighting, and that is visible at a glance.
      `[manual]`: `:cam rts`, pan well off the character — expect ground where there was void, with
      no objects, no players and no grass on it.
      `[manual]`: pan back until the two meet — expect no shimmer where they overlap and no step or
      gap in the ground at the join.
      `[manual]`: `:recall off`, then `:cam ortho` — expect the scene exactly as it is today, both
      times.

- [x] **068.3 — Remembered ground is washed grey.** Adds the wash: `io.brodgar.rts.Greyscale`, a
      `State` whose `ShaderMacro` mods `FragColor.fragcol` at order 1000, mixing the fragment's
      Rec. 709 luma back over its own rgb by a `Uniform` amount. Installed on `RecallTerrain`'s slot,
      where the composed `Pipe` compiles it into every tileset material below it. `:recall wash <a>`
      pushes a new amount through `Slot.ostate`, recompiling nothing. Writes both ways of recolouring
      ground — the sheet and the shader state — into `docs/client/world-3d.md`'s ground-overlay
      section.
      *No suite.* The amount is a judgement, so the task ships the dial rather than a number, and the
      maintainer's answer to the second line is what fixes the default.
      `[manual]`: pan onto remembered ground — expect it drawn without colour, told apart from live
      ground at a glance, with the tile still readable (water, ploughed field, road).
      `[manual]`: try `:recall wash` at a few values and say which one to ship as the default.

- [ ] **068.4 — It stays bounded, and it survives the ground moving.** Adds the cut and grid budget
      with a stated cap and a per-tick limit on new builds, nearest to the camera centre first;
      release of what leaves the view; and the re-base handling — the offset re-derived each tick
      from `sessloc`, every recalled grid dropped when `sessloc.seg` or `tc` moves (`MCache.trimall`
      via `invalblob` type 2), never an offset cached across a segment change. `:recall` reports the
      cap beside the live counts. Discharges the impact set: the terrain clause in
      `docs/addons/api/world.md`, and the ground rule in `docs/addons/api/vr/README.md` and
      `vr/widgets.md`.
      *No suite.* Growth is the failure that only shows over minutes, so what is checked is the
      counter under a long pan rather than a single frame.
      `[manual]`: pan far in one direction for a while, then back — expect the cut count to sit at
      or under the cap throughout, and to come down again as ground leaves the view.
      `[manual]`: walk into a house and out again with the camera panned away — expect the
      remembered ground to disappear and come back in the right place, never to draw at the wrong
      one.

# 132 — tasks

> **Every suite here arms profiling and reads its own cost back.** `hafen.client():profiling()` answers
> `gl().drawCalls` and `memory().allocPerFrame`, so "this costs nothing per frame" is an assertion rather
> than a claim: bracket a bounded stretch of frames with the thing shown and with it absent, and compare.
> The verdict is a difference against the counter's own noise, never an absolute — the number belongs to
> the machine it ran on. Arm in `setup`, disarm at the end, and leave the switch as it was found.
>
> **`allocPerFrame` is only advanced while the client is drawing its own `Mem:` line** (`UILoop.statlines`),
> so it is absent until that has happened once and frozen whenever it is not happening — and a frozen
> counter compares *flat* for the wrong reason. Take it only when it MOVED across the window, and otherwise
> read the same quantity out of `heapUsed` frame by frame with the negative steps dropped, naming on the
> line which of the two the run used. 132.1's suite carries both and is the shape to copy.
>
> **A patch's own cost is one draw call per terrain cut its mask reaches**, because the engine lays a second
> mesh over each and every one of those is a render slot. That is a constant of the ground: it does not grow
> with the ring's points, which is the thing a painter cannot do. So a shown-against-absent bracket is never
> flat, and the flat comparison is the one where only the thing under test differs.

- [x] **132.1 — a patch the world cannot hide.**
      `patch:occluded(b)` joins the patch handle in `VirtualApi`, and threads into `PatchOverlay.set` as
      the fifth thing that decides the material: `States.Depthtest.none` present or absent in the
      `Material(Pipe.Op...)` list that method already builds, beside the `States.maskdepth` that stays
      either way — the first is `glDisable(GL_DEPTH_TEST)`, the second `glDepthMask(false)`, and reading
      them as one state is the mistake this task turns on. A bare adjective read and written by arity, like
      `visible` and `drawn` beside it, defaulting to `true`, so a patch that never names it draws as it
      does today. Because the flag never moves the tiles, `set` returns the answer it returns now and the
      material re-pushes down the path a colour change takes: nothing is re-carved. `patches.md` gains the
      verb and the three things it does **not** change — the HUD still covers a patch, the shape still
      follows the slope, and the mask exists only where a cut is built — and records that ghosts, sprites
      and world panels are unchanged.
      *Its suite* lays a ring, reads `p:occluded()` back as `true`, sets `false`, reads it back, and
      asserts the tint, the border, the offset and `drawn()` all survive the flip — the pair that fails if
      the flag re-carved rather than re-pushed. It brackets a bounded stretch of frames with `drawCalls`
      and `allocPerFrame` — the ring shown the ordinary way against the same ring shown through the world —
      and asserts both are flat, because the two differ by one op and by nothing else. What a patch costs
      the ground is measured beside it, shown against absent, and bounded by the cuts its mask can reach:
      one draw call each, which is what it buys instead of one per edge. It `pcall`s
      `p:occluded(1)` and asserts the refusal names the verb and says a boolean is wanted.
      `[manual]`: stand with a wall between you and a patched object, `occluded(false)` set — expect: the
      ring is drawn whole, through the wall.

- [x] **132.2 — a patch's filter and tags allocate nothing.**
      `filter(Area b)` answers `!b.isects(tiles)` — the pure comparison `Area.overlap` calls first —
      instead of `b.overlap(tiles) == null`, which builds two `Coord` and an `Area` only to throw it away
      for its nullness. `tags()` becomes a `static final` single-entry list instead of a fresh
      `Arrays.asList("show")` per call. Both sit under `MCache.olreaches`, which `world-3d.md` says puts a
      filter to one cut's tiles *"exactly and with no allocation"*, and which runs per cut, per overlay,
      per frame under `MapView.oltick`.
      *Its suite* lays several patches at known places and asserts each is `drawn()` where it should be and
      not where it should not: the filter decides which cuts are asked for at all, so a sense reversed
      shows up as a ring that never appears, or one that claims the whole grid. It then brackets
      `allocPerFrame` over a bounded stretch with N patches laid and with none, and asserts the difference
      does not grow with N — the assertion that fails if `filter` still builds an `Area` per cut, per
      patch, per frame.

- [x] **132.3 — a patch following an object standing still pays nothing.**
      `followPatch` reads `Gob.rc` — the server's own point, a field — before `t.getc()`, and calls the
      interpolated read only when that field differs from what the patch was laid at. `getc()` stays the
      point that is **used**, so a moving object is laid exactly where it is laid today; the cheap read
      gates the expensive one rather than replacing it. It is taken under the gob's own monitor, the one
      `followPatch` already takes for its loading fallback, so no second discipline arrives with it.
      *Its suite* anchors a patch to an object, drives a bounded wait until it answers `drawn()`, and
      asserts `p:position()` tracks the object while it walks and stops changing once it stops — the pair
      that fails in both directions, since a gate that never opens leaves the ring behind a walking object
      and one that never closes is the cost this task removes. It brackets `allocPerFrame` over a stretch
      with a still anchor and asserts it is flat.
      `[manual]`: walk the anchored object across a slope — expect: the ring stays under it with no lag.

# 045-durable-places — Tasks

Three tasks: 045.1 changes what a free entity holds, 045.2 makes the world moving under it an event, 045.3
writes it down. Each ships its own suite per [`TESTING.md`](../TESTING.md).

- [x] **045.1 — A free entity holds its anchor, and a place with no durable form is refused** ✅ 14/14
      The anchor fields land on `LuaWorldEntity` beside `rc`; `rc` becomes the derived session coordinate
      (nullable) and carries the invariant `rc == null ⇒ not drawn`. `LuaPosition.anchorArg` lands beside
      `worldArg` and replaces it at **exactly two** call sites — `VrApi.anchorArg`'s Position branch and
      `<entity>:position(p)`; the other eight keep `worldArg`. `<entity>:position()` hands back the **anchor
      form** for a free entity, the world form for an anchored one. Re-derivation rides only 044.9's existing
      `groundChanged` drain here (`reground` gains one step in front of its ground test); the second event is
      045.2's.
      *Suite proves*: a ghost stood at the player's own position reads `:position():info()` with the grid the
      player stands on and an offset matching `me:position():info()` to the tile, `:x()` still a number — and
      the same for a sprite, an object and a standing widget, since the shared core reaches all four; a
      Position rebuilt through `hafen.world():position(info)` places identically; a **non-durable** Position
      (some thousands of tiles out, asserted `:durable() == false` first so the premise is stated where it can
      fail) is refused at `:add` **and** at `:position(p)`, both messages naming why; a gob-anchored entity is
      untouched; and 044.9's rule still holds — on drawn ground `:drawn()`, moved off it not.
      *Stated, not discovered*: 044.9's archived suite places its far set at `FAR = 400 * T` through
      `hafen.world():position(x, y)`. If that ground was never recorded on the maintainer's character it is now
      a refusal, and this task changes that one line to a durable place that is merely not drawn.
      <!-- extra context: `specs/addons/044-spatial-ui/addons/044-spatial-ui.9/main.lua` -->

- [x] **045.2 — The place that is not here yet, and the moment the numbers move** ✅ 13/13 + 5/5
      `:add(what, p)` with an anchor this session cannot locate stops raising: the entity exists with no gob,
      `:drawn()` false, `:position():info()` answering the grid it was given and `:x()` nil, and it enters the
      scene by itself when that ground resolves — no `Resolve` chain and no retry cap (042.12's lesson). The
      second drain source lands: one guarded `// addon:` line at `MiniMap.tick`'s `sessloc` assignment →
      `AddonManager.sessionRebased()` → the same drain, flag only (D-106). **The guard is this task's risk** —
      `resolve` mints a `Location` every frame, so an unguarded notify is the per-frame poll 042 deleted.
      *Suite proves*: an entity added at a Position built from a **fabricated** grid id exists, is not drawn,
      reads that id back from `:info()`, answers `:x()` nil, and raised nothing on the way in; one added at the
      player's own grid id stands immediately; 044.9's three booleans still read back what they were told while
      a thing waits for its ground; and the tap is not a poll — the drain does no work across a stretch of idle
      ticks (the 019 counter, as 042 and 044.7 assert idle silence).
      *Two-phase, and the walk is the only manual part*: the suite records what it stood and its `:info()` in
      module state, prints `[manual] walk into a cave (or a house) and back out, then run :t045-2 again`, and on
      the **re-run** compares — same `gridId`, same offset to the tile, `:drawn()` true again, `:x()` differing
      allowed and reported rather than failed. So criterion 1 is an assertion, not an eyeball.
      *As built*: **D-208** (a place you have not reached waits, forever, with no `Resolve` chain) and **D-209**
      (the `sessloc` assignment is the second event; the `(seg, tc)` equality test IS the tap). Three things the
      task text did not foresee. The gob is still built at `:add`, at the origin and outside the scene, rather
      than deferred until the place resolves — `attachScene` stays the one door in, and `:drawn()` is false
      either way. `<entity>:position(p)` was relaxed with `:add`, which left `LuaPosition.hereArg` dead and
      deleted. And the guard needed a witness, so a seventh pull-only counter shipped:
      `hafen.client():profiling():entities()` → `{placed, waiting, passes}`, documented with this task.
      *Found on the way*: the tap must listen to the ONE `MiniMap` the derivation reads — the map window
      carries a second, and either-instance dedup lets the earlier tick consume the change for the later.

- [x] **045.3 — The pages, and the close** ✅ §12: 1465 links / 0 broken, no page over 300, 28 retired names at zero
      `docs/addons/api/vr/README.md` carries the place contract: what a free entity's place *is*, that `:info()`
      is what survives while `:x()` is this session's answer, that an unreached place is legal and simply waits,
      and that a place with no durable form is refused — in the vr pages' own voice, not as a changelog.
      `docs/addons/api/world.md`'s "raw world coordinates … reset each login" is **corrected**: they are re-based
      mid-session too, which is the sentence a reader would build the wrong model on. Cross-links checked against
      `api/vr/{ghosts,sprites,models,widgets}.md` and `api/map/grids.md`'s "storing a place"; the two glance
      tables only if a row's wording is now wrong. Read area `docs`'s standard **before** writing
      (`specs/docs/design/style-guide.md` §9–§12 plus the `### D-` one-liners) and run and report its §12 checks.
      *Suite*: none of its own, and it says so — its proof is the §12 report plus 045.1's and 045.2's commands still green.
      *As built*: the vr hub's ground section took **both** halves of the contract — the numbers moving under a
      thing that does not move with them, and *a place this session cannot locate is a legal place to stand
      something* — and the `:info()`/`:x()` asymmetry, which had been stated twice on that page, now lives there
      once with the vocabulary table linking to it. **Three pages the task did not name carried the same wrong
      model verbatim** and were corrected in one clause each rather than left to contradict `world.md`:
      `api/conventions.md`, `guides/reading-the-world.md`, and `api/vr/widgets.md`'s "its place is now a world
      coordinate". Two pre-existing breaches on the pages this task owns were paid off on the way — `conventions.md`
      went to **301** lines on a two-into-three reflow (rewritten to correct in place at equal line count), and the
      vr hub carried a 149-column line and "the same five verbs", a count in prose duplicating the table under it.

## Notes

- **045.1 is the gate.** If `rc` cannot be nullable without the scene paths noticing, 045.2 changes shape, so
  045.1 ships the smallest end-to-end path (the anchor stored, derived, refused) and no new event.
- **The `sessloc` tap is the feature's only `haven` edit**, one guarded line. If `MiniMap.tick` turns out to be
  the wrong place for it, that finding lands in 045.2's `HANDOFF.md` before it is worked around.

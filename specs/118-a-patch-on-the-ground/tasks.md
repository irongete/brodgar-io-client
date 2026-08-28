# 118 — tasks

- [x] **118.1 — A patch lies on the ground.** Adds `hafen.vr():patch()`, the fifth collection, with
      `:add(ring, anchor)`, `:list`, `:count`, `:find`, `:remove`, and on the patch `:exists`, `:drawn`,
      `:info`. Behind it: `PatchOverlay` (the `OverlayInfo` + `LocalOverlay` pair, the bounding-box tile
      mask, the `Material`), `PatchCarve` (the per-edge uniforms, the `fwidth` builtin, the shader, the
      winding-agnostic half-plane build), and the `// addon:` seam on `MapView.Overlay` that keeps the
      slot `added` adds the material into. `SpikeWorldPaint` and its `:spike` command are deleted here.
      *Its suite* builds a ring from the player's own Position with `p:offset`, adds it, and asserts the
      collection holds exactly it — `:count() == 1`, `:find` reaches it, `:exists()` — then that `:drawn()`
      becomes true within a bounded retry, because a patch drawn is a patch whose ground resolved, and
      that one anchored to a Position that character cannot locate reads `:exists()` true with `:drawn()`
      false rather than drawing part of itself. It `pcall`s three bad rings and asserts each failed *and*
      said why: two points naming the three-point minimum, a concave ring naming convex, and a ring over
      the edge limit naming that number. Its manifest declares **no permissions**, and every call above
      succeeding is what unprotected means here.
      `[manual]`: stand on a slope with one under you — expect the ring's own shape, edge crisp and not a
      staircase of square tiles, lying flush with no gap and no flicker as you zoom in and out.

- [x] **118.2 — A patch follows, turns and is tinted.** The patch answers the shared `hafen.vr()`
      vocabulary: a **Gob** anchor that follows and dies with it, `:offset(x, y)` on the ground,
      `:scale`, `:rotate`, `:tint`, `:alpha`, `:visible`, `:position`. `:scale` and `:rotate` recompute
      the half-planes and push a new carve state through the seam rather than rebuilding a mesh.
      *Its suite* round-trips every verb — writes a value, reads it back, asserts equality — and asserts
      `:info()` carries `kind`, `ring`, `scale`, `rotate`, `alpha`, `visible`, `drawn`, `exists`, and no
      `offset` on a planted one. It `pcall`s `:offset(0, 0, 1)` and asserts it failed naming that a patch
      has no height, and `:position(p)` on a gob-anchored one and asserts it failed naming `:offset`.
      It feeds a ring **straight out of `gob:hitbox()`** into `:add` with that gob as the anchor and
      asserts the patch exists, since taking that shape with no projection and no conversion by the
      caller is the whole point of a ring of Positions. It re-asserts `:add` and `:count` rather than
      leaning on 118.1's run.
      `[manual]`: walk a few steps with one anchored to your character — expect it under the feet the
      whole way, keeping its shape.

- [x] **118.3 — A patch answers a click.** `:clickable(b)` and `:onClick(fn)` on the patch, and
      `PatchClicked` on the bus beside `GhostClicked`, `SpriteClicked` and `ObjectClicked` — an addon
      hears only its own. The hit test projects the ring and solves the point against the same
      half-planes the carve uses, run from `MapView`'s synchronous `// addon:` `mousedown` hook, so it
      answers inside the event rather than through the asynchronous pick pass.
      *Its suite* asserts `:clickable()` reads `false` on a fresh patch and round-trips a write, that
      `:onClick(fn)` hands the patch back so a chain continues, and that `:onClick()` reads back what was
      set. It subscribes to `PatchClicked`, asserts the `Sub` and that `sub:off()` returns it, and prints
      what it received on each of the two manual clicks below. Nothing in the client can deliver a click
      from Lua, so the firing itself is the maintainer's.
      `[manual]`: click inside the patch — expect one `[click] inside` line. Then click a hand's width
      outside its edge — expect no line at all.

- [x] **118.4 — The pages.** Writes `docs/addons/api/vr/patches.md`; adds the fifth collection to
      `vr/README.md` and takes the count out of its `## One vocabulary, four kinds` heading,
      **re-pointing the two links into that anchor** (`vr/ghosts.md`, `event/bus/world.md`) in this same
      task; adds `PatchClicked` and its `ev` reads to `event/bus/world.md` and `event/bus/README.md`;
      points `api/gob.md`'s hitbox prose at what a footprint is now for; lists the new page in
      `api/README.md`. Writes the ground-overlay gotchas onto `docs/client/world-3d.md` and **splits it**,
      by the subjects `specs/ROADMAP.md` names, since it is over its ceiling before this feature touches
      it.
      *Its suite* runs the example blocks `patches.md` ships, verbatim, and asserts each produced the
      patch it claims — a page whose example does not run is the defect this catches.
      Then the `DOCUMENTATION.md` §11 checks over every page touched: links and anchors resolved across
      newlines, `wc -l` against the ceilings, headings, the change-note wording greps, every `hafen.*`
      symbol present in `src/`, and the spec's impact set discharged row by row.

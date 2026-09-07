# 135 — gob tint: tasks

- [x] **135.1 — `gob:tint(c)`: a colour laid over a game object.** Adds `GobTint` (a `GAttrib` +
      `Gob.SetupMod` on `GobScale`'s mould, its `Wash` state a `Slot` of its own blending `colblend` at
      fragment order 50, cached once per value), the `tint` verb in `LuaGob` beside `visible` (bare read
      → keyed colour or `nil`; `nil` clears; a colour writes and hands the Gob back; anything else refused
      with `colorRefusal("gob:tint")`), the tint half of `GobIntent.Record` with `tint(id, owner, c)`,
      `dropOwner` and `applyTo`, `GobTint.revert` inside `UiApi.teardownGobScales`, and `tint` in
      `gobInfo`. Builds with `rm -rf build/classes; ant hafen-client; ant bin`. Criteria 1–6.
      *Its suite* (`addons/135-gob-tint.1/`) drives the player's own gob, the one always loaded:
      `tint()` reads `nil` bare; `tint{255, 0, 0, 96}` returns the same id and reads back `r=255,
      a=96` keyed; positional `{0, 255, 0}` reads `a=255`; `info().tint.g == 255`; `tint(nil)` reads
      `nil` and `info().tint == nil`; `tint(1)`, `tint("red")`, `tint(true)`, `tint{"x"}` each fail
      naming "a colour is a table"; `tint(c):scale(2):visible(false):visible(true)` still reads the
      colour and `scale() == 2`, then both are put back; `get(2^40)` reads `nil` and takes a write
      without raising. Then it tints the player `{255, 0, 0, 96}`, tints the nearest object of a
      resource with two or more copies in view, tints the nearest object whose `health() < 1` if any,
      and clears the three on a one-shot 8-second timer.
      `[manual]`: your character wears a red wash for 8 seconds and its shading stays — expect: lit
      and shaded sides still distinct, not a flat red silhouette.
      `[manual]`: of two identical objects side by side, one is tinted — expect: only that one.
      `[manual]`: the damaged object, if one was in view — expect: its cracks and red damage wash still
      show under your colour.
      <!-- extra context: src/haven/render/InstanceList.java (InstKey.uinststate) — the batching key -->

- [ ] **135.2 — The page a gob's look is on.** Creates `docs/addons/api/look.md` (*Look: how a gob is
      drawn*) from `gob.md`'s Size, Drawn or not and Overlays sections plus a new Tint section that
      states the blend, the `a = 255` flat fill, the `nil`, the keyed read, and "a size's rules exactly"
      for where it lands and how long it lasts. `gob.md` keeps a `## How it is drawn` pointer and ends
      under 300 lines, as does `look.md`. Re-points the ten inbound anchors (five outside `gob.md`, five
      inside), adds the `README.md` row, the `conventions.md` *none* entry, `shapes.md`'s reader and
      writer, `threading.md`'s safe-write bullet, `GobInfo`'s `tint` row, and the `MixColor` slot gotcha
      on `world-3d.md`'s `SetupMod` row (split if over 150). Runs `tools/docverbs.py`,
      `tools/refusalverbs.py`, the link scan across newlines, and reports `wc -l`. Criterion 7, and the
      pages for 1–6.
      *Its suite* (`addons/135-gob-tint.2/`) asserts what the page promises, standing alone: the keyed
      read shape (`.r` answers, `[1]` is `nil`), `tint(nil)` legal and reading `nil`, the refusal text
      naming both spellings of a colour, and the write reaching every character: after tinting an object
      it lists `gob:sessions()` and, for each, reads the tint through that session's own
      `s:world():gob():get(id)` — scoring over the sessions logged in, one included.
      `[manual]`: with a second character logged in near the object after the tint was written —
      expect: it arrives already tinted in that character's view.
      `[manual]`: `:reload` the addon layer — expect: every object you tinted is drawn plain again, in
      every character's view.

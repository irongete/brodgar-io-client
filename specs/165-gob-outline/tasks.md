# 165 — Tasks

Both suites `pcall` these, stripping the chunk prefix with `^@?.-%.lua:%d+:?%s*`:

| Call | The text contains |
|---|---|
| a non-colour: `1`, `"red"`, `true`, `{"x"}` | `a colour is a table` |
| width `0`, `9` · `1.5` · `"2"` | `from 1 to 8` · `whole number` · `must be a number` (from `Args.integer(…, 1, 8)`) |
| a third argument | `takes at most 2 arguments` |
| `(nil, 2)` | `takes no width`. Full text: `<verb>(nil) takes the ring off and takes no width — <verb>(color, width) draws one` |
| `patch:outline(c)` | `patch:border(color, width)`. Full text: `patch:outline: a patch's line is patch:border(color, width), a band round the shape in world units — :outline rings what is drawn of a gob or a standing entity` |

Verify each task: `rm -rf build/classes && ant hafen-client` (`BUILD SUCCESSFUL`); `rm build/hafen.jar && ant
bin`; `tools/docverbs.py` and `tools/refusalverbs.py`, each bare, exit `0`; `luac -p` the suite and copy it to
`bin/addons/`; a full client restart.

- [x] **165.1 — gob:outline(color [, width]): a ring round what the client draws of a gob, on its visible part.**
      Code, as `plan.md` specifies: `OutlineMask`, `GobOutline`, `OutlineRing`; the `MapView` and `FragData.defid`
      seams; `LuaGob`'s `outline`; `GobIntent.outline` and its `Record` fields; the revert in
      `UiApi.teardownGobScales`; `gobSnapshot`'s `outline`; `ApiVersion.CURRENT = (1, 2)`.

      Docs:
      - `spec.md`'s 165.1 lines.
      - `look.md`:
        - The intro names four things, and *Composition* covers four verbs.
        - A new `## Outline (unprotected)` section. Its example (≤ 12 lines) is the `PickChanged` hover highlight.
          Its rules cover the width, `a`, the visible part only, what is and is not ringed, `nil`, the refusals
          and API `1.2`.
      - `manifest.md`'s *What needs `1.2`*, version sentence and out-of-date examples, and every
        `"api_version": "1.1"` example in `docs/`.
      - `docs/client/render-targets.md`, and its README row.

      Criteria 1–8.

      *Its suite:* `addons/165-gob-outline.1/`, run with `:t165-1`, `"api_version": "1.2"`. It works on the
      player's gob `me`: 9 `[pass]`, 4 `[manual]`, the summary.

      The `[pass]` checks:
      1. `me:outline{0, 255, 0}` hands `me` back and reads `{r=0, g=255, b=0, a=255}`, then `2` (2).
      2. `me:outline({255, 0, 0, 128}, 5)` reads `a = 128`, then `5` (2).
      3. Under `me:tint{0, 0, 255, 96}`, `info().outline` is `{color = {…, a = 128}, width = 5}`, and writing and
         clearing the outline leaves `me:tint().a == 96`; then `tint(nil)` (5).
      4. `me:outline(nil)` hands `me` back and reads `nil`, and `info().outline` is `nil` (3).
      5. The non-colours are refused (4).
      6. The widths are refused (4).
      7. The surplus argument and `(nil, 2)` are refused (4).
      8. `world:get(2 ^ 40)` reads `nil`, takes `:outline{1, 2, 3}` without raising, and still reads `nil` (5).
      9. `me:outline{0, 255, 0}:scale(2):visible(false):visible(true)` still reads the colour, `scale() == 2` and
         `visible() == true`, then `scale(1)` (5).

      `me` keeps a green 2 px ring; for 20 s a `PickChanged` handler rings the pointed object (never `me`) in
      `{0, 120, 255}` width `4` and clears the one it left; then `sub:off()` and a last clear.

      `[manual]`:
      - Look at your character: a green 2 px ring round body and gear, nothing inside (1).
      - For 20 s, sweep the pointer across objects: the one under it wears a blue 4 px ring, the one left loses it
        (7).
      - Put a tree or wall between camera and character: the ring follows the obstacle's edge, never crossing it
        (6).
      - `:reload`: the green ring is gone (8).

- [ ] **165.2 — entity:outline(color [, width]) on a ghost, a sprite, an object and a panel; a patch refuses it.**
      Code: `LuaWorldEntity.outline`/`outlineWidth`; `entityHandle`'s `outline` and `setEntityOutline`;
      `GobOutline.apply` at the five look-copy sites; `:info()`'s `outline`; `patchHandle`'s refusal.

      Docs:
      - `spec.md`'s 165.2 lines.
      - `virtual/README.md`: a vocabulary row and the snapshot key.
      - The verb lists in `ghosts.md` and `widgets.md` gain `:outline`.
      - `patches.md`: a refusal row. Its line-18 "outline" becomes "edge", as does `pieces.md:14`'s.

      Criteria 9–11.

      *Its suite:* `addons/165-gob-outline.2/`, run with `:t165-2`, `"api_version": "1.2"`. Its files:
      - `disc.png`, copied from `specs/038-gob-overlays/addons/038-gob-overlays.2/icon.png`: a disc on a
        transparent 32×32.
      - `cube.glb`, a unit cube this task writes in Python: 8 `POSITION`s, 36 `uint16` indices, one buffer.

      It stands, from `here = me:position()`:
      - the ghost `"gfx/terobjs/arch/logcabin"` at `offset(33, 0)`, `:scale(0.3)`;
      - the sprite `disc.png` at `(-33, 0)`, `:scale(2)`;
      - the object `cube.glb` at `(0, 33)`, `:scale(1.5)`;
      - a panel of `hafen.ui():window():title("165.2"):size(96, 64)` at `(0, -33)`;
      - `patches.md`'s 12×12 square at `(0, 66)`.

      The `[pass]` checks, each over the four kinds:
      1. `e:outline{255, 160, 0}` hands `e` back and reads `{255, 160, 0, 255}`, then `2` (9).
      2. After `e:outline({255, 160, 0}, 6)`, `info().outline` is `{color = …, width = 6}`. Then `e:outline(nil)`
         reads `nil` and drops the key (9).
      3. The table's first four rows are refused (9).
      4. `patch:outline{1, 2, 3}` raises its text (11).

      Then all four wear `{255, 160, 0}` width `3`. The ghost is at `:alpha(0.5)` from 15 s to 25 s, and the sprite
      faces `"screen"` from 30 s to 40 s. At 60 s the suite removes all five. 8 lines.

      `[manual]`:
      - Look at the four things round your character: each has an orange 3 px ring round what is drawn, the
        sprite's round its disc, not its square (10).
      - Watch the log cabin from 15 s to 25 s: no ring while it is see-through, back after (10).
      - Watch the sprite from 30 s to 40 s: no ring while it faces the screen, back after (10).

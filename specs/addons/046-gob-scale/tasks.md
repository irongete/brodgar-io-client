# 046-gob-scale — Tasks

- [x] **046.1 — a native gob answers `:scale`.** The whole feature in one session: the `GobScale`
      attrib (cached `Location.scale` from `gobstate()`, owner recorded, `null` at `1`), the
      `:scale` read/write pair on `LuaGob`, and `UiApi.teardownGobScales` wired into
      `AddonRegistry.teardown` beside `teardownGobOverlays`. No `haven` file is touched — if one
      has to be, stop and say so, because that is the plan being wrong rather than a detail.

      **The suite (`:t046-1`) must prove, by assertion:**
      - an unscaled gob reads `1`, and `hafen.player():gob():scale(1.5)` reads back `1.5`;
      - the write hands the **Gob** back — `g:scale(1.5):id() == g:id()` — so it chains;
      - `:scale(1)` reads back `1` and leaves nothing behind (a second read still answers `1`);
      - each refusal, naming its rule: `0`, `-1`, `1/0`, `0/0`, `"big"`, `nil`, and a table;
      - a gob that is gone answers `nil` to `:scale()` and **does not throw** on `:scale(2)` —
        take a `hafen.world():gob():get(<an id that never existed>)` for this, the page's own
        "always returns a Gob" door;
      - the vr sibling still answers its own `:scale` (this task's premise: one verb, two kinds) —
        a `hafen.vr():sprite()` scaled and read back, then removed;
      - the suite ends leaving **nothing scaled** — it undoes every write it made and re-reads `1`.

      **`[manual]` (what a program cannot see):**
      - `:t046-1` while standing still ⇒ *"you visibly grow to 1.5× and shrink back, in place — your
        feet do not move and the camera does not jump"*;
      - scale a tree or a boulder near you and click it ⇒ *"the click still selects it, and the
        pick follows the drawn size"*;
      - walk far enough for that gob to unload and come back ⇒ *"it is its original size"* — the
        contract, not a defect;
      - scale something, then `:reload` ⇒ *"it is its original size, and nothing else changed"* —
        the teardown revert, which is the criterion the maintainer named.

      <!-- extra context: `src/haven/GobHealth.java` (the attrib pattern to copy verbatim),
           `src/io/brodgar/addon/LuaGobOverlay.java` + `UiApi.teardownGobOverlays` (:1793)
           (the addon-owned-attrib-on-a-gob precedent and its teardown sweep),
           `src/io/brodgar/addon/AddonRegistry.java` (the ordered teardown list) -->

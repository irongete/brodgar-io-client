# 026-text-cache — Tasks

- [x] 026.1 — **The cache.** Per-addon cache of the rendered `Text` in `LuaGOut`, keyed by
      `(string, FontHandle-or-null, markup)`; colour stays out (blit tint, F2). One `cachegen` int vs
      `Fonts.gen()` — moved ⇒ drop + dispose all, re-stamp. LRU bounded by entry count *and* texture
      bytes, eviction disposes. `bind` grows an `Addon` owner (three call sites: `LuaWidget:115`,
      `UiApi:1188`, `UiApi:1271`); `AddonRegistry.teardown` drops + disposes the owner's entries.
      Both paths go through it — the fast path stops calling `GOut.atext` and does
      `Text.render`→`blitText` itself (same lifecycle minus the `dispose`). Caps are provisional
      constants in one place; 026.2 tunes them.
      **Verify in-game:** FPS with `hello` enabled returns to near the disabled baseline (before:
      220/130) with its three always-on sites still up. Nothing looks different — plain text, `$font`
      /`$col`/`$b`, per-call `{font=}`/`{color=}`, a bare `g:color` tint, every anchor. `:hello font`,
      `button`, `heading`, `chat`, `speech`, `node` still restyle live and still reset. `:reload`
      repeatedly and disable every addon: no visual drift, no growth. `hello` needs no edit for this
      task — it already draws all of these, so it is the regression by simply existing.

- [x] 026.2 — **Make it measurable.** `hafen.client:profiling():textcache()` → entries, texture bytes,
      hits, misses, evictions (pull-only, must answer with profiling OFF — D-051). Then *use* it:
      tune the two caps with real numbers and record them in plan.md.
      **Verify in-game:** `:hello textcache` prints the table; the static HUD lines show a high hit
      rate and the deliberately volatile line shows misses; a scratch loop drawing thousands of
      distinct strings settles at the cap instead of growing, and bytes stop rising once evicting;
      disabling every addon returns bytes to ~0 (the leak check).
      <!-- extra context: `src/io/brodgar/prof/Prof.java`, `src/io/brodgar/addon/ProfHandle.java`,
           `019-profiling/` (the pull-only counter shape + D-050/D-051) -->

- [x] 026.3 — **Close it.** `docs/addons/api/ui.md`: `g:text`/`g:atext` gain the "cached across
      frames, invalidated when a font override moves" behaviour note — including the honest line that
      a string which changes every frame is re-rasterised every frame, so budget a live readout by
      how often its *text* changes, not by how many lines it has. `client.md` gets the `:textcache()`
      row, `fonts.md` the cross-ref, both index tables updated if a row is new. `hello` keeps the
      `:hello textcache` dump + the volatile-vs-static pair from 026.2 as standing harness. Coverage
      toll: extend `specs/codebase/widgets.md`'s "nothing 2D is cached across frames" bullet — still
      true of `haven`, no longer true of addon text. Append to `learnings/ui-widgets.md` beneath the
      019.8 entry (which currently predicts the handle wins) what actually happened and the measured
      hit rates. Write the "cache, not handle" decision into `DECISIONS.md` + `decisions/`.
      **Verify:** maintainer reads the docs against the shipped behaviour; one login re-checks the
      whole `hello` regression.

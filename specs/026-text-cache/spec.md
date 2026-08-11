# 026-text-cache — Spec

## What & why

Every `g:text`/`g:atext` call re-rasterises its string **and creates + destroys a GL texture, every
frame**: both of `LuaGOut.drawText`'s paths end in render→tex→blit→dispose (the fast path inside
`GOut.atext`, the rich path explicitly). 019.8 measured ~0.28 ms per line — ~50x the cost of geometry
(360 primitives = 0.11 ms vs ~20 lines = 5.6 ms), and in-game the `hello` harness costs ~90 FPS
(220 → 130) from just its three always-on draw sites. A client `Label` dodges this by holding its
rendered `Text`; our API is immediate-mode, so no addon can. This feature puts that same "hold the
`Text`" behind `g:text` as a **per-addon, content-keyed, bounded cache** — invisible, no API change,
no addon edit. ROADMAP weighed an explicit text handle as the alternative; it is **discarded**, see
plan.md (it costs the same on the volatile strings the cache misses, and is permanent contract).

## Acceptance criteria

- [ ] With `hello` enabled, in the same spot, FPS is within a few % of the addon-disabled baseline
      (maintainer's own before: 220 disabled / 130 enabled). The three always-on sites stay up
      (top-centre HUD box, centre crosshair, the player tag over your head, the drop box).
- [ ] **Nothing looks different.** Both paths render as before: plain text, `$font`/`$col`/`$b`
      markup, per-call `{font=}`/`{color=}`, a bare `g:color(...)` tint around a `g:text`, and every
      `ax`/`ay` anchor. Compared against the current client side by side on the `hello` windows.
- [ ] **Font overrides still restyle live.** `:hello font`, `:hello button`, `:hello heading`,
      `:hello chat`, `:hello speech`, `:hello node` and their resets all still take effect on the
      frame after the override moves — the cache invalidates on a `Fonts.gen()` bump.
- [ ] A colour change alone does **not** cost a re-rasterisation (colour is a blit tint, not part of
      the key): drawing the same string in two colours in one frame is one cache entry.
- [ ] `hafen.client:profiling()` exposes the cache: entries, texture bytes, hits, misses, evictions.
      With profiling on, `:hello textcache` prints them; the `hello` HUD's mostly-static lines show a
      high hit rate and the volatile ones show the expected misses.
- [ ] The cache is **bounded**: a demo that draws thousands of distinct strings settles at the cap
      instead of growing without limit, and evicted entries are disposed (texture bytes stop rising).
- [ ] `:reload` and disabling an addon drop that addon's entries and dispose their textures — no
      leak across reloads (texture bytes return to ~0 with every addon disabled).
- [ ] `hello` exercises the feature; full regression still passes (one login re-checks everything).

## Out of scope

- **The explicit text handle** (`hafen.render.text` + a blit call). Discarded, not deferred —
  plan.md records why, and the ROADMAP entry is replaced by this feature.
- **Caching `GOut.atext` client-wide.** The cache lives in `LuaGOut` (addon text only). Fixing
  `haven`'s immediate-mode 2D wholesale is a separate, riskier feature.
- **The other per-frame addon-layer cost**: the tick's polling suite runs whether or not any addon is
  loaded (`ActionbarAdapter.poll()` walks all 144 belt slots and allocates a snapshot per occupied
  slot, every frame; likewise equip/study/wounds/markers/models). Real, but a different feature — it
  is not the enabled-vs-disabled delta this one fixes. Goes to ROADMAP.
- Wrapped / multi-line text: `drawText` renders at width 0 (single line) and stays that way.
- Allocation profiling (the `allocBytes` column) — already its own ROADMAP block.

## Context files

- `design/07-ui-and-drawing.md` — the `hafen.ui` drawing surface `g:text`/`g:atext` belong to
- `design/21-fonts.md` — the font provider, scopes and the F1 generation-invalidation model
- `src/io/brodgar/addon/LuaGOut.java` — `drawText`/`blitText`: the only site to change
- `src/io/brodgar/addon/FontHandle.java` — the per-handle `rich(px)` foundry cache; part of the key
- `src/haven/GOut.java` — `atext` (`Text.render`→`tex()`→`aimage`→`dispose`), the pattern replaced
- `src/haven/Text.java` — `Text.render` statics, `Foundry.resolved()`, what a cached `Text` holds
- `src/haven/TexI.java` — `st()` lazy GPU upload + `dispose()`; where the per-frame churn actually is
- `src/haven/Fonts.java` — `gen()` (volatile, bumped on install/move/reset) = the invalidation signal
- `src/haven/Label.java` — the client's own hold-the-`Text` + `fontgen` compare, the pattern copied
- `specs/codebase/text-and-fonts.md` — foundry surfaces + "a site that caches its `Text` must rebuild"
- `specs/codebase/widgets.md` — already records "nothing 2D is cached across frames" for `atext`
- `016-fonts/` — prior art: the F1/F3a–F3c generation-invalidation slices and their learnings
- `019-profiling/` — the measurement (019.8) and the `hafen.client:profiling()` surface to extend
- `docs/addons/api/ui.md` — where `g:text`/`g:atext` are documented (surface unchanged; the cache and
  its invalidation get documented as behaviour)
- `docs/addons/api/fonts.md` — the font-handle side of the key; `docs/addons/api/client.md` — the
  `hafen.client:profiling()` page the cache-stats reader is added to

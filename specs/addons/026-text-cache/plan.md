# 026-text-cache — Plan

## Approach

Do inside `LuaGOut` what a `Label` does by hand: render the `Text` once and keep it. The whole
feature lives in `drawText`; the `g` surface, its arguments and its output are unchanged.

**Where the churn is.** `drawText` has two paths and both end render→tex→blit→dispose. The rich path
does it explicitly; the fast path does it inside `GOut.atext`. Caching the fast path therefore means
*not* calling `GOut.atext` any more — we call `Text.render(str)` ourselves and blit through the
existing `blitText`. That is `GOut.atext` line for line (`Text.render` → `tex()` → `aimage`), minus
the `dispose()`, so it is behaviour-identical by construction rather than by inspection.

**The key** is `(string, FontHandle-or-null, markup)`. Deliberately **not** in it:
- **colour** — `blitText` already applies it as a `chcolor` tint around the blit and the rich path
  rasterises glyphs white (F2). Two colours of one string are one entry.
- ~~**`Fonts.gen()`** — a generation is not a key, it is a *clear*.~~ **REVISED IN 026.1: `Fonts.gen()`
  IS part of the key.** The clear-on-move model (one `cachegen` int, the F1 `Label` pattern) assumes the
  generation is frame-global. It is not: while a per-instance frame is open (F5, `node:setFont` —
  `:hello node`) `gen()` XORs in that override's `Spec.stamp`, so it differs *between draw sites within
  one frame* — a widget inside the frame, a HUD overlay outside it. Clear-on-move would therefore clear
  the cache on every alternation, i.e. be worse than no cache. As a key component it costs the same one
  `int` per draw, is correct under F5, and an override install/move/reset still invalidates for free
  (fresh keys); the stale generation's entries fall out of the LRU on their own.

**Ownership + lifetime.** The cache is **per-addon**, like every other cache in this area
(`LuaGob`/`LuaBuff`/`LuaSound` intern caches). `LuaGOut` is a shared wrapper bound per draw callback
and today has no owner, so `bind(GOut[, FontHandle])` grows an `Addon` argument — there are exactly
**three** call sites (`LuaWidget.java:115`, `UiApi.java:1188` HUD pump, `UiApi.java:1271` gob overlay)
and each already holds its owner. `AddonRegistry.teardown` drops the addon's entries and disposes
their textures, so disable/`:reload`/session-init are covered by the path that already exists.

**Bounding.** LRU with two caps — entry count *and* total texture bytes (a wrapped paragraph is not
one label). Eviction disposes. Both caps are constants in one place, tuned once with 026.2's numbers.

**Threading.** All of this runs on the UI thread inside `UI.draw`; no locking. `Fonts.gen()` is
`volatile`, so worst case an override lands one frame late — already the accepted F3a behaviour.

## Files to create / modify

- `src/io/brodgar/addon/LuaGOut.java` — the cache (map + LRU + caps + `cachegen`), `drawText` reworked
  onto it on both paths, `bind` takes the owner, the fast path stops calling `GOut.atext`
- `src/io/brodgar/addon/Addon.java` — the per-addon cache field (beside the existing per-addon caches)
- `src/io/brodgar/addon/AddonRegistry.java` — teardown drops + disposes the owner's entries
- `src/io/brodgar/addon/LuaWidget.java`, `UiApi.java` — pass the owner at the three `bind` sites
- `src/io/brodgar/addon/ProfHandle.java` — `:textcache()` reader (entries/bytes/hits/misses/evictions)
- `src/io/brodgar/prof/Prof.java` — counter plumbing, if the existing pull-only shape needs it (D-051:
  a pull-only counter must answer with profiling OFF; these are plain longs, so it should)
- `addons/hello/main.lua` — `:hello textcache` dump + a deliberately volatile line beside the static
  ones, so hit and miss are both visible in one login
- `docs/addons/api/ui.md` — `g:text`/`g:atext` gain a "cached across frames" behaviour note
- `docs/addons/api/client.md` — the `:textcache()` row; `docs/addons/api/fonts.md` — the cross-ref
- `specs/codebase/widgets.md` — extend the "nothing 2D is cached across frames" bullet: still true of
  `haven`, no longer true of addon text (coverage toll for the `GOut`/`TexI` reading)
- `specs/addons/learnings/ui-widgets.md` — the 019.8 entry ends "the fix, if ever wanted, is a text
  HANDLE… rather than an invisible cache"; append the outcome and why the handle lost
- `specs/addons/DECISIONS.md` + `decisions/` — the "cache, not handle" decision

## Measured (026.2) — and the caps that came out of it

`:hello textcache`, `hello` alone, ~35 s in the world after login:

    512 entries / 7.91 MiB held · 62844 hits + 7893 misses = 88.8% hit rate · 7381 evictions

- **~15.8 KiB per entry** (7.91 MiB / 512) — a ~256×16 raster rounded up to powers of two. The provisional
  **16 MiB byte cap could therefore never bind before the 512-entry one**: it was decoration, not a bound.
  It comes down to **8 MiB**, where the two caps meet at the measured average width, so narrow text is bounded
  by count and wide text by bytes. That is the point of having two.
- **`MAXENTRIES` stays 512.** The cache is permanently *full*, but not because the working set is 512: the
  static sites are ~40 lines a frame and the rest are dead strings from the deliberately volatile line, never
  looked up again. Cutting the cap to the working set would save VRAM and change the hit rate by almost
  nothing — but a cap *below* one frame's distinct strings evicts every entry before its next use and pays
  eviction + dispose **on top of** the rasterisation it failed to save, i.e. strictly worse than no cache.
  512 keeps a text-heavy addon clear of that cliff; the byte cap bounds what the headroom can cost.
- **88.8% hit rate with a permanently-evicting cache** is the feature working as designed, not a shortfall:
  the misses are one string per frame that has never existed before, and no cache can do anything about those.

**The stress toggle independently re-measured the cost 026 exists to kill.** `:hello textcache stress` adds 32
fresh rasterisations per frame and takes the client from 240 to 80 FPS:
`(1/80 − 1/240) s / 32` = **0.26 ms per line**, against 019.8's 0.28 ms measured a different way. The two
agree, which is the strongest evidence available that the thing being cached is the thing that cost the frames.

## Risks & gotchas

- **The win is workload-dependent, and one addon gains ~nothing.** The 019.8 learning is blunt: *"a
  cache keyed by the string misses on every frame whose digits changed, which in a profiler is all of
  them."* The `profiler` addon is exactly that and will barely improve; `hello` and normal HUD text
  will improve a lot. This is expected, not a defect — but it makes **a miss having to stay cheap**
  (one hash lookup on a string the caller already built) a hard requirement, and it is why 026.2
  exists. Do not tune the caps before the numbers are readable.
- **We now own GPU textures.** Today every texture is disposed the same frame; after this, a missed
  dispose on eviction, on a `gen` clear or on teardown is a real GPU leak. Be the *only* disposer of a
  cached `Text`'s `Tex` — `TexI.dispose()` drops the `ColorTex` and `st()` would silently re-upload,
  hiding a double-free as a slow leak instead of a crash.
- **Do not derive foundries.** `RichText.Foundry.derive(…)` silently rebuilds a plain `Parser` and
  drops a `Parser` subclass (fonts.md). Keep using `FontHandle.rich(px)`, which already caches.
- **The malformed-markup fallback** re-blits through the fast path; make sure it does not land in the
  cache under a key that claims it was rich.
- **`Loading`** must still never escape the draw (the `g:resource` idiom) — the cache adds no new
  throw site, but the fast path moves `Text.render` out of `GOut.atext` and into our `try`.
- **Hidden state in `g` is already precedented**, contra the ROADMAP's worry: `g:resource` keeps a
  static name→`Indir` map on `LuaGOut`, cleared on `:reload` for faithfulness (D-039).

## Discarded alternatives

- **Explicit text handle** (`hafen.render.text` + a blit) — costs the *same* on the volatile strings
  the cache misses (`t:set` must re-rasterise too), only reaches cache-hit parity on the static ones,
  needs every addon's draw path rewritten, and is permanent contract where the cache is a removable
  implementation detail. The ROADMAP framed the two as rivals; on the volatile case they tie.
- **One global cache shared by all addons** — better hit rate on identical strings (rare), but one
  addon can evict another's entries and teardown stops being trivial. Per-addon matches the area.
- **Cache in `haven`'s `GOut.atext`, client-wide** — would help every immediate-mode client site, but
  it is a fundamental-file behaviour change for a client whose own widgets already cache by hand.
- **Call-site memo** (per-widget, per-draw-call ordinal, like a `Label` derived automatically) — no
  eviction policy at all, but a conditional inside `onDraw` shifts every later ordinal. Too fragile.
- **Pool/reuse textures without caching the raster** — skips the GL churn but keeps the AWT layout +
  rasterise, which is the larger half; and `TexI` sizes its texture to the string.

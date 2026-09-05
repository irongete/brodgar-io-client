# 130 — tasks

> **Neither suite can watch the collector.** No verb reports a pause, and a stop-the-world pause is
> invisible to the thread it stops. What `p:memory()` does report is `gcCount` and `gcMs`, cumulative and
> meaningful only as a delta between two reads — so both suites measure the way the feature was diagnosed:
> a delta over a bounded stretch of frames, armed against disarmed, on the same scene. The verdict is a
> ratio, not a millisecond count, because the absolute number belongs to the machine it ran on.

- [x] **130.1 — the frame ring keeps a handful of frames, not five seconds of them.**
      `UILoop.uprof`, `rprof` and `gprof` are built with **16** slots instead of 300, `// addon:` carrying
      the measurement as its reason: three hundred frames of `Part` trees hold `Part.nm`, which for the
      tier `Widget.dispatch` and `Widget.draw` bracket **is the Widget**, so the ring pins every widget the
      client touched in five seconds, and each young collection scans and copies every reference that
      old-generation structure holds into the young one — 68 ms against 9. Nothing else moves: `Profdisp`
      takes its window size, its texture width, both draw loops and its click hit-test from `hist.length`,
      so the backtick graph narrows rather than breaks, and `UILoop:586` reads `last()`, which is one frame
      at any length ≥ 1. `docs/client/boot-and-loop.md`'s profile table has its "300-frame ring" refreshed
      where the number is now wrong.
      *Its suite* arms profiling, takes `p:memory()` twice across a bounded stretch of armed frames for a
      `gcMs`/`gcCount` delta, disarms, repeats on the same spot, and asserts the armed cost per frame is
      within a small multiple of the disarmed one — the assertion that fails if the ring still lengthens
      collection. It also asserts `p:history(n)` answers several hundred frames, and that `p:frame()` still
      resolves a `gpuMs` with its `gpuFrameno`: that path runs through `Prof.pgf`, not the ring, and proving
      it did not move is what separates a shorter ring from a broken GPU read.
      `[manual]`: press backtick with profiling armed — expect: three profile windows open and draw.

- [x] **130.2 — the overhead figure says what its method cannot see.**
      `overhead()` gains `ringFrames` — how many frames the client's own `Profile` trees retain — built in
      `ProfHandle.overhead()` beside the tier rows. `attribution.md` gains the key in its table and, in the
      *two numbers* passage, the sentence the surface was missing: a control frame disarms the probes and
      leaves everything the profiler **holds** exactly where it was, so a cost that lives in retention
      stands on both sides of the subtraction and cancels to zero. The measured method sees what profiling
      *spends* and never what it *keeps*, and `withinBudget` is a claim about the first alone. Written as
      the method's boundary rather than as a caveat: a reader deciding whether to leave profiling armed is
      the reader who needs it.
      *Its suite* asserts `ringFrames` is a positive number while armed, and that `overhead()` answers an
      **empty table** while disarmed — the armed-only rule the page already states, which this key must not
      be the first to break. It asserts `budget` and `withinBudget` still answer while armed, and that
      `ringFrames` is **absent** rather than `0` from the disarmed table, which is the page's own "an absent
      key means not measured, never zero" rule applied to the key this task adds.

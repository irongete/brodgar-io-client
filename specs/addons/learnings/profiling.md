# Learnings — profiling (`io.brodgar.prof`, `hafen.client:profiling()`)

> The client's own frame instrumentation, what it will and will not tell you, and the hot-path rules
> the read surface has to respect. Feature `019-profiling`.

- **(019.2) The client already measures everything — 019 reads, it does not instrument.** `UILoop` builds
  `uprof` (UI thread), `rprof` (render thread) and `gprof` (GL timestamps) every frame while `UILoop.profile`
  is set. The whole handoff is one call at the end of `framedone` passing *finished* parts out; nothing is
  re-timed. Corollary: whatever the client does not already name (the 3D scene) cannot be reported until a
  boundary is added for it.
- **(019.2) `rprof.last()` is the last COMPLETED render frame, so it lags the UI frame by ~1.** The render
  profile closes a frame on the *next* frame's fence. Folding it into the current slot is the honest thing to
  do (the alternative is re-timing = a second source of truth), but it must be said out loud in the docs, or
  the `render` group reads as if it belonged to the frame it is filed under.
- **(019.2) GPU parts arrive several frames late — fold by frame NUMBER, and never read the newest slot.**
  This is the trap the first build fell into: `frame().gpuMs` was always 0 because the newest sample is
  precisely the one whose fence has *not* come back. Two rules that fix it: queue late GPU writes into a FIFO
  keyed by frame number (dropping any whose slot the ring already wrapped past), and have the read verb walk
  *back* to the newest slot that actually resolved, reporting which frame that was.
- **(019.2) `dwait` appears TWICE in one `uprof` frame** (once around the tick, once around the sync wait), so
  a phase fold must `+=` into its bucket, not `=`. Any part-name fold over `Profile.Part.sub()` needs the same
  care — the names are not unique per frame.
- **(019.2) `fps`, `uidle` and `framelag` are private fields of `UILoop`.** Pass them as arguments to the
  handoff; widening them would make the frame loop's own stats writable from outside for nothing. `uidle` is a
  fraction `0..1` and `framelag` is *seconds* (the HUD multiplies by 1000) — convert once, at the boundary.
- **(019.2) The off-path must be one static-volatile read.** `Prof.on` is a plain `static volatile boolean`, not
  a `Config.Variable` (a deref plus an unbox); the `framedone` call site tests it *before* touching anything so
  the JIT can hoist it. The ring is preallocated primitive arrays — a per-frame allocation in a profiler is a
  measurement of itself.
- **(019.2) `Profile.Part` is the right type to pass a late GPU frame as.** Typing the FIFO `Profile.Part`
  rather than the concrete GPU frame made late arrival reproducible in `jshell` — ring wrap, double-`dwait`,
  the `ui` roll-up, p95 and "late GPU folded into its own slot" are all headless checks, and they caught the
  ordering bugs before the first in-game restart.
- **(019.2) Arming is next-frame, and this leaks into every verb.** `UILoop.Frame` samples `profile.get()` in
  its constructor, so the frame during which the switch flips has no tree and the first sample lands on the
  *second* frame. Off (and for that one frame) the Lua verbs answer an **empty table**, never `nil`, so addon
  code never needs a branch.

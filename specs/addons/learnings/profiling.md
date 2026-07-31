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
- **(019.3) Every counter the `:stats on` HUD shows already exists — only the formatting is new.** `Connection.Stats`,
  `Loader`, `Defer`, `InstanceList`, `RenderTree`, `GLDrawList`, `GLEnvironment` each keep private counters and a
  `stats()`/`memstats()` method that bakes them into an abbreviated string. Adding a structured getter *beside* the
  string (never touching it) is the whole job: no new counting, nothing to arm, one source of truth with the HUD, and
  the acceptance test writes itself — compare item by item with `:stats on`.
- **(019.3) Return a consistent counter GROUP in one call, under the one lock the class already takes.**
  `Loader.statcounts()` / `Defer.statcounts()` return `int[]{queue, loading, busy, pool}` inside the same
  `synchronized(queue)` that `stats()` uses. Four separate getters would let a caller see a queue that emptied
  between reads as "queued 0, busy 0" while the work is still in flight. The array allocation is snapshot-time
  (when an addon asks), never per frame.
- **(019.3) `MapView`'s render objects live on `PView`, not `MapView`.** `instancer` (an `InstanceList`) and `back`
  (the `DrawList`) are `protected` fields of `PView` — the accessors go there (`PView.instancer()`/`drawlist()`),
  and both can be **null** before the first draw builds the environment-bound lists. The scene tree is `mv.tree`
  (`RenderTree.nleaves()/nslots()`).
- **(019.3) `DrawList` is an interface — give the count a default, and make "cannot count" negative.**
  `drawslots()` defaults to `-1` on the interface and only `GLDrawList` overrides it (`btsubsize(root)`). The Lua
  layer turns the negative into an **absent key**, which is the D-050 rule applied to a capability gap rather than
  a timing one; a `0` would read as "no draw calls this frame".
- **(019.3) `GLEnvironment.MemStats` is package-private, so hand out pool NAMES, not the enum.**
  `mempools()` returns the lowercased enum names as `String[]` and `memobjects(i)`/`membytes(i)` index by ordinal —
  which is exactly the shape a Lua table keyed `indices`/`vertices`/`textures`/`vaos`/`fbos` wants. Guard the whole
  block on `env instanceof GLEnvironment`: a non-GL environment gets no `vram`/`programs` keys at all.
- **(019.3) `UILoop.framealloc` is an EWMA advanced only by `statlines()`, i.e. only while `:stats on` is drawn.**
  Making it `private static volatile` + a reader keeps one source of truth with the HUD's `Mem:` line, at the price
  of the value being **absent** until the HUD has run once. That price is the right one: computing the estimate on
  the frame loop would put a `Runtime.freeMemory()` call on every frame whether profiling is armed or not.
- **(019.3) `Connection.Stats.srtt`/`rttv` are in SECONDS** (the HUD multiplies by 1000). Convert at the Lua
  boundary — the rest of the profiling surface reports time in **ms**, and mixing units inside one handle is the
  kind of thing nobody re-reads the docs for. Name the resend counters for what they mean, not what the HUD letters
  say: `pretx`→`resentTx`, `prerx`→`resentRx` (received twice), `prorx`→`reorderedRx` (out of order).
- **(019.4) Close the addon frame where `tickLuaNanos` is ALREADY whole — the top of the next tick.**
  It accrues through the tick *and* the draw callbacks after it, so end-of-tick is too early and end-of-frame
  needs a second hook; the instant before `AddonManager.tick` zeroes it, it holds exactly one frame. Snapshot
  there (`profRoll()`) and `p:addons().total` is *identically* `p:frame().addons` with no reconciliation — both
  read one number. Corollary for demos: a reader running later (a timer, an `OnUpdate` a few frames on) sees the
  **next** frame's row, where nothing happened — per-frame `ms`/`calls` read 0 and the cost is in `msPeak`/
  `msAvg`. A dump on a 0.5 s timer showing `scopes{spin=0.0ms/0}` is correct, not a bug; print peak/avg too.
- **(019.4) Splitting an existing measurement beats adding one — and keep the original byte-for-byte.**
  The category split rides inside `callLua`'s existing `finally`, so the D-018 watchdog sees an unchanged
  `tickLuaNanos` and `hogtest` still trips on the identical message and tick count (the regression to check
  after ANY edit to that path). Make the category argument **mandatory**: a defaulted overload means the next
  call site silently lands in the wrong bucket, and there is nothing to grep for afterwards.

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
- **(019.5) The widget tree has exactly TWO traversal seams — probe those, not the widgets.**
  Draw recurses through `Widget.draw(GOut,boolean)`'s child loop; tick recurses through
  `Widget.Event.dispatch` (which `TickEvent` overrides), and *every* widget goes through them, including
  ones that override `tick`/`draw` without calling `super`. Bracketing the two loops gives each child's
  **inclusive** subtree time for free. The one widget neither seam covers is the **root** — its parent is
  `UI.draw`/`UI.dispatch`, not a widget — and it is exactly the total everything reconciles against, so
  `UI.draw` needs its own bracket or the whole-tree number is missing.
- **(019.5) Measure inclusive, derive self — and the sums then telescope exactly.** Store each widget's
  inclusive time *and* the sum of its children's inclusive time; `self = incl - child` at snapshot time.
  Because every non-root widget's inclusive time appears once positively (its own row) and once negatively
  (its parent's child sum), the self times sum to the root's inclusive **to the last digit** — the in-game
  check printed 4.5821 ms of rows against 4.5821 ms of root. That equality is the acceptance test for the
  probe placement: a missing seam shows up as a gap, not as a rounding difference.
- **(019.5) Frame-stamp the accumulator instead of sweeping it.** There is no registry of live widgets and
  building one per frame would cost more than the probe. Put a frame counter in slot 0 of the `long[]` and
  zero the array on the first write of a new frame; the read verb then ignores anything older than the
  previous frame. A closed window's rows disappear for free, and no teardown path has to remember anything.
- **(019.5) `utick` is bigger than the tick tree, and `draw` is much bigger than the draw tree.** `utick`
  also holds `gtick`, the hover query and resize; the `draw` phase holds the entire 3D scene, of which the
  `MapView` widget row is only the submission share (~0.3–0.5 ms against a multi-ms phase). Say so in the
  docs: a user who expects the per-widget table to add up to the phase will report a bug that is not one.
- **(019.5) The self/inclusive split is what makes the table readable.** Leaves show `self == inclusive`
  (`LuaWidget`, `WItem`), containers do not (`DefaultDeco` 0.1633 self vs 0.1711 inclusive) — that contrast
  is also the quickest sanity check that the child subtraction is landing on the right widgets. And
  `AddonRoot` reading `tick 0.13 / draw 0.0` is correct, not a gap: it is ticked but never drawn.
- **(019.6) `GPUProfile.Part.part()` CLOSES the previous sibling — so a new pass must nest, never sit beside.**
  Opening a part emits one timestamp that both starts the new part *and* finishes the chain of the parent's
  previous child. Adding `shadow`/`scene`/`ui2d` as siblings of the frame's `tick`/`draw`/`swap` would
  therefore have truncated `draw` and changed what `Profwnd` shows. Capture the frame's `draw` part in
  `UILoop.Frame.display` and hang the passes under **it**: the client's tree is untouched and gains three
  nested rows. `Part.fin(out)` is the explicit close (safe to leave a gap after it — a later sibling's
  `tfin()` on an already-`fin` part is a no-op).
- **(019.6) A pass left open stalls EVERY later frame's GPU timing.** `GPUProfile.check()` drains `waiting`
  in order and returns on the first part that is not `done`, so one unclosed part blocks the queue forever —
  no more `gpuMs`, no more passes, silently. Bracket every seam in `try`/`finally`; guard `begin` against
  double-open and `end` against a close with no open.
- **(019.6) Nesting in the CODE forces self time in the REPORT.** `shadow` and `scene` run inside the widget
  draw because the `MapView` *is* a widget, so inclusive rows would count the scene twice and sum above the
  frame. Subtracting the nested passes (the D-053 trick again, applied to a part tree instead of a widget
  tree) is what makes `ui2d` mean the 2D UI and the three rows disjoint — verified at 2.962 ms of rows
  against a 3.172 ms GPU frame.
- **(019.6) "What do shadows cost" is TWO costs, and only naming passes separates them.** Shadows off →
  `shadow` 0.273 → 0 ms, but the GPU frame drops 1.06 ms: the rest is `scene` (2.077 → 1.317), whose shaders
  stop sampling the shadow map. Draw calls halve too (1387 → 887) — the shadow map is a full depth-only
  second pass over the geometry. Expect the frame delta to exceed the pass row; that is the answer being
  decomposed, not a discrepancy.
- **(019.6) The CPU and GPU columns of a pass measure different things.** CPU is the time spent *recording*
  GL commands, so `shadow`/`scene` read ~0.04 ms while `ui2d` reads ~3 ms of real widget work; the GPU column
  is when the driver did it. Say so in the docs or the CPU column reads as a bug.
- **(019.6) Count GL submissions at the per-frame DISPATCH seams, not where the GL calls are written.**
  `GLDrawList.SlotRender.draw` and `GLProgram.apply` run at slot *compile* time (rarely), and `BufferBGL`'s
  replay is the render thread's innermost loop (too hot). The per-frame truth is `GLDrawList.draw`'s walk of
  the sorted slot list — one draw call per slot, one program bind wherever `slot.prog` changes — plus
  `GLRender.draw` and `Applier`'s two `GLProgram.apply` sites for the immediate path. Precompute per-slot
  vertex/triangle counts at compile time so the frame walk is an add, and hoist `Prof.on` into a local so the
  disarmed cost is one branch per frame rather than per slot.
- **(019.7) Two running means will not measure a sub-percent overhead — pair and take medians.** Comparing
  the mean work time of armed frames against control frames reported the overhead as **−1.05 ms** on a live
  session: frame work time is spiky (GC, a resource landing, a window opening) and the signal is a fraction
  of a percent of it, so the answer was pure drift between two samples 63:1 apart in count. What works: one
  delta **per period** (median work of that period's armed frames minus its control frame), and the median of
  those deltas. Pairing inside a period kills drift, the medians kill spikes. Verified on synthetic spiky
  data — planted 0 ms reads unresolved, 0.5 ms → +0.536, 2.0 ms → +2.18.
- **(019.7) A measurement must clear its own error bar before it beats a model.** A median at +0.004 ms with
  a ±0.13 ms bar has measured nothing; letting it supersede the calibrated model replaces a rough number with
  a random one. Gate on `median > spread/√n`, report both, and say which one the total used. For a feature
  whose claim is that it costs almost nothing, "unresolvable" is the expected outcome, not a failure.
- **(019.7) Compare WORK time, not frame time — a frame cap hides everything.** Under vsync or an fps limit
  the client absorbs extra cost by idling less, so total frame time is pinned to the cap and shows a delta of
  zero no matter what the probes cost. Subtract the `wait` and `dwait` phases. The same trap bites the manual
  A/B: at a 144 fps cap, the pre-019 build, 019-off and 019-on **all** read 144 — and the idle share drifted
  ±6 points between runs with 019-**on** measuring *less* work than 019-off, which is impossible and so is
  proof that run-to-run noise (~0.9 ms) dwarfs the effect (0.038 ms). Uncap the framerate before A/B-ing FPS.
- **(019.7) A calibration loop cannot resolve an accumulate.** `arr[c] += d; cnt[c]++` over a few indices is
  something HotSpot keeps in registers, so the loop measures at or below an empty one and the unit calibrates
  to **zero** — which silently makes that tier read as *free* and gives it zero weight when a measured total
  is attributed. Floor the unit (1 ns) and document it. A `nanoTime` pair calibrates fine (~36 ns) and errs
  high, because the calibration runs cold while the real probes run JIT-compiled — the right direction.
- **(019.7) Do not withhold the client's OWN instrumentation from a control frame.** `gprof.part(out,"draw")`
  looks like part of the 019.6 pass code but predates it: skipping it left the client's GPU tree missing its
  `draw` part 1 frame in 64, i.e. corrupting `Profwnd`'s data to measure ours. Create the client's part
  always; withhold only the *parent handle* the fork's passes hang from. Check with `git diff <pre-feature>`
  before assuming a line at a seam you edited is yours.
- **(019.7) Splitting a hot switch in two is cheaper than it looks, but every reader must be re-triaged.**
  `Prof.on` became per-frame (false on a control frame) and `Prof.sampling` the master. Every probe wants
  `on`; the checkbox, the Lua `armed()` reads and the end-of-frame handoff want `sampling` — a probe reading
  `sampling` would defeat the control frame, and a UI reader reading `on` would flicker at 1/64. Grep every
  use before flipping the meaning of an existing field.
- **(019.8) A periodic frame spike is a GC pause until proven otherwise, and this surface can prove it in two
  minutes.** A ~70 ms hitch every ~2 s at 100+ fps: `p:history()` scrubbed to the spike showed the whole cost in
  **`utick` (64 ms vs 0.97 normal)** with `draw` and `gpuMs` unchanged — i.e. a stalled thread, not work — and
  `p:memory()` in the COUNTERS tab showed the heap sawtoothing 600 MB → 1800 MB → collect, in lockstep. Order of
  elimination: which phase (a stall lands in one and leaves the others untouched) → `gcCount`/`gcMs` deltas →
  heap sawtooth. Then the lever is the JVM, not the client: the default G1 with the default max heap (a quarter
  of RAM — 16 GB here) collects rarely and long; a **fixed** small heap (`-Xms4g -Xmx4g -XX:+AlwaysPreTouch`)
  or ZGC removes the stall without touching a line of code. It does NOT remove the cause: the client's own
  `allocPerFrame` estimate read **~11 MB/frame**, and reading the widget layer accounts for well under 1 MB of
  it (see `specs/codebase/widgets.md`) — so any allocation work must be MEASURED first, per phase/widget/addon,
  the way time already is. `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()` is a TLAB counter read
  (~20 ns) and fits the seams 019 already brackets: that is the shape of the follow-up feature, not a renderer
  rewrite guessed at from a code read.

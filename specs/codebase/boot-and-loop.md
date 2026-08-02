# Subsystem: boot, frame loop & threading

> `file:line` anchors into the client's startup path, the per-frame loop and the locking rules.
> Line numbers are indicative (upstream merges shift them); the **class + method/field name is
> the stable anchor**. Max 60 lines.

## Boot / entry / init

| What | Where |
|---|---|
| `main()` → spawns "Haven main thread" | [`Client.main`](src/haven/Client.java:397) → [`main2`](src/haven/Client.java:363) |
| Resource setup (global init point, no session) | [`Client.setupres`](src/haven/Client.java:280), called at `main2` (~:367) |
| Runner state machine (`task.run(newui(task))`) | [`Client.run`](src/haven/Client.java:212) |
| Login → session establishment → returns `RemoteUI` | [`Bootstrap.run`](src/haven/Bootstrap.java:164), `Session.connect` (~:293) |
| **Per-session init (`ui.sess` bound)** ← engine attach point | [`RemoteUI.init(UI)`](src/haven/RemoteUI.java:147) |
| In-game HUD construction | [`GameUI` ctor](src/haven/GameUI.java:257) (`GameUI(String chrid, long plid, String genus)`) |

## Frame / tick loop

| What | Where |
|---|---|
| Render+tick thread ("Haven UI thread") | [`UILoop`](src/haven/UILoop.java) (thread created ~:58) |
| **Per-frame tick (holds `synchronized(ui)`)** | [`UILoop.Frame.tick`](src/haven/UILoop.java:433) |
| Game-state tick | `glob.ctick()` at [UILoop.java:442](src/haven/UILoop.java:442) → [`Glob.ctick`](src/haven/Glob.java:143) |
| **Widget-tree tick broadcast** ← engine `OnUpdate` point | `ui.tick()` at [UILoop.java:445](src/haven/UILoop.java:445) → [`UI.tick`](src/haven/UI.java:371) → `TickEvent` → [`Widget.tick`](src/haven/Widget.java:748) |
| Draw + one-shot after-draws | [`UI.draw`](src/haven/UI.java:386) (afterdraws cleared at [:391](src/haven/UI.java:391)) |
| Register a one-shot overlay | [`UI.drawafter`](src/haven/UI.java:365) |

## Profiling & stats (the client's own)

| What | Where |
|---|---|
| The two switches: `:stats on\|off` and `:profile on\|off` | [`UILoop.dbtext`](src/haven/UILoop.java:39) / [`UILoop.profile`](src/haven/UILoop.java:40) — `Config.Variable<Boolean>`, set by the console commands (~:603) |
| Per-frame CPU trees (UI + render thread) | [`UILoop.uprof`/`rprof`](src/haven/UILoop.java:43) — [`CPUProfile`](src/haven/CPUProfile.java), 300-frame ring |
| GPU frame time (GL timestamp queries) | [`UILoop.gprof`](src/haven/UILoop.java:44) — [`GPUProfile.part(Render, nm)`](src/haven/GPUProfile.java:64) inserts a named query |
| **Where a frame decides to profile at all** | [`UILoop.Frame` ctor](src/haven/UILoop.java:502) — reads `profile.get()` **once**, per frame |
| The phase names | `CPUProfile.phase(prof, …)` in [`Frame.tick`](src/haven/UILoop.java:433): `dwait`, `stick`, `utick`, `draw`, `swap`, `wait`, `aux` |
| Ring / part arithmetic (`f()`/`t()`/`d()`/`sub()`, `last()`, `copy()`) | [`Profile`](src/haven/Profile.java:34) |
| Scoped CPU sections (the `ProfilerMarker` primitive) | [`CPUProfile.set(Part)`](src/haven/CPUProfile.java:104) / `phase` / `end`; the `Current` ThreadLocal is `null` when off, so `begin` returns immediately |
| The HUD text (`:stats on`) | [`UILoop.statlines`](src/haven/UILoop.java:233), drawn at ~:291 — the one place that computes `framealloc` (an EWMA from `prevfree`), so that number **only advances while the HUD is drawn**; also the only reader of `Loader.stats()`/`Defer.gstats()`/`Resource.qdepth()`/`numloaded()`/`GLEnvironment.memstats()`/`MapView.stats()` |
| Async pool internals (the `Async:` line) | [`Loader.stats`](src/haven/Loader.java:245) = `queue.size()+loading.size() busy/pool`, [`Defer.stats`](src/haven/Defer.java:314) = `queue busy/pool`; both under `synchronized(queue)`. Fork adds `statcounts()` / `Defer.gstatcounts()` returning the group as an `int[]` **under that one lock** (per-field getters would be mutually inconsistent) |
| The live tree windows | [`Profwnd`](src/haven/Profwnd.java:31) |
| **Master switch (fork)** | [`io.brodgar.prof.Prof.arm`](src/io/brodgar/prof/Prof.java) writes the hot-path field, the pref **and** `UILoop.profile`; `:profile` routes through it (D-049) |
| **End-of-frame hook (fork)** | [`UILoop.framedone`](src/haven/UILoop.java:416) — after `updstats(f)`; hands `uprof.last()`, `rprof.last()`, `f.gprof` + `fps`/`uidle`/`framelag` to `Prof.frame` (019.2) |
| **The frame loop itself / start-of-frame hook (fork)** | [`UILoop.run`](src/haven/UILoop.java:588) — the `while(true)` that does `frame(ui, buf, prevframe)` → `run()` → `fin()` → `framedone()`. `io.brodgar.prof.Prof.begin()` sits immediately **before** the `Frame` is built ([:610](src/haven/UILoop.java:610)): every per-frame arming decision must be made there, because everything the probes hang off (the profile objects, the pass tree's parent part) is created in the `Frame` constructor (019.7) |
| Where a frame's parts finish / frame identity | [`Frame.fin`](src/haven/UILoop.java:481) (the GL fence sets `framelag`), `f.frameno`/`f.ftime` ([:439](src/haven/UILoop.java:439)) |
| The FPS/idle math the HUD shows | [`UILoop.updstats`](src/haven/UILoop.java:398) — `fps`, `uidle` (fraction 0..1), `framelag` (**seconds**), all `private` |
| Render-thread parts | `rprof` parts `tick`/`draw`/`swap`/`finish`; `rprof.last()` is the last **completed** frame, ~1 behind the UI frame |

**Gotchas.** (a) `:profile on` builds the trees but **opens nothing** — the `Profwnd` windows appear only when
you then press the **backtick** key, and only while the flag is set ([`RootWidget.globtype`](src/haven/RootWidget.java:57)).
(b) Because the `Frame` constructor samples the flag, **arming takes effect on the next frame** — the frame
during which you flip it has no tree. (c) GPU parts arrive **late** (GL fences), so frame X's GPU time may land
several frames after its CPU time — fold by frame number, never by position, and never read a GPU number off the
**newest** frame (it is precisely the one still in flight). (d) `fps`/`uidle`/`framelag` are **private** to
`UILoop`: pass them out, do not widen the fields. (e) A part name is **not unique within a frame** — `dwait` is
entered twice (tick + syncwait), so any fold over `Part.sub()` must `+=`. (f) `framedone` runs **after**
[`CPUProfile.end(prof)`](src/haven/UILoop.java:527) closed the frame in `Frame.fin`, so work done in the
end-of-frame hook lands in **no phase at all** — it is invisible to `uprof`, to `:stats on` and to any
frame-time comparison, while still delaying the next frame. Time it directly or it does not exist (019.7).

## Threading & locks

| Concern | Rule / where |
|---|---|
| Widget tree + event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`); writers: UI thread + Loader threads |
| Deferred server widget ops | [`UI.CommandQueue`](src/haven/UI.java:245); executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker ([`OCache.receive`](src/haven/OCache.java:514)); apply on Loader ([`GobInfo.apply`](src/haven/OCache.java:370)); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| `Loading` exception protocol | [`Loader.Future.run`](src/haven/Loader.java:76); any code touching resources/gobs/grids must tolerate it |
| **GPU part nesting + late arrival** | [`GPUProfile.Part.part(Render,nm)`](src/haven/GPUProfile.java:64) hangs the new part **under** the one it is called on and emits **one** timestamp that also closes the parent's previous child (`tfin()`), so opening a sibling **truncates** the part before it — nest, never sit beside. [`Part.fin(out)`](src/haven/GPUProfile.java:83) is the explicit close (idempotent; a later sibling's `tfin()` on a `fin` part is a no-op). [`GPUProfile.check()`](src/haven/GPUProfile.java:119) drains `waiting` **in order** and returns on the first part that is not `done` ⇒ **one unclosed part blocks every later frame's GPU timing, silently**. Fork: [`io.brodgar.prof.Passes`](src/io/brodgar/prof/Passes.java) hangs the named passes under the frame's `draw` part, captured in [`Frame.display`](src/haven/UILoop.java:480), every seam in `try`/`finally` |

## Building a `UI` headlessly (029.1)

The whole widget tree becomes unit-testable once a real `UI` exists off-screen — `hasparent(ui.root)` liveness,
interning, staleness and GC/no-pin proofs all need `ui.root` to hang widgets off. Two things block it:

| Blocker | Where | Fix |
|---|---|---|
| `Text.<clinit>` does a blocking `loadwait("ui/fraktur")` | [`Text`](src/haven/Text.java:40) ← [`ConsoleHost.<clinit>`](src/haven/ConsoleHost.java:33) ← `RootWidget` ← [`UI.<init>`](src/haven/UI.java:178) | put **`bin/builtin-res.jar`** on the classpath — the local pool is a `JarSource("res")` ([`Resource.local`](src/haven/Resource.java:850)), so no network is needed |
| `new ActAudio.Root(sys)` NPEs on a null `Audio.Root` | [`ActAudio.Root`](src/haven/ActAudio.java:176) reads `sys.mixer` | `Unsafe.allocateInstance(Audio.Root.class)` (the real ctor opens a sink line) + reflect a `new Audio.Mixer(true)` into its final `mixer` field |
| toolkit/scale probe at `UI.<clinit>` | [`UI.initscale`](src/haven/UI.java:1063) | add the jogl/lwjgl jars from `build/*.jar`; the `Unavailable` traces it prints are **caught** — not a failure |

Then `UI u = new UI(null, ar, Coord.of(800, 600), null)`; `u.root` is a live `RootWidget`, `u.root.add(w)` and
`w.destroy()` behave exactly as in-game, and `haven.Widget` overrides neither `equals` nor `hashCode` (so it is
safe as an identity map key). The bridge's `AddonManager.ui` is package-private ⇒ a test class declared
`package io.brodgar.addon` can set it directly; `Addon`'s constructor is package-private too and takes
`(Manifest, Path, Globals)` — `null, Paths.get("."), null` is enough for any cache-level test.

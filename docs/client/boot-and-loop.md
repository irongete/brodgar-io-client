# Boot, the frame loop and threading

> Covers the client's startup path, the per-frame loop and the locking rules.
## Boot, entry and init

| What | Where |
|---|---|
| `main()` → spawns "Haven main thread" | `Client.main` → `main2` |
| Resource setup (global init point, no session) | `Client.setupres`, called at `main2` |
| Runner state machine (`task.run(newui(task))`) | `Client.run` |
| Login → session establishment → returns `RemoteUI` | `Bootstrap.run`, `Session.connect` |
| **Per-session init (`ui.sess` bound)** ← engine attach point | `RemoteUI.init(UI)` |
| In-game HUD construction | `GameUI` ctor (`GameUI(String chrid, long plid, String genus)`) |

## Frame, tick loop

| What | Where |
|---|---|
| Render+tick thread ("Haven UI thread") | `UILoop` (thread created) |
| **Per-frame tick (holds `synchronized(ui)`)** | `UILoop.Frame.tick` |
| Game-state tick | `glob.ctick()` at `UILoop.java` → `Glob.ctick` |
| **Widget-tree tick broadcast** ← per-frame update seam | `ui.tick()` at `UILoop.java` → `UI.tick` → `TickEvent` → `Widget.tick` |
| Draw + one-shot after-draws | `UI.draw` (afterdraws cleared at) |
| Register a one-shot overlay | `UI.drawafter` |

## Profiling and stats (the client's own)

| What | Where |
|---|---|
| The two switches: `:stats on\|off` and `:profile on\|off` | `UILoop.dbtext` / `UILoop.profile` — `Config.Variable<Boolean>`, set by the console commands |
| Per-frame CPU trees (UI + render thread) | `UILoop.uprof`/`rprof` — `CPUProfile`, 300-frame ring |
| GPU frame time (GL timestamp queries) | `UILoop.gprof` — `GPUProfile.part(Render, nm)` inserts a named query |
| **Where a frame decides to profile at all** | `UILoop.Frame` ctor — reads `profile.get()` **once**, per frame |
| The phase names | `CPUProfile.phase(prof, …)`, in the order a frame enters them: `dwait` (`Frame.syncwait`, and again at the head of `Frame.tick`), `stick`, `utick`, `sessions` (`Frame.tick`), `draw` (`Frame.display`), `aux` (`Frame.run`, around `swapbuffers`), `wait` (`Frame.fin`). `sessions` is the one every non-drawn session is ticked under, so it is the phase a second character shows up in ([multi-session.md](multi-session.md)) |
| Ring / part arithmetic (`f()`/`t()`/`d()`/`sub()`, `last()`, `copy()`) | `Profile` |
| Scoped CPU sections | `CPUProfile.set(Part)` / `phase` / `end`; the `Current` ThreadLocal is `null` when off, so `begin` returns immediately |
| The HUD text (`:stats on`) | `UILoop.statlines`, drawn at — the one place that computes `framealloc` (an EWMA from `prevfree`), so that number **only advances while the HUD is drawn**; also the only reader of `Loader.stats()`/`Defer.gstats()`/`Resource.qdepth()`/`numloaded()`/`GLEnvironment.memstats()`/`MapView.stats()` |
| Async pool internals (the `Async:` line) | `Loader.stats` = `queue.size()+loading.size() busy/pool`, `Defer.stats` = `queue busy/pool`; both under `synchronized(queue)`. Fork adds `statcounts()` / `Defer.gstatcounts()` returning the group as an `int[]` **under that one lock** (per-field getters would be mutually inconsistent) |
| The live tree windows | `Profwnd` |
| **The arming flag** | `UILoop.profile` — anything switching profiling on must write this field, which `:profile` does |
| **End-of-frame hook (fork)** | `UILoop.framedone` — after `updstats(f)`; hands `uprof.last()`, `rprof.last()`, `f.gprof` + `fps`/`uidle`/`framelag` to `Prof.frame` |
| **The frame loop, and the start-of-frame seam** (`// addon:`) | `UILoop.run` — the `while(true)` that does `frame(ui, buf, prevframe)` → `run()` → `fin()` → `framedone()`. The seam sits immediately **before** the `Frame` is built, and every per-frame arming decision must be made there: everything a probe hangs off (the profile objects, the pass tree's parent part) is created in the `Frame` constructor |
| Where a frame's parts finish / frame identity | `Frame.fin` (the GL fence sets `framelag`), `f.frameno`/`f.ftime` |
| The FPS/idle math the HUD shows | `UILoop.updstats` — `fps`, `uidle` (fraction 0..1), `framelag` (**seconds**), all `private` |
| Render-thread parts | `rprof` parts `tick`/`draw`/`swap`/`finish`; `rprof.last()` is the last **completed** frame, ~1 behind the UI frame |

**Gotchas.** (a) `:profile on` builds the trees but **opens nothing** — the `Profwnd` windows appear only when
you then press the **backtick** key, and only while the flag is set (`RootWidget.globtype`).
(b) Because the `Frame` constructor samples the flag, **arming takes effect on the next frame** — the frame
during which you flip it has no tree. (c) GPU parts arrive **late** (GL fences), so frame X's GPU time may land
several frames after its CPU time — fold by frame number, never by position, and never read a GPU number off the
**newest** frame (it is precisely the one still in flight). (d) `fps`/`uidle`/`framelag` are **private** to
`UILoop`: pass them out, do not widen the fields. (e) A part name is **not unique within a frame** — `dwait` is
entered twice (tick + syncwait), so any fold over `Part.sub()` must `+=`. (f) `framedone` runs **after**
`CPUProfile.end(prof)` closed the frame in `Frame.fin`, so work done in the
end-of-frame hook lands in **no phase at all** — it is invisible to `uprof`, to `:stats on` and to any
frame-time comparison, while still delaying the next frame. Time it directly or it does not exist.

## Threading and locks

| Concern | Rule / where |
|---|---|
| Widget tree + event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`); writers: UI thread + Loader threads |
| Deferred server widget ops | `UI.CommandQueue`; executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker (`OCache.receive`); apply on Loader (`GobInfo.apply`); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| `Loading` exception protocol | `Loader.Future.run`; any code touching resources/gobs/grids must tolerate it |
| **`Defer`'s pool is small, and a worker must never wait on another task** | `Defer.maxthreads` is `max(2, cores - 1)`; a `Worker` exits after 5 s idle and `Defer.later` from inside a worker enqueues onto that **same** group (`getgroup()` reads the thread group). So a task that blocks on a second task's `Future` can consume the whole pool. `Future.get(0)` throws `Defer.NotDoneException` (a `Loading`) rather than waiting — treat that as *ask again next pass* and return, never as something to sleep on |
| **GPU part nesting + late arrival** | `GPUProfile.Part.part(Render,nm)` hangs the new part **under** the one it is called on and emits **one** timestamp that also closes the parent's previous child (`tfin()`), so opening a sibling **truncates** the part before it — nest, never sit beside. `Part.fin(out)` is the explicit close (idempotent; a later sibling's `tfin()` on a `fin` part is a no-op). `GPUProfile.check()` drains `waiting` **in order** and returns on the first part that is not `done` ⇒ **one unclosed part blocks every later frame's GPU timing, silently**. A consumer hangs its named passes under the frame's `draw` part, captured in `Frame.display`, every seam in `try`/`finally` |

## Building a `UI` headlessly

The whole widget tree becomes unit-testable once a real `UI` exists off-screen — `hasparent(ui.root)` liveness,
interning, staleness and GC/no-pin proofs all need `ui.root` to hang widgets off. Two things block it:

| Blocker | Where | Fix |
|---|---|---|
| `Text.<clinit>` does a blocking `loadwait("ui/fraktur")` | `Text` ← `ConsoleHost.<clinit>` ← `RootWidget` ← `UI.<init>` | put **`bin/builtin-res.jar`** on the classpath — the local pool is a `JarSource("res")` (`Resource.local`), so no network is needed |
| `new ActAudio.Root(sys)` NPEs on a null `Audio.Root` | `ActAudio.Root` reads `sys.mixer` | `Unsafe.allocateInstance(Audio.Root.class)` (the real ctor opens a sink line) + reflect a `new Audio.Mixer(true)` into its final `mixer` field |
| toolkit/scale probe at `UI.<clinit>` | `UI.initscale` | add the jogl/lwjgl jars from `build/*.jar`; the `Unavailable` traces it prints are **caught** — not a failure |

Then `UI u = new UI(null, ar, Coord.of(800, 600), null)`; `u.root` is a live `RootWidget`, `u.root.add(w)` and
`w.destroy()` behave exactly as in-game, and `haven.Widget` overrides neither `equals` nor `hashCode`, so a
widget is safe as an identity map key.

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
| The HUD text (`:stats on`) | [`UILoop.statlines`](src/haven/UILoop.java:233), drawn at ~:291 |
| The live tree windows | [`Profwnd`](src/haven/Profwnd.java:31) |
| **Master switch (fork)** | [`io.brodgar.prof.Prof.arm`](src/io/brodgar/prof/Prof.java) writes the hot-path field, the pref **and** `UILoop.profile`; `:profile` routes through it (D-049) |
| **End-of-frame hook (fork)** | [`UILoop.framedone`](src/haven/UILoop.java:416) — after `updstats(f)`; hands `uprof.last()`, `rprof.last()`, `f.gprof` + `fps`/`uidle`/`framelag` to `Prof.frame` (019.2) |
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
entered twice (tick + syncwait), so any fold over `Part.sub()` must `+=`.

## Threading & locks

| Concern | Rule / where |
|---|---|
| Widget tree + event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`); writers: UI thread + Loader threads |
| Deferred server widget ops | [`UI.CommandQueue`](src/haven/UI.java:245); executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker ([`OCache.receive`](src/haven/OCache.java:514)); apply on Loader ([`GobInfo.apply`](src/haven/OCache.java:370)); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| `Loading` exception protocol | [`Loader.Future.run`](src/haven/Loader.java:76); any code touching resources/gobs/grids must tolerate it |

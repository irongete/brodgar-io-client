# Boot, the frame loop and threading

> Covers the client's startup path, the per-frame loop and the locking rules.
## Boot, entry and init

| What | Where |
|---|---|
| `main()` → spawns "Haven main thread" | `Client.main` → `main2` |
| Resource setup (global init point, no session) | `Client.setupres`, called at `main2`. Fork: right after it `main2` starts the addon boot's preparation (`io.brodgar.addon.BootPrepare.start()`, its own javadoc says what it does and why it is safe) and tells it when the toolkit exists (`toolkitReady()`, right after `Toolkit.instance()` returns) — the boot itself still runs on the UI thread's first frame, and the main thread's `newui` waits for that frame before the login screen can be built |
| Runner state machine (`task.run(newui(task))`) | `Client.run` |
| Login → session establishment → returns `RemoteUI` | `Bootstrap.run`, `Session.connect` |
| **The login screen's warm-up (fork)** | `LoginWarmup.start()`, from `LoginScreen`'s constructor, once per process, on a below-normal thread of its own: a `MCache(null)` holding one grid of three tilesets no server sent (`AddonWidgets.settileset`/`putgrid`, the doors 068 opened for the remembered ground), its middle cut built four times over with `MapMesh.build`, and one `RichText.render`. What that buys is measured, not guessed: the first cut mesh of a JVM is built by the interpreter — 265–563 ms of CPU for a cut that costs 16–31 ms once the JIT has seen `MapMesh`/`Surface`/`MeshBuf`/`Tiler.lay` — and the first rich-text layout initialises Java2D's shaper (150–300 ms); before this, both were paid on the critical path between the map's arrival and the first drawn frame. The tilesets it loads (`grass`, `field`, `paving/ballbrick`, their textures, `transitions-*`, `ridges/soil`) sit in the pool for the session. **And the HUD's own art**: every `gfx/hud/**` resource of the local jar (334; found through `gfx/loginscr`, the picture on screen, since the jar carries no directory entries), queued on `Resource.local()` at priority −10 — the background priority of the client's own `res-bgload` list — and never waited for, so a session's `loadwait` (priority 10) and every remote load's first hop through that pool go ahead of what is still queued. Why: a widget takes its pictures with `Resource.loadtex` in its constructor, and at login the `Loader` builds the server's widget flood under `synchronized(ui)`, so the frame waited on the monitor while `MapWnd`'s sixteen toolbar images went through the pool's two threads — 163–223 ms every login, gone with the preload (280 resources decoded in 1.5 s at the login screen, 75 MB of RGBA in the pool's soft cache, most of which the HUD holds anyway). A failure is one stderr line and a login that pays what it was meant to spare |
| **Per-session init (`ui.sess` bound)** | `RemoteUI.init(UI)`, called from `UI`'s own constructor. It binds the session to its `UI` and nothing else: what the fork hangs off a session is attached from the session layer instead, the moment that `UI` exists ([multi-session.md](multi-session.md)) |
| In-game HUD construction | `GameUI` ctor (`GameUI(String chrid, long plid, String genus)`) |
| What the client says about itself | `Utils.useragent` — a static map filled once in `Utils`'s class initialiser: `java.version`/`vendor`/`vm`, `os.name`/`arch`/`version`, `mem.heap`, `cpu.num`, and every key of the jar's **`/buildinfo`** resource as `jar.<key>` (a `Properties` file the build writes into the jar, `git-rev` among its keys). A jar without the resource leaves the `jar.*` keys out; one whose resource cannot be read is an `Error` at class load. Anything that names the build to a server reads it here rather than parsing a manifest |

## The runner state machine

A `UI.Runner` is a step: `run(UI)` returns the next step, or `null` when there is none. Two loops turn
that into the client's life, and they are not the same loop.

| What | Where |
|---|---|
| **The outer one, and the client's own lifetime** | `Client.run(UI.Runner)` — `while(task != null) task = task.run(newui(task))`, with `finally {newui(null);}`. Falling out of it is how the client **exits**, so the task it is given must never return |
| The one that never returns | `Client.Main.run` — an endless `while(true)`: build a `Bootstrap`, title the window from `Runner.title()`, run it, and go round again. It is the task `main2` hands to `Client.run` in the ordinary case |
| Building the runner's UI | `UILoop.newui(fun)` — puts the new `UI` in the `UILoop.ui` **slot**, waits on `uilock` while a frame still holds the previous one, then **destroys** that previous one |
| Building a UI that owns no slot | `UILoop.bgui(fun)` — the same construction minus the slot, the wait and the destroy. Both build **outside `uilock`**, because `UI`'s constructor runs `Runner.init` and no other lock may be taken under that one. `bgui` is overridden (`ClientLoop` adds a console directory), so the constructor itself builds through the private `UILoop.mkui` it delegates to — an override reached from a superclass constructor reads its own fields null |
| **The third UI (fork)** | `UILoop.layer` — the **addon layer**, built by that constructor and never replaced, destroyed or drawn as the slot: a `UI` with a null `sess`, so nothing in it ticks a `Glob` and no server widget can be handed to it. The frame attends it beside `drawn()` and draws it **on top**, session or login screen ([multi-session.md](multi-session.md)) |
| Which UI the frame actually draws | `UILoop.drawn()` / `drawn(UI)` — the slot is what is drawn only while nothing else claims it |
| **Where every UI dies, whoever killed it** | `UI.destroy()` — drains the command queue, then under the UI's **own** monitor destroys `root` and clears `audio`. Both doors reach it: `UILoop.newui` for the slot it replaces, and `UILoop.bgdestroy` for one built with `bgui` ([multi-session.md](multi-session.md)), which first takes the screen off it and waits out any frame still holding it on `uilock`. **That wait is released only where `lockedui` is reassigned** — the `drawn()` read at the top of `UILoop.run`'s loop, *below* `Sessions.tickview()` — so a `UI` that held the screen is never destroyed before the frame has spent the request that moves the views off it, and one that did not hold it fails the wait's test at once. **Fork adds `UI.destroyed`**, raised before the tree is disposed, so anything keyed on a UI can tell a live one from one being taken apart — and `root.destroy()` runs `Widget.remove`/`rdispose` down the whole tree, which is why the flag goes up first |
| **The seam (fork)** | `Client.Main.run` hands a `RemoteUI` to `Sessions.adopt` instead of running it, so the slot holds the **login screen** and every game session is built with `bgui` on a thread of its own ([multi-session.md](multi-session.md)) |
| The other two entries, which use neither | `Client.main2` with `haven.servargs`/`replay` set, and `HeadlessClient.main2` — both hand a bare `RemoteUI` to their own `run`, so there is no `Bootstrap` and no `Main` |

**Gotchas.** (a) `Runner.init` runs **inside `new UI(…)`**, before the constructor returns and any caller has
the reference — so what init asks about the session must be true *before* the UI is built. (b) `newui`
destroys the UI it replaces, so a UI another owner may still be using is never built through it. (c)
`Bootstrap.useinitauth` is a **static one-shot**: only the first `Bootstrap` auto-logs in from the launcher's
cookie or token; every later one shows the login screen. (d) `Bootstrap.run` blocks on its own message queue,
which only user input fills, so an undrawn login screen waits and works the moment it is drawn again.

## The way out

| What | Where |
|---|---|
| What asks for a quit | `Client.EventQueue.event` on a `Toolkit.CloseRequest`, and the `q` entry of `Client.findcmds` — both do the same one thing, `interrupt()` the thread held in `Client.mt`, and there is no other door |
| What the interrupt reaches | whichever `Runner` `Client.Main.run` is blocked in; `Client.run` catches the `InterruptedException`, so the `while(task != null)` loop is left through its own `finally` |
| The order on the way out | `Client.run` — inner `finally {newui(null);}` (which **destroys** the slot's `UI`), `savewndstate()`, then the outer `finally`: `UILoop.dispose()`. Then back in `Client.main2`: `Client.dispose()` (`Windeye.dispose`) and `System.exit(0)` |
| What `dispose` does **not** do | `UILoop.dispose` interrupts `UILoop.th` and `join`s it for 5 s, warning `"ui thread failed to terminate"` if it outlives that — and destroys **no** `UI`: `UILoop.run`'s own `finally` clears `lockedui` and nothing else. A session's `UI` is never destroyed on the way out; the process simply ends under it |
| The seam (fork) | `Client.run`'s outer `finally`, one call **before** `UILoop.dispose()`, and the same call in `HeadlessClient.run`'s. Before, because every `UI` is still alive there and the addon layer's per-character scopes can still be named |

**Gotchas.** (a) The frame loop is **still running** through all of this: nothing stops ticking or drawing
until `UILoop.dispose()` returns, so anything hung on the exit path runs beside a live UI thread and has to
take that tree's monitor — the layer's and each session's, one at a time — to wait a tick or a draw out.
(b) `System.exit(0)` at the end of `main2` is unconditional, so a thread started on the way out dies wherever
it is; nothing on the exit path may depend on one finishing. (c) `HeadlessClient.run` is the same shape with
its own `UILoop` subclass and its own `loop.dispose()` — a seam added to one exit and not the other is a seam
that is missing half the time. (d) A crash or a kill reaches **none** of this: `main2`'s `finally` runs for a
thrown exception, but nothing runs for a `SIGKILL` or a hard JVM failure.

## Frame, tick loop

| What | Where |
|---|---|
| Render+tick thread ("Haven UI thread") | `UILoop` — the thread is created in the constructor and looped in `UILoop.run`. ⚠️ **`run` catches `InterruptedException` and nothing else**: any other `RuntimeException` or `Error` out of `Frame.run` leaves the `while(true)`, runs the `finally` that clears `lockedui`, and **ends the thread** — the window stays up and nothing ticks, draws or dispatches again, and no `UI` is destroyed. Nothing restarts it and no handler is installed for it, so every seam the frame calls into has to contain its own failures, `Error` included |
| **Per-frame tick, two trees** | `UILoop.Frame.tick` — the layer under `synchronized(layer)`, then the drawn session under `synchronized(ui)`, **never both at once**. `ctick`/`gtick` run for the session alone: the layer has no `Glob` |
| Game-state tick | `glob.ctick()` at `UILoop.java` → `Glob.ctick` |
| **Widget-tree tick broadcast** ← per-frame update seam | `ui.tick()` at `UILoop.java` → `UI.tick` → `TickEvent` → `Widget.tick` |
| **Input, and who gets it first (fork)** | `UILoop.dispatch(UI layer, UI ui)` → `Client.EventQueue.dispatch` — the **layer first**, the session only for what it did not consume. A move goes to both (it is a broadcast, and `MouseMoveEvent.propagation` returns true regardless); a button's **release** goes to whichever tree took its press, or a drag begun on the world and let go over an addon window never ends; the layer is offered `UI.keydown(ev, false)`, the focused half alone, because `RootWidget.globtype` consumes every printable key with a `"gk"` message |
| Draw + one-shot after-draws | `UI.draw` (afterdraws cleared at) — `Frame.display` draws the session's tree, then the layer's over it, both inside the one `ui2d` pass |
| Which tree answers the tooltip and the cursor | the layer when it took the hover (`UI.mousehover(c, hovering)` returns that: a window under the pointer) or when a widget of its answers the tooltip query there (`UI.tooltip(c)`: a bare surface or a control outside any window carrying a tip); the session otherwise. `Frame.layerhot` carries it the few lines to `UILoop.display`. A press still falls through a layer widget no handler cancels it on |
| Register a one-shot overlay | `UI.drawafter` |

## Profiling and stats (the client's own)

| What | Where |
|---|---|
| The two switches: `:stats on\|off` and `:profile on\|off` | `UILoop.dbtext` / `UILoop.profile` — `Config.Variable<Boolean>`, set by the console commands |
| Per-frame CPU trees (UI + render thread) | `UILoop.uprof`/`rprof` — `CPUProfile`, `UILoop.histlen` frames long. Fork sets `histlen` to **16**, and it is a retention budget rather than a graph width: see the `Profile` row below |
| GPU frame time (GL timestamp queries) | `UILoop.gprof` — `GPUProfile.part(Render, nm)` inserts a named query; a `Profile` of the same `histlen`, though its late arrivals queue in `GPUProfile.waiting` rather than in the ring |
| **Where a frame decides to profile at all** | `UILoop.Frame` ctor — reads `profile.get()` **once**, per frame |
| The phase names | `CPUProfile.phase(prof, …)`, in the order a frame enters them: `dwait` (`Frame.syncwait`, and again at the head of `Frame.tick`), `ltick`, `stick`, `utick`, `sessions` (`Frame.tick`), `draw` (`Frame.display`), `aux` (`Frame.run`, around `swapbuffers`), `wait` (`Frame.fin`). `ltick` is the addon layer's own tree; `sessions` is the one every non-drawn session is ticked under, so it is the phase a second character shows up in ([multi-session.md](multi-session.md)) |
| Ring / part arithmetic (`f()`/`t()`/`d()`/`sub()`, `last()`, `copy()`) | `Profile` — `add` wraps `hist` by hand and `last()` reads the slot behind the cursor, both correct at any length ≥ 1. ⚠️ **`Profile.Part.nm` is held strongly**, and it is whatever the caller passed: for the tier `Widget.dispatch` and `Widget.draw` bracket that is **the Widget**, so each slot pins every widget the frame touched, plus a `Part` and a child list each. The ring's length is a **retention** decision before it is a display one |
| Scoped CPU sections | `CPUProfile.set(Part)` / `phase` / `end`; the `Current` ThreadLocal is `null` when off, so `begin` returns immediately |
| The HUD text (`:stats on`) | `UILoop.statlines`, drawn at — the one place that computes `framealloc` (an EWMA from `prevfree`), so that number **only advances while the HUD is drawn**; also the only reader of `Loader.stats()`/`Defer.gstats()`/`Resource.qdepth()`/`numloaded()`/`GLEnvironment.memstats()`/`MapView.stats()` |
| Async pool internals (the `Async:` line) | `Loader.stats` = `queue.size()+loading.size() busy/pool`, `Defer.stats` = `queue busy/pool`; both under `synchronized(queue)`. Fork adds `statcounts()` / `Defer.gstatcounts()` returning the group as an `int[]` **under that one lock** (per-field getters would be mutually inconsistent) |
| The live tree windows | `Profwnd`, holding one `Profdisp` — the **only** reader of `hist`, sized from `hist.length` throughout (window, texture, both draw loops, click hit-test, `dump`), so a short ring narrows the graph rather than breaking it. ⚠️ **Ten is the floor**: `Profdisp.tick` reads `order[i]` for `i` up to 9 out of an array `hist.length` long, so a shorter ring throws out of bounds there and nowhere else; its scale is the 30th-worst frame of 300 but the **2nd-worst of 16** |
| **The arming flag** | `UILoop.profile` — anything switching profiling on must write this field, which `:profile` does. What the backtick hands to `Profwnd` is a different trio: `RootWidget.guprof`/`grprof`/`ggprof`, **pushed in by `UILoop`**. ⚠️ Every UI the loop builds must be wired or the key throws `NullPointerException` out of `Profdisp`'s constructor, which reads `prof.hist` first — since the rts handoff every event follows `drawn()`, so in game the key reaches the **`bgui` session's** root and never the anchor's. Fork centralises it in `UILoop.profwire`, called from `newui` **and** `mkui` |
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

## `Loading`: parking, not retrying

A `Loading` **names the thing to wait for**. `waitfor(Runnable, Consumer<Waiting>)` throws
`Loading.UnwaitableEvent` by default; overriding it turns a throw into a **park** — `Loader.Future.run`
catches, `boostprio(1)`s it (false by default), calls `waitfor` and re-queues its own task from the callback.

| What | Where |
|---|---|
| The condition, and the in-tree precedent | `Waitable.Queue` — `add(Runnable)`/`waitfor` register a waiter, `wnotify()` runs every one and empties the queue; one notify wakes them all, so a woken task re-asks from the top. `Gob.updwait` is such a queue, `Gob.updated()` its `wnotify` and `Gob.DataLoading` the `Loading` over it; `Gob.Placed.Placement` waits on that **and** a resource at once through `Waitable.or` (`Waitable.Disjunction`) |
| Throwing one out of a render add | `RenderTree.TreeSlot.add` catches any `RuntimeException` out of `n.added(ch)`, calls `ch.remove()` and rethrows, leaving the tree as it was — `Gob.Placed.added` → `curplace()` does exactly this whenever the ground under an object is not in |

⚠️ **The check that decided to throw ran before `waitfor` registers**, so a notify landing in that gap is
lost and the task parks for ever. Re-ask inside `waitfor` under the monitor the notifier takes, and when it
is satisfied already call `reg.accept(Waitable.Waiting.dummy)` **then** the callback — `reg` always first.

## Threading and locks

| Concern | Rule / where |
|---|---|
| **Which thread is the frame's** | `UILoop.th` — public final, the `HackThread` named `"Haven UI thread"`, assigned in the `UILoop` constructor and started by `UILoop.start`. It is the one handle on "am I on the thread that ticks and draws", so code that may only run there tests against it rather than recording a thread of its own. ⚠️ `th` is assigned **last** in the constructor, after `newui` and everything hooked beside it, so anything reached from there reads it null |
| **A GPU readback answers on a third thread** | `GLEnvironment.callbacks`, a queue drained by `GLEnvironment.cbthread` — the `"Render-query callback thread"`, started on demand by `ckcbt()` and exiting after 5 s idle. Every `Render.pget`/`Clicklist.get` completion runs there: **not** the UI thread, and **not** the render thread that replays the command stream. So a callback body holds no `UI` monitor, is not ordered against the frame, and may run while the tick is midway through mutating whatever it reads — state it touches has to be published for it (`volatile`, or a snapshot the frame hands over), and taking a UI monitor from it inverts the client's one lock direction. `GLEnvironment.synccallbacks` is the barrier that waits the queue out |
| Widget tree + event dispatch | serialized by **that tree's own `UI` monitor** — `synchronized(w.ui)`, and there is one per session, not one per client ([multi-session.md](multi-session.md)). `Widget.ui` is what says which, set by `Widget.attach` down the whole subtree, so the widget in hand always carries the monitor that guards it and no ambient "the session" is ever the right answer. Writers: UI thread + Loader threads |
| Deferred server widget ops | `UI.CommandQueue`; executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker (`OCache.receive`); apply on Loader (`GobInfo.apply`); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| **`Defer`'s pool is small, and a worker must never wait on another task** | `Defer.maxthreads` is `max(2, cores - 1)`; a `Worker` exits after 5 s idle and `Defer.later` from inside a worker enqueues onto that **same** group (`getgroup()` reads the thread group). So a task that blocks on a second task's `Future` can consume the whole pool. `Future.get(0)` throws `Defer.NotDoneException` (a `Loading`) rather than waiting — treat that as *ask again next pass* and return, never as something to sleep on. Fork: `maxthreads` is `static` and package-visible, so a budget for work that runs on this pool is written as a share of it (`MapView.recallmaxbuild`) rather than by restating the formula |
| **`Defer` has an urgent lane (fork)** | A future boosted to `Defer.URGENT` (10) or above is one some thread is *blocked on* — the ground under the player at login, a texture a draw list attaches on — and the pool treats it in three ways, all in `Defer.take()` (which replaced the worker's `queue.poll()`) and `Future.boostprio`/`chstate`: **(1)** one worker of the `maxthreads` is reserved for it — ordinary work is taken only while `reserve` (1, when the pool has 4+) slots would stay free, and a future that turns urgent while queued `notifyAll`s the pool so the idle worker takes it now rather than at its one-second poll; **(2)** ordinary work does not *start* while an urgent future is running, nor while one is **outstanding** — queued, running, or thrown back by a `Loading` and waiting to be asked for again — for at most `URGENT_HOLD` (1 s), the bound being for an urgent future that cannot finish (a cut whose neighbour grid never comes), so the rest of the world still loads; **(3)** the worker running it goes to `NORM_PRIORITY + 1` for the duration, the pool's own priority being below normal. `counted` on the future is the accounting: set the moment it crosses `URGENT` while not done, cleared when it is done whichever way, and `urgentout`/`urgentsince` under `queue` are its sum. Who boosts: `Gob.getc` for the gob `Glob.plgob` names (the session's `MapView` sets it), `MapView.tick`'s camera catch, `MapRaster.Grid.tick`'s slot add and `RenderList.syncadd`'s wait. ⚠️ A `Loading` that wraps another (`Loading(Loading rec)`) forwards `boostprio` to it now, as `waitfor` always did: `MapView.draw` wraps `camload` and its boost used to die in the wrapper. Measured at login on 16 logical cores: the cut under the camera built beside fourteen others cost 203–563 ms of CPU; alone, 15–47 |
| **GPU part nesting + late arrival** | `GPUProfile.Part.part(Render,nm)` hangs the new part **under** the one it is called on and emits **one** timestamp that also closes the parent's previous child (`tfin()`), so opening a sibling **truncates** the part before it — nest, never sit beside. `Part.fin(out)` is the explicit close (idempotent; a later sibling's `tfin()` on a `fin` part is a no-op). `GPUProfile.check()` drains `waiting` **in order** and returns on the first part that is not `done` ⇒ **one unclosed part blocks every later frame's GPU timing, silently**. A consumer hangs its named passes under the frame's `draw` part, captured in `Frame.display`, every seam in `try`/`finally` |

## Building a `UI` headlessly

The whole widget tree becomes unit-testable once a real `UI` exists off-screen — `hasparent(ui.root)`
liveness, interning, staleness and GC/no-pin proofs all need `ui.root` to hang widgets off, and the dispatch
answers (what a `Window` consumes, what a hover or a cursor query returns) are readable there and nowhere
else cheaply. Three things block it:

| Blocker | Where | Fix |
|---|---|---|
| `Text.<clinit>` does a blocking `loadwait("ui/fraktur")` | `Text` ← `ConsoleHost.<clinit>` ← `RootWidget` ← `UI.<init>` | put **`bin/builtin-res.jar`** on the classpath — the local pool is a `JarSource("res")` (`Resource.local`), so no network is needed |
| `Resource`'s annotation scan | `Resource.<clinit>` → `dolda.jglob.Loader` | add **`build/classes-lib`** (or `lib/jglob.jar`) — without it every class touching `Resource` dies with `NoClassDefFoundError` |
| toolkit/scale probe at `UI.<clinit>` | `UI.initscale` | add the jogl/lwjgl jars from `build/*.jar`; the `Unavailable` traces it prints are **caught** — not a failure |

The audio root needs no trickery: `new Audio.Root(haven.iosys.audio.DummyAudio.DummySink.instance)` opens a
`DummyPlayer` and nothing else, which is what `TestClient` builds too. Then
`UI u = new UI(null, ar, Coord.of(800, 600), null)` — a `UI` with a **null `sess`**, exactly what the addon
layer is; `u.root` is a live `RootWidget`, `u.root.add(w)` and `w.destroy()` behave as in-game, and
`haven.Widget` overrides neither `equals` nor `hashCode`, so a widget is safe as an identity map key.

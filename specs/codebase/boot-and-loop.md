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

## Threading & locks

| Concern | Rule / where |
|---|---|
| Widget tree + event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`); writers: UI thread + Loader threads |
| Deferred server widget ops | [`UI.CommandQueue`](src/haven/UI.java:245); executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker ([`OCache.receive`](src/haven/OCache.java:514)); apply on Loader ([`GobInfo.apply`](src/haven/OCache.java:370)); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| `Loading` exception protocol | [`Loader.Future.run`](src/haven/Loader.java:76); any code touching resources/gobs/grids must tolerate it |

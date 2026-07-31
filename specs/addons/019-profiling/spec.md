# 019-profiling — Spec

<!-- Line ceilings waived for 019 by the maintainer: this feature is important enough that the
     planning must not be terse. Other features keep the template limits. -->

## What & why

The client already profiles itself, and none of it is reachable from Lua:

| What exists today | Where | Reachable from an addon? |
|---|---|---|
| Hierarchical CPU frame tree (UI thread + render thread) | `UILoop.uprof`/`rprof` (`CPUProfile`) | no |
| GPU frame timing via GL timestamp queries | `UILoop.gprof` (`GPUProfile`) | no |
| FPS / idle / latency / heap / GL / MapView / net / loader text | `UILoop.statlines`, `:stats on` | no |
| A live tree view of the above | `Profwnd`/`Profdisp`, `:profile on` | no |
| Per-addon Lua CPU time (for the D-018 watchdog) | `Addon.tickLuaNanos` | no |

So the data mostly exists; what is missing is (a) a Lua surface, (b) any **per-addon** attribution,
and (c) any way for an addon to time **its own** sections. This feature ships all three:

- **`hafen.client:profiling()`** — frame/CPU/GPU timings, memory, graphics counters, network, loader
  and **per-addon cost**, plus **custom Lua scopes** (the `ProfilerMarker` equivalent). The goal is
  to make a Unity-Profiler-style addon possible: what costs what, and how it hits FPS.
- **`hafen.client:options():client()`** — a new **"Client"** Options panel (the "Voice Chat
  Integration" / "AddOns" pattern) whose first setting is **Enable profiling**: the one master
  switch that arms every probe, readable and writable from Lua like every other option.

**Naming (approved).** One OptWnd panel = one options subsystem — the shape 018 already ships
(`interface`/`video`/`audio`/`camera`/`keybindings`) — so the panel lives at `options():client()`,
not directly on `hafen.client`. `profiling()` is *data*, not settings, so it stays on `hafen.client`.

## The two guarantees

**Off ⇒ nothing.** Every probe is `if(!Prof.on) return;` over a single `static volatile boolean` —
no `nanoTime`, no allocation, no map lookup, no string formatting. Not a `Config.Variable.get()`
(object deref + unbox); a plain static field is a branch the JIT hoists out of loops. 019.7 proves
this with a measured FPS comparison against the pre-019 build, recorded in its verification.

**On ⇒ a budget, not a hope.** Armed overhead **≤5% of frame time, target ≤2%**, measured by the
control-frame mechanism (see plan.md), reported live by `:overhead()`. **Enforcement:** a tier that
cannot meet the budget is *not* armed by the master switch — it gets its own opt-in checkbox
(Unity's module model). The worst case is therefore never a slow client; it is a tier you have to
tick on deliberately.

## Adding client instrumentation is in scope

Per D-011 (invasiveness allowed where it clearly enables better features), 019 is **not** limited to
what the client happens to expose today. Two distinct kinds of change, in increasing order of risk:

1. **Expose what is already counted.** The render layer maintains draw-slot counts
   (`GLDrawList.btsubsize`), batch/instance counts (`InstanceList.nbatches`/`nuinst`/`ninst`/
   `ninvalid`/`nbypass`), VRAM per pool (`GLEnvironment.stats_obj`/`stats_mem`), render-tree size and
   program counts — then **throws them away into format strings** for the HUD
   (`"Tree %s, Inst %s, Draw %s, Map %d"`). 019 adds structured getters *beside* the existing
   `stats()` methods. No new counting, no behaviour change, no cost, works with profiling **off**.
   A profiler that has to parse `"Tree %s, Inst %s"` is not a profiler.
2. **New probe points** where nothing is counted (the addon-category split, custom scopes, the
   end-of-frame handoff) — all behind the master switch.

No debug build, no shipped debug mode, no JVM agent, no launch flag: one checkbox at runtime.

## The Lua surface (shape, not final field names)

```lua
local p = hafen.client:profiling()

p:frame()      --> { fps=58.7, ms=17.0, msAvg=16.9, msMin=15.2, msMax=31.4, msP95=19.8,
               --    idle=0.34, latency=2.1, gpuMs=11.2, dropped=1,
               --    phases = { stick=1.2, utick=3.4, draw=9.1, swap=0.4, wait=2.6, aux=0.3 },
               --    ui=12.5, scene=8.9, addons=1.7 }          -- ms, this frame
p:history(300) --> { {t=..., ms=..., gpuMs=..., phases={...}}, ... }   -- oldest→newest, for graphs

p:memory()     --> { heapUsed=, heapFree=, heapTotal=, heapMax=, allocPerFrame=, gcCount=, gcMs= }
p:render()     --> { drawSlots=, batches=, instances=, uniqueInstances=, invalid=, bypass=,
               --    treeSize=, programs=, stateSlots=, vram={ [pool]={objects=,bytes=} } }
p:net()        --> { packetsTx=, packetsRx=, bytesTx=, bytesRx=, resentTx=, dupRx=, rtt=, rttVar= }
p:loader()     --> { queued=, loading=, busy=, poolSize=, deferred=, resQueue=, resLoaded= }

p:addons()     --> { { id="hello", ms=0.31, msAvg=0.28, msPeak=2.10, share=0.018,
               --      calls={ events=12, timers=3, draw=60, hooks=0, widgets=4 },
               --      scopes={ ["scan-gobs"]={ ms=0.12, calls=1 } } }, ...,
               --    total = { ms=1.70, share=0.10 } }

p:widgets()    --> { byType = { { type="Inventory", tickMs=, drawMs=, selfMs=, count=3 }, ... },
               --    top = { { type="MapView", id=42, selfMs=6.1, owner=nil }, ... } }  -- sorted
p:passes()     --> { { name="shadow", cpuMs=0.8, gpuMs=3.9 },
               --    { name="scene",  cpuMs=4.2, gpuMs=6.1 },
               --    { name="ui2d",   cpuMs=3.1, gpuMs=1.2 } }
p:gl()         --> { programBinds=, vertices=, triangles= }   -- armed-only counters

local s = p:scope("scan-gobs")      -- a named marker owned by the calling addon
s:begin(); heavy_work(); s:finish() -- both no-ops when profiling is off
p:measure("scan-gobs", heavy_work)  -- the wrapper form

p:overhead()   --> { aggregatorMs=, probeMs=, gpuQueryMs=, totalMs=, shareOfFrame=, method="control" }
p:reset()      -- clear the ring and every accumulator

hafen.client:options():client():profiling()      --> false
hafen.client:options():client():profiling(true)  --> the client-options handle (chains)
```

Reads are **snapshots**, taken on the UI thread, consistent with every other `hafen.*` read surface.
`:frame()` returns an empty table (not `nil`) when profiling is off, so addon code needs no branch.

## API shape: why the results are tables, not objects

This surface follows the **already-shipped** rule in `docs/addons/api/conventions.md` ("Snapshots vs
handles"); it introduces no new style and does not conflict with the Gob OOP migration (D-044/045):

- **Handles** are live, bridge-owned proxies **with methods** — things with identity, a lifetime, and
  verbs you invoke. In 019 those are: `hafen.client:profiling()` itself (verbs: `:reset()`,
  `:scope()`, `:measure()`, plus the read verbs), the **`ProfScope`** returned by `:scope(name)`
  (`:begin()` / `:finish()`), and the options subsystem `options():client()` whose `:profiling()` /
  `:profiling(true)` is the same arity-as-verb pattern 018 ships for `video()`/`camera()`.
- **Snapshots** are plain Lua tables — point-in-time copies with no identity, no lifetime and nothing
  to write. `gob:info()`, `hafen.items.inventory`, `hafen.buffs.list`, `hafen.markers.list` are all
  tables today, and **even the OOP Gob returns a table for a value**: `gob:pos()` → `{x, y}`.

So `p:frame().fps` is the same shape as `me:pos().x`, and the boundary is consistent: **the object is
the thing you address; the table is the measurement you read off it.** Making a frozen measurement
object-oriented would add nothing — there is no identity to re-resolve and no write path — and it
would cost real time in exactly the loop this feature exists to keep cheap: drawing a 600-sample
frame graph is 600 Lua table lookups as a table, versus 600 LuaJ bridge invocations as objects.

**Where OOP does apply, 019 uses it**: the profiling handle, the scope handle, and the options
accessor are all objects with verbs. When the OOP migration reaches the remaining flat namespaces,
this feature needs no change — its handles are already objects, and its snapshots were never the
thing being migrated.

## Acceptance criteria

**Panel & switch**
- [ ] Options → **Client** exists as a button in the main options list, next to "Voice Chat
      Integration" and "AddOns", and opens a panel with an **Enable profiling** checkbox.
- [ ] The checkbox persists across a full client restart (a `Utils.pref*` write, like every other
      option), and toggling it takes effect **live** — no restart, no `:reload`.
- [ ] `hafen.client:options():client():profiling()` returns the same boolean the checkbox shows;
      `:profiling(true)` writes it, returns the handle so writes chain, and an **open** panel's
      checkbox visibly follows the Lua write.
- [ ] With profiling on, the client's own `:profile on` machinery is live too (`Profwnd` shows
      frames) — they are one switch, documented as such.

**Off-state**
- [ ] With profiling off, `:frame()`, `:history()`, `:addons()` return empty, and `:scope()`/
      `:measure()` are no-ops that still run the wrapped function.
- [ ] **Measured:** FPS with profiling off is statistically indistinguishable from the pre-019
      build, in the same spot with the same settings; the numbers go in 019.7's verification.

**Frame data**
- [ ] With profiling on, `:frame()` gives fps, frame time (current/avg/min/max/p95), idle share,
      latency, the per-phase CPU breakdown (`stick`/`utick`/`draw`/`swap`/`wait`/`aux`) and the GPU
      frame time, plus the three roll-ups: whole-UI (`utick`+`draw`), scene, and addons.
- [ ] `:history(n)` returns the last `n` samples oldest→newest, enough to draw a frame graph; the
      ring holds ~600 frames (≈10 s at 60 fps) and `n` larger than that is clamped, not an error.
- [ ] The phase numbers track the client's own `:stats on` / `Profwnd` display — same frames, same
      values, no second source of truth.

**Counters (all answer with profiling OFF)**
- [ ] `:memory()` — heap used/free/total/max plus the per-frame allocation estimate the HUD shows.
- [ ] `:net()` — packets and bytes tx/rx, resends, and the smoothed RTT + variance the connection
      already tracks.
- [ ] `:loader()` — loader queue/loading/busy/pool, `Defer` group stats, resource queue depth.
- [ ] `:render()` — the graphics counters as **numbers**, not the `:stats on` strings: draw slots,
      batches, unique/total instances, invalid + bypass, render-tree size, VRAM objects+bytes per
      pool, GL programs, state slots.
- [ ] **In-game check:** placing a building (or panning to a dense area) visibly moves the instance
      and batch counts; toggling Video → Shadows changes the draw-slot count.

**Addons & scopes**
- [ ] `:addons()` gives one row per loaded addon: cpu ms this frame, rolling average, peak, share of
      frame time, and a call breakdown by category (events, timers, draw, hooks, widgets), plus a
      `total` row that reconciles with the `addons` figure in `:frame()`.
- [ ] `:scope(name)` / `:measure(name, fn)` attribute time to the **calling** addon; the scope
      appears under that addon's row in `:addons()`. Scope names are per-addon namespaced (two
      addons may both use `"update"` without colliding) and disappear on that addon's teardown.
- [ ] Arming `hogtest` makes it the top row, and the watchdog still auto-disables it at exactly the
      same point as before 019 (the D-018 total must be unchanged).

**Per-widget cost**
- [ ] `:widgets()` breaks the UI cost down **per widget type**: tick ms, draw ms, **self** ms
      (inclusive minus children) and instance count, plus a `top` list of the heaviest individual
      widgets. Addon-owned widgets carry their owner, so they appear here **and** in `:addons()`.
- [ ] The per-type totals reconcile with the `utick` and `draw` phases in `:frame()`.
- [ ] **In-game check:** opening the inventory / character window makes those types appear and moves
      their rows; closing them makes the rows drop back.

**Named render passes**
- [ ] `:passes()` returns a **fixed, short** list of named passes with **CPU and GPU time side by
      side** — at minimum `shadow` (wrapping the `ShadowMap` update), `scene`, and `ui2d`.
- [ ] **In-game check:** Video → Shadows **off** makes the `shadow` pass drop to zero (or vanish)
      and the GPU frame time fall by roughly the cost it had been reporting. This is the criterion
      that makes "what do shadows cost me" a number rather than a guess.
- [ ] `:gl()` returns the armed-only counters that need new counting: program/shader binds, vertices
      and triangles submitted per frame.

**Overhead**
- [ ] `:overhead()` reports the aggregator cost, the estimated probe cost, the GPU-query cost and
      the total, as ms and as a share of the frame — while profiling is on.
- [ ] **Each armed tier is measured separately**, so a tier that misses the ≤5% budget can be moved
      behind its own checkbox rather than dragging the whole feature down.
- [ ] **Measured:** armed overhead is within the ≤5% budget (target ≤2%); the numbers go in 019.7's
      verification. If a tier misses it, that tier ships behind its own checkbox and the fact is
      recorded as a decision.

**Harness**
- [ ] A dedicated **`addons/profiler`** ("Brodgar.io Profiler") addon draws a live window: a frame
      graph over the history ring, the phase breakdown, and the addon cost table sorted by cost.
      It is **dormant** — window behind a hotkey — per the `hogtest`/`bags` rule, so a routine login
      is undisturbed. `hello` is **not** extended (it is already too large to absorb a whole feature).
- [ ] Full regression: `hello`, `optionstest`, `bags`, `planner`, `widgetstack`, `netdemo`, `walker`
      behave exactly as before, with profiling both off and on.

## Out of scope

- **Replacing `:stats on` / `:profile on` / `Profwnd`.** They stay exactly as they are; 019 reads the
  same machinery rather than competing with it.
- **JFR or a JVM agent for allocation call sites** — rejected by the maintainer: heavyweight, and
  precisely the "ship a debug mode" shape this feature must not have. Memory stays at heap +
  per-frame delta, which is what the client can give for free.
- **Per-draw-call / per-material / per-shader GPU attribution.** A GL timestamp query per draw call
  costs far more than it measures and stalls the pipeline; that is RenderDoc/Nsight territory, and
  it stays out of scope here too.
- **Per-gob cost.** The scene is instanced and batched: cost is per draw list, not per object.
  Attributing frame time to "that oak tree" is not a thing the renderer can answer.
- **Persisting profiling sessions to disk, flame-graph export, telemetry upload.**
- **Any further setting in the new Client panel** beyond Enable profiling. The panel is the future
  home for client-wide toggles, but this feature ships exactly one.

## Context files

- `018-client-options/` — prior art: `OptionsHandle` / `OptionsMethod` (arity-as-verb accessors), the
  panel↔Lua pairing, and the `optionstest` harness shape `addons/profiler` mirrors
- `design/10-options-panel.md` — the `OptWnd.Panel` + `PButton` clone pattern (AddOns/Voice)
- `src/haven/UILoop.java` — `uprof`/`rprof`/`gprof` (~:43), `Frame` ctor arming (~:502), `Frame.tick`
  phases (~:433-490), `statlines` (~:233), the `:stats`/`:profile` console commands (~:603)
- `src/haven/CPUProfile.java` — `set`/`begin`/`phase`/`end`, the `Current` ThreadLocal, `Frame`
- `src/haven/GPUProfile.java` — GL-timestamp parts and their late arrival
- `src/haven/Profile.java` — `hist` ring, `Part.f()/t()/d()/sub()`, `last()`, `copy()`
- `src/haven/OptWnd.java` — the main button list (~:871-880) where the "Client" `PButton` lands
- `src/haven/Connection.java` — `Stats` (~:43): private `ptx`/`prx`/`btx`/`brx`/`pretx`/`prerx`/
  `prorx`/`srtt`/`rttv`, needing `// addon:` accessors
- `src/haven/Loader.java` (`stats`, ~:245), `src/haven/Defer.java` (`gstats`, ~:321)
- `src/haven/render/gl/GLEnvironment.java` — `stats_obj`/`stats_mem` (~:171), `memstats` (~:1018),
  `numprogs`
- `src/haven/render/InstanceList.java` — `nuinst`/`nbatches`/`ninst`/`ninvalid`/`nbypass` (`stats`
  ~:881); `src/haven/render/gl/GLDrawList.java` — `btsubsize` (`stats` ~:1089)
- `src/haven/MapView.java` — `stats`/`camstats` (~:956-970), the `Back`/`Map` inner stats (~:1398),
  and `updsmap` (~:978-1029) where `smap.update(out, slist)` is the whole shadow pass in one call
- `src/haven/ShadowMap.java` — `update(Render, ShadowList)` (~:240) and its `ShadowList.draw` (~:202)
- `src/haven/Widget.java` — the `tick`/`draw` traversal `:widgets()` probes (tick ~:748);
  `src/haven/UI.java` — `tick` (~:371) / `draw` (~:386) roots and the `ui2d` pass boundary
- `src/io/brodgar/addon/AddonManager.java` — `callLua` (~:643) and the tick pump / soft-budget sweep
  (~:317, ~:398-412); `Addon.java` — `tickLuaNanos` (~:244); `Sandbox.java` — `SOFT_BUDGET_NANOS`
- `src/io/brodgar/addon/OptionsHandle.java` + `OptionsMethod.java` — where `client()` and
  `profiling()` attach, and the read/write-by-arity convention to follow
- `src/io/brodgar/addon/ui/AddonPanel.java` — the panel to clone for `ClientPanel`
- `docs/addons/api/client.md` — the shipped surface this extends
- `specs/codebase/boot-and-loop.md` (frame loop, threading), `specs/codebase/services.md`
  (Options/prefs, what OptWnd actually writes)

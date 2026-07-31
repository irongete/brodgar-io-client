# 019-profiling — Plan

<!-- Line ceilings waived for 019 by the maintainer. -->

## Guiding principle

**Sample what exists; instrument only what's missing; expose what is merely hidden.** Three rules,
in that order of preference, because each is cheaper and less invasive than the next:

1. The client already builds a hierarchical frame tree every frame — read it, don't rebuild it.
2. The client already *counts* draw slots, batches, instances and VRAM — expose the numbers, don't
   re-count them and don't parse the strings it formats them into.
3. Only what genuinely has no counter (per-addon category split, custom scopes) gets a new probe,
   and every new probe sits behind the master switch.

## What the client already gives us

| Source | Data | Cost to read | Needs the switch? |
|---|---|---|---|
| `UILoop.uprof` (`CPUProfile`) | UI-thread frame tree: `stick`/`utick`/`draw`/`swap`/`wait`/`aux` | free (already built) | yes — the tree is only built when `UILoop.profile` is set |
| `UILoop.rprof` | render-thread tree (`tick`/`draw`/`swap`/`finish`), fenced back | free | yes |
| `UILoop.gprof` (`GPUProfile`) | GPU frame time via GL timestamp queries | free | yes |
| `UILoop` `fps`/`uidle`/`framelag` | fps, idle share, latency | free | no (but private → pass them in) |
| `Runtime` + the `statlines` delta | heap free/used/total/max, per-frame alloc | free | no |
| `GLEnvironment` `stats_obj`/`stats_mem`, `numprogs` | VRAM objects+bytes per pool, program count | free | no |
| `InstanceList` `nuinst`/`nbatches`/`ninst`/`ninvalid`/`nbypass` | batching effectiveness | free | no |
| `GLDrawList.btsubsize(root)` | draw-slot count (≈ draw calls) | free | no |
| `MapView.stats`/`camstats`, `RenderTree` | scene tree size, camera state | free | no |
| `State.Slot.numslots()` | render state slots | free | no |
| `Connection.Stats` | packets/bytes tx+rx, resends, smoothed RTT + variance | free | no |
| `Loader.stats`, `Defer.gstats`, `Resource.qdepth` | async queue depths | free | no |
| `Addon.tickLuaNanos` | per-addon Lua CPU, already summed per tick for D-018 | free | no (already always-on) |

Everything in the "no" rows is why `:memory()`, `:net()`, `:loader()` and `:render()` answer **with
profiling off**: they are pull-only reads of counters the client maintains regardless. The only
change those need is **structured getters beside the existing `stats()` strings** — no new counting,
no behaviour change. Everything in the "yes" rows is what the master switch actually arms.

## Architecture

```
                    ┌──────────────────────────────────────────┐
  OptWnd "Client"   │  Prof  (io.brodgar.addon)                 │
  checkbox ────────►│   static volatile boolean on              │
       ▲            │   ring: double[]/long[] × ~600 frames     │
       │            │   per-addon + per-scope accumulators      │
  ClientOptions     │   overhead accounting + control frames    │
  (options():client)└───────▲──────────────────┬───────────────-┘
                            │ frame(...)       │ snapshot()
                    ┌───────┴────────┐   ┌─────▼──────────┐
                    │ UILoop end-of- │   │ ProfHandle     │──► hafen.client:profiling()
                    │ frame handoff  │   │ ProfScope      │──► p:scope()/p:measure()
                    └────────────────┘   └────────────────┘
                            ▲
                    AddonManager.callLua ── per-category nanos ──►
```

`Prof` is the single owner of profiling state. Nothing else caches; `ProfHandle` is a stateless
proxy (like `018`'s options handles), so an addon may stash it forever without pinning client state.

## The master switch and the arming protocol

- `Prof.on` is a **plain `static volatile boolean`**. Every probe reads exactly that. Deliberately
  *not* `Config.Variable.get()` — that is an object deref plus an unbox on a path we want the JIT to
  hoist. The field is written from one place (`Prof.arm(boolean)`), on the UI thread.
- `Prof.arm(true)` (a) sets `UILoop.profile` so the client starts building `uprof`/`rprof`/`gprof`
  frames, (b) runs the one-shot **probe calibration** (below), (c) zeroes the ring and accumulators,
  (d) sets `Prof.on`. `arm(false)` reverses it, clearing `UILoop.profile` too.
- **`UILoop.Frame` decides at construction** whether to build profile objects (`profile.get()` in the
  `Frame` ctor). So a flip mid-frame takes effect on the **next** frame — documented, not a bug, and
  the reason `:frame()` may be empty for exactly one frame after arming.
- The pref (`Utils.setprefb`) and the switch are written in one statement, the way `OptWnd` does it
  for `MapView.invcamx` — a pref-only write would be a no-op until restart.

## Data model

**Ring** — preallocated primitive arrays, ~600 frames (≈10 s at 60 fps), written by index with a
wrapping cursor. Per frame: wall time, frame ms, gpu ms, and one `double` per known phase. **No
allocation per frame** — otherwise `:memory()`'s per-frame-allocation figure would be measuring the
profiler, which is exactly the observer effect this design exists to avoid.

**Accumulators** — per addon: `long[]` indexed by category (events, timers, draw, hooks, widgets),
plus call counts; per scope: a small map on the `Addon`, keyed by scope name, reset each frame with
rolling avg/peak kept alongside. Accumulators are keyed by an **opaque int id**, so the per-widget
and per-pass rows (019.5/019.6) drop in without reshaping the ring or the snapshot format.

**Snapshots** — Lua tables are built **on demand**, when an addon calls a method, never per frame.
This is the same discipline as every other `hafen.*` read surface.

## The per-frame fold

At end-of-frame, `UILoop` hands `Prof` the finished frame parts plus `fps`/`uidle`/`framelag`
(private today → passed as arguments rather than widening the fields). `Prof.frame(...)`:

1. `if(!on) return;` — the whole handoff is one branch when off.
2. Start the aggregator timer (one `nanoTime`).
3. Walk `uprof`'s last `Frame`: total `d()` plus each child part's `d()` into the ring slot. The
   walk is over a handful of parts, not a deep tree.
4. Fold `rprof` and `gprof` **by frame number**, not by position: GPU parts arrive late (GL fences),
   so a frame's GPU time may land N frames after its CPU time. Slots accept a late GPU write.
5. Roll up the three aggregates: `ui = utick + draw`, `scene`, `addons` (the sum of the per-addon
   accumulators for this tick).
6. Stop the aggregator timer; add to the overhead accounting. Zero the per-frame accumulators.

## Per-addon accounting

`AddonManager.callLua` already brackets every event, timer, draw callback and hook with a
`System.nanoTime()` pair and adds the delta to `owner.tickLuaNanos` for the D-018 soft budget. 019
**splits** that same measurement instead of adding a second timer:

- `callLua` gains a **category** argument (a small `int` constant). Call sites already differ by
  purpose, so this is a mechanical change at each site.
- `owner.tickLuaNanos += delta` stays **byte-for-byte what it was** — the watchdog must keep
  auto-disabling at exactly the same point, or 019 silently changes addon behaviour. The category
  split is an *additional* `if(Prof.on) owner.catNanos[cat] += delta;` inside the same `finally`.
- Cost when off: one extra `int` argument and one branch on an already-hot path.
- Addon-owned widgets/overlays route their draw callbacks through `callLua` too, so their cost lands
  in the owning addon's row automatically — no separate bookkeeping.

## Per-widget cost

The UI is one of the three big frame consumers, and `utick`/`draw` only say *how much*, never *who*.
The probe must survive being run for **every widget, every frame**, so the data structure is the
whole design:

- **One `// addon:` field on `Widget`: `long[] prof`**, allocated lazily and only while armed. The
  probe is then a field access plus two `nanoTime` calls — no `IdentityHashMap` lookup per widget per
  frame, which is what makes the naive version unaffordable at hundreds of widgets. Off: the field
  is `null` and untouched; the probe is the same `if(!Prof.on)` branch as everywhere else.
- **Inclusive** nanos accumulate into that array. **Self** time uses the standard single-thread
  trick: the traversal keeps a stack-local sum of the children's inclusive time and subtracts it, so
  self-time costs one extra `long` per stack frame, not a second measurement pass.
- **Per-type roll-up happens at snapshot time**, walking the tree and grouping by
  `widget.getClass()`. The hot path never touches a map or a string; class names are only resolved
  when Lua asks.
- **Ownership:** `LuaWidget` already knows its owning addon, so an addon's widgets are attributed to
  it in `:addons()` *and* itemised in `:widgets()` — the two views reconcile instead of competing.

## Named render passes (CPU + GPU)

`GPUProfile.part(out, name)` inserts a GL timestamp query, so **arbitrary named GPU sections are an
operation the engine already supports** — the client simply never names more than `tick`/`draw`/
`swap`. 019 adds a **fixed, short, curated** pass list, each with CPU and GPU time side by side:

| Pass | Seam |
|---|---|
| `shadow` | `MapView.updsmap` → `smap.update(out, slist)` (~:1029) — the entire shadow render in one call |
| `scene` | the `MapView` draw boundary (the 3D scene draw list) |
| `ui2d` | the `UI.draw` boundary in `UILoop.display` |

The list is fixed on purpose: **every boundary is a real GPU timestamp query**, which is not free, so
passes are curated forever and never swept per draw call. Their cost is itself reported in
`:overhead()` as `gpuQueryMs`, like any other probe.

Alongside them, the **armed-only GL counters** that need genuinely new counting rather than exposing
an existing one: program/shader binds (a `long++` in the bind path) and vertices/triangles submitted
(a `long +=` in the draw path). These are the only counters in 019 that do not already exist, which
is why they are armed-only while everything in `:render()` is pull-only.

## Custom scopes (the `ProfilerMarker` equivalent)

`CPUProfile.begin(name)` / `end()` is already exactly this: nested, named, and `null`-gated (the
`current` ThreadLocal is `null` when off, so `begin` returns immediately). Scopes are that primitive
re-exported to Lua, not a new mechanism:

- `p:scope("name")` returns a handle; `:begin()`/`:finish()` bracket the section. `p:measure(n, fn)`
  is the wrapper form and always runs `fn`, armed or not.
- **Off:** `scope()` returns a shared singleton whose methods are empty — no allocation per call, no
  per-addon state, and code that forgets to `finish()` costs nothing.
- **On:** nanos accumulate against `(owner, name)`. Names are per-addon namespaced, so two addons can
  both use `"update"`. Scopes appear in that addon's `:addons()` row and in the frame's scope list.
- Teardown: the scope map lives on the `Addon`, so it dies with the addon — no leak across `:reload`.

## Measuring the overhead without paying for it twice

The point the maintainer raised: measuring the profiler must not cost more than the profiler. Three
layers, each cheap, cross-checking each other:

1. **The aggregator is timed directly** — one `nanoTime` pair per frame around the fold, which is the
   only place real work happens. Exact, and its own cost is one pair per frame.
2. **Probe cost is counted, not timed.** Each probe already increments a counter as part of its job.
   Per-probe nanos are **calibrated once** when the switch arms (a short micro-loop measuring the
   probe body), so `overhead ≈ hits × unitCost`. Timing each probe individually would roughly double
   the cost of the very thing being reported.
3. **Control frames.** One frame in 64 runs **disarmed**. Comparing the frame-time distribution of
   armed vs control frames is a *measured* total overhead at essentially zero marginal cost, and it
   catches anything layers 1–2 model badly (cache effects, JIT deopt, GPU query stalls). This is the
   number `:overhead()` reports as `method="control"` once enough samples exist; before that it falls
   back to the modelled estimate.

If the control-frame delta exceeds the ≤5% budget, that is a **task failure**, not a footnote: the
offending tier moves behind its own checkbox and the fact is recorded as a decision.

## Storage, threading and staleness

- All of this runs on the single "Haven UI thread" (`tick → draw → swap` under `synchronized(ui)`),
  except `rprof` (already fenced back through `RenderProfile`) and `gprof` (GL fences). The
  aggregator therefore only ever reads frames the client has already finished. **No new locking.**
- `Connection.Stats` is written on the Connection worker; its new accessors are plain `long` reads
  and may be one packet stale. Render counters are written on the render side and may be one frame
  stale. Both are documented as such — adding synchronisation to those paths to buy a freshness
  nobody needs would be the wrong trade.
- Lua never sees a live object: snapshots are copied at call time, on the UI thread.

## Options panel and Lua wiring

- `io.brodgar.ui.ClientPanel extends OptWnd.Panel` — a direct clone of `AddonPanel` /
  `VoiceChatPanel` (design/10), holding one `CheckBox` for **Enable profiling**.
- One `// addon:` line in `OptWnd`'s main button list adds the `PButton` "Client", next to "Voice
  Chat Integration" and "AddOns".
- `ClientOptions` implements the subsystem with `OptionsMethod` (arity is the verb: `profiling()`
  reads, `profiling(v)` writes and returns the handle so writes chain) — identical in shape to
  `VideoOptions`/`CameraOptions`, so there is one canonical way.
- The checkbox **reads `Prof.on` each frame** rather than caching it, so a Lua write moves an open
  panel's checkbox with no event plumbing.
- The console `:profile on` and the checkbox are the **same** switch (`UILoop.profile`). Documented
  explicitly — one canonical way — and `Profwnd` keeps working unchanged.

## Files to create / modify

**Create — package split (decided in 019.1 with the maintainer).** The profiling *engine* is a **client**
feature: its consumers are `UILoop`, `Widget`, `MapView` and the GL layer, and only the Lua bridge belongs
to the addon system. So the engine gets its own package and only the handles stay in `addon/`:

- `src/io/brodgar/prof/Prof.java` — the master switch, calibration, ring, per-frame fold, per-addon and
  per-scope accumulators, overhead accounting, control frames.
- `src/io/brodgar/ui/ClientPanel.java` — the Options "Client" panel (a *client* panel; the addon-manager
  panels stay in `addon/ui/`).
- `src/io/brodgar/addon/ProfHandle.java` — `hafen.client:profiling()`: `frame`, `history`, `memory`,
  `render`, `net`, `loader`, `addons`, `widgets`, `passes`, `gl`, `scope`, `measure`, `overhead`, `reset`.
- `src/io/brodgar/addon/ProfScope.java` — the scope handle (`begin`/`finish`), a shared no-op singleton
  when off.
- `src/io/brodgar/addon/ClientOptions.java` — the `options():client()` subsystem (`profiling()` via
  `OptionsMethod`).

**Modify (addon layer)**
- `OptionsHandle.java` — add `client()` to the options tree and `profiling()` to `hafen.client`.
- `AddonManager.java` — `callLua` category argument; fold addon totals into `Prof` on tick.
- `Addon.java` — per-category nanos, call counts, the per-scope map.

**Modify (`haven` core — all tagged `// addon:`)**
- `UILoop.java` — the end-of-frame handoff (finished `uprof`/`rprof`/`gprof` parts +
  `fps`/`uidle`/`framelag`, private today).
- `OptWnd.java` — one `PButton` "Client" in the main list.
- `Connection.java` — public accessors for the private `Stats` counters.
- `render/InstanceList.java`, `render/gl/GLDrawList.java`, `render/gl/GLEnvironment.java`,
  `MapView.java` — structured getters beside the existing `stats()` strings. Counters already
  maintained: no new counting, no behaviour change, `stats()` untouched.
- `Widget.java` — the `long[] prof` field and the tick/draw probes (the one genuinely hot-path edit).
- `UI.java` — the `ui2d` pass boundary in the draw root; `MapView.java` — the `shadow` (`updsmap`)
  and `scene` pass boundaries.
- the GL bind/draw path — the armed-only program-bind and vertex/triangle counters.

**Other**
- `addons/profiler/{manifest.json,main.lua}` — the "Brodgar.io Profiler" example addon.
- `docs/addons/api/client.md` — extend with `profiling()` and the `client()` options subsystem.
- `specs/codebase/boot-and-loop.md` — extend with the profiling machinery (`CPUProfile`/`GPUProfile`/
  `Profwnd`, `:stats`/`:profile`, `statlines`); no subsystem file covers it today, so 019 pays for
  that coverage once.

## Risks & gotchas

- **`ant hafen-client` is incremental and can false-green.** `callLua` gaining an argument is exactly
  the kind of signature change that leaves stale classes behind — `rm -rf build/classes` for a true
  compile check. Java changes need a full client restart; only Lua files reload live.
- **The D-018 total must not move.** `tickLuaNanos` feeds the watchdog's auto-disable. If the
  category split changes what the watchdog sees, addons start disabling at different points and the
  regression harness lies. Verify `hogtest` trips at the same place.
- **`gprof` frames land late.** GL timestamp results arrive after fences, potentially N frames later.
  Fold by frame number; never assume the GPU time for frame X is available when frame X's CPU time is.
- **Arming is next-frame.** `UILoop.Frame` reads `profile.get()` in its constructor, so the first
  frame after a toggle has no tree. Don't treat the empty frame as a bug.
- **Render/net counters are one frame/packet stale** — by design, see threading above.
- **Don't perturb the harness.** Profiling defaults **off**; `addons/profiler` is dormant with its
  window behind a hotkey (the `hogtest`/`bags` rule), so a routine `hello` regression login is
  unchanged.
- **Don't let the profiler allocate.** Any per-frame allocation in the fold shows up in the
  per-frame-allocation figure the profiler itself reports.
- **`Widget` is the client's hottest class.** The added field must be one reference and the probe one
  branch. If 019.5 cannot demonstrate it free when off, the field goes and `:widgets()` ships
  type-level totals gathered at the `UI.tick`/`UI.draw` roots only — recorded as a decision, not
  quietly dropped.
- **GPU timestamp queries are not free when armed** and can stall the pipeline if overused. Keep the
  pass list fixed and short; report their cost in `:overhead()`; if the pass tier misses the budget,
  it ships behind its own checkbox (the enforcement rule, applied for real).
- **Per-tier budget accounting.** Because several tiers are now armed by one switch, `:overhead()`
  must attribute cost **per tier**, or a tier that misses the budget cannot be identified, let alone
  moved behind its own checkbox.

## Discarded alternatives

- **A parallel profiler that ignores `CPUProfile`** — duplicates the frame tree, doubles the cost,
  and drifts from what `Profwnd` / `:profile on` show. Two sources of truth for frame time is the
  worst possible outcome for a profiler.
- **Parsing the client's `stats()` strings** — brittle, lossy, and absurd when the counters are right
  there behind them.
- **Timing every probe to report overhead** — roughly doubles probe cost; calibration plus control
  frames measure the same thing for free.
- **A GL timestamp per draw call / per material** — costs far more than it measures and stalls the
  pipeline. Named passes (019.6) are the honest granularity; RenderDoc handles anything finer.
- **JFR or a JVM agent for per-addon allocation** — rejected by the maintainer: heavyweight, and the
  "ship a debug mode" shape this feature must not have.
- **`Config.Variable<Boolean>` as the hot switch** — deref plus unbox on every probe.
- **Always-on instrumentation with a Lua-side "read it or not"** — breaks the off-state guarantee
  outright; the whole point is that off costs nothing.
- **`IdentityHashMap<Widget,long[]>` for per-widget cost** — a hash lookup per
  widget per frame; a field on `Widget` is the only version that stays cheap at hundreds of widgets.
- **`hafen.client:client()`** — one OptWnd panel = one options subsystem (018's shape), so the panel
  lives at `options():client()`. Approved by the maintainer.

## API shape — no new style is introduced

019 follows the shipped rule in `docs/addons/api/conventions.md` ("Snapshots vs handles"), so it does
not cut across the Gob OOP migration:

- **Handles = objects with verbs** (identity, lifetime, actions): the profiling handle itself, the
  `ProfScope` from `:scope(name)`, and the `options():client()` subsystem with its arity-as-verb
  `profiling()` / `profiling(true)` — the same shape 018 ships for `video()`/`camera()`.
- **Snapshots = plain tables** (a frozen measurement: no identity to re-resolve, no write path):
  everything the read verbs return. This is what `hafen.items`, `hafen.buffs`, `hafen.markers` do
  today — and what the **OOP Gob itself** does for values, `gob:pos()` → `{x, y}`.

`p:frame().fps` is therefore the same shape as `me:pos().x`. Wrapping frozen measurements in objects
would add no capability and would cost real time in the one loop this feature must keep cheap: a
600-sample frame graph is 600 table lookups as tables, versus 600 LuaJ bridge invocations as objects.
When the OOP migration reaches the remaining flat namespaces, 019 needs no change — its handles are
already objects, and its snapshots were never the thing being migrated.

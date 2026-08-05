# hafen.client: who spent the frame

[`frame()`](README.md) says a frame cost so many milliseconds. These five verbs say **who**: which addon,
which widget, which render pass, what the client handed the driver, and what the measuring itself cost.
All five are **armed only** — with [the switch](../README.md#client) off they answer an empty table.

## `addons()`

One row per Lua owner — every loaded addon, plus `(console)` for the `:lua` prompt — for the **last
completed frame**, sorted most expensive first. The top row is the answer to "who is costing me frames".

| Key | Description |
|---|---|
| `id` | the addon's manifest id |
| `ms` | its Lua time in the last completed frame |
| `msAvg` / `msPeak` | mean and worst frame since the switch was armed, or since `reset()` |
| `share` | `ms` as a fraction of that frame — absent until a frame has been sampled |
| `calls` | how many calls, by category: `events`, `timers`, `draw`, `hooks`, `widgets` |
| `cost` | the same split in milliseconds |
| `scopes` | this addon's [named scopes](#custom-scopes), keyed by name |

The array is followed by a **`total`** key, `{ms=, share=}`, which is the *same number* `frame().addons`
reports: both read the accounting the addon CPU watchdog already keeps, so the two views can never
disagree. Iterate the rows with `ipairs` — `total` is not part of the array.

```lua
local rows = hafen.client():profiling():addons()
for _, r in ipairs(rows) do
  hafen.log():write(string.format("%-12s %.2f ms (%.0f%%)  draw=%d events=%d",
                          r.id, r.ms, (r.share or 0) * 100, r.calls.draw, r.calls.events))
end
```

**Categories describe what your Lua was doing**, not where it lives: `draw` is overlay and widget paint
callbacks, `widgets` the rest of a custom widget's life (tick, mouse, drop), `hooks` input, action and
message hooks plus hotkeys and slash commands, `events` the event bus and async callbacks, `timers` timer
callbacks.

> A callback that calls back into the engine, which calls your Lua again, is charged to **both** brackets,
> the same way the watchdog has always charged it. So `cost` can add up to slightly more than `ms` on a
> re-entrant frame. `ms` is the number to trust.

## Custom scopes

`scope(name)` and `measure(name, fn, ...)` name a section of *your* code so you can see what it costs, in
your own row of [`addons()`](#addons).

```lua
local p = hafen.client():profiling()

p:measure("scan-gobs", function()                  -- the wrapper form: you cannot forget to finish
  for _, g in ipairs(hafen.world():gob():list()) do … end
end)

local s = p:scope("rebuild")                       -- the explicit form, for a section you cannot wrap
s:begin()
rebuildIndex()
s:finish()
```

| Verb | Description |
|---|---|
| `s:begin()` / `s:finish()` | bracket a section; both chain |
| `s:name()` | the scope's name |
| `p:measure(name, fn, ...)` | run `fn(...)` inside the scope and return whatever it returns |

Names are **per addon**: two addons may both use `"update"` without colliding, and a scope map dies with its
addon on `:reload` or disable, so there is nothing to clean up. Each scope appears in that addon's
`addons()` row as `{ms=, msAvg=, msPeak=, calls=}`.

`ms` and `calls` are **this-frame** figures, so a scope that ran a moment ago reads 0 and its cost lives in
`msPeak` and `msAvg`. Read `ms` per frame, from an `OnUpdate` say; read `msPeak` for "how bad does this
get".

**Leave the instrumentation in.** With profiling off, `begin` and `finish` return on a single field check
and `measure` calls `fn` directly — nothing is allocated and nothing is recorded, so a shipped addon pays
effectively nothing for scopes it is not being profiled on. `measure` runs `fn` either way and closes the
scope even if `fn` errors. Only the outermost `begin`/`finish` pair of a recursive section counts, an
unmatched `finish()` is ignored, and a scope left open by an erroring handler closes at end of frame.

## `widgets()`

`frame()` says the widget tree cost so many milliseconds. `widgets()` says **who**.

```lua
local w = hafen.client():profiling():widgets()
for _, r in ipairs(w.byType) do
  hafen.log():write(string.format("%-20s x%d  self %.2f ms", r.type, r.count, r.selfMs))
end
```

| Key | Description |
|---|---|
| `byType` | one row per widget class, **sorted by self time** |
| `top` | the heaviest individual widgets, same sort |
| `total` | the whole tree: `tickMs`, `drawMs`, `ms`, and `count`, the widgets with a live measurement |

A `byType` row:

| Key | Description |
|---|---|
| `type` | the widget class name; an anonymous subclass reports the class it extends |
| `count` | how many of them were measured |
| `tickMs` / `drawMs` | time **inclusive** of children |
| `tickSelfMs` / `drawSelfMs` / `selfMs` | the same **exclusive** of children; `selfMs` is the sum of the two |

A `top` row carries `type`, `selfMs`, `tickMs` and `drawMs`, plus `id` when the widget is bound to a server
id and `owner` when an **addon** put it in the tree, that addon's manifest id.

**Inclusive versus self.** A widget is timed by its *parent*, around the call that ticks or draws its
entire subtree — that is `tickMs` and `drawMs`. Self time is that minus the sum of its children's inclusive
time. So a container holding one expensive child shows a large `tickMs` and a near-zero `tickSelfMs`, and
only the child is blamed. Every row's `selfMs` sums to `total.ms`, which is the root widget's inclusive
tick and draw: the breakdown **reconciles**, it is not indicative.

**Which frame.** The last one in which each widget was ticked or drawn — the frame in progress, or the one
just finished. A widget not touched since, a window you closed or a hidden tab, simply **drops out** of the
tables rather than reporting a cost it no longer has, which is also why `total.count` shrinks when you
close a window.

**`owner` is the link to [`addons()`](#addons).** An addon's own widgets are itemised here *and* rolled into
that addon's row: two views of one measurement, not two measurements.

> **What is not in it.** `tickMs` is the tick traversal only, while the `utick` phase in `frame()` also
> covers the hover query and any resize, so the tree's tick total sits a little under that phase. And the
> `draw` phase covers the whole 3D scene, of which the map-view row is only the widget-side share — the
> scene's own breakdown is what the named [passes](#passes) are for.

## `passes()`

`frame()` says the frame cost so many milliseconds on the GPU. `passes()` says **where they went**, over a
fixed list of named sections with **CPU and GPU time side by side**.

```lua
for _, r in ipairs(hafen.client():profiling():passes()) do
  hafen.log():write(string.format("%-8s cpu %.2f ms  gpu %.2f ms", r.name, r.cpuMs, r.gpuMs))
end
```

| Pass | What it covers |
|---|---|
| `shadow` | the entire shadow-map render |
| `scene` | the 3D draw list, the world itself |
| `ui2d` | the widget tree |

Each row is `{name=, cpuMs=, gpuMs=}`, always in that order. The table also carries `frameno`, and `ms` and
`gpuMs` for the whole frame so you can measure the rows against it.

**The rows are disjoint.** `shadow` and `scene` run *inside* the widget draw, since the map view is a
widget, so each pass reports **self** time: its own span minus the passes nested in it, the same split
[`widgets()`](#widgets) uses. That is why `ui2d` means the *2D* UI, and why the three sum to less than the
frame instead of counting the scene twice.

**CPU and GPU measure different things here.** The CPU column is the time spent *recording* the GL
commands, so `shadow` and `scene` are small while `ui2d` is real widget work. The GPU column is when the
driver actually did it.

**Which frame.** The newest one whose GL timestamps have come **back**, the same rule as `gpuMs` in
`frame()` and for the same reason. Both columns describe that one frame, so a row is internally consistent.
Empty until the first frame resolves.

**What shadows cost you.** Turn Video ▸ Shadows off and the `shadow` row falls to zero *and* `scene` drops
too, because the world's shaders stop sampling the shadow map. Both savings are real and the split tells
you which is which; that decomposition is the whole point of naming passes.

> **The list is fixed, and stays fixed.** Every boundary is a real GL timestamp query, which is not free and
> can stall the pipeline if overused. Per-draw-call or per-material GPU attribution is not something this
> API grows.

## `gl()`

What the client actually handed the driver last frame.

| Key | Description |
|---|---|
| `drawCalls` | draw calls submitted |
| `programBinds` | shader-program switches |
| `vertices` / `triangles` | geometry submitted; point and line geometry counts vertices, not triangles |
| `frameno` | the frame these describe |

These four are new counting rather than a number the client already keeps, which is why they sit behind the
switch while everything in [`render()`](counters.md#render) does not.

`programBinds` against `drawCalls` is the batching story: the draw list is sorted by program, so binds far
below calls means the sort is doing its job.

## `overhead()`

What profiling itself costs, per **tier**, so a tier that gets too expensive can be identified and disabled
rather than dragging the whole feature down.

```lua
local o = hafen.client():profiling():overhead()
hafen.log():write(string.format("profiling costs %.4f ms/frame = %.2f%% (%s)",
                        o.totalMs, o.shareOfFrame * 100, o.method))
for _, r in ipairs(o.tiers) do
  hafen.log():write(string.format("  %-8s %.4f ms", r.name, r.ms))
end
```

Every figure is a **mean per frame** since the switch was armed, or since `reset()`; a single frame's
figure would be noise at this scale.

| Key | Description |
|---|---|
| `totalMs` / `shareOfFrame` | the budget number: ms per frame, and as a fraction of the frame |
| `budget` / `withinBudget` | the ceiling this surface holds itself to, 5% of frame time, and whether this run is inside it |
| `method` | `"control"` if `totalMs` was measured, `"model"` if calibrated — see below |
| `aggregatorMs` | the end-of-frame fold, **timed directly**, so exact |
| `gpuQueryMs` | the GL timestamp queries the named passes insert, timed directly |
| `probeMs` | the **modelled** probe cost: hits times a per-hit cost calibrated when the switch armed |
| `measuredMs` / `measuredErrorMs` | the **measured** probe cost and its error bar; absent until enough samples |
| `measuredSpreadMs` | the comparison's noise floor |
| `frameMs` | mean frame time, what the share is taken against |
| `armedFrames` / `controlFrames` / `periods` / `periodsNeeded` | how much evidence there is so far |
| `tiers` | one row per tier, both keyed by name and ordered as a list |

Each tier row is `{name=, ms=, share=, modelledMs=, method=}`, plus `hits`, probe hits per frame, on the
modelled ones:

| Tier | Probes |
|---|---|
| `frame` | the end-of-frame fold — the only tier that is timed rather than modelled |
| `addons` | the per-addon category split behind [`addons()`](#addons) |
| `widgets` | the per-widget brackets behind [`widgets()`](#widgets) |
| `passes` | the named-pass seams and their GL timestamp queries |
| `gl` | the submission counters behind [`gl()`](#gl) |

**Two numbers, cross-checking each other.** The *modelled* one counts probe hits and multiplies by a
per-hit cost measured once when the switch armed; it is available immediately and errs **high**, because
the calibration runs cold while the real probes run compiled. The *measured* one comes from **control
frames**: one frame in every batch runs with every probe disarmed, and each period contributes one delta —
the median **work** time of its armed frames minus its control frame. Work, not frame time: under vsync or
a frame cap the total is pinned to the cap and would never move. `method` tells you which one `totalMs`
used.

> **`measuredMs` of zero or less is the normal outcome, and does not mean profiling made the client
> faster.** It means the cost is under the comparison's own noise floor. The measurement only takes over
> when it clears `measuredErrorMs`; otherwise the model has the say, because a model that at least counted
> the probes beats a random number. For a feature whose whole claim is that it costs almost nothing, an
> unresolvable measurement is success.

Once the measurement *is* authoritative the tiers are scaled to it in the modelled proportion, so the rows
always sum to `totalMs`. `modelledMs` is reported alongside, so nothing hides behind the scaling.

## See also

- [profiling](README.md) — the handle, `frame()` and `history()`
- [counters](counters.md) — the five that answer whether profiling is armed or not
- [`hafen.client():options()`](../README.md#client) — the switch every verb here needs

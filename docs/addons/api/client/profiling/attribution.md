# hafen.client: Who Spent the Frame

[`frame()`](README.md) says a frame cost so many milliseconds. These verbs say who: which addon, which widget, which render pass, what the client handed the driver, and what the measuring itself cost. All armed only: with [the switch](../README.md#client) off they answer an empty table.

```lua
for _, row in ipairs(hafen.client():profiling():addons()) do
  local milliseconds = math.floor(row.ms * 100 + 0.5) / 100                 -- two decimals by hand: %.2f prints the raw double
  hafen.log():write(string.format("%s %s ms (%d%%)  draw=%d events=%d",
                          row.id, milliseconds, (row.share or 0) * 100, row.calls.draw, row.calls.events))
end
```

---

## `addons()`

One row per Lua owner (every loaded addon, plus `(console)` for the `:lua` prompt) for the last completed frame, most expensive first.

| Key | Description |
|---|---|
| `id` | The addon's manifest id. |
| `ms` | Its Lua time in the last completed frame. |
| `msAvg` / `msPeak` | Mean and worst frame since the switch was armed, or since `reset()`. |
| `share` | `ms` as a fraction of that frame. Absent until a frame has been sampled. |
| `calls` | How many calls, by category: `events`, `timers`, `draw`, `hooks`, `widgets`, `exports`. |
| `cost` | The same split in milliseconds. |
| `scopes` | This addon's [named scopes](#custom-scopes), keyed by name. |

| Rule | Detail |
|---|---|
| `total` | A key after the array, `{ms=, share=}`, the same number `frame().addons` reports: both read the addon CPU watchdog's accounting. Folded a fraction of a frame apart: `frame()` closes with the frame, these rows on the tick after. Lua run in between is in the frame these rows close and not yet in `frame()`'s. Iterate the rows with `ipairs`. `total` is not part of the array. |
| Categories describe what your Lua was doing | `draw`: overlay and widget paint callbacks, a grid's cell paint included. `widgets`: the rest of a widget's life (mouse input, tick, drop, close, destroy, a container's item events, a control's own key). `hooks`: hotkeys, console commands, a mouse grab's move and release. `events`: the event bus and the two message streams. `timers`: timer callbacks. `exports`: calls into this addon's export from other addons, and callbacks they handed it. |
| Re-entrancy | A callback that calls back into the client, which calls your Lua again, is charged to both brackets, as the watchdog charges it. So `cost` can add up to slightly more than `ms`. `ms` is the number to trust. |

## Custom scopes

`scope(name)` and `measure(name, fn, ...)` name a section of your code so its cost shows in your row of [`addons()`](#addons).

```lua
local profiling = hafen.client():profiling()
profiling:measure("scan-gobs", function()                  -- the wrapper form: you cannot forget to finish
  for _, gob in ipairs(hafen.session():current():world():gob():list()) do hafen.log():write(gob:id()) end
end)

local rebuild_scope = profiling:scope("rebuild")           -- the explicit form, for a section you cannot wrap
rebuild_scope:begin()
-- implementation here
rebuild_scope:finish()
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `scope:begin()` / `scope:finish()` | the scope | Unprotected | Bracket a section. |
| `scope:name()` | `string` | Unprotected | The scope's name. |
| `profiling:measure(name, fn, ...)` | whatever `fn` returns | Unprotected | Run `fn(...)` inside the scope. |

| Rule | Detail |
|---|---|
| Arguments | `name` a string, `fn` a function, both required. A missing or wrong-typed one raises naming the verb and the parameter. |
| Per addon | Two addons may both use `"update"`. A scope map dies with its addon on `:reload` or disable. Each scope appears in the addon's `addons()` row as `{ms=, msAvg=, msPeak=, calls=}`. |
| 256 names | A name is a section of code. Naming a scope after an item id, a coordinate or a frame number grows the map without bound. It also builds a table per entry per snapshot. Past the limit `profiling:scope(name)` raises. |
| `finish()` always closes | Armed or not. The switch decides whether the time is written down. |
| This-frame figures | `ms` and `calls` are this frame's, so a scope that ran a moment ago reads 0 and its cost lives in `msPeak` and `msAvg`. Read `ms` per frame from an `Update`. Read `msPeak` for how bad it gets. |
| Leave the instrumentation in | With profiling off `begin` and `finish` return on a single field check and `measure` calls `fn` directly: nothing allocated, nothing recorded. `measure` runs `fn` either way and closes the scope if `fn` errors. Only the outermost `begin`/`finish` pair of a recursive section counts. An unmatched `finish()` is ignored. A scope left open by an erroring handler closes at end of frame. |

## `widgets()`

`frame()` says the widget tree cost so many milliseconds. `widgets()` says who.

```lua
local widget_costs = hafen.client():profiling():widgets()
for _, row in ipairs(widget_costs.byType) do
  hafen.log():write(string.format("%s x%d  self %s ms", row.type, row.count, math.floor(row.selfMs * 100 + 0.5) / 100))
end
```

| Key | Description |
|---|---|
| `byType` | One row per widget class, sorted by self time. |
| `top` | The heaviest individual widgets, same sort. |
| `total` | The whole tree: `count`, the widgets with a live measurement, and `tickMs`, `drawMs` and `ms` where the root itself has one. A frame the client has not finished measuring carries `count` alone. |

| `byType` row key | Description |
|---|---|
| `type` | The widget class name. An anonymous subclass reports the class it extends. |
| `count` | How many were measured. |
| `tickMs` / `drawMs` | Time inclusive of children. |
| `tickSelfMs` / `drawSelfMs` / `selfMs` | The same exclusive of children. `selfMs` is the sum of the two. |

A `top` row carries `type`, `selfMs`, `tickMs` and `drawMs`. It adds `id` when the widget is bound to a server id. It adds `owner`, that addon's manifest id, when an addon put it in the tree.

| Rule | Detail |
|---|---|
| Inclusive versus self | A widget is timed by its parent around the call that ticks or draws its subtree (`tickMs`, `drawMs`). Self time is that minus its children's inclusive time, so a container holding one expensive child shows a large `tickMs` and a near-zero `tickSelfMs`. Every row's `selfMs` sums to `total.ms`, the root's inclusive tick and draw. |
| A clock discontinuity | One mid-frame can time a child above its parent. A negative self time reads as zero and the rows then overshoot `total.ms` a little. |
| Which frame | The last in which each widget was ticked or drawn. A widget not touched since (a closed window, a hidden tab) drops out of the tables. That is why `total.count` shrinks when you close a window. |
| `owner` links to [`addons()`](#addons) | An addon's widgets are itemised here and rolled into that addon's row: two views of one measurement. |
| Not in it | `tickMs` is the tick traversal only, while `frame()`'s `utick` phase also covers the hover query and any resize. The `draw` phase covers the whole 3D scene, of which the map-view row is the widget-side share. The scene's breakdown is [passes](#passes). |

## `passes()`

Where the GPU milliseconds went, over a fixed list of named sections with CPU and GPU time side by side.

```lua
for _, row in ipairs(hafen.client():profiling():passes()) do
  local cpu, gpu = math.floor(row.cpuMs * 100 + 0.5) / 100, math.floor(row.gpuMs * 100 + 0.5) / 100
  hafen.log():write(string.format("%s cpu %s ms  gpu %s ms", row.name, cpu, gpu))
end
```

| Pass | Covers |
|---|---|
| `shadow` | The entire shadow-map render. |
| `scene` | The 3D draw list, the world itself. |
| `ui2d` | The widget tree. |

| Rule | Detail |
|---|---|
| Row shape | `{name=, cpuMs=, gpuMs=}`, always in that order. The table also carries `frameno`, and `ms` and `gpuMs` for the whole frame. |
| Disjoint rows | `shadow` and `scene` run inside the widget draw (the map view is a widget), so each pass reports self time, the split [`widgets()`](#widgets) uses. `ui2d` is the 2D UI, and the rows sum to less than the frame. |
| CPU and GPU differ | The CPU column is the time recording the GL commands, so `shadow` and `scene` are small while `ui2d` is real widget work. The GPU column is when the driver did it. |
| Which frame | The newest whose GL timestamps have come back, the rule of `gpuMs` in `frame()`. Both columns describe that frame. Empty until the first frame resolves. |
| What shadows cost | Turn Video ▸ Shadows off and `shadow` falls to zero and `scene` drops too, since the world's shaders stop sampling the shadow map. |
| The list is fixed | Every boundary is a GL timestamp query, which can stall the pipeline if overused: no per-draw-call or per-material GPU attribution. |

## `gl()`

What the client handed the driver last frame.

| Key | Description |
|---|---|
| `drawCalls` | Draw calls submitted. |
| `programBinds` | Shader-program switches. |
| `vertices` / `triangles` | Geometry submitted. Point and line geometry counts vertices, not triangles. |
| `frameno` | The frame these describe. |

New counting rather than numbers the client already keeps, which is why they sit behind the switch while [`render()`](counters.md#render) does not. `programBinds` against `drawCalls` measures the batching: the draw list is sorted by program, so binds far below calls means the sort works.

## `overhead()`

What profiling itself costs, per tier, so an expensive tier can be identified and disabled. Every figure is a mean per frame since the switch was armed, or since `reset()`.

```lua
local overhead = hafen.client():profiling():overhead()
local function round(number, places) local scale = 10 ^ places return math.floor(number * scale + 0.5) / scale end
hafen.log():write(string.format("profiling costs %s ms/frame = %s%% (%s)",
                        round(overhead.totalMs, 4), round(overhead.shareOfFrame * 100, 2), overhead.method))
for _, tier in ipairs(overhead.tiers) do
  hafen.log():write(string.format("  %s %s ms", tier.name, round(tier.ms, 4)))
end
```

| Key | Description |
|---|---|
| `totalMs` / `shareOfFrame` | Ms per frame, and as a fraction of the frame. The share is absent with no `frameMs`. |
| `budget` / `withinBudget` | The ceiling this surface holds itself to, 5% of frame time, and whether what profiling spends is inside it. Absent with no `frameMs`. |
| `method` | `"control"` if `totalMs` was measured, `"model"` if calibrated. |
| `aggregatorMs` | The end-of-frame fold, timed directly. |
| `gpuQueryMs` | The GL timestamp queries the named passes insert, timed directly. |
| `probeMs` | The modelled probe cost: hits times a per-hit cost calibrated when the switch armed. |
| `measuredMs` / `measuredErrorMs` | The measured probe cost and its error bar. Absent until enough samples. |
| `measuredSpreadMs` | The comparison's noise floor. |
| `frameMs` | Mean frame time. Absent until a frame has been measured. |
| `armedFrames` / `controlFrames` / `periods` / `periodsNeeded` | How much evidence there is so far. |
| `ringFrames` | How many frames of profile tree the client holds at a time. A cost the measurement cannot see. |
| `tiers` | One row per tier, keyed by name and ordered as a list: `{name=, ms=, share=, modelledMs=, method=}`, plus `hits` (probe hits per frame) on the modelled ones. |

| Tier | Probes |
|---|---|
| `frame` | The end-of-frame fold. The only tier timed rather than modelled. |
| `addons` | The per-addon category split behind [`addons()`](#addons). |
| `widgets` | The per-widget brackets behind [`widgets()`](#widgets). |
| `passes` | The named-pass seams and their GL timestamp queries. |
| `gl` | The submission counters behind [`gl()`](#gl). |

| Rule | Detail |
|---|---|
| Two numbers, cross-checking | The modelled one counts probe hits times a per-hit cost measured when the switch armed. It is available at once and errs high: the calibration runs cold, the probes compiled. The measured one comes from control frames. One frame per batch runs with every probe disarmed. Each period contributes the median work time of its armed frames minus its control frame (work, not frame time, which vsync or a cap pins). `method` says which `totalMs` used. |
| `measuredMs` of zero or less is normal | The cost is under the comparison's noise floor, not profiling making the client faster. The measurement takes over only when it clears `measuredErrorMs`. Otherwise the model has the say. Once authoritative, the tiers are scaled to it in the modelled proportion so the rows sum to `totalMs`. `modelledMs` is reported alongside. |
| What the measurement cannot see | The probes are what a control frame disarms. What the profiler holds (the client's own per-frame trees) stays, so a retention cost cancels in the subtraction. The measured method sees what profiling spends, never what it keeps, and `withinBudget` is a claim about the first. `ringFrames` reports the second as a count, since retention is paid as a collection pause on the collector's schedule. Read it if you leave profiling armed. |

---

## See Also

- [Profiling](README.md) — the handle, `frame()` and `history()`.
- [Counters](counters.md) — the ones that answer whether profiling is armed or not.
- [`hafen.client():options()`](../README.md#client) — the switch every verb here needs.

# hafen.client: Profiling Attribution

These methods break down where frame execution time was spent across addons, UI widgets, render passes, and driver submissions. All methods require the profiler to be enabled (see [`hafen.client():options():client():profiling()`](../README.md#client)).

---

## `addons()`

Returns an array of execution metrics for every loaded addon (and the interactive `:lua` prompt) for the last completed frame, sorted by CPU time descending.

```lua
local addon_rows = hafen.client():profiling():addons()

for _, row in ipairs(addon_rows) do
  hafen.log():write(string.format("%-15s %.2f ms (%.1f%%) | draw: %d, events: %d",
    row.id, row.ms, (row.share or 0) * 100, row.calls.draw, row.calls.events))
end
```

### Row Fields

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Addon manifest identifier. |
| `ms` | `number` | Lua execution time in milliseconds during the last completed frame. |
| `msAvg` / `msPeak` | `number` | Mean and peak frame execution time since profiler armed. |
| `share` | `number \| nil` | Fraction of total frame time (`0.0`–`1.0`). |
| `calls` | `table` | Call counts by category: `{draw, events, timers, hooks, widgets}`. |
| `cost` | `table` | Milliseconds spent in each category. |
| `scopes` | `table` | Performance data for custom scopes declared via `profiler:scope()`. |

The array also includes a non-array `total` field (`{ms: number, share: number}`).

---

## Custom Measurement Scopes

Addons can instrument specific code blocks using scopes:

```lua
local profiler = hafen.client():profiling()

-- Wrapper method (handles exceptions and scope cleanup automatically)
local scan_result = profiler:measure("inventory_scan", function()
  -- Heavy calculation or object iteration
  return true
end)

-- Explicit scope handle
local parsing_scope = profiler:scope("json_decode")
parsing_scope:begin()
-- Execute work...
parsing_scope:finish()
```

### Scope Methods

| Method | Returns | Description |
|---|---|---|
| `profiler:scope(name)` | `ScopeHandle` | Obtains or creates a named scope handle. Maximum 256 scopes per addon. |
| `scope:begin()` | `ScopeHandle` | Opens the timing bracket. Chains. |
| `scope:finish()` | `ScopeHandle` | Closes the timing bracket. Chains. |
| `scope:name()` | `string` | Returns the scope name. |
| `profiler:measure(name, fn, ...)` | `any` | Executes `fn(...)` inside the scope and returns its result. |

When the profiler is disabled, scopes execute with minimal overhead (no heap allocations or measurements recorded).

---

## `widgets()`

Breaks down widget tree evaluation and rendering costs across the entire interface:

```lua
local widget_stats = hafen.client():profiling():widgets()

for _, row in ipairs(widget_stats.byType) do
  hafen.log():write(string.format("%-18s (x%d) self: %.2f ms, total: %.2f ms",
    row.type, row.count, row.selfMs, row.tickMs + row.drawMs))
end
```

### Return Table Structure

| Key | Description |
|---|---|
| `byType` | List of widget classes sorted by `selfMs` descending. |
| `top` | List of individual heaviest widgets in the scene. |
| `total` | Summary table: `{count, tickMs, drawMs, ms}` for the root tree. |

### `byType` Row Fields

| Field | Type | Description |
|---|---|---|
| `type` | `string` | Widget class name. |
| `count` | `number` | Number of active widgets measured of this class. |
| `tickMs` / `drawMs` | `number` | Inclusive CPU time spent updating and drawing (includes children). |
| `tickSelfMs` / `drawSelfMs` | `number` | Exclusive CPU time spent directly on this widget class. |
| `selfMs` | `number` | Total exclusive CPU time (`tickSelfMs + drawSelfMs`). |

---

## `passes()`

Reports CPU recording time and GPU execution time for primary rendering passes:

```lua
local render_passes = hafen.client():profiling():passes()

for _, pass in ipairs(render_passes) do
  hafen.log():write(string.format("Pass %-8s | CPU: %.2f ms | GPU: %.2f ms",
    pass.name, pass.cpuMs, pass.gpuMs))
end
```

### Standard Passes

| Pass Name | Description |
|---|---|
| `"shadow"` | Shadow map generation pass. |
| `"scene"` | 3D world geometry, entities, terrain, and models. |
| `"ui2d"` | 2D user interface widget rendering pass. |

GPU metrics reflect timings retrieved from hardware query fences and may lag by 1–2 frames.

---

## `gl()`

Reports low-level graphics driver submission metrics:

```lua
local gl_stats = hafen.client():profiling():gl()

hafen.log():write(string.format("Draw calls: %d, Program binds: %d, Triangles: %d",
  gl_stats.drawCalls or 0, gl_stats.programBinds or 0, gl_stats.triangles or 0))
```

| Field | Type | Description |
|---|---|---|
| `drawCalls` | `number` | Total draw commands submitted this frame. |
| `programBinds` | `number` | Shader program switch operations. |
| `vertices` | `number` | Number of geometry vertices submitted. |
| `triangles` | `number` | Number of triangles rasterized. |
| `frameno` | `number` | Target frame index. |

---

## `overhead()`

Inspects the computational overhead introduced by the profiler instrumentation itself:

```lua
local overhead_data = hafen.client():profiling():overhead()

hafen.log():write(string.format("Profiler cost: %.4f ms (%.2f%% of frame)",
  overhead_data.totalMs, (overhead_data.shareOfFrame or 0) * 100))
```

| Field | Type | Description |
|---|---|---|
| `totalMs` | `number` | Average milliseconds per frame spent on profiling instrumentation. |
| `shareOfFrame` | `number` | Fraction of total frame time taken by the profiler. |
| `budget` | `number` | Maximum allowed budget threshold (`0.05` = 5%). |
| `withinBudget` | `boolean` | `true` if profiler remains below the 5% budget ceiling. |
| `tiers` | `table[]` | Overhead breakdown per subsystem (`frame`, `addons`, `widgets`, `passes`, `gl`). |

---

## See Also

- [Profiling Overview](README.md) — Main profiling entry point and frame history.
- [Counters](counters.md) — Runtime memory, network, and render metrics.
- [`hafen.client():options()`](../README.md) — Global client options.

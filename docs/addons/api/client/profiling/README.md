# hafen.client: Profiling

`hafen.client():profiling()` provides direct programmatic access to the client's internal frame profiler, per-frame CPU/GPU breakdown, and subsystem performance counters. All methods in this subsystem are unprotected.

```lua
local profiler = hafen.client():profiling()

local frame_stats = profiler:frame()
if frame_stats.fps then
  hafen.log():write(string.format("FPS: %d | Frame: %.2f ms (UI: %.2f ms, Addons: %.2f ms)",
    frame_stats.fps, frame_stats.ms, frame_stats.ui or 0, frame_stats.addons or 0))
end
```

---

## Profiler Activation

Frame instrumentation is enabled via client settings:
```lua
hafen.client():options():client():profiling(true) -- Requires client.settings
```
Alternatively, toggle it using the `:profile on` console command. When profiling is disabled, frame sampling methods return empty tables (`{}`), while [counters](counters.md) remain readable at zero performance overhead.

---

## API Overview

### Frame Sampling Methods (Require Profiler Armed)

| Method | Returns | Description |
|---|---|---|
| `profiler:frame()` | `table` | Performance snapshot of the most recently completed frame. |
| `profiler:history(count?)` | `table[]` | Ring buffer of the last `count` frames (oldest first). |
| `profiler:addons()` | `table[]` | CPU execution time attributed per addon. See [Attribution](attribution.md#addons). |
| `profiler:widgets()` | `table` | Hierarchy breakdown of widget tick and render times. See [Attribution](attribution.md#widgets). |
| `profiler:passes()` | `table[]` | Named render pass durations (CPU and GPU). See [Attribution](attribution.md#passes). |
| `profiler:gl()` | `table` | Low-level OpenGL draw calls and geometry counts. See [Attribution](attribution.md#gl). |
| `profiler:overhead()` | `table` | Profiler self-measurement overhead metrics. See [Attribution](attribution.md#overhead). |
| `profiler:scope(name)` | `ScopeHandle` | Custom timing scope marker. See [Attribution](attribution.md#custom-scopes). |
| `profiler:measure(name, fn, ...)` | `any` | Executes `fn(...)` inside a named measurement scope. |
| `profiler:reset()` | `Profiling` | Clears frame history ring buffer and resets peak metrics. Chains. |

### Subsystem Counters (Always Active)

These query live runtime metrics without requiring profiler instrumentation:

| Method | Returns | Description |
|---|---|---|
| `profiler:memory()` | `table` | JVM heap usage and garbage collection statistics. |
| `profiler:net()` | `table` | Packet throughput, packet loss, and RTT. |
| `profiler:loader()` | `table` | Async resource loader queue lengths. |
| `profiler:render()` | `table` | Scene graph node counts and VRAM allocations. |
| `profiler:surfaces()` | `table` | 3D in-world widget surface textures and draw counts. |
| `profiler:entities()` | `table` | Virtual entities and coordinate resolution status. |
| `profiler:session()` | `table` | Multi-session metrics and terrain synchronization. |
| `profiler:textcache()` | `table` | Glyph rasterization cache hit rate and memory use. |

See [Counters](counters.md) for detailed field definitions.

---

## `frame()` Snapshot Structure

The snapshot returned by `profiler:frame()` contains:

| Field | Type | Description |
|---|---|---|
| `frameno` | `number` | Monotonically increasing frame index. |
| `t` | `number` | Frame timestamp in seconds since client launch. |
| `fps` | `number` | Client smoothed frames-per-second calculation. |
| `ms` | `number` | Total UI thread duration for this frame (ms). |
| `msAvg` / `msMin` / `msMax` / `msP95` | `number` | Aggregated frame time statistics across history ring. |
| `idle` | `number` | Fraction of frame spent waiting (`0.0`–`1.0`). |
| `latency` | `number` | CPU-to-GPU fence synchronization latency (ms). |
| `gpuMs` | `number \| nil` | GPU execution time (ms). Absent until GPU fence resolves. |
| `gpuFrameno` | `number \| nil` | Frame index corresponding to `gpuMs`. |
| `ui` | `number` | Milliseconds spent evaluating the widget tree. |
| `addons` | `number` | Lua execution time attributed to all addons this frame. |
| `phases` | `table` | UI thread phase timings (`stick`, `utick`, `sessions`, `draw`, etc.). |
| `render` | `table` | Render thread timings (`tick`, `draw`, `swap`, `finish`). |

---

## `history(count)`

Retrieves historical frame records stored in the circular buffer:

```lua
local profiler = hafen.client():profiling()
local history_frames = profiler:history(60) -- Query last ~60 frames

for index, frame_snapshot in ipairs(history_frames) do
  if frame_snapshot.gpuMs then
    hafen.log():write(string.format("Frame %d: CPU %.2f ms, GPU %.2f ms",
      frame_snapshot.frameno, frame_snapshot.ms, frame_snapshot.gpuMs))
  end
end
```

---

## Behavior When Profiling Is Disabled

When profiling is inactive:
- Sampling methods (`frame()`, `history()`, `addons()`, etc.) return an empty table (`{}`).
- Scope methods (`scope()`, `measure()`) still execute your code cleanly with near-zero overhead.
- Counters (`memory()`, `net()`, etc.) continue reporting real-time metrics.

---

## See Also

- [Attribution](attribution.md) — Breakdown by addon, widget, and render pass.
- [Counters](counters.md) — Memory, network, render, and cache metrics.
- [`hafen.client():options()`](../README.md) — Enabling the profiler via client settings.

# hafen.client: Profiling

`hafen.client():profiling()` is the read surface over the client's frame profiler, the same per-frame CPU and GPU trees the client's own profile windows draw: where a frame went, in your addon or across the whole client. Unprotected.

```lua
local profiling = hafen.client():profiling()
local frame = profiling:frame()
hafen.log():write(string.format("%d fps, %.2f ms (ui %.2f, addons %.2f)",
                               frame.fps, frame.ms, frame.ui, frame.addons))
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `profiling:frame()` | `table` | Unprotected | The frame that just finished. Armed only. |
| `profiling:history(n)` | `table[]` | Unprotected | The last `n` frames, oldest first; `n` omitted is everything held. Armed only. |
| `profiling:addons()` | `table[]` | Unprotected | [What each addon's Lua cost](attribution.md#addons), most expensive first. Armed only. |
| `profiling:widgets()` | `table` | Unprotected | [Where the UI's frame time went](attribution.md#widgets). Armed only. |
| `profiling:passes()` | `table[]` | Unprotected | [The named render passes](attribution.md#passes), CPU and GPU side by side. Armed only. |
| `profiling:gl()` | `table` | Unprotected | [What the client handed the driver](attribution.md#gl). Armed only. |
| `profiling:overhead()` | `table` | Unprotected | [What profiling itself costs](attribution.md#overhead), per tier. Armed only. |
| `profiling:scope(name)` | scope handle | Unprotected | [A named marker](attribution.md#custom-scopes) you bracket your own code with. |
| `profiling:measure(name, fn, ...)` | whatever `fn` returns | Unprotected | Run `fn` inside the scope `name`. |
| `profiling:reset()` | the handle | Unprotected | Drop the history and start measuring afresh. |
| `profiling:memory()` | `table` | Unprotected | [JVM heap and allocation](counters.md#memory). Always answers. |
| `profiling:net()` | `table` | Unprotected | [Packet counters and round-trip time](counters.md#net). Always answers. |
| `profiling:loader()` | `table` | Unprotected | [Async queue depths](counters.md#loader). Always answers. |
| `profiling:render()` | `table` | Unprotected | [Graphics counters](counters.md#render). Always answers. |
| `profiling:surfaces()` | `table` | Unprotected | [Widgets standing in the world](counters.md#surfaces). Always answers. |
| `profiling:entities()` | `table` | Unprotected | [Things standing at a point in the world](counters.md#entities). Always answers. |
| `profiling:session()` | `table` | Unprotected | [What the other sessions answered](counters.md#session). Always answers. |
| `profiling:textcache()` | `table` | Unprotected | [The rendered-text cache](counters.md#textcache). Always answers. |

| Rule | Detail |
|---|---|
| Dormant by design | A profiling addon reads and draws nothing until opened: a profiler that runs while you are not looking measures itself. Arm on a hotkey, read on the frames you watch, stop when the window closes. `history()` held as snapshots turns the graph into a timeline to scrub frame by frame, where a three-frame stutter is visible. |
| The handle | A stateless proxy that never goes stale, a [handle in the API's one shape](../../conventions.md#snapshots-vs-handles): a misspelt counter raises naming the ones there are; `tostring(profiling)` is `Profiling`. |
| The reads are snapshots | Plain tables of frozen numbers: walking hundreds of samples is that many table lookups, not bridge calls. |
| Two kinds of verb | Frame sampling (`frame()`, `history()`, `addons()`, `widgets()`, `passes()`, `gl()`, `overhead()`) exists only while [the switch](../README.md#client) is on. The [counters](counters.md) are pull-only numbers the client keeps anyway, answering armed or not at no cost. `scope()` and `measure()` always run your code and record only while armed. |
| Units | Every duration is milliseconds, except the byte counts in [`memory()`](counters.md#memory). |
| An absent key means "not measured", never zero | Everywhere on this surface: no connection, no `net()` keys; no world, no scene keys in `render()`; no `scene` key on a frame, since the 3D scene has no timing boundary of its own. Check with `if frame.gpuMs then …`, not `> 0`. |

## `frame()`

| Key | Type | Description |
|---|---|---|
| `frameno` | `number` | The client's frame counter. |
| `t` | `number` | Frame timestamp, seconds since client start. |
| `fps` | `number` | Frames per second, as the client's stats HUD computes it. |
| `ms` | `number` | This frame's total UI-thread time. |
| `msAvg` / `msMin` / `msMax` / `msP95` | `number` | Frame time over the whole history ring. |
| `idle` | `number` | Share of the last second spent waiting, `0.0`..`1.0`. |
| `latency` | `number` | UI-thread to GPU-fence lag. |
| `gpuMs` | `number` | GPU time; absent until the first fence lands. |
| `gpuFrameno` | `number` | Which frame `gpuMs` belongs to; absent with it. |
| `phases` | `table` | The UI thread's phase breakdown: `dwait`, `stick`, `utick`, `sessions`, `draw`, `aux`, `wait`, the client's own names, so the numbers line up with its profile window. |
| `render` | `table` | The render thread's phases: `tick`, `draw`, `swap`, `finish`. Lags by about one frame: that profile closes a frame on the next frame's fence. |
| `ui` | `number` | Widget-tree cost this frame. |
| `addons` | `number` | Lua time charged to addons this frame: the accounting the addon CPU watchdog uses, read rather than re-measured. |

> **GPU time arrives late.** GL timestamps come back through fences several frames after their frame, so the frame that just finished has none yet. `frame()` reports the newest frame whose GPU time has landed and names it in `gpuFrameno`, trailing `frameno`.

## `history(n)`

The ring holds several hundred frames, a handful of seconds at a normal framerate; a larger `n` is clamped, not an error. Each entry carries `frameno`, `t`, `ms`, `addons`, `phases`, and `gpuMs` only if that frame's GPU time resolved, so a frame graph skips the unresolved ones rather than drawing a dip to zero.

```lua
local frames = hafen.client():profiling():history(120)     -- the last ~2 seconds, oldest first
for index, frame in ipairs(frames) do
  drawBar(index, frame.ms, frame.gpuMs)                    -- frame.gpuMs may be nil
end
```

## When profiling is off

| Rule | Detail |
|---|---|
| Empty tables, never `nil` | `frame()`, `history()`, `addons()`, `widgets()`, `passes()`, `gl()` and `overhead()` return `{}`, so `for _, frame in ipairs(profiling:history(60)) do … end` does nothing while off. |
| The first sample | On the second frame after arming, since arming is next-frame: a freshly armed profiler answers empty for one frame. |
| `reset()` | Empties the ring and every per-addon, per-scope and per-widget figure, as arming does. The counters are unaffected; [`textcache()`](counters.md#textcache)'s tallies are left alone, since `reset()` owns the frame ring, not a cache's bookkeeping. |

---

## See Also

- [Counters](counters.md) — the ones that answer whether profiling is armed or not.
- [Attribution](attribution.md) — who spent the frame: addons, widgets, passes, GL, and the overhead.
- [`hafen.client():options()`](../README.md#client) — the switch that arms all of this.
- [Drawing](../../ui/drawing.md#text-is-cached-across-frames) — the cache `textcache()` reports on.

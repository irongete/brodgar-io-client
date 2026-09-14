# hafen.client: Performance Counters

Performance counter methods on `hafen.client():profiling()` are **pull-only**: they query existing internal client counters directly. They operate with zero overhead and return valid data whether or not frame profiling is armed.

```lua
local profiler = hafen.client():profiling()

local memory_info = profiler:memory()
hafen.log():write(string.format("Heap: %.1f MB / %.1f MB",
  memory_info.heapUsed / 1048576, memory_info.heapMax / 1048576))
```

---

## `memory()`

Reports Java Virtual Machine (JVM) heap usage and garbage collection statistics. All memory measurements are in **bytes**.

| Field | Type | Description |
|---|---|---|
| `heapUsed` | `number` | Current JVM heap memory utilized. |
| `heapFree` | `number` | Available memory in the currently allocated heap. |
| `heapTotal` | `number` | Total memory currently allocated by the JVM. |
| `heapMax` | `number` | Maximum memory limit configured for the JVM (`-Xmx`). |
| `allocPerFrame` | `number \| nil` | Smoothed estimate of bytes allocated per frame. |
| `gcCount` | `number` | Cumulative garbage collection cycles since client start. |
| `gcMs` | `number` | Cumulative time spent in garbage collection pause states. |

---

## `net()`

Reports network traffic and connection quality for the active character session:

```lua
local network_stats = profiler:net()
if network_stats.rtt then
  hafen.log():write(string.format("Ping: %.1f ms (±%.1f ms)", network_stats.rtt, network_stats.rttVar))
end
```

| Field | Type | Description |
|---|---|---|
| `packetsTx` / `packetsRx` | `number` | Total packets transmitted and received. |
| `bytesTx` / `bytesRx` | `number` | Total bytes transmitted and received. |
| `resentTx` / `resentRx` | `number` | Resent packets (packet loss indicator). |
| `reorderedRx` | `number` | Packets arriving out of sequence. |
| `rtt` / `rttVar` | `number` | Smoothed round-trip time and jitter in milliseconds. |

---

## `loader()`

Reports status of asynchronous background asset and resource loaders:

| Field | Type | Description |
|---|---|---|
| `queued` | `number` | Tasks waiting in the primary UI asset loader queue. |
| `loading` | `number` | Tasks currently undergoing decoding. |
| `busy` | `number` | Worker threads actively processing tasks. |
| `poolSize` | `number` | Total threads allocated in the loader pool. |
| `defer` | `table` | Background queue stats: `{queued, busy, poolSize}`. |
| `resQueue` / `resLoaded` | `number` | Haven resource queue depth and completed downloads. |

---

## `render()`

Reports 3D scene graph, mesh, and GPU resource usage:

| Field | Type | Description |
|---|---|---|
| `drawSlots` | `number` | Number of draw slots submitted to the 3D pipeline. |
| `uniqueInstances` | `number` | Geometry drawn without instancing. |
| `batches` / `instances` | `number` | Instanced draw calls and total instances rendered. |
| `treeLeaves` / `treeNodes` | `number` | Spatial scene graph hierarchy node counts. |
| `programs` | `number` | Active compiled shader programs. |
| `vram` | `table` | VRAM buffer stats: `indices`, `vertices`, `textures`, `vaos`, `fbos`. |
| `gobsHeld` | `number` | Game objects deferred until `GobAdded` events complete. |
| `overlayMeshes` | `number` | Active ground terrain overlay mesh pieces. |
| `recallGridsHeld` | `number` | Explored RTS terrain grids cached in memory. |
| `recallGridsRead` | `number` | Explored terrain grids loaded from disk. |

---

## `surfaces()`

Monitors [3D in-world UI widgets](../../virtual/widgets.md) rendered onto world surfaces:

```lua
local surface_stats = profiler:surfaces()
hafen.log():write(string.format("Surfaces: %d active, %d culled",
  surface_stats.live, surface_stats.culled))
```

| Field | Type | Description |
|---|---|---|
| `live` | `number` | Total active in-world UI widget surfaces across all addons. |
| `culled` | `number` | Surfaces skipped due to occlusion or camera angle. |
| `uploads` | `number` | Cumulative texture upload operations since client start. |
| `frames` | `number` | Cumulative frames surfaces were rendered. |

---

## `entities()`

Monitors [virtual entities](../../virtual/README.md) (ghosts, models, markers) placed in the world:

| Field | Type | Description |
|---|---|---|
| `placed` | `number` | Virtual entities actively positioned in the world coordinate space. |
| `waiting` | `number` | Entities waiting for terrain or coordinate grid resolution. |
| `passes` | `number` | Number of spatial coordinate recalculation passes. |

---

## `session()`

Monitors multi-account session management and addon engine lifecycle:

| Field | Type | Description |
|---|---|---|
| `live` | `number` | Total active character sessions connected to the server. |
| `states` | `number` | Sessions maintaining active client state. |
| `groundAnswered` | `number` | Terrain height queries resolved by background sessions. |
| `addonsLive` | `number` | Number of actively executing addons. |
| `engineReloads` | `number` | Unprompted engine reloads (expected to remain `0`). |

---

## `textcache()`

Monitors the glyph and text rasterization cache used by [`graphics:text()`](../../ui/drawing.md#text-is-cached-across-frames). Data at the root of the table represents the current addon's private cache; `total` aggregates all addons and the client.

```lua
local text_cache_stats = profiler:textcache()

hafen.log():write(string.format("Text cache: %d entries (%.2f KB), Hit rate: %.1f%%",
  text_cache_stats.entries, text_cache_stats.bytes / 1024, (text_cache_stats.hitRate or 0) * 100))
```

| Field | Type | Description |
|---|---|---|
| `entries` | `number` | Cached text string textures currently retained. |
| `bytes` | `number` | GPU texture memory consumed by cached strings. |
| `hits` / `misses` | `number` | Cache hits vs newly rasterized string requests. |
| `evictions` | `number` | Strings dropped from cache to respect limits. |
| `hitRate` | `number \| nil` | Ratio of hits to total requests (`0.0`–`1.0`). |
| `maxEntries` / `maxBytes` | `number` | Capacity caps defined for the cache. |
| `total` | `table` | Aggregate stats across all addons and client subsystems. |

---

## See Also

- [Profiling Overview](README.md) — Frame profiling overview and history ring.
- [Attribution](attribution.md) — Attribution by addon, widget, and render pass.
- [`hafen.client():options()`](../README.md) — Client settings.

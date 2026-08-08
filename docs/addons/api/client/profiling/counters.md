# hafen.client: the counters

`memory()`, `net()`, `loader()`, `render()`, `surfaces()` and `textcache()` are **pull-only**: they read
counters the client keeps anyway, so they answer with [profiling](README.md) off and cost nothing while you
are not asking. The first four are the numbers the client's own stats HUD formats, and always agree with it
field by field.

Nothing here is sampled over time — each call is the value right now. The exceptions are the running
tallies, and each says so: `surfaces()`'s uploads and frames are cumulative since the client started, and
`textcache()`'s hit, miss and eviction totals since the addon loaded.

**An absent key means "not measured", never zero.**

## `memory()`

**Sizes are in bytes**; this is the one place the surface is not in milliseconds.

| Key | Description |
|---|---|
| `heapUsed` / `heapFree` / `heapTotal` / `heapMax` | the JVM heap, as the runtime reports it |
| `allocPerFrame` | the client's own smoothed per-frame allocation estimate |
| `gcCount` / `gcMs` | collections and time spent collecting, **cumulative since client start** |

`gcCount` and `gcMs` only mean something as a **delta between two reads**: take one, wait, take another.
`allocPerFrame` is the estimate behind the HUD's memory line, and the client only advances it while that
HUD is drawn, so the key is **absent** until it has been computed at least once. Measuring it every frame
instead would put a heap read on the frame loop whether profiling is armed or not.

## `net()`

Empty while there is no connection, such as on the login screen. `rtt` and `rttVar` are in milliseconds.

| Key | Description |
|---|---|
| `packetsTx` / `packetsRx` / `bytesTx` / `bytesRx` | the traffic counters, cumulative for the session |
| `resentTx` / `resentRx` | packets re-sent or received twice |
| `reorderedRx` | packets that arrived out of order |
| `rtt` / `rttVar` | smoothed round-trip time and its deviation |

These are written on the connection worker, so a read may be one packet behind. That is by design:
exactness here would mean locking a path nothing needs to be exact on.

## `loader()`

| Key | Description |
|---|---|
| `queued` / `loading` / `busy` / `poolSize` | the UI resource loader |
| `defer` | the shared background pool: `{queued=, busy=, poolSize=}` |
| `resQueue` / `resLoaded` | resource fetch queue depth, and resources resolved so far |

The four loader numbers are taken under one lock, so they are mutually consistent: a queue that just
emptied never shows up as idle with the work still in flight.

## `render()`

Describes the **3D scene**, so everything but `stateSlots` is absent before the world is up.

| Key | Description |
|---|---|
| `drawSlots` | draw slots this frame — as close to "draw calls" as the render tree gets |
| `uniqueInstances` / `batches` / `instances` | the batching split: un-instanced slots, instanced batches, instances in them |
| `invalid` / `bypass` | slots pending revalidation, and slots that cannot be instanced at all |
| `treeLeaves` / `treeNodes` | scene-tree size |
| `programs` | shader programs the GL environment holds |
| `vram` | per-pool VRAM, keyed `indices`/`vertices`/`textures`/`vaos`/`fbos`, each `{objects=, bytes=}` |
| `stateSlots` | render-state slots in use, process-wide rather than per scene |

`programs` and `vram` need a GL environment and are absent on any other backend. The counters are written
on the render side and may be one frame stale.

```lua
local r = hafen.client():profiling():render()
if r.drawSlots then
  hafen.log():write(string.format("%d slots, %d batches, %.1f MB textures",
                          r.drawSlots, r.batches, r.vram.textures.bytes / 1048576))
end
```

## `surfaces()`

The [widgets standing in the 3D world](../../vr/widgets.md), and what drawing them costs. It counts every
addon's, not only your own — a panel is a texture and a widget subtree wherever it came from.

| Key | Description |
|---|---|
| `live` | how many surfaces exist right now, across every addon |
| `culled` | how many of those are being skipped this instant, because nothing is looking at them |
| `uploads` | offscreen passes actually issued, **cumulative since client start** |
| `frames` | frames those passes were offered, **cumulative since client start** |

`live` and `culled` are instantaneous counts, never totals. A surface is culled when the camera is pointing
elsewhere, when the entity is hidden, or when the game object it stands on has left the scene — and being
culled is not being gone: the collection still holds it, its `Tick` still fires, and it draws again the
first frame it is looked at.

`uploads` and `frames` mean something as a **delta between two reads**: take one, wait, take another. That
pair is the whole cost claim. A panel nothing changes holds `uploads` still while `frames` climbs; a panel
painted by a `widget:on("Draw", …)` handler moves them together, because a Lua function of anything can only
be known by running it.

`live` going back to zero is also the **leak check**: `:reload`, or disabling every addon, ends each
standing entity through the same body `:remove` uses, freeing the texture rather than forgetting about it.

```lua
local a = hafen.client():profiling():surfaces()
hafen.timer():after(2, function()
  local b = hafen.client():profiling():surfaces()
  hafen.log():write(string.format("%d standing (%d culled), %d uploads over %d frames",
                          b.live, b.culled, b.uploads - a.uploads, b.frames - a.frames))
end)
```

## `textcache()`

The rendered-text cache that [`g:text` and `g:atext`](../../ui/drawing.md#text-is-cached-across-frames)
draw through. The cache is **per addon**, so the top level is **your own**; `total` sums every Lua owner.

| Key | Description |
|---|---|
| `entries` / `bytes` | cached strings held right now, and the GL texture bytes they occupy |
| `hits` / `misses` / `evictions` | lookups served from the cache, rasterised, or dropped to stay within the caps |
| `hitRate` | `hits / (hits + misses)`, `0.0`..`1.0` — **absent** until something has been looked up |
| `maxEntries` / `maxBytes` | the two caps the cache is bounded by; an entry count says nothing without its ceiling |
| `total` | the same five figures summed over every Lua owner, plus `owners`, how many were summed |

`hits`, `misses` and `evictions` are **cumulative since the addon loaded**: a `:reload` builds a fresh cache
and a fresh count, and [`reset()`](README.md) deliberately does not touch them.  `entries` and `bytes` are
the live state.

**How to read a miss.** A miss is not a fault: it is a string that had never been drawn in that font, and it
costs exactly what every text draw cost before the cache existed. A line whose text changes every frame
misses every frame and always will — that is the
[budgeting rule](../../ui/drawing.md#text-is-cached-across-frames). Likewise a permanently full,
permanently evicting cache is not a problem: `evictions` climbing while `hitRate` stays high means the
volatile strings are aging out and the static ones are being reused.

`total` is also the **leak check**: disable every addon, or `:reload`, and `total.bytes` goes to nearly
zero, because teardown drops each cache and disposes its textures.

```lua
local c = hafen.client():profiling():textcache()
hafen.log():write(string.format("%d entries / %.2f MiB, %.1f%% hit rate (%d evictions)",
                        c.entries, c.bytes / 1048576, (c.hitRate or 0) * 100, c.evictions))
```

## See also

- [profiling](README.md) — the handle, `frame()`, `history()` and what the switch changes
- [attribution](attribution.md) — the armed-only half: who spent the frame
- [drawing](../../ui/drawing.md#text-is-cached-across-frames) — the cache `textcache()` describes
- [widgets in the world](../../vr/widgets.md) — what `surfaces()` counts, and when one stops drawing

# hafen.client: The Counters

`memory()`, `net()`, `loader()`, `render()`, `surfaces()`, `entities()`, `session()` and `textcache()` are pull-only. They read counters the client keeps anyway, so they answer with [profiling](README.md) off and cost nothing while you are not asking. The first four are the numbers the client's own stats HUD formats, field by field.

```lua
local render = hafen.client():profiling():render()
if render.drawSlots then
  local megabytes = math.floor(render.vram.textures.bytes / 1048576 * 10 + 0.5) / 10   -- one decimal by hand: %.1f prints the raw double
  hafen.log():write(string.format("%d slots, %d batches, %s MB textures", render.drawSlots, render.batches, megabytes))
end
```

---

| Rule | Detail |
|---|---|
| The value right now | Nothing here is sampled over time, except the running tallies, each marked cumulative below. Those are `surfaces()`'s uploads and frames, `entities()`'s passes, and `session()`'s ground and click counters since the client started. `textcache()`'s hits, misses and evictions since the addon loaded. A cumulative counter means something as a delta between two reads. |
| An absent key means "not measured", never zero | Everywhere on this surface. |
| Read-only in the strict sense | Each group takes no argument, and passing one raises: `profiling:net(1)` is a refusal. The one argument on the surface is `profiling:history(n)`, a whole number. A negative one raises, and more frames than the ring holds answers all of them. |

## `memory()`

Sizes are in bytes, the one place the surface is not in milliseconds. [`hafen.client():memory()`](../README.md#the-frame-rate-and-the-heap) answers this same table.

| Key | Description |
|---|---|
| `heapUsed` / `heapFree` / `heapTotal` / `heapMax` | The JVM heap, as the runtime reports it. |
| `allocPerFrame` | The client's smoothed per-frame allocation estimate, the figure behind the HUD's memory line. Advanced only while that HUD is drawn, so absent until computed at least once. |
| `gcCount` / `gcMs` | Collections and time spent collecting, cumulative since client start: a delta between two reads. |

## `net()`

Empty while there is no connection (the login screen). `rtt` and `rttVar` are milliseconds.

| Key | Description |
|---|---|
| `packetsTx` / `packetsRx` / `bytesTx` / `bytesRx` | The traffic counters, cumulative for the session. |
| `resentTx` / `resentRx` | Packets re-sent or received twice. |
| `reorderedRx` | Packets that arrived out of order. |
| `rtt` / `rttVar` | Smoothed round-trip time and its deviation. |

| Rule | Detail |
|---|---|
| The character on screen's | As `loader()` and `render()` are: a connection is per login, so a handler for a background character reads the drawn character's traffic. Hand that character the screen with [`hafen.session():current(session)`](../../session.md) to read its own. |
| One packet behind | Written on the connection worker. Exactness would lock a path nothing needs exact. |

## `loader()`

| Key | Description |
|---|---|
| `queued` / `loading` / `busy` / `poolSize` | The UI resource loader. Taken under one lock, so mutually consistent. |
| `defer` | The shared background pool: `{queued=, busy=, poolSize=}`. |
| `resQueue` / `resLoaded` | Resource fetch queue depth, and resources resolved so far. |

## `render()`

The 3D scene: everything but `stateSlots`, `gobsHeld` and the two overlay counters is absent before the world is up. Written on the render side, so one frame stale.

| Key | Description |
|---|---|
| `drawSlots` | Draw slots this frame, the render tree's nearest thing to draw calls. |
| `uniqueInstances` / `batches` / `instances` | The batching split: un-instanced slots, instanced batches, instances in them. |
| `invalid` / `bypass` | Slots pending revalidation, and slots that cannot be instanced. |
| `culled` / `cullable` | Slots frustum culling leaves out of the draw this frame because the camera cannot see them, and how many it could test at all; `0` of both with the Performance page's *Frustum culling* off. |
| `treeLeaves` / `treeNodes` | Scene-tree size. |
| `programs` | Shader programs the GL environment holds. Absent on any other backend. |
| `vram` | Per-pool VRAM, keyed `indices`/`vertices`/`textures`/`vaos`/`fbos`, each `{objects=, bytes=}`. Absent without a GL environment. |
| `stateSlots` | Render-state slots in use, process-wide. |
| `gobsHeld` | Game objects kept out of the scene until their `GobAdded` fired, cumulative since client start. |
| `overlayMeshes` / `overlayOutlines` | Ground-overlay pieces laid over the terrain, and the outlines over those, cumulative since client start. |
| `recallGridsHeld` | Grids of [remembered ground](../README.md#client) held right now. |
| `recallGridsRead` | Grids of it read back off the record, cumulative since the world came up. |
| `recallCutsDrawn` / `recallCutsWanted` | Pieces of remembered ground in the scene right now, and how many the client wanted there. |

| Rule | Detail |
|---|---|
| `gobsHeld` | The addon layer's tally, not the scene's, so it answers on the login screen. Climbs each time the client holds an object back so [`GobAdded`](../../event/bus/world.md#before-the-first-drawn-frame) runs before its first drawn frame. Zero while no addon subscribes. Read as a delta: take one, walk into ground unseen this session, take another. |
| `overlayMeshes`, `overlayOutlines` | Terrain work, counted outside the scene. The client cuts the ground into squares. Every ground overlay (a [patch](../../virtual/patches.md) you lay, a claim, a province) is laid over each cut its shape reaches. It is laid once as the sheet, and again as the outline where it has one. |
| What a patch moves | Laying one moves `overlayMeshes` by the cuts its [pieces](../../virtual/pieces.md) reach and leaves `overlayOutlines` alone, since a patch's [edge](../../virtual/patches.md#the-border) is carved into the sheet. Wearing, widening or taking one up moves neither. Read as a delta: lay one with fifty on the ground and it moves by what one costs. |
| The `recall` keys | Three gauges: held, drawn, wanted at this instant. One cumulative: `recallGridsRead`, climbing while the record is read back until it catches up with the camera. Cuts drawn reaching cuts wanted is the feature's claim as a number. Absent until the world is up. Then `0` is a count: with the setting off or another camera, drawn and wanted fall to zero. Grids held falls only when the client's budget drops a grid, since ground read back is kept for a camera that returns. |

## `surfaces()`

The [widgets standing in the 3D world](../../virtual/widgets.md), every addon's, and what drawing them costs.

| Key | Description |
|---|---|
| `live` | Surfaces that exist right now, across every addon. |
| `culled` | How many of those are skipped this instant, because nothing is looking at them. |
| `uploads` | Offscreen passes issued, cumulative since client start. |
| `frames` | Frames those passes were offered, cumulative since client start. |

| Rule | Detail |
|---|---|
| Culled is not gone | A surface is culled when the camera points elsewhere, the entity is hidden, or its game object left the scene. The collection still holds it, its `Update` fires, and it draws again when looked at. |
| The cost claim | `uploads` and `frames` as a delta: a panel nothing changes holds `uploads` still while `frames` climbs. A panel painted by a `widget:on("Draw", …)` handler moves them together, since a Lua function of anything is known only by running it. |
| The leak check | `live` returning to zero on `:reload` or disabling every addon: each standing entity ends through the body `:remove` uses, freeing the texture. |

```lua
local before = hafen.client():profiling():surfaces()
hafen.timer():after(2, function()
  local after = hafen.client():profiling():surfaces()
  hafen.log():write(string.format("%d standing (%d culled), %d uploads over %d frames",
                          after.live, after.culled, after.uploads - before.uploads, after.frames - before.frames))
end)
```

## `entities()`

The client-only things [standing at a point in the world](../../virtual/README.md) (a ghost, a sprite, a model, a panel) and what keeping them there costs. One standing on a game object is not counted: its place is that object's.

| Key | Description |
|---|---|
| `placed` | How many stand at a point right now, across every addon. |
| `waiting` | How many of those hold a place this session cannot locate. |
| `passes` | Times the client has worked out where they all are, cumulative since client start. |

| Rule | Detail |
|---|---|
| Waiting | The place is real but has no coordinate here. Ground recorded in another part of the world. Every place above while underground. Every place off the streamed ground for the moment after a re-base, until this session's [base](../../position.md) is proved. It answers every verb, reports the place it was given, and stands up when that ground resolves. |
| `passes` | Climbs by a few while you walk and holds still while you stand. Where those things are is worked out when the world moves under them, and at no other time. |

## `session()`

How many accounts the client holds logged in, and what it answered for them. The client keeps several sessions open (`:session add`) and draws one. The rest tick and answer the server with no view of their own, their ground merged into the scene you see.

| Key | Description |
|---|---|
| `live` | Sessions the client holds right now, the one on screen included: `1` with one account in the world, `0` on the login screen. |
| `states` | How many of those hold client-side state of their own, which is every one of them. |
| `groundAnswered` | Times another session supplied the ground height under the camera, cumulative since client start. |
| `groundMissed` | Times none of them had that ground, so the camera kept its last height, cumulative since client start. |
| `placedRebuiltOffTick` | Times a click was resolved before the client had said where the sessions stand, cumulative since client start. |
| `addonsLive` | How many addons are running right now. |
| `engineReloads` | Times the client rebuilt the addon layer without being asked. |

| Rule | Detail |
|---|---|
| `states` equals `live` | The client keeps a set of caches per session. They hold the widgets your selectors matched, the objects seen, and the slots your addon holds on that character's bar. They are made when first needed and dropped when the session ends. Above `live` is a gone session still remembered. Below it is one joined and not yet needing anything, true between logging in and the world coming up. |
| The ground pair | Climbs only while a free camera looks at ground the drawn session never loaded (another character's surroundings). `0` means the query has not run. With `live == 1` only `groundMissed` can climb. Read as a delta: pan the camera between reads. |
| `addonsLive`, `engineReloads` | The addon layer's pair. `addonsLive` does not move when you switch character: your addon is loaded once for the client. `engineReloads` counts rebuilds nobody asked for (a `:reload` you typed does not count), and its only meant value is zero. Anything above is an addon torn down and reloaded behind your back, every Lua value it held gone with no event. |
| `placedRebuiltOffTick` | Only meant to hold zero. Where each session stands is worked out once a frame. A ground click is resolved off the frame's thread a frame or so after the button went down. This counts clicks that got there first. Each is a click on another session's merged ground answered in the drawn session's coordinates. That puts the destination as far off as the two characters are apart. |

```lua
local before = hafen.client():profiling():session()
hafen.timer():after(5, function()
  local after = hafen.client():profiling():session()
  hafen.log():write(string.format("%d sessions (%d holding state), %d addons",
                          after.live, after.states, after.addonsLive))
  hafen.log():write(string.format("%d answers, %d misses over 5 s; %d early, %d unasked reloads",
                          after.groundAnswered - before.groundAnswered, after.groundMissed - before.groundMissed,
                          after.placedRebuiltOffTick, after.engineReloads))
end)
```

## `textcache()`

The rendered-text cache [`graphics:text` and `graphics:atext`](../../ui/drawing.md#text-is-cached-across-frames) draw through. Per addon: the top level is your own, `total` sums every Lua owner.

| Key | Description |
|---|---|
| `entries` / `bytes` | Cached strings held right now, and the GL texture bytes they occupy. |
| `hits` / `misses` / `evictions` | Lookups served from the cache, rasterised, or dropped to stay within the caps. Cumulative since the addon loaded. |
| `hitRate` | `hits / (hits + misses)`, `0.0`..`1.0`. Absent until something has been looked up. |
| `maxEntries` / `maxBytes` | The two caps the cache is bounded by. |
| `maxEntryBytes` | The size past which one raster is drawn and dropped rather than kept ([text is cached across frames](../../ui/drawing.md#text-is-cached-across-frames)). |
| `total` | The same figures summed over every Lua owner, plus `owners`, how many were summed. |

| Rule | Detail |
|---|---|
| Cumulative since the addon loaded | A `:reload` builds a fresh cache and count. [`reset()`](README.md) does not touch them. `entries` and `bytes` are live state. |
| A miss is not a fault | A string never drawn in that font, costing what every text draw cost before the cache. A line whose text changes every frame misses every frame ([the budgeting rule](../../ui/drawing.md#text-is-cached-across-frames)). A full, evicting cache with a high `hitRate` is the volatile strings ageing out and the static ones reused. |
| The leak check | Disable every addon, or `:reload`, and `total.bytes` goes to nearly zero: teardown drops each cache and disposes its textures. |

```lua
local cache = hafen.client():profiling():textcache()
local mebibytes = math.floor(cache.bytes / 1048576 * 100 + 0.5) / 100
local hit_rate = math.floor((cache.hitRate or 0) * 1000 + 0.5) / 10
hafen.log():write(string.format("%d entries / %s MiB, %s%% hit rate (%d evictions)",
                        cache.entries, mebibytes, hit_rate, cache.evictions))
```

---

## See Also

- [Profiling](README.md) — the handle, `frame()`, `history()` and what the switch changes.
- [Attribution](attribution.md) — the armed-only half: who spent the frame.
- [Drawing](../../ui/drawing.md#text-is-cached-across-frames) — the cache `textcache()` describes.
- [Widgets in the world](../../virtual/widgets.md) — what `surfaces()` counts, and when one stops drawing.
- [Things in the world](../../virtual/README.md) — what `entities()` counts, and what a place that waits is.

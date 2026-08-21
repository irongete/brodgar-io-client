# hafen.client: profiling

`hafen.client():profiling()` is the read surface over the client's frame profiler — the same per-frame CPU
and GPU trees the client's own profile windows draw, not a second profiler. Reach for it to answer "where
did this frame go", in your own addon or across the whole client. Unprotected.

```lua
local p = hafen.client():profiling()

local f = p:frame()
hafen.log():write(string.format("%d fps, %.2f ms (ui %.2f, addons %.2f)",
                               f.fps, f.ms, f.ui, f.addons))
```

The bundled **`profiler`** addon is a worked example of everything on these three pages: a six-tab window
with the frame graph and phases, render passes and GL counters, per-widget cost, per-addon cost with
scopes, the pull-only counters, and the overhead accounting. It is **dormant** — nothing is read or drawn
until you open it — which is the shape any profiling addon should have. It also shows what `history()` is
*for*: pausing freezes the snapshots and turns the graph into a timeline you scrub frame by frame.

## Read

| Method | Returns | Description |
|---|---|---|
| `frame()` | table | the frame that just finished — **armed only** |
| `history(n)` | array of tables | the last `n` frames, **oldest first**; `n` omitted is everything held — **armed only** |
| `addons()` | array of tables | [what each addon's Lua cost](attribution.md#addons), most expensive first — **armed only** |
| `widgets()` | table | [where the UI's frame time went](attribution.md#widgets) — **armed only** |
| `passes()` | array of tables | [the named render passes](attribution.md#passes), CPU and GPU side by side — **armed only** |
| `gl()` | table | [what the client handed the driver](attribution.md#gl) — **armed only** |
| `overhead()` | table | [what profiling itself costs](attribution.md#overhead), per tier — **armed only** |
| `scope(name)` | a scope handle | [a named marker](attribution.md#custom-scopes) you bracket your own code with |
| `measure(name, fn, ...)` | whatever `fn` returns | run `fn` inside the scope `name` |
| `reset()` | the handle | drop the history and start measuring afresh; chains |
| `memory()` | table | [JVM heap and allocation](counters.md#memory) — **always answers** |
| `net()` | table | [packet counters and round-trip time](counters.md#net) — **always answers** |
| `loader()` | table | [async queue depths](counters.md#loader) — **always answers** |
| `render()` | table | [graphics counters](counters.md#render) — **always answers** |
| `surfaces()` | table | [widgets standing in the world](counters.md#surfaces) — **always answers** |
| `entities()` | table | [things standing at a point in the world](counters.md#entities) — **always answers** |
| `session()` | table | [what the other sessions answered](counters.md#session) — **always answers** |
| `textcache()` | table | [the rendered-text cache](counters.md#textcache) — **always answers** |

The handle is a stateless proxy: keep it in a variable forever and it never goes stale. It is a
[handle in the API's one shape](../../conventions.md#snapshots-vs-handles), so a misspelt counter raises
naming the ones there are, and `tostring(p)` is `Profiling`. What the read verbs
answer are plain **snapshot tables**, not handles — frozen numbers with nothing to re-resolve, so walking
hundreds of samples for a frame graph is that many table lookups, not that many bridge calls.

**Two kinds of verb.** `frame()`, `history()`, `addons()`, `widgets()`, `passes()`, `gl()` and `overhead()`
are *frame sampling*: they exist only while [the switch](../README.md#client) is on. The
[counters](counters.md) below them are *pull-only* — every number in them is one the client already keeps
for its own reasons — so they answer whether profiling is armed or not, and reading them costs nothing when
it is not.
`scope()` and `measure()` sit across both: they are always callable and always run your code, and only
*record* while armed.

**Every duration is in milliseconds**, except the byte counts in [`memory()`](counters.md#memory).

## `frame()`

| Key | Type | Description |
|---|---|---|
| `frameno` | number | the client's frame counter |
| `t` | number | frame timestamp, seconds since client start |
| `fps` | number | frames per second, as the client's own stats HUD computes it |
| `ms` | number | this frame's total UI-thread time |
| `msAvg` / `msMin` / `msMax` / `msP95` | number | frame time over the whole history ring |
| `idle` | number | share of the last second spent waiting, `0.0`..`1.0` |
| `latency` | number | UI-thread to GPU-fence lag |
| `gpuMs` | number | GPU time — see below |
| `gpuFrameno` | number | which frame `gpuMs` belongs to |
| `phases` | table | the UI thread's own phase breakdown |
| `render` | table | the render thread's phase breakdown |
| `ui` | number | widget-tree cost this frame |
| `addons` | number | Lua time charged to addons this frame |

`phases` carries the client's own phase names — `dwait`, `stick`, `utick`, `sessions`, `draw`, `aux`,
`wait` — so the numbers line up with the client's profile window field by field. `render` is the render
thread's group (`tick`, `draw`, `swap`, `finish`) and **lags by about one frame**: that profile closes a
frame on the next frame's fence, and the API reports what the client measured rather than re-timing it.

`addons` is the same accounting the addon CPU watchdog uses, read rather than re-measured.

> **GPU time arrives late.** GL timestamps come back through fences several frames after the frame they
> belong to, so the frame that just finished essentially never has one yet. `frame()` reports the newest
> frame whose GPU time *has* landed and tells you which one that is in `gpuFrameno`, so expect it to trail
> `frameno`. Until the first one lands, **both keys are absent**.

**An absent key means "not measured", never zero.** That is why there is no `scene` key: the 3D scene has
no timing boundary of its own, and a `0` would read as "free". Check with `if f.gpuMs then …` rather than
comparing against 0.

## `history(n)`

The ring holds several hundred frames, a handful of seconds at a normal framerate. A larger `n` is clamped
to what is held, and is not an error. Each entry carries `frameno`, `t`, `ms`, `addons`, `phases`, and
`gpuMs` **only if** that frame's GPU time resolved, so a frame graph skips the unresolved ones instead of
drawing them as a dip to zero.

```lua
local h = hafen.client():profiling():history(120)     -- the last ~2 seconds, oldest first
for i, f in ipairs(h) do
  drawBar(i, f.ms, f.gpuMs)                         -- f.gpuMs may be nil
end
```

## When profiling is off

`frame()`, `history()`, `addons()`, `widgets()`, `passes()`, `gl()` and `overhead()` return an **empty
table**, never `nil`, so no branch is needed in addon code:

```lua
for _, f in ipairs(p:history(60)) do … end          -- simply does nothing while off
```

The first valid sample arrives on the **second** frame after arming, since arming itself is next-frame, so
a freshly armed profiler answers empty for one frame. `reset()` empties the ring and every per-addon,
per-scope and per-widget figure the same way, as does arming the switch. The counters are unaffected —
they answer the same numbers armed or not, and `reset()` deliberately leaves
[`textcache()`](counters.md#textcache)'s tallies alone, since it owns the frame ring rather than a cache's
own bookkeeping.

**An absent key means "not measured", never zero**, everywhere in this surface: no connection, no `net()`
keys; no world yet, no scene keys in `render()`. Check with `if r.drawSlots then …`, not `> 0`.

## See also

- [counters](counters.md) — the ones that answer whether profiling is armed or not
- [attribution](attribution.md) — who spent the frame: addons, widgets, passes, GL, and the overhead
- [`hafen.client():options()`](../README.md#client) — the switch that arms all of this
- [drawing](../../ui/drawing.md#text-is-cached-across-frames) — the cache `textcache()` reports on

# hafen.client — client settings, hotkeys & profiling

`hafen.client:options()` opens the settings the client's **Options window** edits, plus the hotkey
registry; `hafen.client:profiling()` reads the client's frame profiler. One subsystem per Options panel:

```lua
local opts = hafen.client:options()

opts:interface()      -- UI scale, fine-placement granularity
opts:video()          -- shadows, render scale, vsync, framerate, lighting
opts:audio()          -- volumes and output latency
opts:camera()         -- camera drag inversion
opts:client()         -- client-wide toggles (profiling)
opts:keybindings()    -- register / inspect / remap hotkeys
```

Every handle is a **stateless proxy** over the client's live preference stores — nothing is cached, so
a handle you keep in a variable never goes stale, and a write from Lua is indistinguishable from the
same edit made in the Options window (same stores, same persistence, and the panel shows your value the
next time it is opened).

## Reading and writing

**The arity is the verb.** Calling an option with no argument reads it; calling it with one argument
writes it and returns the subsystem handle, so writes chain:

```lua
local scale = opts:interface():scale()            -- read  -> 1.0
opts:interface():scale(1.2):posGran(5)            -- write, write, chained

opts:video():shadows(true):vsync(false):lightLimit(8)
```

There is no `get`/`set` pair — one name per option, one canonical way. Always use a **colon** call with
at most one argument; anything else is an error.

Invalid values raise a Lua error rather than being clipped (`scale(-1)`, `masterVolume(3)`,
`lightingMode("fancy")`), so a bad write fails loudly instead of silently doing nothing.

## `interface()`

| Method | Type | Description |
|---|---|---|
| `scale()` / `scale(v)` | number | UI scale, `1.0` = native. **Requires a restart** — read once at startup |
| `posGran()` / `posGran(v)` | number | object fine-placement position granularity: subdivisions per tile (`2`..`17`), or `0` for infinite (unsnapped). Applies live |
| `angGran()` / `angGran(deg)` | number | object fine-placement angle granularity, **in degrees** per step. Applies live |

`scale` is the one option here that a restart gates: the write is persisted immediately (exactly like
the panel's "requires restart" slider), and the client picks it up on the next launch. `angGran` crosses
this API as degrees — the value the Options panel displays — not the divisor the client stores
internally.

```lua
hafen.log("ui scale: " .. opts:interface():scale())
opts:interface():angGran(15)                       -- 24 steps per full turn
```

## `video()`

Backed by the client's graphics settings, over the same seam the Options window uses.

| Method | Type | Description |
|---|---|---|
| `shadows()` / `shadows(b)` | bool | shadow rendering |
| `renderScale()` / `renderScale(v)` | number | render resolution multiplier |
| `vsync()` / `vsync(b)` | bool | vertical sync |
| `fpsLimit()` / `fpsLimit(v)` | number | foreground framerate cap; `math.huge` = no limit |
| `bgFpsLimit()` / `bgFpsLimit(v)` | number | background (unfocused) framerate cap; `math.huge` = no limit |
| `lightingMode()` / `lightingMode(s)` | string | `"simple"` or `"zoned"` |
| `lightLimit()` / `lightLimit(n)` | number | maximum simultaneous dynamic lights |

`math.huge` round-trips verbatim on both framerate limits — reading an uncapped limit gives you
`math.huge`, and writing it removes the cap.

A rejected value (one the renderer refuses) raises a Lua error carrying the client's own message.

```lua
local v = opts:video()
hafen.log("shadows: " .. tostring(v:shadows()) .. ", lighting: " .. v:lightingMode())
v:lightingMode("zoned"):lightLimit(16)
```

## `audio()`

| Method | Type | Description |
|---|---|---|
| `masterVolume()` / `masterVolume(v)` | number | master volume, `0.0`..`1.0` |
| `uiVolume()` / `uiVolume(v)` | number | interface sounds, `0.0`..`1.0` |
| `eventVolume()` / `eventVolume(v)` | number | in-game event sounds, `0.0`..`1.0` |
| `ambientVolume()` / `ambientVolume(v)` | number | ambient sound, `0.0`..`1.0` |
| `latency()` / `latency(ms)` | number | output buffer, in **milliseconds** |

Volumes are `0.0`..`1.0` here, not the panel's 0..1000 slider units; a value outside that range is an
error. `latency` is milliseconds (what the panel shows), not the sample count the engine stores, and
writing it reopens the audio output line.

To *play* sounds, see [`hafen.sound`](audio.md) — this subsystem only sets levels. `ambientVolume` is
the one that governs the world's ambient loops, i.e. what sounds like background music here.

## `camera()`

| Method | Type | Description |
|---|---|---|
| `invertHorizontal()` / `invertHorizontal(b)` | bool | invert horizontal camera drag |
| `invertVertical()` / `invertVertical(b)` | bool | invert vertical camera drag |

Both apply live, on the very next drag.

## `client()`

Client-wide toggles — the Options ▸ **Client** panel.

| Method | Type | Description |
|---|---|---|
| `profiling()` / `profiling(b)` | bool | arm the client's profiler |

**Profiling is the client's own profiler, not a second one.** Arming it is exactly what the console's
`:profile on` does — it makes the client build its per-frame CPU and GPU trees (the ones `Profwnd`
displays), which is what any profiling read surface is built on. One switch: the checkbox, `:profile`
and this option always agree, and the state is persisted like every other option.

It defaults **off** and should stay off unless you are measuring something. Off it costs nothing; on it
is a live instrumentation of every frame.

```lua
local c = hafen.client:options():client()

if not c:profiling() then c:profiling(true) end
```

A write moves an **open** panel's checkbox immediately — the panel re-reads the switch every frame, so
there is nothing to refresh.

> Arming takes effect on the **next** frame: the client decides at the start of each frame whether to
> build its profile trees, so the frame during which you flip the switch has none. This is expected.

What the armed profiler measures is read through [`hafen.client:profiling()`](#profiling) below.

## Before the client is up

`video()` and `audio()` read **`nil`** until the client's UI exists (their backing systems are built
with it), and a write in that window is ignored. `interface()`, `camera()` and `client()` always answer. In practice
this only matters if you touch options at load time on the login screen — guard the value, or do it from
`OnEnterWorld` ([events](events.md)):

```lua
local shadows = opts:video():shadows()
if shadows ~= nil then hafen.log("shadows: " .. tostring(shadows)) end
```

## `keybindings()`

The client's hotkey registry: declare your addon's hotkeys, and read or remap any binding, yours or the
client's own.

| Method | Returns | Description |
|---|---|---|
| `register(name, fn)` | the handle | declare a hotkey owned by your addon; `fn` runs when it fires |
| `get(name)` | string \| nil | current key as a display string (`"Ctrl+M"`), or nil if unbound/unknown |
| `set(name, key)` | the handle | remap a binding; `"None"` unbinds it |
| `unregister(name)` | the handle | drop one of *your* hotkeys |
| `list()` | `{ [id] = key }` | every binding in the client, unbound ones reading `"None"` |

`register`, `set` and `unregister` return the handle, so they chain.

### Addon hotkeys start unbound

`register` takes **no default key**. Your addon names an action; **the user assigns the key** in
Options ▸ Keybindings, where every addon that registered a hotkey gets its own section, listed by
addon name. This is the WoW model, and the only one consistent with the client's one-key-one-action
exclusivity — an addon-chosen default could not claim a key already in use anyway, it would just lose
the collision and leave you with a hotkey that never fires.

So **advertise a suggested key in your README instead of claiming one**:

> *Suggested key: `Ctrl+H` — assign it in Options ▸ Keybindings ▸ hello.*

The user's assignment is persisted by the client and survives `:reload` and restarts; re-registering the
same name after a reload picks the existing binding back up.

### Names

Your own hotkeys are namespaced to your addon, so `register("test", fn)` and `get("test")` refer to the
same binding without you ever spelling your addon id. A name that is not one of yours falls back to the
client's own registry id — that is how you reach a built-in hotkey (`get("inv")`,
`set("inv", "Ctrl+I")`). Your scope is tried first, so a client binding can never shadow yours.

`list()` uses the **full registry ids**, so your hotkeys appear there as `addon/<your-addon-id>/<name>`.

`set` on a name that matches no binding is an error; `unregister` only touches your own hotkeys —
client bindings are not an addon's to drop.

### Key strings

A key is the last `+`-separated token, with optional modifiers before it: `Ctrl`/`Control`/`Ctl`,
`Shift`, `Alt`/`Meta` (case-insensitive). Named keys are `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`,
`Esc`, `Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`,
`Right`; anything else is a single character. `"None"` means unbound.

```lua
"F5"   "Ctrl+M"   "Shift+Alt+Left"   "None"
```

Modifier matching is exact: `"M"` fires only on a bare `M`, never on `Ctrl+M`.

### Example

```lua
local keys = hafen.client:options():keybindings()

keys:register("toggle", function()
  hafen.log("toggled")
end)

hafen.log("my key: " .. tostring(keys:get("toggle")))     -- nil until the user assigns one
hafen.log("inventory: " .. tostring(keys:get("inv")))     -- a client binding, e.g. "Tab"

for id, key in pairs(keys:list()) do
  if key ~= "None" then hafen.log(id .. " = " .. key) end
end
```

Hotkeys are torn down with your addon on reload or disable — you do not need to `unregister` in
`OnDisable`. Use `unregister` only to drop a hotkey while your addon keeps running.

> Global hotkeys are not an input hook: they run through the client's binding registry, after the
> client's own bindings. To intercept raw keys and mouse input before any widget sees them, use
> [`hafen.hook`](hooks.md).

## `profiling()`

`hafen.client:profiling()` is the read surface over the client's frame profiler — the same per-frame
CPU and GPU trees the `Profwnd` windows draw, not a second profiler.

```lua
local p = hafen.client:profiling()

local f = p:frame()
hafen.log(string.format("%d fps, %.2f ms (ui %.2f, addons %.2f)", f.fps, f.ms, f.ui, f.addons))
```

The bundled **`profiler`** addon ("Brodgar.io Profiler") is a full worked example of everything below: a
six-tab window — frame graph and phases, render passes and GL counters, per-widget cost, per-addon cost
with scopes, the pull-only counters, and the overhead accounting. It is **dormant** (nothing is read or
drawn until you open it with its `toggle` hotkey or `:profiler`), which is the shape any profiling addon
should have. It also shows what `history()` is *for*: pausing freezes the snapshots and turns the graph
into a timeline you scrub frame by frame — the ring is the recording.

| Method | Returns | Description |
|---|---|---|
| `frame()` | table | the frame that just finished — **armed only** |
| `history(n)` | array of tables | the last `n` frames, **oldest first**; `n` omitted = everything held — **armed only** |
| `addons()` | array of tables | what each addon's Lua cost, most expensive first, plus `total` — **armed only** |
| `widgets()` | table | where the UI's frame time went, per widget type and per widget — **armed only** |
| `passes()` | array of tables | the named render passes, CPU and GPU time side by side — **armed only** |
| `gl()` | table | what the client handed the driver: draw calls, program binds, vertices, triangles — **armed only** |
| `overhead()` | table | what profiling itself costs, per tier — **armed only** |
| `scope(name)` | a scope handle | a named marker you bracket your own code with |
| `measure(name, fn, ...)` | whatever `fn` returns | run `fn` inside the scope `name` |
| `reset()` | the handle | drop the history and start measuring afresh (chains) |
| `memory()` | table | JVM heap, per-frame allocation, GC totals — **always answers** |
| `net()` | table | packet/byte counters and round-trip time — **always answers** |
| `loader()` | table | async queue depths (UI loader, `Defer` pool, resources) — **always answers** |
| `render()` | table | graphics counters: draw slots, batching, tree size, VRAM — **always answers** |
| `textcache()` | table | the rendered-text cache behind `g:text`/`g:atext`: entries, bytes, hits, misses — **always answers** |

The handle is a stateless proxy — keep it in a variable forever, it never goes stale. What the read
verbs answer are plain **snapshot tables**, not handles: frozen numbers with nothing to re-resolve, so
walking 600 samples for a frame graph is 600 table lookups, not 600 bridge calls.

**Two kinds of verb.** `frame()`/`history()`/`addons()`/`widgets()`/`passes()`/`gl()`/`overhead()` are *frame sampling*: they exist only while the
switch above (Options ▸ Client ▸ Enable profiling) is on. The five **counters** below are *pull-only* —
every number in them is one the client already keeps for its own reasons (the first four for the `:stats on`
HUD, `textcache()` because the cache counts hits and bytes in order to bound itself), so they answer whether
profiling is armed or not, and reading them costs nothing when it is not. `scope()`/`measure()`
sit across both: they are always callable and always run your code, and only *record* while armed.

**Every duration is in milliseconds.**

### `frame()`

| Key | Type | Description |
|---|---|---|
| `frameno` | number | the client's frame counter |
| `t` | number | frame timestamp, seconds since client start |
| `fps` | number | frames per second, as the `:stats` HUD computes it |
| `ms` | number | this frame's total UI-thread time |
| `msAvg` / `msMin` / `msMax` / `msP95` | number | frame time over the whole history ring |
| `idle` | number | share of the last second spent waiting, `0.0`..`1.0` |
| `latency` | number | UI-thread → GPU-fence lag |
| `gpuMs` | number | GPU time — see below |
| `gpuFrameno` | number | which frame `gpuMs` belongs to |
| `phases` | table | the UI thread's own phase breakdown |
| `render` | table | the render thread's phase breakdown |
| `ui` | number | widget-tree cost this frame (`utick` + `draw`) |
| `addons` | number | Lua time charged to addons this frame |

`phases` carries the client's own phase names — `dwait`, `stick`, `utick`, `draw`, `aux`, `wait` — so
the numbers line up with `Profwnd` field by field. `render` is the render thread's group (`tick`,
`draw`, `swap`, `finish`) and **lags by about one frame**: that profile closes a frame on the next
frame's fence, and the API reports what the client measured rather than re-timing it.

`addons` is the same accounting the addon CPU watchdog uses, read rather than re-measured.

> **GPU time arrives late.** GL timestamps come back through fences several frames after the frame they
> belong to, so the frame that just finished essentially never has one yet. `frame()` reports the newest
> frame whose GPU time *has* landed and tells you which one that is in `gpuFrameno` — expect it to trail
> `frameno` by a handful of frames. Until the first one lands, **both keys are absent**.

**An absent key means "not measured", never zero.** That is why there is no `scene` key: the 3D scene
has no timing boundary of its own yet (it gets one with the named render passes), and a `0` would read
as "free". Check with `if f.gpuMs then …` rather than comparing against 0.

### `history(n)`

The ring holds about **600 frames** (~10 s at 60 fps); a larger `n` is clamped to what is held, and is
not an error. Each entry carries `frameno`, `t`, `ms`, `addons`, `phases`, and `gpuMs` **only if** that
frame's GPU time resolved — so a frame graph skips the unresolved ones instead of drawing them as a dip
to zero.

```lua
local h = hafen.client:profiling():history(120)     -- the last ~2 seconds, oldest first
for i, f in ipairs(h) do
  drawBar(i, f.ms, f.gpuMs)                         -- f.gpuMs may be nil
end
```

### The counters

`memory()`, `net()`, `loader()`, `render()` and `textcache()` are **pull-only**: they read counters the
client keeps anyway, so they answer with profiling off and cost nothing while you are not asking. The first
four are the numbers the `:stats on` HUD formats, and always agree with it field by field. Nothing here is
sampled over time — each call is the value right now (`textcache()`'s hit/miss/eviction totals being the one
running tally, cumulative since the addon loaded).

#### `memory()`

**Sizes are in bytes** (this is the one place the surface is not in milliseconds).

| Key | Description |
|---|---|
| `heapUsed` / `heapFree` / `heapTotal` / `heapMax` | the JVM heap, as `Runtime` reports it |
| `allocPerFrame` | the client's own smoothed per-frame allocation estimate |
| `gcCount` / `gcMs` | collections and time spent collecting, **cumulative since client start** |

`gcCount`/`gcMs` only mean something as a **delta between two reads** — take one, wait, take another.
`allocPerFrame` is the estimate behind the HUD's `Mem:` line, and the client only advances it while that
HUD is drawn, so the key is **absent** until it has been computed at least once. Measuring it every frame
instead would put a heap read on the frame loop whether profiling is armed or not.

#### `net()`

Empty while there is no connection (the login screen). **`rtt`/`rttVar` are in milliseconds.**

| Key | Description |
|---|---|
| `packetsTx` / `packetsRx` / `bytesTx` / `bytesRx` | the traffic counters, cumulative for the session |
| `resentTx` / `resentRx` | packets re-sent / received twice (the HUD's `R`) |
| `reorderedRx` | packets that arrived out of order (the HUD's `O`) |
| `rtt` / `rttVar` | smoothed round-trip time and its deviation |

These are written on the connection worker, so a read may be one packet behind. That is by design —
exactness here would mean locking a path nothing needs to be exact on.

#### `loader()`

| Key | Description |
|---|---|
| `queued` / `loading` / `busy` / `poolSize` | the UI resource loader (the HUD's `Async:` line) |
| `defer` | the shared background pool: `{queued=, busy=, poolSize=}` |
| `resQueue` / `resLoaded` | resource fetch queue depth and resources resolved so far |

The four loader numbers are taken under one lock, so they are mutually consistent — a queue that just
emptied never shows up as "queued 0, busy 0" with the work still in flight.

#### `render()`

Describes the **3D scene**, so everything but `stateSlots` is absent before the world is up.

| Key | Description |
|---|---|
| `drawSlots` | draw slots this frame — as close to "draw calls" as the render tree gets |
| `uniqueInstances` / `batches` / `instances` | the batching split: un-instanced slots, instanced batches, instances in them |
| `invalid` / `bypass` | slots pending revalidation / that cannot be instanced at all |
| `treeLeaves` / `treeNodes` | scene-tree size |
| `programs` | shader programs the GL environment holds |
| `vram` | per-pool VRAM, keyed `indices`/`vertices`/`textures`/`vaos`/`fbos`, each `{objects=, bytes=}` |
| `stateSlots` | render-state slots in use (process-wide, not per scene) |

`programs` and `vram` need a GL environment and are absent on any other backend. The counters are
written on the render side and may be one frame stale.

```lua
local r = hafen.client:profiling():render()
if r.drawSlots then
  hafen.log(string.format("%d slots, %d batches, %.1f MB textures",
                          r.drawSlots, r.batches, r.vram.textures.bytes / 1048576))
end
```

#### `textcache()`

The rendered-text cache that [`g:text`/`g:atext`](ui.md#text-is-cached-across-frames) draw through. The cache
is **per addon**, so the top level is **your own**; `total` sums every Lua owner.

| Key | Description |
|---|---|
| `entries` / `bytes` | cached strings held right now, and the GL texture bytes they occupy |
| `hits` / `misses` / `evictions` | lookups served from the cache / rasterised / dropped to stay within the caps |
| `hitRate` | `hits / (hits + misses)`, `0.0`..`1.0` — **absent** until something has been looked up |
| `maxEntries` / `maxBytes` | the two caps the cache is bounded by (an entry count says nothing without its ceiling) |
| `total` | the same five figures summed over every Lua owner, plus `owners` = how many were summed |

`hits`/`misses`/`evictions` are **cumulative since the addon loaded** — a `:reload` builds a fresh cache and a
fresh count, and `reset()` deliberately does not touch them (it owns the frame ring, not a cache's own
bookkeeping). `entries`/`bytes` are the live state.

**How to read a miss.** A miss is not a fault: it is a string that had never been drawn in that font, and it
costs exactly what every text draw cost before the cache existed. A line whose text changes every frame misses
every frame and always will — that is the budgeting rule the [`hafen.ui`](ui.md#text-is-cached-across-frames)
page states. Likewise a permanently-full, permanently-evicting cache is not a problem: `evictions` climbing
while `hitRate` stays high means the volatile strings are aging out and the static ones are being reused.

`total` is also the **leak check**: disable every addon (or `:reload`) and `total.bytes` goes to ~0, because
teardown drops each cache and disposes its textures.

```lua
local c = hafen.client:profiling():textcache()
hafen.log(string.format("%d entries / %.2f MiB, %.1f%% hit rate (%d evictions)",
                        c.entries, c.bytes / 1048576, (c.hitRate or 0) * 100, c.evictions))
```

### `addons()`

One row per Lua owner — every loaded addon, plus `(console)` for the `:lua` REPL — for the **last
completed frame**, sorted most expensive first. The top row is the answer to "who is costing me frames".

| Key | Description |
|---|---|
| `id` | the addon's manifest id |
| `ms` | its Lua time in the last completed frame |
| `msAvg` / `msPeak` | mean and worst frame since the switch was armed (or since `reset()`) |
| `share` | `ms` as a fraction of that frame — absent until a frame has been sampled |
| `calls` | how many calls, by category: `events`, `timers`, `draw`, `hooks`, `widgets` |
| `cost` | the same split in milliseconds |
| `scopes` | this addon's named scopes, keyed by name — see below |

The array is followed by a **`total`** key (`{ms=, share=}`) that is the *same number* `frame().addons`
reports: both read the accounting the addon CPU watchdog already keeps, so the two views can never
disagree. Iterate the rows with `ipairs` — `total` is not part of the array.

```lua
local rows = hafen.client:profiling():addons()
for _, r in ipairs(rows) do
  hafen.log(string.format("%-12s %.2f ms (%.0f%%)  draw=%d events=%d",
                          r.id, r.ms, (r.share or 0) * 100, r.calls.draw, r.calls.events))
end
```

**Categories describe what your Lua was doing**, not where it lives: `draw` is overlay and widget paint
callbacks, `widgets` the rest of a custom widget's life (tick, mouse, drop), `hooks` input/action/message
hooks, hotkeys and slash commands, `events` the event bus and async callbacks, `timers` timer callbacks.

> A callback that calls back into the engine, which calls your Lua again, is charged to **both** brackets
> — the same way the watchdog has always charged it. So `cost` can add up to slightly more than `ms` on a
> re-entrant frame. `ms` is the number to trust.

### Custom scopes

`scope(name)` and `measure(name, fn, ...)` are the `ProfilerMarker` equivalent: name a section of *your*
code and see what it costs, in your own row of `addons()`.

```lua
local p = hafen.client:profiling()

p:measure("scan-gobs", function()                  -- the wrapper form: cannot forget to finish
  for _, g in ipairs(hafen.world.gobs()) do … end
end)

local s = p:scope("rebuild")                       -- the explicit form, for a section you cannot wrap
s:begin()
rebuildIndex()
s:finish()
```

| Verb | Description |
|---|---|
| `s:begin()` / `s:finish()` | bracket a section (both chain) |
| `s:name()` | the scope's name |
| `p:measure(name, fn, ...)` | run `fn(...)` inside the scope and return whatever it returns |

Names are **per addon**: two addons may both use `"update"` without colliding, and a scope map dies with
its addon on `:reload`/disable — nothing to clean up. Each scope appears in that addon's `addons()` row
as `{ms=, msAvg=, msPeak=, calls=}`.

`ms` and `calls` are **this-frame** figures, so a scope that ran a moment ago reads 0 — its cost lives in
`msPeak`/`msAvg`. Read `ms` per frame (from an `OnUpdate`, say); read `msPeak` for "how bad does this get".

**Leave the instrumentation in.** With profiling off, `begin`/`finish` return on a single field check and
`measure` calls `fn` directly — nothing is allocated and nothing is recorded, so a shipped addon pays
effectively nothing for scopes it is not being profiled on. `measure` runs `fn` either way, and closes
the scope even if `fn` errors. Only the outermost `begin`/`finish` pair of a recursive section counts, an
unmatched `finish()` is ignored, and a scope left open by an erroring handler closes at end of frame.

### `widgets()`

`frame()` says the widget tree cost `utick` + `draw` milliseconds. `widgets()` says **who**.

```lua
local w = hafen.client:profiling():widgets()
for _, r in ipairs(w.byType) do
  hafen.log(string.format("%-20s x%d  self %.2f ms", r.type, r.count, r.selfMs))
end
```

| Key | Description |
|---|---|
| `byType` | one row per widget class, **sorted by self time** — the answer to "what is my UI spending on" |
| `top` | the 20 heaviest individual widgets, same sort |
| `total` | the whole tree: `tickMs`, `drawMs`, `ms`, and `count` (widgets with a live measurement) |

A `byType` row:

| Key | Description |
|---|---|
| `type` | the widget class name (an anonymous subclass reports the class it extends) |
| `count` | how many of them were measured |
| `tickMs` / `drawMs` | time **inclusive** of children |
| `tickSelfMs` / `drawSelfMs` / `selfMs` | the same **exclusive** of children; `selfMs` is the sum of the two |

A `top` row carries `type`, `selfMs`, `tickMs`, `drawMs`, plus `id` when the widget is bound to a server
id and `owner` when an **addon** put it in the tree (its manifest id).

**Inclusive vs self.** A widget is timed by its *parent*, around the call that ticks or draws its entire
subtree — that is `tickMs`/`drawMs`. Self time is that minus the sum of its children's inclusive time. So
a container holding one expensive child shows a large `tickMs` and a near-zero `tickSelfMs`, and only the
child is blamed. Every row's `selfMs` sums to `total.ms`, which is the root widget's inclusive tick+draw:
the breakdown **reconciles**, it is not indicative.

**Which frame.** The last one in which each widget was ticked or drawn — the frame in progress, or the one
just finished. A widget not touched since (a window you closed, a hidden tab) simply **drops out** of the
tables rather than reporting a cost it no longer has, which is also why `total.count` shrinks when you
close a window.

**`owner` is the link to `addons()`.** An addon's own widgets are itemised here *and* rolled into that
addon's `addons()` row — two views of one measurement, not two measurements.

> **What is not in it.** `tickMs` is the tick traversal only. The `utick` phase in `frame()` also covers
> `gtick`, the hover query and any resize, so the tree's tick total sits a little under that phase. And the
> `draw` phase covers the whole 3D scene, of which the `MapView` row is only the widget-side share — the
> scene's own breakdown is what the named render passes are for.

### `passes()`

`frame()` says the frame cost so many milliseconds on the GPU. `passes()` says **where they went**, over a
fixed list of named sections with **CPU and GPU time side by side**.

```lua
for _, r in ipairs(hafen.client:profiling():passes()) do
  hafen.log(string.format("%-8s cpu %.2f ms  gpu %.2f ms", r.name, r.cpuMs, r.gpuMs))
end
```

| Pass | What it covers |
|---|---|
| `shadow` | the entire shadow-map render |
| `scene` | the 3D draw list — the world itself |
| `ui2d` | the widget tree |

Each row is `{name=, cpuMs=, gpuMs=}`, always in that order. The table also carries `frameno`, and `ms` /
`gpuMs` for the whole frame so you can measure the rows against it.

**The rows are disjoint.** `shadow` and `scene` run *inside* the widget draw — the `MapView` is a widget — so
each pass reports **self** time: its own span minus the passes nested in it, the same inclusive/self split
`widgets()` uses. That is why `ui2d` means the *2D* UI and why the three sum to less than the frame instead
of counting the scene twice.

**CPU and GPU measure different things here.** The CPU column is the time spent *recording* the GL commands,
so `shadow` and `scene` are typically a few hundredths of a millisecond while `ui2d` is milliseconds of real
widget work. The GPU column is when the driver actually did it.

**Which frame.** The newest one whose GL timestamps have come **back** — same rule as `gpuMs` in `frame()`,
and for the same reason. Both columns describe that one frame, so a row is internally consistent. Empty
until the first frame resolves.

**What shadows cost you.** Turn Video ▸ Shadows off: the `shadow` row falls to zero *and* `scene` drops too,
because the world's shaders stop sampling the shadow map. Both savings are real and the split tells you
which is which — that decomposition is the whole point of naming passes.

> **The list is fixed, and stays fixed.** Every boundary is a real GL timestamp query, which is not free and
> can stall the pipeline if overused. Per-draw-call or per-material GPU attribution is not something this
> API will grow; that is RenderDoc/Nsight territory.

### `gl()`

What the client actually handed the driver last frame.

| Key | Description |
|---|---|
| `drawCalls` | draw calls submitted |
| `programBinds` | shader-program switches |
| `vertices` / `triangles` | geometry submitted (point and line geometry counts vertices, not triangles) |
| `frameno` | the frame these describe |

These four are the only counters in this surface that are **armed only**. Everything in `render()` below is
a number the client already keeps and merely formats into its HUD, so it answers whether profiling is on or
not; nothing counts *these*, so they are new counting and they sit behind the switch.

`programBinds` against `drawCalls` is the batching story: the draw list is sorted by program, so binds far
below calls means the sort is doing its job.

### `overhead()`

What profiling itself costs — per **tier**, so a tier that gets too expensive can be identified and disabled
rather than dragging the whole feature down. The promise is that armed overhead stays under **5%** of frame
time (target 2%); this is where you check it.

```lua
local o = hafen.client:profiling():overhead()
hafen.log(string.format("profiling costs %.4f ms/frame = %.2f%% (%s)",
                        o.totalMs, o.shareOfFrame * 100, o.method))
for _, r in ipairs(o.tiers) do
  hafen.log(string.format("  %-8s %.4f ms", r.name, r.ms))
end
```

Every figure is a **mean per frame** since the switch was armed (or `reset()` called) — a single frame's
figure would be noise at this scale.

| Key | Description |
|---|---|
| `totalMs` / `shareOfFrame` | the budget number: ms per frame, and as a fraction of the frame |
| `budget` / `withinBudget` | the 5% ceiling, and whether this run is inside it |
| `method` | `"control"` if `totalMs` was measured, `"model"` if calibrated — see below |
| `aggregatorMs` | the end-of-frame fold, **timed directly** — exact |
| `gpuQueryMs` | the GL timestamp queries the named passes insert, timed directly |
| `probeMs` | the **modelled** probe cost: hits × a per-hit cost calibrated when the switch armed |
| `measuredMs` / `measuredErrorMs` | the **measured** probe cost and its error bar (absent until enough samples) |
| `measuredSpreadMs` | the comparison's noise floor |
| `frameMs` | mean frame time, what the share is taken against |
| `armedFrames` / `controlFrames` / `periods` / `periodsNeeded` | how much evidence there is so far |
| `tiers` | one row per tier — both keyed by name and ordered as a list |

Each tier row is `{name=, ms=, share=, modelledMs=, method=}`, plus `hits` (probe hits per frame) on the
modelled ones:

| Tier | Probes |
|---|---|
| `frame` | the end-of-frame fold — the only tier that is timed rather than modelled |
| `addons` | the per-addon category split behind `addons()` |
| `widgets` | the per-widget tick/draw brackets behind `widgets()` |
| `passes` | the named-pass seams and their GL timestamp queries |
| `gl` | the submission counters behind `gl()` |

**Two numbers, cross-checking each other.** The *modelled* one counts probe hits and multiplies by a per-hit
cost measured once when the switch armed; it is available immediately and errs **high**, because the
calibration loops run cold while the real probes run JIT-compiled. The *measured* one comes from **control
frames**: one frame in 64 runs with every probe disarmed, and each period contributes one delta — the median
**work** time of its armed frames minus its control frame. (Work, not frame time: under vsync or a frame cap
the total is pinned to the cap and would never move.) `method` tells you which one `totalMs` used.

> **`measuredMs` ≤ 0 is the normal outcome, and does not mean profiling made the client faster.** It means
> the cost is under the comparison's own noise floor — frame work time is spiky and the cost being hunted is
> a fraction of a percent of it. The measurement only takes over when it clears `measuredErrorMs`; otherwise
> the model has the say, because a model that at least counted the probes beats a random number. For a
> feature whose whole claim is that it costs almost nothing, an unresolvable measurement is success.

Once the measurement *is* authoritative, the tiers are scaled to it in the modelled proportion, so the rows
always sum to `totalMs`. `modelledMs` is reported alongside, so nothing hides behind the scaling.

### When profiling is off

`frame()`, `history()`, `addons()`, `widgets()`, `passes()`, `gl()` and `overhead()` return an **empty table**, never `nil` — no branch needed in addon
code:

```lua
for _, f in ipairs(p:history(60)) do … end          -- simply does nothing while off
```

The first valid sample arrives on the **second** frame after arming (arming is next-frame, as above), so
a freshly armed profiler answers empty for one frame. `reset()` empties the ring and every per-addon,
per-scope and per-widget figure the same way, as does arming the switch. The five counters are unaffected — they answer
the same numbers armed or not, and `reset()` deliberately leaves `textcache()`'s tallies alone (it owns the frame
ring, not a cache's own bookkeeping). `scope()`/`measure()` still run your code (see above).

**An absent key means "not measured", never zero** — everywhere in this surface. No connection, no
`net()` keys; no world yet, no scene keys in `render()`; a `0` would read as "measured, and it is zero".
Check with `if r.drawSlots then …`, not `> 0`.

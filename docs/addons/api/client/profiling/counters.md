# hafen.client: the counters

`memory()`, `net()`, `loader()`, `render()`, `surfaces()`, `entities()`, `session()` and `textcache()` are
**pull-only**: they read counters the client keeps anyway, so they answer with [profiling](README.md) off and
cost nothing while you are not asking. The first four are the numbers the client's own stats HUD formats, and
always agree with it field by field.

Nothing here is sampled over time — each call is the value right now. The exceptions are the running
tallies, and each says so: `surfaces()`'s uploads and frames, `entities()`'s passes and `session()`'s
ground and click counters are cumulative since the client started, and `textcache()`'s hit, miss
and eviction totals since the addon loaded. `session()`'s `live`, `states` and `addonsLive` are not
among them: they count what exists right now, like `surfaces()`'s `live`.

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

**These are the character on screen's**, as `loader()` and `render()` below are. A connection is per login,
so a handler running for a background character reads the drawn character's traffic rather than its own;
hand that character the screen with [`hafen.session():current(s)`](../../session.md) to read it.

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

Describes the **3D scene**, so everything but `stateSlots`, `gobsHeld` and the two overlay counters is
absent before the world is up.

| Key | Description |
|---|---|
| `drawSlots` | draw slots this frame — as close to "draw calls" as the render tree gets |
| `uniqueInstances` / `batches` / `instances` | the batching split: un-instanced slots, instanced batches, instances in them |
| `invalid` / `bypass` | slots pending revalidation, and slots that cannot be instanced at all |
| `treeLeaves` / `treeNodes` | scene-tree size |
| `programs` | shader programs the GL environment holds |
| `vram` | per-pool VRAM, keyed `indices`/`vertices`/`textures`/`vaos`/`fbos`, each `{objects=, bytes=}` |
| `stateSlots` | render-state slots in use, process-wide rather than per scene |
| `gobsHeld` | game objects kept out of the scene until their `GobAdded` fired, **cumulative since client start** |
| `overlayMeshes` / `overlayOutlines` | ground-overlay pieces laid over the terrain, and the outlines over those, **cumulative since client start** |
| `recallGridsHeld` | grids of [remembered ground](../README.md#remembered-ground) the client is holding right now |
| `recallGridsRead` | grids of it read back off the record, **cumulative since the world came up** |
| `recallCutsDrawn` / `recallCutsWanted` | pieces of remembered ground in the scene right now, and how many the client wanted there |

`programs` and `vram` need a GL environment and are absent on any other backend. The counters are written
on the render side and may be one frame stale.

`gobsHeld` counts the other way round from the rest of this table: it is the addon layer's own tally, not the
scene's, so it answers on the login screen too. It climbs each time the client holds an object back so that
[`GobAdded`](../../event/bus/world.md#before-the-first-drawn-frame) runs before that object's first drawn
frame, and it stays at zero for as long as no addon subscribes to that event. Like `gcCount` it means
something as a **delta between two reads**: take one, walk into ground you have not seen this session, take
another.

`overlayMeshes` and `overlayOutlines` are counted the same way, outside the scene, and what they count is
terrain work. The client cuts the ground into squares, and every ground overlay — a
[patch](../../virtual/patches.md) you lay, a claim or a province the client draws — is laid a second time
over each cut its shape reaches: once as the sheet, and again as the outline round it where the overlay has
one. So laying a patch moves `overlayMeshes` by the cuts its own [pieces](../../virtual/pieces.md) reach and no others, and leaves
`overlayOutlines` where it was, because a patch's own
[edge](../../virtual/patches.md#the-border) is carved into the sheet the cut already draws rather than laid
as a second mesh over it — so wearing one, widening it or taking it off moves neither counter either.
Taking a patch up moves neither: nothing is built to stop drawing something. Read as a **delta between two reads**, that is what makes the
cost of a patch a number rather than a feeling — lay one with fifty already on the ground and it moves by
what one costs.

The four `recall` keys are the [remembered ground](../README.md#remembered-ground)'s, and they are the whole
of what that reach costs. Three of them are **gauges** — what is held, drawn and wanted at this instant, so
each falls back as you pan away and rises as you pan in. `recallGridsRead` is the odd one and it is
**cumulative**: it climbs while the record is being read back and stops the moment it has caught up with the
camera, so like `gcCount` it means something as a **delta between two reads**. Cuts drawn reaching cuts
wanted is the feature's own claim as a number: while the two differ there is ground the client means to draw
and has not finished building.

Reading them costs nothing and needs nothing armed, but they describe the **scene**, so all four are absent
until the world is up. Once it is, a `0` is a count and not an absent key: with the setting off, or with
every other camera, nothing is drawn and nothing is wanted, and both of those gauges fall to zero and stay
there. Grids held is the one that does not, and on purpose — ground already read back is **kept** for the
camera that comes back to it, so that gauge falls only when the client's own budget drops a grid.

```lua
local r = hafen.client():profiling():render()
hafen.log():write(string.format("%d overlay pieces laid, %d of them outlines",
                        r.overlayMeshes, r.overlayOutlines))
if r.drawSlots then
  hafen.log():write(string.format("%d slots, %d batches, %.1f MB textures",
                          r.drawSlots, r.batches, r.vram.textures.bytes / 1048576))
end
```

## `surfaces()`

The [widgets standing in the 3D world](../../virtual/widgets.md), and what drawing them costs. It counts every
addon's, not only your own — a panel is a texture and a widget subtree wherever it came from.

| Key | Description |
|---|---|
| `live` | how many surfaces exist right now, across every addon |
| `culled` | how many of those are being skipped this instant, because nothing is looking at them |
| `uploads` | offscreen passes actually issued, **cumulative since client start** |
| `frames` | frames those passes were offered, **cumulative since client start** |

`live` and `culled` are instantaneous counts, never totals. A surface is culled when the camera is pointing
elsewhere, when the entity is hidden, or when the game object it stands on has left the scene — and being
culled is not being gone: the collection still holds it, its `Update` still fires, and it draws again the
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

## `entities()`

The client-only things you have [standing at a point in the world](../../virtual/README.md) — a ghost, a
sprite, a model or a panel — and what keeping them there costs. One standing on a game object is not counted:
its place is that object's, so there is nothing to work out for it.

| Key | Description |
|---|---|
| `placed` | how many are standing at a point right now, across every addon |
| `waiting` | how many of those hold a place this session cannot locate |
| `passes` | times the client has worked out where they all are, **cumulative since client start** |

`placed` and `waiting` are instantaneous counts, never totals. A thing is **waiting** when the place it
holds is real but has no coordinate here — ground recorded in another part of the world; while you are
underground, every place above at once; and, for the moment after the server re-bases you, every place off
the ground you are streaming, until this session's [base](../../position.md) is proved again. It is not lost
and it is not an error: it exists, it answers every verb, it reports the place it was given, and it stands
itself up the moment that ground resolves.

`passes` is the running tally, and it is what makes the cost claim checkable: where those things are gets
worked out when the world moves under them and at no other time, so `passes` climbs by a handful while you
walk and holds still while you stand. Two reads a few seconds apart, with nothing happening in between,
return the same number.

```lua
local e = hafen.client():profiling():entities()
hafen.log():write(string.format("%d standing, %d waiting for their ground", e.placed, e.waiting))
```

## `session()`

How many accounts the client is holding logged in, and what it answered for them. The client can keep
several sessions open at once (`:session add`) and draws exactly one of them; the rest are live, ticking
and answering the server with no view of their own, and their ground is merged into the scene you are
looking at.

| Key | Description |
|---|---|
| `live` | how many sessions the client holds right now, **the one on screen included** |
| `states` | how many of those hold client-side state of their own, which is **every one of them** |
| `groundAnswered` | times another session supplied the ground height under the camera, **cumulative since client start** |
| `groundMissed` | times none of them had that ground, so the camera kept the height it last had, **cumulative since client start** |
| `placedRebuiltOffTick` | times a click was resolved before the client had said where the sessions stand, **cumulative since client start** |
| `addonsLive` | how many addons are running right now |
| `engineReloads` | times the client rebuilt the addon layer without being asked |

`live` is an **instantaneous count, never a total**: it goes up when a session joins and down when one
ends, and the character you are looking at is one of them — with one account in the world it reads `1`,
and on the login screen, with nobody logged in, `0`. There is no separate reading for the drawn session,
because there is nothing separate about it: the client holds sessions and draws one of them.

`states` is the same instantaneous count seen from the other side, and it is worth reading for one reason:
**it equals `live`**. The client keeps a set of caches for each session it holds — the widgets your
selectors have matched, the objects it has seen, the slots your addon is holding on that character's action
bar — and those are made when a session first needs them and dropped when that session ends. A number above
`live` means a session that has gone is still being remembered; a number below it means one has joined and
not yet needed anything, which is true for the moment between logging an account in and its world coming up.

The ground pair climbs only while a free camera is looking at ground the drawn session has never loaded —
panned over another character's surroundings, which is the one place the drawn session's own terrain cannot
answer. Anywhere else neither moves, so like `surfaces()` they mean something as a **delta between two
reads**: take one, pan the camera, take another.

`addonsLive` and `engineReloads` are the addon layer's own pair, and they are in this group because what
they say is about the layer's relationship to your characters. `addonsLive` is what the last load produced,
counted right now — and its whole claim is that **it does not move when you switch character**: your addon
is loaded once for the client and stays loaded, so tabbing between two accounts leaves this number where it
was. `engineReloads` counts the rebuilds nobody asked for, and the only value it can hold is **zero**: a
`:reload` you typed is a reload you asked for and does not count, and nothing else rebuilds the layer.
Anything above zero is an addon being torn down and reloaded behind your back, which is every Lua value it
held going missing with no event to tell it.

`placedRebuiltOffTick` is not a rate to watch, and the only value it is meant to hold is **zero**. Where each
session stands is worked out once a frame and read everywhere else, and a click on the ground is resolved
off the frame's own thread, a frame or so after the button went down — so this counts the clicks that got
there before the frame had said anything. Each one is a click on another session's merged ground answered in
the drawn session's coordinates rather than that session's, which puts the destination as far from the
cursor as the two characters are from each other. Any number above zero is the whole finding.

**A zero here is a count, not an absent key.** The client counts from the frame it starts, so `0` on the
ground pair means the query has not run — over your own ground it never does. With `live == 1`
`groundMissed` is the only one of the two that can climb: the session that would be asked is the one already
looking.

**Every counter group is read-only in the strict sense** — each takes no argument, and passing one raises
rather than being ignored, so `p:net(1)` is a refusal rather than a read that quietly discards what you
meant. The one that does take an argument is `p:history(n)`, which says how many frames back to read: a
whole number, and a negative one raises rather than answering an empty table. Asking for more frames
than the ring holds is how you ask for all of them, and answers with all of them.

```lua
local a = hafen.client():profiling():session()
hafen.timer():after(5, function()
  local b = hafen.client():profiling():session()
  hafen.log():write(string.format("%d sessions (%d holding state), %d addons",
                          b.live, b.states, b.addonsLive))
  hafen.log():write(string.format("%d answers, %d misses over 5 s; %d early, %d unasked reloads",
                          b.groundAnswered - a.groundAnswered, b.groundMissed - a.groundMissed,
                          b.placedRebuiltOffTick, b.engineReloads))
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
- [widgets in the world](../../virtual/widgets.md) — what `surfaces()` counts, and when one stops drawing
- [things in the world](../../virtual/README.md) — what `entities()` counts, and what a place that waits is

# Several sessions in one client

One process, several accounts logged in at once, one of them on screen. This is the client's own
structure, not an addon: the seams are in `haven` and the policy standing on them is `io.brodgar.rts`.

The session being drawn is the **anchor**; every other session is live, ticked and answering the
server, and simply not rendered. Which one is the anchor changes at any time.

## Driving it

| Command | Does |
|---|---|
| `:fleet users` | the accounts with a token saved by the login screen (`Bootstrap.gettoken`), which is all `add` can reach — there is no login dialog for a second account |
| `:fleet add USER [CHAR]` | connect and hold a session open. The character name is the **rest of the line**, spaces and all; without one it plays whichever the server offers first. A token login does not rotate the token, and a failed add deliberately does not clear it |
| `:fleet list` | each session and how far it has got: connecting, character list, loading, or its character and how many grids it has streamed |
| `:fleet drop USER\|all` | close a session. `Session.close` is what ends it, so `RemoteUI.run` unwinds through its own cleanup instead of being torn out from under itself |
| `:fleet anchor USER\|main` | hand the screen over. Everything else in the layer reads `Fleet.anchor()`, so the offsets, the orders and the merged patches follow by themselves |
| `:fleet wnd` | rebuild the switcher window after its close button. It hides rather than sending `close`, which the server would not understand |
| `:fleet rts [on\|off]` | the mode below; bare means on |

## The RTS mode

A mode and not a rebinding: a left click on the ground walks you there, and a marquee needs that
button. Off, the map view behaves as it does without any of this. On, the anchor's view takes
`MapView.FleetCam` (also `:cam fleet`), a `FreeCam` with a centre of its own so that panning survives
the character moving.

| Input | Does |
|---|---|
| Left drag, left click | select by box, or the nearest character within 24px. Both project each character with `MapView.screenxf` and test in screen space: only our own characters are ever selected, so no rectangular pick pass exists |
| Right click | order the selection. The destination is resolved by the client's own pick pass (`MapView.FleetClick extends Hittest`), so it lands where a real click would |
| Middle drag | pan. The pixel delta is solved back into world units through the view's own projection — three `screenxf` probes and a 2×2 inverse — rather than by rebuilding the camera's trigonometry |
| Shift and middle drag | `FreeCam`'s own rotate and elevate |
| `rts-next-anchor` (Tab), `rts-focus` (space) | next session; centre on the selection, or on everyone when nothing is selected |
| `cam-reset` (Home), `cam-left`, `cam-right`, `cam-in`, `cam-out` | follow the anchor's character again; rotate and zoom, since `FreeCam` has no keyboard of its own |

## One loop, several sessions

| What | Where |
|---|---|
| **The two UIs, and why they are two** | `UILoop.ui` is the main runner's, which `Client.Main` replaces and **destroys** as its runner chain advances; `UILoop.drawn` is the one actually drawn, dispatched to and `gtick`ed. One field could not be both — destroying the runner's UI must never be able to destroy a session it does not own. `newui` clears `drawui` when it destroys the UI that was on screen |
| Building a session's UI | `UILoop.bgui` — `newui` minus the replace and the destroy, and like it built **outside `uilock`**, because `UI`'s constructor runs `Runner.init` and no other lock may be taken underneath that one. The profiling fields are left off: `uprof`/`rprof`/`gprof` describe the frame, and only the anchor has one |
| The anchor's frame | `UILoop.Frame.tick`, all inside `synchronized(ui)`: dispatch, `glob.ctick`, `glob.gtick`, `ui.tick`, `mousehover`, resize |
| Everything not drawn | `Fleet.tick` and `Fleet.tickbg`, called **after** that block closes, each session under its own monitor. `tickbg` exists for the main session when a member holds the screen: `Fleet` owns its members and the main session is not among them, so nothing else would tick it |
| Handing the screen over | `Fleet.anchor` flips `UILoop.drawn` and the dormancy of the two views. `Fleet.reclaim` does it unasked when the session holding the screen dies, because that runner unwinds on its own thread, where touching render trees would be wrong |

**One lock direction, and it is load-bearing.** The tick never holds two UI monitors at once. The
console does nest them — it runs inside the drawn UI's monitor and reaches into a member's — so that
direction, anchor then member, is the only one anything may take. `Fleet.say` queues its text and
drains it on the tick rather than delivering it where it is thought, because delivering means taking
the **anchor's** monitor from whatever thread happened to speak.

## What dormancy buys, and what it costs

A session that is not drawn is still whole: a `Session`, a `UI`, a widget tree and a `Glob`.

| Consequence | Where |
|---|---|
| **Its terrain is never meshed** | `MapRaster.Grid.tick` returns while its node holds no slot, so a view outside a render tree builds nothing. That one fact is what makes holding several sessions affordable, and it cuts both ways: promoting one re-meshes everything in view, which is the hitch on an anchor switch. `MapView.dormant` is the attach and the detach |
| It still asks the server for ground | `MapView.tick`'s dormant branch. `MCache.sendreqs` and `reqarea` live in `draw`, so a view that is never drawn would never request a grid — and both the offset below and any order needing a destination rest on the member having loaded the ground it stands on. Throttled: `reqarea` calls `getcut` once per cut of its rectangle, and `sendreqs` already rate-limits each grid to one request a second |
| It does not take the addon engine | `RemoteUI.init` guards the `AddonManager.init` call. That hub is single-session — its `ui` and `view` are the client's one live pair — so a background session binding itself there points every addon at a session nobody is looking at |
| It is silent | `ActAudio.RootChannel.mute` scales the channel's `VolAdjust` and leaves `volume` and its pref alone: going quiet because nobody is looking must not read as the user turning the sound down, nor survive into the next launch |
| It does not fight over window geometry | `GameUI.onscreen` gates all three writes. Several `GameUI`s persist to the same keys, and the damage is not that they want different layouts — it is that an untouched session writes back the positions it loaded |

## Aligning two coordinate frames

`Gob.rc` is relative to where its session logged in, so one patch of ground has a different number in
each session. A grid carries both coordinates it has, and only one of them is shared.

| What | Where |
|---|---|
| The two coordinates | `MCache.Grid.gc` is this session's and means nothing in another; `MCache.Grid.id` is the server's and is the same number in every client that has ever loaded that grid |
| Reading them out | `MCache.gridids` snapshots `id` to `gc` under the grids lock, skipping grids whose `id` has not arrived — `Grid.fill` writes it when the `"m"` layer lands, so a grid exists briefly with none |

Intersecting two sessions' id sets and differencing the `gc`s gives the offset between their frames.
It is a whole number of grids and constant for a pair of sessions, and **every shared grid must
agree**: two frames are rigid translations of one another or they are not frames, so a disagreement
is worth reporting rather than averaging away. No shared grid at all means the two are not near each
other, which is a state and not a fault. What proves an offset right: gob ids are global, so a
character translated into the anchor's frame and the anchor's own view of that same gob coincide.

## Ordering a session that is not drawn

An order is not a mouse event. It is the widget message a click would have produced, and it needs no
camera, no click-map and no scene — which is why a member obeys while invisible.

| What | Where |
|---|---|
| The message | `MapView.Click.hit` sends `wdgmsg("click", pc, mc.floor(posres), btn, modflags)`. `pc` is a screen coord the protocol carries and **not** what picks the destination, so any plausible value serves |
| Getting it there | `UI.rawWdgmsg` resolves the widget id from **that** UI's own `rwidgets` and hands it to that UI's receiver. Raw rather than `wdgmsg` for a member: the action-hook chain belongs to the anchor and knows nothing about the session it would be walking for |
| A gob target | `Gob.GobClick.clickargs` is `{0, id, gob.rc.floor(posres), 0, -1}`, and that coordinate is the **observer's**. Relaying the arguments verbatim hands every session a place from somebody else's login, so a target travels as an id and each recipient rebuilds them from its own `OCache`. One that cannot see the target walks to the spot, which is what a click on unidentifiable ground does anyway |

## Drawing another session into this one

Characters standing together need none of this: the anchor's `OCache` already carries them all. Apart,
one session's ground and objects are drawn into the anchor's scene under a single translation.

| What | Where |
|---|---|
| The sources | `MapRaster` and `Gobs` take their `MCache` and `Glob` as constructor arguments; `MapView.FleetTerrain`, `FleetGobs` and `FleetClickMap` are those same classes pointed at another session |
| One object, one copy | `MapRaster.skipcut` and `Gobs.skipgob`. The anchor claims its ground first and each patch claims what is left; a gob belongs to the **first** view, in a stable order, whose session can see it. Positional rather than claimed, because `skipgob` runs on Loader threads as well as on the tick and so can agree with nobody about who ran first |
| Clickability | the pick pass tests `MapView.clmaptree` alone, so merged ground absent from it is scenery. `MapView.checkmapclick` then derives its coordinate from `cut.ul`, in the frame of whichever session's map produced that cut — the translation above it never enters that arithmetic. `MapMesh.map` says whose, and the answer is corrected once, at the source |
| Cost | `ShadowMap.maskshadow` keeps a patch out of `ShadowMap.ShadowList`, which is a second full render of every triangle it holds; the shadow map is a 750-unit box around the anchor's character, so a patch far enough away to need merging contributes nothing to it. Each patch is also frustum-tested per cut and per gob, because [nothing in the render path culls](world-3d.md) |

## Gotchas

- **A slot introducing a `Location` above locked state must declare `lockstate`.** The tree locks a
  great many slots — every map cut's click geometry, every composited gob — and a locked slot may not
  depend on an ancestor whose state could still change. `RenderTree.TreeSlot.checklockdeps` throws
  *"locked state depends on non-locked state"* on the UI thread, at the first frame that draws it.
  Locking is honest here because a patch's offset never changes: a `FleetView` is rebuilt whole if it
  ever does.
- **`TickList` does not call `Gob.gtick`.** It dispatches `TickList.Ticking.autogtick`, which
  `Gob.Placed` does not implement, so a gob's animated pose reaches the GPU through `OCache.gtick`
  alone. A gob drawn from a session whose `Glob.gtick` never runs shows whatever its buffers happen
  to hold, and being in a render tree is not enough to fix that.
- **Foliage sway measures from the gob's own origin.** `lib/svaj` displaces each vertex by an amplitude
  proportional to its distance from `Svaj.origin`, which `GobSvaj.placestate` takes from `Gob.getc` in
  the gob's own session's frame. Draw those vertices translated and the subtraction misses by the
  translation, and **the miss is the amplitude** — trees thrash instead of swaying: only trees, only
  horizontally while the translation has no z, and only for a session that logged in on a different
  grid. The copy under `haven.res.lib.svaj` corrects it, and the engine prefers a local copy only
  while its `FromResource` version matches the resource served, so a server-side bump restores the
  bug with a warning naming the version to move to.

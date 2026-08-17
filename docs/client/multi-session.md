# Several sessions in one client

One process, several accounts logged in at once, one of them on screen. This is the client's own
structure, not an addon: the seams are in `haven` and the policy standing on them is `io.brodgar.session`.

The session being drawn is the **anchor**; every other session is live, ticked and answering the
server, and simply not rendered. Which one is the anchor changes at any time.

## Driving it

| Command | Does |
|---|---|
| `:session users` | the accounts with a token saved by the login screen (`Bootstrap.gettoken`), which is all `add` can reach — there is no login dialog for a second account |
| `:session add USER [CHAR]` | connect and hold a session open. The character name is the **rest of the line**, spaces and all; without one it plays whichever the server offers first. A token login does not rotate the token, and a failed add deliberately does not clear it |
| `:session list` | each session and how far it has got: connecting, character list, loading, or its character and how many grids it has streamed |
| `:session drop USER\|all` | close a session. `Session.close` is what ends it, so `RemoteUI.run` unwinds through its own cleanup instead of being torn out from under itself |
| `:session anchor USER\|main` | go to a character. Everything else in the layer reads `Sessions.anchor()`, so the offsets, the orders and the merged patches follow by themselves; `Control.take` adds the one thing the anchor knows nothing about — the selection (below) |
| `:session wnd` | rebuild the switcher window after its close button. It hides rather than sending `close`, which the server would not understand |

## The RTS mode

A mode and not a rebinding: a left click on the ground walks you there, and a marquee needs that
button. Off, the map view behaves as it does without any of this. On, it still does — what the mode
adds is one gesture (Alt and the left button picks who is commanded, and picking exactly one is
picking whose screen it is) and one recipient (with something selected, a left click walks the
selection instead of the character on screen).

**The mode has no switch of its own.** It is derived from the membership, because it is only ever
wanted when there is something to command: `Sessions.tickmode` measures `members` against its own
`modeon` and calls `Control.mode` on the edge alone, so the mode arrives with the first extra session
and leaves with the last. Two things about where it sits.

| What | Where |
|---|---|
| It runs **above** the early return | `Sessions.tick` returns on `members.isEmpty()`, and dropping the last member is exactly the frame on which the list is empty and the mode still has to go off |
| Any frame will take it | `Control.mode` touches nothing but its own state, so there is no edge to leave pending. A session that has not reached the world yet simply has nothing to select until it does |

**The mode installs no camera, ever.** A second session changes who can be commanded and nothing
about how the world is looked at, so no view is touched when it arrives and none is remembered to be
put back when it goes. `MapView.RTSCam` — a `FreeCam` with a centre of its own, so that panning
survives the character moving — is registered under the name `rts` beside every other camera
([world-3d.md](world-3d.md)) and is installed by hand with `:cam rts`, on any character, with or
without a second session. It is the mode's only dependant: `rts-focus` answers *not on the rts camera*
under any other, since no other has a centre to move.

**There is one camera, not one per character.** A camera is an inner class of the view it draws, so
each session's `MapView` owns its own object and they cannot be shared — what is shared is the state.
`Sessions.anchor` hands the outgoing view's camera to the incoming one (`MapView.adoptcam`) before the
screen changes, so the type, the angle, the elevation, the zoom and every option that camera type
invents follow the player from character to character. Two things about *when*: it runs **before**
`lp.drawn` and before `invalidate`, while `placed()` still measures every offset against the session
that has the screen — which is the frame the outgoing camera's pan is named in, so `Placed.offset` for
the incoming session is exactly what turns it into the new one's. Sessions too far apart to share a
grid have no offset, and a pan that cannot be translated becomes *follow the character* rather than a
coordinate from somebody else's login. What is copied and what is deliberately not is
[world-3d.md](world-3d.md).

| Input | Does |
|---|---|
| Alt and left click, on a character | **go to it** — its screen, and it alone selected: naming one character on the map is the switcher window's gesture said somewhere else, so it does the same thing (below). The nearest within 24px counts as clicked |
| Alt and left click, on nothing | clear the selection. Whose screen it is does not change: there is no character being named |
| Alt and left drag | select by box, and nothing more — a box says who is commanded, not whose screen this is. It and the click above project each character with `MapView.screenxf` and test in screen space: only our own characters are ever selected, so no rectangular pick pass exists |
| Shift or ctrl, alongside | extend the selection instead of replacing it, for both. Extending is not naming one character, so it never changes the screen either |
| Left click, on the ground, with somebody **else** selected | walk the selection there, and nothing else — the only order there is. The destination is resolved by the client's own pick pass (`MapView.ClickOrder extends Hittest`), so it lands where a real click would. The right button is never taken over at all |
| Left click, on a gob | the click it always was. `ClickOrder` hands it back through `MapView.clickhit`, the same code an ordinary `Click` runs — one press is still one pick pass and one message, only decided a frame later. It cannot be answered in `mousedown`, which is a frame before anything knows what was under the cursor. Interacting with a gob is the drawn character's own business and travels to no other login |
| Left click, with only the character on screen selected | also the click it always was — `Control.commands` reports nobody to command. Ordering your own character to walk somewhere *is* a left click, and this is not a corner case: `Control.take` makes the anchor's own character the whole selection on every switch, so without it the first thing a second session costs is the left button |
| Middle drag | pan. The pixel delta is solved back into world units through the view's own projection — three `screenxf` probes and a 2×2 inverse — rather than by rebuilding the camera's trigonometry |
| Ctrl and middle drag | `FreeCam`'s own rotate and elevate. `RTSCam.click` reads `ui.modflags()` once, at the press, so letting Ctrl go mid-drag does not change what the drag is already doing. Not a `KeyBinding`: `KeyMatch.Capture.handle` refuses a bare `VK_SHIFT`/`VK_CONTROL`/`VK_ALT`/`VK_META`/`VK_WINDOWS`, and that refusal is exactly what holds the key grab open across a modifier press so the chord after it can be captured — so a rebindable id could only ever carry a non-modifier key. No modifier collides: `MapView.mousedown` sends `ev.b == 2` straight to `camera.click` with no modifier branch and no fallthrough, so the middle button on the map view is the camera and nothing else |
| `rts-next-anchor`, `rts-focus` | go to the next session; centre on the selection, or on everyone when nothing is selected. **Both ship unbound** — see below. Both are dispatched by `Control.keydown`, which `MapView.keydown` runs **before** `camera.keydown` — so they are the mode's keys under whatever camera is installed, and answer nothing while the mode is off |
| `cam-reset` (Home), `cam-left`, `cam-right`, `cam-in`, `cam-out` | follow the anchor's character again; rotate and zoom, since `FreeCam` has no keyboard of its own. These are the camera's own, so they answer with the mode off too |

**Going to a character is one gesture, spelled four ways.** The switcher window's buttons, an
Alt-click on a character in the world, `rts-next-anchor` and `:session anchor` all call
`Control.take`, and none of them calls `Sessions.anchor` directly. It hands over the screen and makes that character the **whole**
selection — anything left selected would take the next order with it — and it stops there: the camera
is the player's, not the switch's. A switch that did not happen (a session with no screen yet) leaves
the selection alone as well, which is why `take` asks `Sessions.anchormember()` afterwards rather than
trusting its own argument.

**The mode's own two keys ship unbound**, `KeyBinding.get(id, KeyMatch.nil)`, and `OptWnd.BindingPanel`
lists them by hand under **Multi session** for the user to assign. No default can be conflict-free:
`KeyBinding.get` runs none of `set`'s exclusivity pass, so two *defaults* sharing a key leave both
firing, and neither is repairable afterwards ([services.md](services.md)).

## One loop, several sessions

| What | Where |
|---|---|
| **The two UIs, and why they are two** | `UILoop.ui` is the main runner's, which `Client.Main` replaces and **destroys** as its runner chain advances; `UILoop.drawn` is the one actually drawn, dispatched to and `gtick`ed. One field could not be both — destroying the runner's UI must never be able to destroy a session it does not own. `newui` clears `drawui` when it destroys the UI that was on screen |
| Building a session's UI | `UILoop.bgui` — `newui` minus the replace and the destroy, and like it built **outside `uilock`**, because `UI`'s constructor runs `Runner.init` and no other lock may be taken underneath that one. The profiling fields are left off: `uprof`/`rprof`/`gprof` describe the frame, and only the anchor has one |
| The anchor's frame | `UILoop.Frame.tick`, all inside `synchronized(ui)`: dispatch, `glob.ctick`, `glob.gtick`, `ui.tick`, `mousehover`, resize |
| Everything not drawn | `Sessions.tick`, called **after** that block closes, each session under its own monitor, and the one holding the screen skipped because the frame above has already ticked it in full. One call and one loop: every game session the client holds is a member of that list |
| Handing the screen over | `Sessions.anchor` flips `UILoop.drawn` and the dormancy of the two views. `Sessions.reclaim` does it unasked when the session holding the screen dies, because that runner unwinds on its own thread, where touching render trees would be wrong |

**One lock direction, and it is load-bearing.** The tick never holds two UI monitors at once. The
console does nest them — it runs inside the drawn UI's monitor and reaches into a member's — so that
direction, anchor then member, is the only one anything may take. `Sessions.say` queues its text and
drains it on the tick rather than delivering it where it is thought, because delivering means taking
the **anchor's** monitor from whatever thread happened to speak.

## What dormancy buys, and what it costs

A session that is not drawn is still whole: a `Session`, a `UI`, a widget tree and a `Glob`.

| Consequence | Where |
|---|---|
| **Its terrain is never meshed** | `MapRaster.Grid.tick` returns while its node holds no slot, so a view outside a render tree builds nothing. That one fact is what makes holding several sessions affordable, and it cuts both ways: promoting one re-meshes everything in view, which is the hitch on an anchor switch. `MapView.dormant` is the attach and the detach |
| It still asks the server for ground | `MapView.tick`'s dormant branch. `MCache.sendreqs` and `reqarea` live in `draw`, so a view that is never drawn would never request a grid — and both the offset below and any order needing a destination rest on the member having loaded the ground it stands on. Throttled: `reqarea` calls `getcut` once per cut of its rectangle, and `sendreqs` already rate-limits each grid to one request a second |
| It does not take the addon engine | `RemoteUI.init` guards the `AddonManager.init` call. That hub is single-session — its `ui` and `view` are the client's one live pair — so a background session binding itself there points every addon at a session nobody is looking at |
| **Its effects are consumed, not deferred** | An overlay is ticked only once it stands in a render tree, and a dormant view has none — so `Gob.ctick` ticks one whose `slots` are still empty when `Glob.dormant` says nobody is looking, and `Sprite.unheard` ends the sprites whose only other ending is being played through (`AudioSprite.ClipSprite`). Otherwise the sprite never ages, never leaves `ols` and keeps the audio stream it opened at construction: every effect a background session was ever told about piles up, and the whole backlog enters the tree in a single frame when that session takes the screen. `Glob.dormant` is settled once per session per frame because `Gob.ctick` asks it per gob |
| It is silent | `Sessions.applymute` → `ActAudio.RootChannel.mute` scales the channel's `VolAdjust` and leaves `volume` and its pref alone: going quiet because nobody is looking must not read as the user turning the sound down, nor survive into the next launch. Every live session, every frame, and not through `placed()` — the answer changes when a session **joins**, not only when the anchor moves, and a session still on the character list is already making noise while being absent from `placed()`. What this silences is the whole UI channel, which is most of the game's audio: the server's own sounds (`RootWidget`), the minimap's alerts (`GobIcon`), the chat ping (`ChatUI`). The server tells every session about the same event and each `UI` rate-limits only its own (`UI.lastmsgsfx`), so sessions standing together and left unsilenced play one event as many times over |
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
| The message | `MapView.Click.hit` sends `wdgmsg("click", pc, mc.floor(posres), btn, modflags)`. `pc` is a screen coord the protocol carries and **not** what picks the destination, so any plausible value serves. It is `Widget.MouseButtonEvent.c` handed straight through `Hittest` to `checkmapclick`, so it is **map-view-local DEVICE pixels** — the space [world-3d.md](world-3d.md) gives that whole path, and a `Widget.rootpos()` away from a root coordinate. `mc` is world units, and `floor(posres)` is the **only** scaling in the pair: argument 1 and argument 2 are the same `{x, y}` shape in two different spaces |
| Getting it there | `UI.rawWdgmsg` resolves the widget id from **that** UI's own `rwidgets` and hands it to that UI's receiver. Raw rather than `wdgmsg` for a member: the action-hook chain belongs to the anchor and knows nothing about the session it would be walking for |
| Ground, and only ever ground | `Sessions.send` builds those four arguments and **no fifth**: a gob's `Gob.GobClick.clickargs` is never appended and never relayed. Not because it could not be spelled — a target could travel as an id and be rebuilt from each recipient's own `OCache` — but because a walk is the whole of what one session says to another. Everything else a click can mean stays with the character the player is looking at |

## Drawing another session into this one

Characters standing together need none of this: the anchor's `OCache` already carries them all. Apart,
one session's ground and objects are drawn into the anchor's scene under a single translation.

| What | Where |
|---|---|
| The sources | `MapRaster` and `Gobs` take their `MCache` and `Glob` as constructor arguments; `MapView.SessionTerrain`, `SessionGobs` and `SessionClickMap` are those same classes pointed at another session |
| One object, one copy | `MapRaster.skipcut` and `Gobs.skipgob`. The anchor claims its ground first and each patch claims what is left; a gob belongs to the **first** view, in a stable order, whose session can see it. Positional rather than claimed, because `skipgob` runs on Loader threads as well as on the tick and so can agree with nobody about who ran first |
| The one window that leaves | `SessionGobs.tick` reconciles the two sets on a 0.25s timer, which suits geometry — the boundary walks — but not an object the anchor has **just** learned about: a member's copy is already in the tree, so both are drawn and both tick until the timer runs, and an effect firing in that window is played twice. `Gobs.dedupe` evicts the copies at the moment the anchor's own enters the tree — at the end of `addgob` and not when the add was merely promised, or the object blinks out for as long as its model takes to build |
| Clickability | the pick pass tests `MapView.clmaptree` alone, so merged ground absent from it is scenery. `MapView.checkmapclick` then derives its coordinate from `cut.ul`, in the frame of whichever session's map produced that cut — the translation above it never enters that arithmetic. `MapMesh.map` says whose, and the answer is corrected once, at the source |
| Cost | `ShadowMap.maskshadow` keeps a patch out of `ShadowMap.ShadowList`, which is a second full render of every triangle it holds; the shadow map is a 750-unit box around the anchor's character, so a patch far enough away to need merging contributes nothing to it. Each patch is also frustum-tested per cut and per gob, because [nothing in the render path culls](world-3d.md) |

## Gotchas

- **A slot introducing a `Location` above locked state must declare `lockstate`.** The tree locks a
  great many slots — every map cut's click geometry, every composited gob — and a locked slot may not
  depend on an ancestor whose state could still change. `RenderTree.TreeSlot.checklockdeps` throws
  *"locked state depends on non-locked state"* on the UI thread, at the first frame that draws it.
  Locking is honest here because a patch's offset never changes: a `SessionView` is rebuilt whole if it
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

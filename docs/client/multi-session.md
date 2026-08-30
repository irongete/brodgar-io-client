# Several sessions in one client

One process, several accounts logged in at once, one of them on screen. The seams are in `haven`, the policy
on them is `io.brodgar.session`. The session drawn is the **anchor**; every other is live, ticked, answering
the server and not rendered, and which one is the anchor changes at any time. `UILoop.ui` holds the **login
screen** and never a game session, so a client with none draws that, `UILoop.layer` over it either way.

## Driving it

| Command | Does |
|---|---|
| `:session users` | the accounts with a token saved by the login screen (`Bootstrap.gettoken`), which is all `add` can reach — there is no login dialog for a second account |
| `:session add USER [CHAR]` | connect and hold a session open. The character name is the **rest of the line**, spaces and all; without one it plays whichever the server offers first. An account already live is refused. A token login does not rotate the token, and a failed add deliberately does not clear it |
| `:session list` | each session and how far it has got: connecting, character list, loading, its character and how many grids it has streamed, or **`dead`** — dropped, and on the list until its own thread unwinds `Sessions.Member.run`'s `finally`, which is what takes it off |
| `:session drop USER\|all` | close a session, **any** session. `Session.close` is what ends it, so `RemoteUI.run` unwinds through its own cleanup instead of being torn out from under itself. Dropping the last one leaves the client on the login screen, which is what logging out does |
| `:session where` | where each session actually is: the **base** its own corner minimap gives it (segment and segment tile coord), whether that base has been **proved** against a live grid, and its **offset from the anchor in grids**. One line per session, the anchor's own among them — its base is one half of every difference between two frames. A session with no proved base is named as such and its line stops there, carrying no coordinate at all: a place said through an unproved base is a place the character has never been. A line that names one of the three refusals says `no offset` rather than the last number that worked |
| `:session anchor USER` | go to a character, named by the **account** it logged in as. The client's own spelling of that gesture, beside every one the addon layer offers through `hafen.session():current(s)` — a switcher row, a cycle hotkey, a click-a-character hotkey. Every one of them calls `Control.take` rather than `Sessions.anchor` directly, so the screen and the selection always move together. Everything else in the layer reads `Sessions.anchor()`, so the offsets, the orders and the merged patches follow by themselves. `main` is refused **by its own name**: no session is called that, and left to fall through the account lookup below it, it would answer *no such session: main* and send you looking for an account of that name |

**The login screen is a place to go to, not only where you are left.** `Client.Main.run` builds a fresh
`Bootstrap` the instant it hands a `RemoteUI` to `Sessions.adopt`, so it is live in `UILoop.ui` behind every
session — waiting on a login nobody is looking at. `Control.take(null)` gives it the screen, which is
`hafen.session():current(nil)` and the switcher window's *New session* button, and nothing takes it back on
its own: `reclaim` returns while it holds the anchor, and `adopt` anchors the session it logs in precisely
because none does. That makes it the only door for an account with **no saved token** — `Sessions.add`
reads `Bootstrap.gettoken` and can reach nothing else — and the sessions behind it go on being ticked by
`Sessions.tick` throughout, since none of them holds the screen. Being nobody, it fires no session event:
`AddonManager.queueSession` drops a nameless one, and `Sessions.anchor` calls `sessionSelected` only for a
member.

**One session per account, on both doors.** `Sessions.adopt` and `Sessions.add` refuse a login for an
account already live through one `Sessions.claim`, because the account *is* the address above this layer:
two members of one name share a row in the switcher and a `Session` object in every addon, and the server
ends one of the two connections a moment later regardless. `adopt` closes the session it refuses and
throws, which `Client.Main.run` catches to put the login screen back up. The reason travels in
`Sessions.denial` and is shown by `Bootstrap.run` on the next `LoginScreen`, since `Sessions.say` delivers
to the anchor and the anchor at that moment is a login screen whose tree answers no notice.

## The RTS mode

A mode and not a rebinding, and since 105 not a gesture either: the mouse keeps every one of Haven's own
bindings and this layer claims no button and no modifier over the map. What the mode adds is one
**recipient** — with somebody other than the drawn character selected, a left click on the ground walks the
selection rather than the character on screen. Off, and with only the drawn character selected, the map
view behaves exactly as it does without any of this.

**It owns no input and draws nothing.** Naming a character with the mouse was `Control.mousedown`, showing
who is named was `Control.draw`, and cycling and centring were `Control.keydown` — all four are the addon
layer's now, and `session-manager` is what carries them. They reach Java through the API like anything
else: `hafen.session():current(s)` (which is `Control.take`), `hafen.ui():mouse():cursor(name)`, `ev:gob()`
and `s:world():focus(p)`. What is left here is the part no addon can hold — the selection, and the one
click the mode re-addresses.

**That is what makes the modifiers free.** Nothing of this layer's reads a modifier at all, so Alt and
the left button over the map are an addon's to claim.

**The mode has no switch of its own.** `Sessions.tickmode` derives it from the membership —
`members.size() > 1`, the drawn session being a member too — and calls `Control.mode` on the edge alone,
outside the branch that ticks them, because dropping the last one is exactly the frame on which the list
is empty and the mode still has to go off.

**The mode installs no camera, ever.** `MapView.RTSCam` — a `FreeCam` with a centre of its own, so that
panning survives the character moving — is registered under the name `rts` beside every other camera
([camera.md](camera.md)) and is installed by hand with `:cam rts`, mode or no mode. It is what
`s:world():focus(p)` needs: that verb raises under any other camera, none of which has a centre to move.

**There is one camera, not one per character.** Sessions share a camera's state and never the object
([camera.md](camera.md)). `Sessions.anchor` reads the incoming session's `Placed.offset` **before**
`lp.drawn` and before `invalidate`, while `placed()` still measures every offset against the session that
has the screen — the frame the outgoing pan is named in — and hands it to the `MapView.adoptcam` that
`Sessions.tickview` runs. Read after those two lines, `buildplaced` answers `Coord2d.of(0, 0)` for the new
anchor and the pan lands a whole offset away; a session with no offset falls to *follow the character*.

| Input | Does |
|---|---|
| Left click, on the ground, with somebody **else** selected | walk the selection there, and nothing else — the only order there is. The destination is resolved by the client's own pick pass (`MapView.ClickOrder extends Hittest`), so it lands where a real click would. The right button is never taken over at all |
| Left click, on a gob | the click it always was. `ClickOrder` hands it back through `MapView.clickhit`, the same code an ordinary `Click` runs — one press is still one pick pass and one message, only decided a frame later. It cannot be answered in `mousedown`, which is a frame before anything knows what was under the cursor. Interacting with a gob is the drawn character's own business and travels to no other login |
| Left click, with only the character on screen selected | also the click it always was — `Control.commands` reports nobody to command. Ordering your own character to walk somewhere *is* a left click, and this is not a corner case: `Control.take` makes the drawn character the whole selection on every switch, so without it the first thing a second session costs is the left button |
| Middle drag; ctrl and middle drag | pan; `FreeCam`'s own rotate and elevate. The pan's pixel delta is solved back into world units through the view's own projection — three `screenxf` probes and a 2×2 inverse — rather than by rebuilding the camera's trigonometry. `RTSCam.click` reads `ui.modflags()` once, at the press, so letting Ctrl go mid-drag does not change what the drag is already doing. No modifier collides: `MapView.mousedown` sends `ev.b == 2` straight to `camera.click` with no modifier branch and no fallthrough |
| `cam-reset` (Home), `cam-left`, `cam-right`, `cam-in`, `cam-out` | follow the drawn character again; rotate and zoom, since `FreeCam` has no keyboard of its own. These are the camera's own, so they answer with the mode off too |

**The mode has no keys of its own.** `rts-next-anchor` and `rts-focus` are gone with the gestures: cycling
and centring are `session-manager` hotkeys, listed in `OptWnd.BindingPanel` under the addon's own name by
the generic per-addon loop rather than by a hand-written **Multi session** section. An addon hotkey ships
unbound for the same reason a client one would have to: `KeyBinding.get` runs none of `set`'s exclusivity
pass, so two *defaults* sharing a key leave both firing, and neither is repairable afterwards
([services.md](services.md)). A modifier cannot be bound at all — `KeyMatch.Capture.handle` refuses a bare
`VK_SHIFT`/`VK_CONTROL`/`VK_ALT`, which is what holds the key grab open across a modifier press — so a
hold-while-dragging gesture cannot be a binding whoever owns it.

## One loop, several sessions

| What | Where |
|---|---|
| **The three UIs, and why they are three** | `UILoop.ui` is the runner's — the **login screen**, which `Client.Main` replaces and **destroys** as its chain advances; `UILoop.drawn` is the one actually drawn, dispatched to and `gtick`ed. One field could not be both — destroying the runner's UI must never be able to destroy a session it does not own, and the slot holding no game session is what makes that unreachable. `drawn()` answers the session on screen, or `ui` when none does. `UILoop.layer` is the **addon layer** (fork): built once in the constructor with a null `sess`, never replaced or destroyed, ticked and hovered beside the drawn one, drawn on top of it and offered the input first ([boot-and-loop.md](boot-and-loop.md)) — it holds no `Glob`, so `Sessions.tick` and every count of "how many sessions" pass it by |
| Building a session's UI | `UILoop.bgui` — `newui` minus the replace and the destroy, and like it built **outside `uilock`**, because `UI`'s constructor runs `Runner.init` and no other lock may be taken underneath that one. The profiling fields are left off: `uprof`/`rprof`/`gprof` describe the frame, and only the anchor has one |
| Where a session comes from | `Client.Main.run` hands its `RemoteUI` to `Sessions.adopt` instead of running it — and goes straight back to a fresh `Bootstrap`, so **the login screen is live behind every session** — while `Sessions.add` connects a saved token; both build with `bgui` on a thread of their own ([boot-and-loop.md](boot-and-loop.md)). The **account name is reserved first**, under `Sessions.claim`'s own monitor and ahead of `connect`'s two blocking round-trips: a check reading the membership on the near side of those lets a second add of the same name past the same read. `Sessions.enlist` relieves the reservation by putting the member on the list under that one monitor, and a `finally` releases it — never a `catch`, which misses the `InterruptedException` and the `Error` and would leave the account unusable for the rest of the login. That monitor is a **leaf**: nothing is taken underneath it, and both doors reach it from a thread of their own holding no widget tree and not `Sessions.class`. A session then registers **before** its `UI` exists, because `MapView`'s constructor asks `Sessions.dormant(glob)` once and keeps that answer for the life of the view — built for a member not yet on the list it comes up non-dormant, meshing terrain for a session nobody draws. `Sessions.Member.start` unwinds a `Thread.start` that does not take: the thread it starts is the only thing that ever runs that `UI`'s runner chain and the only thing that destroys it, so it clears `ui` and discards the tree rather than leaving one live and ticked by nothing |
| Taking one down | `UILoop.bgdestroy` — `bgui`'s counterpart, run from the **session's own** thread (`Sessions.Member.discard`): it takes the screen off that `UI` if the caller has not, waits under `uilock` for any frame still holding it, then `UI.destroy`. So a `UI` can already be destroyed before the loop thread's next tick notices the anchor moved — a relogin destroys the old one and builds the new one inside `Member.run`, which is why anything that must still read a session as it ends has to **hold** what it needs rather than look it up by that `UI` |
| The anchor's frame | `UILoop.Frame.tick`: dispatch, then `synchronized(layer)` for the layer's `tick`/`gtick`/hover/resize, then `synchronized(ui)` for `glob.ctick`, `glob.gtick`, `ui.tick`, `mousehover`, resize — one monitor at a time, and the session's hover is told the layer took the pointer |
| Everything not drawn | `Sessions.tick`, called **after** that block closes, each session under its own monitor, and the one holding the screen skipped because the frame above has already ticked it in full. One call and one loop: every game session the client holds is a member of that list |
| Handing the screen over | **Two halves.** `Sessions.anchor` publishes — it reads the incoming offset, flips `UILoop.drawn`, `invalidate`s and coalesces one request — and touches no widget tree, so it is callable from a thread already holding one. `Sessions.tickview` spends that request at the top of `UILoop.run`, after `env.render()` and before the `uilock` block, on the frame's own thread and holding nothing: `adoptcam` then `dormant(false)` on the incoming view, `dormant(true)` on the outgoing one, each tree walked and written under its **own** monitor — a **private** walk, because `Widget.child`/`next` are not volatile and `Widget.unlink` ends by setting `next = null`, so a `findchild` racing that session's own `Loader` (`UI.CommandQueue.execute` → `NewWidget`/`AddWidget`/`DstWidget`, each under `synchronized(UI.this)`) stops early and answers `null` in silence, and a missed `dormant(false)` leaves the session that just took the screen drawing no scene until the player switches away and back. `Sessions.mapview(UI)` itself stays unguarded: `AddonManager.screenView` reaches it from handlers already holding the **layer's** monitor, and a `synchronized(u)` in it would nest layer → session. The coalesce keeps the **first** `cur` and the **last** `target`, and a request whose two ends are the same `UI` is **dropped** — away and back inside one step is not a change of screen, and applying that pair would `detachscene` the view being drawn. `Sessions.relinquish` publishes from a dying session's own thread, re-reading the anchor and picking the successor **under** `Sessions.class`; `Sessions.reclaim` does it a frame later if that did not happen. Both hand the screen to another live session — or to the login screen when there is none left |

**One lock direction, and it is load-bearing.** The tick never holds two UI monitors at once, the addon
layer's included — its tick is a block of its own, and its input dispatch takes each tree's in turn. The
console does nest them — it runs inside the drawn UI's monitor and reaches into another session's — so that
direction, anchor then member, is the only one anything may take. **`Sessions.class` is a leaf under all of
it**, which is what lets a keybinding, a control, a draw handler or an action hook ask for the screen while
already holding the drawn tree. `Sessions.say` queues its text and drains it on the tick for the same
reason: delivering means taking the **anchor's** monitor from whatever spoke.

## What dormancy buys, and what it costs

A session that is not drawn is still whole: a `Session`, a `UI`, a widget tree and a `Glob`.

| Consequence | Where |
|---|---|
| **Its terrain is never meshed** | `MapRaster.Grid.tick` returns while its node holds no slot, so a view outside a render tree builds nothing. That one fact is what makes holding several sessions affordable, and it cuts both ways: promoting one re-meshes everything in view, which is the hitch on an anchor switch. `MapView.dormant` is the attach and the detach — and it is **decided once**, in the constructor, which then runs its `if(!dormant)` block or does not, for ever. Promotion calls `attachscene` again; nothing re-enters that block, so whatever is hung inside it never happens **at all** for a view that came up behind another, and a constructor is the wrong place for anything that is not about the drawn scene |
| It still asks the server for ground | `MapView.tick`'s dormant branch. `MCache.sendreqs` and `reqarea` live in `draw`, so a view that is never drawn would never request a grid — and both the base's proof below and any order needing a destination rest on the session having loaded the ground it stands on. Throttled: `reqarea` calls `getcut` once per cut of its rectangle, and `sendreqs` already rate-limits each grid to one request a second |
| It takes a full share of the addon engine | Its own tick pump and its own per-session caches, attached from `Sessions.Member.start` the moment its `UI` exists — a session's **arrival** is what drives that, never the screen moving to it, so a session nobody looks at is served exactly like one that is. What is not per session is the addons: they are the client's, loaded once, and each ask names what it is about — `Sessions.byuser(user).ui` for the tree an addon **searches**, `Sessions.anchor()` for the screen, `Sessions.mapview` for the scene and `Sessions.layer()` for the tree its own windows are built into. A search reaches a session whether or not it is drawn; only what is genuinely the screen's follows the anchor, and nothing is torn down when it moves |
| **Its effects are consumed, not deferred** | An overlay is ticked only once it stands in a render tree, and a dormant view has none — so `Gob.ctick` ticks one whose `slots` are still empty when `Glob.dormant` says nobody is looking, and `Sprite.unheard` ends the sprites whose only other ending is being played through (`AudioSprite.ClipSprite`). Otherwise the sprite never ages, never leaves `ols` and keeps the audio stream it opened at construction: every effect a background session was ever told about piles up, and the whole backlog enters the tree in a single frame when that session takes the screen. `Glob.dormant` is settled once per session per frame because `Gob.ctick` asks it per gob |
| It is silent | `Sessions.applymute` → `ActAudio.RootChannel.mute` scales the channel's `VolAdjust` and leaves `volume` and its pref alone: going quiet because nobody is looking must not read as the user turning the sound down, nor survive into the next launch. **From a session's first sound, not its second**: `mute` is `synchronized` on the channel, and that is the monitor `RootChannel.mixer()` builds the `VolAdjust` under and reads `muted` under ([services.md](services.md)). The frame writes the mute for a channel that has played nothing and holds no `VolAdjust` at all, and the session's own thread builds one the moment it does play — on the far side of that edge, or it comes up audible and every later `mute` returns on no change. Every live session, every frame, and not through `placed()` — the answer changes when a session **joins**, not only when the anchor moves, and a session still on the character list is already making noise while being absent from `placed()`. What this silences is the whole UI channel, which is most of the game's audio: the server's own sounds (`RootWidget`), the minimap's alerts (`GobIcon`), the chat ping (`ChatUI`). The server tells every session about the same event and each `UI` rate-limits only its own (`UI.lastmsgsfx`), so sessions standing together and left unsilenced play one event as many times over |
| It does not fight over window geometry, and it writes its own down as the screen leaves | `GameUI.onscreen` gates all three writes: several `GameUI`s persist to the same keys, and the damage is not that they want different layouts — it is that an untouched session writes back the positions it loaded. **The character losing the screen is the one exception, and it has to be** — the anchor is published before the frame moves the views, so by then that guard is false by construction. `MapView.dormant(true)` calls `GameUI.leavingscreen`, which is the write without the guard, so what the player just arranged goes down on the switch rather than waiting out the 60 s clock or being lost with the session ([gameui-windows.md](gameui-windows.md)) |

## Aligning two coordinate frames

`Gob.rc` is relative to where its session logged in, so one patch of ground has a different number in each
session. Every session records its ground into one map database, so **where a session sits in that record
relates it to any other**: two frames are differenced through their bases, needing no ground in common.

| What | Where |
|---|---|
| The base | `Sessions.Member.Base` — the `MapFile`, the segment id, the segment tile coord of that session's tile `(0, 0)`, and whether it has been **proved**. Immutable, replaced whole by `Sessions.Member.tickbase` from `GameUI.mmap.sessloc` and no other `MiniMap`: `MapWnd` carries a second over the same file, and a base taken from that one follows a window the user panned. Derived every tick for **every** member, in a loop of its own ahead of the one that skips the session holding the screen — the anchor's base is one half of every difference |
| Proving it | `Sessions.Member.prove` — a live `MCache.Grid`'s coord translated by the base must find that grid's own `id` in `Segment.gridid`. `sessloc` goes stale rather than null, so a base that merely exists is not one a place may be read through; [minimap.md](minimap.md) carries the rule, its `tryLock` and its lock-miss verdict |
| The offset | `Sessions.Member.tickoffset`: `anchor.tc.sub(mine.tc)` scaled by `MCache.tilesz`. A base is grid-aligned or `tickbase` refuses it, so this is a whole number of grids and exact |
| No offset | three refusals, in this order and each named: one of the two bases is not proved · the two name different `MapFile`s (`chrmap`) · they name different segments of one. The database is asked before the segment because `Segment.id` is `rnd.nextLong()` inside the file that minted it. `Member.offwhy` holds the reason and `Sessions.say` fires only when it **changes**; `Member.offset()` answers null, and `Sessions.buildplaced` drops the member — a session that cannot be related leaves the merged scene rather than being drawn through a base nobody checked |

**Derived again every tick, and never kept.** The server re-bases a session's coordinate space mid-play
(a cave, a house, `MCache.trimall`) and a `Glob` survives that, so an offset found once and kept goes on
placing that session's ground and objects where they have never been for the rest of the login. It
subscribes to nothing: a number derived from scratch each frame needs no event to say the ground moved.

## Ordering a session that is not drawn

An order is not a mouse event. It is the widget message a click would have produced, and it needs no
camera, no click-map and no scene — which is why a session obeys while invisible.

| What | Where |
|---|---|
| The message | `MapView.Click.hit` sends `wdgmsg("click", pc, mc.floor(posres), btn, modflags)`. `pc` is a screen coord the protocol carries and **not** what picks the destination, so any plausible value serves. It is `Widget.MouseButtonEvent.c` handed straight through `Hittest` to `checkmapclick`, so it is **map-view-local DEVICE pixels** — the space [map-click.md](map-click.md) gives that whole path, and a `Widget.rootpos()` away from a root coordinate. `mc` is world units, and `floor(posres)` is the **only** scaling in the pair: argument 1 and argument 2 are the same `{x, y}` shape in two different spaces |
| Getting it there | `UI.rawWdgmsg` resolves the widget id from **that** UI's own `rwidgets` and hands it to that UI's receiver. Raw rather than `wdgmsg` for a session that is not drawn: the action-hook chain belongs to the anchor and knows nothing about the session it would be walking for |
| Ground, and only ever ground | `Sessions.send` builds those four arguments and **no fifth**: a gob's `Gob.GobClick.clickargs` is never appended and never relayed. Not because it could not be spelled — a target could travel as an id and be rebuilt from each recipient's own `OCache` — but because a walk is the whole of what one session says to another. Everything else a click can mean stays with the character the player is looking at |

## Drawing another session into this one

Characters standing together need none of this: the anchor's `OCache` already carries them all. Apart,
one session's ground and objects are drawn into the anchor's scene under a single translation.

| What | Where |
|---|---|
| The sources | `MapRaster` and `Gobs` take their `MCache` and `Glob` as constructor arguments; `MapView.SessionTerrain`, `SessionGobs` and `SessionClickMap` are those same classes pointed at another session |
| One object, one copy | `MapRaster.skipcut` and `Gobs.skipgob`. The anchor claims its ground first and each patch claims what is left; a gob belongs to the **first** view, in a stable order, whose session can see it. Positional rather than claimed, because `skipgob` runs on Loader threads as well as on the tick and so can agree with nobody about who ran first |
| **One object, several `Gob`s** | `skipgob` asks `glob.oc.getgob(ob.id)` of every other session, so **`Gob.id` is the server's** and names the same object in each of them. The object is not shared: every `OCache` holds its own `Gob`, and `Gob.glob` is **`final`** — a gob is placed against the map of the session that built it (`Gob.placer` &rarr; `glob.map.mapplace`, `Gob.getmapstate` &rarr; `glob.map.tiler`), so drawing a thing in another session's scene means **building another `Gob`**, never adding one to two views. ⚠️ `Gob.rc` is that session's frame too, so a gob handed across without the offset above is placed where nothing is. ⚠️ **`OCache.ChangeCallback` has `added` and `removed` and no third**: `OCache.add`/`remove`/`ladd`/`lrem` are its only call sites and nothing disposes a cache, so a session ending drops everything it held **in silence** — a consumer that tracks which objects exist has to re-ask on its own when a `UI` dies. `OCache.cbs` is a `WeakList`, so a callback also needs a strong reference somewhere or it is collected and simply stops arriving |
| The one window that leaves | `SessionGobs.tick` reconciles the two sets on a 0.25s timer, which suits geometry — the boundary walks — but not an object the anchor has **just** learned about: another session's copy is already in the tree, so both are drawn and both tick until the timer runs, and an effect firing in that window is played twice. `Gobs.dedupe` evicts the copies at the moment the anchor's own enters the tree — at the end of `addgob` and not when the add was merely promised, or the object blinks out for as long as its model takes to build |
| Clickability | the pick pass tests `MapView.clmaptree` alone, so merged ground absent from it is scenery. `MapView.checkmapclick` then derives its coordinate from `cut.ul`, in the frame of whichever session's map produced that cut — the translation above it never enters that arithmetic. `MapMesh.map` says whose, and the answer is corrected once, at the source |
| Cost | `ShadowMap.maskshadow` keeps a patch out of `ShadowMap.ShadowList`, which is a second full render of every triangle it holds; the shadow map is a 750-unit box around the anchor's character, so a patch far enough away to need merging contributes nothing to it. Each patch is also frustum-tested per cut and per gob, because [nothing in the render path culls](map-click.md) |

## Gotchas

- **A slot introducing a `Location` above locked state must declare `lockstate`.** The tree locks a great
  many slots — every map cut's click geometry, every composited gob — and a locked slot may not depend on
  an ancestor whose state could still change. `RenderTree.TreeSlot.checklockdeps` throws *"locked state
  depends on non-locked state"* on the UI thread, at the first frame that draws it. Locking is honest
  here: a patch's offset never changes, and a `SessionView` is rebuilt whole if it does.
- **`TickList` does not call `Gob.gtick`.** It dispatches `TickList.Ticking.autogtick`, which `Gob.Placed`
  does not implement, so a gob's animated pose reaches the GPU through `OCache.gtick` alone: one drawn
  from a session whose `Glob.gtick` never runs shows whatever its buffers hold, tree or no tree.
- **Foliage sway measures from the gob's own origin.** `lib/svaj` displaces each vertex by an amplitude
  proportional to its distance from `Svaj.origin`, which `GobSvaj.placestate` takes from `Gob.getc` in the
  gob's own session's frame. Draw those vertices translated and the subtraction misses by the translation,
  and **the miss is the amplitude** — trees thrash instead of swaying: only trees, only horizontally while
  the translation has no z, and only for a session logged in on a different grid. The copy under
  `haven.res.lib.svaj` corrects it, and the engine prefers a local copy only while its `FromResource`
  version matches the resource served, so a server-side bump restores the bug with a warning naming it.

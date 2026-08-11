# RTS — fleet control across several sessions, one view

> Branch **`rts`**. This is a structural change to the client core, outside the `/plan` → `/implement`
> → `/end` cycle: it is not one feature with one suite, it is a series of spikes that each answer the
> question the next one depends on. Everything stays local; nothing is pushed.

## What it is

Log in several accounts in one client process. When the characters stand in the same area, see them in
**one** world view, select one or several — click, or drag a box — and give orders individually or to
the whole selection. StarCraft, with Haven characters.

**Not in scope (v1):** playing several characters that are far apart in one view; driving another
character's inventory, crafting or menus without making it the anchor; anything that needs the server
to do something it does not already do for a normal client.

## The model, and the five facts it rests on

The characters are in the same area, so **the anchor session already sees all of them**: its `OCache`
receives every nearby object, including the other accounts' characters, exactly as it receives any
other player. There is nothing to compose. One scene is rendered, and it is an ordinary session's
scene. Render cost is ×1.

What has to be built is the **command path**: a click on another character's gob selects *its* session,
and an order travels down *its* socket.

| # | Fact | Where |
|---|---|---|
| 1 | Game state is already per-session: `Glob` holds `oc`, `map`, `sess`, `loader` as instance fields, and `MapView` takes its `Glob` by constructor | `haven/Glob.java:34`, `haven/MapView.java:499` |
| 2 | Terrain meshes are built **only for a view that is in a render tree** (`if(slot == null) return;`) — a background session costs network + `OCache` + widget tick, not GPU | `haven/MapView.java:674` |
| 3 | `MCache.Grid` carries **both** `gc` (login-relative) and `id` (the server's), and `grids` maps coord → grid — so the offset between two sessions' frames is exact, live, and needs no `MapFile` | `haven/MCache.java:306`, `haven/MCache.java:51` |
| 4 | An order is a plain widget message, synthesizable without a mouse and without a drawn view: `wdgmsg("click", pc, mc.floor(posres), btn, modflags[, clickargs])` | `haven/MapView.java:2130` |
| 5 | **Gob ids are the same for every player** (established by the maintainer), so a fleet member is found in the anchor's `OCache` by `GameUI.plid` alone — no position matching | `haven/GameUI.java:41`, `haven/OCache.java:199` |

`UI.rawWdgmsg(sender, msg, args)` (`haven/UI.java:694`) is the door for a fleet order: it resolves the
widget id from *that* UI's own `rwidgets` and hands it to *that* UI's receiver, and it deliberately
bypasses the addon action-hook chain. Ordering session B is `bUI.rawWdgmsg(bGameUI.map, "click", …)`.

## Phases

Each phase ends with something observable. A phase is not finished because the code compiles.

### F0 — Several sessions alive in one process

The scaffolding, and the phase that decides whether the whole thing is affordable.

- `UILoop`: the single `ui` field becomes an ordered list of slots plus an `anchor` pointer.
  `newui()` (`haven/UILoop.java:81`) currently **destroys** the previous UI — split it into the
  replacing form (what the anchor's own `Bootstrap → RemoteUI` chain still needs) and
  `addui()` / `removeui()`.
- `Frame.tick` (`haven/UILoop.java:476`): `ctick()` every session's glob; `gtick(out)` and
  `mousehover`/`root.resize` only the anchor's. Background roots keep a nominal size.
- `Client.Main` (`haven/Client.java:184`): one runner thread per session. Extract a `SessionSlot`
  holding `{Session, UI, runner thread, GameUI}`.
- `Client.EventQueue.dispatch(ui)` (`haven/Client.java:103`): dispatch to the anchor only, for now.
- Adding a session: `Client.connect()` (`haven/Client.java:313`) is already static, already returns a
  `Session` from a saved token, and the token pref is already keyed per user
  (`savedtoken-<user>@<host>`). So `addsession(user)` = connect → `new RemoteUI(sess)` on a fresh UI
  and thread. No login UI in this phase.
- Drive it from the console: `:fleet add <user>`, `:fleet list`, `:fleet drop <n>`. `Client` is already
  a `Console.Directory` (`haven/Client.java:239`).

**Watch:** a background session's `MapView` must never enter a render tree. The server creates that
widget; nothing today stops it from meshing terrain on tick.

**Done when:** two accounts are connected, only one is drawn, and `:stats on` shows the frame time and
memory of the second session. Those two numbers decide how many accounts this design can carry — write
them down.

#### As built

- `io/brodgar/rts/Fleet.java` — the registry, the saved-token login, the runner chain and the tick.
- `UILoop.bgui()` builds a member's UI exactly as `newui()` builds the anchor's, but replaces nothing
  and destroys nothing; `Frame.tick` ticks the fleet **outside** the anchor's monitor, in its own
  `"fleet"` CPU phase, so what a member costs is readable directly in `:profile`.
- `MapView` grew a `dormant` flag, read once in the constructor from `Fleet.dormant(glob)`. Only the
  constructor half exists — `dormant(false)`, the promotion, belongs to F5 and is not written until
  something can verify it.
- `RemoteUI.init` no longer hands the addon engine to a session the client is not drawing.
- `MCache.numgrids()` — how much ground a member has actually streamed.
- `:fleet add|drop|list|users`, with the connect on its own thread so the client keeps drawing.

**Two things the plan had not accounted for, both found by building it:**

1. **The scene attaches in the constructor**, not at draw: `basic.add(gobs)` / `basic.add(terrain)` /
   `clmaptree.add(clickmap)` all ran at `MapView.<init>`. So "never drawn" was never going to be
   enough on its own — fact 2 only pays off because the constructor now skips those three adds. This
   was the predicted risk, and it was real.
2. **A view that is never drawn never asks for map data.** `glob.map.sendreqs()` and
   `glob.map.reqarea(…)` both live in `MapView.draw`, nowhere else. A dormant session would have sat
   on whatever grids the server volunteered and streamed nothing — which would have left **F1 with no
   shared grid to align on**, and looked like the offset was impossible rather than un-requested. A
   dormant `tick` now does those two calls and nothing else.

**A third thing, found on the first run:** a fleet member is invisible by construction, so *the only
evidence it exists is what it says*. `Console.out` lands in the chat's "System" channel, which is easy
to have closed — a member that connected fine and then sat on its character list forever produced no
sign of either. Fleet messages go through the anchor's on-screen notice (`UI.msg`) instead, and a
requested character that is not offered is reported with the list of names that were. Every later
phase inherits this: **anything a member does silently, did not happen** as far as the maintainer can
tell. (Haven character names contain spaces, so `fleet add` takes the rest of the line as the name.)

**Not yet measured.** The numbers this phase exists for need two accounts in-game.

### F1 — The coordinate anchor

- New code under `src/io/brodgar/rts/`. Core edits into `haven` stay minimal, centralized, and tagged
  `// rts:`.
- `offset(A, B)`: find a grid `id` present in both `A.glob.map.grids` and `B`'s; then
  `offset = B.gc − A.gc` in grid coords, `× cmaps × tilesz` for world coords. Cache it; invalidate on
  relog. `grids` is package-private and mutated on the Connection worker under `synchronized(grids)` —
  take the lock, copy what is needed, get out.
- No shared grid ⇒ no offset ⇒ the pair is not co-located. That is a legitimate state, not an error:
  it is exactly the "they are far apart" case v1 does not serve.

**Done when:** the anchor's own position, translated into B's frame, matches B's `getcc()` within a
tile — printed live while both walk.

#### As built

- `MCache.gridids()` — a snapshot of `id → gc` taken under the grids lock, skipping grids whose `id`
  has not arrived yet (`fill()` writes it when the `"m"` layer lands, so a grid exists briefly with
  none).
- `Fleet.Member.tickoffset()` finds the offset once and keeps it, dropping it whole if either side
  relogs. Throttled to one attempt a second until it succeeds, then one reference comparison a frame.
- `Member.toanchor(p)` / `tomember(p)` — the pair F2 needs. `tomember` is the order's translation.
- `:fleet where`, and a per-member error on the `:stats on` line.

**A better test than the one the plan wrote down.** The plan said to translate the anchor's own
position into the member's frame and compare with the member's `getcc()` — but those are two different
characters standing in two different places, so there is nothing to compare. The real invariant uses
the confirmed global gob id: take the **member's own character**, translate it into the anchor's
frame, and compare against **where the anchor independently sees that same gob**. One object, two
observers, and the error should be zero. It proves the offset and the gob-id sharing in one number.

**Disagreement is reported, never averaged.** Every shared grid must yield the same difference — two
frames are rigid translations of one another or they are not frames. If they disagree, `:fleet where`
says `DISAGREEING` rather than silently picking one; quietly averaging is how a broken premise would
survive into F2 as characters walking to the wrong place.

### F2 — The order

- `Fleet.order(slot, anchorPos, btn, mods)`: translate the point, then
  `slot.ui.rawWdgmsg(slot.gameui.map, "click", pc, mc.floor(posres), btn, mods)`.
- `pc` is a screen coord the protocol carries; pass the anchor's. It is not what decides the
  destination.
- A gob-targeted order appends `inf.clickargs()` (`haven/MapView.java:2132`). Ids being global, the
  gob part transfers verbatim — **check whether `Gob.GobClick.clickargs` also encodes a coordinate**,
  and translate it if so.

**Done when:** `:fleet move` walks the *non-anchor* character to a point clicked in the anchor's view.
That is the whole thesis of the project proved.

#### As built

- `Fleet.order(member, anchorpos, btn, mods)` — translate with F1's offset, then
  `u.rawWdgmsg(gui.map, "click", …)` into the member's own tree and session. Six lines. The member has
  no camera, no click-map and no scene, and needs none: an order is not a mouse event.
- `:fleet come` (every member walks to where the anchor is standing) and `:fleet moveto X Y` (tile
  coords in the anchor's frame). Clicking to give the order is F3's job — it needs selection first.

**`clickargs` does carry a coordinate — confirmed.** `Gob.GobClick.clickargs` is
`{0, (int)gob.id, gob.rc.floor(posres), 0, -1}`, and that `rc` is in the *observing* session's frame.
So a gob-targeted order cannot be relayed verbatim. The fix is better than translating it: gob ids are
global, so the member's own `OCache` can be asked for the same gob and produce the coordinate in its
own frame directly. F3 does that, where selection makes it testable.

**`say()` is queued, not spoken where it is thought.** Delivering a message takes the *anchor's*
monitor, and `say` is called from fleet threads, from the console (which runs inside the anchor's
monitor already) and from the tick. Queue + drain on the UI thread holding nothing: one lock
direction, and that whole class of deadlock cannot form.

### F3 — Selection

- `Map<Long, SessionSlot>` keyed by `GameUI.plid`. A click that resolves to a fleet member's gob
  (`Click.hit`, `haven/MapView.java:2116`) selects that session; ctrl/shift extends.
- **Marquee:** do *not* build a rectangular hit-test over `Clicklist` (`haven/MapView.java:1167`). We
  only ever box-select our own characters, and we know exactly where they are — project each fleet
  member's `rc` to screen and test containment against the dragged rectangle. No new picking
  machinery.
- Selection highlight: an overlay on the selected gobs.

**Done when:** dragging a box over two characters selects both, and a right-click sends both.

#### As built

- `io/brodgar/rts/Control.java` — selection, marquee, the mouse seams and the on-screen brackets.
  Split from `Fleet` because they answer different questions: a session exists whether or not anyone
  is commanding it, and the selection is about the anchor's screen, not about anybody's connection.
- `MapView.FleetClick` — a `Hittest`, so an order's destination is resolved by the client's **own**
  pick pass and lands exactly where a click would have. It sends nothing itself; it hands the point
  and the target gob's id to `Control`.
- Four `// rts:` seams in `MapView`: after the camera/placing/grab chain in `mousedown`, in
  `mousemove`, in `mouseup`, and after `partydraw` in `draw`.
- `Fleet.orderunit(gobid, …)` and `Fleet.bygob(gobid)`; `:fleet rts on|off`, `:fleet sel`.

**The anchor is a unit too.** An RTS where your own character cannot be boxed with the others is not
one. An id that belongs to no member *is* the anchor's, so it needs no translation and sends through
`wdgmsg` — its orders stay indistinguishable from real clicks, and visible to the addon action hooks.
A member sends through `rawWdgmsg`: that chain belongs to the anchor and knows nothing about the
session it would be walking for.

**A target gob travels as an id, never as coordinates.** This is F2's `clickargs` finding, resolved:
each recipient looks the gob up in its **own** `OCache` and builds its own arguments. Copying the
anchor's `{0, id, rc.floor(posres), 0, -1}` would hand every member a coordinate belonging to somebody
else's login. A unit that cannot see the target walks to the spot instead — which is what a click on
ground it could not identify would have done anyway.

**RTS mode rebinds nothing.** The mouse keeps Haven's own bindings with the mode on: left click walks
and interacts, right click does what right click does, the middle button is the camera's. The mode
adds exactly one gesture — **ALT** held, left button: click a unit, or drag a box, and shift/ctrl
alongside it extends. What the mode changes is not the button but the *recipient*: with units
selected, a click is asked of them instead of the character on screen, carrying **the button that was
pressed**, so each unit receives the very message that click would have produced. With nothing
selected the map view behaves exactly as it always did.

**No rectangular hit-test was built.** We only ever box-select our own characters and we know exactly
where they are: `MapView.screenxf` projects each one and the rectangle is tested in screen space. A
click picks the nearest unit within 24px by the same measure. The `Clicklist` is untouched.

### F4 — The camera

`camtypes` is extensible (`haven/MapView.java:61`), but every shipped camera centres on `getcc()` —
the player. A fleet camera has its own centre: free pan, centre-on-selection, and it stops following
the anchor's character. New `Camera` subclass, registered in `camtypes`.

#### As built

- `OrthoCam.camcc()` — the one line that said "the camera looks at the player", factored out. Two
  call sites changed (`OrthoCam.tick2`, `SOrthoCam.tick2`). That is the entire core edit.
- `MapView.FleetCam extends SOrthoCam`, registered as `fleet` in `camtypes`. It inherits the
  isometric snap, the wheel zoom and the arrow-key rotation, and overrides only where it looks.
- `KeyBinding "rts-focus"` (space by default) centres on the selection, or on the whole fleet when
  nothing is selected. `Home` goes back to following the anchor's character.
- The camera comes with the mode: `:fleet rts on` swaps it in and `off` puts the previous one back.
  Neither writes the `defcam` pref — this is a mode, not a preference.

**The middle button pans instead of rotating.** Rotation is already on the arrow keys, and an RTS
without panning is not one.

**Rebased onto `FreeCam` (`bad`), not `SOrthoCam`** — perspective, at the maintainer's call. `FreeCam`
got the same `camcc()` hook, and its `dist`/`angl`/`elev` targets became `protected`.

**The black-out when pulling back was the projection, not culling.** `Camera.resized()` fixes the far
plane at 2000, which is generous for a camera bolted to a character and is the entire world for one
that is not: past it everything is clipped, ground included. No draw-distance setting would have
touched it. `FleetCam` now sizes the frustum from the current distance every tick, and raises the near
plane with it so the depth buffer does not spend all its precision on the first ten metres.

**`field` is a size, not an angle.** `makefrustum`'s scale term is `2*near/(right-left)`, so the
frustum rectangle is given *at the near plane* and the field of view is `field/near`. Raising the near
plane while holding `field` at the shipped 0.5 narrowed the view by exactly the factor the distance
had widened it — the camera moved back and the image did not, which reads as a zoom that is stuck.
Measured rather than reasoned about: the on-screen scale ran 2 → 200,000 across the distance range
before, and is a flat 2 after scaling `field` with `near`. The shipped cameras get away with the
constant only because their near plane never moves off 1.

**Zoom is proportional and rotation is on the arrow keys.** A fixed 25-units-a-notch is a shove up
close and imperceptible far out, which reads as a wall. `FreeCam` has no keyboard at all — its
rotation is a drag and nothing else — so `cam-left`/`cam-right`/`cam-in`/`cam-out`, the client's own
rebindable bindings, are wired up where a player would already look for them. Shift + middle-drag
still does `FreeCam`'s own rotate-and-elevate.

**`MapView.view` became one shared, settable knob** (`:fleet view N`). It was a per-view constant of 2
cuts, which is all a character view needs — but a camera that pulls back sees nothing but the edge of
it, and a member's dormant view must request at least as much ground as the anchor intends to draw of
it. One static is the only way those two stay in step.

**The pan is solved through the view's own projection, not through the camera's trigonometry.** Three
probes (`screenxf` of the centre and of one world unit along each axis) and a 2×2 inverse give the
world delta for a pixel delta. Rebuilding the `makepointed` maths by hand would have meant getting
four sign conventions right — the y-inversion, screen-y downward, the elevation foreshortening and
the azimuth — and being wrong in a way that only shows at some angles. This cannot drift out of
agreement with what is actually on screen, because it asks the screen.

### F7 — The merged view *(added after F4, at the maintainer's request)*

The plan said that when the characters are far apart, v1 degrades to a split view or the minimap.
Tried in practice, that is the wrong answer: the anchor streams neither the ground under the others
nor their gobs, so they simply **vanish**, and an RTS whose units disappear when they spread out is
not one. So the scene merges instead.

#### As built

A member's surroundings are rasterized into the **anchor's** scene, out of the member's own `MCache`
and `OCache`, under a single translation — F1's offset, which is precisely what makes two
login-relative frames addressable in one space. The member is still never drawn as a *view*: it has
no camera and no click-map. What is added are nodes in the anchor's tree that read another session's
data.

- `MapRaster` and `Gobs` each stopped hard-wiring `glob`: both take their source as a constructor
  argument, defaulting to this view's own. That is the whole core edit.
- `MapRaster.skipcut(cc)` and `Gobs.skipgob(ob)` — overridable vetoes, both false for a view's own
  world.
- `MapView.FleetTerrain` / `FleetGobs` / `FleetView`, driven from `fleettick()` beside
  `terrain.tick()`.
- `Fleet.views()` supplies the descriptors; `Fleet.Member.anchorpos()` answers where a character is
  in the anchor's frame **whether or not the anchor can see it**.

**The anchor wins every overlap.** The same ground drawn twice is z-fighting and the same tree drawn
twice is a double tree. A member yields every cut inside the anchor's own terrain area and every gob
whose id the anchor already has, and fills in only what is missing. Because gob ids are global, "does
the anchor already have this" is one lookup and no bookkeeping.

**The dedupe boundary moves, and nothing fires when it does.** A gob the anchor gains was never
removed from the member, so no callback reports it. The two sets are reconciled on a 0.25 s timer
rather than per frame: the boundary moves at walking pace, and this is a few hundred map lookups.

**The merged ground was scenery until it entered the click tree.** The pick pass tests against
`clmaptree` alone, and only the anchor's own `ClickMap` was in it — so an order landed only while the
anchor stood near the ground being clicked, which is precisely when the merge is not needed.
`FleetClickMap` puts each member's cuts in that tree under the same translation.

**And a click answers in the frame of the cut it hit.** `checkmapclick` derives the coordinate from
`cut.ul` — the source session's tile coords — so the scene translation above it does not enter the
arithmetic at all. `MapMesh.map` says which `MCache` produced the cut, so `Fleet.offsetfor(map)` brings
it back to the anchor's frame once, at the source, and everything downstream goes on speaking one
coordinate system. No tagging of the geometry was needed.

**A translation slot must declare `lockstate()`.** Introducing a `Location` above the fleet nodes
crashed the UI thread with *"locked state depends on non-locked state"*. The render tree locks a great
many slots — every map cut's click geometry, every composited gob — and a locked slot may not depend
on an ancestor whose state could still change, because its state is baked. Nothing above those trees
had ever defined a `Location`, so nothing had ever been that ancestor; adding one is what made the
declaration necessary. Reproduced in isolation first — a bare `RenderTree`, one translated slot, one
locked wrapping under it — which turned a plausible guess into three lines of output: no translation
passes, unlocked translation throws, locked translation passes.

**A free camera must never raise `Loading`.** `MapView.draw` turns a camera `Loading` into a black
screen and *"Waiting for map data..."*, which is exactly right for a camera bolted to a character — it
cannot be anywhere the player has not been. A free one can, and routinely is: panned over a merged
patch, the anchor has no map there at all and never will. `FleetCam.camcc()` now asks whichever
session does have that ground (`Fleet.groundz`) and keeps the last height rather than the whole view.

**The gap between two patches is not a seam, it is unrequested ground.** `Area.contains` is half-open
and agrees with its own iterator, and the cut offset divides exactly (a grid is 100 tiles, a cut is
25), so no cut is lost between the anchor's area and a member's — the union is exact. What is between
them is ground *nobody asked the server for*. A member's patch is drawn one ring wider than a
character view, which is free (it already requested that much), and past that the only cure is
`:fleet view N`: there is no rendering setting that draws ground the client does not have.

**Selection had to stop asking the anchor's `OCache`.** It was the whole basis of F3 — and it breaks
exactly when the merge starts to matter, because the character being rendered is one the anchor
cannot see. `anchorpos()` prefers the anchor's own view and falls back to the member's, translated.
A unit that cannot be clicked the moment it walks out of range is a unit you cannot call back.

### F5 — The HUD, and switching anchor

v1: the anchor's HUD **is** the HUD. Tab, or a click on a portrait, switches which UI is drawn and
which receives input.

**The cost to measure here:** on a switch, the new anchor's `MapView` enters a render tree and rebuilds
its terrain meshes (fact 2 cuts both ways) — a visible hitch. If it is bad, keep the previous anchor's
tree warm for a few seconds.

Pop-out windows from a non-anchor session's widget tree are v2. Drawing them is easy; routing input to
them per-widget is not.

#### As built

- `UILoop.drawn()` / `drawn(UI)` — the drawn UI split from `ui`, the main runner's. Two fields and not
  one because `Client.Main` owns `ui` and replaces and destroys it as the runner chain advances; that
  must never be able to destroy a member's UI, nor be confused by one being on screen.
- Whatever is not drawn is ticked as a background session — including the **main** one, which nothing
  else would tick, since `Fleet` owns its members and the main session is not among them.
- `MapView.dormant(boolean)` is now reversible: the scene is torn out and rebuilt rather than hidden,
  because "in a render tree" is exactly what decides whether terrain is meshed at all.
- `Fleet.anchor(member)`, `Fleet.next()`, Tab (`rts-next-anchor`), `:fleet anchor USER|main`.

**The anchor stopped being the main session, and that broke every "the anchor's own character" path
at once.** Up to F4 those were the same thing and each lookup could special-case it; the moment a
member can hold the screen they are different, and the special case becomes a character that cannot be
seen, selected or ordered from the instant you tab away from it. Replaced with `Fleet.Sess` and
`sessions()`: one uniform list in which the anchor is simply the session whose offset is zero. `views()`,
`offsetfor()`, `orderunit()` and `Control.units()` all collapsed onto it, and each got shorter.

**The main session needed an offset of its own** — the machinery that existed only for members. The
grid intersection came out of `Member.tickoffset` into a shared `findoffgc()`.

**The camera is per `MapView`, so there is no one camera to carry across.** Each session has its own;
`Control.recam()` installs a `FleetCam` on whichever view is now on screen and remembers what it had,
keyed by view rather than by a single field.

**The addon engine deliberately does not move.** Rebinding it is `AddonManager.init` — a full teardown
and reload of every addon — which is not what a key press should do. It stays bound to the main
session's view, so addons keep observing that session while you look at another. That is a real
limitation and it is exactly what F6 exists to remove.

### F6 — What this breaks

- **`AddonManager` is all-static and single-session** (`static volatile UI ui`, `static volatile
  MapView view`). For the spike, bind it to the anchor and re-bind on every anchor switch — cheapest,
  and addons keep working. Per-session addon state is a later project, and it is a large one.
- Two `GameUI`s save window positions to the same prefs keys and fight over them. Namespace by session.
- One shared `Audio.Root`: every session plays. Mute the non-anchors.

#### As built

- **Audio.** `ActAudio.RootChannel.mute(boolean)` scales the channel's `VolAdjust` without touching
  `volume` or its pref — a session going quiet because nobody is looking at it must not be mistaken
  for the user turning the sound down, and must not survive into the next launch. Applied only on a
  change of anchor; nothing else can alter the answer.
- **Prefs.** `GameUI.onscreen()` gates all three geometry writes (`savewndpos`, `makewndc`,
  `wndc-misc`). The conflict was never that the sessions want different layouts — it is that a session
  nobody has touched writes back the positions it loaded, undoing what the user just did in the one
  they were looking at. The session on screen owns the layout; the rest keep their geometry and say
  nothing about it. Single-session behaviour is untouched.
- **Addons.** The engine follows the session on screen.

**"Follows" can only mean `AddonManager.init`.** It is a single-session static hub, and that call is
the only rebind that exists: it tears the addons down, resets every per-session cache naming the old
session's widgets, gobs and markers, and loads them again — the same path a relog takes. F5 left it
behind instead, and that is worse in a way you can see: an addon's windows live on *its* session's
root, so they vanish from the screen the moment you tab, while the addon goes on acting on a character
you are not looking at. The cost is a full addon reload per switch, and it is **reported, not hidden**
— if that number is bad, the fix is per-session addon state, which is its own project.

**Deferred to the tick, not done in `anchor()`.** That runs inside the drawn UI's monitor, and `init`
takes the AddonManager lock and then the target UI's; deferring means no two of those three are ever
held at once. It also coalesces a burst of tabbing into one rebind.

**A dead session must not keep the screen.** A member holding the anchor can vanish at any moment, and
its runner unwinds on its own thread where touching render trees would be wrong. It leaves a corpse,
and `reclaim()` notices on the UI thread on the next frame and hands the screen back to the main
session.

## Open decisions

- **Authentication for extra accounts.** F0 uses saved tokens only. A real "add account" flow needs a
  login dialog that does not own the whole UI the way `Bootstrap` does.
- **Tag.** `// rts:` beside the existing `// addon:`, so the two forks of core edits stay separable.
- **A Lua surface for the fleet.** Deliberately absent from v1 — the fleet lives in Java. Once F0–F4
  stand, whether addons can drive the fleet is a real design question (it collides with the "a section
  is a per-addon singleton bound to one session" grammar), and it should be decided with the working
  thing in hand, not before.

## Risks, in the order they can kill it

1. **CPU/memory per extra session.** Measured in F0, not guessed. Everything after it assumes the
   answer was "affordable for four or five".
2. **Background `MapView` meshing terrain anyway.** Would multiply memory by the number of accounts.
   Same phase, same measurement.
3. **`clickargs` carrying coordinates** that need translating (F2). Small, but it silently sends
   characters to the wrong place if missed.
4. **Anchor-switch hitch** (F5). Cosmetic, but it is the difference between a toy and a tool.

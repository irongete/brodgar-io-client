# Learnings — Ghosts / virtual entities

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **V1 (world ghosts):** a **client-only `Gob`** is the whole trick. `new Gob(glob, rc)` passes `id = -1`, and
  `Gob` sets `virtual = true` for any `id < 0` ([Gob.java:437](src/haven/Gob.java:437)) → it is **never in
  `OCache`**, so it is invisible to every read API and to the server (N1/N2 hold **by construction**, not by a
  gate). The engine's own **placement preview `MapView.Plob`** ([:1779](src/haven/MapView.java:1779)) is the exact
  template: `new Gob` + `setattr(new ResDrawable(...))` + `basic.add(gob.placed)` + `Gob.move` + `slot.remove()`.
- **`basic`/`Gob.placed` are BOTH public** (`PView.basic` is `public final RenderTree.Slot`; `Gob.placed` is
  `public final`), so a zero-edit ghost was *possible* — but the spec/PLAN chose **option A** anyway: a centralized
  `// addon:` `MapView.addClientGob`/`removeClientGob` seam. The value isn't access, it's **encapsulation** — the
  addon layer never couples to "scene nodes go through `gob.placed` into `basic`", and V-series changes (a ghost
  scene layer, shadow exclusion) centralize in one place. D-011: invasiveness that clearly enables the feature.
- **`ResDrawable`'s ctor calls `res.get()`** → throws `Loading` until the resource is cached. So building a ghost
  on the UI thread would blow up on a cold prop. The fix is the **`glob.loader.defer` idiom** (Plob + `hafen.sound.
  play`): defer the build to a loader thread; `Loading` **re-runs** the deferred task when the resource lands; a
  non-`Loading` error means a bad name (report + abandon, don't re-run forever). The handle is returned immediately
  and works while the visual streams in — every `hafen.*` "create a visual from a resource name" call should do this.
- **RenderTree slot mutation is thread-safe from any thread** — `Slot.add`/`remove` take the tree's own lock
  ([RenderTree.java:472](src/haven/render/RenderTree.java:472)), which is why the engine's `Gobs`/`Plob` add slots
  on **loader** threads. So a deferred-create `addClientGob` (loader thread) + a UI-thread `removeClientGob` are
  both fine. Build the heavy gob+drawable OUTSIDE the ghost's own lock; keep only the **atomic add-and-publish**
  inside it (so a concurrent `:destroy` either discards the un-added gob or removes the published slot — never leaks).
- **A client-only gob must be ctick'd + gtick'd by hand — the first in-game bug** ("nothing appears"). The render
  tree ticks `gob.placed` (a `TickList.Ticking`) → `Placed.autotick` recomputes the **position** when `rc`/`a`
  change (so `:move` needs no poll). BUT the **sprite** (`ResDrawable`) is ticked by **`OCache.ctick`** → `gob.
  ctick` → `spr.tick`, and drawn via `gob.gtick`; a client-only gob is **not in OCache**, so without explicit
  `ctick`/`gtick` the sprite never prepares/animates and **renders nothing**. That is precisely why MapView
  hand-ticks the placement `Plob` — `ob.ctick(dt)` in `tick()` ([:1730](src/haven/MapView.java:1730)),
  `placing.get().gtick(g.out)` in `draw()` ([:1651](src/haven/MapView.java:1651)). Fix: the seam makes MapView own a
  `clientGobs` list and `ctick`/`gtick` each ghost in those same two spots (error-isolated per gob). So ghosts need
  no *addon-side* poll, but they DO need the engine to tick them — "in the scene" ≠ "ticked". Whenever you add a
  non-OCache gob, ticking is your job.
- **Game resources load via `Resource.remote()`, not `local()` — the second in-game bug** (the resource silently
  failed). `Resource.local()` = only the client jar (HUD/sfx, bundled). `Resource.remote()` = the game/server pool
  (terobjs, gobs, downloaded + preload) **with `local()` as a fallback** — the pool the engine uses for gob
  drawables (Session/Music/Widget/ISBox). A terobj like `gfx/terobjs/arch/logcabin` is a **server** resource, so
  `local().load` never finds it (→ non-`Loading` error → the create abandons, no prop). Use **`remote()`** for
  anything a game object would draw; `remote()` still covers client resources via its fallback, so it is the safe
  default for a "resource by name" API.
- **`Gob.removed()` is package-private** (only `Gob.dispose()` is public) — the addon layer can't call the Plob
  cleanup verbatim. `slot.remove()` (unrender) + `gob.dispose()` (free the sprite) is the reachable, correct pair;
  guard `slot.remove()` against `SlotRemoved` so a relog (which already tore down the whole scene) leaves teardown
  idempotent.
- **V2 (clickable ghosts): a virtual gob has NO pick surface by construction.** `Gob.GobState.apply` preps the
  `GobClick` (the `Clickable` the pick pass keys on) **only `if(!virtual)`** ([Gob.java:716](src/haven/Gob.java:716)),
  so the very thing that makes a ghost a ghost (virtual, id -1) also makes it unpickable. Do **NOT** fix this by
  flipping `virtual` — that would re-expose it to OCache-adjacent behaviour AND break any `cg.virtual` "is this a
  ghost" test (the `Click.hit` intercept relies on exactly that). The clean seam is the **`protected obstate(Pipe)`
  hook** `GobState.apply` calls for **every** gob regardless of `virtual`: a `Gob` subclass (`GhostGob`) overrides it
  to prep a `GobClick` when a `clickable` flag is set. `obstate` runs at render-**apply** time, so the flag is read
  live. Lesson: when a base-class behaviour is gated off for your case, look for the unconditional extension hook it
  still calls — don't fight the gate.
- **`Clicklist` (the gob pick list) decides membership at slot-ADD time and never re-checks it.** `Clicklist.add`
  ([MapView.java:1234](src/haven/MapView.java:1234)) runs `slot.state().get(Clickable.slot) == null → skip` **once**,
  when the mesh slot is added; a later ancestor-state change routes to `Clicklist.update`, which only touches
  *already-tracked* slots (a slot that GAINS a `Clickable` afterward is never promoted). The engine never needs a
  runtime toggle (a real gob is clickable for life; a virtual one never is), so there is no re-filter path. Practical
  consequence: to toggle a ghost's clickability live you must **remove + re-add** it to the scene (`removeClientGob`
  + `addClientGob`) so the filter re-runs on the fresh state — a state edit alone is silently ignored. A ghost
  created clickable needs no re-add: set the `GhostGob` flag **before** `addClientGob` and it is tracked from frame
  one. (Corollary: `Gob.GobState.equals` compares only `mods`, so `updstate()` — called every `ctick` — won't
  re-apply an obstate change either; the re-add is the reliable trigger.)
- **`RenderTree.Slot.cstate`/`ostate` REPLACE (they don't compose), and a `State` IS a `Pipe.Op`.** A slot has one
  `cstate` + one `ostate`, prepped in order into its `dstate` and inherited by children; `slot.ostate(x)` swaps the
  ostate wholesale (`chstate`). This is why I could NOT just add a `GobClick` to the ghost's placed-slot `cstate`
  after the fact and expect the click-list to notice (see above) — and why re-add, not state-poke, is the toggle.
- **`GhostClicked` is owner-scoped, not a global `fire`.** Every other bus event is about **shared server state**
  and broadcasts to all addons; `GhostClicked` carries the ghost **handle**, which is a private owner-only capability
  (it can move/destroy the ghost). Broadcasting it would hand addon B a live handle to addon A's ghost. So it is
  `fireTo(owner, …)` only — matching the per-ghost `onClick`. Lesson: an event whose payload is a *capability* (a
  handle, not data) should be delivered only to the owner; reserve the global bus for shared-world *data* events.
- **The `Click.hit` intercept is the mirror of the L2 action hook, done in-place.** Ghost clicks are consumed at the
  same `MapView.Click.hit` choke point the voice feature hooks ([:2066](src/haven/MapView.java:2066)) — before
  `wdgmsg("click", …)`. The `cg.virtual` pre-check is a near-zero fast path (real gobs are non-virtual → the addon
  layer is never asked about an ordinary click). A clickable ghost consumes **unconditionally** (client-only ⇒ a
  "click" send would be a bogus id -1 anyway); "pass through to the gob behind" would need a re-pick excluding the
  ghost, so it is deferred — an addon that wants a normal click keeps the ghost `:clickable(false)`.
- **V3 (ghost look): tint and translucency are ordinary render states in `obstate` — the engine already has the
  recipes.** A gob **tint** is a `MixColor(r,g,b,a)` (a = blend strength) — the exact state `GobHealth` composes for
  the red damage overlay ([GobHealth.java:48](src/haven/GobHealth.java:48)). A **translucent** object is
  `BaseColor(1,1,1,α)` (multiply the fragment alpha) + `FragColor.blend(new BlendMode())` (SRC_ALPHA/INV_SRC_ALPHA)
  + `States.maskdepth` (don't write depth) — the same three the client uses for its own translucent overlays
  (`gridmat` [MapView.java:872](src/haven/MapView.java:872), the drag-select rect [~:2277](src/haven/MapView.java:2277)).
  Both are `State`s (⇒ `Pipe.Op`s), so `obstate(Pipe buf)` just `buf.prep(...)`s them alongside V2's `GobClick`. No
  bespoke shader. Caveat: `maskdepth` means a translucent ghost does **not self-occlude** (x-ray/hologram look) — the
  standard trade for not writing depth; a solid-but-tinted prop is `tint` with `alpha = 1`.
- **A live `obstate` change (tint/alpha/clickable) needs the SAME remove+re-add as V2, for the SAME reason.**
  `Gob.GobState.equals` compares only the `SetupMod` mods, never `obstate`'s output, so `updated()`/`updstate()`
  won't re-apply a tint/alpha edit any more than it re-applied a clickable flip. One `refreshGhostScene` (remove +
  re-add the scene slot) serves all three; `:rotate`/`:move` are different — they change the `Placement` (position/
  facing), which `Placed.autotick` DOES diff and re-apply every frame, so no re-add. Lesson: know which state bag a
  change lands in — the placement (auto-diffed) or the gob obstate (not) — before deciding how to propagate it.
- **Swapping a drawable on a LIVE gob needs `synchronized(gob)` — the engine's own live res-swap shows the lock.**
  `ResDrawable.$cres.apply` (the server "change resource" delta) calls `g.setattr(new ResDrawable(...))` from a
  loader thread, and it runs inside `OCache.GobInfo.apply`'s `synchronized(gob)` ([OCache.java:384](src/haven/OCache.java:384)).
  `Gob.setattr` mutates the gob's plain-`ArrayList` `slots` (via `RUtils.multiadd/multirem`), which the ghost `ctick`
  path also touches under `synchronized(gob)` — so `:setRes` must take that same lock. Resolve the resource first on
  a loader thread (dodging `Loading`, like `new`), build the `ResDrawable` outside the lock, then `setattr` under it;
  a newer `:setRes` supersedes an older one via a `gh.res != rid` identity check so a slow swap can't clobber a fast
  one. (The create path does `setattr` BEFORE `addClientGob`, i.e. before the gob is live, so it needs no lock.)
- **V4 (grid-anchored persistence): `gridPos` had no inverse, and it needs a grid-by-STABLE-id lookup.** Saving a
  layout means storing `gridPos(x,y)` = `{gridId, off}` (the offset is grid-relative ⇒ session-invariant) and, on
  load, turning it back into a login-relative world coord. The forward path (`getgrid(gc)`) keys grids by
  session-local `gc`; there is **no** public "find the grid whose stable `id == X`" — and stable ids aren't spatially
  ordered, so you can't derive it from a neighbour. The one honest primitive is a scan of `MCache.grids` (package-
  private) for `g.id == id`, returning `g.ul·tilesz` — the SAME basis `gridPos` subtracts, so `fromGridPos(gridPos)`
  is exact by construction. It lives in the addon-owned `AddonWidgets` (one read, **zero `MCache` edit**), not the
  engine. Lesson: when you expose a lossy/relative encoder (`gridPos`), ship its decoder as a real primitive rather
  than making every addon re-derive it — and reach package-private engine state through the ONE `AddonWidgets`
  accessor, never reflection.
- **V4: re-resolve grid anchors WITH A RETRY — grids stream in after `OnEnterWorld`.** The per-char store is restored
  before `OnEnterWorld` (1e), but the **map grids around you are not loaded yet** at that instant, so `fromGridPos`
  returns nil for anchors whose grid hasn't arrived. Resolve what you can at login, then `timer.every(1, …)` retry the
  rest for ~15s (same pattern as `hello` re-reading char/inventory at +3s). Anchors still unresolved after that are
  simply out of range this login — **keep them in the store** (never drop), they resolve on a closer login. This is
  the general shape for any grid-anchored reload (markers, ghosts, waypoints).
- **V4: the ghost HANDLE can't be persisted — keep a runtime list + a serializable mirror.** A ghost handle carries
  Lua closures/Java refs, so a saved-variables table stores only `{res, a, anchor}` and the live handle stays in a
  parallel runtime list (`items`, the source of truth); `persist()` projects the serializable subset. The store JSON
  round-trips a nested **array-of-objects** fine (verified: writer recurses, reader rebuilds 1-based arrays + string-
  keyed objects), and a 64-bit `gridId` MUST stay a **string** end-to-end or a double loses its low digits — the same
  reason `gridPos` returns it as a string (§ grid-ids-are-strings).
- **V5a: screen→world is a GPU readback ⇒ ASYNC — don't fake a synchronous return.** The engine's only accurate
  pixel→terrain answer is `MapView.Maptest` (the click-location buffer readback the client's own placement uses); a
  synchronous `screenToWorld(sx,sy)→{x,y}` would fence the UI thread on the GPU. So the primitive takes a **callback**
  (`fn` called a frame later), exactly the one-frame lag a placement `Plob` has. The spec table's `{x,y}|nil` return
  was illustrative; the faithful (D-009) reuse is async. During a drag, **coalesce** (one raycast in flight per
  callback) so a fast drag doesn't queue a backlog of GPU passes — the same cadence the client's placement has.
- **V5a: `MouseMoveEvent` is BROADCAST to every *visible* widget; `MouseUpEvent` is not.** A drag needs every move
  (even off the origin) + the terminating up wherever it lands. `MouseMoveEvent.propagation` dispatches to ALL visible
  children (no cursor-area test), so a **visible** root child gets every move — but the invisible `AddonRoot` tick
  pump (`visible=false`) does **not**, so a grab needs its own visible (zero-size) widget. `MouseUpEvent` only reaches
  the widget under the cursor unless `ui.grabmouse` captures it (grabs are checked before normal dispatch; it also
  captures down/wheel → the MapView neither pans nor clicks during the drag = "camera stays put", zero core edit).
  This is precisely the draggable-`Window` pattern — reuse it, don't invent capture.
- **V5a: factor a client formula into a shared static rather than mirroring it (D-033/D-009).** Ghost snapping must
  feel identical to placing a building, so instead of copying `StdPlace`'s math into the bridge (drift risk), one
  `// addon:` refactor lifts it **verbatim** into `MapView.placeSnap(Coord2d,int)` that both `StdPlace` and
  `hafen.map.snapPlace` call — one code path, always honouring the live `:placegrid`. A `MapView.<clinit>` needs GL
  (`dolda.jglob.Loader`), so the static can't be jshell-loaded headless; verify the pure formula on `Coord2d` and rely
  on textual identity with the proven client code (in-game confirms end-to-end) — the A8/A10 "headless resource skip"
  pattern, now for a whole class's `<clinit>`.
- **V5b: the spec's "fallback" can be the better build — DRAWN (2D-projected) handles need no resource.** V2 gives 3D
  ghost picking, so the "primary" gizmo was 3D arrow-**mesh** ghosts — but that needs a small arrow **resource**, and
  none ships in the client jar. The spec's documented alternative (§4) — project the target + world-axis tips to
  screen, draw the handles, hit-test in screen space — sidesteps the resource entirely and is what the maintainer
  wanted (Unity look, `g:draw` triangles). When a "primary" path has a hidden asset dependency, the "fallback" is
  often the pragmatic default. The arrows still look 3D: re-project every frame so they foreshorten with the camera.
- **V5b: arm the drag on the REAL mousedown (via `hook.input`+`preventDefault`), not on a V2 ghost-click.** A ghost
  `onClick` fires from the async `Click.hit` pick a frame AFTER mousedown, so "click an arrow-ghost to start a drag"
  races the click's own mouseup (does the arming release reach the grab, or not? — timing-dependent). Detecting the
  press directly with a `mousedown` input hook, `preventDefault`, and arming `hook.grab` **on that press** makes it a
  clean press-drag-release: the grab captures the moves + the terminating up, and the FIRST up IS the drop. No
  moved-flag hacks. `preventDefault` on a hit also blocks the map click + camera + the ghost's own V2 select; on a
  MISS, don't preventDefault, so ordinary clicks still work.
- **V5b: mapview-local pixels line up across pick and projection — `hook.input` `ev.x/y`, `worldToScreen`, and the
  overlay all share it (fullscreen MapView).** So a screen-space hit-test of the cursor against `worldToScreen`ed
  handles is exact with no conversion. Only the DRAWING assumes the MapView is fullscreen at the origin (like the 2b
  crosshair overlay); hit-testing is unaffected either way (both sides are mapview-local).
- **V5b: axis-lock is trivial because `placeSnap` snaps each component independently.** For an X-drag, vary world-X and
  pin world-Y to the value captured at grab start (the axis line through the target); `snapPlace(w.x, anyY).x` is the
  snapped X regardless of Y. So "project ground point → keep one component → snap → move" is the whole constraint — no
  line-projection math needed.
- **V6: a `Location.scale` prepped in `Gob.obstate` scales IN PLACE, because obstate is the gob's CHILD render slot.**
  The `Placed` slot applies the world translate (`"gobx"`) + facing rotate (`"gob"`); `obstate` (the `GobState`) is a
  deeper child slot, and `Location` composes multiplicatively down the tree (`Transform.fin(p)=p.mul(xf)`), so the
  model matrix is **T·R·S** — scale first (local origin), then rotate, then translate. So the "least-native piece"
  (spec §10) is a **zero-core-edit** one-liner in our own `GhostGob`. Guarded by: a sprite that does
  `Location.goback("gobx")` (e.g. `resutil.CSprite`) resets past BOTH facing and scale — but such resources already
  ignore ghost rotation, so it's a consistent, documented caveat, not a regression. Confirm survivability by the fact
  that ROTATION reaches the mesh (a `goback("gob")` would drop scale but not rotation — and no sprite does that).
- **V6: rotation snapping is NOT symmetric with position snapping — `StdPlace.rotate` is wheel-RELATIVE, so there's no
  verbatim math to share.** Position got a shared `MapView.placeSnap` (extracted verbatim from `StdPlace.adjust`);
  the rotate handler *increments* the facing per wheel notch, so a gizmo ring (which needs an ABSOLUTE angle→grid
  snap) has no engine expression to factor out. Answer: a small pure `snapPlaceAngle` in the BRIDGE reading the
  **public** `plobagran` (the §4.1 zero-edit mirror) — `45°` coarse / `π/plobagran` fine. Don't force a `MapView`
  refactor when the client has no matching operation; the public setting is the shared contract.
- **V6: a gizmo rotate ring should be RELATIVE (anchor the swept angle at grab), not absolute "face the cursor".**
  Capture `offset = a₀ − rawAngle` on the first raycast and apply `snapAngle(rawAngle + offset)`, so grabbing the ring
  rotates BY the swept angle instead of snapping the object to point at where you grabbed. Same trick works for any
  drag that maps an absolute cursor angle to a delta.
- **V6: the "draw-on-top + constant-size handles" polish is FREE on the 2D-projected gizmo path.** A HUD overlay has no
  depth test (always on top), and fixed-px handles are literally fixed px — no camera-distance rescaling (that was the
  3D-native path's problem). Only the world-anchored axis shafts foreshorten, which is desirable.
- **(039.8) A client gob entering the scene resolves the TILE under it, so there is no "harmless" origin.**
  `MapView.addClientGob` is `basic.add(gob.placed)`, and `Gob.Placed.Placement`'s ctor calls
  `Gob.this.placer().getr(...)` + `getmapstate(oc)` — both of which read the map at the gob's coordinate.
  At world `(0, 0)` that raises `MCache.LoadingMap` *"Waiting for map data..."*, and because the add is
  synchronous in `makeSprite`/`makeObject` the `Loading` propagates **out of the Lua call**, killing the
  handler with a message that fingers the map system rather than the missing placement. Two consequences:
  a world entity cannot be built "bare and placed later" the way a widget or an overlay can (D-127), and
  any scene add over ground that is explored-but-not-streamed must catch `Loading` and retry, not throw.
  The **anchored** path is exempt and always was: `applyEntityFollow` sets a `FollowMoving` before the add,
  so `Placement` takes its `flwxf` branch and never touches the map — which is why every `gob:overlay()`
  suite stayed green while the free-standing one died.
- **(039.8) `Loading` is a `RuntimeException`, so the existing `catch(RuntimeException)` guards already
  swallow it.** `refreshEntityScene` and `showEntity` were therefore safe from the moment they were
  written; only the three `make*` creates had a bare `addClientGob`. Worth checking which of these guards
  is deliberate: swallowing a `Loading` silently means "not in the scene, and nobody will try again".
- **(042.12) `addClientGob`'s `Loading` is not always `MCache.LoadingMap` — a busy render backend also
  throws `Defer.NotDoneException`, and WHICH texture is a moving target, not a stable wait.** Porting
  `armPending`'s per-frame retry onto `Resolve` (M2) at first used the library default of 8
  re-registrations, tuned for a read blocked on ONE thing (an `Indir<Resource>`, a study slot's numbers).
  In-game the ghost still frequently failed to appear even over *loaded* ground, giving up with
  `Resolve: gave up after 8 retries on haven.Defer$NotDoneException: Finalizing texture in
  gfx/terobjs/woodpulp...` — a **different**, unrelated resource name on every retry (`woodpulp`,
  `sprucebough`, the ghost's own `logcabin`), never the same one twice. Root cause: `TexL.prepare`
  (`src/haven/TexL.java:142`) throws `Defer.NotDoneException` for whichever GL texture upload the render
  backend happens to be mid-decode on at that instant — a *system-busy* signal, not "wait for this one
  specific thing," so retrying on the SAME notify doesn't converge; the next retry just as often finds a
  DIFFERENT texture still finalizing. The pre-042.12 `armPending` had **no bound at all** (retried every
  tick, forever), so the 8-retry default was a real regression on a busy scene, not a pre-existing
  limitation. Fix: `Resolve.on` gained a `maxRetries` overload; `RenderApi`'s scene-add call uses 128
  instead of the default 8 — still a real retry-on-notify chain (every step fires because a specific
  texture's own decode completed, never a timer), just bounded high enough to ride out a heavy load.
  Lesson: before capping a `Resolve` retry chain at the library default, check whether the `Loading`
  thrown at that call site has ONE stable identity across retries or can legitimately rotate through many
  unrelated blockers — the two need very different bounds.

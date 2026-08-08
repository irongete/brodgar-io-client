# 044 — Tasks

> Caps lifted for this feature (maintainer directive). Each task is one session, self-contained,
> and verified by its own `:t044-<X>` suite per [`TESTING.md`](../TESTING.md) — which stands alone
> and re-asserts whatever its own proof rests on, however old.
>
> **[043-vr-namespace](../043-vr-namespace/) must be closed before 044.1 starts.**
>
> Java is touched throughout, so every verification is `ant hafen-client` → full client restart →
> run the command.

- [x] **044.1 — The surface: a widget drawn into a texture, standing in the world** ✅
  *Shipped*: `WidgetSurface` (invisible root under `ui.root` + offscreen `Texture2D`/`Pipe`/`GOut` + dirty flag
  + counter), `SurfaceQuad`, `LuaWidgetEntity`, the fourth collection on `VrApi`, `p:surfaces()`, and ONE
  `// addon:` line in `UILoop.display`. Both open questions decided: the pass is issued **before** `ui.draw(g)`
  in the same `Render` (same frame, never stale), and the material is `draw + clip + blend` (**D-192**, **D-191**).
  22/22 green; the `[manual]` line caught a v-flip (a render target's first row is its bottom) — fixed.
  `hafen.vr():widget()` as the fourth collection, Position anchor only, `"fixed"` facing only,
  owned widgets only. The offscreen `Texture2D` + `FragColor` `Pipe` + `GOut` (the
  `Streamer`/`HeadlessClient` recipe, narrowed to one subtree), the widget reparented into the
  surface and drawn there, and the `TexRender` quad in the entity's `Drawable` slot. Ships the
  **dirty flag** and the **profiling counter**, because this task's own claim is that it does not
  redraw every frame. Decides and states two things the plan left open: the blend-vs-clip material
  for a widget with a translucent background, and whether the offscreen pass runs before the world
  pass or one frame stale.
  *Suite proves*: `:add(w, p)` stands one and hands it back; its `Draw` still fires; the widget is
  gone from the flat UI's hit-testing (`hafen.ui():at()`) while still in the tree and `:exists()`;
  content unchanged over N ticks holds the upload counter at 1; changing a label bumps it by
  exactly 1; a non-widget first argument raises naming the widget builders; 026's text cache is
  confirmed still hit from the offscreen `GOut`. `[manual]`: confirm something is actually visible
  where it was put.

- [x] **044.2 — Both anchors, and the collection** ✅
  *Shipped*: ~15 lines of Java, all in `VrApi` — the collection's `addMember` drops 044.1's "a widget stands at
  a POINT" refusal and passes `an.tgt` on; `makeWidget` takes it, sets `followTgt` **before** `anchorRegister`
  and calls `applyEntityFollow` before the scene add. **Nothing else was needed**: the refused `:position(p)`,
  the `:offset(x, y, z)`, `anchorGone`'s death-with-the-gob, `hafen.vr():list()` and the read-only entry at the
  gob were all already the shared core's, so 043.2's "the anchor is an ARGUMENT" is what made the second form a
  parameter rather than a kind. 20/20 green (run twice) plus 6/6 on the despawn round.
  `:add(w, gob)` alongside `:add(w, p)`, on 043's anchor argument. The free-standing one carries
  `:position(p, a)`; the gob-anchored one does not, because its place is the gob's. Full
  collection verbs.
  *Suite proves*: `:add(w, gob)` stands on a game object and tracks it as it moves; `:add(w, p)`
  stands still and `:position(p, a)` moves it; `:list`/`:count`/`:find`/`:remove` answer; a
  gob-anchored one reports `:exists()` false once its gob is gone while a free one is untouched;
  `hafen.vr():list()` includes standing widgets alongside the other three kinds; a title-bar drag
  is inert on both.

- [x] **044.3 — `"camera"`: the mode the whole thing is for** ✅
  *Shipped*: `CameraFacing` (a `SprDrawable` whose `Gob.Placer` returns a **view-plane aligned** rotation — the
  render tree's own `Placed.autotick` applies it, so no tick loop, **D-193**), `LuaSurfaceBillboard` (the
  `"screen"` blit of a surface), `facing` + `visual(gob, mode)` lifted onto `LuaWorldEntity`, one shared
  `facingVerb`/`setEntityFacing`, and `:facing` on the widget handle with **all three** modes rather than the one
  asked for (**D-194** — a property on the shared core reaches every kind whole; 044.4 clicks all three). TWO
  `// addon:` one-liners: `MapView.camview()` and `Window.animating()`. 16/16 + 3/3 on the `off` round, three
  runs including one across a `:reload`; all three `[manual]` lines confirmed. **It also found and fixed a 044.1
  defect**: a `Window`'s fade is a private field, not a `Widget.Anim`, so `changing()` never saw it and a
  standing window could freeze on the fade's first near-transparent frame — which the quad's `TexClip` discards
  WHOLE, absent rather than faint. D-192's enumeration corrected in place.
  The third facing mode stops raising and becomes a real world quad turning in yaw and pitch — on
  **sprites as well as widgets**, since they share the entity core and special-casing one would be
  the larger change. This is the mode that gives real world size, perspective, occlusion and
  shrink-with-distance while staying square-on and readable.
  *Suite proves*: `:facing("camera")` reads back and no longer raises; it is world geometry — the
  suite asserts it is occluded and scales with distance where `"screen"` does not; the mode reads
  back on a sprite too; `:rotate` is stored-but-unused in `"camera"` and `"screen"` and honoured
  in `"fixed"`. `[manual]`: rotate the camera and confirm `"camera"` stays square-on while
  `"fixed"` does not, and walk away and back to see both world modes shrink while `"screen"` does
  not.

- [x] **044.4 — Input: clicks land where they look like they land** ✅
  *Shipped*: `SurfaceInput` (the corner homography, the front-to-back resolution, the four entries) and
  `SurfaceDrawable` (the widget quad's `Drawable`, and a `PView.Render2D` that draws nothing and records the four
  projected corners). **The pick pass is NOT used** — it answers a frame later, and a press, its drag and its
  release must be one gesture dispatched inside the event the press arrived in (**D-195**); the cost, stated
  rather than hidden, is that input ignores terrain occlusion. A standing widget therefore **left the world pick
  entirely** (no `GobClick`), so a miss reaches the world beneath by construction and `widget:onClick`/
  `WidgetClicked` are refused — a widget answers a click as a widget (**D-196**), with `:clickable(b)` now
  meaning "does the panel take the pointer", default true. `WidgetSurface.parentpos` answers with a live origin
  so a widget that grabs the mouse is fed correct coordinates by `UI.PointerGrab` (**D-197**). Two new verbs,
  `hafen.vr():pointer(key, x, y [, a])` and `widget:screen(x, y)`, exact inverses off the one map. FIVE
  `// addon:` core lines: four `MapView` hooks and `SIWidget.redrawing()`. **It found a 044.1 defect**: a
  `Button` caches its rasterised face and `redraw()`s on press — invisible to the signature, so a standing button
  clicked, played its sfx and never visibly pressed; D-192's enumeration is corrected in place a second time.
  11/11 + 2/2, five rounds; two of the failures were the suite's own and both are `learnings/` entries (a held
  synthetic press orphans a `Button`'s grab client-wide; a bare `hafen.ui():widget()` paints nothing at all).
  The pick on a surface routed before it becomes `wdgmsg("click", …)`, and the corner-homography
  turning a screen point into widget-local pixels. Both anchors, all three facing modes,
  `"screen"` degenerating to a rectangle test.
  *Suite proves*: a synthetic click at a known point fires the widget's `MouseDown`/`MouseUp` with
  the expected widget-local `x, y` **asserted numerically**; `MouseMove` and `Wheel` likewise; a
  button's callback runs; a click lands on both anchors in all three modes; a click that misses
  still reaches the world beneath it; `ev:preventDefault()` still cancels. Driven through the same
  internal dispatch a real click takes, so no click hardware is needed.

- [x] **044.5 — The surface is a real root: focus, keyboard, popups, tooltips** ✅
  *Shipped*: the answer is **split**. **Focus and the keyboard needed nothing** — a surface is a plain
  non-`focusctl` child of the root, so `Widget.setfocus` forwards straight past it and `FocusedKeyEvent` comes
  back down the same chain; `hasfocus` turned out to be the wrong read (it is false on nearly everything that is
  typing), so `widget:focused()` walks the path the client actually delivers along. **Popups WERE hard-wired**:
  `Widget.popuproot()` (new, `// addon:`) + `parentpos(popuproot())` replace `ui.root`/`rootpos()` at all three
  sites (`SDropBox`, `SListMenu`, `BuddyWnd`), identical for any non-standing widget (**D-198**). **So were the
  three per-frame point queries**: `UI.tooltip`/`getcurs`/`mousehover` ask `AddonManager.surfaceQuery` first,
  the panel answering in its own pixels off 044.4's corner map, with `mousehover` still walking the flat tree at
  `hovering=false` (**D-199**). `SurfaceInput.refreshOrigin` gained a RESTING origin (the projected top-left),
  which an in-surface popup's own mouse grab needs. Six `// addon:` core lines and one new `Widget` method — a
  seam, not the subsystem `tasks.md` feared. New: `widget:focused()`, `widget:tooltip()`/`:tooltip(s)`,
  `hafen.ui():tipAt(x, y)`. 9/9 + 2/2 manual. **A pre-existing 040.10 defect surfaced and is NOT fixed here**:
  `dropdown:size(w, h)` leaves the drop arrow outside the resized box, clipped and unhittable.
  The transparency proof, and the task that establishes whether popup/tooltip placement resolves
  against the nearest root or a fixed `ui.root` — adding the one `// addon:` seam if it is fixed.
  *Suite proves*: clicking a standing text entry gives it keyboard focus and typed text arrives;
  focus leaves correctly; a dropdown opens its list **inside** the surface, asserted by where the
  popup widget's root resolves; a tooltip resolves against the surface; hover state changes on a
  standing button. `[manual]`: hover and click a dropdown and confirm the list appears on the
  panel in the world rather than on the flat UI.

- [x] **044.6 — Native windows, going back, and `replace`** ✅
  *Shipped*: the provenance refusal in `standable` is **gone** — a client window stands, ungated, and the record is
  where it was, never whether it was shown, so D-070 holds with no branch and no second record and the window's
  **toggle stays the client's** (standing hides nothing, so `togglewnd`/`wndstate` were already right — **D-200**).
  `dispatchStandingRemoved` joins the 042.1 removal drain as its fifth consumer, so a standing widget ends with
  its content — which is how the `replace` composition resolves when the server destroys the window (**D-201**).
  `UiApi.stockPos` answers from the record, or `savewndpos` would have written the pinned origin to disk as the
  user's own inventory position. **The manual round found a real gap**: an item aimed at a standing container
  landed on the map beneath it, because a drop is dispatched from the dragged item's OWN parent and so reaches
  neither the surface nor 044.4's `MapView` intercept — three `// addon:` lines (`ItemDrag` ×2, `DropTarget`),
  answering *did a widget accept it* so the fall-through stays identical to the flat UI (**D-202**). 11/11 + 5/5,
  four rounds, every `[manual]` confirmed: items in and out of the standing inventory, Tab, the Equipment toggle
  over a stand-in, and `:reload` (its put-back verified numerically, the same pixel before and after).
  Borrowed widgets: the layer-that-restores, ungated, on the same footing as
  `:position`/`:visible`/`replace`. `:remove` as the undo, under one rule for both provenances —
  standing records where the widget was, removing puts it back. Then the composition with
  `w:replace(view)`, which is the maintainer's own scenario and therefore not an edge case.
  *Suite proves*: a native window stands, stays live and server-bound (still `:id()`, still
  reading its items); `:remove`, `:reload` and disable each return it under D-070's rule, in both
  the was-visible and was-hidden cases; an owned widget goes back to its default parent; a second
  addon standing the same window is refused naming the first; standing is confirmed **ungated**
  (an addon with no `actions` permission can do it); a replaced stand-in stands, is driven by the
  native toggle, and survives the server destroying the window it replaced. `[manual]`: open a
  real container window, stand it on its gob, use it, confirm it comes back.

- [x] **044.7 — Culling, and the ends of a surface** ✅
  *Shipped*: **there is no engine culling to reuse** — nothing in this client tests a gob against a frustum
  (geometry goes to the GPU and is clipped there, and `PView.ScreenList` calls every `Render2D` slot every
  frame), and it would be the wrong test anyway, since a panel's cost is a widget subtree plus a Lua `Draw`
  handler rather than its quad. **But the test was already being computed**: 044.4 projects the four corners
  every frame to resolve a click, and against the view they landed in those corners ARE the frustum test
  (**D-203**). So culling is two field reads — was the quad drawn recently (the `qframe` clock that already
  ends a stale panel's claim on the pointer, which covers `:hide()`, a gob out of the scene and a client with
  no map view) and did what it drew meet the view — asked FIRST in `needsDraw`, before the signature read, so
  what changes out of sight is still a change the first frame it is seen. One frame late by construction, which
  costs at most one upload as a panel swings into view and can never show a stale picture. `p:surfaces()` gains
  `culled`. **The five endings needed no new code** — 044.6 left them all wired — so the task asserts them.
  9/9 + 1/1 on the `off` round, run twice including across a `:reload`. Two suite defects on the way, both
  `learnings/testing-tooling.md`: a threshold picked first-past-the-post instead of by margin read red on a
  working feature, and a `[manual]` line claimed a despawn that cannot happen to a point-anchored panel.
  Off-screen surfaces stop drawing (reusing the engine's own culling); `Tick` keeps firing while
  culled and `Draw` does not. Every way a surface ends: removed, gob gone, server destroys the
  standing window, `:reload`, disable — for both anchors.
  *Suite proves*: with the anchor off-camera the upload counter stays flat while a `Tick` counter
  inside the widget keeps rising; `:remove` or losing the gob releases the surface (live-surface
  count drops); re-adding does not error; the server destroying a standing window takes it out of
  the collection cleanly and the handle reports `:exists()` false; `:reload` leaves the
  live-surface count at exactly 0. `[manual]`: aim the camera away from the anchor before running.

- [ ] **044.8 — Docs, the example addon, and the close**
  `api/vr/widgets.md` (new page), `api/vr/README.md`'s fourth collection row, `api/vr/sprites.md`
  (the `"camera"` mode reaches sprites), `api/client/profiling/counters.md` — **`p:surfaces()` is
  `live`, `culled`, `uploads`, `frames`, and 044.7's `culled` is the one nothing has documented
  yet** — both "API at a glance" tables, and `examples.md`. **Plus the three verbs 044.5 put on the `ui` pages, not the
  `vr` ones** — `widget:focused()` and `widget:tooltip()`/`:tooltip(s)` in `api/ui/widget.md`'s read
  and write tables, and `hafen.ui():tipAt(x, y)` in its lookup table and `api/ui/README.md`. Say on
  `widgets.md` that a popup opens **inside** the panel and is therefore clipped by it. **`widgets.md` and `sprites.md` must both carry 044.3's
  finding**: a camera-facing quad rises along the camera's *up* axis, so at a fully top-down
  camera it lies in the horizontal plane through its anchor — at ground level that is the
  terrain's own plane and it is lost in it; `<entity>:offset(x, y, z)` is the answer.
  The example addon is the maintainer's scenario and is
  **entirely event-driven**: watch for the smelter's window to appear → stand it on the smelter;
  the server closes it → swap in your own panel built from the contents you snapshotted; it
  appears again → swap back. No distance checks and no timers — only widget open and close, which
  is the shape 042 left the area in. Runs area `docs`'s §12 checklist and reports it.
  *Suite proves*: the example addon's surfaces exist, stand, and answer their reads; the docs
  sweep is reported as counts, not prose. `[manual]`: the whole thing in-game — it looks right,
  clicking feels like clicking a normal window, walking away and back swaps cleanly, and ore goes
  from the flat inventory into it and comes back out.

- [ ] **044.9 — A free entity stops drawing over ground that has unloaded**
  *Raised verifying 043.2, seen again verifying 044.7, and made its own task by maintainer
  directive.* Walk away from anything `hafen.vr()` placed at a **point** and the terrain cuts off
  while the thing keeps drawing, hanging over the void until it leaves render range; walk back and
  it is still there. Nothing is broken and nothing regressed: a client-only gob is in no `OCache`,
  so nothing removes it — that is the very premise an *anchored* entity's death rests on — and
  `Gob.Placed.autotick` catches the `Loading` a missing tile throws and keeps the previous
  placement rather than dropping the gob (`codebase/world-3d.md`, `learnings/ghosts.md`). It is a
  **cosmetic** gap and it is **not culling**: 044.7 stops the offscreen repaint, which leaves the
  quad drawing its last texture, so the void is untouched by it.
  **It lands on the shared entity core, so it reaches all four kinds** — ghost, sprite, object and
  standing widget — which is why it is not part of 044.7. The shape it wants is the one 043.4
  already built for `:visible`: per-entity desired state rather than a flag read at the draw,
  driven by the tile's own `Waitable` rather than by any sweep. Open question the task settles: is
  a free entity over unloaded ground **hidden** (and shown again when the tile returns) or
  **ended** — and whether that is a policy the addon chooses.
  *Suite proves*: with the tile under a free entity unloaded it is not drawn (the live-surface or
  draw counters answer, as they do in 044.7) and it comes back when the tile does; an entity over
  loaded ground is untouched; the reach across all four kinds. `[manual]`: walk away and back.

## Notes

- **044.1 is the gate.** If the offscreen `GOut` cannot draw a widget subtree cleanly, every later
  task changes shape — so it ships the smallest possible end-to-end path (one fixed-facing quad at
  a point, one owned widget, one texture) and nothing else.
- **044.5 is the risk.** It is the only task that may need a `haven` seam nobody has scoped yet.
  If popup rooting turns out to be hard-wired in a way one line cannot redirect, that finding
  lands in the task's `HANDOFF.md` and the maintainer decides before 044.6 starts.
- **044.3 reaches sprites, and that is not scope creep.** Sprites and widgets share the entity
  core, so `"camera"` either works for both or is special-cased for one; working for both is the
  smaller change.
- **044.2 could run before 044.1's counter work** if the gate task grows, but the order here is
  the natural one: prove one surface renders before giving it two anchors.

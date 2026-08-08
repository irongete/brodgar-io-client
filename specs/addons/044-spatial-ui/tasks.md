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

- [ ] **044.4 — Input: clicks land where they look like they land**
  The pick on a surface routed before it becomes `wdgmsg("click", …)`, and the corner-homography
  turning a screen point into widget-local pixels. Both anchors, all three facing modes,
  `"screen"` degenerating to a rectangle test.
  *Suite proves*: a synthetic click at a known point fires the widget's `MouseDown`/`MouseUp` with
  the expected widget-local `x, y` **asserted numerically**; `MouseMove` and `Wheel` likewise; a
  button's callback runs; a click lands on both anchors in all three modes; a click that misses
  still reaches the world beneath it; `ev:preventDefault()` still cancels. Driven through the same
  internal dispatch a real click takes, so no click hardware is needed.

- [ ] **044.5 — The surface is a real root: focus, keyboard, popups, tooltips**
  The transparency proof, and the task that establishes whether popup/tooltip placement resolves
  against the nearest root or a fixed `ui.root` — adding the one `// addon:` seam if it is fixed.
  *Suite proves*: clicking a standing text entry gives it keyboard focus and typed text arrives;
  focus leaves correctly; a dropdown opens its list **inside** the surface, asserted by where the
  popup widget's root resolves; a tooltip resolves against the surface; hover state changes on a
  standing button. `[manual]`: hover and click a dropdown and confirm the list appears on the
  panel in the world rather than on the flat UI.

- [ ] **044.6 — Native windows, going back, and `replace`**
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

- [ ] **044.7 — Culling, and the ends of a surface**
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
  (the `"camera"` mode reaches sprites), `api/client/profiling/counters.md`, both "API at a
  glance" tables, and `examples.md`. **`widgets.md` and `sprites.md` must both carry 044.3's
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

# 112 — Tasks

Order is load-bearing. The pump leaves the monitor **before** the check forbids a second one: while
`layerTick` still runs inside `synchronized(layer)`, writing a session's widget from `Update` — which
is most of what addons do — holds one monitor and takes another, so a check landing first would refuse
the API's ordinary path. From 112.2 onward every remaining violation is a message rather than a freeze,
and each later task removes one.

- [x] **112.1 — the engine step, and `widget:on("Update")` with it, leave the tree monitor.**
      `LayerRoot` is retired: its whole job was calling `AddonManager.layerTick` from inside
      `UI.tick()`'s `TickEvent` broadcast, which `UILoop.Frame.tick` runs under `synchronized(layer)` —
      so every addon's per-frame work began with a monitor already held. `Frame.tick` calls
      `layerTick(loop.layer)` directly, between `loop.dispatch(layer, ui)` and the `synchronized(layer)`
      block, and `layerTick` takes its own delta off `Utils.rtime()` rather than the widget tick's.
      `layerhot`, the hover and the resize stay inside their block untouched. `AddonWidget.tick` stops
      firing `"Update"`; the pump walks a per-addon list of the `"Update"` subscribers — maintained on
      subscribe and unsubscribe, so an unlistened surface stays as free as `widgetSubsOrNull` made it —
      and fires each with the frame's delta, in registration order, once per frame, with the
      `dead`/`pending` guards moved to the walk.
      *Its suite* declares a window of its own with `win:on("Update", fn)` and, inside that handler,
      writes both the window and a widget of the character's tree, asserting **both land in the same
      turn** — the pair that used to nest two monitors. From the same handler it reads `item:contents()`
      on a real item, which forces `GItem.info`'s build and the seam inside it, while a second surface
      is built: the recorded deadlock, asserted to *finish* rather than the run stopping. It counts its
      own `Update` against `hafen.event():on("Update")` over a timed second and asserts the two agree
      within a frame, so a per-widget `Update` that stopped firing — or fires twice — fails instead of
      passing quietly, and it asserts `dt` is positive and sums to about that second. It asserts a
      destroyed surface stops receiving `Update`.
      `[manual]`: the HUD keeps drawing and answering the mouse for a few seconds after the run — a pump
      that lost its frame is exactly what no assertion running inside that pump could report.

- [x] **112.2 — a second tree monitor is a refusal, not a wait.** `LuaWidget.monitor(Widget)` stops
      being a plain accessor. It is called immediately before every `synchronized(monitor(w))` in the
      package — 18 sites in `LuaWidget`, ~40 across it — so guarding it there changes no call site: it
      walks the live trees (`AddonManager.layer()` and each `SessionState.ui`), and where
      `Thread.holdsLock` says this thread already holds one that is **not** `w.ui`, it throws naming
      both trees, the seam holding the first, and the `Update` or timer that holds neither. The same
      tree is never refused — `synchronized` is reentrant and a verb re-entering its own tree is
      correct. `UNATTACHED` is not a tree and never refuses. A `monitorOf(UI)` overload takes the two
      sites that bypass the funnel today, `Gesture.write:427` and `Layout.sweep:632`.
      *Its suite* installs `win:on("Draw", fn)` on a surface of its own — a draw runs under that tree's
      monitor and a frame fires it unaided, so nothing has to be gestured — and inside it `pcall`s a
      write to a widget of the character's tree, reading the result back on a timer two frames later.
      It asserts the call failed, and that the message names **both** trees and the word `Update`, so a
      refusal that merely says "no" fails the check. It then asserts the three things that must *not*
      be refused: a write into the Draw handler's own tree from inside it, the same cross-tree write
      issued from `Update`, and a write to a surface built but not yet armed. What is refused is the
      nesting, never the crossing.

- [x] **112.3 — the two seams that ran Lua on a Loader thread queue and drain on the step.**
      `AddonManager.onWidgetEntered` and `onItemInfo` stop calling Lua where they are reached — inside
      `Widget.add0` under `synchronized(w.ui)`, and inside `GItem.info()`'s build, on whichever thread
      asked first. Each enqueues instead: a new `SessionState.enteredWidgets`, and a `GItem` queue, both
      drained on the pump, **entered before removed**, so `Added` can never follow `Removed` for a
      widget that came and went in one frame. `UiApi.onWidgetEntered`'s subtree walk and its per-widget
      `hasparent(u.root)` re-test move to the drain, where the answer is still given at the moment of
      handing over — the promise that seam exists to make. Two gotchas go onto `docs/client/widgets.md`.
      *Its suite* subscribes `s:ui():on("item", "Added", fn)` and, inside the handler, builds a window
      and writes a widget of that session — the exact arrow both dumps caught — asserting the handler
      ran and that neither call raised. The same for `item:on("Changed", fn)`. It asserts the widget
      handed over answers `:exists()` at that instant. It asserts the handler ran **on the step** rather
      than on the placing thread, by reading a flag the pump raises around its own drain — assumed
      otherwise, and the assumption is the whole defect. It records `Added` and `Removed` for one widget
      in a single list and asserts the order. It retries on a timer over a bounded window for a real
      item icon and scores over what it reached.
      `[manual]`: open a container and close it once — the `Removed` edge needs a widget only the server
      takes away.

- [ ] **112.4 — the session's own step leaves its tree's monitor, the way the layer's did.**
      `AddonRoot.tick` calls `AddonManager.tick(ui, dt)`, and that is a `TickEvent` callback: `UI.tick()`
      broadcasts it, and both drivers hold the tree while they do — `UILoop.Frame.tick`'s `synchronized(ui)`
      for the session on screen, `Sessions.tick`'s `synchronized(u)` for every background member. So
      `plan.md`'s family B is true of the **layer's** drains and false of every **session's**:
      `drainRemovedWidgets`, `drainOverlayEvents`, `drainChatEvents`, `drainBeltSet`, `drainTextRewrites`,
      `drainResizedWidgets`, `drainMarkerChanges`, `HttpApi.drainHttp`, `CharApi.refreshTreeAdapters` and
      `UiApi.flushItemWatchers` all begin with that session's monitor held — which is the whole of criterion
      3's "the drains". 112.3 makes the asymmetry an author can see: an `s:ui():on(sel, "Added")` handler
      may reach any tree and the `Removed` beside it meets a refusal, for the same line written twice.
      `AddonRoot` keeps `globtype` — the hotkey seam needs a widget in the tree — and loses `tick`:
      `Frame.tick` calls `AddonManager.tick(ui, dt)` **after** its `synchronized(ui)` block, so a drain
      reads geometry the frame has already settled, and `Sessions.tick` calls it for each member after that
      member's own block closes. Each tree's delta comes off `Utils.rtime()`, as `layerTick`'s does, and the
      session step raises `AddonManager.stepThread` too, so `hafen.client():stepping()` stays one answer.
      *Its suite* installs `s:ui():on(sel, "Removed", fn)` and `widget:on("Removed", fn)` on a widget of its
      own in the character's tree, destroys it, and from inside each handler builds a window and writes a
      second widget of that session — asserting both land, that neither raised, and that
      `hafen.client():stepping()` is true in both, which is what the drain's new address buys. It asserts the
      edge did not move in time: the widget is destroyed from an `Update` handler and its `Removed` is
      asserted to arrive within one frame, not later. It asserts an `HttpApi` callback and a
      `MessageAdded` reach another tree from the same freedom, the two other per-session drains an addon
      meets most.
      `[manual]`: the HUD keeps drawing and answering the mouse for a few seconds after the run — a session
      pump that lost its frame is what no assertion running inside that pump could report.

- [ ] **112.5 — the placement seam hands its adapters to the entry seam's drain.**
      `AddonManager.onWidgetPlaced` is a fourth site of `plan.md`'s family C and the table does not list it:
      `UI.AddWidget.run` calls it inside `synchronized(UI.this)` on a Loader thread, and
      `CharApi.dispatchPlaced` runs Lua from there — `MeterAdded`, `BuffAdded` and the study and equipment
      fires, each through `AddonManager.fireTo`. 112.3 took the selector half off this seam, because
      deferring the entry seam would otherwise have left this one the call that *fires*; the adapters were
      not made worse by that and so were left, and nothing else will move them. `dispatchPlaced` is reached
      from `UiApi.dispatchEntered`'s drain instead, so a `BuffAdded` handler holds no tree and may build a
      window. Two things follow and both are the task. The tap's guard widens: `enqueueEntered` records a
      widget when there are selector watches **or** tree adapters, or a client with no `s:ui():on` at all
      would stop seeing buffs. And the adapters are offered every widget that enters rather than only the
      server's own, which is more than they had — each already filters by type and dedups on its own cache,
      so a meter the client mints for itself is seen at last. `Layout.placed` and
      `dispatchWidgetSubsPlaced` stay where they are: neither runs Lua, which is what keeps that seam legal
      inside the block.
      *Its suite* subscribes `hafen.event():on("EquipChanged", fn)` and, from inside the handler, builds a
      window and writes a widget of the character's tree — asserting both land and that
      `hafen.client():stepping()` is true, which is the arrow this seam carries today. It registers **no**
      selector subscription of its own, so a pass is also the widened guard: the adapters fire for an addon
      that never asked to watch the tree. It retries on a timer over a bounded window and scores over what
      the run reached.
      `[manual]`: take one worn item off and put it back — an equipment change is the server's to make.

- [ ] **112.6 — an anchor that crosses trees applies one monitor at a time.** `Layout.apply(w, depth)`
      calls `applyDependents` inside `synchronized(LuaWidget.monitor(w))`, and that recurses into
      `apply(dep)`, taking a second tree's monitor with the first still held — in whichever direction
      the anchor points, so two anchors are enough to build both edges through the public API alone,
      with no Loader thread involved. `applyDependents` returns its list instead; `apply` closes its
      block, then applies each in sequence. `MAXDEPTH` and the `derived` snapshot under
      `synchronized(Layout.class)` are untouched.
      *Its suite* anchors a window of its own to a widget of the character's tree and a second surface
      the other way about, moves each target, and asserts every dependent re-derived to the place its
      anchor names, read back through `:position()`. It asserts neither write raised 112.2's refusal —
      a cascade that still nested would meet it, and that is what makes this an assertion rather than a
      hope. It builds a chain longer than `MAXDEPTH` and asserts the far end did not move, so the depth
      limit still ends what a cycle would not. It asserts a dependent whose target has been destroyed
      drops out of the cascade instead of throwing.

- [ ] **112.7 — the inbound stream decides before the monitor, and the outbound one is told why it
      cannot.** `AddonManager.onMessage` is hoisted above `UI.UiMessage.run`'s `synchronized(UI.this)`:
      the handler still answers before the widget applies, so `ev:preventDefault` and `ev:rewrite` are
      untouched, and only `dispatch(wdg, MessageEvent)` stays inside the block. `onWdgmsg` cannot be
      hoisted — input dispatch already holds the monitor when it is reached, which is precisely what its
      `Thread.holdsLock` test detects — so it stays in family A and a cross-tree reach from it meets
      112.2's refusal. That is the last site, and with it the invariant holds everywhere.
      *Its suite* subscribes `hafen.event():message():on("*", fn)` and, inside the handler, writes a
      window in the layer, asserting it lands — which it could not while that seam held a session's
      monitor, and which is therefore the whole proof of the hoist. It asserts the stream lost nothing:
      `ev:preventDefault()` still leaves the widget unchanged, and `ev:rewrite(t)` still reaches it. For
      the action stream it asserts a cross-tree write is refused and that the message names `Update`.
      The message half retries on a timer over a bounded window until a server update arrives.
      `[manual]`: click the ground once — nothing in Lua delivers an input event, so the action-stream
      half needs a real gesture.

- [ ] **112.8 — threading becomes a page, and it says which seam runs where.** A new
      `docs/addons/api/threading.md`: which seam runs on which thread and which of them hold a monitor,
      in the reader's vocabulary rather than the engine's; the one-monitor rule; what the refusal means
      and the two verbs that answer it; `hafen.client():stepping()`, which says which side of that rule
      the code you are in is on, and which lives on `api/client/README.md` because that is the section
      it hangs off; and the one honest remainder — the action stream can still run an addon's Lua beside
      the pump's, against a single `Globals`. `conventions.md`'s Threading section
      becomes a pointer to it, which also takes that page down toward its own ceiling. `custom.md` says
      `Update` fires from the step and `Draw` from the pass it paints in; `selectors.md`, `streams.md`,
      `runtime.md` and `guides/events-and-timers.md` each say where their own handler runs instead of
      promising "the UI thread". The `DOCUMENTATION.md` §11 checks close it: the link scan, `wc -l` on
      every page touched, and the derived grep from `spec.md` re-run against the result.
      *Its suite* `pcall`s every nesting the new page quotes and asserts the client's own message is the
      string the page prints, character for character, so the page cannot drift from the check that
      raises it. It asserts the timings the pages now state — that an `Added` and a `Changed` land on
      the step *after* their seam and not within it — and that each verb the page names as safe from a
      held monitor is in fact safe.

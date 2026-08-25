# 112 — Plan

## The lock graph as it stands

Every door into addon Lua, and the tree monitor its caller is holding when it opens. This table is
the feature: the fix is what each row has to become.

**Family A — holds its own tree's monitor, and must.** Dispatched by that tree's tick, draw or input,
each either paints into the pass that raised it or answers it.

| Site | What fires |
|---|---|
| `AddonWidget.tick:182` | `widget:on("Update", fn)` — **the one the dumps caught**; moves to B |
| `AddonWidget.draw:203` | `widget:on("Draw", fn)` — paints into this pass |
| `AddonWidget:229`, `:253` | `"Close"`, `"Drop"` (its return value consumes the drop) |
| `CGrid:120` | `"Cell"` |
| `Controls.fire:477` ← `CCheck`, `CDropdown`, `CEntry`, `CICheck`, `CList`, `CMenu`, `CRadio`, `CScrollbar`, `CSlider`, `CtlButton`, `CtlIButton` | `"Pressed"`, `"Changed"`, `"Submitted"`, `"Selected"` |
| `WidgetSubs:192`, `:215` | the native widget's own events |
| `LuaMouseGrab:65`, `:73` | `"Move"`, `"Up"` |
| `Gesture.fire:486` | the drag and resize gestures |
| `AddonPagina:177` | `"use"` on a menu entry |
| `AddonManager.dispatchAction:1894/1896` | the outbound action stream — `ev:preventDefault` answers the send |

**Family B — holds nothing (the engine step).** `AddonManager.fireTo:3243` (bus events, timers),
`WidgetSubs:249/339/431/435` (the `Removed` and `ItemAdded`/`ItemRemoved` drains), `HttpApi:484`.
Today it is *not* true that it holds nothing: `layerTick` is reached from `LayerRoot.tick`, inside
`UI.tick()`'s `TickEvent` broadcast, which `UILoop.Frame.tick` runs under `synchronized(layer)`.

**Family C — holds a session's monitor, on a thread that is not the frame's.** These are the two
arrows the dumps caught, plus the seam beside them.

| Site | Reached from |
|---|---|
| `AddonManager.onWidgetEntered:1545` | `Widget.add0`, inside `Widget.add`'s `synchronized(ui)` |
| `AddonManager.onItemInfo:2065` | inside `GItem.info()`'s build block, on whichever thread asked first |
| `AddonManager.onMessage:1948/1950` | `UI.UiMessage.run`, inside its `synchronized(UI.this)` |

**Family D — takes a tree monitor without going through `LuaWidget.monitor`.** `Gesture.write:427`
and `Layout.sweep:632` both write `synchronized(u)` directly. `AddonManager.awaitIdle:712/717` does
too, deliberately, one tree at a time on the shutdown path — that one is already correct and is left
exactly as it is.

## Approach

The client's rule exists (`docs/client/multi-session.md`) and `UILoop.Frame.tick` obeys it. Nothing
here is invented: the families above are brought to it, and the prose that guarded it becomes a check.

**1. The engine step leaves the monitor, and `Update` comes with it.** `LayerRoot` is retired — its
whole job was calling `AddonManager.layerTick` from inside the tick broadcast. `UILoop.Frame.tick`
calls `layerTick(loop.layer)` directly, between `loop.dispatch(layer, ui)` and the
`synchronized(layer)` block, and `layerTick` takes its own delta off `Utils.rtime()` rather than the
widget tick's. `layerhot`, the hover and the resize stay inside their block, untouched.

`widget:on("Update", fn)` moves with it: `AddonWidget.tick` stops firing, and the pump walks the
surfaces that have an `"Update"` subscription and fires each with the frame's delta. Order and
once-per-frame are preserved; the `dead`/`pending` guards move to the walk.

**2. The check is one method, and no call site changes.** Every tree-monitor acquisition in the layer
reads `synchronized(monitor(w))` — 18 sites in `LuaWidget`, ~40 across the package — and
`LuaWidget.monitor(Widget)` returns the object to lock, so it is called *immediately before* the
acquisition. Making it refuse there needs nothing else:

```java
static Object monitor(Widget w) {
    UI u = (w == null) ? null : w.ui;
    if(u == null) return UNATTACHED;          // no tree: never a monitor, never a refusal
    UI held = heldOther(u);                   // a LIVE tree, not u, this thread already holds
    if(held != null) throw new LuaError(nested(held, u, verb));
    return u;
}
```

`heldOther` walks `AddonManager.layer()` and each `SessionState.ui`, testing `Thread.holdsLock`. The
same tree is never refused — `synchronized` is reentrant and a verb re-entering its own tree is
correct — so the test is `t != u`. A handful of `holdsLock` calls (a JVM intrinsic) on a path about to
enter a monitor anyway.

The message names the fix, as 084 requires: which two trees, which seam holds the first, and the
`Update` or timer that holds neither. Family D is routed through a `monitorOf(UI)` overload so nothing
bypasses the guard.

**3. The two Loader-thread seams queue.** This is the pattern the page names (`Sessions.say` queues
and drains on the tick) and that `onWidgetRemoved` already uses. `onWidgetEntered` enqueues into a new
`SessionState.enteredWidgets`; `onItemInfo` into a `GItem` queue. Both drain on the pump, **entered
before removed**, so `Added` can never follow `Removed` for a widget that came and went in one frame.
`UiApi.onWidgetEntered`'s per-widget `hasparent(u.root)` re-test moves to the drain, where it still
answers at the moment of handing over — which is the promise that seam exists to make.

**4. The anchor cascade flattens.** `Layout.apply(w, depth)` calls `applyDependents` inside
`synchronized(LuaWidget.monitor(w))`, and that recurses into `apply(dep)`, taking a second tree's
monitor with the first still held — in whichever direction the anchor points, so an addon can build
both edges through the public API alone. `applyDependents` returns its list; `apply` closes its block,
then applies each in sequence. `MAXDEPTH` and the `derived` snapshot under `synchronized(Layout.class)`
are untouched.

**5. The inbound stream is hoisted; the outbound one is told why it cannot be.**
`AddonManager.onMessage` moves above `UI.UiMessage.run`'s `synchronized(UI.this)`: the handler still
answers before the widget applies, so `ev:preventDefault` and `ev:rewrite` are unchanged, and only
`dispatch(wdg, MessageEvent)` stays inside. `onWdgmsg` cannot be hoisted — input dispatch already
holds the monitor when it is reached, which is exactly what its `Thread.holdsLock` test detects — so it
stays in family A, and a cross-tree reach from it meets the check.

## Files to create / modify

| File | What |
|---|---|
| `src/haven/UILoop.java` | `Frame.tick` calls `layerTick` between dispatch and the two blocks (core edit, `// addon:`) |
| `src/haven/UI.java` | `onMessage` hoisted out of `UiMessage.run`'s block (core edit) |
| `src/io/brodgar/addon/LayerRoot.java` | retired |
| `src/io/brodgar/addon/AddonManager.java` | `layerTick` takes its own delta and walks the `Update` surfaces; the two queues and their drains; `onWidgetEntered`/`onItemInfo` stop calling Lua; `SessionState.enteredWidgets` |
| `src/io/brodgar/addon/AddonWidget.java` | `tick` stops firing `"Update"` |
| `src/io/brodgar/addon/LuaWidget.java` | `monitor(Widget)` becomes the guarded acquisition; `monitorOf(UI)` for family D |
| `src/io/brodgar/addon/UiApi.java` | `onWidgetEntered`'s subtree walk moves to the drain |
| `src/io/brodgar/addon/Layout.java` | `applyDependents` returns; `apply` iterates outside the block; `sweep` through `monitorOf` |
| `src/io/brodgar/addon/Gesture.java` | `write` through `monitorOf` |
| `docs/addons/api/threading.md` | **new** — which seam runs where, the one-monitor rule, the refusal, the remainder |
| `docs/addons/api/conventions.md` | its Threading section becomes a pointer |
| `docs/addons/api/ui/custom.md` | `Update` fires from the step, `Draw` from the pass |
| `docs/addons/api/ui/selectors.md`, `api/event/streams.md`, `runtime.md`, `guides/events-and-timers.md` | where each handler runs |
| `docs/client/widgets.md` | two gotchas: `add0` fires `onWidgetEntered` **under `synchronized(w.ui)`**, and `GItem.info()` builds once per arrival/revision and fires our seam inside that build |

## Risks & gotchas

- **`Widget.add` holds `synchronized(ui)` around `add0`.** `widgets.md:16` names the seam and not the
  monitor, which is the fact the whole defect turns on — hence the gotcha, and hence the seam must
  queue rather than merely be reordered.
- **`OCache.add` fires `ChangeCallback.added` under the *gob's* monitor, not the cache's**
  (`OCache.java:84-91`). Our callback only enqueues and is already correct; it must stay that way.
- **`UILoop.Frame.tick` settles `layerhot` inside `synchronized(layer)`** and `display()` reads it the
  same frame. Only the pump moves.
- **Walking for `"Update"` subscribers changes a cost profile.** `widgetSubsOrNull` was chosen so an
  unlistened surface costs one map lookup per tick; a per-frame walk of every widget-subs entry is not
  that. Keep a per-addon list of just the `"Update"` subscribers, maintained on subscribe and
  unsubscribe, so the unlistened case stays free.
- **`AddonManager.boot()` is `static synchronized`** and reached from `layerTick`. With the pump
  outside the layer's monitor it is taken with no tree held — safer, not worse. `sessionArrived`'s
  deliberate non-`synchronized` stays exactly as it is, for the reason its own javadoc gives.
- **The `Sandbox` watchdog counts Lua instructions only**, so a verb blocking in Java is invisible to
  it. The check's refusal must not read like a watchdog abort.
- **`docs/addons/api/conventions.md` is 349 lines**, already over the 300-line ceiling before this
  feature. Moving Threading out makes it smaller; the remainder is reported at the close.

## Discarded alternatives

- **All addon Lua on one thread, never under any tree monitor** — a `Draw` handler paints into the
  pass that dispatched it, and `"Drop"`, `"Pressed"` and `ev:preventDefault` answer the dispatch that
  raised them. Freeing them means deferring an answer that has to be given now.
- **A total order `layer < anchor < member`, leaving the pump under the layer's monitor** — the anchor
  is mutable, so that is not a stable order: the tree that was outermost becomes the inner one at a
  session switch, and two sessions still build both edges.
- **A per-addon Lua lock, closing the concurrent-Lua race with it** — the action stream is reached with
  a tree monitor already held, so the lock would be taken beneath one and would rebuild the cycle.
- **One global lock standing in for every tree** — one lock cannot deadlock against itself, but
  `synchronized(ui)` is written throughout upstream `haven` (`Widget.add`, `UI.tick`, `UILoop.Frame`),
  so the layer would be holding a lock the client never takes and protecting nothing.
- **A timed acquisition that gives up and reports instead of waiting** — `synchronized` has no timed
  form, and a `ReentrantLock` would mean replacing every `synchronized(ui)` in upstream.
- **Queue `UiApi.attach` alone, so a Loader-thread build defers** — any verb touching a layer widget
  from that seam carries the same edge, not only the builder.
- **Keep `onWidgetEntered` synchronous and re-check the tree at the drain** — it is the monitor it
  fires under that is the defect, not the moment it fires.
- **Move the pump into `UILoop.run`, outside `Frame` altogether** — the frame owns the delta, the
  resize and `layerhot`; outside it, all three have to be handed over.
- **Refuse every cross-tree reach, the pump's included** — reaching a session's widgets from the pump
  is what the API is *for*; the pump is precisely where one monitor may be taken.
- **Leave it, and tell addon authors not to nest two trees** — that is what the two javadocs at
  `AddonManager.java:1435` and `:1505` already do, and both of them went stale.

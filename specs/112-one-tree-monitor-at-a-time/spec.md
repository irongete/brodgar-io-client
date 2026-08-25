# 112 — One tree monitor at a time

## What & why

The addon layer deadlocks the client on an ordinary login. Three thread dumps off two separate
freezes — one with a second session up, one with a single character and nothing done — show the same
ABBA between two `haven.UI` monitors, the addon **layer**'s and a **session**'s:

- the **UI thread** holds the LAYER's monitor (`UILoop$Frame.tick`), runs addon Lua from
  `AddonWidget.tick` — a `widget:on("Update", fn)` handler on a surface of the addon's own — which
  reads item info (`item:contents()` → `LuaContents.block` → `LuaItem.info` → `GItem.info`). That
  build fires the item-info seam into a second addon's `item:on("Changed")` handler, which writes a
  widget of the SESSION's tree (`widget:size`, `widget:visible` → `synchronized(monitor(w))`) → waits
  on the session's monitor;
- a **Loader thread** holds that SESSION's monitor (`UI$AddWidget.run` → `Inventory.addchild` →
  `Widget.add0`), runs addon Lua through the widget-entry seam (`UiApi.onWidgetEntered` →
  `offerPlaced` → `record`), and that `s:ui():on("item", "Added", fn)` handler builds a surface,
  which attaches to the layer's root (`UiApi.attach` → `u.root.add`) → waits on the layer's monitor.

Both dumps name the same two addons and a different verb each time (`:visible` and `:holds` in one,
`:size` and `:text` in the other), so it is the shape and not a call site.

**The rule this breaks is the client's own, and it is already written down.**
`docs/client/multi-session.md`: *"The tick never holds two UI monitors at once, the addon layer's
included … that direction, anchor then member, is the only one anything may take."* `UILoop.Frame.tick`
obeys it — the layer's block, then the session's, never nested, with a comment saying why. The addon
layer does not. The pattern that fixes it is written on the same page: `Sessions.say` queues its text
and drains it on the tick, and our own `onWidgetRemoved` already works that way.

**Why prose was not enough.** Both offending seams carry a javadoc arguing they are safe
(`AddonManager.java:1505`: *"the Lua raised here never races other Lua"*; `:1435`: *"the handler Lua
cannot race any other Lua"*). Both reason about a client with one tree. The layer has had more than
one since 074, and nothing re-read those comments. So this feature does not only move the sites — it
replaces the promise with a check that refuses, at the line, the first time anything nests two again.

## What the invariant actually is

Not *"addon Lua never holds a tree monitor"* — most of the API cannot honour that. A `Draw` handler
paints during the draw pass; a control's `"Pressed"`, a `"Drop"` and a mouse-grab `"Move"` answer the
input that dispatched them. Each runs under exactly one tree's monitor, permanently and correctly.

The invariant is the narrower one the client already states: **while one tree monitor is held, a
second is never taken.** Everything follows from it — what needs to reach across trees must hold
none, and what legitimately holds one may reach only into the tree it already holds.

## Acceptance criteria

1. **A second tree monitor is refused, not awaited.** Reaching a widget of another tree from inside a
   seam that already holds one raises an error naming both trees, the seam, and the `Update` or timer
   that does it safely. It is `pcall`-able, so a suite asserts the failure *and* its text.
2. **What is refused is the nesting, never the crossing.** The same cross-tree write from a handler
   holding nothing lands, and a write into the handler's *own* tree always lands.
3. **The engine step holds no tree monitor.** Bus events, timers, the drains and
   `widget:on("Update", fn)` all run with none held, so an addon's ordinary per-frame work reaches any
   tree.
4. **The widget-entry seam delivers on the step.** `s:ui():on(sel, "Added", fn)` fires from the pump,
   not from the thread that placed the widget; the handler may build a window and write any tree; the
   widget handed over is in the tree at the moment it is handed over; and one widget's `Added` never
   arrives after its `Removed`.
5. **The item-info seam delivers on the step.** `item:on("Changed", fn)` fires from the pump and its
   handler may write any tree.
6. **The inbound message stream holds no tree monitor**, while still deciding before the widget
   applies: `ev:preventDefault()` still swallows and `ev:rewrite(t)` still reaches the widget.
7. **An anchor cascade never nests two trees.** A widget anchored across the layer/session boundary
   re-derives in both directions with one monitor held at a time, and a chain past `MAXDEPTH` still
   stops.
8. **The contract says what is true.** The pages state which seam runs on which thread, which of them
   hold a monitor, what the refusal means, and the one remainder below.

## Out of scope

- **The concurrent-Lua race.** `AddonManager.callLua` holds no lock, so the outbound action stream
  (`hafen.event():action()`) can run one addon's Lua on the render or a Loader thread while the pump
  runs it on the UI thread, against a single non-thread-safe LuaJ `Globals`. It **cannot** be closed
  with a lock: that seam is reached from input dispatch with a tree monitor already held, so any Lua
  lock would be taken beneath one and would rebuild the cycle this feature removes. Closing it needs
  the seam to stop answering synchronously, which is what makes it cancellable at all. This feature
  **documents** it (criterion 8) and leaves the half inside coherent and whole. The other half is:
  *the cancellable seams stop running Lua on the applying thread*.
- **Freeing family A.** `Draw`, `Drop`, `Close`, the control notifications, gestures and mouse grabs
  keep running under their own tree's monitor, because each either paints into the pass that
  dispatched it or answers it. They are correct as they are; criterion 1 is what makes them safe.
- **Hangs that are not deadlocks.** A verb blocking in Java is invisible to the `Sandbox` instruction
  watchdog and is a different failure with a different fix.
- **`docs/client/multi-session.md`'s ceiling and its `io.brodgar` content**, both already on
  `ROADMAP.md` (filed: 066). The page states upstream's rule correctly; this feature reads it and does
  not write it.
- **`docs/addons/api/conventions.md`'s 349 lines.** It is over the 300-line ceiling *before* this
  feature; moving Threading out makes it smaller, and the remainder is past this boundary and is
  reported at the close.

## Docs impact

Pages written: **`docs/addons/api/threading.md` (new)**, `docs/addons/api/conventions.md`,
`docs/addons/api/ui/selectors.md`, `docs/addons/api/ui/custom.md`, `docs/addons/api/ui/items.md`,
`docs/addons/api/event/streams.md`, `docs/addons/api/client/README.md`, `docs/addons/runtime.md`,
`docs/addons/guides/events-and-timers.md`, `docs/client/widgets.md` and the
**`docs/client/widget-introspection.md` (new)** its own ceiling splits out of it.

Derived impact set — the prose elsewhere this feature makes false. Those claims are written in the
vocabulary of the *promise* ("the UI thread"), not of the fix, so a grep aimed at the new syntax
cannot see them:

```sh
grep -rn "only Lua running\|never need locks\|one UI thread\|UI thread" docs/addons/
```

→ `api/conventions.md:312-316` (*"yours is the only Lua running … You never need locks"*, and a
"one exception" that is a whole family), `runtime.md:126` (*"the client's one UI thread"*),
`api/console.md:49`, `api/event/bus/lifecycle.md:20`, `api/event/bus/world.md:58`,
`guides/events-and-timers.md:74`, `api/timer.md:6`, `api/http.md:70-71`,
`api/client/profiling/README.md:75`. Each is checked against the new page: the ones that are merely
*true* (`http`, `timer`, `profiling`) are left alone, and the ones promising sole occupancy are
rewritten.

```sh
grep -rn "one lock direction\|two UI monitors\|lock direction" docs/client/
```

→ `multi-session.md:103`, `boot-and-loop.md:110`, `console.md:20` — all three accurate, none edited.

## Context files

- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 5
- `src/io/brodgar/addon/AddonWidget.java` — 1, 6
- `src/io/brodgar/addon/AddonRoot.java` — 3 (a session's pump is a WIDGET, so its step holds that tree)
- `src/io/brodgar/session/Sessions.java` — 3 (`tick()` steps every background member under its own monitor)
- `src/io/brodgar/addon/CharApi.java` — 4 (the tree adapters the placement seam fires)
- `src/io/brodgar/addon/Selector.java` — 4 (`matches` walks the tree and must hold its monitor)
- `src/io/brodgar/addon/OptionsHandle.java` — 3, 8 (`hafen.client()`, where `stepping()` hangs)
- `src/io/brodgar/addon/LuaWidget.java` — 2, 4
- `src/io/brodgar/addon/UiApi.java` — 1, 3
- `src/io/brodgar/addon/Layout.java` — 2, 4
- `src/io/brodgar/addon/Gesture.java` — 2
- `src/io/brodgar/addon/Addon.java` — 1 (the per-addon registries: the `Update` subscriber list)
- `src/io/brodgar/addon/Subs.java` — 1, 2, 3
- `src/io/brodgar/addon/WidgetSubs.java` — 1, 6
- `src/io/brodgar/addon/Controls.java` — 2, 6
- `src/haven/UILoop.java` — 1
- `src/haven/UI.java` — 2, 5
- `src/haven/Widget.java` — 3
- `src/haven/GItem.java` — 3
- `docs/client/multi-session.md` — 1, 2 (read, never written: the rule)
- `docs/client/widgets.md` — 3 (the entry seam and its monitor; the read-only walk it used to carry
  is `docs/client/widget-introspection.md`, split out of it when the gotchas hit the 150-line ceiling)
- `docs/client/widget-draw.md` — 6 (a `tick`/`draw` is a callback with that tree's monitor held)
- `docs/addons/api/conventions.md` — 2, 6
- `docs/addons/api/ui/selectors.md` — 3, 6
- `docs/addons/api/ui/custom.md` — 1, 6
- `docs/addons/api/event/streams.md` — 5, 6
- `docs/addons/api/ui/items.md` — 5 (`item:on("Changed")`'s own page: when it fires)
- `docs/addons/api/client/README.md` — 3, 8 (`hafen.client():stepping()`)
- `docs/addons/runtime.md`, `docs/addons/guides/events-and-timers.md` — 6
- `DOCUMENTATION.md` — 6

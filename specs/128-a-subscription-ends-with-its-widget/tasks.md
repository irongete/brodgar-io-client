# 128 — tasks

> **Every suite here is non-regression, and none of them can prove the leak.** No verb reports the size of
> `Addon.widgetSubs`, `LuaSelectorWatch.matched`, `Layout.dragListeners`, `Addon.gestures` or an intern
> cache, and a subscription on a destroyed widget fired
> nothing before this feature and fires nothing after — so there is no behavioural difference for a suite to
> assert. The retirement itself is proved headlessly, with `jshell` over a real off-screen `UI`
> (`docs/client/boot-and-loop.md`, "Building a `UI` headlessly": `bin/builtin-res.jar` and
> `build/classes-lib` on the classpath, `new UI(null, ar, Coord.of(800, 600), null)`), which is the one
> place the map is readable. Each task states its `jshell` proof as well as its suite; both are required,
> and the suite is what guards the surface the fix could break.

- [x] **128.1 — the disposal seam retires a widget's subscriptions.**
      `AddonManager.onWidgetDisposed` appends the widget to a `ConcurrentLinkedQueue<Widget>` **before** its
      `w.parent == null || !(w instanceof Owned)` guard, so the widget itself and every descendant are
      covered while the event half below the guard is untouched. The queue drains on the step, calling
      `Addon.dropWidgetSubs(w)` for every addon `profOwners()` lists — which already removes the entry and
      `teardown()`s it, releasing every engine listener and watch-list registration and firing nothing.
      `Addon.widgetSubs` becomes a `ConcurrentHashMap`, the drain being a second writer beside a mint that
      `threading.md` allows beside the step; the `WeakHashMap` it replaces collected nothing, because
      `WidgetSubs` holds the key in `wdg` and again in `inwdg`. `UiApi.prune`'s sweep stays as the backstop.
      *Its jshell proof* builds a headless `UI`, subscribes an addon on a child of a window, `destroy()`s
      the window, runs the drain, and asserts `widgetSubs` is empty — then repeats with
      `remove(); other.add(w)` and asserts the entry **survives**, which is the re-home case that makes
      `rdispose` the only safe seam.
      *Its suite* builds a bare widget, subscribes, drives a bounded wait until the handler has fired,
      destroys the widget and asserts the handler stops firing and `sub:off()` on the dead subscription
      raises nothing — the pair that fails if `teardown` were made to announce, which is what would turn a
      closing window into a hundred events.

- [x] **128.2 — the disposal seam retires what a selector matched.**
      The same drain walks `st.selectorWatches` and removes the widget from each `LuaSelectorWatch.matched`,
      and from `st.selectorPending`, taking the tree's own monitor — `LuaWidget.monitorOf(st.ui)`, one tree
      at a time — because `UiApi` records that monitor as what keeps `matched` from being written by two
      threads at once. It does **not** call `dispatchSelectorRemoved`: that fires `Removed` where it
      removes, and firing here would mint an announcement per descendant of every closing window, which is
      the cost the disposal seam's own design refuses. So a widget disposed as a descendant leaves `matched`
      exactly as silently as it leaves the tree.
      *Its jshell proof* builds a headless `UI`, registers a selector subscription, lets a matching widget
      enter as a descendant, `destroy()`s the ancestor, runs the drain and asserts `matched` is empty while
      no handler ran — the second half being the assertion that separates this from calling the dispatch.
      *Its suite* registers `s:ui():on(sel, "Added", fn)` and `("Removed", fn)` against a selector the
      client's own tree satisfies, and asserts the `Added` handler fired and that a `Removed` still fires
      for a widget that is properly removed — the check that the new retirement did not eat the announcement
      the removal seam is supposed to make.
      `[manual]`: open and close the inventory twice — expect: the window's own `Removed` still reported
      once per close, in the log line the suite writes.

- [ ] **128.3 — the disposal seam retires a widget's layout record.**
      `Layout.dispatchRemoved(st, w)` runs from `drainDisposedWidgets` as well as from `drainRemovedWidgets`,
      so it reaches a widget that dies as a descendant: it drops the widget's `st.layoutPending` entry, calls
      `LuaWidget.pruneRemoved(w)`, and under `Layout.class` calls `retarget(w, null)`, which drops `derived`'s
      entry and — when nothing else names the same target — the drag listener installed for it. It is
      idempotent, so the destroyed widget itself, which reaches both queues, is retired once and pays one
      `derived.isEmpty()` the second time. `Layout.dragListeners` becomes an `IdentityHashMap` under
      `Layout.class` beside `derived`: the handler `installDragListener(t)` returns closes over `t` and is
      stored under `t`, so the `WeakHashMap` announces a collection it can never perform.
      *Its jshell proof* builds a headless `UI`, anchors one window to a widget nested inside a second,
      `destroy()`s the second so the target dies as a descendant, runs the drain, and asserts `derived` and
      `dragListeners` are empty and the target's `listening` list no longer carries the handler — then repeats
      with the target `remove()`d and added elsewhere, asserting the anchor and the listener both **survive**,
      which is the re-home case that keeps this off the removal seam.
      *Its suite* anchors one of its own widgets to another, drives a bounded wait until the anchor has been
      applied, destroys the target's parent, and asserts the anchored widget stays where it was and that a
      fresh anchor onto a new target still tracks that target's move — the pair that fails if the retirement
      dropped the seam rather than one widget's record.

- [ ] **128.4 — the disposal seam retires a widget's gesture bindings.**
      `Gesture.dispatchRemoved(Widget)` is new: the subsystem has no departure entry point at all today, so
      `widget:draggable(h)` leaves a `Bind` holding the target and the handle strongly in `Addon.gestures` for
      the rest of the session. It runs from both drains, for the reason `Layout.dispatchRemoved` does. For
      every addon `profOwners()` lists it drops each `Bind` naming the widget as target or as handle through
      the existing `forget` — which deafens a handle nobody names any more, so dropping each `Bind`
      individually is what keeps a handle serving its other targets — and it ends a gesture in flight whose
      handle or target has just died, releasing the `UI.grabmouse` it holds. `Gesture.arms` becomes an
      `IdentityHashMap` under `Gesture.class` for the reason `dragListeners` does.
      *Its page*: `docs/addons/api/ui/native.md`, under *Letting the user drag it*, gains the one fact this
      task makes definite — an arming ends when either widget goes, the target or the handle — written where
      the page already says what `w:draggable()` reads back.
      *Its jshell proof* builds a headless `UI`, arms `draggable` with a handle on a target nested in a
      window, `destroy()`s the window, runs the drain, and asserts `Addon.gestures` is empty, `arms` holds
      nothing for the handle and the handle's `listening` list is clean — then arms two targets through one
      handle, kills one, and asserts the handle is **still listening** for the other.
      *Its suite* arms `w:draggable(h)`, reads it back with `w:draggable()`, destroys `w`, and asserts a fresh
      arm on a new widget still reads back, and that `w:draggable(nil)` on the dead one raises nothing.
      `[manual]`: drag the armed widget by its handle — expect: it follows the pointer.

- [ ] **128.5 — the disposal seam retires an item's subscriptions and its handles.**
      `GItem extends AWidget`, so a disposed item is already in the queue and needs no new tap: the drain
      calls `dropItemSubs(it)` for it as well as the removal seam does. An inventory that closes **destroys**
      the items inside it rather than removing them, so the removal seam never reaches them, and
      `Addon.dropItemSubs` already names what that costs — a handler closing over its own item makes the map's
      value reach its key, so the `WeakHashMap` holds the `GItem` and everything under it. Each addon's
      per-item intern cache drops the item's entry on the same drain: `LuaItem.Cache.live`, an
      `IdentityHashMap<GItem, Ref>` swept only on the next `of()`, and its Contents, Meter and Buff siblings,
      each through the monitor `of()` takes rather than by reaching the map.
      *Its jshell proof* builds a headless `UI`, places a container widget holding a `GItem`, subscribes an
      addon on the item, `destroy()`s the container without removing the item, runs the drain, and asserts
      `itemSubs` and every per-item cache are empty — then repeats with the item `remove()`d and re-added,
      asserting the subscription **survives**.
      *Its suite* takes an item the character already holds (a bounded retry until an inventory answers),
      subscribes `item:on("Changed", fn)`, asserts `item:info()` interns to the same handle across a drain,
      and asserts `sub:off()` on a subscription whose item has gone raises nothing.
      `[manual]`: close the inventory window — expect: the suite's next line reads
      `[pass] an item's subscription ends with the container that held it`.

- [ ] **128.6 — a per-widget map added later cannot reach one drain only.**
      `tools/widgetstate.py`, run and read like `docverbs.py` and `refusalverbs.py`. **Check 1**: every field
      in `src/io/brodgar/**` whose declared type is a map keyed by `Widget` or `GItem` carries either a
      `// retired:` note naming the method that retires it — which must exist, and must be called from
      `drainDisposedWidgets` by a call chain the tool follows one level — or a `// retained:` note giving the
      reason it is not retired. **Check 2**: no `WeakHashMap<K, V>` declares a `V` with a field of type `K`.
      It prints its counts and **states its own blind spots**: it matches declared types textually, so a map
      behind a helper or a generic wrapper is invisible to it, and check 2 is one level deep — it cannot see a
      value that reaches its key through a captured local or through a Lua closure, which is how
      `dragListeners`, `arms` and `itemSubs` each do it. Check 1 is what covers those, by asking for the
      retirement rather than for the absence of the cycle. Every map this feature touched gets its note in
      this task, which is where the reasons are written down once.
      *Its proof* is the tool itself: green on the tree, and non-zero on each of two seeded violations — a map
      declared with no note, and a `WeakHashMap` whose value declares its key — reverted after.
      *Its suite* is the feature's own integration check, and it duplicates rather than defers: it subscribes
      on a widget, anchors a widget, arms a drag handle and subscribes on an item, destroys each one's
      ancestor, and asserts all four are silent afterwards and that a fresh one of each still works — so this
      one command is the whole verification of 128 even if no earlier suite is ever run again.

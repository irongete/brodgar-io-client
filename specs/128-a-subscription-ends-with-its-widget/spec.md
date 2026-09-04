# 128 — a subscription ends with its widget

## What and why

`Addon.widgetSubs` is a `WeakHashMap<Widget, WidgetSubs>` and `widgetSubs(w)` mints
`new WidgetSubs(this, w)`, which keeps the widget in `WidgetSubs.wdg` — and again in `inwdg`. **The value
holds its own key**, so the entry can never become weakly unreachable and the map cannot collect anything.
The weak type announces an automatic cleanup that cannot happen, which is what makes a reader assume there
is nothing to retire.

Nothing retires it either, for the ordinary case. `Addon.dropWidgetSubs` has one caller, `widget:revert()`.
`Addon.teardownWidgetSubs` runs on reload and disable. The sweep in `UiApi.prune` calls a widget dead by
`dead(w) = (w == null) || (w.ui == null) || w.ui.destroyed` — **the death of the whole tree**, not of a
widget. So a window that closes, an inventory that closes, a flower menu that goes, a control that is taken
out — every one of them, with its session still logged in — leaves its entry, its `WidgetSubs`, and every
engine listener and watch-list registration that `WidgetSubs` installed, held for the rest of the session.
Widgets rotate constantly in this client and a session is long, so an addon that subscribes on transient
widgets grows without bound until the next `:reload`.

**And the hole belongs to the seam, not to one map.** `AddonManager.drainRemovedWidgets` is where this
client retires its per-widget bookkeeping, and it has seven consumers: the tree adapters, `widget:on`
removals, a replaced view's death test, a widget standing in the world, the selector dispatch,
`Layout.dispatchRemoved`, and — for a `GItem`, which is a `Widget` — `Addon.dropItemSubs`. Every one of them
is fed from the **removal** queue alone, so every one of them keeps what it holds for a widget that dies as a
descendant. `Addon.dropItemSubs` states the mechanism itself: a handler "closes over the very item it was
subscribed on … so the map's VALUE reaches its own KEY", and the `clear()` that breaks that cycle runs only
where a removal reaches. An inventory that closes **destroys** the items inside it rather than removing them,
so it never does.

**`Gesture` has no departure seam at all.** `widget:draggable(h)` and `widget:resizable(h)` file a `Bind` in
`Addon.gestures` — a `CopyOnWriteArrayList` holding the target and the handle **strongly** — and arm a
`MouseDownEvent` listener recorded in `Gesture.arms`. `forget` runs from a re-arm, from `:draggable(nil)` and
from reload, never from a death, so a window armed once is held for the session by a strong list.
`Layout.dragListeners` is that shape one subsystem along and with the weak-map fault on top: the handler
`installDragListener(t)` returns closes over `t` and is stored under `t`, so it can collect nothing either.

**`LuaItem.Cache.live` holds its key outright.** It is an `IdentityHashMap<GItem, Ref>` whose `Ref` is weak on
the handle, drained on the next `of()` — so an addon that stops minting items pins every `GItem` it ever
interned, handle or no handle.

`LuaSelectorWatch.matched`, a strong `LinkedHashMap<Widget, Integer>`, has the same root. It is drained by
`UiApi.dispatchSelectorRemoved`, fed from `AddonManager.drainRemovedWidgets`, which is fed from the
**removal** seam. `docs/client/widgets.md` records why that is not enough: `Widget.destroy()` is `remove()`
on the widget itself plus `rdispose()`, and `rdispose` recurses `dispose()` only — no descendant is
unlinked or runs `remove()`. So every client-minted widget that dies as a descendant stays in `matched`.

The seam that does reach them exists: `Widget.rdispose` ends in `onWidgetDisposed`, is overridden nowhere,
and is the one call every descendant of a destroyed widget makes. `AddonManager.onWidgetDisposed` returns
early on `w.parent == null` or a widget that is not `Owned`, which is what keeps it from reaching the
widget itself and from reaching a native descendant.

And it is the **only** safe seam. `docs/client/widgets.md` is explicit that `Widget.remove()` is a death
notice rather than a detach: a re-home is `remove(); other.add(w)`, so retiring on removal would silently
unsubscribe a widget that is alive one line later.

## Acceptance criteria

1. A widget destroyed on its own, with its session still alive, is retired from every addon's `widgetSubs`
   and its `WidgetSubs` is torn down — every engine listener and watch-list registration released.
2. Every **descendant** of a destroyed widget is retired the same way, including one the client minted.
3. A **re-homed** widget keeps its subscriptions: nothing is retired by `remove()` alone.
4. What a selector subscription matched is retired on the same path, so `LuaSelectorWatch.matched` holds no
   widget that has been disposed.
5. **No event changes.** No removal, no `Removed` and no `Destroy` is minted for a widget that does not mint
   one today; the retirement is bookkeeping and fires nothing.
6. `widgetSubs`'s declared type says what it does: retirement is explicit, and the tree-death sweep in
   `UiApi.prune` remains as the backstop rather than as the mechanism.
7. A widget disposed on its own or as a descendant is retired from its layout record — `Layout.derived`, the
   drag listener installed for it, the session's `layoutPending`, and `LuaWidget`'s own per-widget records.
8. It is retired from every addon's gesture bindings: no `Bind` in `Addon.gestures` names a disposed widget
   as target or as handle, no entry in `Gesture.arms` outlives its handle, and a gesture in flight whose
   handle or target dies ends rather than going on writing to a destroyed widget.
9. An item destroyed with the container that held it — never removed — drops every `item:on(key, fn)` on it
   and every handle interned for it, so no `GItem` is held by a cache after the widget is gone.
10. Every declared type says what it does: no map in this feature's reach announces a collection it cannot
    perform.
11. The two drains cannot drift apart. A per-widget or per-item map added later, and left off the retirement,
    fails a checker rather than being found nine hours into a session.

## Out of scope

- **The silence `widget:on("Destroy", fn)` keeps for one of the client's own widgets inside a destroyed
  window** — `specs/ROADMAP.md` line 18, and a deliberate decision recorded at the disposal seam: a window
  closing does not mint a removal for each of the hundred widgets inside it. This feature retires the
  bookkeeping for exactly those widgets and leaves the event semantics untouched, so the two are separable
  and criterion 5 is the line between them. The other half would be a decision about whether a closing
  window announces its subtree at all, which is a change to a published event and to its cost.
- **Whether a disposed widget's handlers should fire one last time.** They do not today and do not after;
  changing that is criterion 5's boundary again.

## Docs impact

**One line, on one page.** Almost nothing here is observable, and the exception is worth stating:

- Nothing an addon can observe changes for criteria 1–4, 7 and 9. A subscription on a destroyed widget
  never fired after the destroy and still does not; a `Sub` held in Lua was already answered by a dead
  handler and still is; an anchor whose target has gone was already inert, because `Anchor` holds that
  target weakly. Criterion 5 is the promise that nothing else moves.
- **`docs/addons/api/ui/native.md` gains one fact** under *Letting the user drag it*: an arming ends when
  **either** widget goes — the target or the handle. The page says `w:draggable()` gives "the grip back, the
  same Widget object you passed" and says nothing about a destroyed grip; today that leaves an arming naming
  a widget which no longer exists, and after 128.4 it leaves none. An author who builds a grip and later
  tears their own surface down cannot read the page without it.
- The item half needs nothing written: `items.md` already states that an item leaving "drops every
  subscription on it", and 128.5 is what makes that true for an item destroyed with the container that held
  it rather than removed from it.
- `docs/client/widgets.md` already carries every seam and gotcha this feature stands on — the universal
  disposal seam, the "not removed" subtree, the death-notice-not-detach rule. `DOCUMENTATION.md` §12.3
  forbids writing `io.brodgar` there, so where the retirement hangs is not that page's subject.

Derived impact set — subscription lifetime stated in prose:

```text
$ grep -rn "torn down with your addon\|released when\|drops every subscription" docs/addons/
docs/addons/api/event/README.md:39       …owned by your addon and released when it reloads or is disabled…
docs/addons/api/ui/custom.md:4           …both are torn down with your addon.
docs/addons/api/ui/items.md:310          …drops every subscription on it.
docs/addons/api/client/addon.md:142      Subscriptions are torn down with your addon like every other one…
docs/addons/api/client/keybindings.md:211  Hotkeys are torn down with your addon on reload or disable…
```

All five state when a subscription is released **to the author**, and all five stay true: an addon's
subscriptions are still released on reload and disable, and this feature adds a release the author cannot
observe and never had to think about. Each is discharged with that reason by the task that owns the seam.

## Context files

- `src/io/brodgar/addon/Addon.java` — 1, 2, 8, 9 (`widgetSubs`, `widgetSubs(w)`, `dropWidgetSubs`,
  `teardownWidgetSubs`, `itemSubs`, `dropItemSubs` — whose javadoc already names the cycle a weak map
  cannot collect — and `gestures`, the strong list nothing retires)
- `src/io/brodgar/addon/WidgetSubs.java` — 1 (`wdg`, `inwdg`, `teardown`)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2 (`onWidgetDisposed`, `onWidgetRemoved`,
  `drainRemovedWidgets`, `queueState`)
- `src/io/brodgar/addon/UiApi.java` — 1, 2 (`prune`, `pruneDeadTrees`, `dead`,
  `dispatchSelectorRemoved`, `selectorWatches`, `selectorPending`)
- `src/io/brodgar/addon/LuaSelectorWatch.java` — 2 (`matched`, `alive`)
- `src/io/brodgar/addon/Layout.java` — 7 (`dispatchRemoved`, `retarget`, `derived`, `dragListeners`,
  `installDragListener`, `dropDragListener`)
- `src/io/brodgar/addon/Gesture.java` — 8 (`arms`, `listen`, `deafen`, `forget`, `find`, `Bind`, and the
  running gesture's own teardown)
- `src/io/brodgar/addon/LuaItem.java` — 9 (`Cache.live`, `Cache.of`, `Cache.drain`, and the `item:on` mint
  that already names the cycle)
- `tools/` — 11 (the new checker; `docverbs.py` and `refusalverbs.py` as the shape a checker is written in)
- `src/haven/Widget.java` — 1, 2 (read only: `destroy`, `remove`, `rdispose`, and the two seams' call sites)
- `docs/client/widgets.md` — 1, 2 (read only: the seams, the destroy gotchas, the re-home rule)

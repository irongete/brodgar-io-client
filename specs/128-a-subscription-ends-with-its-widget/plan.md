# 128 — plan

## Approach

### The tap goes in front of the guard

`AddonManager.onWidgetDisposed` is the end of `Widget.rdispose`, which is overridden nowhere and is the one
call every descendant of a destroyed widget makes. It reads:

```java
if((w.parent == null) || !(w instanceof Owned))
    return;
onWidgetRemoved(w);
```

The retirement goes **before** that guard, and the guard keeps the event half exactly as it is. `parent` is
already null for the destroyed widget itself, because `destroy()` is `remove()` — which nulls it — and then
`rdispose()`, so the widget itself is precisely what the first clause skips today; putting the retirement
ahead of it covers the widget and every descendant in one line. The `Owned` clause keeps the second half
addressing only an addon's own widget, so no removal is minted for a native descendant and
`ROADMAP.md` line 18 is neither fixed nor widened.

`Widget.remove` is deliberately **not** used. `docs/client/widgets.md` states that it is a death notice
rather than a detach — a re-home is `remove(); other.add(w)` — so retiring there would unsubscribe a widget
that is alive one line later. `rdispose` does not run on a re-home, which is what makes it the only safe
seam.

### The retirement is deferred, because the tap has no thread of its own

`rdispose` runs on whatever thread reached `destroy()` — a Loader thread applying a server update as often
as the UI thread — and this feature adds a **writer** to a map that is only read on the fire path today. So
the tap does the least it can: it appends the widget to a `ConcurrentLinkedQueue<Widget>` on
`AddonManager` and returns. The queue is drained on the step, which is the shape the removal seam beside it
already uses (`st.removedWidgets`, drained into `drainRemovedWidgets`) and the shape 126.1's quarantine
queue uses. Nothing at the tap touches Lua, reads widget state or takes a monitor.

The drain calls `Addon.dropWidgetSubs(w)` for every addon `profOwners()` lists — the same set
`UiApi.pruneDeadTrees` walks. That method already exists and already does the whole job: `widgetSubs.remove(w)`
then `s.teardown()`, releasing every engine listener and watch-list registration and marking each handler
dead so a `Sub` kept in Lua still finds nothing to end. It fires nothing, which is criterion 5 for free —
the javadoc's own words are "a revert is not a destroy".

A queue is a strong reference for the one tick before it drains, which is the correct trade: the alternative
is doing map work on an arbitrary thread.

### The map says what it does

`widgetSubs` becomes a `ConcurrentHashMap<Widget, WidgetSubs>`. The `WeakHashMap` never collected anything —
`WidgetSubs` holds the key in `wdg` and again in `inwdg`, so no entry was ever weakly unreachable — and
keeping a weak type after adding explicit retirement would leave the same false promise standing over a
mechanism that no longer needs it. Concurrent rather than plain because this feature adds the second writer:
minting runs wherever `widget:on` was called, which `threading.md` says can be beside the step, and the
drain runs on the step. `haven.Widget` overrides neither `equals` nor `hashCode`, so identity semantics are
what the map has always had and what it keeps.

`UiApi.prune`'s sweep stays, unchanged, as the backstop for a whole tree dying. It stops being the mechanism
and becomes what it reads as.

### What a selector matched

`LuaSelectorWatch.matched` has the same root and is fixed on the same drain. It is fed and cleared by
`UiApi.dispatchSelectorRemoved`, which runs from `drainRemovedWidgets` — the removal seam — so a widget that
dies as a descendant never reaches it. The drain therefore also walks `st.selectorWatches` and removes the
widget from each `matched`, and from `st.selectorPending`, **without firing**: `dispatchSelectorRemoved`
fires `Removed` where it removes, and calling it here would mint the hundred announcements the disposal
seam's own design refuses.

The two writers to `matched` must stay ordered. `UiApi` records that the tree's monitor is what keeps that
map from being written by two threads at once, so the retirement takes the same monitor the dispatch takes
— `LuaWidget.monitorOf(st.ui)`, as `AddonManager` already does around its own tree work — rather than
inventing a second discipline for one map.

### One drain, every consumer that retires

The disposal drain is not a fix for `widgetSubs`; it is the retirement half of the death seam, and every
consumer of `drainRemovedWidgets` that keeps per-widget state belongs on it. `docs/client/widgets.md` is why:
`rdispose` recurses `dispose()` only, so a descendant is never unlinked and never runs `remove()`. The rule
that separates the two halves is **the removal drain announces, the disposal drain retires**, and criterion 5
is what keeps the announcing consumers where they are.

`Layout.dispatchRemoved` and `dropItemSubs` are the two that retire, so both run from both drains. Neither
fires, both are idempotent by construction (a `remove` that finds nothing), and the widget that reaches both
queues in one frame — removed and then disposed, which is what `destroy()` does to the widget itself — is
retired once and pays one `isEmpty()` the second time.

### The maps stop announcing a collection they cannot perform

`Layout.dragListeners` and `Gesture.arms` are `WeakHashMap<Widget, EventHandler<...>>` whose value is the
handler installed **on that same widget**: `installDragListener(t)` closes over `t`, and `Gesture.listen`'s
handler closes over `handle`. A `WeakHashMap` entry holds its value strongly, so the value reaches the key
and the entry is never weakly unreachable — the identical fault `widgetSubs` has, one level less obvious
because the reference is a captured local rather than a field. Both become `IdentityHashMap` guarded by the
monitor their subsystem already takes (`Layout.class`, `Gesture.class`), retired on the drain, for the reason
128.1 gives: an explicit retirement under a weak type leaves a false promise standing over a mechanism that
no longer needs it.

`Layout.derived` stays a `WeakHashMap`. Its value is an `Anchor` that holds its target through a
`WeakReference` deliberately, so it collects — and it is retired on the drain as well, because a record that
outlives its widget by a collection cycle is still a record of a widget that is gone.

### `Gesture` gains the seam it never had

`Gesture.dispatchRemoved(Widget)` is new: the subsystem has no departure entry point at all today. It drops
every `Bind` naming the widget as target or as handle, through the existing `forget`, which already deafens a
handle nobody names any more, and it ends a gesture in flight whose handle or target has died. The strong
`Addon.gestures` list is the worse half of that pair — a `WeakHashMap` that collects nothing at least holds
one entry, while a `CopyOnWriteArrayList` of `Bind` holds two widgets per arming with nothing weak anywhere.

### An item is a widget, so it is already in the queue

`GItem extends AWidget`, so a disposed item reaches the disposal queue with no new tap. `dropItemSubs` runs
from the drain as well, and the per-addon intern caches — `LuaItem.Cache.live` and its Contents, Meter and
Buff siblings, all `IdentityHashMap` keyed strongly on the item and swept only on the next mint — drop the
item's entry there. That is the whole of the item half: no new seam, one call each on a drain that already
sees them.

### A checker, because the sixth site is the one nobody is looking for

`tools/widgetstate.py` makes two mechanical checks over `src/io/brodgar/**`, in the shape `docverbs.py` and
`refusalverbs.py` are written in:

1. every field whose declared type is a map keyed by `Widget` or `GItem` is named in the retirement path, or
   carries a `// retained:` note giving the reason it is not;
2. no `WeakHashMap<K, V>` has a `V` that declares a field of type `K`.

It states its own blind spots, which are real and are why check 1 exists at all: it matches declared types
textually, so a map behind a helper or a generic wrapper is invisible to it, and check 2 is one level deep —
it cannot see a value that reaches its key through a captured local or through a Lua closure, which is
exactly how `dragListeners`, `arms` and `itemSubs` do it. Check 1 covers those by asking for the retirement
rather than for the absence of the cycle.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/addon/AddonManager.java` | the retirement ahead of `onWidgetDisposed`'s guard; the disposed queue; its drain on the step |
| `src/io/brodgar/addon/Addon.java` | `widgetSubs` becomes a `ConcurrentHashMap` |
| `src/io/brodgar/addon/UiApi.java` | the `matched` / `selectorPending` retirement, under the tree monitor; `prune` restated as the backstop |
| `src/io/brodgar/addon/Layout.java` | `dispatchRemoved` from both drains; `dragListeners` becomes explicit |
| `src/io/brodgar/addon/Gesture.java` | `dispatchRemoved`, new; `arms` becomes explicit; a running gesture ends with its widget |
| `src/io/brodgar/addon/LuaItem.java` | the intern cache drops a disposed item |
| `tools/widgetstate.py` | the two checks that keep a later map from reaching one drain only |
| `docs/addons/api/ui/native.md` | one fact: an arming ends when either widget goes |
| `addons/128-a-subscription-ends-with-its-widget.1` ... `.6` | one suite per task |

One page is written, and one line of it: `native.md` gains the lifetime of an arming, which 128.4 is the
first task to make definite. `DOCUMENTATION.md` §12.3 keeps `io.brodgar` off `docs/client/`, so where a
retirement hangs stays out of the map. The five pages stating when a subscription is released stay true and
are discharged with that reason.

## Risks and gotchas

- **`Window.reqdestroy` fades instead of unlinking**, so `onWidgetRemoved` fires as the fade starts while
  the window is still linked and full. `rdispose` runs at the real end, so the retirement lands after the
  fade — later than the removal event, and correct.
- **`w.parent` is null for the destroyed widget itself at `rdispose`**, and not for its descendants. Reading
  the guard the other way round is what has kept the widget itself out of this seam.
- **`dropWidgetSubs` must stay silent.** It is `widget:revert()`'s implementation and fires nothing; a
  future change that made it announce would turn criterion 5 into a hundred events per closing window.
- **The drain must tolerate a widget no addon subscribed on**, which is almost all of them: the queue sees
  every disposed widget in the client, so the per-widget cost is one map lookup per addon and must stay
  that.
- **`profOwners()` is the addon set the sweep already uses**, and it includes the `:lua` REPL owner. Its
  subscriptions are retired like any other addon's.
- **`matched` is guarded by the tree's monitor**, and the drain runs on the step holding none — so the
  monitor has to be taken deliberately, one tree at a time, never two.
- **The widget itself reaches BOTH queues**: `destroy()` is `remove()` and then `rdispose()`. Every
  retirement run from both drains must be idempotent, and must stay cheap on the second visit.
- **A gesture in flight holds a `UI.grabmouse`**, so ending one from the drain has to release the grab; a
  grab left behind is the failure mode `LuaGrab` already records for a session switch.
- **`Gesture.forget` deafens only when no binding names the handle any more**, which is what makes dropping
  each `Bind` individually correct and dropping the whole list wrong.
- **The intern caches are `synchronized` on themselves and minted off the step**, so the drain's removal
  takes the same monitor `of()` takes rather than reaching the map directly.
- **`LuaItem.Cache` holds its `Addon`**, so a cache swept per disposed widget must be reached through
  `profOwners()` like every other per-addon retirement on this drain, and not through a static registry.

## Discarded alternatives

- **Retiring at `Widget.remove` / `onWidgetRemoved`** — it is a death notice, not a detach, and a re-home
  goes through it, so an addon's subscriptions would vanish on a widget that is alive one line later.
- **Retiring inside the tap rather than on the step** — `rdispose` runs on whatever thread destroyed the
  widget, so it would put map writes and listener teardown on a Loader thread, beside a fire path that
  reads the same map every frame.
- **Widening `UiApi.prune`'s `dead(w)` to test reachability instead of tree death** — it turns an
  end-of-tree sweep into a per-frame walk of every widget an addon ever subscribed on, and it still answers
  a tick or more late for a widget that was disposed rather than unlinked.
- **Making the disposal seam call `dispatchSelectorRemoved`** — it retires `matched` and fires `Removed`
  for every descendant of a closing window, which is the announcement the disposal seam was written to
  avoid and a change to a published event.
- **Leaving `widgetSubs` a `WeakHashMap`** — it collects nothing while the value holds its key, so the type
  would go on promising a cleanup the code no longer relies on and no longer needs.
- **Calling `Layout.dispatchRemoved` from the disposal drain instead of from both** — a widget that is
  properly removed and then lives on re-homed would keep an anchor record pointing nowhere, and the removal
  seam is where the late-caption pending list has to be cleared to stay in step with its own dispatch.
- **Leaving `Gesture` to `UiApi.prune`'s tree-death sweep** — it does not walk `gestures` at all, and
  widening it to would put a per-frame scan in front of a list whose whole design is that arming is rare and
  reading is rarer.
- **Ending a running gesture by testing its widgets on every mouse move** — the gesture already knows its
  handle and its target, so the death seam can tell it once instead of it asking sixty times a second.
- **A `ReferenceQueue` sweep for the intern caches instead of the drain** — the queue clears when the
  **handle** is dropped, which says nothing about the item, and the entry that pins a `GItem` is exactly the
  one whose handle has already gone.
- **Writing the checker over bytecode rather than source** — a captured local is visible there, so check 2
  would find all three cases directly; but it needs a build to run where every other tool in `tools/` reads
  the tree, and it would still miss the Lua closure that check 1 is written for.
- **Making `widget:on("Destroy", fn)` fire for a native descendant** — `ROADMAP.md` line 18 and the
  maintainer's to schedule; it is an event's reach and its cost, where this feature is a map's contents.
- **Retiring the three window-keyed intern caches as well** — `LuaFightSummary.Cache.live`,
  `LuaFood.Cache.live` and `LuaStudySummary.Cache.live` are `LuaItem.Cache`'s strong-keyed shape exactly, and
  128.6's checker is what found them: the item half named the Contents, Meter and Buff siblings and these
  three are keyed on a widget instead. They stay `// retained:`, because the thing that made the item case
  bite is absent — all three key on a tab of `CharWnd`, which answers its own close by hiding, so nothing
  rotates through them and `drain()` takes an entry at the next `of()`. Reaching them would put three more
  `instanceof` in front of every widget the client destroys, against the rule this drain is built on: a
  widget nobody interned must cost one map lookup and nothing else.

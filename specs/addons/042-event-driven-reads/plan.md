# 042-event-driven-reads — Plan

> Size limits lifted by the maintainer (2026-08-06). Tasks run in **fresh contexts**, so this file
> carries the mechanism in full rather than pointing at it.

## Approach

Three mechanisms replace the poll stage. Two of them already exist in the client and only need wiring;
one is a new ~80-line class in the addon layer plus a single core tap.

### M1 — the removal seam (the one new core tap)

**The tap goes in `Widget.remove()`**, not in `cdestroy`:

```java
// src/haven/Widget.java
public void remove() {
    if(canfocus)
        setcanfocus(false);
    if(parent != null) {
        unlink();
        parent.cdestroy(this);
        parent = null;
    }
    if(ui != null)
        ui.removed(this);
    io.brodgar.addon.AddonManager.onWidgetRemoved(this);   // addon: widget-removal seam (042.1)
}
```

Placement is chosen deliberately: **after** `unlink()`/`cdestroy`/`parent = null` and after
`ui.removed(this)`, so a handler sees the tree in its settled post-removal state — the mirror of
`onWidgetPlaced`, which fires *after* the child is in the tree. `remove()` is overridden nowhere and is
on every removal path (`UI.DstWidget.run` → `UI.destroy(Widget)` → `reqdestroy()` → `destroy()` →
`remove()`, plus any direct client call), which is exactly why it beats `cdestroy`: **9 of the 17
`cdestroy` overrides never call `super`**, and they are the parents this feature depends on
(`Bufflist`, `GameUI`, `ChatUI`, `QuestWnd`'s questbox, `WoundWnd`'s woundbox, the four `GameUI` inner
panels). The same fact makes `Widget.childseq` unusable as a change counter here.

`AddonManager.onWidgetRemoved(Widget)` is a one-line delegate in the hub (the house style: core edits
call a named hub method so `haven` edits never move again). It **must not touch Lua** — `remove()` can
run on a Loader thread — and instead routes to the interested consumers the way `onUimsg` routes to
`CharApi.dispatchUimsg`: mark, or enqueue, and let the tick fire.

**Threading.** `Widget.remove` runs under `synchronized(ui)` when it comes from the server command queue
([`UI.CommandQueue.execute`](src/haven/UI.java:272) → `synchronized(UI.this)`), but a client-side
`destroy()` can come from the UI thread. Neither is guaranteed to *be* the UI thread, so the seam
enqueues onto the existing tick drain, exactly as `gobEvents`/`overlayEvents` do (038.3), and inherits
D-106: **a queue whose handlers write back into it is drained one frame's worth, never to empty.**

### M2 — `Resolve`: the resolution notify (new class, `src/io/brodgar/addon/Resolve.java`)

The client already answers *"tell me when this has loaded"*: `Loading implements Waitable`
([Loading.java:32](src/haven/Loading.java:32)) and `Waitable.waitfor(Runnable, Consumer<Waiting>)`
registers a **one-shot, cancellable** continuation ([Waitable.java:32](src/haven/Waitable.java:32)).
`Waitable.Queue.wnotify()` fires and clears every registration; `Waiting.cancel()` withdraws one.

Nothing in `io.brodgar` uses it today — **`waitfor` and `Waitable` appear nowhere in the learnings or in
the addon layer**, so this feature introduces the idiom and owes it a learnings entry.

`Resolve` wraps the raw seam with the three things every use here needs:

1. **Retry-on-notify, not wait-once.** A read that throws `Loading` is retried when *that* `Loading`
   resolves; if the retry throws a *different* `Loading` (a second resource, a further tile), it
   re-registers on the new one. This is the client's own `Waitable.Checker` shape
   ([Waitable.java:147](src/haven/Waitable.java:147)) and it is what makes the retry event-driven rather
   than a loop. Bounded: a stated maximum number of re-registrations, then the value is left unresolved
   and **stays** unresolved until something else announces it — never a fallback poll.
2. **Marshalling.** `wnotify()` runs on whichever thread completed the load (Loader, Defer pool). The
   callback therefore never runs Lua directly: it enqueues onto the tick drain (P5).
3. **Ownership (P2).** Every `Waiting` is registered in the owning `Addon`'s resource registry, so
   `:reload` and per-addon disable `cancel()` it. A cancelled `Waiting` must never fire, and a callback
   must double-check its addon is still alive before doing anything — the cancel and the notify can race
   on two threads.

**Refusal path.** `Loading.waitfor` on a bare `new Loading("…")` throws `Loading.UnwaitableEvent`
([Loading.java:70](src/haven/Loading.java:70)). `Resolve` catches it and reports "this value is not
waitable" to the caller, which is the signal that the read belongs in the spec's stated boundary rather
than in a hidden retry. The blocking helpers (`Loading.waitfor(Indir)`, `queuewait`, `waitforint`) are
**never** used — they park the calling thread and would freeze the UI.

Known waitable throwers, for the tasks: `Resource.Loading` ([Resource.java:431](src/haven/Resource.java:431)),
`Session.LoadingIndir` ([Session.java:114](src/haven/Session.java:114)), `Defer.NotDoneException`,
`Future.NotDone`, `Gob.DataLoading` ([Gob.java:806](src/haven/Gob.java:806)), `MCache.LoadingMap`
([MCache.java:67](src/haven/MCache.java:67)), `Timeout.NotYetException`.

### M3 — the two seams that already exist

- **Placement** — `UI.AddWidget.run` → `onWidgetPlaced(id, wdg)` ([UI.java:494](src/haven/UI.java:494)),
  fired *after* `pwdg.addchild(...)`, so the child is in the tree and a `Selector` already resolves. Two
  consumers today (selector events, the layout cascade); this feature adds the adapters.
  **Do not use `Widget.added()`** — it fires inside the private `add0`, and many `addchild` overrides
  re-target `add()` to a different widget (`Equipory`, the whole `GameUI.addchild` `place ==` chain), so
  `added()` can fire with a parent the server never named.
- **uimsg** — `onUimsg(wdg, msg)` ([UI.java:732](src/haven/UI.java:732)). Already the `refresh()` path
  for `FepAdapter`/`KinAdapter`/`QuestAdapter`/meter values/buff content/the wound list. **Its javadoc
  is wrong about the monitor** — see gotcha 1 — and 042.1 corrects it.

### The belt (row 4): three of five paths need no edit

`GameUI.uimsg` ([GameUI.java:1374-1415](src/haven/GameUI.java:1374)) writes `belt[]` synchronously for
`setbelt`-clear, `setbelt2 "p"` and `setbelt2 "d"` — the existing uimsg tap already fires after those.
Only `setbelt`-with-res and `setbelt2 "r"` defer via `glob.loader.defer(...)`; those get the notify
**inside the lambda, immediately after the assignment**, which is the only place the change happens:

```java
ui.sess.glob.loader.defer(() -> {
        belt[slot] = mkbeltslot(slot, rdt);
        io.brodgar.addon.AddonManager.onBeltSet(slot);   // addon: deferred belt write (042.6)
    }, null);
```

This **supersedes the standing rule** recorded in `learnings/widget-tree-reads.md`: *"if a widget
mutation is `loader.defer`-red, use `poll()`, not the uimsg tap."* The new rule is: *put the notify
where the write lands.* The learnings entry must be amended by 042.6, not left to contradict the code.

### Ordering, seeding and the invariants that must survive

Today `tick` runs `refreshTreeAdapters()` **then** `pollTreeAdapters()`, and 027.2 records that this is
what makes a brand-new meter surface as one `MeterAdded` rather than `MeterChanged`-then-`MeterAdded` —
**but that the seed-on-add is what actually buys it, not the ordering alone.** Event-driven has no
global ordering to lean on, so every add path must **seed its cache with the current value at the moment
it fires `*Added`**. Same for buffs (`BuffAdded` carries content already applied).

Two more invariants from 025:
- **`*Removed` fires before the cache entry is dropped**, so the payload is literally the entry being
  dropped and no handler re-entering the API sees a map the event has not been announced from.
- **One scan, shared.** `LuaBuff.actives()` is used by both `hafen.buff():list()` and the adapter, and
  `AddonWidgets.hudMeters(GameUI)` likewise for meters — *"so the list form and the events can never
  disagree about what is up."* Event-driven must keep reading through the same predicate, not invent a
  second membership test beside it.

### M4 — the geometry seam, and the end of `redrive` (row 10)

`Layout.poll()` is three halves. Two are already determined: `pending` (the late `[title=]`/`[res=]`
caption) becomes **uimsg**, and `LuaWidget.pruneMoved` becomes **M1**. The third, `redrive(u)`, is the
per-frame fold over `derived` that re-derives every anchored widget because *"the screen may have been
resized, a target may have been dragged, a window may have packed itself around new contents"*.

**`redrive` is deleted. An anchored widget re-derives on its inputs' own events — never on a fold.**
There are exactly three inputs, and all three have a seam:

| Input | Seam | Cost |
|---|---|---|
| The anchor **target's size**, and a window **packing itself** (`pack()` → `resize(contentsz())`) | **`Widget.resize(Coord)`** — core edit 3 | one tap, and it is already guarded |
| The **screen size** | the same tap: `UILoop` calls `ui.root.resize(sz)`, so the root is just another resize | free, same tap |
| The anchor **target's position** under a hand-drag | **`Widget.listen`** on that target — a **zero-edit** seam the engine already provides, the same one `WidgetSubs` uses (041.3) | one listener per *anchored target*, not per widget |

```java
// src/haven/Widget.java
public void resize(Coord sz) {
    if(Utils.eq(this.sz, sz))
        return;                    // unchanged: the tap never sees a no-op resize
    this.sz = sz;
    for(Widget ch = child; ch != null; ch = ch.next)
        ch.presize();
    if(parent != null)
        parent.cresize(this);
    io.brodgar.addon.AddonManager.onWidgetResized(this);   // addon: geometry seam (042.10)
}
```

The tap sits **after** the `Utils.eq` early return, so it fires only on a real size change, and after the
`presize()` cascade and `cresize`, so a handler reads settled geometry — the same rule as M1.

**This supersedes [D-091](../decisions/widgets-ui.md)**, which decided the opposite (*"a derived position
is re-derived by POLLING what it reads"*) and must be answered on its own terms, because its reasoning
was sound at the time:

- *"`Widget.c` is a public field the client and the user's own drag write directly — hooking
  `Widget.move` would put addon code in the client's hottest path."* **Correct, and we do not hook
  `move`.** `move()` is not a chokepoint anyway (`Window.java:147` writes `this.c` directly for the drag,
  as do `GameUI.java:1197/1476/1662`), so a tap there would be both hot *and* incomplete. Instead each
  **anchored target** gets its own engine listener via `Widget.listen`. That is not the hot path: it
  exists only on the handful of widgets an addon actually named as an anchor, it is the mechanism the
  layer already uses for input, and a client with no anchors registers none.
- *"`UI.scalef` is `static final`, read once at class load, so a scale change cannot be observed at
  runtime by anything — the option itself says requires restart."* **Still true, and it makes that
  trigger vacuous**: there is nothing to observe and therefore nothing to re-derive. It was never a
  reason to poll; it was a reason the third trigger does not exist.

What D-091 got right and this keeps: the derived set is **weak and holds only the widgets an anchor is
holding** — never the tree; a plain `pos` is never in it; a move made **through this API** stays
synchronous (applying a widget's layout re-derives whatever hangs off it before the call returns,
bounded by a depth limit); and an off-screen result still goes through `GameUI.fitwdg`'s own formula
(`UiApi.fitc`). Only the *trigger* changes: from every tick, to the events that actually move things.

**Re-deriving during a drag is correct, not a compromise.** A drag emits pointer events as the pointer
moves, so the anchored widget follows the target the way the user expects, and the work is proportional
to actual movement rather than to frames. `Layout.apply` already writes only when the answer changed, so
a drag that does not move an anchor's answer costs the fold and no writes.

## Decisions this feature records (D-178..D-182)

Written into `decisions/` by the task that first relies on each — **nothing here is left open for the
implementer.** The highest existing decision is D-177 (041).

- **D-178 — a change is announced at the MOMENT it happens, and the seam goes where the write LANDS,
  not where the message arrived.** The governing rule. Its sharpest case is the belt: the `uimsg`
  arrives, but two of five paths write `belt[]` from a `loader.defer` task afterwards, so the notify
  belongs inside that lambda. **Supersedes** the standing rule in `learnings/widget-tree-reads.md`
  (*"if a widget mutation is `loader.defer`-red, use `poll()`, not the uimsg tap"*).
  → `decisions/architecture-api.md`, first relied on by 042.1.
- **D-179 — the removal seam is `Widget.remove`, never `cdestroy`: a notification a subclass can skip
  is not a seam.** 9 of the 17 `cdestroy` overrides never call `super`, and they are exactly the parents
  this layer reads (`Bufflist`, `GameUI`, `ChatUI`, `QuestWnd`'s questbox, `WoundWnd`'s woundbox). The
  same fact makes `Widget.childseq` unusable as a change counter.
  → `decisions/widgets-ui.md`, first relied on by 042.1.
- **D-180 — a thing that FADES announces its end when the server said so, not when the animation
  unlinks it.** `Buff.reqdestroy` sets `dest` and starts a 0.35 s fade; `Window.reqdestroy` does the
  same with `animst = "dest"`. The unlink is the *animation ending*, not the *thing ending*, so the
  removal seam is the wrong signal for these two and the server's own message is the right one. The
  late unlink must not then produce a second event.
  → `decisions/widgets-ui.md`, first relied on by 042.2.
- **D-181 — a derived position re-derives on its INPUTS' own events; a per-target listener is not the
  hot path a global hook would be. SUPERSEDES D-091.** See M4 above for the full answer to D-091's
  reasoning: `move()` is never hooked (it is not a chokepoint anyway), size and screen ride one guarded
  tap on `Widget.resize`, the drag rides `Widget.listen` on the named target only, and the `UI.scalef`
  trigger is vacuous because a scale change requires a restart. D-091's weak derived set, its
  synchronous same-call re-derive and its `fitwdg` clamp are all kept.
  → `decisions/widgets-ui.md`, first relied on by 042.10; the D-091 entry gets a "superseded by D-181"
  line so a later reader is never handed the retired rule.
- **D-182 — a value that is still loading is WAITED ON, not re-read; and a build with no queue of its
  own is retried ONCE on the notify that its source landed.** `Loading implements Waitable`, so the
  client already answers *"tell me when this resolved"*. Where the notify covers the value (an
  `Indir<Resource>`), that is the whole answer. Where the value is *derived* and has no queue
  (`GItem.info()`'s rebuilt list), the notify says the source arrived and the build is retried once on
  it — still an event, still no loop. Where neither applies (a bare `new Loading(…)`, e.g.
  `GItem.sprite()`, which throws `UnwaitableEvent`), the value is **stated on the page as unresolvable**
  rather than chased by a hidden retry (D-092: a system's boundary is a decision on the page).
  → `decisions/architecture-api.md`, first relied on by 042.1.

## Files to create / modify

### `haven` core edits (all tagged `// addon:`)

| # | File | Change | Task |
|---|---|---|---|
| 1 | `src/haven/Widget.java` | **NEW SEAM** — `AddonManager.onWidgetRemoved(this)` as the last statement of `remove()` (:570) | 042.1 |
| 2 | `src/haven/GameUI.java` | **NEW SEAM** — `AddonManager.onBeltSet(slot)` inside the two `glob.loader.defer` lambdas in the `setbelt`/`setbelt2` block (:1374-1415) | 042.6 |
| 3 | `src/haven/Widget.java` | **NEW SEAM** — `AddonManager.onWidgetResized(this)` at the end of `resize(Coord)` (:1534), after the `Utils.eq` early return | 042.10 |
| — | `src/haven/AddonWidgets.java` | any new package-private read a task needs (e.g. a meter/study membership predicate) — **never reflection**, per D-017 | as needed |

**Exactly three core edits**, all one-line delegates into the hub, all tagged `// addon:`. Two of them
are in the same file and the third is in `GameUI`. Everything else is addon-layer. The drag half of M4
needs **no** edit at all — `Widget.listen` is a seam the engine already provides.

### New

| File | Purpose |
|---|---|
| `src/io/brodgar/addon/Resolve.java` | M2 — the `Waitable` wrapper: retry-on-notify, marshalling, owned `Waiting` cancellation, `UnwaitableEvent` refusal |

### Modified

| File | Change |
|---|---|
| `AddonManager.java` | `onWidgetRemoved`/`onBeltSet` delegates; the removal queue + its drain in `tick`; **delete** the poll stage calls (:398-433); correct the `onUimsg` javadoc |
| `CharApi.java` | `TreeAdapter` loses `poll()` from the interface; the six polling adapters rewritten onto placement/removal/resolution; `resetSession` unchanged in shape |
| `UiApi.java` | `pollReplaced`/`pollWidgetSubs`/`pollSelectorWatches` deleted; `sweepReplaced` driven by removal; `WidgetSubs` `Destroy`/`ItemAdded`/`ItemRemoved` driven by removal/placement |
| `WidgetSubs.java` | `poll(UI)` replaced by removal/placement entry points; the copy-on-write re-subscribe rule kept |
| `Layout.java` | `poll()` **and `redrive()`** deleted; `pending` onto uimsg, `pruneMoved` onto removal, each `derived` record instead holding the subscription that wakes it (M4) |
| `MapApi.java` | `pollMarkers` deleted; `MarkersChanged` fired from a notify at the `markerseq` bump, marshalled |
| `RenderApi.java` | `addToScene`'s `catch(Loading l)` registers on `l` via `Resolve`; `armPending` and `e.pending` deleted |
| `Addon.java` | the owned-resource registry gains the `Waiting` kind (cancel on reload/disable) |
| `decisions/architecture-api.md` | D-178, D-182 appended |
| `decisions/widgets-ui.md` | D-179, D-180, D-181 appended; **D-091 gets its "superseded by D-181" line** |
| `specs/codebase/addon-engine.md` | the seam table gains `onWidgetRemoved`/`onBeltSet`/`onWidgetResized` and the `Waitable` row — `/end` extends it |
| `specs/codebase/widgets.md` | the removal seam and the `cdestroy`-override finding — `/end` extends it |
| `learnings/widget-tree-reads.md` | amend the superseded `loader.defer` ⇒ `poll()` rule |
| `learnings/threading.md` | new entry: the `Waitable` idiom and its cancel/notify race |
| `docs/addons/api/event.md` | timing sentence only — no key or payload change |

## Risks & gotchas

Every task runs in a fresh context; each of these can bite any of them.

1. **`onUimsg` does NOT hold the UI monitor.** Its javadoc claims *"on a Loader thread under
   `synchronized(ui)`"*; [UI.java:731](src/haven/UI.java:731) closes the `synchronized(UI.this)` block
   at :730 and calls it at :732. Benign today only because `CharApi.dispatchUimsg` touches a
   `CopyOnWriteArrayList` and a concurrent set and nothing else. **The moment an adapter reads widget
   state from this tap, it races tick and draw.** Either take the monitor explicitly or keep the tap to
   marking and read on the tick. 042.1 corrects the javadoc.
2. **A removed buff is still a child for 0.35 s — `Buff.dest` is the only "gone" signal** (025.1).
   `Buff.reqdestroy` does not destroy: it sets the protected `dest` flag and starts a fade, so the
   removal seam (M1) fires **0.35 s late**. `BuffRemoved` must fire when `dest` is set, read through
   `AddonWidgets.buffDest(b)`. The same shape applies to `Window.reqdestroy`. **Do not assume M1 is the
   removal signal for a widget that fades.**
3. **Unbind ≠ unlink.** `removeid` runs before the unlink, so a closing window has
   `getwidget(id) != wdg` while `hasparent(root)` is still true and its reads still answer. Liveness
   needs the two-branch test — by id when server-bound, by reachability otherwise. `Layout.alive` and
   `WidgetSubs.live` already implement it; reuse, do not re-derive.
4. **A widget id is safe to send, unsafe to store** (D-138). `removeid` returns it to the pool and the
   server may reissue it for a different widget, so a stored id silently comes to mean something else.
5. **Buff identity is the WIDGET, never the resource name** (025.1) — the same res can be up twice, and
   `Bufflist` child order is arrival order, not sorted, not stable across a re-add.
6. **Seed the cache at `*Added` time** (027.2), or a bar that arrives filled fires
   `MeterChanged`-then-`MeterAdded`. Ordering alone does not buy this; the seed does.
7. **An event payload can be younger than its resource** (027.2): `:res()` may be `nil` at fire time
   while the `bg` is still `Loading`. That is existing, accepted behaviour — do not "fix" it by
   delaying the event.
8. **`Widget.add` does not route through `addchild`**, so a client-side add never reaches the placement
   seam. Everything the adapters watch is server-created; an addon's own widget is not.
9. **`SAttrWnd`/`SkillWnd` are built through `CharWnd.TabProxy`** — the `@RName` widget is the *proxy*,
   not the window. Locate via the public `CharWnd` field, never the RName.
10. **The cancel/notify race.** `Waiting.cancel()` and `wnotify()` can run on two threads at once, so a
    callback must re-check liveness (addon alive, entity not dead, widget still in the tree) *inside*
    the marshalled tick step, not at registration time.
11. **`Loading` is a control-flow exception** — any resource/gob/grid read can throw it. Swallow to
    nil/partial or register through `Resolve`; never let it escape into Lua.
12. **A queue whose handlers write back into it is drained one frame's worth, never to empty** (D-106).
13. **`ant hafen-client` is INCREMENTAL and can false-green** when a symbol moves between files. The
    close does `rm -rf build/classes` for a true compile check. Java changes need a **full client
    restart** — only Lua addon files reload with `:reload`.

## Discarded alternatives

- **Gate each poll on `hasSub` (the ROADMAP's own suggestion).** Makes the idle client free and leaves
  the *listening* client — the one the system exists for — paying a 144-slot diff every frame. It is a
  cheaper patch over the same shape, and the maintainer ruled it out explicitly.
- **Keep `poll()` as a fallback behind the seams.** Two paths to one event: every latency bug becomes
  unreproducible (the poll hides the missing notify one frame later) and the cost never actually goes.
  Rejected on the maintainer's "nada de fallbacks".
- **Tap `cdestroy` instead of `remove`.** 9 of 17 overrides skip `super.cdestroy`, and they are exactly
  the parents that matter. Would silently lose removals.
- **Use `Widget.added()` for placement.** Fires with a re-targeted parent for `Equipory` and the whole
  `GameUI.addchild` chain. `onWidgetPlaced` already exists and is correct.
- **Use `Widget.childseq` as a change counter.** Same `super.cdestroy` hole; it is bumped only in `add0`
  and the base `cdestroy`.
- **`Loading.waitfor(Indir)` / `queuewait` / `waitforint` (the blocking helpers).** They park the calling
  thread — on the UI thread that is a frozen client.
- **A per-adapter listener interface in `haven` (a `BeltListener`, an `EquipListener`, …).** One core
  edit per subsystem, ~100+ lines of boilerplate, against two taps that cover everything. Rejected as
  invasiveness the area rule does not buy (D-011 allows invasive where it *enables* — this enables
  nothing extra).
- **Deriving removal from the existing placement seam by diffing the tree on each placement.** Still a
  diff, just triggered differently, and it misses removals that come with no placement.

# 042-event-driven-reads — Spec

> Size limits lifted for this feature by the maintainer (2026-08-06): it is a load-bearing refactor and
> every task is executed in a **fresh context**, so spec/plan/tasks carry full detail rather than pointers.

## What & why

[`design/14`](../design/14-widget-tree-reads.md) closes with the sentence this feature exists to make
true: *"Each also gets its `*Changed`/`*Added` event so addons are event-driven, not polling."* Addons
got that. **The engine underneath never did.**

`AddonManager.tick` runs a **poll stage on every frame** that re-reads the widget tree, rebuilds a
snapshot, and diffs it against a cache in order to *synthesise* the events addons subscribe to. It is
the same shape the design set out to remove, moved one layer down and out of sight. Eleven call sites
do it, six of them `TreeAdapter`s. `ActionbarAdapter` alone walks all 144 belt slots and allocates a Lua
snapshot per occupied slot; `EquipAdapter`, `StudyAdapter` and `WoundAdapter` each rebuild a whole
snapshot list and deep-compare it — **whether or not any addon is loaded**, because `AddonManager.init`
registers the adapters and attaches the tick pump unconditionally, with no `addons.isEmpty()` guard
anywhere. This is the [ROADMAP](../ROADMAP.md) entry *"Per-frame cost of the addon layer's polling
suite"*, and the maintainer's instruction for it is explicit: **not a gate, not a staged migration — the
polling goes.**

**The rule this feature turns on: a change is announced at the moment it happens, and the moment is the
client's own** (D-178). There are exactly four such moments. The client already publishes all four; the
addon layer wires two of them and re-derives the rest per frame.

| Moment | The client's seam | Status in the addon layer |
|---|---|---|
| **The server pushes a value** | `UI.UiMessage.run` → `AddonManager.onUimsg(wdg, msg)` ([UI.java:732](src/haven/UI.java:732)) | **wired** — this is `refresh()`, already event-driven |
| **A widget enters or leaves the tree** | enter: `UI.AddWidget.run` → `onWidgetPlaced(id, wdg)` ([UI.java:494](src/haven/UI.java:494)). leave: **`Widget.remove()`** ([Widget.java:570](src/haven/Widget.java:570)) | enter **wired**; **leave has no seam** — core tap 1 (D-179) |
| **Geometry changes** — a widget resizes, a window packs itself, the screen changes | **`Widget.resize(Coord)`** ([Widget.java:1534](src/haven/Widget.java:1534)), which nearly every size change funnels through (16 of 17 overrides call `super.resize`) and which already early-returns on a no-op — `Window` is the one that does not and gets the same tap from a second call site ([Window.java:428](src/haven/Window.java:428)). Position under a hand-drag: **`Widget.listen`**, a zero-edit seam | **no seam** — core tap 3 (D-181, superseding D-091) |
| **A value the client is still loading arrives** | `Loading implements Waitable` ([Loading.java:32](src/haven/Loading.java:32)) → `waitfor(Runnable, Consumer<Waiting>)`, one-shot, cancellable via `Waiting.cancel()` ([Waitable.java:32](src/haven/Waitable.java:32)) | **exists in `haven`, used nowhere in `io.brodgar`** (D-182) |

**Six core edits total**, each a one-line delegate into the hub, each tagged `// addon:`:
`Widget.remove` (042.1), `Buff.reqdestroy` (042.2, D-180 — a fade has no other seam at the moment it
starts), the two `GameUI` deferred-belt lambdas (042.6), `Widget.resize` (042.10), `Window.reqdestroy`
(042.7, D-180's second consumer — the same fade problem, on the widget `widget:on("Destroy", fn)` watches),
`Window.resize` (042.10, D-179's own reasoning applied a second time — see below).
The plan originally budgeted three; 042.2 found that a widget which FADES (`dest = true`, then a 0.35s animation before the
real unlink) has no addon-visible signal at the moment the server actually said "gone" — no `uimsg`, no
existing seam — so `BuffRemoved` firing correctly (not 0.35s late) needed a fourth, reusing the same
`AddonManager.onWidgetRemoved` hub method M1 already established. 042.7 found the identical problem on
`Window` (`animst = "dest"` is the same shape as `Buff.dest`) and needed a fifth, the same reuse again.
042.10 found a sixth: **`Window.resize(Coord)` does not call `super.resize`** — it dispatches straight to
its own `resize2` (deco/chrome sizing) and never reaches `Widget.resize`'s body at all, so the geometry tap
placed there is silently skipped for exactly the widget M4's own acceptance criterion names ("a window
packing itself"). The same D-179 reasoning applies verbatim (*"a notification a subclass can skip is not a
seam"*) — of the seventeen classes that override `resize(Coord)`, sixteen call `super.resize(sz)` and reach
the tap that way; only `Window` (chrome sizing) and `Tabs` (a sub-tab strip, not a widget an addon's own
selector would realistically single out — no title, no res, no distinguishing key — left as a known,
narrow gap rather than a seventh edit) skip it outright. `Window.resize` gets the same one-line reused-hub
call, from a second call site, the same shape D-180 already established for removal.

So this is **not new machinery**. It is wiring the adapters to seams the client already has and then
**deleting the poll stage** — no gate, no fallback, no dual path, no "poll as a safety net". Cost stops
being proportional to *frames* and becomes proportional to *changes*; an idle client pays nothing
because there is no loop left to gate, and a listening client stops paying a 144-slot diff per frame for
a belt that changed twice this session.

### Why removal needs a core tap, and why it is `Widget.remove`

`Widget.remove()` runs `unlink()`, then `parent.cdestroy(this)`, then `parent = null`. `cdestroy` looks
like the seam and is not usable as one: **17 classes in `src/haven` override it and 9 of them never call
`super.cdestroy`** — `Bufflist`, `ChatUI`, `GameUI` itself, the `Hidepanel`/`Polities`/`Zergwnd`/craft/
`qq` inner classes, `QuestWnd`'s questbox and `WoundWnd`'s woundbox. Those are precisely the parents
this feature cares about, and it is also why `Widget.childseq` is **not** a usable change counter for
them. `Widget.remove()` itself is not overridden anywhere and runs on every path (`UI.DstWidget.run` →
`UI.destroy(Widget)` → `reqdestroy()` → `destroy()` → `remove()`, and any direct client call), so **one
tap there is override-proof and covers every removal in the client**.

## The complete poll inventory (what this feature deletes)

Every per-frame site in `AddonManager.tick`, why it polls today, and which moment replaces it. This
table is the feature's scope: when it is empty, the feature is done.

| # | Site | Polls because | Becomes |
|---|---|---|---|
| 1 | `CharApi` `MeterAdapter.poll` ([CharApi.java:186](src/io/brodgar/addon/CharApi.java:186)) | a meter appearing/disappearing is a child create at `GameUI` `place=="meter"` and a `meters.remove(w)` inside `GameUI.cdestroy` — no uimsg | **placement + removal** |
| 2 | `BuffsAdapter.poll` ([:278](src/io/brodgar/addon/CharApi.java:278)) | `Bufflist` has **no `uimsg` override at all**; add/remove is `addchild`/`cdestroy` ([Bufflist.java:77](src/haven/Bufflist.java:77)) | **placement + removal**, with the fade rule below |
| 3 | `StudyAdapter.poll` ([:348](src/io/brodgar/addon/CharApi.java:348)) | `SAttrWnd` has **no `uimsg` override at all**; the study inventory arrives via `addchild(place=="study")` ([SAttrWnd.java:255](src/haven/SAttrWnd.java:255)) and each slot's numbers resolve a beat later | **placement + removal + resolution** |
| 4 | `ActionbarAdapter.poll` ([:389](src/io/brodgar/addon/CharApi.java:389)) | `setbelt`/`setbelt2` **is** a uimsg on `GameUI`, but only *some* paths write `belt[]` synchronously (see below) | **uimsg** for the immediate paths + **one notify inside the deferred write** |
| 5 | `EquipAdapter.poll` ([:434](src/io/brodgar/addon/CharApi.java:434)) | `Equipory`'s only uimsg is `"pop"` (the avatar); equip/unequip is `addchild`/`cdestroy` of a `GItem` ([Equipory.java:149,166](src/haven/Equipory.java:149)) | **placement + removal**; item *data* already arrives as `GItem` uimsgs (`"num"`/`"chres"`/`"tt"`) that reach the existing tap |
| 6 | `WoundAdapter.poll` ([:585](src/io/brodgar/addon/CharApi.java:585)) | the wound *list* **is** a uimsg (`"wounds"` on `WoundWnd`), but a wound's **severity** comes from resource-published `ItemInfo` that streams in after the row | **uimsg + resolution** |
| 7 | `UiApi.pollReplaced` | the server destroying a window an addon replaced with `widget:replace(view)` | **removal** |
| 8 | `UiApi.pollWidgetSubs` | `w:on("ItemAdded"/"ItemRemoved")` is a `WItem` create/`cdestroy`; `w:on("Destroy")` is a removal | **placement + removal** |
| 9 | `UiApi.pollSelectorWatches` ([UiApi.java:938](src/io/brodgar/addon/UiApi.java:938)) | a `[title=]`/`[res=]` refiner cannot resolve at placement (the caption arrives by uimsg a tick later); `disappear` needs to see departures | **uimsg + removal** |
| 10 | `Layout.poll` ([Layout.java:468](src/io/brodgar/addon/Layout.java:468)) | three halves: the same late caption; pruning records whose widget has left; and `redrive`, a per-frame fold over `derived` because a target may have resized, packed itself or been dragged | **uimsg + removal + geometry**; `redrive` is deleted outright (D-181) |
| 11 | `MapApi.pollMarkers` ([MapApi.java:439](src/io/brodgar/addon/MapApi.java:439)) | `MapFile.markerseq` is bumped by the map DB's own processor thread (server `markobj`, addon `PMarker`, a segment merge re-keying) — nothing announces it | **a notify at the bump**, marshalled onto the tick |
| 12 | `RenderApi.armPending` ([RenderApi.java:604](src/io/brodgar/addon/RenderApi.java:604)) | a world entity whose ground has not streamed in is retried on every addon tick | **resolution** (`MCache.LoadingMap`/`Gob.DataLoading` are waitable) |

`FepAdapter`, `KinAdapter` and `QuestAdapter` already have **no `poll()`** — they are uimsg-driven and
are the proof the shape works. They change only where a task's premise touches them.

### The belt, precisely

`setbelt`/`setbelt2` mutate `GameUI.belt[]` in [GameUI.java:1374-1415](src/haven/GameUI.java:1374), and
**the write is deferred only on two of the five paths**:

| Path | Write |
|---|---|
| `setbelt` with no res arg (clear) | **immediate** — `belt[slot] = null` |
| `setbelt` with a res | **deferred** — `glob.loader.defer(() -> belt[slot] = mkbeltslot(slot, rdt), null)` |
| `setbelt2 "p"` (pagina by id) | **immediate** |
| `setbelt2 "r"` (pagina by res) | **deferred** — `glob.loader.defer(...)` |
| `setbelt2 "d"` (res + data) | **immediate** |

So the existing `onUimsg` tap fires *before* the two deferred writes land — which is exactly why the
adapter was made poll-driven. Three of five paths need no core edit at all; the two deferred ones need
the notify **inside the lambda, after the assignment**, which is the only place the change actually
happens.

## Acceptance criteria

Verified in-game by the maintainer, per `specs/testing/addon-suite.md`.

**Behaviour is preserved exactly.**
- [ ] Every event the poll stage used to synthesise still fires, with an **unchanged name, payload type
      and payload identity**: `MeterAdded`/`MeterRemoved`/`MeterChanged`, `BuffAdded`/`BuffRemoved`/
      `BuffChanged`, `StudyChanged`, `ActionbarChanged`, `EquipChanged`, `WoundChanged`, `KinChanged`,
      `FepChanged`, `MarkersChanged`, and the per-widget `ItemAdded`/`ItemRemoved`/`Destroy` keys.
- [ ] **No `hafen.*` surface change and no `docs/addons/api/` semantic change.** A page edit in this
      feature is a *timing* sentence at most. `hafen.event()`'s key list is untouched.
- [ ] A removal payload still answers its verbs after the fact (`buff:res()`, `meter:value()`) and
      reports `:exists()` false — the widget is unlinked, not cleared, exactly as today.

**Timing improves and never regresses.**
- [ ] Each event fires **in the frame the change happens**, not the frame after.
- [ ] Where a value arrives late (a study slot's numbers, a wound's severity, an equipped item's data,
      a world entity's ground), the event fires **when the `Waiting` resolves — once**, not on every
      frame until it does.
- [ ] A brand-new buff still surfaces as a single `BuffAdded` with its content already applied — never
      `BuffChanged` then `BuffAdded` (the ordering `refresh`-before-`poll` buys today).

**The removal edge cases hold.**
- [ ] A widget whose removal is a **fade** — `Buff.reqdestroy` ([Buff.java:190](src/haven/Buff.java:190)),
      `Window.reqdestroy` ([Window.java:609](src/haven/Window.java:609)) — reports removal **when the
      server said so** (the `Buff.dest` flag, reachable via `AddonWidgets.buffDest`), not when the
      animation ends. Unbind and unlink are not simultaneous and the feature must not conflate them.
- [ ] A **re-used widget id** never retargets a subscription or a cached entry (D-138): `removeid`
      returns the id to the pool and the server may reissue it for a different widget.
- [ ] A parent that skips `super.cdestroy` (9 of 17 overrides) loses no removal.

**The polling is gone, not gated.**
- [ ] All twelve rows of the inventory above are **deleted** as per-frame work — `pollTreeAdapters`,
      `pollReplaced`, `pollWidgetSubs`, `pollSelectorWatches`, `Layout.poll`, `pollMarkers` and
      `armPending`'s per-frame retry no longer exist. Proven by grep at the close, reported in the
      closing task.
- [ ] `TreeAdapter.poll()` is removed from the interface, not left as an unused default.
- [ ] `Layout.redrive` is **deleted** and an anchored widget still tracks its target: it follows a
      dragged target, re-derives once when a window packs itself, and re-derives once per game-window
      resize — while a HUD at rest does **zero** layout writes. D-091 carries its "superseded by D-181"
      line so no later reader is handed the retired rule.
- [ ] **Nothing fires when nothing changes**: a suite subscribing to every event above and idling for a
      stated interval counts **zero** events, and a cooling-down actionbar slot still fires none.

**Cost and lifetime.**
- [ ] Measured with `hafen.prof` (019), **before and after**, and reported as numbers in the closing
      task: the addon layer's per-frame cost (a) with no addon loaded, (b) with a subscriber to every
      event above. The "before" figure is taken on the current build, not estimated.
- [ ] Every `Waiting` and every engine listener the layer registers is an **owned resource** (P2):
      `:reload` and per-addon disable cancel them; a second `:reload` neither double-fires nor leaks a
      callback into a torn-down addon layer; a cancelled `Waiting` never fires.
- [ ] Nothing this feature adds touches Lua off the UI thread (P5). Every seam that runs on a Loader
      thread marshals onto the tick, as `gobEvents`/`overlayEvents` already do.

**Per task.**
- [ ] Each task ships its self-checking addon per `specs/testing/addon-suite.md`; its run is all `[pass]`
      (plus any `[manual]` line the maintainer confirms) and every prior suite still is.
- [ ] Each task **names**, in its own spec section, which of its reads is waitable, which rides a
      retry-on-notify, and which is neither — see the boundary below.

## Out of scope

- **Any `hafen.*` surface change.** This feature changes *when and how* an event is produced, never its
  name, payload or page.
- **Continuous values with no moment** — actionbar cooldown, a meter draining, durability drift, a
  tooltip's quality. These have no discrete change to listen for and stay live reads (`slot:cooldown()`),
  exactly as today. `ActionbarAdapter`'s deliberate exclusion of `cooldown` from its change key is kept
  for the same reason it exists: it would otherwise fire every frame while an ability cools down.
- **Unwaitable loads — the feature's stated boundary (D-092).** `Waitable` covers `Indir<Resource>` from
  `Resource.Pool` ([Resource.java:431](src/haven/Resource.java:431)) and `Session.getresv`
  ([Session.java:114](src/haven/Session.java:114)), plus `Defer`, `Future`, `Gob.DataLoading`,
  `MCache.LoadingMap` and `Timeout`. It does **not** cover a bare `new Loading("…")` — e.g.
  `GItem.sprite()` — which throws `Loading.UnwaitableEvent` if you register on it; nor does *derived*
  state have a queue of its own: `GItem.info()` rebuilds a `List<ItemInfo>` and nothing notifies when
  the built list is ready. The notify says **the resource landed**; the build is then retried once, on
  that notify. Each task states which of its reads is which. A value that is neither waitable nor
  reachable by retry-on-notify is **written on the page as a decision**, never left to a hidden loop.
- **The client's own screen-size comparison.** `UILoop.Frame.tick` compares `ui.root.sz` with the OS
  window size every iteration and calls `ui.root.resize(sz)`. That poll is `UILoop`'s and this layer
  does not own it — but the addon layer no longer *adds* a poll of its own on top: it hears the
  resulting `ui.root.resize(sz)` through core tap 3 like any other resize (D-181).
- **UI scale changes.** `UI.scalef` is `static final`, read once at class load, so a scale change cannot
  be observed at runtime by anything and the option itself says *requires restart*. There is nothing to
  listen for and nothing to re-derive — the trigger does not exist (this is one of the three D-091
  listed, and the reason it never justified a poll).
- **Everything else in the ROADMAP**: `dependencies`/`optional_dependencies`, the `hafen.render`/
  `hafen.ghost` relocation under `hafen.world`, L4 method replacement, allocation profiling.
- **`hafen.hook` era leftovers.** 041 removed them; nothing here revives a hook path.

## Known hazards this feature must not walk into

Stated here because every task runs in a fresh context and each one can be bitten by these.

1. **`onUimsg` does NOT hold the UI monitor.** `AddonManager.onUimsg`'s javadoc says it is called *"on a
   Loader thread under `synchronized(ui)`"* — [UI.java:731-732](src/haven/UI.java:731) calls it **after**
   the `synchronized(UI.this)` block closes. It is benign today only because `CharApi.dispatchUimsg`
   touches nothing but a `CopyOnWriteArrayList` and a concurrent set. **The moment this feature makes
   the tap read widget state, that read races tick and draw.** The doc is wrong and must be corrected by
   the task that first relies on this seam.
2. **`onWidgetPlaced` is reliable; `added()` is not.** `onWidgetPlaced(id, wdg)` fires at
   [UI.java:494](src/haven/UI.java:494) *after* `pwdg.addchild(...)`, so the child is in the tree.
   `Widget.added()` fires inside the private `add0`, and many `addchild` overrides re-target `add()` to a
   *different* widget (`Equipory`, the whole `GameUI.addchild` `place ==` chain), so `added()` can fire
   with a parent the server never named. Use the placement seam, not `added()`.
3. **`Widget.add` does not route through `addchild`** — a client-side add never reaches the placement
   seam. Everything this feature listens for is server-created, but an addon's own widget is not.
4. **Unbind ≠ unlink.** For a `Window`, `removeid` runs first and the unlink is deferred to the end of a
   fade, so a closing window has `getwidget(id) != wdg` while `hasparent(root)` is still true and its
   reads still answer. Liveness needs the two-branch test (by id when server-bound, by reachability
   otherwise).
5. **A queue whose handlers write back into it is drained one frame's worth, never to empty** (D-106,
   from 038.3). Any new marshalled queue here inherits that rule.

## Context files

The feature's whole context budget. `/implement` loads nothing else unless a task names it.

**Design and prior art**
- [`design/14-widget-tree-reads.md`](../design/14-widget-tree-reads.md) — the design this finally
  implements; its "Update hook → semantic events" section is the intended shape
- [`design/04-engine.md`](../design/04-engine.md) — the tick order the poll stage sits in
- [`003-widget-tree-reads/`](../003-widget-tree-reads/) — the feature that introduced the adapter
  mechanism *and* the poll; its reasoning is what this one revisits
- [`041-unified-events/`](../041-unified-events/) — `Subs`/`WidgetSubs`/`hasSub` and the `LuaEvent`
  payload shapes every event here still carries
- [`038-gob-overlays/`](../038-gob-overlays/) — the prior art for marshalling an off-thread change onto
  the tick (`gobEvents`/`overlayEvents`), D-100 (the state belongs on the thing) and D-106 (drain one
  frame's worth)

**The seams**
- `src/haven/Waitable.java` — `waitfor`/`Waiting.cancel`/`Queue.wnotify`/`Disjunction`/`Checker`
- `src/haven/Loading.java` — `implements Waitable`; the blocking `Loading.waitfor(...)` helpers are
  **not** usable on the UI thread; `UnwaitableEvent`
- `src/haven/Widget.java` — `remove` (:570), `destroy` (:586), `cdestroy` (:594), `add0` (:272),
  `added` (:317), `listen`/`deafen` (:916)
- `src/haven/UI.java` — `AddWidget.run` (:485, the placement seam at :494), `UiMessage.run` (:715, the
  uimsg tap at :732 and the monitor boundary at :730), `destroy` (:621), `DstWidget.run` (:651),
  `CommandQueue.execute` (:272, why this is a Loader thread)
- `src/haven/GameUI.java` — `belt` (:71), the `setbelt`/`setbelt2` block (:1374-1415), `addchild`'s
  `place ==` chain (:910+), `cdestroy` (:1139)
- `src/haven/AddonWidgets.java` — the `haven`-package read shim the layer owns (`hudMeters`,
  `buffDest`, the MCache reads); a new package-private read belongs here, not in reflection
- `src/haven/Bufflist.java` (:77-84), `src/haven/Equipory.java` (:149-181),
  `src/haven/SAttrWnd.java` (:255), `src/haven/WoundWnd.java` (:361-411) — the four parents whose
  change vector is child create/destroy

**Where the code lands**
- `src/io/brodgar/addon/CharApi.java` — the nine adapters and `TreeAdapter`
- `src/io/brodgar/addon/AddonManager.java` — `tick` (:316), the named seams (`onUimsg` :597,
  `onWidgetPlaced`), the `fire*` dispatchers (:927-1157), `gobEvents` as the marshalling pattern
- `src/io/brodgar/addon/UiApi.java`, `Layout.java`, `MapApi.java`, `RenderApi.java` — the other polls
- `src/io/brodgar/addon/Subs.java` — `has(key)`, and why a gate is no longer the answer
- `src/io/brodgar/addon/Addon.java` — the owned-resource registry every `Waiting` must join

**Codebase coverage**
- [`specs/codebase/widgets.md`](../codebase/widgets.md) — the destroy gotcha, the re-used id, that
  `Widget.add` does not route through `addchild`
- [`specs/codebase/boot-and-loop.md`](../codebase/boot-and-loop.md) — the `Loading` protocol, the UI
  monitor, which thread runs what
- [`specs/codebase/addon-engine.md`](../codebase/addon-engine.md) — the current seam inventory; this
  feature adds a row to it and `/end` must extend the file
- [`specs/codebase/services.md`](../codebase/services.md) — buffs, vitals, study, wounds, kin as
  subsystems (open only the entries a task needs)

**Docs tier (read before writing, per `AREA.md`)**
- `specs/standards/docs.md` §9-§12 and `grep "^### D-" specs/decisions/docs-standard.md`
- `docs/addons/api/event.md` — the event surface this preserves; any timing sentence lands here

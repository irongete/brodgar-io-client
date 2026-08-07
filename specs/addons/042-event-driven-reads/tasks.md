# 042-event-driven-reads — Tasks

> Size limits lifted by the maintainer (2026-08-06). **Every task is executed in a fresh context**, so
> each carries its own seam, anchors, gotchas and suite contract in full. Read `spec.md` + `plan.md`
> first; then only the files this task names.
>
> **Every task ships `addons/042-event-driven-reads.<X>/` run as `:t042-<X>`** per
> `specs/addons/TESTING.md`: read-only (never declare `permissions`), never auto-starts, never mutates
> persistent state, ≤15 lines of output, and **stands alone** — where its proof rests on something an
> earlier suite also checks, it duplicates the assertion rather than saying "also run `:t042-N`".
>
> **Two assertions every suite in this feature repeats**, because they are the feature's whole claim:
> (a) **idle ⇒ silence** — subscribe to this task's events, idle a stated interval, count **zero**;
> (b) **one change ⇒ exactly one event** — after the `[manual]` action, the count is exactly 1, not 0
> and not N. Both are cheap and both fail loudly if a poll survives or a notify double-fires.
>
> **Java changes need `ant hafen-client` → `BUILD SUCCESSFUL` and a FULL CLIENT RESTART.** Only Lua
> addon files reload with `:reload`.

---

- [x] **042.1 — the two mechanisms, with the meters as their consumer.**
      Ships M1 (the removal seam) and M2 (`Resolve`) together with the first adapter ported, per D-108:
      *a mechanism built for later tasks ships with a consumer or it ships unproven.*
      **Core edit (1):** `AddonManager.onWidgetRemoved(this)` as the last statement of
      [`Widget.remove()`](src/haven/Widget.java:570), tagged `// addon:`. Placement is after
      `unlink()`/`cdestroy`/`parent = null`/`ui.removed(this)` so a handler sees the settled tree —
      the mirror of `onWidgetPlaced`, which fires after the child is in.
      **New:** `src/io/brodgar/addon/Resolve.java` — wrap `Waitable.waitfor(Runnable, Consumer<Waiting>)`
      with (i) retry-on-notify that re-registers when the retry throws a *different* `Loading`, bounded
      by a stated maximum then left unresolved (never a fallback poll), (ii) marshalling onto the tick
      drain — `wnotify()` runs on whatever thread finished the load, so no Lua off the UI thread (P5),
      (iii) the `Waiting` registered in the owning `Addon`'s resource registry so `:reload`/disable
      `cancel()` it, (iv) `catch(Loading.UnwaitableEvent)` reporting "not waitable" to the caller
      instead of throwing.
      **Port:** `MeterAdapter` ([CharApi.java:166](src/io/brodgar/addon/CharApi.java:166)) — `poll()`
      goes. `MeterAdded` fires from the placement seam for a `place=="meter"` child; `MeterRemoved` from
      M1. `refresh()` (the `"set"`/`"col"` uimsg path) is already event-driven and **stays as is**.
      **Also:** correct the `AddonManager.onUimsg` javadoc — it claims `synchronized(ui)` and
      [UI.java:731](src/haven/UI.java:731) closes that block at :730 (gotcha 1).
      **Gotchas:** seed the cache with the meter's current segments at the moment `MeterAdded` fires
      (027.2 — the seed, not the ordering, is what prevents `MeterChanged`-then-`MeterAdded`); fire
      `MeterRemoved` **before** dropping the map entry (025.2); keep reading membership through
      `AddonWidgets.hudMeters(GameUI)` — the one scan shared with `hafen.meter()`, so list and events
      can never disagree; colour is state, so the `"col"` diff stays (027.1); `:res()` may be `nil` at
      fire time and that is accepted, do not delay the event to "fix" it.
      **Suite proves:** `Resolve` cancels (a registered `Waiting` never fires after `:reload`);
      `MeterAdded`/`MeterRemoved`/`MeterChanged` still fire with a Meter payload answering
      `:value()`/`:color()`/`:segments()`; a removed meter's payload still answers its verbs and reports
      `:exists()` false; idle ⇒ silence; `[manual]` — take a hit / drink so a bar changes, expect exactly
      one `MeterChanged` per real change and no repeats while the bar sits still.
      <!-- extra context: `src/haven/Widget.java` (:570 remove, :586 destroy, :594 cdestroy), `src/haven/Waitable.java`, `src/haven/Loading.java`, `src/haven/AddonWidgets.java` (hudMeters), `src/io/brodgar/addon/LuaMeter.java`, `src/io/brodgar/addon/Addon.java` (owned-resource registry) -->

- [x] **042.2 — buffs, and the fade that is not a removal.**
      `BuffsAdapter.poll()` ([CharApi.java:278](src/io/brodgar/addon/CharApi.java:278)) goes.
      `Bufflist` has **no `uimsg` override at all** ([Bufflist.java:77](src/haven/Bufflist.java:77)):
      add is `addchild`, so `BuffAdded` comes from the placement seam.
      **The trap this task exists for:** `BuffRemoved` must **not** come from M1.
      `Buff.reqdestroy` ([Buff.java:190](src/haven/Buff.java:190)) does not destroy — it sets the
      protected `dest` flag and starts a **0.35 s fade**, so the widget is still a child and M1 would
      fire a third of a second late (025.1). The "gone" signal is `dest`, read through
      `AddonWidgets.buffDest(b)`. Fire on the message that sets it; keep M1 only as the *late* unlink,
      which must **not** produce a second `BuffRemoved`.
      `refresh()` (the `"ch"`/`"tt"` uimsg path → `BuffChanged`) is already event-driven and stays.
      **Gotchas:** buff identity is the **widget**, never the res name — the same res can be up twice and
      `Bufflist` order is arrival order, not stable across a re-add (025.1); keep the single
      `LuaBuff.actives()` predicate shared with `hafen.buff():list()`; a brand-new buff must surface as
      **one** `BuffAdded` with its `"tt"` content already applied, which means seeding at add time;
      `Bufflist.cdestroy` is one of the 9 that skip `super`, which is why M1 taps `remove`.
      **Suite proves:** exactly one `BuffAdded` per buff and one `BuffRemoved` per expiry, never two;
      the removal is announced at `dest`, not 0.35 s later — assert by timing the gap between the
      `[manual]` observation and the event, or by asserting `:exists()` is already false while the
      widget is still drawn; a `BuffRemoved` payload still answers `:res()`/`:name()`; idle ⇒ silence.
      `[manual]` — eat something that grants a buff, then let it expire.
      <!-- extra context: `src/haven/Bufflist.java`, `src/haven/Buff.java` (:190 reqdestroy, the dest flag), `src/haven/AddonWidgets.java` (buffDest), `src/io/brodgar/addon/LuaBuff.java` (actives/snapshot), `025-buffs-oop/` -->

- [x] **042.3 — equipment.**
      `EquipAdapter.poll()` ([CharApi.java:434](src/io/brodgar/addon/CharApi.java:434)) goes.
      `Equipory`'s **only** uimsg is `"pop"` (the paperdoll avatar) — equip/unequip is a `GItem`
      `addchild`/`cdestroy` ([Equipory.java:149,166](src/haven/Equipory.java:149)). So structure comes
      from placement + M1. Item *data* (`"num"`, `"chres"`, `"tt"`) arrives as uimsgs **on the child
      `GItem`** and already reaches the existing tap — wire `EquipChanged` to those rather than
      re-reading per frame.
      **Gotchas:** `Equipory.cdestroy` *does* call `super`, but M1 is still the seam (uniformity, and it
      is the one that cannot be skipped); the change key excludes `wear` and quality deliberately (a
      durability drifting down and a tooltip resolving are not equipment changes) — keep that exclusion
      or the event fires constantly; positional compare is safe because `Equipory` child order is
      creation-order and stable between equips; `GItem.info()` is **derived** state with no queue of its
      own — the `Resolve` notify says the underlying resource landed and the build is retried once, per
      the spec's stated boundary; `GItem.sprite()` throws a **bare** `Loading` and is *not* waitable.
      **Suite proves:** one `EquipChanged` per equip and one per unequip, never a stream; the payload is
      the Item objects `hafen.ui():equipment():items()` hands back and a stashed payload still answers
      after the gear comes off; no event while wearing gear whose durability is ticking down; idle ⇒
      silence. `[manual]` — equip and unequip one item.
      <!-- extra context: `src/haven/Equipory.java`, `src/haven/GItem.java` (:201-255 info/uimsg/infoseq, :223 the bare Loading in sprite()), `src/io/brodgar/addon/LuaItem.java` -->

- [x] **042.4 — study.**
      `StudyAdapter.poll()` ([CharApi.java:348](src/io/brodgar/addon/CharApi.java:348)) goes.
      `SAttrWnd` has **no `uimsg` override at all**; the study inventory arrives via
      `addchild(place=="study")` ([SAttrWnd.java:255](src/haven/SAttrWnd.java:255)) and each slot is a
      `GItem` child whose `Curiosity` numbers resolve a beat later. So: placement + M1 for the slots,
      **`Resolve` for the numbers** — this is the task that proves M2 on real streaming data.
      **Gotchas:** locate via the public `CharWnd.sattr` field, **never** the `@RName` — `SAttrWnd` is
      built through a `CharWnd.TabProxy` and the RName widget is the *proxy*, not the window; reach the
      study inventory as `sattr.children(SAttrWnd.StudyInfo.class)` → `StudyInfo.study`; a slot can hold
      an item with **no `Curiosity` info** at all (a Hearth-Magic bond) — `ItemInfo.find` returns null
      and the snapshot is `{res,name}` only, which is correct and must not be "fixed" by waiting
      forever; while the sattr tab has never been opened there is nothing to read and nothing must fire.
      **Suite proves:** `StudyChanged` fires when a curiosity is placed and when one finishes, once each;
      it fires **again** when a slot's numbers resolve, and **only once** for that resolution (the old
      `0→1→3` streaming at login must not become a per-frame stream); a `Curiosity`-less item is reported
      with `{res,name}` and adds 0 to the summary; idle with the tab open ⇒ silence.
      `[manual]` — open Study, place a curiosity, close and reopen the tab.
      <!-- extra context: `src/haven/SAttrWnd.java` (:255 addchild, StudyInfo), `src/haven/CharWnd.java` (the sattr field, TabProxy), `src/haven/resutil/Curiosity.java`, `src/io/brodgar/addon/LuaStudySlot.java` -->

- [x] **042.5 — wounds.**
      `WoundAdapter.poll()` ([CharApi.java:585](src/io/brodgar/addon/CharApi.java:585)) goes.
      The wound **list** already is a uimsg (`"wounds"` on `WoundWnd`,
      [WoundWnd.java:399](src/haven/WoundWnd.java:399) → `decwound`) — so `interested()` becomes true for
      it instead of returning false, and `refresh()` does the work. A wound's **severity** comes from
      resource-published `ItemInfo` that streams in after the row, which is the `Resolve` half.
      **Gotchas:** `severity` must stay in the change key or a wound *worsening* never fires; `WoundWnd`'s
      woundbox `cdestroy` is one of the 9 that skip `super`; while the Health & Wounds tab has never been
      opened, nothing must fire; the detail pane is a separate child create (`place=="wound"`).
      **Suite proves:** `WoundChanged` fires on an add, on a heal, and on a severity resolving nil→value,
      once each; it does **not** fire repeatedly while a wound sits unchanged; the payload is the Wound
      objects `hafen.wound():list()` hands back; idle ⇒ silence.
      `[manual]` — take a wound (or open the tab with one present) and let it heal a step.
      <!-- extra context: `src/haven/WoundWnd.java` (:361 cdestroy, :370 addchild, :381-411 decwound/uimsg), `src/io/brodgar/addon/LuaWound.java` -->

- [ ] **042.6 — the action bar, and the notify where the write lands.**
      `ActionbarAdapter.poll()` ([CharApi.java:389](src/io/brodgar/addon/CharApi.java:389)) goes — the
      144-slot per-frame walk this feature's headline number comes from.
      **Three of the five `setbelt`/`setbelt2` paths already write `belt[]` synchronously**
      ([GameUI.java:1374-1415](src/haven/GameUI.java:1374)): `setbelt`-clear, `setbelt2 "p"`,
      `setbelt2 "d"`. The existing uimsg tap fires after those — no edit needed, just make
      `interested()` true for `setbelt`/`setbelt2` and read in `refresh()`.
      **Core edit (2):** the other two — `setbelt`-with-res and `setbelt2 "r"` — defer via
      `glob.loader.defer(...)`. Add `AddonManager.onBeltSet(slot)` **inside each lambda, immediately
      after the `belt[slot] = …` assignment**, tagged `// addon:`. That is the only place the change
      happens.
      **Amend the learnings:** `learnings/widget-tree-reads.md` records the rule *"if a widget mutation
      is `loader.defer`-red, use `poll()`, not the uimsg tap."* This task supersedes it with *put the
      notify where the write lands* — amend the entry, do not leave it contradicting the code.
      **Gotchas:** `onBeltSet` runs on a **Loader thread** — enqueue, never touch Lua (P5);
      `cooldown` stays out of the change key (a live meter would fire every frame while an ability
      cools) and stays a live read on `slot:cooldown()`; a login sets many slots in a burst, so one
      event per slot is correct and expected; `belt` is field-initialised so the array is never null,
      but its elements are.
      **Suite proves:** setting a slot fires exactly one `ActionbarChanged` for that slot index; clearing
      one fires exactly one; **no event fires while an ability is cooling down** (the regression this
      task could most easily introduce), while `slot:cooldown()` still reads live and non-nil during it;
      idle ⇒ silence. `[manual]` — drag an action onto a belt slot, use it (watch the cooldown), then
      clear the slot.
      <!-- extra context: `src/haven/GameUI.java` (:71 belt, :180-186 use/keyact, :1374-1415 setbelt/setbelt2), `src/io/brodgar/addon/LuaSlot.java`, `specs/addons/learnings/widget-tree-reads.md` (the rule to amend) -->

- [ ] **042.7 — the per-widget container keys.**
      `UiApi.pollWidgetSubs` and `WidgetSubs.poll(UI)` go. Three keys move:
      `w:on("Destroy", fn)` → M1; `w:on("ItemAdded"/"ItemRemoved", fn)` → placement + M1 on the `WItem`
      children (the same create/`cdestroy` shape as buffs and equipment).
      **Gotchas:** `WidgetSubs.live(u)` is the two-branch liveness test (by id when server-bound, by
      reachability otherwise) — reuse it, do not re-derive; `listening` is copy-on-write **by design**,
      and `WidgetSubs` relies on being able to swap one engine listener for a fresh one from inside
      `handle(Event)` without racing the dispatch that triggered the swap — preserve that; a firing
      `Destroy`/`ItemAdded` handler may (un)subscribe re-entrantly, which is why `polling` is
      copy-on-write today; the `hasSub` gate stays meaningful here (a widget nobody listens to registers
      no seam interest at all, which is now free rather than merely skipped).
      **Suite proves:** `Destroy` fires exactly once per widget destroyed, including for a **`Window`,
      whose removal is a fade** — and at the right moment, not a third of a second late;
      `ItemAdded`/`ItemRemoved` fire once per item entering/leaving a container; a handler that
      unsubscribes from inside its own fire does not break the dispatch; idle ⇒ silence.
      `[manual]` — open a container, move one item in and out, then close the container window.
      <!-- extra context: `src/io/brodgar/addon/WidgetSubs.java`, `src/io/brodgar/addon/UiApi.java` (pollWidgetSubs, the polling list), `src/io/brodgar/addon/LuaWidget.java`, `src/haven/Inventory.java` (:100-116 addchild/cdestroy), `041-unified-events/` -->

- [ ] **042.8 — replacements.**
      `UiApi.pollReplaced`/`sweepReplaced` go. The thing being watched for is *the server destroying a
      window an addon replaced with `widget:replace(view)`* — a removal, so M1 is the whole answer.
      **Gotchas:** the `LuaWidget.anyHidden` fast-path flag exists to keep the sweep free when nothing is
      replaced; with M1 the flag is no longer a gate on a loop but may still be the cheap "is any of this
      live" test — keep or delete it deliberately, do not leave it as a vestige; the `:lua` REPL
      (`consoleOwner`) replaces windows too and owns them the same way, so it must be covered by the same
      path, not forgotten (the current `pollReplaced` sweeps it explicitly); replacement ends must restore
      the window **and its toggle** under the one rule (031/032), and the stand-in view is destroyed with
      it — that behaviour is unchanged and must be re-proven, not assumed.
      **Suite proves:** a replaced window destroyed by the server ends the substitution once, restores the
      native window and its toggle, and destroys the stand-in; a replacement made from the `:lua` REPL
      behaves identically; idle ⇒ silence.
      `[manual]` — replace a window, then have the server close it (or close it natively).
      <!-- extra context: `src/io/brodgar/addon/UiApi.java` (pollReplaced/sweepReplaced), `src/io/brodgar/addon/LuaWidget.java` (anyHidden), `032-replace-verb/`, `031-window-lifecycle/` -->

- [ ] **042.9 — selector watches.**
      `UiApi.pollSelectorWatches` ([UiApi.java:938](src/io/brodgar/addon/UiApi.java:938)) goes. Two
      halves: the bounded re-check for a `[title=]`/`[res=]` refiner that could not resolve at placement
      (**the caption arrives as a uimsg a tick later — the existing tap sees it**), and `disappear` for a
      tracked widget that has left the tree (**M1**).
      **Gotchas:** the refiner cannot resolve at placement time by construction, so the fix is to *listen
      for the caption*, not to re-check on a countdown; `Selector` is pure (no Lua, no state) and the
      events and the inspector share it — keep that; a widget may be matched by two addons and each keeps
      its own record.
      **Suite proves:** a selector with a `[title=]` refiner fires `appear` once, for the right widget,
      once the caption has arrived — and **not** before, and not twice; `disappear` fires once when the
      widget leaves; a selector that never matches fires nothing; idle ⇒ silence.
      `[manual]` — open a window whose caption the selector matches, then close it.
      <!-- extra context: `src/io/brodgar/addon/UiApi.java` (pollSelectorWatches, the watch records), `src/io/brodgar/addon/Selector.java`, `030-ui-selectors/` -->

- [ ] **042.10 — the layout cascade: `redrive` is deleted, and D-091 is superseded.**
      `Layout.poll()` ([Layout.java:468](src/io/brodgar/addon/Layout.java:468)) is **three halves**, all
      three decided (plan.md §M4, D-181 — **this task carries no open question**):
      (a) `pending` — the late `[title=]`/`[res=]` caption → **uimsg**, same as 042.9;
      (b) `LuaWidget.pruneMoved(u)` — records of departed widgets → **M1**;
      (c) `redrive(u)` — **deleted.** An anchored widget re-derives on its inputs' own events.
      **Core edit (3):** `AddonManager.onWidgetResized(this)` at the end of
      [`Widget.resize(Coord)`](src/haven/Widget.java:1534), **after** the `Utils.eq` early return (so it
      never fires on a no-op) and after the `presize()` cascade and `parent.cresize` (so a handler reads
      settled geometry). One tap covers **all three** size inputs: an anchor target resizing, a window
      packing itself (`pack()` → `resize(contentsz())`), and the screen changing (`UILoop` calls
      `ui.root.resize(sz)` — the root is just another resize).
      **The drag needs no core edit.** Register a `Widget.listen` listener on each **anchored target** —
      the zero-edit engine seam `WidgetSubs` already uses (041.3). It exists only on the handful of
      widgets an addon actually named as an anchor, so it is not the hot path D-091 rightly refused to
      put a `Widget.move` hook in. Re-deriving on each pointer move **during** the drag is the intended
      behaviour, not a compromise: the anchored widget follows the target, and the work is proportional
      to movement rather than to frames.
      **Record D-181 in `decisions/widgets-ui.md` and add the "superseded by D-181" line to D-091**, so
      a later reader is never handed the retired rule. D-091's reasoning is answered on its own terms in
      plan.md §M4 — quote it, do not paraphrase it away.
      **Keep everything D-091 got right:** the `derived` set stays **weak** and holds only the widgets an
      anchor is holding — **never the tree**; a plain `pos` is never in it; a move made *through this
      API* stays **synchronous** (applying a widget's layout re-derives whatever hangs off it before the
      call returns, bounded by a depth limit, not a visited set); an off-screen result still goes through
      `GameUI.fitwdg`'s own formula (`UiApi.fitc`), so "is this on screen" keeps one answer.
      **Gotchas:** `Layout.alive` is the two-branch liveness test — reuse it; `apply` writes only when
      the answer actually changed, so a HUD already where the cascade wants it does no writes — preserve
      that; layout is a **write**, not something read at the draw (036.2), so none of this may become
      draw-time work; a listener on a target must be an **owned resource** cancelled with the anchor
      (P2), or a dropped anchor leaks a listener onto a native widget that outlives `:reload`
      (`listen`/`deafen` are per-instance and nothing else will remove it).
      **Suite proves:** an anchored widget lands correctly when its rule's `[title=]` resolves late; its
      record **and its target listener** are dropped when it leaves the tree; a window that re-packs
      itself re-derives once; resizing the game window re-derives once per resize, not per frame; a HUD
      at rest does **zero** layout writes; a `:reload` with an anchor live leaves no listener behind;
      idle ⇒ silence. `[manual]` — open an anchored window, drag its target, resize the game window.
      <!-- extra context: `src/io/brodgar/addon/Layout.java` (:460-510 poll/redrive/alive/apply), `src/io/brodgar/addon/LuaWidget.java` (anyMoved/pruneMoved), `src/haven/Widget.java` (:1530 move, :1534 resize, :1563 cresize, :916 listen/deafen), `src/haven/Window.java` (:147 the drag writing c), `src/io/brodgar/addon/WidgetSubs.java` (the listen idiom), `specs/addons/decisions/widgets-ui.md` (D-091, to supersede), `036-ui-layout/` -->

- [ ] **042.11 — map markers.**
      `MapApi.pollMarkers` ([MapApi.java:439](src/io/brodgar/addon/MapApi.java:439)) goes. It watches
      `MapFile.markerseq`, which is bumped by the map DB's own processor thread — the server pushing
      `SMarkers` via `markobj`, an addon adding a `PMarker`, and a **segment merge re-keying** them.
      Nothing announces the bump, so this row needs **a notify at the bump**, marshalled onto the tick.
      **Gotchas:** the map DB has **one RW lock and its own processor thread** — the notify must not be
      raised while holding the write lock in a way that lets a handler re-enter and deadlock; the current
      code takes the *read* lock only to size `markers`, and the payload is the **count itself**, not a
      `{count = n}` wrapper (041.1) — keep it; `markersPrimed` exists so the initial set is read via
      `markers:list()` and does not arrive as a change — an event-driven version still must not announce
      the initial load; a merge re-bases marker ids (D-096), so a count change and an identity change are
      not the same thing.
      **Suite proves:** `MarkersChanged` fires once per marker added and once per removed, carrying the
      new count as a plain number; the initial set at login fires **nothing**; idle ⇒ silence.
      `[manual]` — place a map marker and delete it.
      <!-- extra context: `src/io/brodgar/addon/MapApi.java` (:439 pollMarkers, mapfile()), `src/haven/MapFile.java` (markerseq, the lock, the processor thread), `specs/codebase/mapfile.md`, `037-map-database/` -->

- [ ] **042.12 — world entities whose ground has not arrived.**
      `RenderApi.armPending` ([RenderApi.java:604](src/io/brodgar/addon/RenderApi.java:604)) and the
      `e.pending` flag go. This is the cleanest `Resolve` case in the feature: `addToScene` already
      does `catch(Loading l)` — **`l` is the `Waitable`**. Register on it and drop the retry loop:

      ```java
      try { e.slot = mv.addClientGob(e.gob); }
      catch(Loading l) { Resolve.on(l, owner, () -> retryAdd(e)); }
      ```

      **Gotchas:** the retry may throw a *different* `Loading` (a further tile) — `Resolve`'s
      re-registration is what covers that, and it is bounded; the current `armPending` catches
      `RuntimeException` broadly and silently, which hides real errors — do not carry that forward
      unexamined; the caller holds the entity monitor in `addToScene`, so registration must not invoke
      the callback inline while holding it (`Resource.Loading.waitfor` **does** run the callback inline
      when the resource is already done); an entity that is `dead`/`hidden`/detached must not be added
      when its notify finally arrives; a place whose ground never arrives simply stays out of the scene,
      which is the correct answer for an addon asking for a place it cannot see.
      **Suite proves:** a world entity created over ground that is not streamed in is added **exactly
      once** when the ground arrives, and is not added at all if it is destroyed first; a `:reload` while
      one is pending cancels cleanly and leaves nothing behind; creating one over loaded ground still
      adds it synchronously; idle ⇒ silence.
      `[manual]` — create a ghost/sprite at a far coordinate the client has not streamed, then walk there.
      <!-- extra context: `src/io/brodgar/addon/RenderApi.java` (:580-625 addToScene/armPending), `src/io/brodgar/addon/LuaWorldEntity.java`, `src/haven/MapView.java` (addClientGob), `src/haven/MCache.java` (:57-67 LoadingMap), `learnings/ghosts.md` (grep "Loading") -->

- [ ] **042.13 — the close: the poll stage is deleted, and the numbers are measured.**
      **Delete the stage, do not gate it.** Remove from `AddonManager.tick`
      ([:398-433](src/io/brodgar/addon/AddonManager.java:398)): `CharApi.pollTreeAdapters()`,
      `UiApi.pollReplaced()`, `UiApi.pollWidgetSubs()`, `UiApi.pollSelectorWatches()`, `Layout.poll()`,
      `MapApi.pollMarkers()` and `RenderApi.armPending`'s per-frame retry. Remove `poll()` from the
      `TreeAdapter` interface itself — **not** left as an unused `default {}`.
      **Sweep and report** (the close's own grep, in the report the maintainer reads): no `poll`-shaped
      per-frame work remains in the tick; all twelve rows of `spec.md`'s inventory are struck; **exactly
      four** `// addon:` core edits were added (`Widget.remove`, `Buff.reqdestroy`, `Widget.resize`, the
      two `GameUI` deferred-belt lambdas) and no others — the plan originally budgeted three; 042.2 found
      a fourth was unavoidable (a fading widget's "gone" moment has no other addon-visible seam); **D-178..D-182
      are all recorded** and D-091 carries its "superseded by D-181" line.
      **Measure with `hafen.prof` (019) and report the numbers**, before and after, taken on a real
      build — not estimated: the addon layer's per-frame cost (a) with no addon loaded, (b) with a
      subscriber to every event in the feature. The "before" figure is taken on the pre-042 build.
      **Docs and coverage:** `docs/addons/api/event.md` gets a timing sentence and nothing else — no key,
      no payload, no page structure changes. Extend `specs/codebase/addon-engine.md` (the seam table
      gains `onWidgetRemoved`/`onBeltSet` and a `Waitable` row) and `specs/codebase/widgets.md` (the
      removal seam and the `cdestroy`-override finding). Add the learnings entries: the `Waitable` idiom
      and its cancel/notify race (`threading.md`), and the amended `loader.defer` rule
      (`widget-tree-reads.md`, if 042.6 has not already).
      **Run the docs standard before writing any page**: `style-guide.md` §9-§12 and
      `grep "^### D-" specs/docs/decisions/docs-standard.md`; report §12's six checks.
      **Build check:** `rm -rf build/classes` then `ant hafen-client` → `BUILD SUCCESSFUL` — incremental
      builds false-green when a symbol moves between files, and this feature moves several.
      **Suite proves:** every event in the feature still fires with its documented name and payload
      (one line each — this is the regression the rest of the feature rests on); idle ⇒ silence across
      **all** of them at once; and the categorical claim, stated the way D-099 requires: the zero is a
      property of the code's shape (there is no loop), not a branch inside a callback.
      <!-- extra context: `src/io/brodgar/addon/AddonManager.java` (:316-470 the whole tick), `docs/addons/api/event.md`, `specs/docs/design/style-guide.md` (§9-§12), `specs/codebase/addon-engine.md`, `specs/codebase/widgets.md`, `019-profiling/` (the prof read surface) -->

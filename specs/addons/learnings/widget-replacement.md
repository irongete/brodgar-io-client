# Learnings — Widget replacement

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Widget-creation interception (3a) — the descriptor needs BOTH creation and placement.** A server widget's
  registered **type** string (`"inv"`, `"wnd"`, …) is known only at `UI.NewWidget.run` (the instance does **not**
  carry it — `gettype3(typenm)` resolves a `Factory` and discards the name), while **place**/**parentType**/
  **caption** only exist after `UI.AddWidget.run` places it. So D-024's `{id,type,place,caption,parentType}`
  descriptor can't be built from one seam: `NewWidget.run` records `id→typenm`, and `AddWidget.run` (right after
  `pwdg.addchild`) reads it back + adds place/parent — **firing at placement is the first complete moment**
  (refines api-reference's indicative "`UI.NewWidget` hook"). `AddWidget.run` runs on a **Loader thread but under
  `synchronized(ui)`** (the monitor tick/draw hold), so the observer's Lua is safe to call inline like an **L3
  message hook** — no `holdsLock` gate. **Seam choice:** adopt-after-create (**seam B**, `AddWidget.run`) is the
  default — it needs no tampering with the package-private `Widget.types` map and dodges the off-UI-thread
  `create()` caveat (learning above); the factory override (**seam A**) is only needed to *pre-empt* a type and
  can't touch resource-published `/`-names anyway. **`place` = `pargs[0]` only when it's a String** (an item added
  to an inventory has `pargs[0]` = a grid `Coord` → `place=nil`, and its `parentType` is the container, not
  `"GameUI"` — so filter HUD windows by `parentType=="GameUI"`). **`caption`** reads a `Window`'s own `cap`;
  bare widgets the engine wraps in a titled window (e.g. `Inventory` inside a `Hidewnd "Inventory"`) report
  `caption=nil` — identify those by `type`/`place`. Every widget placement passes the seam, so a **global-empty
  fast path** is essential (and `widgetTypes` records nothing unless an observer exists → bounded, cleared per
  session). Referencing `haven.Window` headlessly triggers its static `Tex` loads (needs GL) → test the
  `instanceof Window ? cap : null` branch via the descriptor builder with an explicit caption instead.

- **3b — model handle / adopt-by-id (`hafen.ui.adopt`).** **`UI.getwidget(id)`** is the public id→widget map both
  `uimsg` and `wdgmsg` route by, and it doubles as the **destroy signal**: when the server destroys a widget
  (`UI.DstWidget.run` → `destroy` → `removeid`), its id stops mapping to it, so a per-tick `getwidget(id) != wdg`
  test is a zero-core-edit `onDestroy` (also fires if the id is reused). **`Widget.hide()` keeps a server widget
  fully live** — it only sets `visible=false` + drops the parent's focusable entry; the widget stays a child, stays
  bound, keeps getting `uimsg`/`addchild` (D-009's headless model, confirmed by construction). So `:items()` + the
  add/remove poll work while hidden. **Item add/remove is NOT a `uimsg`** (it's a `WItem` create/`cdestroy`, exactly
  like buffs/study), so it must be **polled** and diffed identity-keyed — reuse the `BuffsAdapter` shape. **A bare
  `new haven.Widget(Coord)` IS constructible headless** (no GL), and `hide`/`show`/`visible`/`children(WItem.class)`
  all work on it — so the whole model-handle surface is headless-testable with a real Widget (unlike 3a's `Window`);
  only the `UI.getwidget`-backed paths (a live UI + server items) need in-game. **Un-hide-on-teardown timing:** guard
  the un-hide on the widget *still being the live server-bound one* (`getwidget(id)==wdg`) — because `init` sets
  `ui = ui_` (the NEW session) BEFORE the teardown loop, a relog's teardown correctly *skips* un-hide (old widget
  gone), while a same-session `:reload`/disable correctly *does* un-hide. **`children(Class)` is a DEEP traversal**
  (whole subtree, not just direct children) — fine for an `Inventory` (WItems are direct kids). **Scope boundary
  (important):** an item **verb** (`take`/`drop`/`transfer`/`use` → `GItem.wdgmsg`) is an **outbound gameplay
  action**, so it belongs to the **gated Phase-4 `hafen.act.*` tier** (D-010/D-025 + the Phase-4 queue's "item
  verbs"), NOT the read-tier model — even though spec 08's older sketch listed it on `model`. Rule of thumb going
  forward: **anything that emits a player `wdgmsg` is gated; reads/UI stay ungated.** The `hello` harness adopts via
  the onWidgetCreate observer, which **won't re-fire for the existing inventory after a `:reload`** (only creation
  fires it) → re-finding an already-open window is 3c's `replace`; don't store a widget id across relogs (ids are
  per-session, and a stale id could adopt the WRONG widget). **Harness gotcha (found in-game):** `onItemAdded`
  fires for the items ALREADY in the backpack as they stream in at login (like `BuffAdded` for pre-existing buffs)
  — ~12 of them — so a naive "log the first 5" cap is exhausted before the user can act, making a live pick-up
  look like it did nothing. The event was firing; the log was masked. Fix pattern for any poll-based lifecycle
  event: log the initial fill quietly, flip a `ready` flag a few seconds after enter-world, then log EVERY live
  change. (Also: hiding the grid does NOT stop the add — `children()` still sees the new `WItem` — which is the
  whole point, so the demo must survive the hidden state.)

- **3c — `hafen.ui.replace` = adopt+hide+view over 3a's dispatch, plus a SCAN for the already-open case.** The
  headline of 3c is the **two match paths**: (1) **creation** — fold the replacer check straight into 3a's
  `onWidgetPlaced` (right after the observers, same `synchronized(ui)`, same fast path — now gated on *both*
  `widgetObservers` and `widgetReplacers` being empty; `onWidgetCreated` records the type when *either* exists), so a
  target opened after registration is caught with the full descriptor; (2) **scan** — at `replace()` registration,
  sweep the live tree ONCE for an already-open match. The scan is what closes the `:reload` gap 3b flagged (a rebuilt
  addon layer gets no creation event for the existing inventory). **Zero new `haven` edit** — the whole slice rides
  3a's two `UI.java` one-liners.
- **The scan can't use the server type string — key on the Java class instead (3c).** `widgetTypes` records
  `id→typenm` only for **in-flight** creations (recorded at `NewWidget.run`, removed at `AddWidget.run`), so an
  *already-live* widget has no recorded type. The scan therefore maps the addon's `type` string → a Java class
  (`typeClass`: `inv`→`Inventory`, `epry`→`Equipory`, `chr`→`CharWnd`, `wnd`→`Window`) and walks
  `root.children(cls)` (a **`Set<T>`**, deep). This is a bridge-internal second lookup, NOT a second addon-facing
  way (D-013): the addon still passes ONE `type` string; the class map is an implementation detail of the scan. For
  `context="main"` skip the class-scan entirely and use the unambiguous public **`GameUI.maininv`** (a bare
  class-scan of `Inventory` would also return open *container* inventories — the ambiguity `context` resolves).
- **The main inventory's parent differs between the two paths — don't match on it in the scan (3c).** At **creation**
  the descriptor's `parentType` is the *server* parent (`GameUI`, `place="inv"`), but `GameUI.addchild` immediately
  re-parents the `Inventory` into a client-side `Hidewnd` (`invwnd`), so a **live** `maininv.parent` is `Hidewnd`,
  not `GameUI`. Hence `context="main"` is gated by `place=="inv" && parentType=="GameUI"` on the **creation** path
  only; the **scan** path targets `GameUI.maininv` directly (no place/parent recoverable for a live widget →
  `place=nil`). Keep the public `opts` to fields consistent across both paths (`context`/`caption`/`match`); expose
  `place`/`parentType` only inside the `match(desc)` predicate.
- **Replace hides the WRAPPER window, and must restore its ORIGINAL visibility, not blindly `show()` (3c).** `replace`
  hides `nativeWindowOf(wdg)` = the nearest enclosing `Window` (the *"Inventory"* `Hidewnd` around `maininv`), so the
  whole stock window disappears (the other 3b-deferred item), not just the grid. But that wrapper is
  **hidden-by-default** (the client only shows it on `Tab`), so teardown must **replay the captured
  `hideTargetOrigVisible`** (`false`→`hide()`), else disabling the addon would leave the native inventory *showing*.
  Generalised on `LuaModel`: a `hideTarget` (defaults to `wdg` → 3b `adopt` byte-for-byte unchanged; `replace`
  overrides it to the wrapper) + `hideTargetOrigVisible` captured in the ctor. `hide`/`show`/`visible`/`teardownModels`
  all route through `hideTarget`. Rule for any "hide the native UI" feature: **capture visibility before hiding,
  restore to that** — never assume the thing you hid was visible.
- **A live `:remove()` must UNDO, not just stop matching (3c).** For a toggle (the `bags` hotkey), the replace
  handle's `:remove()` restores the native window + destroys the view (`undoReplace` per active model), whereas
  teardown (reload/disable) reaches the same end via the existing `teardownModels` (restore) + `destroyWidgets`
  (destroy). Track the view as `LuaModel.replaceView` (the Lua handle `fn` returned) and destroy it by calling its
  own `:destroy()` (idempotent); a server-destroy in `pollModels` does the same (spec 08 "the view dies with the
  model"). The replacer holds an `active` list + a `handled` id-set so a re-scan/placement never double-fires.

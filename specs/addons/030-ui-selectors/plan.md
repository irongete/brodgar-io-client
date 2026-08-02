# 030-ui-selectors — Plan

## Approach

**Parse once, match on demand, classify in one place.**

1. **`Selector`** — a small value object (`role`, `class`, `title`, `res`), parsed from the string once at the
   call or at subscription time, never per node. Matching is a predicate over one widget, so the same object
   serves the lookup, the collection form, the events and (later) C's stylesheet.
2. **The classifier is the feature's heart.** `LuaWidget.role(Widget)` — one `instanceof` chain in ONE Java
   method, the same discipline `typeName`/`nodeText` already use for fragile upstream knowledge: upstream churn
   breaks that one method, not addons, and an unknown widget answers `nil`. Roles = the **10** carried
   `Fonts.SCOPES` names + **`window`** + **`inventory`**; `"default"` does not carry over — its selector twin
   is `*`.
3. **`@Class` reuses `LuaWidget.typeName`**, never `getClass().getSimpleName()`: Hafen builds widgets as
   anonymous subclasses everywhere, and `typeName` already walks up to the nearest **named** superclass.
4. **`[title=]` matches the nearest enclosing `Window`'s caption, not the widget's own.** A bare widget the
   engine wraps in a titled window — `Inventory` inside a `Hidewnd "Inventory"` — has **no `cap` of its own**
   (`learnings/widget-replacement.md`), and 029 found the same shape from the other side: `hafen.ui.inventory()`
   is the *grid*, its window is `:parent()` one hop up. Matching the widget's own caption would make
   `inventory[title=Cupboard]` — the single most obvious selector a user will write — silently never match.
   `[res=]` reads the res-published name where the widget has one (D-063: the stable key).
5. **Events: candidates at the placement seam, then a bounded re-check.** `hafen.ui.on(sel, "appear"|
   "disappear", fn)` hangs off the existing `onWidgetPlaced` seam (global-empty fast path preserved), which is
   **the first complete moment** — `place`/`parentType`/`caption` do not exist at `NewWidget.run`. But a
   `.res` window can still receive its caption a tick or two later, so a candidate that matches structurally
   (role/class) and carries a `[title=]`/`[res=]` refiner is **re-checked for a bounded number of ticks**
   before being dropped. Cost then scales with **widget creation, not with frames** — which is why this is not
   a per-tick diff of the whole tree (see *Discarded*).
6. **`hafen.ui` becomes callable** (the `hafen.sound`/`hafen.kin` shape): `hafen.ui(sel)` = first match or
   `nil`, `hafen.ui.all(sel)` = every match, `hafen.ui()` = the root. **`hafen.ui.root()` is hard cut**, and so
   is **`onWidgetCreate`** + its `desc` descriptor, replaced by `on(selector, …)`.
7. **The inspector goes into `widgetstack`**, which is already the `/framestack` clone: the hovered widget's
   role / class / title / res, the selectors that match it, and which is most specific.

## Files to create / modify

- **create** `src/io/brodgar/addon/Selector.java` — parse + match; **create** `specs/addons/design/22-ui-selectors.md`
  — the standing design doc (C, D and E all consume this vocabulary, so it outlives the feature folder).
- `src/io/brodgar/addon/LuaWidget.java` — `:role()` + the classifier; `typeName` reused by `@Class`.
- `src/io/brodgar/addon/UiApi.java` — the callable namespace, `all`, `on`; `root()`/`onWidgetCreate` cut; the
  placement seam grows the candidate re-check.
- `src/io/brodgar/addon/AddonManager.java` — install `hafen.ui` as a callable table.
- `src/haven/Fonts.java` — **read-only** reference for the scope names; do **not** move the enum in this feature
  (fonts still resolve by scope until C folds them into the stylesheet).
- `docs/addons/api/ui.md` (the grammar, the role table, `:role()`, the events), `conventions.md` (selectors as
  the third addressing convention beside ids and handles), `events.md` (`onWidgetCreate` gone), `fonts.md`
  (cross-ref: same names, two consumers), both index tables, `getting-started.md`.
- `addons/widgetstack/main.lua` (the inspector), `addons/hello/main.lua` (grammar contract check + its
  `onWidgetCreate` use), `addons/bags/main.lua` (only if it touches the descriptor).
- **No `haven` edit expected** ⇒ no coverage toll; `Fonts.java` is read, and `specs/codebase/` already covers it.

## Risks & gotchas

*(prior art: `learnings/widget-replacement.md`, `learnings/ui-widgets.md` — grepped, not read whole)*

- **The placement seam runs on a Loader thread but under `synchronized(ui)`** (the same monitor tick/draw hold),
  so calling Lua inline there is safe — as the L3 hooks already do. Keep the **global-empty fast path**: every
  widget placement passes this seam, and it must cost nothing when no addon subscribes.
- **`caption` is only a `Window`'s own `cap`** — see approach §4. This is the single easiest way to ship a
  selector engine that looks right and never matches.
- **`children(Class)` is a DEEP traversal.** `hafen.ui.all()` must walk once and test each node, never call a
  deep helper per node — 029.4 hit exactly this and had to prune `hello`'s container scan at
  `Inventory`/`Equipory` to avoid O(n²).
- **Anonymous subclasses are the norm**; `@Class` against `getSimpleName()` would match almost nothing.
- **Cost**: 029 measured an interned handle at **27 ns** against the old builder's 513 ns, and a 2000-node walk
  at **0.05 ms** steady — so a one-off `all("*")` is affordable and a *per-frame* one is not. The docs must say
  "hold the result; do not re-select every frame", and the profiler numbers go in the task's verification.
- **The classifier will be incomplete on day one** and that is fine — an unmatched widget answers `nil` and the
  role set grows. What must not happen is a role that matches the *wrong* widget: prefer no answer to a guess.

## Discarded alternatives

- **A per-tick diff of matching widgets for `appear`/`disappear`** — rejected: one subscription would cost a
  full tree walk every frame (~0.05 ms each, several subscriptions = real budget) where the placement seam
  costs nothing until a widget is actually created.
- **Descendant selectors in v1** — rejected: multiply resolution cost, rarely needed, addable later.
- **`replace(selector, fn)`** — rejected here (→ B3): `replace` matches a *descriptor* at placement time, whose
  `place`/`parentType` are the **server** parent; a live-tree selector cannot express `context="main"`.
- **Caching resolved selectors** — rejected as premature: entities are interned, so holding the result is free.
- **A dedicated inspector addon** — rejected: `widgetstack` is already the hover-the-tree tool.
- **Moving `Fonts.SCOPES` into the selector engine now** — rejected: fonts keep resolving by scope until C.

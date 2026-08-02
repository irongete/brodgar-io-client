# 030-ui-selectors — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **030.1 — the grammar and the classifier.**
      New `Selector` (parse `*` · role · `@Class` · `[title=]` · `[res=]` once, then a predicate over one
      widget) and `LuaWidget.role(Widget)` — **one** `instanceof` chain, the `typeName`/`nodeText` discipline,
      unknown ⇒ `nil`, and **never a wrong answer in place of no answer**. Roles = the 10 carried
      `Fonts.SCOPES` names + `window` + `inventory`; `"default"` does not carry over (its twin is `*`).
      `hafen.ui` becomes **callable**: `hafen.ui(sel)` = first match or nil, `hafen.ui.all(sel)` = every match,
      `hafen.ui()` = the root — **`hafen.ui.root()` hard cut**. `@Class` goes through `typeName` (anonymous
      subclasses are the norm); **`[title=]` matches the nearest enclosing `Window`'s caption**, not the
      widget's own, or `inventory[title=Cupboard]` silently never matches. `w:role()` on the entity.
      **Measure** (`hafen.client:profiling()`, the 029 method): one `all("*")` over a real UI.
      **Verify:** in-game from `:lua` — each grammar element against real windows, an open Cupboard matched by
      title AND by res, a malformed selector's error, and the profiler number for `all("*")`.

- [ ] **030.2 — selector events, and the end of `onWidgetCreate`.**
      `hafen.ui.on(sel, "appear"|"disappear", fn)` on the existing `onWidgetPlaced` seam — **the first complete
      moment** (`place`/`parentType`/`caption` do not exist at `NewWidget.run`) — with the **global-empty fast
      path preserved**. A candidate that matches structurally but carries a `[title=]`/`[res=]` refiner is
      **re-checked for a bounded number of ticks**, so a `.res` window whose caption lands a tick late still
      fires exactly once. Hard cut of `onWidgetCreate` and its `desc` descriptor.
      **Verify:** in-game — open and close a Cupboard: one `appear`, one `disappear`, with a title selector;
      a late-captioned window still fires once and only once; with no subscription the profiler shows the seam
      costing nothing; `hello`'s old `onWidgetCreate` use is ported.

- [ ] **030.3 — the inspector (`widgetstack`).**
      Hovering shows, per widget: **role**, class, `[title=]` and `[res=]` where they exist, the selectors that
      match it and **which is most specific** — plus a copyable selector string for the hovered widget. Reuses
      015's `at()`/`rootpos()` and 029's `==` hover guard; no new addon.
      **Verify:** in-game — hover the inventory grid, its window, a button and a chat line; each reports a role
      (or an honest `nil`), and the offered selector, pasted into `:lua hafen.ui("…")`, returns that same
      widget (`==`).

- [ ] **030.4 — docs, harness, close.**
      New `specs/addons/design/22-ui-selectors.md` (the standing design doc — C, D and E consume this
      vocabulary). `docs/addons/api/ui.md` gains the grammar, the **role table with its class mapping**,
      `:role()`, the events, and the two rules that are easy to get wrong: `[title=]` resolves against the
      enclosing window, and **hold your result — do not re-select every frame** (with the measured numbers).
      Sweep `conventions.md` (selectors as an addressing convention), `events.md`, `fonts.md` (same names, two
      consumers), `getting-started.md` and both index tables. `hello` version bump + the once-per-login
      contract check (each grammar element, `w:role()`, an `on()` round trip, `rootGone`/`onWidgetCreateGone`).
      **Verify:** one login re-checks 030 and every prior feature; `widgetstack` and `bags` clean; the
      link/anchor checker over `docs/addons/` reports 0 broken.

# 049-css-selectors — Spec

## What & why

An addon author **cannot point at one exact nested widget with a single selector**. Reaching "the
Close button of the Foo window" is `hafen.ui():all(sel)[i]` — a **positional index** whose meaning
changes silently the moment a second matching window is open — or a hand-written `ipairs` loop,
because a widget exposes no scoped search (`:children`/`:parent`/`:walk`/`:at` only).
[design/22](design/22-ui-selectors.md) deferred the two fixes as *"addable later"*: **no descendant
selectors, no attribute operators beyond `=`**. This ships both — and in doing so corrects the one
part of the grammar that **lies to a CSS reader**: `[title=]` today tests the nearest *enclosing*
window's caption, an attribute that walks UP the tree. Once a combinator exists the ancestor test is
the **combinator's** job, so every attribute reverts to testing the widget it is written on.

## Acceptance criteria

- [ ] **The combinator.** `hafen.ui():find("window[title=Cupboard] inventory")` hands back the
      Cupboard's grid (the same widget `inventory[title=Cupboard]` returns today), and
      `grid:parent() == hafen.ui():find("window[title=Cupboard]")`. A three-step chain resolves
      (`window[title=Cupboard] * label`). The space means **descendant at any depth**, so an
      intervening `Frame`/`Scrollport` never breaks a chain.
- [ ] **CSS-pure attributes.** `[title=]` and `[text=]` test the widget the step is written on, never
      an ancestor. `Selector.windowTitle` is **gone**.
- [ ] **The retired spelling THROWS, it does not silently miss.** `[title=]` off the `window` role
      (`inventory[title=X]`, `*[title=X]`, bare `[title=X]`) is a **parse error** naming
      `window[title=X] inventory`. Symmetrically `window[text=X]` is a parse error naming
      `window[title=X]`. The two keys are disjoint by construction, so neither can be written where
      the other is meant.
- [ ] **`[text=]` is new** and reads `LuaWidget.text` — a `button`, `label`, `textentry` or checkbox
      matches by the words it displays: `hafen.ui():find("window[title=Foo] button[text=Close]")`.
- [ ] **The four CSS operators** `=` (exact) · `*=` (contains) · `^=` (starts with) · `$=` (ends
      with) work on every attribute key. **`=` is now exact on `res` too** (it was an implicit
      substring): `[res*=gfx/hud]` is the substring form and says so. An unknown operator errors
      naming the four.
- [ ] **All three consumers speak the new grammar**, since they share the parser: `:find`/`:all`,
      the `:on(sel, "appear"|"disappear", fn)` subscriptions (including the bounded re-check when a
      **late caption lands on an ancestor step**, which is the common case), and `hafen.ui.skin`
      tree keys.
- [ ] **Scoped search.** `w:find(sel)` / `w:all(sel)` search **within** that widget's subtree
      (inclusive), same grammar, same errors. This is the only correct answer inside an
      `:on("window[title=Foo]", "appear", fn)` callback, where a root-anchored selector would
      re-find an ambiguous window rather than the one handed to the callback.
- [ ] **`find` refuses an ambiguous answer.** `hafen.ui():find(sel)` and `w:find(sel)` RAISE when two
      or more widgets match, saying how many and naming `:all(sel)[i]` — the rule the classifier beside
      them already follows (*never a wrong answer in place of no answer*). `:all()` is unchanged. `find`
      stops short-circuiting and walks the whole tree (0.08 ms / 625 widgets, design/22).
- [ ] **Nothing shipped is left speaking the old grammar** — and the sweep is small, because
      `window[title=X]` (a window testing its OWN caption) means the same before and after: measured,
      `cupboard` and `atlas` need **no** selector change, `bags` **one**, frozen `hello` **~5** plus the
      `:hello selector` narration that currently teaches the deleted ancestor rule. All five load clean
      and behave as before; `hello` is FIXED under `TESTING.md`'s "fix what a change breaks" exception.
- [ ] **The inspector teaches the new grammar.** `widgetstack`'s selector panel offers combinator
      candidates and its self-validation still holds — every offered line resolves back to the
      hovered widget, and a `find(...)` line is offered only when it is the first match.
- [ ] Each task ships its self-checking addon per `specs/testing/addon-suite.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **`>` (direct child).** Hafen's layout wrappers (`Frame`, `Scrollport`, a window's content area)
  make it fail almost always. Purely additive — it can come later without breaking a chain.
- **Pseudo-classes** (`:first`, `:has`), sibling combinators, `,` selector lists, quoted attribute
  values, any change to `@Class` (the bare type-selector slot is taken here by the **role**), and any
  change to `LuaWidget.role`'s classifier coverage.

## Context files

- `design/22-ui-selectors.md` — the design of record; this feature reverses its "no descendant
  selectors / no operators" scope line and its `[title=]` ancestor rule
- `design/20-widget-introspection.md` — the tree walk and hit-testing the lookups ride
- `src/io/brodgar/addon/Selector.java` — the parser + matcher; the whole grammar change lands here
- `src/io/brodgar/addon/UiApi.java` — `selArg`/`selectFirst`/`selectAll`/`firstMatch`/`collect`, the
  `:on()` placement seam and the late-refiner re-check
- `src/io/brodgar/addon/LuaSelectorWatch.java` — the subscription; `matchesStructure`/`late()` split
- `src/io/brodgar/addon/Sheet.java` — `siteOf` + the per-widget tree-rule cache keyed on the grammar
- `src/io/brodgar/addon/LuaWidget.java` — `text`/`typeName`/`role`; `:find`/`:all` land here
- `addons/widgetstack/main.lua` — the selector inspector; it BUILDS candidates and must be taught
- `docs/addons/api/ui/selectors.md` (18 hits) + `ui/style/keys.md`, `ui/replace.md`, `ui/widget.md`,
  `guides/theming.md`, `guides/debugging.md` — the docs tier this rewrites
- `030-ui-selectors/` — the feature that shipped the grammar; its tasks are the migration's map

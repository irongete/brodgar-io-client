# 049-css-selectors — Plan

## Approach

**`Selector` becomes a chain of the value object it already is.** Today it is one immutable
`(role, cls, title, res)`; it becomes `Step[]`, each step that same shape with `title`/`text`/`res`
now carried as `(key, op, value)` triples. Everything downstream keeps calling `matches(Widget)`, so
the three consumers inherit the grammar for free — which is why the parser is task 1 and the
consumers only need the work the *semantics* force on them.

**Matching is greedy right-to-left, and for pure descendant chains that is provably correct** — no
backtracking. Match the LAST step against `w`, then walk `w.parent` upward taking the *nearest*
ancestor that matches the next step leftward. Greedy can never block: if the nearest matching
ancestor has no valid ancestor above it, neither does a farther one, since its ancestors are a
subset. (This is exactly what `>` would break, and one more reason it stays out.)

**`Selector.windowTitle` is deleted.** The ancestor walk it hid inside `[title=]` becomes the
combinator's job. What replaces its safety net is a **parse error**: `[title=]` is accepted only in a
step whose role is `window`, `[text=]` only in a step whose role is not — so `inventory[title=X]`
cannot be written at all, and the error names `window[title=X] inventory`. The keys are disjoint by
construction rather than by a precedence rule.

**`matchesStructure`/`late()` split survives, widened over the chain**: structure is every step's
role/class (fixed for a widget's life), `late()` is the OR over steps of "carries an attribute that
can land after placement". The common case is now a caption arriving late on an **ancestor** step,
which is precisely what the existing bounded 20-tick re-check already exists for.

**`find` becomes strict.** `selectFirst` stops short-circuiting: it collects up to two matches and
raises on the second, naming `:all(sel)[i]`. `:all()` is untouched — the collection form always
answers. This is the rule `LuaWidget.role` beside it already follows: *never a wrong answer in place
of no answer*.

**Scoped search is the same walk from a different root.** `UiApi.firstMatch`/`collect` are already
recursive from an arbitrary widget and are handed `u.root` today; `w:find`/`w:all` hand them `w`.
They move from `private static` to package-private.

## Files to create / modify

- `src/io/brodgar/addon/Selector.java` — the whole grammar: `Step[]`, the space combinator, the four
  operators, `[text=]`, the title/text disjointness errors, greedy ancestor matching; `windowTitle`
  **deleted**
- `src/io/brodgar/addon/UiApi.java` — `selectFirst` becomes strict; `firstMatch`/`collect` opened up
  for the scoped doors; the `:on()` placement seam's re-check now keys on an ancestor step's caption
- `src/io/brodgar/addon/LuaWidget.java` — new `:find(sel)`/`:all(sel)`; `text(Widget)` becomes the
  `[text=]` reader (no change to the switch itself)
- `src/io/brodgar/addon/LuaSelectorWatch.java` — `matchesStructure`/`late()` widened over the chain
- `src/io/brodgar/addon/Sheet.java` — `siteOf` answers "tree key" for any multi-step selector (a font
  scope is always one bare word); the per-widget cache must invalidate when an **ancestor's** caption
  lands, not only the widget's own
- `addons/bags/main.lua` — one selector + the two comments documenting the deleted rule
- `addons/hello/main.lua` — ~5 selectors + the `:hello selector` narration (FIXED, never grown)
- `addons/atlas/main.lua` — one stale comment; no selector change
- `addons/widgetstack/main.lua` — the inspector BUILDS candidates: combinator candidates, the new
  operators, `[text=]`, and `find(...)` offered only when unique (which strictness now enforces)
- `docs/addons/api/ui/selectors.md` (18 hits) + `ui/style/keys.md`, `ui/replace.md`, `ui/widget.md`,
  `ui/README.md`, `ui/items.md`, `ui/native.md`, `guides/theming.md`, `guides/debugging.md`,
  `api/conventions.md`, `api/event.md`, `runtime.md` — the docs tier
- `specs/codebase/addon-engine.md` — its `Selector` and `Sheet` rows still describe the old grammar
- `specs/design/22-ui-selectors.md` — a superseding banner: its "no descendant selectors, no
  operators" scope line and its `[title=]` ancestor rule are both reversed here
- `specs/decisions/widgets-ui.md` — the new decisions (see tasks.md)

## Risks & gotchas

- **This reverses learning (030.1), which exists for a good reason.** `[title=]` walked up because
  *"matching a widget's own caption would make `inventory[title=Cupboard]` silently return `nil`
  forever"*. The parse error is the entire replacement for that safety net — if it is not airtight
  (bare `[title=X]`, `*[title=X]`, `@Class[title=X]` with no role), we ship exactly the failure 030.1
  was written to avoid, and it fails silently.
- **`[res=]` becoming exact is the one break nothing can catch.** It parses fine and stops matching.
  Only the sweep and the suite cover it; `[res*=]` is the migration.
- **Ancestry is NOT immutable.** `w:replace()` and 044's re-homing reparent live widgets, so
  `matchesStructure`'s "this can never match" is only safe because the placement seam re-fires on
  re-add (learning 042.1/044). Verify that a re-homed widget re-enters the seam.
- **`find` now always pays the full walk** — 0.08 ms / 625 widgets (030.1, measured). Fine per call,
  fatal per frame: the docs rule *"hold your result; do not re-select every frame"* stops being
  advice and becomes load-bearing.
- **The Sheet cache has no ancestor walk today** (learning 035.1: *"`styleFor` asks about that one
  widget, and there is no ancestor walk"*). A chain rule makes a descendant's style depend on an
  ancestor's caption, so `capDirty`'s re-check must reach descendants, not just the captioned widget.
- **`hello` is frozen** — `TESTING.md`'s "fix what a change breaks" exception applies; do not grow it.
- Anonymous-subclass `typeName` (learning 044) is unchanged, but a chain multiplies the exposure: a
  step naming `@Class` on an intermediate wrapper is the brittlest thing an author can write.

## Discarded alternatives

- **`>` (direct child)** — Hafen interposes `Frame`/`Scrollport`/content areas, so it would fail
  almost always; and it is the one combinator greedy matching cannot do without backtracking.
- **Keeping `[title=]`'s ancestor semantics beside the combinator** — two ways to say one thing, and
  the CSS lie survives in the half everyone writes first.
- **Letting `inventory[title=X]` parse and simply never match** — silent, and exactly the 030.1
  failure. A retired spelling throws naming its replacement, like every other one in this API.
- **A log warning instead of an error on an ambiguous `find`** — addons do not read logs; this
  codebase refuses rather than guesses (D-186).
- **A backtracking matcher** — unnecessary while every combinator is descendant (see Approach).
- **Deprecating `atlas`/`bags`/`cupboard`/`hello` instead of fixing them** — measured, the fix is ~6
  selector strings and deprecation is 14 docs pages. If those demos should retire, that is its own
  feature, not a side effect of a grammar change.

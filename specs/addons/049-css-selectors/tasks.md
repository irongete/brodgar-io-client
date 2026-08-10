# 049-css-selectors — Tasks

- [x] **049.1 — the grammar: chain, operators, `[text=]`, and the disjointness errors.**
      `Selector` becomes `Step[]`; the space is the descendant combinator; `=`/`*=`/`^=`/`$=` on every
      key; `[text=]` reads `LuaWidget.text`; `windowTitle` deleted and matching goes greedy
      right-to-left. `[title=]` outside a `window` step and `[text=]` inside one are **parse errors
      naming the other**. Sweeps `bags` (1 selector + 2 comments), `hello` (~5 + its `:hello selector`
      narration) and `atlas` (1 comment) here, so nothing is left broken (`cupboard` needs nothing).
      **Proved:** two- and three-step chains against a self-built window; descendant not child; the anchor
      is not its own descendant; all four operators, `=` rejecting a prefix; `[res=]` exact vs `[res*=]`;
      the four refusals naming their replacement; the old error catalogue still erroring.
      **Shipped 17/17, 0 manual.** D-085 corrected a first draft that delegated proof to `:hello selector`
      and `:bags`: the migrated spellings are asserted HERE — the old one refused naming its replacement,
      the new one resolved against the live Inventory window. `atlas`/`cupboard` needed no change at all.

- [x] **049.2 — the lookup doors: strict `find` + scoped `w:find`/`w:all`.**
      `selectFirst` stops short-circuiting, collects up to two and RAISES on the second, saying how
      many matched and naming `:all(sel)[i]`. `:all()` unchanged. `w:find(sel)`/`w:all(sel)` search the
      widget's own subtree (inclusive) through the same `firstMatch`/`collect`, opened to
      package-private. Audit every `find()` in the shipped addons for one that is newly ambiguous.
      **Suite must prove:** `find("*")` raises and the message carries the count and `:all`; a selector
      matching exactly one still answers; `w:find` scoped inside one window returns that window's own
      child while the root-anchored form raises on two open containers; `w:all` is empty-not-nil for no
      match; a stale widget handle refuses at both doors.
      **`[manual]`:** with two containers open, the root-anchored `find` raises and the scoped one does
      not — the whole point of the pair.
      **Shipped 11/11, 0 manual.** The `[manual]` never fired: the suite BUILDS its two same-captioned windows
      (deterministic), then scans the live HUD and, finding two windows captioned "Stack", asserted the pair on
      the client's own too. `find` walks the WHOLE tree and reports the exact count (**D-224**) — plan.md's
      "up to two" would not have; `firstMatch` was deleted, having no caller left. The scope narrows the
      CANDIDATES only, so an ancestor step still names a widget above it (**D-225**), and a departed subject
      REFUSES at both doors where every flat read answers empty (**D-226**). The audit found five raising
      `find`s in frozen `hello` — including the root-anchored chain inside its own `appear` callback, now
      `w:find("inventory")` — plus `theme`'s key read from a FILE, which is the dangerous class.

- [ ] **049.3 — the other two consumers: subscriptions and stylesheet keys.**
      `LuaSelectorWatch`'s `matchesStructure`/`late()` widen over the chain; the placement seam's
      bounded re-check must fire when a **late caption lands on an ancestor step**, which is now the
      common case. `Sheet.siteOf` answers "tree key" for any multi-step selector, and the per-widget
      cache invalidates when an ancestor's caption arrives — it has no ancestor walk today.
      **Suite must prove:** `:on("window[title=Cupboard] inventory", "appear", fn)` fires exactly once
      for a container opened AFTER subscribing and once for one already open (the D-068 scan);
      `disappear` fires on close; a late-captioned window fires the chain exactly once, not twice and
      not never; a `hafen.ui.skin` chain key styles the descendant and not its siblings, and re-folds
      when the ancestor's caption lands late.
      **`[manual]`:** open and close a Cupboard twice; the counts printed match the claims above.

- [ ] **049.4 — the inspector teaches the new grammar.**
      `widgetstack`'s selector panel builds **combinator** candidates (the enclosing window as the
      anchor step + the hovered widget as the target), offers `[text=]` and the operators, keeps its
      self-validation (`:all()` must resolve the candidate back to this very widget), and offers a
      `find(...)` line only where it is unique. Report the walk
      count: a chain candidate costs more than a flat one.
      *Reduced by 049.2*, which already switched `pasteLine` from "the FIRST match" to "the ONLY match" — a
      shipped inspector could not go on offering a line that raises. What is left is the candidate BUILDING:
      combinator candidates, `[text=]`, the operators, and the walk-count report.
      **Suite must prove:** the panel's offered line, pasted into `:lua`, returns the hovered widget
      (`==`); no offered candidate raises; a candidate offered as `find` resolves to exactly one.
      **`[manual]`:** hover a button in a Cupboard; the offered line is a chain and pasting it works.

- [ ] **049.5 — the docs tier.** *Reduced by 049.1 and again by 049.2*: 049.1 rewrote `api/ui/selectors.md`
      around the combinator/operators/two-disjoint-keys and fixed every example on five other pages that the new
      grammar would have made RAISE — a shipped page teaching a selector that errors could not wait four
      tasks — and 049.2 shipped **strict `find`** and the scoped **`w:find`/`w:all`** on their own pages the
      same day for the same reason (`selectors.md` "One, or all of them",
      `widget.md` "Searching inside one widget", plus `ui/README.md`, `references.md` and `replace.md`).
      What is left here is the inspector's new candidates and a pass over the pages those touch. Read
      `specs/docs/design/style-guide.md` §9–§12 and the
      `grep "^### D-" specs/docs/decisions/docs-standard.md` one-liners BEFORE writing, and run and
      report §12's six checks.
      **Suite must prove:** every selector string quoted in the docs parses (drive the page's examples
      through `hafen.ui():find` in a `pcall` and assert none raises a parse error).
      **`[manual]`:** none beyond the §12 report.

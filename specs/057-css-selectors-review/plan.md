# 057-css-selectors-review — Plan

## Approach

The design already exists (`design/style-guide.md`, `design/information-architecture.md`); this
feature runs it over one surface and lands only deltas. The shape is `005`/`006`'s, narrowed to a
single `addons` feature:

1. **`census.md` first, both directions.** One table, `src/` on the left and the page on the right:
   every grammar fact `Selector` enforces (the step, the combinator, the four operators, the two
   disjoint keys, the refusals), every door `UiApi`/`LuaWidget` opens (`:find` strict, `:all`,
   the scoped pair, the stale-handle refusal) and every consumer `LuaSelectorWatch`/`Sheet` drives.
   Verdicts are `OK` / `WRONG` / `THIN` / `GAP` / `INVENTED`, and `CUT`/`N/A` are first-class
   (001.1) — a fact the tier deliberately does not state is `CUT`, not a gap. The census is the
   task's evidence and it stays in this folder; nothing about it reaches `docs/`.
2. **The ten pages `049` wrote, checked against the source that proves each claim.** They are
   likelier right than the rest — they were written the day the code landed — so the report says
   which were checked and clean, and corrects the rest. `049`'s own spec and task notes are the map
   of *what to check*, never the oracle for *whether it is true* (005.2).
3. **The fifteen it never opened, read whole.** Not grepped for the new syntax — the syntax greps
   are already green — but read for the sentence a grammar change leaves false: a page-level
   generalisation about lookups, a `find` example that is now ambiguous, link text that names the
   old rule, a negative that promised what now ships.
4. **The standard, then the close.** Settle the refused spellings against D-243, re-derive the
   tree's figures into `STATE.md` and the IA's §3, and run §12 whole-tree with every falsification.

The area ships no code and no tests (AREA.md). Verification is the maintainer reading the changed
pages; each task's report is the material — counts with the offenders listed, and a `src/` citation
behind every corrected claim.

## Files to create / modify

**Created**
- `specs/057-css-selectors-review/census.md` — 057.1's table, both directions.

**Modified — `docs/` (the deliverable)**
- The ten `049` wrote: `api/ui/selectors.md`, `api/ui/widget.md`, `api/ui/replace.md`,
  `api/ui/README.md`, `api/references.md`, `api/ui/style/keys.md`, `api/ui/style/README.md`,
  `guides/debugging.md`, `guides/theming.md`, `examples.md` — corrections only (057.1).
- The fifteen it did not: `api/ui/items.md`, `api/ui/native.md`, `api/ui/custom.md`,
  `api/ui/mouse.md`, `api/ui/lists.md`, `api/ui/controls/README.md`, `api/ui/controls/interactive.md`,
  `api/ui/style/geometry.md`, `api/ui/style/surfaces.md`, `api/README.md`, `api/conventions.md`,
  `api/event.md`, `api/font.md`, `api/speed.md`, `guides/custom-ui.md`,
  `guides/events-and-timers.md` — plus `runtime.md`, which quotes a lookup in a `:lua` line (057.2).
- Whatever §12 turns up tree-wide (057.3).

**Modified — `specs/`**
- `design/information-architecture.md` §3 — page count, total lines, the largest pages.
- `design/style-guide.md` §7 — only if a refused spelling is admitted, with its falsification.
- `decisions/docs-standard.md` — the D-243 outcome as D-249, whichever way it goes.
- `STATE.md`, `FEATURES.md` (`/end`), `learnings/docs-maintenance.md` (append, `/end`).

Nothing under `src/`, nothing under `addons/`, nothing in area `addons`' own trees: a finding is
filed into `STATE.md`'s open list and reported (AREA.md).

## Risks & gotchas

- **`049`'s spec is the same source, not a second opinion (005.2).** `041`'s rationale was copied
  into `api/event.md` word for word and carried its blind spot; a reviewer reading the spec to
  "check" the page confirmed the error twice. Every corrected claim here is backed by the file that
  implements it — and `049.1`..`049.5`'s close notes record *deltas from what the task expected*,
  which is exactly where a page written mid-task can have frozen the expectation instead.
- **The stale prose is rarely on the page that changed (003.1, 006.3).** `038` left `player.md`
  saying "gob overlay" at a live-but-wrong link, invisible to every check the standard runs. So
  057.2 greps the **prose names** of this grammar — "selector", "the first match", "the enclosing
  window", "the window it sits in", "tree key" — and greps **link text** against its target's page
  name, not only the targets.
- **The refused spellings are the false-zero's twin (006.1).** `inventory[title=…]` reads 1 on a
  healthy tree, because `selectors.md` quotes it as the boundary it must quote. `hafen.act` read 19
  for the same shape of reason and was admissible only as a regex. Either a spelling exists that
  reads zero *and* catches a reintroduction, or D-243's second half says it does not — and 004.2's
  rule applies to whichever is chosen: an admitted entry is proven at the moment it is admitted, so
  it is falsified in the same task.
- **A code block is a claim (001.5).** Every selector string in the tier is driven through the real
  parser rather than eyeballed — `049.5` did this headlessly against `Selector.parse` and it is the
  cheaper check by far. `hafen.ui():find(…)` examples need the second question too: legal, but
  ambiguous against a real HUD?
- **Collection `:find(filter)` is a different verb** on some twenty pages ("the first that matches")
  and `049` did not touch it. Any sweep of `find` semantics that does not separate the two will
  either rewrite twenty correct tables or miss the five pages that matter.
- **`specs/codebase/addon-engine.md` is known stale** (`STATE.md`: a `RenderApi` that `043`
  replaced, a `hafen.act():flower` `048.7` deleted). Read the files, not the map.
- **Line endings and columns**: `sed -i` rewrites every ending on this CRLF tree (001.4), and the
  110-column check measures bytes unless it counts characters (002.2, 003.1).

## Discarded alternatives

- **One task instead of three.** The census and the whole-tree §12 are different kinds of work with
  different evidence; `006` proved the close needs its own task or its falsifications get skipped.
- **Grep-only review of the fifteen unopened pages.** That is precisely the check `049.5` showed to
  be green while three pages were wrong. They are read.
- **Re-running `005`/`006`'s corrections.** Out of scope: their surfaces were checked against `src/`
  at their close, and §12 whole-tree at 057.3 is what re-covers them cheaply.
- **Splitting `api/ui/selectors.md`.** 205 lines with the ceiling at 300 and no inbound-anchor
  pressure; a split priced on nothing is a re-point bill for free (006.3).

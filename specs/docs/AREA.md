# Area: docs — the user-facing documentation, kept true and readable

> Manifest read by `/plan`, `/implement` and `/end` before anything else. Everything specific
> to this area lives HERE, not in the commands. Max 30 lines.

- **Scope**: the `docs/` tree as a *product* — information architecture, navigation, page size,
  style, guides, examples and accuracy. A **maintenance area**: it ships no code, no addons and
  no tooling; its features are documentation tasks the maintainer asks for with `/plan docs …`
  (review, update, restructure, extend). It never touches `src/` or the `hafen.*` API — an audit
  that finds an engine bug or a missing API reports it and files it to the owning area.
- **Boundary with `addons`**: area `addons` still writes the reference content for each surface
  it ships (its docs tier stays `docs/addons/**`), following the style and page limits this
  area sets. Same rule for any future area with a docs tier.
- **Branch**: `feature/addons` (same working branch; docs ride the same tree).
- **Docs tier (ONE)**: `docs/**` — the site itself is this area's deliverable. Every feature
  updates the affected pages plus their indexes (`docs/addons/README.md`, `docs/addons/api/README.md`).
- **Build check**: none — docs are not compiled and no client rebuild is needed. If a feature
  ever also touched `src/`, that area's build check would apply.
- **Verification**: by the maintainer, reading the changed pages as rendered Markdown, against
  the acceptance criteria in the task's spec. Nothing to rebuild, nothing to restart. Claims
  about behaviour are backed by the source or by a `NNN-` feature folder, cited in the task
  report — never by memory.
- **Test protocol**: `none` — this area ships no tests and no test addons. `/implement` reports
  what it changed and what it checked (links resolved, symbols found in `src/`) as plain lines
  the maintainer can spot-check.
- **Design rules**: one topic per page and no page over the size ceiling the active feature
  states; every public symbol reachable from an index; task-first pages (guides) kept separate
  from lookup pages (reference); every documented behaviour traceable to real code — no
  aspirational docs and no undocumented shipped surface.
- **Commit paths** (for `/end`'s single commit): `docs specs`.
- **Contract file**: none separate — **the docs tier IS the contract**.
- **Design docs**: `specs/docs/design/` — the style guide and the site's information
  architecture, written on demand by the feature that needs them.

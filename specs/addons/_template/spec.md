# NNN-<feature> — Spec

<!-- MAX 80 lines (a ceiling, not a target). If it doesn't fit, split the feature. -->

## What & why
<!-- 3-6 lines: the capability, who uses it, why now. If a design/ doc covers it, reference
     it and state only the delta this feature ships. -->

## Acceptance criteria
<!-- Each one verifiable IN-GAME by the maintainer. Concrete: what to type/click, what appears. -->
- [ ] ...
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope
- ...

## Context files
<!-- The feature's context budget: the EXACT files to read to work on it. Nothing else is
     loaded by /implement. Include: design/ docs, .java sources, docs/addons/** pages,
     and related previous NNN- folders. One line each, with why. -->
- `design/XX-....md` — the design this implements
- `src/haven/....java` — the seam being hooked
- `src/io/brodgar/addon/....java` — where the code lands
- `docs/addons/....md` — the shipped surface this extends
- `NNN-<related-feature>/` — prior art / mechanism reused

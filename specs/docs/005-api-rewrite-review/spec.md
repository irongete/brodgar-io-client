# 005-api-rewrite-review — Spec

## What & why

Seven area-`addons` features have landed with **no area-`docs` review**: `039-uniform-api`,
`041-unified-events`, `042-event-driven-reads`, `043-vr-namespace`, `044-spatial-ui`,
`045-durable-places`, `046-gob-scale`. `004` reviewed `040` only, and `001`–`003` all closed
*before* `039` — so the whole grammar rewrite (a section is called, arity is the verb, a
collection is an object), the single `:on()` door, the `hafen.vr()` move and the durable-place
contract were documented by the area that built them and never checked by the area that owns
the standard. That is the same gap `002`/`003`/`004` each opened with, and each time it held
wrong claims: nine in `003`, three in `004`.

The standard itself is stale with it: `style-guide.md` §7's grep list predates `039` and still
annotates as **live** names `039` deleted (`hafen.world.gridPos`, `hafen.world.fromGridPos`,
`hafen.ui.all`), while `043`'s close **filed** `hafen.ghost`/`hafen.render` for admission and
nothing added them. `041`'s retirements (`hafen.hook`, `onWidgetCreate`, the `on`-prefixed
widget callbacks, the `ev.` payload fields) are absent too. The mechanical state is good — the
current spellings read **zero** hits over `docs/` today — so the guard is what is missing, not
the cleanliness.

Two corrections the maintainer named, and the ROADMAP's own wrap-and-wording line, ride this
feature.

## Acceptance criteria

- [ ] **Coverage, both directions.** Every `hafen.*` surface the seven features shipped has an
      owning page, reachable from `docs/addons/api/README.md` in at most two clicks; and no page
      names a symbol absent from `src/`. The report carries the surface → page map and the two
      zero counts.
- [ ] **Accuracy.** Every claim on every page those seven features touched is checked against
      `src/`. The report lists each correction with the source it was checked against — a claim
      corrected from memory is not a correction.
- [ ] **§7 is current and falsified.** The names `039`, `041` and `043` retired are admitted
      under D-013's rule (or refused in writing, with the reason, as `:offset(` was); the "live"
      annotations `039` falsified are corrected. Every entry reads zero on the tree **and**
      catches a planted reintroduction.
- [ ] **No history and no defensiveness, tree-wide.** No obituary, no rename note, no correction
      addressed to a reader of an earlier page. `docs/addons/api/act.md:34`'s "used to" and every
      peer the sweep finds are gone; the final grep over the sweep's own pattern set reads zero,
      with the pattern set in the report.
- [ ] **`docs/README.md` opens professionally.** "It covers one thing" is gone, and so is "the
      ten addons" — a count in prose duplicating a list (§6), and wrong besides.
- [ ] **§12, tree-wide.** Links and anchors counted and zero broken, checker falsified in both
      directions; `wc -l` ≤ 300 on every page; headings clean (no em dash, no `#####`, no
      internal codes); wrap ≤ 110 columns in prose and < 100 inside fences — including the six
      lines the ROADMAP names.
- [ ] **The standard describes the tree.** `design/information-architecture.md` and both "API at
      a glance" tables carry `api/vr/**`, `api/event.md` and `api/ui/controls/**` as they are;
      the three places outside the two `AREA.md` files still spelling the tier
      `docs/addons/api/*.md` are corrected (ROADMAP).
- [ ] Anything found that is an engine or API defect is **filed to area `addons`**, named in the
      report, and never fixed here (AREA.md).
- [ ] Test protocol: `none` (AREA.md). Each task reports what it changed and what it checked, as
      plain lines the maintainer can spot-check.

## Out of scope

- Re-auditing what `002`, `003` and `004` certified, except where the seven features touched it.
- New pages, new guides or a new tier — this is a review; a page is created only where the
  coverage check finds a shipped surface with no owner.
- Any change to `src/`, to the `hafen.*` API, or to the addons under `addons/`.
- Tooling: the checks stay ad-hoc `grep`/`awk` runs (AREA.md).
- Cross-checking the `addons` area's own `specs/` for retired spellings — `docs/` is the tier
  under review, and `specs/` is history by design.

## Context files

- `specs/docs/design/style-guide.md` — the standard being enforced, and §7/§12 are edited here
- `specs/docs/design/information-architecture.md` — the target tree, which the review updates
- `specs/docs/decisions/docs-standard.md` — D-001..D-014, one entry opened at a time
- `specs/docs/learnings/docs-maintenance.md` — the slugger trap and the checker that over-reported
- `specs/docs/003-gob-overlays-review/`, `004-ui-controls-review/` — the review method that found
  nine and three wrong claims; their close reports are the shape this one takes
- `docs/**` — the tier under review (80 pages, 10,037 lines)
- `specs/addons/039-uniform-api/`, `041-unified-events/`, `043-vr-namespace/`,
  `044-spatial-ui/`, `045-durable-places/`, `046-gob-scale/` — what shipped, per task
- `specs/addons/042-event-driven-reads/` — no `hafen.*` change, but it changed **when** events
  fire; the pages that describe polling are the risk
- `src/io/brodgar/addon/**` — the only admissible backing for a claim about behaviour

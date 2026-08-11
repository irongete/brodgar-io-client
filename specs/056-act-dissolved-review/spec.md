# 056-act-dissolved-review — Spec

## What & why

Area `addons` closed two features since this area's last review (`005` covered `039`, `041`..`046`):
**`047-flowermenu`** and **`048-act-dissolved`**, the second of which deleted a whole namespace,
rehoused ten verbs onto the things they change, gated one that never was, and **renamed the tier's own
adjective** — `gated`/`ungated` became `protected`/`unprotected` across `src/` and `docs/`.

The pages themselves were carried by the shipping tasks and the substitution reads clean: `hafen.act`
and `gated`/`ungated` are both at **0** under `docs/`, `api/act.md` is gone and `api/flowermenu.md` is
in both indexes. What no shipping task can do for itself is the other half — **the accuracy of the
moved claims against `src/`**, and **the standard**, which still teaches `## Write (gated: actions)` in
§3 and §6, still uses `hafen.act():moveTo(p)` as its own worked example of D-237, still names
`api/act.md` in the IA's tree with no `flowermenu.md` beside it, and whose §7 whole-section list holds
**seven** bare names where the refusal table now holds eight — the new one, `hafen.act`, being a prefix
of a live spelling (`hafen.actionbar`) and admissible only as the regex `hafen\.act\b`. Both are the
standing ROADMAP entry, filed by the area that made the change.

The tree also has **no headroom left**: 80 pages, 10,274 lines, with `ui/widget.md` at 300,
`conventions.md` at 300 and `gob.md` at 299 — the three pages `048` wrote into.

## Acceptance criteria

- [ ] Every claim the `047`/`048` surfaces make is checked against `src/` and the wrong ones corrected:
      `api/flowermenu.md`, `api/player.md` (`:move`, `:hand`), `api/gob.md` (`:click`),
      `api/ui/items.md` (`:use`/`:take`/`:drop`/`:transfer`), `api/world.md` (`:place`/`:select`),
      `api/ui/widget.md` (`:send`), `api/menugrid.md` (`pag:use` and its new gate),
      `api/conventions.md` and `guides/actions-and-permissions.md`. The report names the file and line
      of `src/` behind each corrected claim, and lists the verified ones as a table.
- [ ] The standard reads in the shipped adjective: `style-guide.md` §3/§6 and the decisions that spell
      the annotation say `protected`/`unprotected`, and the change is recorded as a new decision rather
      than by rewriting an old entry's body.
- [ ] `style-guide.md` §7's whole-section list carries `hafen.act` in the one spelling that reads zero
      on a healthy tree, and the list's greps are re-derived from `Retired.java` and reported at zero,
      falsified by planting one and catching it.
- [ ] The IA's §3 tree is the tree as it stands: no `act.md`, `flowermenu.md` in place, and no reading
      order pointing at a page that is gone.
- [ ] No page under `docs/` is within 10 lines of the 300-line ceiling; every page that was split is
      split **by subject**, and every link into a moved heading is re-pointed in the same task.
- [ ] §12's seven checks are run over the whole tree and reported as counts with the offenders listed:
      links and anchors (0 broken, checker falsified both ways), `wc -l`, headings, the retired-name
      derivation with its row count, symbols both ways (the backward colon-verb sweep with every
      unregistered verb named), and §2's change-note constructions read rather than counted.
- [ ] The "Filed to area `addons` and still open" list in `specs/STATE.md` is re-checked against
      `src/` — `pag:use()`'s missing gate is claimed fixed by `048.5` — and what survives is restated.
- [ ] `/end` reports the tree's re-derived figures (pages, lines, links, the largest page) and the
      area's STATE, FEATURES and ROADMAP are updated from them, never carried forward.

## Out of scope

- **`049-css-selectors`, entirely.** It is ACTIVE and its docs land after its implementation. Pages
  describing the selector grammar are checked against what ships **today** and left in that state; a
  finding this review makes that `049` will change is recorded in the report and **not** written.
- Any edit under `src/`. An engine gap or a wrong behaviour found is filed to area `addons`, named in
  the task's report, and never fixed here.
- New pages, new guides and new tutorial steps: this is a review, not an extension. A page is created
  only as the half of a split.
- The example addons under `addons/` and their manifests.

## Context files

- `specs/standards/docs.md` — the standard being applied and, in §3/§6/§7, edited
- `specs/standards/docs-ia.md` — §3 is the tree and the authority; it is stale
- `specs/decisions/docs-standard.md` — D-231..D-246; the new decision appends here
- `specs/048-act-dissolved/` — the map of what moved where, and the eight tasks' own reports
- `specs/047-flowermenu/` — the correlation rule and the two events
- `src/io/brodgar/addon/Retired.java` — §7's list is derived from it, never remembered
- `src/io/brodgar/addon/` — the registration set the backward verb sweep tests against
- `docs/addons/api/` + `docs/addons/guides/actions-and-permissions.md` — the pages under review
- `specs/055-api-rewrite-review/census.md` — the census shape this feature repeats

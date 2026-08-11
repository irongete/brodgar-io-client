# 051-docs-overhaul — Plan

## Approach

**Evidence, then standard, then one pass per section group.** The audit comes first and edits
nothing: a coverage matrix built from `specs/FEATURES.md` + `STATE.md` (features 001–036,
task by task) crossed against `docs/addons/`, plus a page inventory with sizes and topics. That
matrix is the completeness contract — the close re-runs it and every row must be covered.

The standard follows, written *from* the audit: `design/style-guide.md` (page template, voice,
example rules, the ~300-line ceiling, link rules) and `design/information-architecture.md` (the
target tree + a migration map naming, for every current page and section, where it ends up).

Then the pages are rewritten **one section group per task, single pass**: fill the gaps, delete
the drift and the history, apply the template and land the page at its target path — a page is
touched once, not once per concern. Group A is the small read-side pages, group B the oversized
UI stack (`ui.md` 1171 → topic pages; `client.md` 688; `fonts.md` 436; `render.md` 336), group C
the cross-cutting and infrastructure pages. The tutorial and the new `guides/` tier come after
the reference is true, so guides can link into it instead of restating it. The close re-checks
every link and anchor and re-runs the matrix.

Three standing rules for every task: **no history** (a removed API is deleted, not noted); a
claim is backed by `src/` or by the `NNN-` folder that shipped it, cited in the report; and
links/anchors are re-checked after any heading is retitled or any page moves.

The verification is the maintainer reading the changed pages. There is no test protocol, no
script and no Python — the link/anchor and symbol sweeps are ad-hoc `grep` runs in the session,
reported as counts with the offenders listed.

## Files to create / modify

- `specs/051-docs-overhaul/audit.md` — the coverage matrix (feature × where documented) and
  the 39-page inventory. Written by 051.1, re-run at the close.
- `specs/standards/docs.md` — page template, voice, examples, ceiling, link rules.
- `specs/standards/docs-ia.md` — the target tree + the migration map.
- `docs/README.md` — new: the site root, one screen, pointing into `docs/addons/`.
- `docs/addons/README.md` — rewritten as the landing page + nav (its "API at a glance" table
  regenerated from the final tree).
- `docs/addons/getting-started.md` — rewritten as a real first-addon tutorial.
- `docs/addons/guides/*.md` — new: task-first how-tos (UI, events, saved data, permissions,
  theming, world reads, debugging), each linking into the reference.
- `docs/addons/api/*.md` — all 37 pages: rewritten in place, split, or merged per the migration
  map; `api/README.md` regenerated to list exactly what exists.
- `specs/{STATE,FEATURES,LEARNINGS}.md` — updated by `/end`; learnings go to
  `specs/learnings/docs-maintenance.md` (new category file).

## Risks & gotchas

- **Retitling a heading silently breaks every anchor pointing at it** (029.4). This feature
  retitles and moves nearly everything, so the link/anchor sweep is the *last step of every
  task*, not only of the close — ~690 links today, and the cross-page ones into `ui.md`'s
  sections are the fragile set (`README.md` alone links six `ui.md` anchors).
- **Rot lives in prose, not in tables** (035.4): the tables were rewritten with the code; what
  went stale is `>` notes, "Limits" paragraphs, counts ("eleven site keys") and promises ("a
  later feature"). The audit greps for the *promise shapes*, not only for API names.
- **The docs tier is the one place that must be true now** (030.4) — `specs/design/` docs are
  historical at write time and must NOT be used as ground truth. Ground truth is `src/` first,
  the feature's `NNN-` folder second.
- Completeness has a floor the audit must respect: some surfaces are documented as deliberately
  absent (no `hafen.music`; the skinning boundary, D-092). Those are present-tense design
  statements and stay; they are not history.
- A 36-feature matrix is the biggest single unit here. If 051.1 cannot finish it in a session,
  it splits by feature range (001–018 / 019–036) rather than shipping a partial matrix that
  reads as complete.
- Splitting `ui.md` risks scattering one mental model across five pages. The IA must give the
  new pages an explicit reading order and a hub page, or the split trades length for confusion.

## Discarded alternatives

- A committed checker script (Python or Java) — the maintainer's call: no tooling in this area.
  Sweeps are ad-hoc `grep` and reported as counts.
- A generated docs site (mkdocs/Docusaurus) — a build step and a dependency for a repo whose
  docs are read on disk and on GitHub; the structure is the problem, not the renderer.
- A "removed APIs" / migration page — no users, no release: history lives in git and `specs/`.
- Splitting purely by line count — pages must split by *topic*; a 320-line page that is one
  subject beats two 160-line halves of one.
- Fixing accuracy and restructuring in separate passes over the same pages — doubles the reading
  and the anchor churn; each page is rewritten once, in its group's task.

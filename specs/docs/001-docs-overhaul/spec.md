# 001-docs-overhaul — Spec

## What & why

`docs/addons/` grew task by task alongside 36 features: 5,727 lines over 39 pages, with
`api/ui.md` at **1,171** lines and `api/client.md` at **688**. Nobody re-reads prose, so it rots —
035.4's own sweep found a note that had been wrong since 035.2 — and a newcomer has no path in:
there is one tutorial page and then a wall of reference. This feature does three things, in order:
**make it true** (audit every claim against `src/`), **make it navigable** (split the oversized
pages, one topic per page), and **make it teachable** (a real first-addon tutorial plus
task-first guides with runnable examples). The target is the ordinary shape of modern developer
docs — *Getting started · Guides · Reference* — deep enough to get someone writing addons, not a
specification.

## Acceptance criteria

- [ ] `specs/docs/design/style-guide.md` and `information-architecture.md` exist and state the
      page ceiling, the page template, the voice, the example rules and the link rules. Every
      later task is checkable against them.
- [ ] An audit table covers **all 39 current pages**: verdict (keep / split / rewrite / merge /
      delete), owner section, and every drift found. Nothing is left unexamined.
- [ ] **Completeness is the headline: nothing shipped is undocumented.** A coverage matrix has a
      row per `addons` feature **001–036** and per task inside it, naming where each thing it
      shipped is documented. At the close every row is covered — no "partially", no silent
      omission. A capability that is deliberately not documented is listed with its reason.
- [ ] **Accuracy**: every `hafen.*` symbol named in `docs/addons/api/` is found in `src/`, and
      every namespace/verb/argument `src/` installs is documented, including error cases and
      gating. The report lists both directions; drift is fixed or, where it is an engine gap,
      filed to area `addons` and named in the report.
- [ ] **No page over the ceiling.** `ui.md`, `client.md`, `fonts.md` and `render.md` are split by
      topic, and the maintainer can open any resulting page and see one subject.
- [ ] Every internal link and `#anchor` under `docs/` resolves — checked by hand/grep, reported
      as a count, with the broken ones listed (there must be none at the close).
- [ ] `getting-started.md` takes a reader with no context from zero to a working addon, and every
      code block in it runs as written.
- [ ] A `docs/addons/guides/` set covers the tasks a new author actually has (UI, events, saved
      data, permissions, theming, debugging), each pointing into the reference rather than
      restating it.
- [ ] The indexes (`docs/addons/README.md`, `api/README.md`) list every page that exists and
      nothing that does not; a reader lands on the right page in at most two clicks.
- [ ] **No history in the docs.** Every mention of a removed or renamed API is **deleted**, not
      relocated: no obituaries, no "X is gone", no "was called Y". The docs describe what exists
      today. (A *capability* the reader would reasonably expect and that deliberately does not
      exist — no music API, the skinning system's stated limits — stays, phrased as a boundary of
      the current design, never as a change from a past one.)
- [ ] The maintainer reads the changed pages and confirms them (this area's Verification; there
      is no test protocol).

## Out of scope

- Any change to `src/`, the `hafen.*` API, or the addons under `addons/` — findings are filed,
  not fixed. (Example addons may be *cited*; they are not rewritten here.)
- Tooling: no checker script, no site generator, no Python, no new build step. Docs stay plain
  Markdown read on disk and on GitHub.
- Documenting anything not yet shipped, and any doc tier outside `docs/` (`specs/` is not docs).
- Any migration, changelog or deprecation tier. The project has no users and no release: history
  lives in git and in `specs/`. That changes only when something ships `@Deprecated` for real.
- Translation. Everything stays English.

## Context files

- `docs/addons/**` — the 39 pages under audit; the deliverable itself.
- `specs/docs/design/style-guide.md` + `information-architecture.md` — written by task 001.1,
  read by every later task.
- `specs/addons/AREA.md` — the boundary: who writes reference content after this lands.
- `specs/addons/STATE.md` — what actually shipped, feature by feature; the ground truth prose is
  audited against, and the pointer to the `NNN-` folder behind any doubtful claim.
- `src/io/brodgar/addon/AddonManager.java` (the `hafen` facade assembly, ~line 1240+) and the
  `*Api.java` installers beside it — the real symbol list, for the accuracy pass.
- `specs/codebase-map.md` — INDEX only; open a subsystem file when a claim needs checking.

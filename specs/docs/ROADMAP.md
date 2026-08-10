# ROADMAP — future work for this area

> Candidate features, not yet planned. One line each. `/plan` removes the line it picks up.

- **Split `api/conventions.md`.** It sits at exactly 300 lines, the ceiling, so the next convention any
  area ships has nowhere to go — 004.2's second split signal (no headroom), independent of D-001's prose
  test. Three pages sit within 15 lines of it (`gob.md`, `types.md`, `ui/widget.md`) and want the same
  read.
- **The tier's adjective is now `protected`/`unprotected` in the tree, and the standard still says
  `gated`/`ungated`.** `style-guide.md` §3 and §6 spell the group-heading annotation `## Write (gated:
  actions)` / `## Write (ungated)`, which no page under `docs/` writes any more; and §7's whole-section
  bare-name list says *seven* names where the refusal table now holds **eight** — the new one, `hafen.act`,
  is a PREFIX of a live spelling (`grep -F hafen.act` hits `hafen.actionbar` 19 times) and is admissible
  only as the regex `hafen\.act\b`, which is the first entry on that list needing a regex rather than a
  literal. Filed by the area that made the change; the standard is this area's to edit.

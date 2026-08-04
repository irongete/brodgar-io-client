# 002-map-database-review — Spec

## What & why

Area `addons` shipped `037-map-database` after `001-docs-overhaul` set the standard, and it wrote its own
reference content: `hafen.markers` and `hafen.radar` folded into `hafen.map`, `hafen.map`'s live half moved
to `hafen.world`, and five new subjects (segments and grids, overlays, drawings, markers, icon categories)
landed on one page. The re-pointing was done well — the tree is still link-clean (1,147 links, 0 broken),
no retired name from the §7 list is back, no heading trap, every inbound anchor resolves. Two things do not
meet the standard, and both are structural rather than cosmetic:

- **`docs/addons/api/map.md` is 436 lines** — the only page in the tree over the 300-line ceiling, over the
  350 hard limit, holding five `##` subjects and nine callouts (the next page in the tree has four). The
  style guide's split test applies literally: the reference index needs four "and"s to describe it. IA
  rule 3 says a namespace over the ceiling becomes a directory with a hub.
- **`map.md` carries an obituary** — "**`hafen.markers` and `hafen.radar` are gone.**" — which §7 forbids,
  and the retired names are not on the §7 grep list that guards against their return.

This feature brings the post-037 tree back to the standard and closes the two follow-ups `001` left filed.

## Acceptance criteria

- [ ] `docs/addons/api/map.md` is gone as a single page; `docs/addons/api/map/` holds a `README.md` hub
      plus one page per subject, **none over 300 lines**, each opening sentence free of "and".
- [ ] The hub gives the reading order; `docs/addons/api/README.md` lists **every** new leaf page and
      `docs/addons/README.md`'s at-a-glance row points at the hub. Every page in the tree is still within
      two clicks of `docs/addons/README.md` — measured as a traversal, not read off the index.
- [ ] Every inbound link into the old page is re-pointed **in the same task** (22 links, 14 anchored).
      Link sweep over the whole tree: count reported, **0 broken**, checker falsified in both directions.
- [ ] No obituary anywhere under `docs/`: the boundary between the recorded database and the live world is
      stated in the present tense, and `hafen.markers` / `hafen.radar` appear nowhere.
- [ ] `hafen.markers`, `hafen.radar`, `hafen.map.tile`, `hafen.map.gridPos`, `hafen.map.fromGridPos`,
      `hafen.map.screenToWorld`, `hafen.map.snapPlace` and `hafen.map.snapAngle` are on the retired-name
      grep list in `design/style-guide.md` §7, and the grep returns zero hits over `docs/`.
- [ ] The six checks of style guide §12 run over every page touched and every page linking into them, and
      are reported as counts with the offenders listed. Wrap drift (23 non-table lines over 110 columns
      tree-wide, 9 on 037-touched pages) is zero on every page this feature touches.
- [ ] Every `hafen.*` name this feature moves resolves to a registration in `src/`, cited in the task
      report. No page's content changes meaning: this feature moves and re-words, it does not
      re-document the surface, and no claim is restated from memory.
- [ ] `specs/docs/AREA.md` and `specs/addons/AREA.md` state the addons docs tier as `docs/addons/**` —
      the two widenings IA §7 filed and `001` deliberately left open.

## Out of scope

- Any change to `src/`, to the `hafen.*` API or to the addons under `addons/` — this area ships no code.
  An engine gap or a wrong behaviour found while reading is filed to area `addons` and named in the report.
- Re-auditing the whole tree against `src/`. `001.1`'s matrix stands; this feature re-checks only the
  surface `037` touched.
- `docs/addons/api/world.md` (161 lines) and the other 037-touched pages beyond the wrap and §12 checks —
  they are within the standard as written.
- New guides or examples: `guides/reading-the-world.md` and `examples.md` already carry `037`'s content.

## Context files

- `specs/docs/design/style-guide.md` — the standard being enforced (§6 facts, §7 no history, §9 size, §12 checks)
- `specs/docs/design/information-architecture.md` — rules 2–4 (namespace → path → directory + hub), §7 ownership, §8 link discipline
- `specs/docs/decisions/docs-standard.md` — D-001..D-011, opened per entry, never whole
- `specs/docs/learnings/docs-maintenance.md` — the sweep traps: the double-hyphen slug, the over-reporting checker
- `docs/addons/api/map.md` — the page being split
- `docs/addons/api/world.md`, `api/README.md`, `docs/addons/README.md`, `api/conventions.md`, `api/types.md`,
  `api/ghost.md`, `api/hook.md`, `api/asset.md`, `examples.md`, `guides/reading-the-world.md` — the inbound links
- `specs/addons/037-map-database/spec.md` + `tasks.md` — what shipped, and in which order
- `specs/docs/001-docs-overhaul/close.md` — the sweep and the coverage matrix this feature re-runs a slice of

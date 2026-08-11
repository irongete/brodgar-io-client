# 054-ui-controls-review — Spec

## What & why

Area `addons`' `040-ui-controls` shipped thirteen tasks, and each wrote its own slice of the docs
tier as it went: two new pages (`api/ui/controls.md`, `api/ui/lists.md`) plus edits to
`api/ui/widget.md`, `api/ui/custom.md`, `api/asset.md`, both indexes and `examples.md`. Nothing has
read that surface as a whole. Same shape as `002-map-database-review` and `003-gob-overlays-review`:
bring one feature's docs output back to the standard — accuracy against `src/`, then structure,
size and the §12 guards — and land the deltas the standard's own files owe.

Five things are already visible without opening the pages, and they set the floor:

- `api/ui/controls.md` is **exactly 300 lines**, the ceiling, with eleven per-control `##` sections.
  Whether that is one subject or two is the feature's first structural question (IA rule 3).
- **The IA does not know these pages exist**: `design/information-architecture.md` §3's tree lists
  neither, and §4's `api/ui/README.md` reading order does not mention them.
- **The §7 retired-name guard is red**: `examples.md:106` hits `:offset(`. Introduced by `039.15`,
  so it is inherited, but the guard is a tree-wide gate and it does not pass today.
- Neither new page uses the reference skeleton's `## Read` / `## Write (…)` groups — `controls.md`
  has `## Builders` and `## Setters`, carrying no gating annotation (§3, D-240).
- Prose counts are back (`Two faces or three`, `Four faces here, not two or three`, `the first
  three take the same …`) — the class of defect 003.2 removed eight of (§6).

## Acceptance criteria

- [ ] **Every claim on the 040 surface is true against `src/`**, checked the way 003.1 learned to
      check it: the registration string and the `LuaError` text, not the presence of a name. Each
      correction is cited to a file in the task's report; each page reads correctly as rendered
      Markdown. Verbs that answer `nil` on a control rather than throwing are stated as such.
- [ ] **Roster completeness both ways**: all **16** builders of `040`'s roster are documented, and
      no page names a builder, verb or key that does not exist. Reported as a list.
- [ ] **The reference skeleton is applied and the gating question is settled** — either every write
      group on the two pages carries its annotation, or a decision entry records why a control you
      built is your own UI and stays plain (the `hafen.ui.overlay` / `g:` precedent in §3). The
      answer is written into `decisions/docs-standard.md`, not left in a task report.
- [ ] **`controls.md` is under the ceiling with headroom, split by subject if it splits** — and if it
      splits, every inbound link and anchor is re-pointed in the same task (§8, D-231, IA rule 3).
- [ ] **The IA and the style guide match the tree they describe**: §3's tree and §4's reading order
      carry the pages that exist; §7's grep list is extended with any spelling `040` retired
      (`entry:text()` is one) under D-243's admission rule.
- [ ] **The §12 checklist is run and reported as counts with the offenders named**: links and anchors
      over every touched page *and every page linking into them*, falsified in both directions;
      `wc -l`; headings; the retired-name greps at **zero**, the inherited `examples.md` hit
      included; every `hafen.*` name found in `src/`; prose wrap at 110 columns measured in
      characters with the mixed-terminator fix (003.1).
- [ ] `STATE.md` closes on figures **re-derived from the tree**, not carried forward — today's 75
      pages / 8,081 lines describes a tree that is now 77 pages / 9,466 lines.
- [ ] Test protocol: **none** (`AREA.md`). Each task reports what it changed and what it checked as
      plain lines the maintainer can spot-check.

## Out of scope

- Any change under `src/`, to the `hafen.*` API, or to `040`'s addons and suites. An engine gap or a
  wrong behaviour found here is **filed to area `addons`** and named in the report, never fixed.
- The `ROADMAP.md` tree-wide wrap sweep (six inherited lines on pages no feature has touched) and
  the three stale `docs/addons/api/*.md` spellings outside `docs/`. Untouched pages stay untouched —
  except `examples.md`'s §7 hit, which the guard forces into scope.
- Re-auditing the pre-040 tree. `001`..`003` closed it; this feature reads the 040 surface and the
  pages that link into it.

## Context files

- `specs/standards/docs.md` — the standard being enforced (§3, §6, §7, §9, §10, §12)
- `specs/standards/docs-ia.md` — §3's tree, §4's reading orders, rules 2-4
- `specs/decisions/docs-standard.md` — D-231, D-236..D-240, D-242, D-243 (open only these)
- `specs/learnings/docs-maintenance.md` — grep it; 001.4/002.1's re-point and re-wrap prices,
  001.7's two-click traversal, 003.1's terminator fix, 001.2's registration-string oracle
- `specs/053-gob-overlays-review/` — the closest prior art: same shape, two tasks
- `specs/040-ui-controls/` — `spec.md`, `tasks.md`, `api-sketch.md`: the roster to check against
- `docs/addons/api/ui/controls.md`, `lists.md`, `widget.md`, `custom.md`, `README.md` — the surface
- `docs/addons/api/README.md`, `docs/addons/README.md`, `docs/addons/examples.md`, `api/asset.md` —
  the indexes and the two pages 040 edited outside `api/ui/`
- `specs/codebase/ui-controls.md` — 040.13's subsystem map, the entry point into `src/` for the
  accuracy pass

# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** none. `001-docs-overhaul` (001.1..001.7, plus 001.4b and 001.5b) and
`002-map-database-review` (002.1..002.2) are both **DONE**: the tree meets the standard it declares, and
the standard and both area contracts describe the tree that exists.

## docs/ — the deliverable

- **75 pages, 7,906 lines**, average ~105. Nothing over the 300-line ceiling (largest 250); the bimodal
  distribution of the old tree is gone. Three tiers, and a page belongs to exactly one: the tutorial,
  `guides/` (one task per page), `api/` (one namespace per page, a directory where it is large).
- **Sweep at the close of 002: 1,181 internal links, 0 broken**, checker falsified in both directions (a
  planted bad path and a planted bad anchor both caught; healthy tree reports zero). **11 links leave
  `docs/`** — `examples.md` → `addons/<id>/main.lua`, the one allowed exception (D-009). **0 retired
  names** over the full 22-name §7 list, 0 slugger-trap headings, 0 internal codes.
- **Wrap: 0 over-wide lines on every page 001 and 002 touched**, measured in columns (`perl -CSD`, not
  `awk`, which counts bytes). Seven remain tree-wide, on pages neither feature touched — see ROADMAP.
- **Every page is within two clicks of `docs/addons/README.md`** (42 at one, 31 at two) — measured as a
  traversal, not read off the index. `docs/README.md` is the site root above it; nothing links down to it.
- **Coverage: 140 `addons` task rows — 127 OK · 4 N/A · 9 CUT**, zero THIN and zero GAP
  (`001-docs-overhaul/close.md`). All **36** namespaces `src/` registers have an owning page; every
  `hafen.*` name under `docs/` resolves to a registration, bar the one stated absence (`hafen.music`);
  the deliberate omissions are listed with their reasons in `close.md` §4.
- **`hafen.map` is six pages** — the hub (the `nil`-until-loaded rule, interning, the reading order) plus
  `grids` `overlays` `drawings` `markers` `icons`, none over 106 lines.

## The standard

`design/style-guide.md` (voice, the three page kinds with a literal template, heading/anchor and example
rules, how facts are stated, the no-history rule + the retired-name grep list — **22 names**, the eight
`037` retired included, each separated from its live near-mate — link rules, the ceiling, the six checks
every docs task runs) and `design/information-architecture.md` (the target tree, now recording `api/map/`
and its reading order, the migration map, the link discipline). Twelve decisions in
`decisions/docs-standard.md` (D-001..D-012). Both files are the contract every later docs task — and area
`addons` — is checkable against. **Both `AREA.md` files state the addons docs tier as `docs/addons/**`**.

## What the features landed, task by task

- **001.1/001.2** the audit — 140 task rows + 36 feature rows, the 39-page inventory, 14 drift entries —
  and the standard written from it.
- **001.3/001.4/001.5** reference groups A, B and C: 21 + 22 + 10 pages, the four oversized pages gone.
- **001.6** the learning path: the tutorial, the 8 guides, `runtime.md`, `examples.md`.
- **001.7** the close: `docs/README.md`, the two indexes, the sweep, the matrix re-run.
- **002.1** the split: `api/map.md` → six pages, 22 inbound links re-pointed, the obituary deleted.
- **002.2** the close: the §7 grep list, the IA's tree and §7, the two `AREA.md` wordings, the wrap pass.

## Filed to area `addons` and still open

- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`.
- `specs/addons/017-gob-oop/tasks.md` has 017.2 unchecked while `FEATURES.md` records it done.
- `pag:use()` is an ungated write while the 18 other server-reaching verbs need `actions`.
- `dependencies` / `optional_dependencies` are parsed, validated and never used.

## Known and deliberate

Three places still spell the addons docs tier `docs/addons/api/*.md` — `CLAUDE.md`, the addons spec
template, and IA §5.3, which is 001's migration record rather than a target. All three are on the ROADMAP.

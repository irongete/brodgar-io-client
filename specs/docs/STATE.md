# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** none. `001-docs-overhaul` is **DONE** (001.1..001.7, plus 001.4b and 001.5b): the
`docs/` tree is true, navigable and learnable, and it is link-clean. The next feature comes from
`ROADMAP.md` (empty today) or from whatever the maintainer asks `/plan docs` for.

## docs/ — the deliverable

- **72 pages, 7,460 lines**, average ~104. Nothing over the 300-line ceiling (largest 250); the bimodal
  distribution of the old tree is gone. Three tiers, and a page belongs to exactly one: the tutorial,
  `guides/` (one task per page), `api/` (one namespace per page).
- **Sweep at the close: 1,112 internal links, 0 broken**, checker falsified in both directions (planted
  breaks caught; healthy tree reports zero). **10 links leave `docs/`** — `examples.md` →
  `addons/<id>/main.lua`, the one allowed exception (D-009). 0 retired names, 0 slugger-trap headings,
  0 internal codes.
- **Every page is within two clicks of `docs/addons/README.md`** (44 at one, 26 at two) — measured as a
  traversal, not read off the index. `docs/README.md` is the site root above it.
- **Coverage: 140 `addons` task rows — 127 OK · 4 N/A · 9 CUT**, zero THIN and zero GAP
  (`001-docs-overhaul/close.md`). All **36** namespaces `src/` registers have an owning page; every
  `hafen.*` name under `docs/` resolves to a registration, bar the one stated absence (`hafen.music`).
  Deliberate omissions are listed with their reasons in `close.md` §4.

## The standard

`design/style-guide.md` (voice, the three page kinds with a literal template, heading/anchor and example
rules, how facts are stated, the no-history rule + the retired-name grep list, link rules, the ceiling,
the six checks every docs task runs) and `design/information-architecture.md` (the target tree, the
reading orders, the migration map, the link discipline). Twelve decisions in
`decisions/docs-standard.md` (D-001..D-012). Both files are the contract every later docs task — and
area `addons`, when it writes reference content — is checkable against.

## What the feature landed, task by task

- **001.1** the audit: 140 task rows + 36 feature rows, the 39-page inventory, 14 drift entries.
- **001.2** the standard, written from that evidence.
- **001.3** reference group A, the read side: 21 pages.
- **001.4** reference group B, the UI stack: 22 new pages, the four oversized pages gone.
- **001.5** reference group C, cross-cutting and infrastructure: 10 pages, 24 more re-pointed.
- **001.6** the learning path: the tutorial, the 8 guides, `runtime.md`, `examples.md`.
- **001.7** the close: `docs/README.md`, the two indexes, the sweep, the matrix re-run.

## Filed to area `addons` and still open

- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`.
- `specs/addons/017-gob-oop/tasks.md` has 017.2 unchecked while `FEATURES.md` records it done.
- `pag:use()` is an ungated write while the 18 other server-reaching verbs need `actions`.
- `dependencies` / `optional_dependencies` are parsed, validated and never used.

## Known and deliberate

Both `AREA.md` files still describe the addons docs tier as `docs/addons/api/*.md`; after the migration
the tier is `docs/addons/**` (nested reference pages, plus `runtime.md` and `examples.md`). Widening
those two wordings changes an area contract and belongs to whoever edits them, not to this feature
(IA §7).

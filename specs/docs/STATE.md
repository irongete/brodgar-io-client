# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `003-gob-overlays-review` — area `addons`' `038-gob-overlays` brought back to the
standard. **003.1 is DONE**: the both-directions sweep (65 `hafen.*` names, 13 `ov:` verbs, 14 spec
fields, 16 error sites) landed **nine** corrections on five pages. **003.2 remains** — the four
obituaries, the measured figure, the three prose counts, the `## Read` row, the two headings, §7's grep
list. `001-docs-overhaul` and `002-map-database-review` are **DONE**.

## docs/ — the deliverable

- **75 pages, 8,078 lines**, average ~108 — re-derived, not carried forward (002's close read 7,906,
  before 038 wrote ~140 lines of its own). Nothing over the 300-line ceiling (largest 254, `api/gob.md`).
  Three tiers, and a page belongs to exactly one: the tutorial, `guides/` (one task per page) and `api/`
  (one namespace per page, a directory where it is large).
- **Sweep at the close of 003.1: 1,193 internal links, 0 broken**, checker falsified in both directions.
  **12 links leave `docs/`** — `examples.md` → `addons/<id>/main.lua`, the one exception (D-009). **0
  retired names** over the 22-name §7 list, 0 slugger-trap headings, 0 internal codes; the one live
  retired name is `hafen.ui.gobOverlay` (`ui/custom.md:77`), which 003.2 removes as it adds it to §7.
- **Wrap: 0 drift introduced by 001, 002 or 003.1**, in columns (`perl -CSD` with `s/\r?\n?$//` — the tree
  is **mixed CRLF/LF per file** and `chomp` alone over-reports by one on the CRLF half). Seven inherited
  lines remain; two sit on pages 003.1 touched (`sprites.md:81` at 111, `player.md:29` at 131 — ROADMAP).
- **Every page is within two clicks of `docs/addons/README.md`** (42 at one, 31 at two), measured as a
  traversal. `docs/README.md` is the site root above it; nothing links down to it.
- **Coverage: 140 `addons` task rows — 127 OK · 4 N/A · 9 CUT**, zero THIN and zero GAP
  (`001-docs-overhaul/close.md`). All **36** namespaces have an owning page; every `hafen.*` name resolves
  to a registration, bar the stated absence (`hafen.music`).

## The standard

`design/style-guide.md` (voice, the three page kinds and their template, heading/anchor and example rules,
how facts are stated, the no-history rule + the **22-name** retired grep list, links, the ceiling, the six
checks every docs task runs), `design/information-architecture.md` (the target tree, the migration map,
the link discipline) and `decisions/docs-standard.md` (D-001..D-012) are the contract every later docs
task — and area `addons` — is checkable against. **Both `AREA.md` files say `docs/addons/**`**.

## What the features landed

- **001** the audit (140 task rows, 36 feature rows, 14 drift entries) and the standard from it; reference
  groups A, B and C (21 + 22 + 10 pages, the four oversized gone); the learning path — tutorial, 8 guides,
  `runtime.md`, `examples.md`; then `docs/README.md`, the two indexes and the close.
- **002** `api/map.md` → six pages (the hub + `grids` `overlays` `drawings` `markers` `icons`), 22 links
  re-pointed, the obituary gone; then §7's list, the IA's tree, the two `AREA.md` wordings.
- **003.1** `ghost.list`'s exclusion of overlay-owned ghosts, the four arities' `nil` on a gone gob, the
  two attach raises, `ov:info()`'s two shapes, `clickable`/`onClick` scoped to world space, the native
  refusal of the five composed verbs, `ov:pos()` out of the chaining claim, `events.md`'s gob-leaves rule
  narrowed to yours, `player.md`'s "gob overlay" link re-pointed, and `sdt` on the world-spec table.

## Filed to area `addons` and still open

- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s
  017.2 is unchecked while `FEATURES.md` records it done.
- `pag:use()` is an ungated write while the 18 other server-reaching verbs need `actions`;
  `dependencies` / `optional_dependencies` are parsed, validated and never used.
- `clickable`/`onClick` on a **screen-space** overlay spec are silently ignored (the refusal is in
  `RenderApi.overlayEntity`, which `LuaGobOverlay.Attach.of` returns before reaching); `x`/`y` in any
  overlay spec are read and then overridden by the anchor. Both are D-072's case.

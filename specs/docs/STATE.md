# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** none. `001-docs-overhaul`, `002-map-database-review` and `003-gob-overlays-review` are
all **DONE** — the post-038 surface is back to the standard. Next work comes from `ROADMAP.md`.

## docs/ — the deliverable

- **75 pages, 8,081 lines**, average ~108 — re-derived from the tree, not carried forward. Nothing over the
  300-line ceiling (largest 257, `api/gob.md`). Three tiers, and a page belongs to exactly one: the
  tutorial, `guides/` (one task per page) and `api/` (one namespace per page, a directory where it is large).
- **Sweep at the close of 003.2: 1,192 internal links, 0 broken**, checker falsified in both directions (a
  bad path, a bad cross-page anchor and a bad same-page anchor all caught; zero on restore). **12 links
  leave `docs/`** — `examples.md` → `addons/<id>/main.lua`, the one exception (D-009). **0 retired names**
  over the **28**-entry §7 list, 0 slugger-trap headings, 0 `#####`, 0 internal codes, 0 `--` slugs.
- **Wrap: 0 drift introduced by 001, 002 or 003**, in columns (`perl -CSD` with `s/\r?\n?$//` — the tree is
  **mixed CRLF/LF per file** and `chomp` alone over-reports by one on the CRLF half). **Six** inherited
  lines remain, all on pages no feature has touched (ROADMAP); `render/sprites.md` left that list in 003.2.
- **Every page is within two clicks of `docs/addons/README.md`** (42 at one, 31 at two), measured as a
  traversal. `docs/README.md` is the site root above it; nothing links down to it (D-012).
- **Coverage: 140 `addons` task rows — 127 OK · 4 N/A · 9 CUT**, zero THIN and zero GAP
  (`001-docs-overhaul/close.md`). All **36** namespaces have an owning page; every `hafen.*` name resolves
  to a registration, bar the stated absence (`hafen.music`).

## The standard

`design/style-guide.md` (voice, the three page kinds and their template, heading/anchor and example rules,
how facts are stated, the no-history rule + the **28-entry** retired grep list in two blocks, links, the
ceiling, the six checks every docs task runs), `design/information-architecture.md` (the target tree, the
migration map, the link discipline) and `decisions/docs-standard.md` (D-001..D-013) are the contract every
later docs task — and area `addons` — is checkable against. **Both `AREA.md` files say `docs/addons/**`**.

## What the features landed

- **001** the audit (140 task rows, 36 feature rows, 14 drift entries) and the standard from it; reference
  groups A, B and C (21 + 22 + 10 pages, the four oversized gone); the learning path — tutorial, 8 guides,
  `runtime.md`, `examples.md`; then `docs/README.md`, the two indexes and the close.
- **002** `api/map.md` → six pages (the hub + `grids` `overlays` `drawings` `markers` `icons`), 22 links
  re-pointed, the obituary gone; then §7's list, the IA's tree, the two `AREA.md` wordings.
- **003.1** the accuracy pass, from the second oracle (`spec.get("…")` reads and `LuaError` strings, not the
  registration grep): `ghost.list`'s exclusion of overlay-owned ghosts, the four arities' `nil` on a gone
  gob, the two attach raises, `ov:info()`'s two shapes, `clickable`/`onClick` scoped to world space, the
  native refusal, `ov:pos()` out of the chaining claim, `events.md`'s gob-leaves rule narrowed to yours,
  `player.md`'s "gob overlay" link re-pointed, `sdt` on the world table.
- **003.2** the four obituaries → present-tense boundaries (and out of their blockquotes, §10); the `13 of
  33` figure and eight prose counts gone; `gob:overlay` out of `## Read`, ungated stated in prose
  (`## Overlays` **not** retitled — 12 inbound links); the two `ON a gob` headings in sentence case; §7
  extended by four falsified spellings under **D-013** (a spelling, not a name, when the name survives).

## Filed to area `addons` and still open

- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s
  017.2 is unchecked while `FEATURES.md` records it done.
- `pag:use()` is an ungated write while the 18 other server-reaching verbs need `actions`;
  `dependencies` / `optional_dependencies` are parsed, validated and never used.
- `clickable`/`onClick` on a **screen-space** overlay spec are silently ignored (the refusal is in
  `RenderApi.overlayEntity`, which `LuaGobOverlay.Attach.of` returns before reaching); `x`/`y` in any
  overlay spec are read and then overridden by the anchor. Both are D-072's case.

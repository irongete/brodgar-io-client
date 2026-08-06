# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** none. `001-docs-overhaul`, `002`, `003-gob-overlays-review` and
`004-ui-controls-review` are all **DONE**; figures below are re-derived post-004.

## docs/ — the deliverable

- **79 pages, 9,507 lines**, average ~120 — re-derived from the tree, not carried forward. Nothing over
  the 300-line ceiling (largest is exactly 300, `api/conventions.md`, pre-004). Three tiers, and a page
  belongs to exactly one: the tutorial, `guides/` (one task per page) and `api/` (one namespace per page,
  a directory where it is large — `map/`, `style/`, `client/profiling/` and, since 004.2, `ui/controls/`).
- **Sweep at the close of 004.2: 1,392 internal links, 0 broken**, checker falsified in both directions (a
  bad path, a bad cross-page anchor and a bad same-page anchor all caught; zero on restore). **13 links
  leave `docs/`** — `examples.md` → `addons/<id>/main.lua`, the one exception (D-009; up from 12 as
  `tagger` shipped). **0 retired names** over the **28**-entry §7 list (`:offset(` swapped for
  `entry:text(` — it collided with live `ov:offset`/`p:offset`), 0 slugger-trap headings, 0 `#####`, 0
  internal codes, 0 `--` slugs.
- **Wrap: 0 drift tree-wide** (`perl -CSD` with `s/\r?\n?$//` — the tree is **mixed CRLF/LF per file** and
  `chomp` alone over-reports by one on the CRLF half). The six lines 003.2 left inherited are gone too.
- **Every page is within two clicks of `docs/addons/README.md`** (42 at one, 35 at two), measured as a
  traversal. `docs/README.md` is the site root above it; nothing links down to it (D-012).
- **Coverage: 140 `addons` task rows — 127 OK · 4 N/A · 9 CUT**, zero THIN and zero GAP
  (`001-docs-overhaul/close.md`). All **36** pre-040 namespaces have an owning page; every `hafen.*` name
  resolves to a registration, bar the stated absence (`hafen.music`).

## The standard

`design/style-guide.md` (voice, the three page kinds and their template, heading/anchor and example rules,
how facts are stated, the no-history rule + the **28-entry** retired grep list in two blocks, links, the
ceiling, the six checks every docs task runs), `design/information-architecture.md` (the target tree, the
migration map, the link discipline) and `decisions/docs-standard.md` (D-001..D-014) are the contract every
later docs task — and area `addons` — is checkable against. **Both `AREA.md` files say `docs/addons/**`**.

## What the features landed

- **001** the audit (140 task rows, 36 feature rows, 14 drift entries) and the standard from it; reference
  groups A, B and C (21 + 22 + 10 pages, the four oversized gone); the learning path — tutorial, 8 guides,
  `runtime.md`, `examples.md`; then `docs/README.md`, the two indexes and the close.
- **002** `api/map.md` → six pages (the hub + `grids` `overlays` `drawings` `markers` `icons`), 22 links
  re-pointed, the obituary gone; then §7's list, the IA's tree, the two `AREA.md` wordings.
- **003** nine corrections from the second oracle (`spec.get("…")`/`LuaError`, not registration): the
  four obituaries → present-tense boundaries; a measured figure and eight prose counts gone; `gob:overlay`
  ungated in prose, not its `## Read` heading; §7 extended under **D-013**.
- **004.1** three corrections against `src/`, the 16-builder roster checked both ways, a 115-column fix.
- **004.2** `controls.md` (300/300, zero headroom) split into `controls/` — hub + `display` +
  `interactive`, none over 155, 37 links re-pointed; gating settled in prose, not a heading (**D-014**);
  §7's `:offset(` correction (above); the IA taught `api/ui`'s two 040 pages exist.

## Filed to area `addons` and still open

- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s
  017.2 is unchecked while `FEATURES.md` records it done.
- `pag:use()` is an ungated write while the 18 other server-reaching verbs need `actions`;
  `dependencies` / `optional_dependencies` are parsed, validated and never used.
- `clickable`/`onClick` on a **screen-space** overlay spec are silently ignored (the refusal is in
  `RenderApi.overlayEntity`, which `LuaGobOverlay.Attach.of` returns before reaching); `x`/`y` in any
  overlay spec are read and then overridden by the anchor. Both are D-072's case.

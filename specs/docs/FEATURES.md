# FEATURES — index of `NNN-` folders

> ONE line per feature folder: `NNN-name — STATUS — summary (design doc) — tasks`.
> STATUS ∈ ACTIVE / DONE / PENDING. Folders are never archived — they are the addressable
> history of the area; closed ones are only opened when named.

- `001-docs-overhaul` — DONE — `docs/` rebuilt as a product: 39 pages → **72** (7,460 lines, none over
  300), audited against `src/` and re-checked at the close (140 coverage rows, 0 GAP; 1,112 links, 0
  broken), plus a tutorial, a `guides/` tier, `runtime.md` and `examples.md`
  (`design/style-guide.md`, `design/information-architecture.md`) — tasks 001.1..001.7 + 001.4b, 001.5b
- `002-map-database-review` — DONE — area `addons`' `037-map-database` brought back to the standard: the
  436-line `api/map.md` split into `api/map/` (six pages, none over 106; 22 inbound links re-pointed), the
  obituary deleted, the §7 grep list extended to **22 names**, the IA and both `AREA.md` files widened to
  `docs/addons/**`, wrap drift zeroed on the 037 surface — **75 pages, 7,906 lines, 1,181 links, 0 broken**
  (`design/style-guide.md` §7/§9/§12, `design/information-architecture.md` rules 2–4) — tasks 002.1..002.2
- `003-gob-overlays-review` — DONE — area `addons`' `038-gob-overlays` brought back to the standard: nine
  claims corrected against `src/` (`ghost.list`'s exclusion, the four arities' absence case, two raises,
  `ov:info()`'s two shapes, the native refusal, `clickable`/`onClick` scoped to world space), the four
  obituaries restated as present-tense boundaries, a measured figure and eight prose counts removed,
  `gob:overlay` classified out of the `## Read` table with its ungated statement in prose, and the §7 grep
  list extended to **28 entries** under a new admission rule (D-013) — **75 pages, 8,081 lines, 1,192
  links, 0 broken** (`design/style-guide.md` §4/§6/§7/§10/§11/§12) — tasks 003.1..003.2
- `004-ui-controls-review` — DONE — area `addons`' `040-ui-controls` brought back to the standard:
  accuracy against `src/` and the 16-builder roster checked both ways (004.1); `api/ui/controls.md`
  (300/300, zero headroom) split into `api/ui/controls/` — hub + `display` + `interactive`, none over
  155, 37 links re-pointed; the write-group gating settled in prose, not a heading (**D-014**); the §7
  grep list corrected (`:offset(` pulled — 17 false hits from live `ov:offset`/`p:offset` — `entry:text(`
  admitted instead); the IA's tree and `api/ui` reading order taught the pages exist — **79 pages, 9,507
  lines, 1,392 links, 0 broken** (`design/style-guide.md`, `design/information-architecture.md`,
  `decisions/docs-standard.md` D-014) — tasks 004.1..004.2
- `005-api-rewrite-review` — DONE — the seven area-`addons` features that closed with no docs review
  (`039-uniform-api`, `041`..`046`): the census both ways (`census.md`), **ten** wrong claims corrected
  against `src/` on `api/event.md`, `api/ui/items.md`, `api/ui/widget.md`, `api/world.md` and
  `api/ui/style/README.md`, and the THIN rows filled on `api/vr/**`; §7 **derived from the engine's refusal
  table** (**D-015**: 93 `hafen.*` + 75 entity + 4 event keys, one regex for 86 of them, the entity half
  guarded by the backward verb sweep) replacing 28 hand-kept names that called six refused names live;
  §2's word ban scoped to the change-note (**D-016**: 243 legitimate hits, 7 real ones fixed); the IA's
  tree, reading orders and `001`-record boundary; the two READMEs, all ten over-wide lines and three
  §10 callout pairs — **80 pages, 10,059 lines, 1,473 links, 0 broken**
  (`design/style-guide.md`, `design/information-architecture.md`, `decisions/docs-standard.md`
  D-015/D-016) — tasks 005.1..005.4
- `006-act-dissolved-review` — ACTIVE — the two area-`addons` features that closed since `005`
  (`047-flowermenu`, `048-act-dissolved`): the rehoused verbs checked against `src/`, the standard
  brought onto the shipped adjective (`protected`/`unprotected`) with `hafen.act` on §7's list, the IA's
  tree, the three ceiling pages split, and §12 over the whole tree. `049-css-selectors` is out of scope
  (`design/style-guide.md`, `design/information-architecture.md`, `decisions/docs-standard.md`)
  — tasks 006.1..006.4

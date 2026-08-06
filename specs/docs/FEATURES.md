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

# 002-map-database-review — Tasks

<!-- Area `docs` declares Test protocol: none. A task is verified by the maintainer reading the
     changed pages as rendered Markdown, against the spec's acceptance criteria; the task's report
     carries the §12 check counts with the offenders listed, and cites `src/` for every claim it
     moved. Nothing is rebuilt and nothing is restarted. -->

- [x] **002.1 — the split**: `docs/addons/api/map.md` becomes `docs/addons/api/map/` — the hub plus
      `grids` `overlays` `drawings` `markers` `icons`, cut exactly as `plan.md`'s table says. The hub keeps
      the `nil`-until-loaded rule and interning; the recorded-vs-live boundary is stated in the present
      tense and the obituary is gone. Every one of the 22 inbound links is re-pointed, the 20 self-anchors
      become cross-page links, the 26 outbound links gain their `../`, and `api/README.md` lists all six
      new pages while `docs/addons/README.md` points at the hub.
      **Report**: `wc -l` per new page (none over 300) · link sweep over the whole tree, count and 0
      broken, falsified in both directions · BFS from `docs/addons/README.md`, every page at depth ≤ 2 ·
      heading greps (`^#.*( [—/&] |\[, )`, `#####`, internal codes) · `hafen.markers` / `hafen.radar`
      zero hits · every `hafen.*` name that moved, cited to its `set("…"` registration in
      `src/io/brodgar/addon/`.
      **`[manual]`**: that the hub reads as a page and not as an index, and that each leaf's opening
      sentence needs no "and" — the maintainer's read, not a grep.

- [ ] **002.2 — the close**: the standard and the contract catch up with what shipped. `style-guide.md`
      §7's grep list gains the eight names `037` retired (`hafen.markers`, `hafen.radar`, `hafen.map.tile`,
      `.gridPos`, `.fromGridPos`, `.screenToWorld`, `.snapPlace`, `.snapAngle`);
      `information-architecture.md` §3 records `api/map/` in the target tree and §7 marks the tier widening
      done; `specs/docs/AREA.md` and `specs/addons/AREA.md` read `docs/addons/**`. Wrap drift goes to zero
      on every page this feature touched (9 non-table lines over 110 columns today).
      **Report**: the extended grep list run over `docs/`, zero hits · `awk 'length>110'` on every touched
      page, zero · the full sweep re-run after this cosmetic pass — not before it (001.4) · the tree's
      page and line totals re-derived from the artefact, never quoted from `STATE.md` (001.7).
      **`[manual]`**: that the two `AREA.md` wordings still describe their areas correctly after the
      widening — an area contract is the maintainer's to accept.
      <!-- extra context: `specs/docs/AREA.md`, `specs/addons/AREA.md` (only this task edits them) -->

# 003-gob-overlays-review — Tasks

<!-- Area `docs` declares Test protocol: none. A task is verified by the maintainer reading the
     changed pages as rendered Markdown, against the spec's acceptance criteria; the task's report
     carries the §12 check counts with the offenders listed, and cites `src/` for every claim it
     writes. Nothing is rebuilt and nothing is restarted. -->

- [x] **003.1 — the accuracy pass: what the pages say, checked against what shipped.** Run the
      both-directions symbol sweep over the 038 surface with the two oracles `plan.md` names — the
      registration sites for verbs, `spec.get("…")` and the `LuaError` strings for spec fields and error
      cases — including every name inside a fence, and land the corrections it confirms. The four known
      ones: `ghost.md`'s `hafen.ghost.list(filter)` states that an overlay-owned ghost is not in it and
      is reached through `gob:overlay`; `gob.md`'s call table states the absence case of **all four**
      arities on a gone gob (`nil`, the attach and the remove included) and the two raises (a gob not
      renderable yet — attach from `GobAdded`; a world-space overlay with no map view yet); `ov:info()`
      is published as the two shapes the code writes, `world` and `kind` being yours only. Anything else
      the sweep turns up is corrected here or, if the code is what is wrong, filed to area `addons`.
      **Report**: the sweep as two lists — every name on a page → its site in `src/`, and every registered
      verb / spec field / error → the page that carries it, with the gaps named · each correction quoted
      against the file and line it came from · `wc -l` per touched page · link and anchor sweep over the
      touched set and every page linking into it, count and 0 broken, falsified in both directions ·
      `perl -CSD` column check, diffed against `HEAD` so inherited drift is not claimed.
      **`[manual]`**: that the corrected sentences read as descriptions of the API rather than as errata —
      a reader who has never seen the previous version must not be able to tell one was corrected.
      <!-- extra context: `src/io/brodgar/addon/LuaGob.java`, `LuaOverlay.java`, `LuaGobOverlay.java`,
           `RenderApi.java` — only this task reads them -->

- [ ] **003.2 — the standard, and the close.** The four obituaries become present-tense boundaries
      (`ghost.md:158`, `render/sprites.md:116`, `ui/custom.md:77`, and the two "used to float / used to
      be" clauses), keeping the behaviour each carried. The measured figure leaves `gob.md`'s `:count()`
      paragraph; the counts leave `gob.md` ("one of five things"), `events.md` ("Four rules") and the
      four index pages ("twelve addons"). `gob:overlay(…)` leaves the `## Read` table and the gating
      sentence goes in prose — **`## Overlays` is not retitled**. The two `ON a gob` headings are
      retitled to sentence case, with their inbound links checked **before** the edit. `render/sprites.md`
      goes under 110 columns. `style-guide.md` §7's grep list gains the spellings that pass both
      directions; a candidate that cannot both read zero on the healthy tree and catch a planted
      reintroduction is not admitted, and the report says which were rejected and why.
      **Report**: the six §12 checks over every page this feature touched and every page linking into
      them, run **after** this cosmetic pass · the extended grep list over `docs/`, zero hits, each entry
      falsified · heading greps (`^#.*( [—/&] |\[, )`, `#####`, internal codes) and a slug pass for `--` ·
      two-click reachability re-measured as a traversal · the tree's page and line totals re-derived from
      the artefact, never quoted from `STATE.md`.
      **`[manual]`**: that no page reads as a change note any more — the §7 test applied by hand, deleting
      every clause that only makes sense to someone who read the previous version.
      <!-- extra context: `specs/docs/design/style-guide.md` §7 (only this task edits it) -->

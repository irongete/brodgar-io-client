# 056-act-dissolved-review — Tasks

- [x] 056.1 — **The census.** Build `census.md`: one row per rehoused `048` verb (`hafen.player():move`,
      `gob:click`, `hafen.player():hand()` + `hand:item`/`:use`, `item:use`/`:take`/`:drop`/`:transfer`,
      `hafen.world():place`/`:select`, `widget:send`, `pag:use` and its new gate) and per `047` surface
      (`:list`/`:count`/`:gob`, `:select`/`:cancel`, `FlowerMenuOpened`/`Closed`) — page, anchor, the
      claim, what `src/` does, verdict. Both directions: no shipped verb is undocumented, no documented
      verb is invented. Report: the table, every WRONG row with the `src/` file and line behind it, and
      the deleted-verb check (`:menu`, `:flower`, `:enabled`, and `raw`'s `"mapview"`/`"gameui"`/`"root"`
      target vocabulary) at zero under `docs/`. **Corrects nothing** — 056.2 writes.
      <!-- extra context: `specs/048-act-dissolved/` (all eight task reports), `specs/047-flowermenu/` -->

- [x] 056.2 — **The corrections, and the standard.** Fix every WRONG row 056.1 found, in the page's own
      voice and without a change-note. Then the standard: `style-guide.md` §3/§6 and the IA's two
      `ungated` lines take `protected`/`unprotected`; §7's whole-section list gains `hafen.act` as
      `hafen\.act\b`, with the falsification (plant, catch, remove) and the note that it is the first
      entry needing a regex; §4 and §7's worked examples stop being written as `hafen.act():moveTo(p)`;
      the IA's §3 tree drops `act.md` and carries `flowermenu.md`; one new decision records the
      adjective, leaving D-240/D-244 as written. Report: every page changed with its `wc -l`, the four
      §7 greps at zero re-derived from `Retired.java` with the row count stated as what it is.

- [x] 056.3 — **The ceiling.** `conventions.md` (300), `ui/widget.md` (300) and `gob.md` (299) split by
      subject, the seam chosen after counting each candidate's **inbound anchors**. Every link into a
      moved heading re-pointed in the same task; `api/README.md` lists every new leaf and
      `docs/addons/README.md`'s glance table stays true; the two-click reachability re-measured as a
      graph. Report: the before/after `wc -l` of every page touched, the anchor count moved, links
      checked with the checker falsified both ways (bad path, cross-page anchor, same-page anchor, a
      break inside a wrapped link).

- [x] 056.4 — **The sweep and the close.** §12's seven checks over the **whole** tree, each re-derived
      rather than copied: links and anchors, `wc -l`, headings, the retired-name derivation with its
      generators expanded, symbols both ways (the backward colon-verb sweep, every unregistered verb
      named and accounted for), §2's change-note constructions read rather than counted, the
      two-threshold wrap check (110 prose / 100 in a fence). Plus: `hafen.act` and `gated`/`ungated`
      re-confirmed at zero, the old-grammar greps (a field read where a call belongs) for `048`'s moved
      verbs, and `STATE.md`'s "Filed to area `addons`" list re-checked against `src/` — `pag:use()`'s
      missing gate is claimed fixed by 048.5. Close: STATE and FEATURES rewritten from the tree's
      re-derived figures, learnings appended, `/end`'s single commit.

<!-- 049-css-selectors is out of scope in EVERY task: the selector pages are read against what ships
     today, and a finding 049 will overwrite is reported and left in the tree. -->

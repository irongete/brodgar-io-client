# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `001-docs-overhaul` — audit, restructure and teach: make `docs/addons/` true,
navigable and learnable. **001.1, 001.2 and 001.3 DONE**; 001.4..001.7 open. The evidence and the
standard are written; the migration has started and the reference tree is being rewritten group by
group, link-clean at every task boundary.

**001.1 DONE — the audit** (`001-docs-overhaul/audit.md`), the evidence every later task is measured
against: a coverage matrix of 36 feature rows + all 140 tasks of `addons` 001–036 with five verdicts
(**107 OK · 11 THIN · 3 GAP · 8 N/A · 11 CUT**), the 39-page inventory, and 14 drift entries each
checked against `src/`. Its findings: five shipped example addons named nowhere under `docs/`; four
pages holding 45% of all lines; the stylesheet documented twice (`fonts.md` and `ui.md`), whose
sharpest symptom is `widget:skin{…}` having no section in `ui.md` while it links out for it 8 times;
no owning page for the engine/dev tier. Sweeps: 695 internal links, **0 broken**; 16 leaving `docs/`;
26/26 events and 18/18 gated verbs documented.

**001.2 DONE — the standard**, written from that evidence: `design/style-guide.md` (voice, the three
page kinds with a literal reference template, heading and anchor rules, example rules, how facts are
stated, the no-history rule + a 13-name retired grep list, link rules, the ceiling, the six checks
every docs task runs) and `design/information-architecture.md` (the **72-page target tree**, each
hub's reading order, the section-level migration map for all 39 current pages, which task lands what,
and the link discipline). Decisions **D-001..D-005** (`decisions/docs-standard.md`).

**001.3 DONE — reference group A, the read side.** 21 pages rewritten in one pass each and landed at
their target paths: `gob world map markers radar player time char study party buff meter kin speed
craft quests wounds fight actionbar act menugrid`. `char.md` split into `char` + `study`; `buffs`,
`meters`, `actions` renamed to `buff`, `meter`, `act` (D-002), every inbound link re-pointed in the
same task across `types ghost hooks ui conventions events` and the three indexes. Drift closed:
**D-2** (`pag:use()` stated as an ungated fact, not a promise), **D-3** (`markers` and `radar` each
gained a `## Write (ungated)` group — the omission the audit called the one with teeth), **D-13** and
**D-4** on `map.md`. One accuracy fix against `src/`: `hafen.speed.set` throws for `n` outside `0..3`
and before the selector exists, where the page implied the server refused it.
Two new decisions, folded back into the style guide so 001.4 does not re-litigate them: **D-006**
granularity is per verb (a table row by default, a `###` call heading when a verb needs prose; the
gating annotation sits on write groups only) and **D-007** a call heading carries its parameters
without `[ ]`, generalising D-004 to *any* slugger-deleted character between two spaces.
**Filed to area `addons`** (`audit.md`, findings section): `pag:use()` sends a real action and is the
one write verb outside the `actions` model.

**What the remaining four execute.** 001.4 group B (the whole anchor-rot surface: `api/ui/**`,
`api/client/**`, `api/font.md`, `api/render/**`, `ghost`, `asset`, `hook` — it carries the largest
share of the re-pointing, and the 15 remaining links out of `docs/` are all on its pages) · 001.5
group C (`conventions` `types` `events` `timer` `store` `json` `http` `log` `slash` `sound`) · 001.6
the learning path (`getting-started.md`, `guides/**`, **plus `runtime.md` and `examples.md`**) ·
001.7 the close (the three indexes, the full sweep, the matrix re-run).

## docs/addons
- **40 pages, 5,986 lines.** Group A is done: 21 reference pages averaging ~67 lines, none over 131.
  Untouched and oversized: `ui.md` 1,171 · `client.md` 688 · `fonts.md` 436 · `render.md` 336.
  Still missing: the site root, the guides tier, `runtime.md`, `examples.md`.
- **Sweep at this boundary: 779 links checked, 0 broken** (checker falsified both ways). 15 links
  leave `docs/`, all on 001.4's pages. Retired names: 0 outside `events fonts render ui`.

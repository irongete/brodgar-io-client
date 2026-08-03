# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `001-docs-overhaul` — audit, restructure and teach: make `docs/addons/` true,
navigable and learnable. **001.1 DONE**; 001.2..001.7 open. No docs page has been edited yet.

**001.1 DONE — the audit, and it is the evidence every later task is checked against.**
`001-docs-overhaul/audit.md` (345 lines) holds the three deliverables. **(a) The coverage matrix**:
36 feature rows + **all 140 tasks** of `addons` 001–036, recovered from each feature's own
`tasks.md` — three different formats across the 36 folders, and deliberately not from `FEATURES.md`,
whose one-line-per-feature prose loses every task that shipped nothing user-facing. Verdicts are
**five** values, not two, and that is the round's decision: `CUT` (11 rows — the API shipped and a
later task deleted it) and `N/A` (8 rows — an internal refactor, a bug fix, a spec-only closure) are
not gaps, and folding them into GAP would have manufactured 19 pieces of work that must not be done
*and* made the feature's own criterion — every row covered at the close — permanently unreachable.
Result: **107 OK · 11 THIN · 3 GAP · 8 N/A · 11 CUT**. **(b) The 39-page inventory** — size, topics
and a keep/rewrite/split/merge verdict each. **(c) 14 drift entries**, every one checked against
`src/`.

**The findings, in the order they matter.** The three GAPs are one shape: **five of the nine shipped
example addons — `bags`, `hogtest`, `netdemo`, `walker`, `optionstest` — are named nowhere under
`docs/`**, `bags` most sharply, since `ui.md` describes the replacement mechanism at length and never
points at the addon that uses it. Structurally, **4 pages hold 45% of all lines** (`ui.md` 1171 +
`client.md` 688 + `fonts.md` 436 + `render.md` 336 = 2,631 of 5,727) while 14 pages are under 40
lines and **nothing sits between 260 and 330** — the bimodal shape of a tree grown task-by-task.
**The stylesheet is documented twice**, `fonts.md` 134–424 and `ui.md` 583–1111, both current and
neither wrong; its sharpest symptom is that `widget:skin{…}` has no section in `ui.md` at all, which
links out to `fonts.md` for it **eight times**. Two drift entries are outright wrong: **D-1**,
`ui.md:541` still calls placing/anchoring by rule *"a later task of the same feature"* when
036.2/036.3 shipped `pos`/`size`/`anchor` and the same page documents them 300 lines earlier; and
**D-12**, `asset.md:51` says *"identical for all three types"* 25 lines below a heading titled *"The
four types"*. Seven more are obituaries the no-history rule deletes; **D-3** is an omission with
teeth — `markers.add` and `radar.setVisible` write and call no `requireActions`, and no page says
so while `actions.md` tells the reader the permission gates the per-subsystem writes.

**What was checked, as counts** (the close re-runs all of them): **695** internal links/anchors under
`docs/` → **0 broken**; **16** further links leave `docs/` for `specs/`+`addons/`, all resolving —
which is why they are drift D-4 and not a checker result, seven of them targeting the
historical-at-write-time `specs/addons/design/*`. **26 of 26** events fired by `src/` are catalogued
and none is invented; **18 of 18** `requireActions` verbs are documented; of **452** Lua names
`src/io/brodgar/addon/*` registers, the only 9 absent from `docs/` are sandbox internals. The link
checker was ad-hoc Python in the scratchpad and is **not committed** (this area ships no tooling) —
and its own lesson is in `learnings/docs-maintenance.md`: its first run reported **208 broken
anchors, every one false**, because an em dash between spaces slugs to a *double* hyphen. A planted
break would not have caught an over-reporting checker; the rule is to falsify in both directions.

**Filed to area `addons`, not fixed here** (this area never edits another's tree): `STATE.md:25`
there lists a `ChatMessage` event that exists nowhere in `src/` — the docs are right and that STATE
is wrong; and `017-gob-oop/tasks.md` still has `017.2` unchecked while `FEATURES.md` reads DONE.

## docs/addons
- 39 pages, 5,727 lines, unchanged by this task — 001.1 edits no docs page by design. One tutorial
  (`getting-started.md`, 255 lines), one reference tree (`api/`, 37 pages), two indexes.
- No guides tier, no `docs/README.md` root, no style guide, no page ceiling. 001.2 writes the
  standard (`design/style-guide.md`, `design/information-architecture.md`) from the audit's evidence;
  001.3..001.6 rewrite the pages one group per task; 001.7 re-runs the matrix and the sweeps.

# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `001-docs-overhaul` — audit, restructure and teach: make `docs/addons/` true,
navigable and learnable. **001.1 and 001.2 DONE**; 001.3..001.7 open. **No docs page is edited
yet, by design**: the closed tasks are the evidence and the standard the other five run against.

**001.1 DONE — the audit** (`001-docs-overhaul/audit.md`, 345 lines), the evidence every later
task is measured against. **(a)** A coverage matrix of 36 feature rows + **all 140 tasks** of
`addons` 001–036, recovered from each feature's own `tasks.md` (three formats), with **five**
verdicts, because `CUT` (11) and `N/A` (8) are not gaps: **107 OK · 11 THIN · 3 GAP · 8 N/A ·
11 CUT**. **(b)** The 39-page inventory — size, topics, verdict. **(c)** 14 drift entries, each
checked against `src/`. **The findings**: five shipped example addons (`bags`, `hogtest`,
`netdemo`, `walker`, `optionstest`) are named **nowhere** under `docs/`; 4 pages hold 45% of all
lines while 14 are under 40 and nothing sits between 260 and 330; the stylesheet is documented
**twice** (`fonts.md` 134–424, `ui.md` 583–1111), whose sharpest symptom is `widget:skin{…}`
having no section in `ui.md` — which links out to `fonts.md` for it **8 times**; the engine/dev
tier (sandbox, `:reload`, the AddOns panel, the watchdog) has no owning page at all. Two drift
entries are outright wrong (D-1 a stale "a later task will…" promise 300 lines under the shipped
feature; D-12 "three types" under a heading reading "The four types"), seven are obituaries the
no-history rule deletes, and D-3 is an omission with teeth: `markers.add` and `radar.setVisible`
write and are **not** gated, and no page says so. **Sweeps**: 695 internal links → **0 broken**;
16 leave `docs/` (D-4); 26/26 events and 18/18 gated verbs documented; of 452 registered Lua
names only 9 (sandbox internals) are undocumented.

**001.2 DONE — the standard**, written from that evidence and edited into no docs page:
`design/style-guide.md` (208 lines) — voice · the three page kinds with a literal reference
template · heading and anchor rules · example rules (runnable, cut down from the shipped example
addon where one exists) · how facts are stated (tables carry facts, **no counts in prose**, every
verb states its gating *including the ungated ones* and its absence case) · the
no-history rule + a 13-name retired grep list · link rules · the ceiling · mechanics · the six
checks every docs task runs and reports. `design/information-architecture.md` (250 lines) — the
**72-page target tree** with a size estimate per page, each hub's reading order, the **migration
map** (section-level for `ui.md`/`client.md`/`fonts.md`/`render.md`/`getting-started.md`,
page-level for the other 34 — all 39 current pages appear in it), which task lands what,
ownership afterwards, and the link discipline. Decisions **D-001..D-005**
(`decisions/docs-standard.md`): the 300-line ceiling with splits by subject and no floor · one
namespace one path, `api/<namespace>.md` spelled as in Lua (so `buffs`→`buff`, `meters`→`meter`,
`actions`→`act`, `hooks`→`hook`, `audio`→`sound`, `char`→`char`+`study`, `console`→`log`+`slash`;
over the ceiling it is a directory with a `README.md` hub) · the stylesheet is owned by
`api/ui/style/` and `api/font.md` keeps typography only · no em dash in a heading · `docs/` never
links `specs/`. The task also **corrected the audit**: `hafen.ui.node(id)` is live
(`UiApi.java:240`) and was dropped from the retired list — 015.1's `CUT` row retired its siblings,
not it.

**What the remaining five execute.** 001.3 group A (the 20 read-side pages) · 001.4 group B (the
whole anchor-rot surface: `api/ui/**`, `api/client/**`, `api/font.md`, `api/render/**`, `ghost`,
`asset`, `hook`) · 001.5 group C (`conventions` `types` `events` `timer` `store` `json` `http`
`log` `slash` `sound`) · 001.6 the learning path (`getting-started.md`, `guides/**`, **plus
`runtime.md` and `examples.md`**, added to the task by 001.2 — they are what close the four THIN
engine/dev rows and the three GAPs) · 001.7 the close (the three indexes, the full sweep, the
matrix re-run). Standing rule during the migration: **the task that moves or retitles a page
re-points every link into it in the same task**, so the tree is link-clean at every boundary.

## docs/addons
- 39 pages, 5,727 lines — **unchanged**: one tutorial (255), one reference tree (`api/`, 37
  pages), two indexes. No guides tier, no root, no runtime or examples page; `ui.md` still 1,171.
</content>

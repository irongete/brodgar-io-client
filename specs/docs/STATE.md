# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `001-docs-overhaul` — make `docs/addons/` true, navigable and learnable.
**001.1..001.6 DONE**; only 001.7 is open (plus 001.4b and the `api/README.md` half of 001.5b). The
evidence and the standard are written, **the whole reference tree is landed at its target paths**, and
**the learning path exists** — tutorial, guides, runtime, examples. Link-clean at every task boundary.

**001.1 DONE — the audit** (`001-docs-overhaul/audit.md`), the evidence every later task is measured
against: 36 feature rows + all 140 tasks of `addons` 001–036 with five verdicts (**107 OK · 11 THIN ·
3 GAP · 8 N/A · 11 CUT**), the 39-page inventory, 14 drift entries each checked against `src/`, and the
sweeps — 695 links **0 broken**, 16 leaving `docs/`, 26/26 events and 18/18 gated verbs documented.

**001.2 DONE — the standard**, written from that evidence: `design/style-guide.md` (voice, the three page
kinds with a literal template, heading/anchor and example rules, how facts are stated, the no-history rule +
a 13-name retired grep list, link rules, the ceiling, the six checks every docs task runs) and
`design/information-architecture.md` (the **72-page target tree**, the reading orders, the migration map for
all 39 pages, which task lands what, and the link discipline).

**001.3 DONE — reference group A, the read side.** 21 pages rewritten in one pass each — `gob world map
markers radar player time char study party buff meter kin speed craft quests wounds fight actionbar act
menugrid`, with `char.md` split into `char` + `study` and `buffs`/`meters`/`actions` renamed (D-002). Drift
**D-2**, **D-3**, **D-13**, **D-4** closed. Decisions **D-006** and **D-007**.

**001.4 DONE — reference group B, the UI stack.** The four oversized pages are gone, split by subject into
**22 new pages** — `api/ui/**` (custom widget selectors items native replace drawing + `style/**`),
`api/client/**` (keybindings + `profiling/**`), `api/render/**`, `api/font.md`, `api/hook.md` — with
`ghost.md` and `asset.md` rewritten in place. The stylesheet has **one owner** (D-003) and `widget:skin{…}`
a section on the page that owns the cascade. Drift **D-1, D-5, D-7, D-8, D-9, D-12, D-13, D-14** closed.
Decisions **D-008** and **D-009**.

**001.5 DONE — reference group C, cross-cutting and infrastructure.** 10 pages: `conventions types events
timer store json http` rewritten in place, `console.md` split into **`log` + `slash`**, `audio.md` renamed
to **`sound`** (D-002), links re-pointed in 24 further pages in the same task. The tree reached **0 em-dash
headings and 0 retired names**; four accuracy fixes against `src/`, three inside examples. Decision
**D-010**: a client-local write group carries `(ungated)`.

**001.6 DONE — the learning path.** 12 pages, 1,336 lines: `getting-started.md` rewritten as a real
tutorial (one path, eight steps, ending in the whole addon as one paste-able listing), the **`guides/`
tier** (`README` + `reading-the-world events-and-timers custom-ui saved-data hotkeys-and-commands
actions-and-permissions theming debugging`), and the two pages the tree never had: **`runtime.md`**, closing
the four THIN engine/dev rows (the manifest field by field, the sandbox, both CPU budgets, the AddOns
panel's vocabulary, the console commands, what `:reload` keeps and drops), and **`examples.md`**, which
describes all ten shipped addons and closes **G-1, G-2, G-3**. Writing `runtime.md` found two shipped
surfaces named nowhere under `docs/`, now documented: the global `ADDON` table, and the manifest's
`dependencies`/`optional_dependencies` as recorded-but-not-enforced, whose engine half is **filed to area
`addons`**. Decision **D-011**: launcher flags stay out of the user-facing tier.

**What 001.7 executes.** The close: the three indexes — `docs/README.md`, `docs/addons/README.md` and
`api/README.md`, the last listing every leaf page so nothing sits over two clicks from the landing page —
the full sweep, and the matrix re-run with every deliberate omission listed and reasoned (D-011's launcher
flags among them). It absorbs **001.4b** and 001.5b's three remaining ` & ` headings on the way.

## docs/addons
- **71 pages, 7,396 lines** — the target tree but for `docs/README.md` and the indexes' regeneration, both
  001.7's. Nothing over the 300-line ceiling (largest 250); the bimodal distribution is gone.
- **Sweep at this boundary: 1,106 links checked, 0 broken** (falsified both ways). **10 leave `docs/`** —
  `examples.md` → `addons/<id>/main.lua`, the one allowed exception. Retired names: 0. Slugger-trap
  headings: 3, all in `api/README.md`, which 001.7 rewrites.

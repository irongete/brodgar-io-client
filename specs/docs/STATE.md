# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `001-docs-overhaul` — audit, restructure and teach: make `docs/addons/` true,
navigable and learnable. **001.1..001.4 DONE**; 001.5..001.7 open. The evidence and the standard are
written, and the reference tree is rewritten and landed at its target paths except group C — link-clean
at every task boundary.

**001.1 DONE — the audit** (`001-docs-overhaul/audit.md`), the evidence every later task is measured
against: 36 feature rows + all 140 tasks of `addons` 001–036 with five verdicts (**107 OK · 11 THIN ·
3 GAP · 8 N/A · 11 CUT**), the 39-page inventory, and 14 drift entries each checked against `src/`. Its
findings: five shipped example addons named nowhere under `docs/`; four pages holding 45% of all lines;
the stylesheet documented twice; no owning page for the engine/dev tier. Sweeps: 695 internal links,
**0 broken**; 16 leaving `docs/`; 26/26 events and 18/18 gated verbs documented.

**001.2 DONE — the standard**, written from that evidence: `design/style-guide.md` (voice, the three page
kinds with a literal reference template, heading and anchor rules, example rules, how facts are stated,
the no-history rule + a 13-name retired grep list, link rules, the ceiling, the six checks every docs task
runs) and `design/information-architecture.md` (the **72-page target tree**, each hub's reading order, the
section-level migration map for all 39 current pages, which task lands what, and the link discipline).

**001.3 DONE — reference group A, the read side.** 21 pages rewritten in one pass each and landed at their
target paths: `gob world map markers radar player time char study party buff meter kin speed craft quests
wounds fight actionbar act menugrid`. `char.md` split into `char` + `study`; `buffs`, `meters`, `actions`
renamed to `buff`, `meter`, `act` (D-002). Drift closed: **D-2**, **D-3** (the omission with teeth: two
ungated *write* groups), **D-13** and **D-4** on `map.md`, plus one accuracy fix (`hafen.speed.set` throws
client-side). Decisions **D-006** (granularity per verb; the gating annotation on write groups only) and
**D-007** (a call heading carries its parameters, without `[ ]`).

**001.4 DONE — reference group B, the UI stack.** The four oversized pages are gone, split by subject into
**22 new pages**: `api/ui/{README custom widget selectors items native replace drawing}`,
`api/ui/style/{README keys surfaces text chrome geometry}`, `api/client/{README keybindings,
profiling/{README counters attribution}}`, `api/render/{README sprites models}`, plus `api/font.md` (was
`fonts.md`) and `api/hook.md` (was `hooks.md`); `ghost.md` and `asset.md` rewritten in place. The
stylesheet now has **one owner** (D-003) and `widget:skin{…}` a section on the page that owns the cascade
— the 034.3 THIN row, and the eight cross-links that were its symptom, are both gone. 012.7's lighting
clause is a paragraph, verified against `Gltf.java` + `MeshSprite.java`. Drift closed: **D-1, D-5, D-7,
D-8, D-9, D-12, D-13, D-14**. One accuracy fix against `src/`: `render.sprite{follow=}`/`object{follow=}`
require a **Gob object** — `RenderApi.java:994` rejects the ids and `"player"`/`"me"` tokens the old
options tables advertised. Two decisions: **D-008** (a measured figure stays out, a documented cap or
budget stays in) and **D-009** (a reference page names the example addon; only `examples.md` links it),
both folded into the style guide.

**What the remaining three execute.** 001.5 group C (`conventions` `types` `events` `timer` `store` `json`
`http` `log` `slash` `sound`) — it also carries the last two ` — ` heading violations and the last retired
name in the tree (`events.md`'s **D-10** obituary) · 001.6 the learning path (`getting-started.md`,
`guides/**`, **plus `runtime.md` and `examples.md`**, which is where the deferred example-addon links land)
· 001.7 the close (the three indexes — including the leaf listing that puts every nested page two clicks
from the landing page — the full sweep, the matrix re-run).

## docs/addons
- **59 pages, 6,203 lines.** Groups A and B are done: nothing is over the 300-line ceiling (largest page
  250), and the bimodal distribution is gone. Untouched: the ten group-C pages, of which `types` (230) and
  `conventions` (200) are the largest. Still missing: the site root, the guides tier, `runtime.md`,
  `examples.md`.
- **Sweep at this boundary: 917 links checked, 0 broken** (checker falsified both ways). **0 links leave
  `docs/`** — the last 15 went with group B. Retired names: 1 hit, `events.md`'s D-10 obituary, 001.5's.

# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** none. `001`..`007` are all DONE; `ROADMAP.md` is empty. The next feature is whatever
area `addons` closes without a docs review, or whatever the maintainer asks for with `/plan docs …`.

## docs/ — the deliverable

**83 pages, 10,457 lines**, average ~126, and **1,548 links, 0 broken**, whole-tree, all re-derived at 007.3
into the IA's §3, which the tree text matches 83/83. **The ceiling has headroom** — the largest are
`types.md`/`event.md` 286, then `world.md` 271 and `ui/widget.md` 257 — and every page is ≤ 2 clicks from
`docs/addons/README.md` (006.3).

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-019**)

**§7 is derived, not remembered (D-015):** `Retired.java` is the engine's own refusal table — **180 `NAMES`
rows + 4 `KEYS`**, counted **by reflection** on the built map, because a regex over the call sites reads 160
and misses two `for` loops that register the world-entity verb families. The guard is one dotted regex,
seven bare section names, `hafen\.act\b`, the event keys and a residue, with the entity half guarded
**backwards**. **D-017**: the tier's adjective is `protected`/`unprotected`. **D-018**: at the top of `api/`
a *directory* means a namespace, so a **type** page splits into a sibling. **§2's word ban is scoped to the
change-note (D-016). §12 is seven checks**, and check 5 now has a third direction. **D-019 closed D-013's
open question**: a refused *grammar* spelling gets no §7 entry — the parser guards it over fenced blocks.
**A grep is falsified by a plant, never trusted for its zero** — `grep -iF` core-dumps on this toolchain,
and an aborted grep reads as a clean sweep (007.2).

## 007 — `049-css-selectors` reviewed, both directions and whole-tree

- **007.1, the census.** `census.md`, 62 rows: **44 OK · 7 WRONG · 2 THIN · 2 GAP · 0 INVENTED · 6 CUT · 1
  N/A**. Eleven corrections on six pages; four clean. The one the standard could never have caught was
  `keys.md` listing the **refused** `[title=…]` as a valid tree key, found by driving the **concretised**
  notation through the real `Selector.parse` (49/49, falsified four ways).
- **007.2, the fifteen `049` never opened.** Derived, not remembered: `grep -rlni selector docs/` reads 25
  minus `049`'s ten. **Twelve were clean.** **Nineteen negatives about this surface exist; seventeen are
  true** — the two that were not said the same thing on both halves of one pair (`style/keys.md` and
  `style/surfaces.md` claimed "no selector ever finds it" of a window's chrome, where the true fact is one
  word narrower: no *role* classifies it). Four more: `items.md`'s `find("window[title=Chest]")` was legal
  but **ambiguous**, now an `appear` subscription; two verb slips; one index row.
- **007.3, the standard and the sweep.** **D-019** settles the refused spellings: none is admitted —
  `inventory[title=` reads 1 by design, `*[title=` reads 0 against a planted `button[title=…]` (an open
  family), only `window[text=` clears D-013 — and the guard is the **parser over fenced blocks**, 56/56,
  falsified six ways. `find`'s contract is stated once on `selectors.md`; `widget.md` kept its two-row
  table and dropped the near-verbatim restatement. §12 whole-tree: 25 retired-name plants caught, 301 colon
  verbs with **7 unregistered all accounted for**, 5-way link falsification, §2's 6 hits read.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one (`LuaEvent.java:712,720`) · a numeric **string** passes `v.isnumber()` in LuaJ, so
`hafen.world():place(p, "0.5")` and `item:drop("2")` are coerced through (`WorldApi.java:453`,
`LuaItem.java:324`) — and `widget:send` does **not** refuse it either · `dependencies`/
`optional_dependencies` are parsed into `Manifest` and never read (`Manifest.java:151,99`) ·
`specs/codebase/addon-engine.md:20,53` still names a `RenderApi` `043` replaced with `VrApi.java`, and
`FlowerMenuApi.java:165` the `hafen.act():flower` 048.7 deleted · `LuaWorldEntity.java:30-33`'s javadoc
calls five retired verbs "the common handle verbs" · `UiApi.java:257`'s comment gives `:text()`'s roster as
"(Label/Button/Window/TextEntry)", missing the `CheckBox` arm `LuaWidget.java:1696` has carried since 040.4.
**Closed since 005** (detail in `005-…/`): `pag:use()`'s gate (048.5) · `ChatMessage` · `017-gob-oop`'s
017.2 · the screen-space overlay **spec table**.

# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** `007-css-selectors-review` — **007.1 and 007.2 are closed**; next is **007.3** (the
refused spellings against D-013, `find`'s contract stated once, the figures and §12 whole-tree).
`001`..`006` are all DONE. ROADMAP empty.

## docs/ — the deliverable

**83 pages, 10,470 lines** and **1546 links, 0 broken**, whole-tree; 007.3 re-derives all four into the IA's
§3 and reconciles the link total (007.1's line-oriented sweep read 1545). **The ceiling has headroom** — the
largest are `types.md`/`event.md` 286 — and every page is ≤ 2 clicks from `docs/addons/README.md` (006.3).

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-018**)

**§7 is derived, not remembered (D-015):** `Retired.java` is the engine's own refusal table, and the guard
is one dotted regex, seven bare section names, `hafen\.act\b`, the event keys and a residue, with the entity
half guarded **backwards**. **D-017**: the tier's adjective is `protected`/`unprotected`. **D-018**: at the
top of `api/` a *directory* means a namespace, so a **type** page splits into a sibling. **§2's word ban is
scoped to the change-note (D-016). §12 is seven checks.** **D-013's own question is open at 007.3**:
`[title=…]` and `window[text=…]` are refusals `selectors.md` must quote, so a bare grep reads non-zero by
design. **A grep is falsified by a plant, never trusted for its zero** — `grep -iF` core-dumps on this
toolchain, and an aborted grep reads as a clean sweep (007.2).

## 007.1 — the census both ways, and the ten pages `049` wrote

`census.md`, 62 rows: **44 OK · 7 WRONG · 2 THIN · 2 GAP · 0 INVENTED · 6 CUT · 1 N/A**. Eleven corrections
on six pages; four clean. The one the standard could never have caught was `keys.md` listing the **refused**
`[title=…]` as a valid tree key, found by driving the **concretised** notation through the real
`Selector.parse` (49/49, falsified four ways). Detail: `007-…/census.md`.

## 007.2 — the fifteen `049` never opened, and the negatives

- The fifteen are **derived**: `grep -rlni selector docs/` reads 25, minus `049`'s ten; `items.md` and
  `runtime.md` were read too, carrying a lookup example without the word. **Twelve were clean.**
- **Nineteen negatives about this surface exist; seventeen are true.** The two that were not said the same
  thing on both halves of one pair — `style/keys.md` and `style/surfaces.md` claimed "no selector ever finds
  it" of a window's chrome, where the true fact is one word narrower (**no *role* classifies it**):
  `Window.chdeco` adds the deco as an ordinary child and `selectors.md` teaches `@DefaultDeco`. The same
  bullet called the close button unclassified — `DefaultDeco.cbtn` is an `IButton`, role `button`.
- Four more: `items.md`'s `find("window[title=Chest]")` was legal but **ambiguous**, now an `appear`
  subscription; `interactive.md` "finds every button" → "matches"; `debugging.md` "enclosing" →
  "captioned"; `api/README.md`'s index row omitted the lookups. All 14 "the first match" hits are the
  **collection** verb, untouched. `>`, `:hover`, `:first-child` and `,` all raise, so `style/README.md`'s
  four-way boundary holds.

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

# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** `007-css-selectors-review` — **007.1 is closed**; next is **007.2** (the fifteen pages
`049` never opened, read whole, and the negatives), then **007.3** (the refused spellings against D-013,
`find`'s contract stated once, the figures and §12 whole-tree). `001`..`006` are all DONE. ROADMAP empty.

## docs/ — the deliverable

**83 pages, 10,466 lines** (re-derived at 007.1: 006.4's 10,344 plus `049`'s ten pages and 007.1's
corrections) and **1545 links, 0 broken**, whole-tree; 007.3 re-derives all four into the IA's §3. **The
ceiling has headroom** — the largest are `types.md`/`event.md` 286, then `world.md`/`ui/widget.md` 271 —
and every page is ≤ 2 clicks from `docs/addons/README.md`, measured as a graph at 006.3.

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-018**)

**§7 is derived, not remembered (D-015):** `Retired.java` is the engine's own refusal table, and the guard
is one dotted regex, seven bare section names, `hafen\.act\b`, the event keys and a residue, with the
entity half guarded **backwards**. **D-017**: the tier's adjective is `protected`/`unprotected`. **D-018**:
at the top of `api/` a *directory* means a namespace, so a **type** page splits into a sibling. **§2's word
ban is scoped to the change-note (D-016). §12 is seven checks**; its first names four falsification
directions plus the over-reporting one. **D-013's own question is open at 007.3**: `[title=…]` and
`window[text=…]` are refusals `selectors.md` must quote, so a bare grep for them reads non-zero by design.

## 007.1 — the census both ways, and the ten pages `049` wrote

- **`census.md`, 62 rows** over `Selector`, the two lookup doors, `LuaSelectorWatch`/`Sheet` and the
  inspector as the pages describe it: **44 OK · 7 WRONG · 2 THIN · 2 GAP · 0 INVENTED · 6 CUT · 1 N/A**.
- **Eleven corrections on six pages.** `keys.md` listed the **refused** `[title=…]` as a valid tree key;
  `references.md` said "a role names a render site" where `window`/`inventory` are roles with no site;
  `widget.md` said only `send` raises on a stale widget while its own next section documents the two
  searches that do; `selectors.md` said every lookup walks the whole tree (the scoped pair walks a
  subtree), made the role look mandatory in a step, called `[res=]` absent from *all* windows, and carried
  three inspector claims copied from `049.4`'s close note (the anchor is the nearest **captioned** window;
  the panel lists the candidates it *builds*; the `^=` form is on the widget's **own** key). Two gaps
  filled: `[text=]`/`:text()` also answer a `CheckBox`, and `[text=]` skips a `Window` even on `*`.
  **Checked and clean, not rewritten:** `ui/replace.md`, `ui/README.md`, `ui/style/README.md`, `theming.md`.
- **Every selector string on the ten pages driven through the real `Selector.parse`** — 49/49 with an
  expected verdict each, 0 mismatches, the harness falsified four ways in one control run. A notation form
  is driven **concretised**, which is the only reason the `keys.md` row was findable at all.
- Links 1545/0, falsified **five** ways (bad path · both anchor kinds · a **broken** wrapped link · a
  **valid** one reading clean); every page under 300, wrap clean; §7's greps 0, a three-name plant caught.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one (`LuaEvent.java:712,720`) · a numeric **string** passes `v.isnumber()` in LuaJ, so
`hafen.world():place(p, "0.5")` and `item:drop("2")` are coerced through (`WorldApi.java:453`,
`LuaItem.java:324`) — and `widget:send` does **not** refuse it either: `LuaMarshal.toJava` switches on
`v.type()`, so `"2"` reaches the wire **as a string** · `dependencies`/`optional_dependencies` are parsed
into `Manifest` and never read (`Manifest.java:151,99`) · `specs/codebase/addon-engine.md:20,53` still
names a `RenderApi` that `043` replaced with `VrApi.java`, and `FlowerMenuApi.java:165` still names the
`hafen.act():flower` 048.7 deleted · `LuaWorldEntity.java:30-33`'s javadoc calls the five verbs it names
"the common handle verbs", all rows in `Retired.java` · **new at 007.1**: `UiApi.java:257`'s comment gives
`:text()`'s roster as "(Label/Button/Window/TextEntry)", missing the `CheckBox` arm `LuaWidget.java:1696`
has carried since 040.4.

**Closed since 005:** `pag:use()`'s gate (048.5) · `ChatMessage`, gone from `src/` and `specs/addons/` ·
`017-gob-oop`'s 017.2 · the screen-space overlay **spec table**, gone since 039.3. Detail: `005-…/`.

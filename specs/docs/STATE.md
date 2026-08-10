# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** [`006-act-dissolved-review`](006-act-dissolved-review/) — area `addons`' `047-flowermenu`
and `048-act-dissolved` (a namespace deleted, ten verbs rehoused, `gated`/`ungated` now
**`protected`/`unprotected`**), plus a tree with no headroom left. `049-css-selectors` is deliberately
**out of it**. **006.1 and 006.2 are DONE**; `006.3` (the ceiling) and `006.4` (the sweep and the close)
are open. `001`..`005` are DONE — `005` closed the seven area-`addons` features that had no docs review.

## docs/ — the deliverable

**80 pages**, ~10,280 lines, re-derived at 006.1 and unmoved by 006.2, whose corrections were all
**line-neutral** bar the actions guide (103 → 108). **Three pages have no headroom** — `ui/widget.md` 300,
`api/conventions.md` 300, `gob.md` 299, the three `048` wrote into and `006.3`'s whole job — then
`types.md` and `event.md` at 286. Links, headings, wrap and the matrices are **005's**, `006.4`'s to redo.

## 006.1 — the census (`006-act-dissolved-review/census.md`)

**20 of 20 surfaces read OK against `src/`**, against the method body and its `LuaError` strings rather than
the feature spec. **Both directions**: the **22** protected verbs (`requireActions`) are all in the guide's
"whole set" table, `hafen.world()` is 12 and `BUS_KEYS` **28**; backward, 170 colon verbs, **0** invented.

## 006.2 — the corrections, and the standard

- **The three WRONG rows are fixed against `src/`**: `types.md:43` names `hafen.player():hand()`
  (`Retired.java:314` throws on the old spelling), `ui/widget.md:42` scopes its no-op claim to
  *client-side* writes (`LuaWidget.java:574`), `gob.md:108` excepts the click (`LuaGob.java:332`).
  **T1 is scoped, not deleted**: the tier is "a verb that **starts** an action", in `conventions.md` **and**
  in the guide's line 9, which held the same claim; the guide names `ev:resend`/`ev:send` as what it admits.
- **The standard reads in the shipped adjective** (**D-017**; D-006/D-010/D-014 left as written, the
  D-015/D-016 precedent): style guide §3, §6, §12 and the IA's §3 tree. §4's examples are live calls
  again; the IA drops `act.md`, carries `flowermenu.md`, and §2's rule illustrates `hafen.flowermenu`.
- **§7 gains its first regex entry**, `hafen\.act\b` — `hafen.act` is a *prefix* of the live
  `hafen.actionbar`, so nothing else is admissible under D-013; it carries the colon spelling too.
- **Counts, re-derived and falsified by a plant**: `Retired.java` expands to **180 `NAMES` rows** (8 whole
  sections · 97 verb keys · 75 entity keys) **+ 4 `KEYS`**, the same expander reproducing D-015's 93/75/4
  on the pre-`048` file. All §7 checks **0**; links **1504 checked, 0 broken** tree-wide.

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-017**)

- **§7 is derived, not remembered (D-015).** `Retired.java` is the engine's own refusal table; the guard is
  one dotted regex, seven bare section names, `hafen\.act\b`, the event keys and a residue, with the entity
  half guarded **backwards**. Re-derived, never copied — and **expanded, never counted**.
- **§2's word ban is scoped to the change-note (D-016)**; the construction reads **six** tree-wide, **0
  real**, four of them substrings — the count is noise, every hit is read. **§12 is seven checks.**

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one — `006.2` **documented** that rather than hiding it, so if the engine gains the gate two
sentences come back out · a numeric **string** passes `v.isnumber()` in LuaJ, so `hafen.world():place(p,
"0.5")` and `item:drop("2")` reach the wire where `widget:send` refuses exactly that (`WorldApi.java:453`,
`LuaItem.java:324`) · `specs/codebase/addon-engine.md` names a `RenderApi` that `043` replaced with
`VrApi.java`, as does `LuaWorldEntity.java:32`'s javadoc, and `FlowerMenuApi.java:165` still names the
`hafen.act():flower` 048.7 deleted · `specs/addons/STATE.md` lists a `ChatMessage` event absent from `src/`,
and `017-gob-oop`'s 017.2 is unchecked while `FEATURES.md` records it done · `dependencies` /
`optional_dependencies` are parsed and never used · a **screen-space** overlay spec ignores
`clickable`/`onClick` and reads `x`/`y` before the anchor overrides them. **Closed by 048.5:** `pag:use()`
is protected. Detail: `005-api-rewrite-review/`.

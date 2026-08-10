# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** [`006-act-dissolved-review`](006-act-dissolved-review/) — area `addons`' `047-flowermenu`
and `048-act-dissolved` (a namespace deleted, ten verbs rehoused, `gated`/`ungated` now
**`protected`/`unprotected`**), plus a tree that had no headroom left. `049-css-selectors` is deliberately
**out of it**. **006.1, 006.2 and 006.3 are DONE**; `006.4` (the tree-wide sweep and the close) is the last
one open. `001`..`005` are DONE — `005` closed the seven area-`addons` features that had no docs review.

## docs/ — the deliverable

**83 pages, 10,344 lines**, re-derived at 006.3. **The ceiling is no longer tight**: the largest pages are
`types.md` and `event.md` at 286, then `world.md` 271 — nothing within 10 lines of 300, where 006.1 found
three at 300/300/299. **Links: 1504 checked at 006.2, 1534 at 006.3, 0 broken both times**, and every page is
≤ 2 clicks from `docs/addons/README.md`, re-measured as a graph. Headings, wrap and the matrices tree-wide
are still **005's**, `006.4`'s to redo.

## 006.1 and 006.2 — the census (`006-act-dissolved-review/census.md`), and the corrections

- **20 of 20 surfaces read OK against `src/`**, against the method body and its `LuaError` strings rather
  than the feature spec. **Both directions**: the **22** protected verbs (`requireActions`) are all in the
  guide's "whole set" table, `hafen.world()` is 12 and `BUS_KEYS` **28**; backward, 170 verbs, **0** invented.
- **The three WRONG rows are fixed against `src/`**: `types.md:43` names `hafen.player():hand()`
  (`Retired.java:314`), `ui/widget.md:42` scopes its no-op claim to *client-side* writes
  (`LuaWidget.java:574`), `gob.md:108` excepts the click (`LuaGob.java:332`). **T1 is scoped, not deleted.**
- **The standard reads in the shipped adjective** (**D-017**). **§7 gained its first regex entry**,
  `hafen\.act\b` — `hafen.act` is a *prefix* of the live `hafen.actionbar`, so nothing else is admissible
  under D-013. `Retired.java` expands to **180 `NAMES` rows + 4 `KEYS`**; all §7 checks **0**.

## 006.3 — the ceiling

Three pages split **by subject**, each seam priced by its **inbound anchors** before the cut:
`conventions.md` 300 → **205** + `references.md` 111 (3 anchors) · `gob.md` 299 → **170** + `overlay.md` 160
(11) · `ui/widget.md` 300 → **242** + `ui/mouse.md` 70 (7). **21 links re-pointed**, and the checker
falsified **four ways** plus the over-reporting direction — the fourth, a link whose text wraps across a
newline, is the one a line-oriented checker skips silently. **D-018**: at the top of `api/` a *directory*
means a namespace, so a **type** page splits into a sibling — `api/gob/` would have named the retired
`hafen.gob` and cost all 66 of `gob.md`'s inbound links instead of 11. Three indexes and the IA's §3 tree were
retrued with it; §9 and §12.1 now carry the pricing rule and the fourth plant.

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-018**)

**§7 is derived, not remembered (D-015):** `Retired.java` is the engine's own refusal table, and the guard is
one dotted regex, seven bare section names, `hafen\.act\b`, the event keys and a residue, with the entity half
guarded **backwards** — re-derived, never copied, and **expanded, never counted**. **§2's word ban is scoped
to the change-note (D-016). §12 is seven checks**, and its first now names four falsification directions.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one — `006.2` **documented** that rather than hiding it · a numeric **string** passes
`v.isnumber()` in LuaJ, so `hafen.world():place(p, "0.5")` and `item:drop("2")` reach the wire where
`widget:send` refuses exactly that (`WorldApi.java:453`, `LuaItem.java:324`) · `specs/codebase/addon-engine.md`
names a `RenderApi` that `043` replaced with `VrApi.java`, as does `LuaWorldEntity.java:32`'s javadoc, and
`FlowerMenuApi.java:165` still names the `hafen.act():flower` 048.7 deleted · `specs/addons/STATE.md` lists a
`ChatMessage` event absent from `src/`, and `017-gob-oop`'s 017.2 is unchecked while `FEATURES.md` records it
done · `dependencies`/`optional_dependencies` are parsed and never used · a **screen-space** overlay spec
ignores `clickable`/`onClick` and reads `x`/`y` before the anchor overrides them. **Closed by 048.5:**
`pag:use()`. Detail: `005-api-rewrite-review/`. **006.4 re-checks this list against `src/`.**

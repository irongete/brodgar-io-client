# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** [`006-act-dissolved-review`](006-act-dissolved-review/) — area `addons`' `047-flowermenu`
and `048-act-dissolved` (a whole namespace deleted, ten verbs rehoused, and `gated`/`ungated` renamed
**`protected`/`unprotected`**), plus the two ROADMAP entries that change makes due and a tree with no
headroom left. `049-css-selectors` is deliberately **out of it**. **006.1 is DONE**; `006.2`..`006.4` are open.
`001`..`005` are DONE — `005-api-rewrite-review` closed the seven area-`addons` features that landed with no
docs review (`039`, `041`..`046`).

## docs/ — the deliverable

- **80 pages, 10,274 lines**, average ~128, re-derived at 006.1. **Three pages have no headroom** —
  `ui/widget.md` 300, `api/conventions.md` 300, `gob.md` 299, the three `048` wrote into and `006.3`'s
  whole job — then `types.md` 286 and `event.md` 286.
- Links, headings, wrap and the coverage matrices are **005's figures and are not restated here**: `006.4`
  re-derives all of them over the whole tree. What `006.1` did measure stands below.

## 006.1 — the census (`006-act-dissolved-review/census.md`)

- **20 of 20 surfaces read OK against `src/`** — `048`'s thirteen rehoused verbs and `047`'s seven, each
  against the method body and its `LuaError` strings rather than the feature spec.
- **Both directions.** All **22** protected verbs (`requireActions`, the whole gate) are in
  `guides/actions-and-permissions.md`'s "whole set" table; `hafen.world()` is 12 verbs, all on its page;
  `BUS_KEYS` is **28**, all on `event.md`. Backward over the eleven pages: 170 colon verbs, **0 real**.
- **The deleted-verb greps read zero and were falsified** on a copy of `docs/`. `hafen.act` is admissible
  **only** as `hafen\.act\b` (plain reads 19, all `hafen.actionbar`) and `gated` only as `\bgated\b`
  (bare reads 2, both inside *propagated*).
- **Three WRONG rows for `006.2`**, all one shape — a page's own universal sentence, true when written and
  falsified by a verb that arrived later: `types.md:43` (the retired `hafen.ui():hand()`),
  `ui/widget.md:42` ("every write is a silent no-op" vs `widget:send`), `gob.md:108` ("like every other
  method here" vs `gob:click`). Plus **T1**, `conventions.md:279` against the ungated `ev:send`/`ev:resend`.

## The standard

`design/style-guide.md`, `design/information-architecture.md`, `decisions/docs-standard.md`
(**D-001..D-016**): what every later docs task — and area `addons` — is checkable against. It is still
written in the **retired** adjective (§3/§6 say `gated`) and §7's list is missing `hafen.act` — both `006.2`.

- **§7 is derived, not remembered (D-015).** `src/io/brodgar/addon/Retired.java` is the engine's own
  refusal table; the guard is one regex for the dotted spellings plus the section names, the event keys and
  a residue, with the entity half guarded **backwards** (every colon verb against the registration set),
  since a `<entity>:<verb>` entry reads zero for the wrong reason. Re-derived, never copied.
- **§2's word ban is scoped to the change-note (D-016)**; the sweep greps the construction, which reads
  three tree-wide and is read rather than counted. **§12 is seven checks.** In the IA, **§3 is the tree as
  it stands and is the authority**; §1, §5, §6 and §8 record `001`'s migration and are not rewritten.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one · a numeric **string** passes `v.isnumber()` in LuaJ, so `hafen.world():place(p, "0.5")`
and `item:drop("2")` are coerced onto the wire where `widget:send` refuses exactly that
(`WorldApi.java:453`, `LuaItem.java:324`) · `specs/codebase/addon-engine.md` names a `RenderApi` that `043`
replaced with `VrApi.java`, as does `LuaWorldEntity.java:32`'s javadoc, and `FlowerMenuApi.java:165` still
names the `hafen.act():flower` that 048.7 deleted · `specs/addons/STATE.md` lists a `ChatMessage` event
absent from `src/`, and `017-gob-oop`'s 017.2 is unchecked while `FEATURES.md` records it done ·
`dependencies`/`optional_dependencies` are parsed and never used · a **screen-space** overlay spec silently
ignores `clickable`/`onClick` and reads `x`/`y` before the anchor overrides them. **Closed by 048.5 and
verified at 006.1:** `pag:use()` is protected (`LuaPagina.java:301`). Detail: `005-api-rewrite-review/`.

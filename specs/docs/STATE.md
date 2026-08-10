# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch: see `AREA.md`; detail per feature: its `NNN-` folder.

**Active feature:** none. `001`..`006` are all DONE — `006-act-dissolved-review` closed at 006.4, covering
area `addons`' `047-flowermenu` and `048-act-dissolved`. The ROADMAP holds one standing entry: review
`049-css-selectors` once `addons` closes it — held out of `006` on purpose, so its selector pages still
describe what shipped **before** it.

## docs/ — the deliverable

**83 pages, 10,344 lines**, re-derived at 006.4. **Links: 1534 checked, 0 broken**, and every page is
≤ 2 clicks from `docs/addons/README.md`, measured as a graph at 006.3. **The ceiling has headroom**: the
largest are `types.md`/`event.md` at 286, then `world.md` 271 — nothing within 10 lines of 300, where
006.1 found three at 300/300/299. Headings, wrap and the symbol sweep are 006.4's, tree-wide.

## The standard — `design/*.md` + `decisions/docs-standard.md` (**D-001..D-018**)

**§7 is derived, not remembered (D-015):** `Retired.java` is the engine's own refusal table, and the guard
is one dotted regex, seven bare section names, `hafen\.act\b`, the event keys and a residue, with the
entity half guarded **backwards**. **D-017**: the tier's adjective is `protected`/`unprotected`, which is
what `048` shipped. **D-018**: at the top of `api/` a *directory* means a namespace, so a **type** page
splits into a sibling — `api/gob/` would have named the retired `hafen.gob` and cost all 66 of `gob.md`'s
inbound links instead of 11. **§2's word ban is scoped to the change-note (D-016). §12 is seven checks**;
its first names four falsification directions plus the over-reporting one.

## 006.4 — the tree-wide sweep, every check re-derived

- **Links** 1534/0, falsified **five** ways — bad path, cross-page anchor, same-page anchor, a **broken**
  wrapped link, then a **valid** wrapped one confirmed clean. 66 links whose text names another page all
  read correct: no move left stale prose behind.
- **Retired names** re-expanded through the generators — `section`×8, `act`×10 + `moved`×1 at **two**
  spellings each, which a `put(`-only expansion misses (158 vs the true **180 `NAMES` + 4 `KEYS`**,
  unchanged since 006.2). Every entry **0**, each falsified by a plant; D-013 holds for `hafen.act`.
- **Symbols** — 301 colon verbs, 294 registered, **7 accounted, 0 invented**. The sweep's output is a list
  to account for, not to act on: it flags an example addon's own handle verbs, Lua stdlib on a string
  literal, and any **computed** registration (`ev:sender()` is `m.set(noun, …)`). §7 now says so.
- **Wording** 0 real change-notes (6 hits, all substring collisions or runtime state) · **headings** clean
  (0 em-dash, 0 `#####`, 0 `--` anchors, one `#` per page) · **wrap** two thresholds, one offender fixed.
- `hafen.act`, `gated`/`ungated`, the field-read grammar for `048`'s moved verbs, `:flower(`/`:enabled(`
  and `raw`'s target vocabulary all **0**. `:menu(` reads 4, all `hafen.ui():menu()` — §7's
  live-on-another-entity case, which no spelling can separate.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one (`LuaEvent.java:712,720`) · a numeric **string** passes `v.isnumber()` in LuaJ, so
`hafen.world():place(p, "0.5")` and `item:drop("2")` are coerced through (`WorldApi.java:453`,
`LuaItem.java:324`) — and `widget:send` does **not** refuse it either: `LuaMarshal.toJava` switches on
`v.type()`, so `"2"` reaches the wire **as a string**. Both doors accept it and disagree about what it
means · `dependencies`/`optional_dependencies` are parsed into `Manifest` and never read
(`Manifest.java:151,99`) · `specs/codebase/addon-engine.md:20,53` still names a `RenderApi` that `043`
replaced with `VrApi.java`, and `FlowerMenuApi.java:165` still names the `hafen.act():flower` 048.7 deleted
· **new at 006.4**: `LuaWorldEntity.java:30-33`'s javadoc calls `:move`/`:pos`/`:show`/`:hide`/`:destroy`
"the common handle verbs" — all five are rows in `Retired.java`.

**Closed since 005:** `pag:use()`'s gate (048.5, `requireActions` at `LuaPagina.java:301`) · `ChatMessage`,
gone from `src/` and `specs/addons/STATE.md` · `017-gob-oop`'s 017.2, now checked · the screen-space
overlay **spec table**, gone since 039.3 (`LuaGobOverlay.java:84`), mooting the `clickable`/`onClick`
finding. Detail: `005-api-rewrite-review/`.

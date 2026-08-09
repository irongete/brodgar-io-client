# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** none. `001`..`005` are DONE — `005-api-rewrite-review` closed the seven area-`addons`
features that landed with no docs review (`039`, `041`..`046`). Every figure below is **re-derived from
the tree**, never carried forward from the previous close.

## docs/ — the deliverable

- **80 pages, 10,059 lines**, average ~126. Nothing over the 300-line ceiling; the largest is exactly 300
  (`api/conventions.md`, zero headroom — on the ROADMAP), then `gob.md` 289, `types.md` 286,
  `ui/widget.md` 285.
- **1,459 internal links + 14 leaving `docs/` = 1,473, exactly the raw `](` count** — reconciling whole
  for the first time, since 005.2's leftover "bracketed text" is a real link (`font.md:43`'s `$font[…]`
  tag). **0 broken**, checker falsified **four** ways (bad path, cross-page anchor, same-page anchor, a
  break inside a link whose text wraps). The 14 outbound are `examples.md`'s, one per addon (D-009).
- **Every page is within two clicks of `docs/addons/README.md`** (40 at one, 38 at two) as a traversal;
  all **66** `api/` pages sit at depth ≤ 1 from `api/README.md`; `docs/README.md` is the root (D-012).
  **573 headings, 0 offenders**; **0 prose lines over 110 columns, 0 fence lines at 100+** — the fence
  bound had never been checked, and six of the ten offenders sat exactly at 100.
- **Coverage both ways against `src/io/brodgar/addon/`**: all **31** mounted sections have an owning page,
  and of **32** `hafen.*` tokens used the only unmounted one is `hafen.music`, the stated absence.
  Backward: **303** colon verbs, **7** unregistered and all accounted for (the `gizmo` library's four,
  two Lua string methods, `:sender`'s computed key).

## The standard

`design/style-guide.md`, `design/information-architecture.md`, `decisions/docs-standard.md`
(**D-001..D-016**): what every later docs task — and area `addons` — is checkable against.

- **§7 is derived, not remembered (D-015).** `src/io/brodgar/addon/Retired.java` is the engine's own
  refusal table: **93 `hafen.*` keys** (86 dotted + 7 whole sections), **75 entity-verb** and **4** event
  keys once `section(…)` and three loops are expanded — the literal `put(` rows read only 69 and 54. The
  guard is one regex for all 86 dotted spellings, the seven section names, the four event keys, a 9-name
  residue for cuts older than the table, and `follow =` / `follow=` / `:follow(`; the entity half is
  guarded **backwards** (every colon verb against the registration set), since a `<entity>:<verb>` entry
  reads zero for the wrong reason — so `:offset(`, `entry:text(` and the retired-here / live-there verbs
  are refused in writing. Falsified every sweep: **five plants, five catches**.
- **§2's word ban is scoped to the change-note (D-016).** `now`/`already`/`still` read **243** hits, none
  of them history; the sweep greps the construction (`used to`, `as it always did`, `before this`, …),
  which reads 3 tree-wide and is read rather than counted. **§12 is seven checks**, gaining the
  re-derivation and the backward verb sweep. In the IA, **§3 is the tree as it stands and is the
  authority**; §1, §5, §6 and §8 record `001`'s migration and are not rewritten as the tree moves on.

What each feature landed is one line each in `FEATURES.md`; `005`'s worklist and its two zero counts are
`005-api-rewrite-review/census.md`.

## Filed to area `addons` and still open

`ev:resend()`/`ev:send(t)` reach the server through `UI.rawWdgmsg` with **no `actions` gate** while their
siblings have one · `specs/codebase/addon-engine.md` names a `RenderApi` that `043` replaced with
`VrApi.java`, as does `LuaWorldEntity.java:32`'s javadoc · `specs/addons/STATE.md` lists a `ChatMessage`
event absent from `src/`, and `017-gob-oop`'s 017.2 is unchecked while `FEATURES.md` records it done ·
`pag:use()` is an ungated write among 18 gated siblings · `dependencies`/`optional_dependencies` are
parsed and never used · a **screen-space** overlay spec silently ignores `clickable`/`onClick` and reads
`x`/`y` before the anchor overrides them. Detail: `005-api-rewrite-review/`.

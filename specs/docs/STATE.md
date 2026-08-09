# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `005-api-rewrite-review` — the seven area-`addons` features that landed with no docs
review (`039`, `041`..`046`). `005.1` (the census) and `005.2` (accuracy for `039`/`041`/`042`) are **DONE**;
`005.3` and `005.4` work from the census's worklist. `001`..`004` are DONE. Every figure below was
**re-derived from the tree**.

## docs/ — the deliverable

- **80 pages, 10,040 lines**, average ~126. Nothing over the 300-line ceiling; the largest is exactly 300
  (`api/conventions.md`), then `gob.md` 289, `types.md` 286, `ui/widget.md` 282, `event.md` 273. **Since
  004.2, under area `addons`' own hand**: `api/vr/**` arrived, `api/ghost.md`, `api/hook.md` and
  `api/render/**` went, **56 other pages changed** — the surface `005` reviews.
- **1,471 internal links, 0 broken**, checker falsified three ways (a bad path, a bad cross-page and a bad
  same-page anchor, one finding each; zero on restore). **14 leave `docs/`** (D-009). **0 retired names**
  over §7's **28** entries. The count is 2 above `005.1`'s because the checker is now whole-file rather
  than line-based: **3 links in the tree have text that wraps across a newline**.
- **Every page is within two clicks of `docs/addons/README.md`** (40 at one, 38 at two), as a traversal;
  all **66** `api/` pages sit at depth ≤ 1 from `api/README.md`, and `docs/README.md` is the root (D-012).
- **Coverage, both directions, against `src/io/brodgar/addon/`**: all **31** mounted sections have an owning
  page; every verb and sub-collection is named in its owner's subtree; **486** registered Lua names, **485**
  named under `docs/` (a sandbox-allowlist stdlib name). Backward: **0** bar the `ghost:move(...)` pair.

## The standard

`design/style-guide.md` (voice, the three page kinds, headings, examples, the no-history rule + the
**28-entry** retired grep list, links, the ceiling, §12's six checks), `design/information-architecture.md`
and `decisions/docs-standard.md` (D-001..D-014) are the contract every later docs task — and area `addons` —
is checkable against. §7's list predates `039`; `005.4` rewrites it as derived from `Retired.java`.

## What the features landed

- **001** the audit, the standard, the three reference groups and the learning path. **002** `api/map.md` →
  six pages. **003** nine corrections, the obituaries → present-tense boundaries (**D-013**). **004** three
  corrections, `controls.md` split, gating in prose (**D-014**).
- **005.1** the census (`005-api-rewrite-review/census.md`) — the counts, traversal and greps above, plus
  the worklist: **2 WRONG** (`api/ui/widget.md:261`, `api/world.md:217` — both call `ghost:move(...)`,
  registered nowhere; the first also holds the pre-045 `snapPlace`), **3 THIN**, **4** second-oracle rows.
- **005.2** five corrections on two pages, each cited to `src/`. `api/event.md`: the closed-set refusal
  points at the catalogue and does **not** list the keys; "never a frame later" contradicted the page's own
  overlay rule and the two next-tick drains; the ungated sentence denied the section reaches the server at
  all, which `ev:resend()`/`ev:send(t)` do; `ev:args()`'s coordinate is keyed. `api/ui/items.md` (**T2**):
  the poll is gone — a watch list at the seams, items seeded **inside** `:on`, plus the dead-widget case.
  **C1** (arity rows) and **C2** (the event door, 26 + 4 + 3 keys) checked, correct, no edit.

## Filed to area `addons` and still open

- `ev:resend()`/`ev:send(t)` reach the server via `UI.rawWdgmsg` with **no `actions` gate**, while
  `slot:use`, `craft:make` and the kin writes have one — and `041/spec.md:214` justifies the ungated tier
  with "cancelling … rather than sending anything", which `ev:send(t)` is not.
- `specs/codebase/addon-engine.md` is **stale**: its file table gives `RenderApi` as the owner of
  `hafen.ghost` + `hafen.render`, and there is no `RenderApi.java` — `043` replaced it with `VrApi.java`.
- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s 017.2
  is unchecked while `FEATURES.md` records it done. `pag:use()` is an ungated write while the 18 other
  server-reaching verbs need `actions`; `dependencies` / `optional_dependencies` are parsed and never used.
- `clickable`/`onClick` on a **screen-space** overlay spec are silently ignored; `x`/`y` in any overlay spec
  are read and then overridden by the anchor. Both are D-072's case.

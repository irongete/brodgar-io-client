# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `005-api-rewrite-review` — the seven area-`addons` features that landed with no docs
review (`039`, `041`..`046`). `005.1` (census), `005.2` (`039`/`041`/`042`) and `005.3` (`043`..`046`) are
**DONE**; only `005.4` is left. `001`..`004` are DONE, and every figure below is **re-derived from the tree**.

## docs/ — the deliverable

- **80 pages, 10,055 lines**, average ~126. Nothing over the 300-line ceiling; the largest is exactly 300
  (`api/conventions.md`), then `gob.md` 289, `types.md` 286, `ui/widget.md` 284. **Since 004.2 area `addons`
  wrote it alone**: `api/vr/**` arrived, `api/ghost.md`/`api/hook.md`/`api/render/**` went, **56** more
  pages changed.
- **1,472 internal links, 0 broken**, checker falsified **four** ways (bad path, bad cross-page anchor, bad
  same-page anchor, and a break planted inside a link whose text wraps — the fourth is what proves the
  whole-file scan sees the tree's **3** wrapped links). **14 leave `docs/`** (D-009). **0 retired names**
  over §7's **28**; raw `](` reads 1473, the difference being `font.md:43`'s bracketed text.
- **Every page is within two clicks of `docs/addons/README.md`** (40 at one, 38 at two), as a traversal;
  all **66** `api/` pages sit at depth ≤ 1 from `api/README.md`, and `docs/README.md` is the root (D-012).
- **Coverage, both directions, against `src/io/brodgar/addon/`**: all **31** mounted sections have an owning
  page, every verb and sub-collection is named in its owner's subtree, and **485** of **486** registered Lua
  names appear under `docs/` (the one is a stdlib name on the sandbox allowlist). Backward: the tier's colon
  verbs are **303** distinct and **0** invented — the `ghost:move(...)` pair is gone.

## The standard

`design/style-guide.md` (voice, page kinds, headings, examples, the no-history rule + the **28-entry**
retired grep list, links, the ceiling, §12's six checks), `design/information-architecture.md` and
`decisions/docs-standard.md` (D-001..D-014) are the contract every later docs task — and area `addons` —
is checkable against. §7's list predates `039`; `005.4` derives it from `Retired.java`.

## What the features landed

- **001**..**004**: the audit, the standard, the reference groups and the learning path; `api/map.md` → six
  pages; twelve corrections; the `controls.md` split (**D-013**, **D-014**). **005.1**: the census.
- **005.2** five corrections, both pages cited to `src/`. `api/event.md`: a closed-set refusal that pointed
  at the catalogue instead of listing keys, a "never a frame later" its own overlay rule contradicted, an
  ungated sentence denying the section reaches the server at all (`ev:resend()`/`ev:send(t)` do), and
  `ev:args()`'s keyed coordinate. `api/ui/items.md`: the poll is gone — a watch list at the seams.
- **005.3** four wrong examples on two pages, two THIN rows filled. `ghost:move(...)` was retired at `039`
  (→ `:position(p [, a])`), and both live calls also mis-typed what `screenToWorld` hands back and what
  `snapPlace` takes — a **Position**, not numbers; two more are field reads the census's verb sweep cannot
  see (`m.x`/`m.y`, `c.x`/`c.y` — `learnings/`). Filled: `hafen.vr():pointer` on the hub, 044's transparency
  rule, `046`'s refusal against the `vr` clamp. `043`'s `:facing` and 045's cut checked clean.

## Filed to area `addons` and still open

- `ev:resend()`/`ev:send(t)` reach the server via `UI.rawWdgmsg` with **no `actions` gate**, while
  `slot:use`, `craft:make` and the kin writes have one — and `041/spec.md:214` justifies the ungated tier
  with "cancelling … rather than sending anything", which `ev:send(t)` is not.
- `specs/codebase/addon-engine.md` is **stale**: it gives `RenderApi` as the owner of `hafen.ghost` +
  `hafen.render`, and there is no `RenderApi.java` — `043` replaced it with `VrApi.java`. So is
  `LuaWorldEntity.java:32`'s javadoc (a gone `AddonManager.addEntityHandle`).
- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s 017.2
  is unchecked while `FEATURES.md` records it done. `pag:use()` is an ungated write while the 18 other
  server-reaching verbs need `actions`; `dependencies`/`optional_dependencies` are parsed and never used.
  And on a **screen-space** overlay spec `clickable`/`onClick` are silently ignored while `x`/`y` are read
  then overridden by the anchor — both D-072's case.

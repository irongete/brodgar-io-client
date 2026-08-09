# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch: see `AREA.md`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `005-api-rewrite-review` — the seven area-`addons` features that landed with no docs
review (`039`, `041`..`046`). `005.1` (the census) is **DONE**; `005.2`, `005.3`, `005.4` work from its
worklist. `001`..`004` are all DONE. Every figure below was **re-derived from the tree by `005.1`**.

## docs/ — the deliverable

- **80 pages, 10,037 lines**, average ~125. Nothing over the 300-line ceiling; the largest is exactly 300
  (`api/conventions.md`), then `gob.md` 289, `types.md` 286, `ui/widget.md` 282. Three tiers, and a page
  belongs to exactly one: the tutorial, `guides/`, and `api/` — one namespace per page, a directory where it
  is large (`map/`, `style/`, `client/profiling/`, `ui/controls/` and, since `043`, `vr/`).
  **Since 004.2, under area `addons`' own hand**: `api/vr/**` arrived, `api/ghost.md`, `api/hook.md` and
  `api/render/**` went, **56 other pages changed** — the surface `005` reviews.
- **1,469 internal links, 0 broken**, checker falsified both ways (a bad path, a bad cross-page anchor and a
  bad same-page anchor each caught, exactly one finding each; zero on restore). **14 leave `docs/`** —
  `examples.md` → `addons/<id>/main.lua` (D-009; up from 13 as `cupboard` shipped). **0 retired names** over
  §7's **28** entries.
- **Every page is within two clicks of `docs/addons/README.md`** (40 at one, 38 at two), measured as a
  traversal; all **66** `api/` pages sit at depth ≤ 1 from `api/README.md`. `docs/README.md` is the site root
  above it, and nothing links down to it (D-012).
- **Coverage, both directions, against `src/io/brodgar/addon/`**: all **31** mounted sections have an owning
  page; every verb of every plain section, and every sub-collection, is named in its owner's subtree; **486**
  registered Lua names, **485** named under `docs/` (the exception is a Lua stdlib name on the sandbox
  allowlist). Backward: **0** offenders bar the two `ghost:move(...)` uses below.

## The standard

`design/style-guide.md` (voice, the three page kinds and their template, heading/anchor and example rules,
how facts are stated, the no-history rule + the **28-entry** retired grep list, links, the ceiling, the six
checks every docs task runs), `design/information-architecture.md` (the target tree, the migration map, the
link discipline) and `decisions/docs-standard.md` (D-001..D-014) are the contract every later docs task — and
area `addons` — is checkable against. **Both `AREA.md` files say `docs/addons/**`.** §7's list predates
`039`; `005.4` rewrites it as derived from `Retired.java`.

## What the features landed

- **001** the audit (140 task rows, 36 feature rows, 14 drift entries), the standard from it, the three
  reference groups and the learning path. **002** `api/map.md` → six pages. **003** nine corrections from the
  second oracle, the four obituaries → present-tense boundaries, §7 extended under **D-013**. **004** three
  more corrections, `controls.md` (300/300) split into `controls/`, gating settled in prose (**D-014**).
- **005.1** the census (`005-api-rewrite-review/census.md`, 152 lines) — the counts, traversal and greps
  above, plus the worklist: **2 WRONG** (`api/ui/widget.md:261` and `api/world.md:217` both call
  `ghost:move(...)`, registered nowhere; the first also reads `.x`/`.y` off a Position and gives `snapPlace`
  the pre-045 three-argument form — the **fourth** page holding that model, on no feature's file list),
  **3 THIN**, **4** rows needing the second oracle, three no-history candidates filed to `005.4`.

## Filed to area `addons` and still open

- `specs/codebase/addon-engine.md` is **stale** on the surface `005` reviews: its file table still gives
  `RenderApi` as the owner of `hafen.ghost` + `hafen.render`, and there is no `RenderApi.java` — `043`
  replaced it with `VrApi.java` owning `hafen.vr()`.
- `specs/addons/STATE.md` lists a `ChatMessage` event that exists nowhere in `src/`; `017-gob-oop`'s 017.2
  is unchecked while `FEATURES.md` records it done. `pag:use()` is an ungated write while the 18 other
  server-reaching verbs need `actions`; `dependencies` / `optional_dependencies` are parsed and never used.
- `clickable`/`onClick` on a **screen-space** overlay spec are silently ignored; `x`/`y` in any overlay spec
  are read and then overridden by the anchor. Both are D-072's case.

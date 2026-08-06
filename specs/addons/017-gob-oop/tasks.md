# 017-gob-oop — Tasks

> Two tasks. **017.1 is atomic and cannot be split**: the flat table dies and every consumer moves
> in the same session, or the client is unusable in between. 017.2 is spec-side text only (no code,
> nothing to verify in-game) and is the only part safe to defer.

- [x] **017.1 — The hard cut: `LuaGob` + every consumer + the shipped docs**
  - `LuaGob.java`: id holder, per-addon intern cache (weak values + **`ReferenceQueue` drain**),
    per-addon metatable (`__index` methods, `__tostring`, `__name`), `resolve(LuaValue)`.
  - Methods: `:id() :exists() :info() :pos() :facing() :name() :health() :moving() :speed()
    :speech() :icon() :overlays() :isplayer() :distance([other])` (`other` defaults to the player).
  - `WorldApi.installGob` → `hafen.gob(id)` factory (callable table, `__call`); `installWorld`:
    `gobs/nearest/within` return Gobs, `count` unchanged; `matches`/`distTo` retargeted to `Gob`,
    filters still evaluated **outside** the OCache lock.
  - Delete `AddonManager.resolve(LuaValue)` + `pos(LuaValue)` and the flat `hafen.gob` table
    (`WorldApi.java:154`). Keep `gobSnapshot` — it now backs only `gob:info()`.
  - Ripples: `CharApi.installPlayer` → callable Player (`:gob()/:name()/:vitals()/:worldToScreen()`,
    drop `exists`/`id`); `ActApi.actClickGob`; `RenderApi.followTargetId`; `UiApi.gobOverlay` filter;
    `GobAdded`/`GobRemoved` payload → a Gob.
  - Addons: `hello` (11 sites + event/overlay handlers, and NEW harness checks — identity
    `hafen.gob(id) == hafen.gob(id)`, `seen[gob]` de-dup across two sweeps, a stashed Gob tracking
    you as you walk, and `hafen.gob.health == nil` proving the cut), `hello/manifest.json`,
    `planner` (3 sites), `walker` (2 sites, `o.isplayer` → `g:isplayer()`).
  - Docs: rewrite `docs/addons/api/gob.md`; cross-reference edits in `world.md`, `player.md`,
    `conventions.md` (drop the token table), `types.md`, `party.md` (drop the token mention),
    `actions.md`, `ghost.md`, `map.md`, `render.md`, `README.md`, `getting-started.md`.
  - Verify: `rm -rf build/classes` → `ant hafen-client` (incremental builds false-green on a symbol
    this widely moved), then **`grep -rn 'hafen\.gob\b'` must be zero** across
    `src/io/brodgar/addon/`, `docs/addons/`, `addons/`. Then a cold restart + one login.
  - *Extra context:* none beyond spec.md's list — this task is the whole feature.

- [x] **017.2 — Spec-side closure (no code, not in-game verifiable)** — closed late (2026-08-06):
      `decisions/architecture-api.md` never got D-044/045/046 appended at the time, despite
      `FEATURES.md` and later decisions (D-056, D-059, D-060, D-064, D-065) already citing them by
      number — found while resolving an unrelated D-144 collision. `design/06-lua-api.md`,
      `API-REFERENCE.md`, `STATE.md` and `docs/addons/api/gob.md` were already correct (rewritten
      here or superseded in full by 039-uniform-api); only the decision entries were missing.
  - `decisions/architecture-api.md`: append **D-044** (Gob is OOP + hard cut; supersedes D-012/D-013
    for gobs), **D-045** (identity by per-addon weak interning), **D-046** (Player by composition,
    no forwarded methods). Mark `(SUPERSEDED by D-044)` on the D-012 / D-013 / D-022 headers —
    headers only, entries verbatim.
  - `design/06-lua-api.md`: one-line banner at the top — the `hafen.gob.*(ref)` material is obsolete,
    the rest of the doc stands.
  - `specs/addons/README.md`: closed `NNN-` folders are history (they describe their date); on
    conflict `STATE.md` and `API-REFERENCE.md` win.
  - `API-REFERENCE.md`: "Calling style" → *Gob is OOP, everything else is still flat* (transitional
    text, deleted by the final demolition `/plan`); rewrite the GobRef, `hafen.gob`, `hafen.world`
    and `hafen.player` sections; drop the token table.
  - `STATE.md`: the Read-API line. `codebase-map.md` if an anchor moved.
  - Verify: `grep -rn 'hafen\.gob\b'` zero in `API-REFERENCE.md` and `STATE.md`.

**Untouched, deliberately:** closed `NNN-` folders, `learnings/`, git history.

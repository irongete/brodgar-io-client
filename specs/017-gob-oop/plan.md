# 017-gob-oop — Plan

## Approach

**One new class, one deleted table.** `LuaGob` (new, `io.brodgar.addon`) is a bare id holder —
`long id` and nothing else. It crosses into Lua as `LuaValue.userdataOf(luaGob, mt)` where `mt` is a
**per-addon** metatable whose `__index` is the shared methods table (plus `__tostring` → `Gob(<id>)`
and `__name`). Userdata, not a table, on purpose: the interned object is shared across the addon's
own code, so it must be **immutable from Lua** — no `gob.foo = 1` polluting it — and the userdata
stays unforgeable (the R1 handle pattern, `luaj-bridge.md`). Field access is methods-only: `gob.id`
yields the function, `gob:id()` the number.

**Interning.** Per-addon `Map<Long, WeakReference<LuaValue>> gobs` + a `ReferenceQueue`, drained on
every access (amortised, no sweep timer). Weak **values**, so an entry dies when the addon drops its
last reference — `WeakHashMap` is the wrong tool (weak keys). Living in the addon's env, it dies
whole on `:reload`/disable; **nothing static in `AddonManager`** (a static cache would survive
reloads — the C1 console-command trap again). `hafen.gob(id)` = drain → hit → or mint+insert.

**The cut.** `AddonManager.resolve(LuaValue)` — the central GobRef resolver, with its `"player"`/
`"me"`/`"partyN"` token branches — is **deleted**, along with `pos(LuaValue)` and the whole
`hafen.gob` flat table. What replaces it is `getgob(long)`, which every method already funnels
through. The per-attribute readers (`gobName`/`gobSpeech`/`gobIcon`/`overlayNames`) and
`gobSnapshot(Gob)` **stay as-is** — `gobSnapshot` now backs only `gob:info()`.

**Ripples (all in this task).** `hafen.world.gobs/nearest/within` return interned Gobs;
`matches(filter, …)` takes the `Gob` (string filter → `gobName(g)` directly, no snapshot built;
function filter → called with the Gob **outside the OCache lock**, as today) and `distTo` takes a
`Gob`. `installPlayer` becomes a callable returning an interned Player userdata
(`:gob()/:name()/:vitals()/:worldToScreen()`; `exists`/`id` dropped). `ActApi.actClickGob`,
`RenderApi.followTargetId` and the `GobAdded`/`GobRemoved` fire site take/emit a `LuaGob`.
`UiApi.gobOverlay`'s filter switches from snapshot to Gob (its match cache keys by gob id already).

## Files

**New** — `src/io/brodgar/addon/LuaGob.java` (the id holder, `resolve(LuaValue)→LuaGob`, the intern
cache + methods-table builder; mirrors `LuaWidgetNode`'s shape).

**Modified (Java)** — `WorldApi.java` (`installGob` rewritten to the factory + methods, `installWorld`
returns Gobs) · `AddonManager.java` (delete `resolve`/`pos`, retarget `matches`/`distTo`, the
`GobAdded`/`GobRemoved` payload, `installHafen` wiring, the doc-comment block ~:639) ·
`CharApi.java` (`installPlayer` → callable) · `ActApi.java` (`actClickGob`) · `RenderApi.java`
(`followTargetId`) · `UiApi.java` (`gobOverlay` filter).

**Modified (addons)** — `hello/main.lua` (11 sites + the `GobAdded`/`GobRemoved` and gob-overlay
handlers; extend the harness to prove identity, freshness and the hard cut) · `hello/manifest.json`
(the description's read-API sentence) · `planner/main.lua` (3 sites) · `walker/main.lua`
(2 sites — note `o.isplayer` → `g:isplayer()`).

**Modified (docs)** — `docs/addons/api/gob.md` is the real rewrite; `world.md`, `player.md`,
`conventions.md` (drop the token table), `types.md`, `party.md` (drop the token mention),
`actions.md`, `ghost.md`, `map.md`, `render.md`, `README.md`, `docs/addons/getting-started.md` —
1–5 line cross-reference edits each.

**Modified (specs, task 017.2)** — `decisions/architecture-api.md` (D-044/045/046 appended;
`(SUPERSEDED by D-044)` on the D-012/D-013/D-022 headers, entries untouched) · `design/06-lua-api.md`
(one-line banner) · `README.md` (closed `NNN-` folders are history; STATE + API-REFERENCE win) ·
`API-REFERENCE.md` (Calling style / GobRef / `hafen.gob` / `hafen.world` / `hafen.player`) ·
`STATE.md` · `codebase-map.md` if a line moves.

## Risks & gotchas

- **The intern cache is the one real leak vector.** `Map<Long, WeakReference<V>>` keeps the key +
  the dead `WeakReference` forever unless drained — a per-tick world sweep sees tens of thousands of
  ids over a session. `ReferenceQueue` drain is a **requirement of 017.1**, not a follow-up.
- **No pinning by design.** `LuaGob` holds no `haven.Gob` ref, so a stashed handle never keeps a
  despawned gob (or its `.res`/overlays) alive — strictly better than `LuaWidgetNode`, which has to
  null its `Widget` by hand.
- **Filters must stay outside the OCache lock** (`world-reads.md`): they call back into Lua. Copy
  the gob list under `synchronized(oc)`, then filter. Unchanged discipline, easy to lose in a rewrite.
- **`Loading` is a control-flow exception**: every attribute reader stays try/catch → nil. Reused
  verbatim, but the new method bodies must not drop the guard.
- **`gob:health()` on the player is nil by design** (no `GobHealth` on the player body,
  `world-reads.md`) — not a regression when the harness prints it.
- **`ant hafen-client` is incremental and will false-green** on a symbol as widely moved as
  `resolve` → `rm -rf build/classes` before believing the compile.
- **Java 1.8**: no `var`, no `Map.of`; `ReferenceQueue`/`WeakReference` are fine.
- **The engine change needs a full client restart** — only the Lua files reload live, so the
  in-game verification is one cold login.

## Discarded alternatives

- **Keep a compat shim / deprecation period** — rejected by the brief; a shim is exactly the dual
  style D-013 forbids, and the only consumers are three in-repo addons.
- **Migrate Java first, docs and addons after** — rejected: the hard cut breaks every addon the
  moment the flat table dies, so a split leaves the client unusable between tasks.
- **`hafen.player` stays a flat table with a new `hafen.player.gob()`** — strictly less churn and
  more faithful to "only Gob is OOP", but the brief is explicit about `hafen.player():gob()`;
  recorded here as the road not taken.
- **A LuaTable handle instead of userdata** — cheaper, but an interned table is writable from Lua,
  so one addon function could scribble on the object every other one shares.
- **A global (cross-addon) intern cache** — one object per gob for the whole engine, but Lua values
  would cross sandbox boundaries (D-017) and the cache would outlive `:reload`.
- **Re-adding the tokens as `hafen.gob("player")`** — that is the flat API wearing a new hat; the
  successors are `hafen.target():gob()` / `hafen.party()[1]:gob()`, deliberately deferred.

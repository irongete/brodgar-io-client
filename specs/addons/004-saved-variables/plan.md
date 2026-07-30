# 004-saved-variables — Plan

> History: this work appears in git history and `learnings/` tagged **1e** (Phase 1e).

## Approach
- **Reuse the existing JSON facilities** — the REPL's compact `json()` writer + the manifest
  `Json` reader (`fillTable`: object → string-keyed table, array → 1-based) — no new formats.
  Integers stay clean; >2^53 loses precision (same reason grid ids are strings); an empty table
  serializes `[]` but round-trips fine.
- **`savedata/` = sibling of the resolved addon dir** (follows the dev/release rule;
  `-Dhaven.savedatadir` override); one `<addon>.json` per folder mapping each declared name to
  its table. Gitignored.
- **Split lifecycle by scope** — the key insight: the per-char folder is `<genus>_<char>` and
  the character is only known once the HUD is up, but addons load at `RemoteUI.init` (pre-HUD).
  So account vars load at addon-load (before `OnLoad`); per-char vars load at `OnEnterWorld`.
- **Flush uses a scope CAPTURED at `OnEnterWorld`** (`charScope`), not a live `gui()` lookup —
  on a relog the old addons are torn down before the new `GameUI` exists, and the old
  character's data must land in the old character's folder.
- **Cheap + crash-safe**: per-scope write-skip cache (unchanged scope → no disk I/O), 30 s
  throttled auto-save in `tick` (UI thread), atomic tmp+move writes, failures logged never
  thrown.

## Files created / modified
- `src/io/brodgar/addon/Manifest.java` — `saved_variables` → `List<SavedVar>` (name + account flag)
- `src/io/brodgar/addon/Addon.java` — `store` proxy ref + per-scope write-skip caches
- `src/io/brodgar/addon/AddonManager.java` — facade + lifecycle wiring + store helpers
  (`saveDir`/`scopeKey`/`loadScope`/`writeScope`/`fillTable`…)
- `addons/hello/` — v0.10.0 (per-char + account login counters + bounded `recent` array)
- `.gitignore` — `/savedata`
- No `haven` edits (`GameUI.genus`/`chrid` already public).

## Risks & gotchas hit (detail: learnings/engine-lifecycle.md, testing-tooling.md)
- The relog teardown order is what forces the captured `charScope` (live lookup finds nothing).
- Headless end-to-end persistence testing (19 checks: write→relog→read→increment→relog,
  array round-trip, write-skip, `scopeKey` sanitize) — the throwaway-in-scratchpad pattern.

## Discarded alternatives
- `%APPDATA%`/registry storage — client-local `savedata/` tree (D-001/D-002).
- A get/set persistence API — the proxy table is the one way (D-013).

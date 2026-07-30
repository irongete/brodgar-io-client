# 004-saved-variables — Spec

## What & why
The first **write-to-disk** surface: WoW-`SavedVariables`-style persistence. An addon declares
variable names in its manifest; the engine restores them on load and flushes them as JSON under
`savedata/` — per-character by default, account-wide on request (D-002/D-023). One canonical way
to persist: the `hafen.store.<name>` proxy table (no parallel get/set API, D-013).
Design: [design/02-filesystem-and-build.md](../design/02-filesystem-and-build.md) (layout),
[design/05-lifecycle-and-reload.md](../design/05-lifecycle-and-reload.md) (lifecycle),
[design/03-addon-format.md](../design/03-addon-format.md) (manifest field).

## Acceptance criteria (verified in-game)
- [x] `hafen.store.<name>` is a plain Lua table per manifest `saved_variables` entry — mutate in
      place or replace wholesale, both persist; always a table (never nil-check).
- [x] Relog DoD: `hello`'s per-char login counter increments across a relog and its file appears
      at `savedata/<genus>_<char>/hello.json`; the account counter
      (`{"name":…,"scope":"account"}` declaration → `savedata/account/`) is shared across
      characters; a bounded `recent` array round-trips as a JSON array.
- [x] Lifecycle: account vars ready at `OnLoad`; per-char vars restored just before
      `OnEnterWorld` (no streaming delay); flush on `OnDisable`/teardown + a 30 s throttled
      auto-save; `hafen.store.flush()` forces a write.
- [x] Writes are atomic (tmp + move) and non-fatal (logged, never thrown into Lua).

## Out of scope
- Size caps / key whitelists (sandbox quotas) and a dedicated shutdown hook (30 s auto-save
  covers unclean exits).
- Storing functions/userdata (quietly stringified — don't).

## Context files
- `design/02-filesystem-and-build.md`, `design/05-lifecycle-and-reload.md`,
  `design/03-addon-format.md` — layout, lifecycle, manifest
- `src/io/brodgar/addon/Manifest.java` (`SavedVar`), `Addon.java` (store ref + write-skip
  caches), `AddonManager.java` (facade + lifecycle + store helpers)
- `docs/addons/api/store.md` — shipped surface
- `../001-bootstrap-engine/` — the JSON reader/writer this reuses; `../005-sandbox-reload-panel/`
  — the reload path that exercises the flush

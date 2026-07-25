# Phase 1e — Saved variables (`hafen.store`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), headless end-to-end
> persistence test (19/19: write → relog → read → increment → relog, array round-trip, write-skip,
> `scopeKey`) + LuaJ parse of the harness. **In-game verification pending.**
> **Design:** [specs/addons/05-lifecycle-and-reload.md](../../specs/addons/05-lifecycle-and-reload.md)
> (saved-variables lifecycle), [specs/addons/02-filesystem-and-build.md](../../specs/addons/02-filesystem-and-build.md)
> (`savedata/` layout), [specs/addons/03-addon-format.md](../../specs/addons/03-addon-format.md)
> (`saved_variables` manifest field), [specs/addons/api-reference.md](../../specs/addons/api-reference.md)
> (`hafen.store`), decisions **D-002** (separate `savedata/` JSON tree) + **D-023** (per-char + account scope).

The first **write-to-disk** surface: addons persist Lua tables across sessions, WoW-`SavedVariables`-style.
An addon declares saved-variable names in its manifest; the engine restores them on load and flushes them
to JSON under `savedata/`. Everything is **local** to the client folder ([D-001](../../specs/addons/decisions.md)) —
no `%APPDATA%`, no network.

## The API — `hafen.store`

```lua
-- manifest.json:  "saved_variables": ["settings", { "name": "prefs", "scope": "account" }]

hafen.store.settings              -- a persisted Lua table (per-character)
hafen.store.settings.volume = 3   -- read/write like any table; the engine serializes it
hafen.store.flush()               -- force a write to disk right now
```

- **`hafen.store.<name>`** is a plain Lua table, one per name declared in the manifest's
  `saved_variables`. Mutate it in place (`store.settings.x = 1`) or replace it wholesale
  (`store.settings = {...}`) — both persist. It is **always a table** (empty if never saved / no file
  yet), so you never need to nil-check before indexing.
- **`hafen.store.flush()`** forces an immediate write. You rarely need it: the engine also **auto-saves
  on a throttle** and **flushes on `OnDisable`** (which includes a relog). The `hello` demo calls it to
  make the write deterministic for verification.

There is exactly **one** way to persist (the proxy table) — no parallel get/set API
([D-013](../../specs/addons/decisions.md), one canonical way).

## Manifest declaration + scope (D-023)

`saved_variables` is an array whose entries are **either** a bare name (per-character) **or** an object
`{ "name": ..., "scope": "account" }`:

```json
"saved_variables": ["persist", { "name": "acct", "scope": "account" }]
```

| Scope | Declared as | Stored at | Shared? |
|---|---|---|---|
| **per-character** (default) | `"persist"` | `savedata/<genus>_<char>/<addon>.json` | one per character |
| **account** | `{ "name": "acct", "scope": "account" }` | `savedata/account/<addon>.json` | across all your characters |

Any `scope` other than `"account"` (including absent) is per-character. A folder holds one `.json` file
per addon; the file is a JSON object mapping each declared name to its table:

```json
{ "persist": { "logins": 7, "recent": ["login #6", "login #7"] } }
```

## On-disk layout

```
<client>/                       (bin/ in a release; ${basedir} under the dev override)
  addons/                       ← addon code (read)
  savedata/                     ← addon data (written by the engine)
    account/
      hello.json                ← account-scope vars, all characters
    w16_Irongete W16.1/         ← one folder per <genus>_<char>
      hello.json                ← per-character vars
```

`savedata/` resolves as the **sibling of the addon dir** (so it follows the same dev/release rule as
`addons/`: `bin/savedata` in a release, `${basedir}/savedata` under the dev override).
`-Dhaven.savedatadir=<path>` overrides it. `savedata/` is **gitignored** (`/savedata`, plus `bin/` already).

## When it loads and saves (the lifecycle)

The tricky part: the per-character folder is `<genus>_<char>`, and **the character is only known once the
HUD (`GameUI`) is up** — but addons load earlier (at `RemoteUI.init`, before world entry). So the two
scopes restore at different times:

```
addon load ─▶ install hafen.store (empty tables) ─▶ load ACCOUNT vars ─▶ run files ─▶ OnLoad
   … enter world (GameUI up) …
capture <genus>_<char> ─▶ load PER-CHAR vars ─▶ OnEnterWorld   ◀── read your per-char store here
   … running: throttled auto-save every 30 s (writes only what changed) …
OnDisable (relog / reload / shutdown) ─▶ flush all
```

- **Account-scope vars are ready in the file body / `OnLoad`** (loaded before your files run).
- **Per-character vars are restored just before `OnEnterWorld`** — read/initialize them there or later,
  **not** in `OnLoad` (the character isn't known yet). This matches the rest of the API, where all
  char/world state streams in at `OnEnterWorld` anyway. (Unlike the read API, though, the store has **no
  post-enter streaming delay** — it is fully restored the instant `OnEnterWorld` fires.)
- **Flush** happens on `OnDisable` (a relog rebinds the session, which tears down and flushes the old
  addons first) and on a **30 s throttle** while running (covers an unclean exit; mirrors how `GameUI`
  throttles window-position saves). The write-skip cache means an unchanged flush touches no disk.

### Why flush uses a captured scope, not a live lookup

On a relog, `AddonManager.init` binds the **new** UI and tears down the **old** addons *before* the new
`GameUI` exists — a live `gui()` lookup would find nothing. So the `<genus>_<char>` key is **captured at
`OnEnterWorld`** into `charScope` and reused on flush, guaranteeing the old character's data lands in the
old character's folder. `charScope` resets per session in `init()` (after the old-session flush).

## Serialization

- **Writer:** the compact single-line JSON writer already used by the `:lua` REPL (arrays vs objects
  auto-detected, cycles broken). Values allowed: table / string / number / boolean (nested freely).
- **Reader:** the engine's dependency-free `Json` parser (the one that reads manifests), converted back to
  Lua (`fillTable`: JSON object → string-keyed table, JSON array → 1-based table).
- **Round-trips intact:** nested objects and arrays (e.g. `recent = {"login #1", ...}` ↔ `["login #1", …]`).
  Integers stay clean (no `.0`). **Caveat:** numbers are IEEE doubles, so integers beyond 2⁵³ lose
  precision (same reason grid ids are strings). An **empty** table serializes as `[]` (Lua can't tell an
  empty array from an empty object) but round-trips as an empty table — harmless.

## Writes are atomic and non-fatal

Each file is written to a `.tmp` sibling then `move`d over the target (atomic where the OS supports it),
so a crash mid-write can't corrupt an existing file. A write failure (e.g. a read-only install) is
**logged, never thrown** — a broken save never breaks the addon or the frame.

## The `hello` example (`addons/hello/main.lua`)

A second `OnEnterWorld` handler (demonstrating multiple subscribers) exercises both scopes:

```lua
hafen.events.on("OnEnterWorld", function()
  local s = hafen.store.persist                 -- per-character
  s.logins = (s.logins or 0) + 1
  s.name = hafen.player.name() or s.name
  s.recent = s.recent or {}                      -- a bounded array (round-trips as JSON array)
  s.recent[#s.recent + 1] = ("login #%d"):format(s.logins)
  while #s.recent > 5 do table.remove(s.recent, 1) end

  local a = hafen.store.acct                     -- account-wide
  a.logins = (a.logins or 0) + 1

  hafen.log(("store: %s entered %d time(s) [account total %d]; recent: %s")
    :format(tostring(s.name), s.logins, a.logins, table.concat(s.recent, ", ")))
  hafen.store.flush()
end)
```

Each world entry bumps a **per-character** login counter and an **account** counter — the proof that the
values survive a relog (per char) and are shared across characters (account). It stays our standing
regression harness — one login re-checks Phase 0 / 1a / 1b / 1c / 1d **and** this slice. Bumped to
**v0.10.0**, and its `manifest.json` now declares `saved_variables`.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```
ant run
```

1. Enter the world. Expect: `[hello] store: <char> entered 1 time(s) [account total 1]; recent: login #1`.
2. Confirm a file appeared: `bin/savedata/<genus>_<char>/hello.json` (and `bin/savedata/account/hello.json`).
   It should contain `"logins":1`.
3. **Log out and back in** (same character). Expect the counter to increment:
   `... entered 2 time(s) [account total 2]; recent: login #1, login #2`. **This is the DoD** — the value
   survived the relog.
4. (Optional) Log in a **different** character: its per-char `logins` starts at 1, but the **account**
   total continues from where it was (shared).
5. Poke it live from the console: `:lua hafen.store.persist` and `:lua hafen.store.acct` show the tables;
   `:lua hafen.store.persist.foo = 42` then `:lua hafen.store.flush()` writes it (visible in the JSON).

## Files

- `src/io/brodgar/addon/Manifest.java` — parse `saved_variables` into `List<SavedVar>` (name + `account`
  flag); new public `SavedVar` type.
- `src/io/brodgar/addon/Addon.java` — holds the `store` proxy table + per-scope write-skip caches.
- `src/io/brodgar/addon/AddonManager.java` — the `hafen.store` facade (proxy table + `flush`); the
  lifecycle wiring (`restorePerChar` before `OnEnterWorld`; flush in `teardown` after `OnDisable`;
  throttled auto-save in `tick`; `charScope`/`lastAutoSave` reset in `init`); and the store helpers
  (`saveDir`, `scopeKey`/`sanitize`, `storeFile`, `loadScope`, `flush`, `writeScope`, `scopeJson`,
  `hasScope`, `jsonToLua`, `fillTable`, `clearTable`, `readFile`, `writeFile`). Reuses the existing
  `json(LuaValue)` writer and `Json` reader.
- `addons/hello/` — example + manifest bumped to **v0.10.0** with a `saved_variables` declaration.
- `.gitignore` — added `/savedata`.

**No `haven` core edit** — saved variables are pure engine + filesystem (all reads for genus/char use the
already-public `GameUI.genus`/`chrid`).

## Limitations / deferred

- **No sandbox/quotas yet** (Phase 1f) — a saved table is written as-is; there is no size cap or key
  whitelist. Storing a function/userdata is quietly stringified by the writer (don't).
- **No dedicated shutdown hook** — a hard client-close relies on the 30 s auto-save (at most ~30 s lost);
  a relog flushes cleanly via `OnDisable`. A UI-thread shutdown flush can be added if needed.
- **Per-character only distinguishes `<genus>_<char>`** — two same-named characters in the same genus
  would share a folder (not expected in practice; `chrid` is unique per world).
- **`:reload` (Phase 1f)** will re-run the flush → teardown → reload path; the store already flushes on
  `OnDisable`, so it is reload-ready.

# 159 — Plan

## Approach

**One class, two files, two seams.** `io.brodgar.addon.SqliteCache implements haven.ResCache`, one
instance per file, opened by `SqliteCache.open(String filename)` under `ClientDb.dir()` (package-private,
same package — `savedata/`, or `haven.savedatadir`). The connection recipe is `ClientDb.Conn`'s: WAL,
`synchronous=NORMAL`, busy timeout 3000 ms, `PRAGMA user_version` = 1 and a higher one refused,
`Exception | LinkageError` caught into a `Warning` and a `null` store — the client then runs without
it, exactly as when `HashDirCache.create()` returns `null`. A shutdown hook closes the connection so
the WAL is checkpointed and the sidecars go.

Schema, from the benchmark of 2026-09-20 — a `WITHOUT ROWID` text key spilled 1.7 KB blobs to overflow
pages and read 5× slower:

```sql
CREATE TABLE entries (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE,
                      data BLOB NOT NULL, mtime INTEGER NOT NULL)
```

- `fetch(name)`: `SELECT data FROM entries WHERE name = ?`; the bytes are copied and the `ResultSet`
  closed before a `ByteArrayInputStream` is returned. A miss is `FileNotFoundException(name)` — the
  signal `CacheSource`, `Pool.handle`, `GobIcon.Settings.load` and `MapFile`'s `sfetch` callers key
  on; a `SQLException` is wrapped in an `IOException`.
- `store(name)`: an `OutputStream` over a `ByteArrayOutputStream`; `close()` — idempotent — runs
  `INSERT … ON CONFLICT(name) DO UPDATE SET data = excluded.data, mtime = excluded.mtime`, autocommit.
  A stream never closed writes nothing: `StreamTee.setncwe` closes the fork only after EOF, so an
  aborted download leaves no entry where `HashDirCache` leaves a header-only slot. A zero-length blob
  round-trips as such — `MapFile.segments`' store writes one as a tombstone.
- One connection per file, every statement under the instance's monitor: reads ~10 µs, writes
  ~50–100 µs, so every thread serialises on it without a queue. `toString()` is `SqliteCache(<path>)`,
  which `CacheSource.cachedesc` puts in every `LoadException`.

**The switch** lives in the class: `SqliteCache.mode`, a `Config.Variable<String>` `haven.store`
defaulting to `files`; `sqlite` selects the class, anything else warns naming the value and both
accepted ones, and means `files`. Two statics answer the two seams — `global()`: `map.sqlite`, else
`HashDirCache.create()`; `resources()`: `rescache.sqlite`, else `ResCache.global`. The `haven` edits
are two `// addon:` lines: `ResCache.StupidJavaCodeContainer.makeglobal` returns `SqliteCache.global()`,
and `Client.setupres` passes `SqliteCache.resources()` to `Resource.setcache`, null-guarded as today
and at the same place — before the first `Resource.remote()`, which builds the `CacheSource`.

**`:store`** is registered in `SqliteCache`'s static initialiser (`Console.setscmd`, the pattern of
`AddonManager`'s block; the class loads in both modes because `global()` is the switch) and prints
through `cons.out`, which `GameUI.added` re-points at the System log — where the suites read it. Lines:
`store: sqlite|files`; in sqlite mode `map: <path> — <n> entries, <x> MB`, `res: …` (or `— not open:
<why>`) and `sweep: examined <n>, dropped <m>` (`sweep: none` before one ran); in files mode `data:
<Config.localdir()/data> — HashDirCache <id>` (`HashDirCache.base` is private; the path is by
construction). `:store sweep` runs the sweep now (159.3).

**The sweep (159.3)** runs on a daemon thread started by `resources()` in sqlite mode after the open,
and on `:store sweep`. It reads `SELECT id, name, substr(data, 17, 2) FROM entries WHERE name LIKE
'res/%'` — the little-endian `uint16` after the 16-byte `"Haven Resource 1"` signature, never the
blob — and probes the pack per row the way `JarSource.get` does,
`Resource.class.getResourceAsStream("/brodgar-res/" + name + ".res")`, reading the same 18 bytes.
Pack version ≥ row version → `DELETE`; the pack lacks the name, or the row is newer → keep. Thousands
of rows at ~100 µs each: under a second. A row the tee writes meanwhile is a version the pack lacked —
kept by the rule. `examined`/`dropped` sit in statics for `:store`.

**Build**: `<sysproperty if:set="store" key="haven.store" value="${store}" />` in the `run` target,
beside the `registry` line.

## Files to create/modify

- **Create** `src/io/brodgar/addon/SqliteCache.java` (the class, the switch, `:store`, the sweep).
- **Modify** `src/haven/ResCache.java` (`makeglobal`), `src/haven/Client.java` (`setupres`), `build.xml`.
- **Create** `docs/client/rescache.md`; **modify** `docs/client/README.md` (row),
  `docs/client/resource-loading.md`, `docs/client/mapfile.md`, `docs/client/services.md` (in place),
  `docs/addons/manifest.md` (layout + owner row), `docs/addons/runtime.md` (commands table).
- Suites `addons/159-sqlite-store.1/`, `addons/159-sqlite-store.3/`.

## Risks & gotchas

- **`ResCache.global` is built at interface initialisation** (`StupidJavaCodeContainer.makeglobal`):
  the switch and the driver load the first time anything touches it, and a `Warning` there reaches
  stderr only.
- **A miss must be `FileNotFoundException`, and only a miss.** `Pool.handle` marks `Queued.found` on
  any other throwable, and judges an empty stream itself (`"empty file"`) — return the tombstone as is.
- **Never hold a `ResultSet` across a return**: an open read transaction blocks the WAL checkpoint and
  the `-wal` file grows for the session.
- **Two JVMs on one file** (one folder started twice): WAL plus the busy timeout is the whole answer;
  `MapFile`'s own two-process race is unchanged and on `mapfile.md`.
- **`Console.setscmd` has no unregister** and a static command shadows an addon's: `store` makes
  `hafen.console():on("store")` a refusal naming the command — asserted by the suite, in `runtime.md`.
- **`resource-loading.md` is above its 150-line ceiling and `mapfile.md` at it**: their edits replace
  text inside an existing row or line, adding none.
- **The suite's clock.** A grid recorded by an earlier process has `grid:modified()` before the suite's
  load time (`os.time()` at file load). The suite finds one by probing `segment:grid():get({x, y})` on
  a ring around the character's grid (`player():gob():position():info().gridId` →
  `hafen.map():grid():get(id):segmentCoord()`), polling on a timer since a loading coordinate answers
  `nil`. Only such a grid proves a read from `map.sqlite`; a live one may come from memory.
- **The pack is `bin/brodgar-res.jar`**, on the classpath through `hafen.jar`'s `Class-Path`; 159.3's
  pre-check builds a tiny jar of its own with `brodgar-res/<name>.res` entries at chosen versions.
- **The pre-check is headless**: a `MapFile` over a `SqliteCache` (`Grid.save`/`load`, the tombstone),
  a `TeeSource` over a fake back source into a `CacheSource`, four threads hammering `store`/`fetch`,
  two JVMs on one file — with `-Dhaven.savedatadir=<scratch>`, since `ClientDb.dir()` resolves beside
  the jar. `luac -p` and a stubbed `hafen` for the suites.

## Discarded alternatives

- **One SQLite file for cache and map** — a cache wipe or a future eviction would run inside the
  player's map, and a `VACUUM` would need the map's size twice.
- **Routing names by prefix inside one store** — the two lifecycles are two files, chosen at the two
  call sites, not by parsing `res/` and `map/`.
- **A writer thread with a queue** — writes are ~50–100 µs autocommit; a queue adds lost-at-exit writes
  for nothing measurable, and the map's processor thread already serialises its own.
- **`name TEXT PRIMARY KEY … WITHOUT ROWID`** — 5× slower on 1.7 KB blobs: the payload overflows the
  index B-tree's cell.
- **Importing `%APPDATA%\…\data`** — the maintainer's ruling: each store keeps its own data.
- **A size cap or LRU** — a policy on the cache, not the store; `mtime` is there for the day it comes.
- **An access time written on every read** — a write per read on the loader threads.
- **A Lua verb reporting the store** — no addon needs it; the console command is the client's
  diagnostic and the suites read it back from the System log.
- **Replacing `HashDirCache` outright** — the maintainer's ruling: a toggle, each store with its own data.
- **Sweeping only when the pack changes** — the sweep is under a second and bounded by the cache's
  size; a pack identity is one more thing to get wrong.
- **Sweeping in files mode** — enumerating 680k files is minutes.

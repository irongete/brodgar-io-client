# 159 — SQLite store

## What & why

Everything the client keeps on disk outside `savedata/` goes through one interface, `ResCache`
(`store(name)` → `OutputStream`, `fetch(name)` → `InputStream`), and one implementation:
`HashDirCache`, a hash table spread over `%APPDATA%\Haven and Hearth\data` as one file per entry. Its
tenants are the resource cache (`res/<name>`), the recorded map (`map/<file>/{index,gi-,grid-,seg-,zgrid-}`)
and the minimap icon settings (`data/mm-icons-2/…`). Measured: 680k files, ~88 % of them map entries,
and every read is a file open under an exclusive lock — ~510 µs before a byte is parsed, ~640 µs for
a grid; the same entries in a SQLite file read in ~10 µs.

A second implementation, selected by a JVM property the launcher passes:

- `-Dhaven.store=files` (the default, and the value when the property is absent): `HashDirCache` under
  `%APPDATA%`, untouched.
- `-Dhaven.store=sqlite`: two SQLite files beside the jar, `savedata/map.sqlite` for what
  `ResCache.global` holds (the map, the icon settings) and `savedata/rescache.sqlite` for the resource
  cache. One blob per entry, the bytes `HashDirCache` holds today: ~1.7 KB per grid.

**Each store keeps its own data.** No import, no migration: the SQLite files start empty and fill as
the client plays; switching back shows the files store as it was left. The launcher checkbox that
passes the property belongs to the launcher repository.

**The cache follows the pack.** `brodgar-res.jar` answers before the disk cache, so the cache only
receives what the pack lacks — dead weight once the pack is rebuilt. In sqlite mode it is dropped.

## Acceptance criteria

1. Started with `-Dhaven.store=sqlite`, the client creates and writes `savedata/map.sqlite` and
   `savedata/rescache.sqlite` beside the jar (`ClientDb.dir()`, honouring `haven.savedatadir`) and
   neither creates nor writes `%APPDATA%\Haven and Hearth\data`.
2. Started without the property, or with `files`, nothing changes. Any other value warns, naming it
   and the two accepted, and means `files`.
3. `:store` prints the store in force: in sqlite mode one line per file with its path, entry count and
   size; in files mode the folder and the `HashDirCache` identity. Its lines reach the System log
   in-world, where a suite reads them back.
4. The map round-trips through the SQLite store: after a relog in sqlite mode, a grid recorded in an
   earlier run answers its tiles and heights, and the segments and markers of that run are there.
5. A resource the network answers is written into `rescache.sqlite` only when its stream was read to
   the end (`StreamTee`'s rule), and a zero-length entry — the segment tombstone `MapFile` writes — is
   stored and read back as zero bytes, never as a miss.
6. A store that cannot be opened (no driver or `java.sql`, an unwritable folder, a file a newer client
   wrote) leaves the client running without it, with a warning naming the file and the reason — the
   degradation a missing `HashDirCache` already has.
7. Two client processes on one `savedata/` share the files without an error (WAL, a busy timeout).
8. `ant -Dstore=sqlite run` starts the development client in sqlite mode.
9. In sqlite mode a background sweep at start drops every `res/` entry whose name the pack holds at
   a version ≥ the entry's (the `uint16` after `"Haven Resource 1"`); a newer entry stays; no pack,
   nothing dropped. `:store` reports the last sweep's examined and dropped counts. Files mode never
   sweeps.
10. The docs say all of it (below), and `tools/docverbs.py` and `tools/refusalverbs.py` exit 0.

## Out of scope

- **The launcher checkbox** — the launcher repository's; this feature ships the property it passes.
- **Import or migration** in either direction, by the maintainer's ruling; the map window's export
  and import move a map.
- **A size cap or eviction** for `rescache.sqlite` — a policy `HashDirCache` lacks too; each entry's
  write time is recorded so a later policy needs no migration.
- **A relational map schema** (a row per grid with columns): a feature over `MapFile`'s own
  persistence, not over `ResCache`. This feature's blobs are what it would import.
- `haven-config.properties` stays in `%APPDATA%`; `haven.cachebase`/`haven.mapbase` mean nothing to
  the SQLite store; `HashDirCache`, `BaseFileCache`, `FileCache`, `SteamCache` and the pack's
  `JarSource` are untouched.

## Docs impact

- **New** `docs/client/rescache.md`: the on-disk store — `ResCache`, who writes which key family,
  `HashDirCache`'s mechanics and gotchas, the `haven.store` seam and the sweep. Row in `docs/client/README.md`.
- **In place, line-neutral**: `docs/client/resource-loading.md` (the `CacheSource(prscache)` cell —
  the page is above its ceiling), `docs/client/mapfile.md` (the `mapstore` line — at its ceiling),
  `docs/client/services.md` (the "Local data dir" row).
- `docs/addons/manifest.md`: the `savedata/` layout gains the two files, and the "filed by owner" row
  says whose they are. `docs/addons/runtime.md`: `:store` in the console commands table (now a name an
  addon cannot register).
- Derived impact set — `grep -rn -i "APPDATA\|HashDirCache\|ResCache\|localdir\|disk cache" docs/`:
  `client/services.md:15`, `client/mapfile.md:9,10,15`, `client/steam.md:16` (stays),
  `client/resource-loading.md:13,18,85`. `grep -rn "savedata" docs/` beyond `<id>` rows:
  `addons/manifest.md:27-39`, `client/services.md:15,17`; `addons/api/store/README.md:56` and
  `client/prefs-and-options.md:15` stay true.

## Context files

- `src/haven/{ResCache,HashDirCache}.java` — 1, 2
- `src/haven/LockedFile.java` — 2
- `src/haven/Client.java` — 1, 3
- `src/haven/Resource.java` (`setcache`, the sources, `remote`, the version check in `load`) — 1, 2, 3
- `src/haven/{StreamTee,Config,Console}.java` — 1
- `src/haven/MapFile.java` (`sfetch`/`sstore`, `gridinfo`, `segments`, `Grid.load`/`save`) — 1, 2
- `src/haven/{GobIcon,GameUI}.java` (`Settings`; `mapstore`, `iconconfname`) — 2
- `src/io/brodgar/addon/{ClientDb,AddonManager}.java` — 1
- `src/io/brodgar/addon/SqliteCache.java` (159.1's) — 2, 3
- `build.xml` (the `run` target) — 1
- `docs/client/{console,resource-loading}.md` — 1, 2, 3
- `docs/client/{mapfile,services,README}.md`, `DOCUMENTATION.md` — 2
- `docs/client/rescache.md` (159.2's) — 3
- `docs/addons/{manifest,runtime}.md` — 1, 2, 3
- `docs/addons/api/{console,chat}.md`, `docs/addons/api/resource/README.md` — 1, 3
- `docs/addons/api/map/grids.md`, `docs/addons/api/{position,player}.md`, `docs/addons/guides/permissions.md` — 1

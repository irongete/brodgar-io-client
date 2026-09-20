# 159 — Tasks

- [x] **159.1 — The SQLite store and the switch.** `io.brodgar.addon.SqliteCache implements ResCache`:
      `open(filename)` under `ClientDb.dir()` with `ClientDb.Conn`'s recipe (WAL, `synchronous=NORMAL`,
      busy 3000 ms, `user_version` 1, `Exception | LinkageError` → `Warning` + `null`), the `entries`
      table of `plan.md`, `fetch` (a copy, the `ResultSet` closed, a miss `FileNotFoundException`, a
      zero-length blob returned as such), `store` (buffered, the upsert on an idempotent `close()`,
      nothing written otherwise), one connection under the instance's monitor, a shutdown hook,
      `toString()`. `mode` (`haven.store`: `files` default, `sqlite`, anything else warns and is
      `files`), `global()` (`map.sqlite` | `HashDirCache.create()`), `resources()` (`rescache.sqlite`
      | `ResCache.global`). The two `// addon:` lines: `ResCache.StupidJavaCodeContainer.makeglobal`,
      `Client.setupres`. `:store` (`Console.setscmd` in the class's static block, printing through
      `cons.out`: `store:`, `map:`, `res:` lines, `sweep: none`). `build.xml`: the `store` sysproperty.
      Criteria 1–8.
      *Its suite* (`addons/159-sqlite-store.1/`, permission `console.run`, run in sqlite mode after a
      second login at a place walked away from): `session:console():run("store")`, then the System
      log's newest lines (`session:chat():find` on kind `chat.system`, `:message():list()`, a timer
      up to 2 s) — `[pass]` `store: sqlite`, `[pass]` `map:` and `res:` naming `savedata` paths; a
      timer up to 30 s until `map:` reports ≥ 1 entry — `[pass]` the map writes through the store;
      a grid recorded before the suite's load time, found on a ring of radius 2–12 around the
      character's grid through `segment:grid():get({x, y})` polled up to 20 s, `grid:live()` false,
      whose `grid:tile({0, 0})` and `grid:height({0, 0})` answer — `[pass]` read from `map.sqlite`
      (else `[manual]` relog after walking, run again); `pcall(hafen.console().on, …, "store", fn)`
      fails naming the command — `[pass]`.
      `[manual]`: launch without the property and type `:store` -- expect `store: files` and the
      `%APPDATA%\Haven and Hearth\data` path. `[manual]`: that folder's modified date -- expect: older
      than this session. `[manual]`: a second client started from the same folder logs in -- expect:
      no store warning in either.
      <!-- extra context: the headless pre-check of plan.md runs before the maintainer restarts -->

- [x] **159.2 — The on-disk store, mapped.** `docs/client/rescache.md` (≤ 150 lines): `ResCache` and
      its two implementations; who writes which key family (`Resource` `res/<name>` through the tee,
      `MapFile` `map/<file>/…`, `GobIcon.Settings` `data/mm-icons-2/…`, `Client.setupres`'s dormant
      `tmp/allused`); `HashDirCache`'s mechanics — the identity hash, the `%016x.%d` slots, the header,
      `lookup`'s exclusive `LockedFile`, `store`'s temp-and-rename, no eviction, `list` only in `main`
      and the CLI (`ls`, `cat`, `purge`, `rm`); gotchas — a read opens for write and locks, header-only
      slots from an unclosed store stream, a `-U` change orphans a whole identity, the segment
      tombstone is a zero-length entry; the `haven.store` seam — three `// addon:` lines, not two:
      `ResCache.makeglobal`, `Client.setupres`, and `GameUI`'s `mapstore`, which every shipped
      `haven-config.properties` sends to `HashDirCache.get(haven.mapbase)` unless the store is sqlite —
      the two files, `:store` and the sweep as seams, the SQLite store named as `src/`'s. Rows in `docs/client/README.md`; the in-place
      edits of `resource-loading.md`, `mapfile.md`, `services.md`; `docs/addons/manifest.md`'s layout
      and owner row; `docs/addons/runtime.md`'s commands table. Criterion 10.
      *Its verification* (the precedent of 154: a docs task ships no addon): `tools/docverbs.py` and
      `tools/refusalverbs.py` exit 0; every relative link and anchor resolves; `wc -l` shows
      `rescache.md` ≤ 150, `resource-loading.md` and `mapfile.md` at their counts before the task;
      `grep -rn "io.brodgar" docs/client/rescache.md` finds nothing but the one `src/` pointer.

- [x] **159.3 — The cache follows the pack.** The sweep of `plan.md` in `SqliteCache`: on a daemon
      thread from `resources()` in sqlite mode, and on `:store sweep`; `SELECT id, name, substr(data,
      17, 2)` over `res/%` rows, the pack probed per row through `Resource.class.getResourceAsStream`,
      the little-endian `uint16` compared, `DELETE` when the pack's is ≥; `examined`/`dropped` in
      statics, printed by `:store` as `sweep: examined <n>, dropped <m>`, and one stderr line per run.
      `rescache.md`'s sweep row filled in. Criterion 9.
      *Its suite* (`addons/159-sqlite-store.3/`, permission `console.run`): `:store` read back as in
      159.1 — `[pass]` a `sweep:` line with two numbers; `:store sweep`, then `:store` again — `[pass]`
      `dropped 0` on a second run (nothing left to drop), `[pass]` `examined` ≤ the `res:` entry count
      and `dropped` ≤ `examined`; `pcall(hafen.console().on, …, "store", fn)` fails naming the command
      — `[pass]`.
      `[manual]`: rebuild `brodgar-res.jar` after a session that fetched from the network, restart,
      `:store` -- expect: `dropped` > 0 and the `res:` count lower than before.
      <!-- extra context: the pre-check builds a small jar of `brodgar-res/<name>.res` entries at chosen
      versions on the classpath and asserts drop / keep-newer / keep-absent before the restart -->

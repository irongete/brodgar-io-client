# 146 — plan

## Approach

**`StoreApi` keeps its Lua half and swaps its persistence half.** `installStore`, `store(owner,
user)`, `:get`/`:list`/`:flush`, `CharStore` and its lifecycle (`enterWorld`, `sessionEnded`,
`rescope`, `detach`, `autosave`) stay. `loadInto` reads a row of `hafen_documents`; `writeAccount`
and `writeChar` write one after the unchanged-JSON check; `writePlacements` writes
`hafen_placements`; `readFile`, `writeFile`, `accountFile`, `charFile` go. `scopeKey` stays as the
row key — `""` for the addon's own documents, the character's key for theirs. `Manifest.savedvars`
reads `"scope": "client"` or `"character"` (a bare name), refuses any other word naming the two;
`SavedVar.account` becomes `client`.

**One new file, `SqliteApi.java`, owns the connection and the record verbs.** `open(a)` at
`installStore` — documents are readable before `Load` — builds `savedata/<id>.sqlite` through
`Inside.inside(StoreApi.saveDir().toPath(), …)` and opens it with `SQLiteConfig`: `setJournalMode(WAL)`, `setSynchronous(NORMAL)`, `enforceForeignKeys(true)`,
`enableLoadExtension(false)`, a busy timeout for a second client on the folder; then
`SQLiteConnection.setLimit(SQLITE_LIMIT_ATTACHED, 0)`, creates the two `hafen_` tables, and sets
`user_version`. Every `org.sqlite`/`java.sql` type lives in a nested `Db` class. **An open that fails**
— native library, corrupt file, lock — leaves the store unavailable: documents empty and never
written, every other verb refusing naming the cause, one log line naming the file; the addon loads.
The bridge serializes every verb on the connection.

**The one section carries nine verbs**, mounted in `installStore`: `:get`, `:list`, `:flush`,
`:info` (`{file, bytes}`), `:table`, `:exec`, `:query`, `:transaction`, `:vacuum` — actions that
answer, none a property write.

**The builder** (`:table(name)` → `:column(name, type)` → `:key(...)` → `:index(...)` → `:create()`)
is validated as it is configured (the name, a repeated column, the type, the key, a `:create()`
without one) and dispatched once. `:create()` reads `pragma_table_info` and `pragma_index_list`: absent → `CREATE TABLE` with the
declared affinities and `PRIMARY KEY`; present → `ALTER TABLE ADD COLUMN` for each declared column
the file lacks, a stored column nobody declares left alone (it is data), a key that differs from the
stored `pk` ordinals refused naming both; then `CREATE INDEX IF NOT EXISTS <table>_<cols>`. It answers
the **Table**, interned per addon by name in `Addon`; its live declaration is the only place the Lua
types live — `boolean` and `json` are `INTEGER` and `TEXT` in the file.

**The Table's verbs** are the collection grammar over rows: `:put(row)` is `INSERT OR REPLACE …
RETURNING *`, typed both ways, an undeclared column or a wrong type refused naming it; `:get(k, ...)`
and `:remove(k, ...)` take the key in `:key` order, the count held; `:list`, `:count`, `:find` run
`SELECT * | count(*) FROM <table> <clause>` with `?` values, a function clause refused naming SQL,
`:find` reading one row. A row is a plain table; `NULL` is an absent key.

**Statements** bind a string as `TEXT`, a whole number as `INTEGER`, any other as `REAL`, a boolean as
`1`/`0`, `nil` as `NULL`, the count held to `getParameterCount()`; a column reads back
`Integer`/`Long` → number (exact to 2^53), `Double` → number, `String`/`byte[]` → string, `null` →
absent. `execute()` answering a result set decides the verb: rows through `:exec` name `:query`, none
through `:query` name `:exec`. **The scan** (a `;` outside `'…'`, `"…"`, `` `…` ``, `[…]`, `--`,
`/* */`) refuses a second statement — xerial silently drops it on both paths, verified — and reads
the first keyword and every identifier: `CREATE TABLE`/`CREATE INDEX` name the builder, `hafen_`
names the client's tables, `ATTACH`/`DETACH` the sandbox, `VACUUM` `:vacuum()`, the six transaction
words `:transaction(fn)`. Enforcement is the attach limit (it refuses `VACUUM INTO` too, verified —
`:vacuum()` raises it to 1 inside its lock) and `load_extension` off; the keyword only picks the
message. `:transaction(fn, ...)` brackets `BEGIN`/`COMMIT`, rolls back and re-raises, refuses nesting
and `:vacuum()` inside.

**Two bounds, `Json.MAX_INPUT`'s precedent** — one bridge call the watchdog charges ~1 for.
`ProgressHandler.setHandler(conn, 1000, h)` with `progress()` answering 1 past a deadline set per
statement from `-Dhaven.addon.sqlite.timeout` (ms, 5000) — `SQLITE_INTERRUPT`, connection still
usable, verified; `-Dhaven.addon.sqlite.maxrows` (50000) stops `:query`, `:list`, `:find` naming
`LIMIT`.

**Lifecycle.** Closed by `Step("sqlite", SqliteApi::close)` after `"saved variables"` in
`AddonRegistry.STEPS` and in `flushAll` beside `StoreApi.flush(a)`; `close()` checkpoints and drops
the `-wal`/`-shm` sidecars, verified.

## Files to create/modify

- `src/io/brodgar/addon/SqliteApi.java` — new: `Db`, the builder, the Table, the scan, the verbs
- `src/io/brodgar/addon/StoreApi.java`, `Manifest.java`, `AddonRegistry.java` — as above
- `build.xml` — `get-sqlite` (`org/xerial/sqlite-jdbc/3.53.4.0`, 12 MB, `slf4j-api` optional),
  `hafen-client` depends on it, the jar's `Class-Path`
- `../brodgar-io-client-launcher/build.xml` — `java.sql` in `modules`;
  `../brodgar-io-client-addons/*/manifest.json` — `"client"`; both commits are the maintainer's
- `docs/addons/api/store/README.md`, `documents.md`, `tables.md`, `statements.md` — new; `api/store.md`
  removed; the spec's impact set; 29 pages re-pointed
- `tools/docverbs.py` — the pages' receivers in the per-page table

## Risks and gotchas

- **`java.sql` is not in the launcher's runtime** — a hand-kept `modules` list; a released client
  throws `NoClassDefFoundError` where `ant run` works.
- **The native library is extracted to `java.io.tmpdir`** (`org.sqlite.tmpdir` overrides); a folder
  it cannot write is the unavailable store.
- **Every addon with a document needs the driver at install** — the cost of one file; unavailable
  is a logged line, not a failed load.
- **`Statement.execute` and `prepareStatement` both drop a second statement silently**;
  `getUpdateCount()` reads `1` for `BEGIN` — use `DB.changes()`.
- **`INSERT OR REPLACE` deletes and reinserts**: a column left out of `put` is `NULL`; a one-column
  change is an `UPDATE` through `:exec`.
- **LuaJ's `LuaInteger` is 32-bit**: a `Long` past `int` reads back as `LuaDouble`; `os.time()` is a
  double and binds `INTEGER` because it is whole.
- **The message thread can be inside your Lua**: the bridge's lock makes the shared connection safe.
- **A suite writes its own file**: it drops its tables first; the reload check is a row from an earlier
  run — `[manual]` on the first run, `[pass]` on the second.
- **The maintainer's `savedata/**/*.json` are read by nothing after 146.1** — reported at the close;
  nothing in the client or the docs knows they existed.

## Discarded alternatives

- **`hafen.sqlite()` beside the store** — two answers to "where do I keep data", one with a ceiling.
- **A query language of its own (`nodes:list{kind = "fir"}`)** — cannot say the nearest 200 over a set
  of grids or a `GROUP BY`, so it ends with a raw-SQL hatch: two spellings.
- **Settings as declared tables, no documents** — `settings:get("main").value.enabled` against
  `settings.enabled`; a live table is what settings are.
- **A file per account or per character** — a Gatherer that forgets every node at the other login.
- **Keeping `"scope": "account"`** — the file is every account's; the word is the misreading.
- **`:add` for the upsert** — `add` says new; a tree put twice is one row, and `put` says so.
- **The Lua types in an engine table** — the live declaration is re-read every load; a type nobody
  declared has no reader.
- **A transaction object with `:finish()`** — a bracket left open outlives the frame.
- **Asynchronous verbs** — an indexed local read answers in microseconds; a callback per read is a
  state machine per data path.
- **A pure-Java engine** — H2 runs Java source through `CREATE ALIAS`; SQLite's escapes are three.
- **A statement-text sandbox** — the attach limit is the engine's refusal; the scan only picks messages.
- **Import code in the client** — nothing is released; the one savedata that exists is the
  maintainer's.
- **Trimming the jar's platforms** — breaks on the one nobody tested.

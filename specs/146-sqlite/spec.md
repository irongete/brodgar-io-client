# 146 — The store is SQLite: documents, tables and statements

## What & why

`hafen.store()` keeps an addon's data as whole documents: whole in RAM, serialized whole on the
session tick to see whether anything changed, capped at 8 million characters. Right for settings;
wrong for a record. Nothing is released, so the store is cut over whole.

**The store becomes one SQLite file per addon, for the client**: `savedata/<id>.sqlite`, read and
written by every account this client logs in with and every character. It holds three shapes:

- a **document** — a live Lua table you assign into, declared in the manifest, saved for you: settings.
  A character's documents are rows keyed by that character; the addon's are keyed by nobody.
- a **table** you declare — columns, a key, indexes — whose rows go in and come out typed: a record. A
  character is a column where the rows are one character's, absent where they are everyone's.
- a **statement** — SQL, for what only SQL says: an aggregate, a join, a bulk write.

## Acceptance criteria

1. `hafen.store():info()` answers `{file, bytes}`; the file is `savedata/<id>.sqlite`, its name
   holding no login's.
2. A bare name in `saved_variables` is a character's document, `"scope": "client"` the addon's, any
   other word a manifest error naming the two; `:get`, `:list`, `:flush` answer on both doors; a
   document written before a `:reload` is read back after it; `w:remember` lands in the same file.
3. `hafen.store():table(name)` is a builder — `:column(name, type)` over `text`, `integer`, `real`,
   `boolean`, `json`; `:key(col, ...)`; `:index(col, ...)`; `:create()` answering the interned Table;
   a malformed declaration is refused naming what is allowed. `:create()` over a table already in the
   file adds the columns and indexes it lacks, and refuses a changed key naming both.
4. The Table: `:put(row)` upserts by key and answers the row as stored; `:get(k, ...)` a row or `nil`;
   `:remove(k, ...)` the Table; `:list`, `:count`, `:find` take a SQL clause after `FROM` and `?`
   values. A `boolean` column answers `true`, a `json` column the table it took; an undeclared column,
   a wrong type, a wrong key count and a function clause are each refused naming it.
5. `:exec(sql, ...)` answers the rows it changed; `:query(sql, ...)` rows keyed by column; a `?` binds
   string, number, boolean or `nil`, the count held; rows through `:exec` are refused naming `:query`
   and none through `:query` naming `:exec`; a second statement in one call, a `CREATE TABLE` or
   `CREATE INDEX` (naming the builder) and a `hafen_` name are refused.
6. `:transaction(fn, ...)` commits when `fn` returns, rolls back when it raises with the error out of
   the call, refuses nesting, answers what `fn` answers.
7. `ATTACH`, `load_extension(...)`, `VACUUM INTO` and `BEGIN` are each refused naming why;
   `:vacuum()` answers the store.
8. A statement past the time limit raises naming it; more rows than the cap through `:query`, `:list`
   or `:find` raises naming `LIMIT`.
9. The released client runs it: the launcher's runtime carries `java.sql`.
10. `api/store/` is a hub and three pages, reachable from the index, never calling the file's scope
    an account.

## Out of scope

- **A path of the addon's choosing.** One file, named by the id.
- **A file per account or per character.** A character is a column, or a document's row key; a file
  per login is one schema in N files no query reads together — a Gatherer that forgets every node
  when you log in as your other account.
- **Declaring a virtual table.** `CREATE VIRTUAL TABLE … USING rtree` or `fts5` runs through `:exec`;
  a virtual table has no columns to type.
- **Blobs.** A string binds as `TEXT`; a `BLOB` reads back as a string.
- **A query language of its own.** A clause is SQL and a predicate is refused naming it: `WHERE` in
  Lua is a second grammar that still hands you SQL at the first `GROUP BY`.

Addons declaring `"scope": "account"` stop loading with a manifest error naming `client`; 146.1
edits each of the maintainer's, as a change that breaks one must.

## Docs impact

New: `api/store/README.md` (hub), `documents.md`, `tables.md`, `statements.md`.

```bash
grep -rn "\"scope\": \"account\"\|savedata/\|\.layout\.json\|<genus>_<char>\|account-wide\|account's\b" docs/addons/
grep -rln "store\.md" docs/addons/ | wc -l      # 29 pages
grep -rno "store\.md#[a-z-]*" docs/addons/      # 3 anchor links, 2 anchors
```

- `manifest.md:18-25` — the `savedata/` tree is one file; `saved_variables` names the scopes.
- `guides/saved-data.md:10,18,21` — the scope word, the two doors, the three shapes.
- `guides/translating.md:35,44,51` — `"scope": "client"`, the path comment, "account-wide".
- `getting-started.md:167`, `api/ui/style/README.md:94`, `api/session.md:73` — "account-wide", "an
  account's single file".
- `api/conventions.md` — `nil` is `NULL` in a `?` and a `put` row; a store table's members are rows
  and its filter a clause; *snapshots vs handles*.
- `api/README.md` — Infrastructure; `runtime.md` — a statement that returned is already on disk;
  `panel.md:144` — still true, discharged.

No `docs/client/` page: everything read is `io.brodgar`.

## Context files

- `src/io/brodgar/addon/SqliteApi.java` — 2, 3, 4 (`Db`: the connection, `transaction`, `document`,
  `placements`, `bytes`, `close`, and the statement primitives `select` (rows, at most `max`), `change`,
  `ddl`, `Rows`, `Arity`; `open`, `close`, `db`, `require`, `info`, `Failure`; the builder `Declaration`,
  `Decl`, `Table`, `create`; `bindable` and `raw` — how a `?` binds and a cell reads with no declaration;
  `run` and `refused` — how a driver refusal is phrased as the verb's; the statements: `exec`, `query`,
  `rawRow`, `statement` (the verb's wrapper, where the row ceiling of `:query` is the `-1`), `Scan`
  (`check` and the messages; `bodyEnd` is a trigger's body, `tail` the second statement), `Db.statement`
  (`total_changes()` across the step is the count; `answersRows` decides the verb before the step),
  `Db.Answer`, `Db.Kind`, `Db.read`; the bracket and the bounds: `transaction`, `vacuum`, `TIMEOUT_MS`,
  `MAX_ROWS`, `prop`; `Db.bracket` (holds the monitor across `fn`; `open`, `broken`), `Db.engine`/`begin`/
  `commit`/`rollback` (through `DB.exec`, never JDBC auto-commit), `Db.vacuum` (the attach limit raised and
  put back), `Db.arm`/`disarm` and the `ProgressHandler` in the constructor (the deadline), `Db.read` (the
  cap), `Db.failure` (the interrupt as `Timeout`, and the `BEGIN` after an interrupted write inside a
  bracket), `Db.Open`, `Db.Broken`, `Db.Timeout`, `Db.Cap`)
- `src/io/brodgar/addon/StoreApi.java` — 1, 2, 3, 4 (`installStore` mounts the verbs; the persistence
  half: `loadInto`, `writeClient`, `writeChar`, `writePlacements`, `scopeRows`, `saveDir`, `scopeKey`,
  `CharStore`, `autosave`, `flush`, `detach`; `CLIENT` is the row key of the addon's own scope)
- `src/io/brodgar/addon/Addon.java` — 2, 3, 4 (`db`, `dbWhy`, `storeTables`)
- `src/io/brodgar/addon/Manifest.java` — 1 (`savedvars`, `SavedVar.client`)
- `src/io/brodgar/addon/LuaSession.java` — 1 (`storeObj`); `AddonManager.java` — 1 (`installStore`)
- `src/io/brodgar/addon/AddonRegistry.java` — 1 (`STEPS`, `flushAll`, `shutdown`, `detach`)
- `src/io/brodgar/addon/Section.java`, `Args.java`, `Refusal.java` — 2, 3, 4
- `src/io/brodgar/addon/Json.java` — 1, 2, 4 (`write`, `parse`; `MAX_INPUT`)
- `src/io/brodgar/addon/Inside.java` — 1
- `build.xml` — 1 (`get-luaj`, `Class-Path`)
- `../brodgar-io-client-launcher/build.xml` — 1 (`modules`)
- `../brodgar-io-client-addons/*/manifest.json` — 1
- `tools/docverbs.py` — 5 (`PER_FILE`: the store pages' receivers)
- `docs/addons/api/store/tables.md`, `docs/addons/api/store/statements.md` — 6, 7 (*The Table*, *The clause*,
  *What is refused*: the lines each task adds)
- `DOCUMENTATION.md` — 5

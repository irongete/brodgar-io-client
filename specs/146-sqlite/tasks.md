# 146 — tasks

Each task ships its suite at `addons/146-sqlite.<X>/`, run with `:t146`. A suite clears its own rows
first, so a rerun is clean, and touches no other addon's file.

- [x] **146.1 — The file, and the documents in it.** `build.xml` gains `get-sqlite` (as `get-luaj`)
      and the jar's `Class-Path` names it; the launcher's `modules` gains `java.sql`. New
      `SqliteApi.Db`: open at `installStore`, `savedata/<id>.sqlite` through `Inside`, the config and
      the attach limit of `plan.md`, `hafen_documents`/`hafen_placements`, `user_version`; the
      unavailable state. `StoreApi`'s persistence half reads and writes rows for both scopes and the
      placements; `Manifest.savedvars` takes `client`/`character` and refuses another word; the
      store's `:info()`. Closed by the `Step` after `"saved variables"` and in `flushAll`. Every
      sibling addon declaring `"scope": "account"` gets `"client"`.
      *Its suite* declares `settings` (a character's) and `seen` (`client`), writes both, flushes both
      doors, reads both back; `:info().file` ends in `146-sqlite.1.sqlite` and holds no `account`;
      `bytes` is over 0 after the flush; `:list()` names its own on each door; a run counter in
      `seen` — a count from an earlier run is `[pass]`, none prints
      `[manual] :reload and run :t146 again -- expect: [pass] an earlier run is counted`. The
      manifest refusal is verified by reading its site: a suite cannot ship the manifest it refuses.
      `[manual]`: drag the suite's window, then `:reload` -- expect: it reopens where you left it.
      `[manual]`: run `:t146` on the launcher's rebuilt runtime -- expect: the same summary line.

- [x] **146.2 — Declared tables.** The builder — `:table(name)`, `:column`, `:key`, `:index`,
      `:create()` — its refusals, the evolution against `pragma_table_info`/`pragma_index_list`, the
      interned Table; `:put`, `:get`, `:remove`, `:list`, `:count`, `:find`, typed both ways by the
      live declaration, `json` through `Json.write`/`parse`, `NULL` an absent key.
      *Its suite* declares `nodes` (`grid` text, `x`/`y` integer, `kind` text, `seen` boolean, `flags`
      json; key `grid, x, y`; index `kind`), removes its rows, and asserts: `put` answers the row with
      `seen == true` and `flags.a == 1`; `get(g, 1, 2)` reads it back; `get(g, 1)` fails naming the
      three key columns; `put{ kidn = "fir" }` fails naming `kind`; `put{ x = "1" }` fails naming
      `integer`; `list("WHERE kind = ? ORDER BY x", "fir")` and `count(…)` agree; `find` answers one;
      `remove` then `get` is `nil`; `list(function() end)` fails naming SQL; a second
      `:table("nodes")…:create()` is `==` the first; a declaration with one more column round-trips it
      through `put`; one with another key fails naming both; `:table("hafen_x")` and `:column("a",
      "blob")` fail naming what is allowed.

- [x] **146.3 — Statements.** `:exec`, `:query`, the binding and the arity, `execute()` deciding
      the verb, the scan — a second statement, `CREATE TABLE`/`CREATE INDEX` naming the builder,
      `hafen_`, and the keyword messages — with the library's refusals re-raised naming the sandbox.
      *Its suite* declares a table through the builder, then: `exec("INSERT …", …)` answers `1`;
      `query` answers rows keyed by column with `1` for the boolean — raw, as the page says; three
      values for two `?` fail naming both counts; `nil` binds `NULL` and reads as an absent key;
      `exec("SELECT 1")` fails naming `:query`, `query("DELETE …")` naming `:exec`; two `INSERT`s in one
      string fail naming one per call; `exec("CREATE TABLE t (a)")` fails naming `:table`,
      `CREATE INDEX` naming `:index`; `query("SELECT * FROM hafen_documents")` fails naming the client's
      tables; `ATTACH DATABASE ':memory:' AS m`, `SELECT load_extension('x')`, `VACUUM INTO 'x'` and
      `BEGIN` each fail with the message `plan.md` gives them; `query("INSERT … RETURNING x")` answers.

- [ ] **146.4 — Transactions, the bounds and vacuum.** `:transaction(fn, ...)`; the deadline through
      `ProgressHandler` and `-Dhaven.addon.sqlite.timeout`; the row cap `-Dhaven.addon.sqlite.maxrows`
      over `:query`, `:list`, `:find`; `:vacuum()` raising the attach limit inside its lock.
      *Its suite* puts 10000 rows inside one `:transaction` and asserts `count()` is 10000; a `fn` that
      puts one row then `error("stop")` leaves the count unchanged and the message contains `stop`; a
      nested call fails naming `:transaction`; `:transaction(function() return 1, "a" end)` answers
      `1, "a"`; `:transaction(42)` fails naming `fn`; `:vacuum()` answers the store, and inside a
      transaction fails naming it; a recursive CTE with no end fails naming the timeout, in under
      twice it; the same CTE with `LIMIT 50001` through `query` fails naming `LIMIT`, and `list()` over
      50001 rows too, while `list("LIMIT 10")` answers 10.

- [ ] **146.5 — The pages.** `docs/addons/api/store/README.md` (the file, the three shapes and which
      is which, when it is written and closed, the sandbox as three facts, the two caps, the
      sidecars), `documents.md` (the declaration and its scopes, both doors, what survives, the
      placements), `tables.md` (the builder, the Table, the types both ways, evolution),
      `statements.md` (the verbs, binding, one statement per call, what is refused, threading) —
      never calling the file's scope an account, by `DOCUMENTATION.md` §4; `api/store.md` removed
      and its 29 links and 3 anchors re-pointed; the spec's impact set, each page revised or
      discharged; `conventions.md`'s `nil` row and collections paragraph. `docverbs.py` maps the
      pages' receivers; both checkers run bare and exit 0; the §11 checks over every page touched,
      counts reported.
      *Its suite* is the four pages' example blocks, pasted as written, one `[pass]` per line each
      prints — a page whose example does not run is the defect this suite exists to catch.

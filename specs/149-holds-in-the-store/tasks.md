# 149 — tasks

- [x] **149.1 — The held slots live in the store.** `hafen_holds(scope, slot, entry, at)` joins
      `hafen_documents` and `hafen_placements` in every addon's file (`SqliteApi.Db`'s constructor,
      `SCHEMA` 2); `Db.holds(scope)`, `Db.holds(scope, rows)` and `Db.clearHolds()` are its three doors.
      `BeltHold`'s placements half is rewritten on them: `Placed{id, at}` rows in `SessionState.beltPlaced`,
      `beltLast` by addon id, `beltScope` held, `beltReadOnly`; `restore(st)` flushes the outgoing key, reads
      every loaded addon's rows outside the monitor and merges newest-`at`-wins; `flush(st)` writes the
      changed slices outside the monitor; `write(a)`/`write(a, st)` are called from `StoreApi.flush(a)`,
      `save(a, st)` and `s:store():flush()` beside `writePlacements`; `addonDisabled(id, a)` clears the
      file whole. Retired with it: `BeltHold.FILE`/`json`, `StoreApi.readClientFile`/`writeClientFile`/
      `clientDir`/`readFile`/`writeFile`/`tmpseq`, the `Inside` check in `scopeKey`. The two `hafen_`
      refusal texts name `hafen_holds`. Pages: `store/README.md` (a *held slot* row in *When it is
      written*; sandbox fact 3), `store/statements.md` (the `hafen_` row), `store/documents.md` (the
      character-key sentence), `actionbar.md` (*A hold is remembered*, in place, line-neutral),
      `manifest.md` (the tree comment), `docs/client/glossary.md` (**genus** without the `savedata/`
      clause). Acceptance criteria 1–7.
      *Its suite* (`addons/149-holds-in-the-store.1/`, `:t149`, three runs, the phase in its own
      document `run`): **run 1** — `pag = s:menugrid():add("held")`, `s:actionbar():get(143):hold(pag)`
      and `get(144):hold(pag)`; asserts `get(143):hold() == pag`, `get(144):hold() == pag`, `get(143):res()`
      is `"addon/149-holds-in-the-store.1/held"`; asserts `hafen.store():query("SELECT * FROM hafen_holds")`
      is refused naming `hafen_holds` (the client's table, and the text now names it); asserts
      `hafen.store():table("hafen_holds")` is refused naming `hafen_`; sets `run.phase = 1`, `:flush()`.
      **run 2** (phase 1, after a `:reload`) — `add("held")` **before any hold**; asserts both slots hold
      `pag` again — the proof the rows were read back from the file, since the reload rebuilt the addon and
      the object it holds is new; `get(144):hold(nil)`; asserts `get(144):hold() == nil`; phase 2.
      **run 3** (phase 2, after a `:reload`) — after `add("held")`, asserts 143 is held and 144 is not (the
      forgotten slot stayed forgotten across the file); asserts `s:store():flush()` answers the store with
      a hold on record (the write path the criterion names); cleanup: `get(143):hold(nil)`,
      `s:menugrid():remove(pag)`, `run.phase = nil`, `:flush()`. Every run ≤ 15 lines, one `[summary]`.
      `[manual]` (runs 1 and 2): `:reload, then :t149 again -- expect: the next run's [pass] lines`.
      `[manual]` (run 3): `ls bin/savedata/ (delete <world>_<char>/ before run 1 if it is there) --
      expect: no <world>_<char>/ folder was recreated`.
      <!-- extra context: specs/147-undeclared-documents/addons/147-undeclared-documents.1/main.lua —
           the phase-across-:reload pattern; jshell on build/classes for the Db round trip before the
           maintainer restarts (memory: jshell headless widget pre-check) -->

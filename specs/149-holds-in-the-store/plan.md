# 149 — plan

## Approach

The record moves, its meaning does not. `BeltHold`'s holds half — `hold`, `release`, `writeLanded`,
`serverWrote`, `dropped`, `entryRemoved`, `teardownHolds`, `entryAdded` — is untouched; its
**placements half** is rewritten to read and write `hafen_holds` in each addon's own file, through the
same `SqliteApi.Db` that already carries `hafen_placements`, and the JSON path is deleted.

**The table.** In `SqliteApi.Db`'s constructor, beside the two `CREATE TABLE IF NOT EXISTS`, and
`SCHEMA` 1 → 2 (a `1` file gets the table and its `user_version` raised; a file from a newer client is
refused as today):

```sql
CREATE TABLE IF NOT EXISTS hafen_holds (scope TEXT NOT NULL, slot INTEGER NOT NULL,
  entry TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (scope, slot)) WITHOUT ROWID
```

`scope` is the character key (never `""`: a hold is always a character's); `slot` the 0-based index
`beltPlaced` keys on; `entry` the identity exactly as `beltPlaced` holds it, `AddonPagina.PREFIX + id +
"/" + rel`; `at` the `System.currentTimeMillis()` the placement was made. An addon's file holds only rows
whose `entry` is its own. `Db` gains `holds(scope)` (SELECT, ordered by slot), `holds(scope, rows)`
(DELETE the scope + INSERT, one `transaction(Work)` — the shape of `placements(scope, rows)`) and
`clearHolds()` (DELETE all: every character's, for the persisted disable).

**Memory** (`AddonManager.SessionState`): `beltPlaced` becomes `Map<Integer, BeltHold.Placed>`,
`Placed { final String id; final long at; }`, minted by `place()` only when the id changes so an unchanged
placement keeps its `at`. `beltLastJson` becomes `beltLast: Map<String, String>` — addon **id** → that
addon's slice as last read or written (keyed by id, not `Addon`, so a `:reload`'s new objects keep it
valid). New: `beltScope` — the key the map is filed under, **held, not looked up**, as `CharStore.scope`
is: a write goes back to the key the rows were read for, never to whatever `charScope` is now; and
`beltReadOnly: Set<String>` — ids whose rows could not be read.

**The slice.** One addon's rows out of `beltPlaced` are the entries with its prefix, serialised over
the `TreeMap` as `slot=entry@at;…` — the write-skip compare; no `Json` in `BeltHold` any more.

**`restore(st)`** (the tick, at enterWorld, right after `StoreApi.enterWorld`): `flush(st)` first, so the
outgoing character's dirt lands under the old `beltScope`; then, **outside the monitor**, read every
loaded addon's `db.holds(st.charScope)` — a `null` db or a `Failure` puts the id in `beltReadOnly` with
one log line naming the file, as `loadPlacements` does; then under the monitor clear the maps, set
`beltScope`, merge **newest `at` wins** per slot, prime `beltLast[id]` with each file's own rows, and mark
`beltDirty` when a row lost a tie so the next flush corrects that file.

**`flush(st)`** (the tick): under the monitor, if dirty, build every loaded addon's slice under
`beltScope`; outside it, for each whose slice differs from `beltLast[id]` and is not read-only, `db =
SqliteApi.db(a)`, skip when `null` (closed — the teardown's write came first), else `db.holds(scope,
rows)` and update `beltLast`. **`write(a)` / `write(a, st)`** are the same write for one addon over
every live state / one state, called from `StoreApi.flush(a)` (teardown Step "saved variables" and
`AddonRegistry.flushAll` at quit — both before `SqliteApi.close(a)`), `StoreApi.save(a, st)` (the
30-second autosave) and `s:store():flush()`, each beside its `writePlacements`.

**`addonDisabled(id, a)`**: the memory part under the monitor as today; then, outside it,
`SqliteApi.db(a).clearHolds()` when `a` is loaded and open. `AddonRegistry.setEnabled` passes
`findLoaded(id)` (package-private now).

**Lock order, written in `BeltHold`'s class comment**: never touch a `Db` while holding `BeltHold`'s
monitor — `Db.bracket` holds the connection's monitor across the Lua `fn` of `:transaction`, and that Lua
may call `slot:hold(pag)`.

**Deleted**: `BeltHold.FILE`, `json(st)`, the JSON imports; `StoreApi.readClientFile`, `writeClientFile`,
`clientDir`, `readFile`, `writeFile`, `tmpseq` and their imports; the `Inside.inside(saveDir(), key)`
check in `scopeKey` (its folder is gone — `sanitize` stays, the key's spelling is what the rows are
under); the `saveDir()` comment's "and the layer's own". The two refusal texts that list the client's
tables (`Decl` name check ~423, `Scan` ~1399) name `hafen_holds`.

## Files to create/modify

- `src/io/brodgar/addon/SqliteApi.java` — `SCHEMA`, the constructor's DDL, `Db.holds` ×2, `Db.clearHolds`,
  the two refusal texts, the class comment's "documents and remembered placements".
- `src/io/brodgar/addon/BeltHold.java` — `Placed`, the placements half, the header paragraphs
  (`writeClientFile`, threading), the lock-order rule.
- `src/io/brodgar/addon/AddonManager.java` — the four `SessionState` fields and their comments.
- `src/io/brodgar/addon/StoreApi.java` — the deletions; `BeltHold.write` in `flush(a)`, `save(a, st)`,
  `s:store():flush()`; `scopeKey` and its comment.
- `src/io/brodgar/addon/AddonRegistry.java` — `setEnabled` → `addonDisabled(id, findLoaded(id))`.
- `docs/addons/api/store/README.md`, `statements.md`, `documents.md`; `docs/addons/api/actionbar.md`
  (line-neutral); `docs/addons/manifest.md`; `docs/client/glossary.md`.
- `addons/149-holds-in-the-store.1/` — the suite.

## Risks & gotchas

- **`serverWrote` runs on the message thread** under `synchronized(ui)` and marks the map dirty; nothing
  on that thread may wait on disk — the outside-the-monitor rule covers both this and the lock order.
- **`Addon` objects are replaced by every `:reload`**: `beltLast` keyed on them would leak and miss;
  `a.manifest.id` is the key.
- **A hold before the HUD is refused** (`hold`: "no action bar yet"), so a `null` `beltScope` in `flush`
  is a no-op with nothing behind it.
- **A row's `slot` is 0-based**, as `beltPlaced` keys it and `GameUI.belt` counts; `LuaSlot` adds the 1.
- **`sanitize` stays exactly as it is**: every existing row is under the key it spells.
- **`docs/addons/api/actionbar.md` is at 308 lines**: the one edit replaces words inside an existing
  sentence and adds no line. `wc -l` before and after must agree.
- **`docs/client/glossary.md`**: the **genus** entry keeps upstream's own use (`GameUI.mapfilename()`)
  and drops the `savedata/` clause — nothing about `io.brodgar` goes there.
- **The suite cannot read `hafen_holds`** — the scan refuses the name in a statement, which is itself one
  of its checks — so its proof of persistence is the round trip through `:reload`, in three runs with
  the phase kept in the suite's own document (the 147.1 pattern).

## Discarded alternatives

- **A client-owned file** (`savedata/hafen.sqlite`, one `holds` table) — a new file with a lifecycle and
  a schema of its own, for a record keyed by an addon's entry: it belongs with that addon's file, where
  its placements already are, and it vanishes with that folder.
- **A document under a reserved name** (`hafen_documents`, name `"hafen:holds"`) — the addon would see it
  in `:list()`, could `:get` it and overwrite it, and a document is a Lua table refilled in the addon's
  own environment; the record is the client's, and the `hafen_` prefix exists to keep the two apart.
- **No `at` column, first file wins** — two files can claim one slot when one went stale (a read-only
  store, an uninstalled addon), and the page promises "the last one written"; load order is not a rule a
  reader can predict, a timestamp is.
- **Keeping the folder check on the character key** — the key was also a folder name, and that folder is
  gone; a check whose reason has left is a vestige, and `sanitize` alone keeps the spelling the rows
  are under.
- **A Lua read of the record** (`slot:placed()`) — the page says the addon stores nothing and asks
  nothing back; a read is a verb of `hafen.actionbar` and a feature of its own.
- **No tick write, only the timer and the teardown** — a crash between two timer ticks would lose the
  drag the player just made; the tick write costs one string compare when nothing changed.

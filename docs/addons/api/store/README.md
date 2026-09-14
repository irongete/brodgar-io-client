# hafen.store: your addon's file

Everything your addon keeps between sessions lives in **one file**, `savedata/<id>/<id>.sqlite`: an SQLite
database named by your addon's id, in a folder of its own under `savedata/`, read and written by every
account this client logs in with and every character they play. `hafen.store()` is that file. Every verb on this page and on the three under it is
**unprotected**: nothing here reaches the server, and the file it writes is your addon's own.

```lua
local seen = hafen.store():get("seen")                -- a document: a live table, saved for you
seen.launches = (seen.launches or 0) + 1

local trees = hafen.store():table("trees")            -- a table you declare: typed rows in and out
  :column("id", "integer"):column("kind", "text"):key("id"):create()
trees:put{ id = 1, kind = "fir" }

for _, r in ipairs(hafen.store():query("SELECT kind, count(*) AS n FROM trees GROUP BY kind")) do
  hafen.log():write(r.kind .. ": " .. r.n)            -- a statement: SQL, for what only SQL says
end
```

## The three shapes

| Shape | What it is | Reach for it when | Page |
|---|---|---|---|
| a **document** | a live Lua table you name at `get` and assign into, written for you | settings: a handful of values you read at load and change now and then | [documents](documents.md) |
| a **table** | columns, a key and indexes you declare; rows go in and come out typed | a record: many rows of one shape, looked up by key or by clause | [tables](tables.md) |
| a **statement** | one SQL statement, with a value bound to each `?` | what only SQL says: an aggregate, a join, a bulk write | [statements](statements.md) |

**Which is which.** A document is whole: it is held in memory as one table and written as one row, so it
is right for what you would keep in a settings table and wrong for what grows — ten thousand map nodes in
a document are ten thousand entries serialised at every save. A table is rows: each `:put` is one row in
the file, each `:list` reads what its clause keeps, and nothing else moves. A statement is the hatch under
both: `count(*)`, `GROUP BY`, a `JOIN` across two of your tables, an `UPDATE` of one column, a `DELETE`
by clause.

**A character is a column, or a row key, never a file.** The file is the client's, so where the rows are
one character's you say so: a document reached through a character's [session](../session.md) is a row
keyed by that character; a table's rows are one character's where you declare a column for it, and
everyone's where you do not. The whole picture — every node any of your characters ever saw — is then one
`SELECT` away, because it is one file.

## The section

`hafen.store()` is the door to the file, and its verbs are the file's: the addon's own, whichever account
or character is up. A character's documents are the one thing reached elsewhere, through
[`s:store()`](documents.md#read-and-write).

| Verb | Answers | Page |
|---|---|---|
| `hafen.store():get(name)` | the live table of your addon's own document `name`, empty until something is saved under it | [documents](documents.md) |
| `hafen.store():list()` | the names that exist in your addon's own scope, sorted, a string array | [documents](documents.md) |
| `hafen.store():flush()` | the store, once the client scope's documents and placements are written | [documents](documents.md) |
| `hafen.store():info()` | `{file, bytes}` | [below](#the-file) |
| `hafen.store():table(name)` | a bare declaration of one of your own tables | [tables](tables.md) |
| `hafen.store():exec(sql, ...)` | how many rows one statement changed | [statements](statements.md) |
| `hafen.store():query(sql, ...)` | the rows one statement answers, keyed by column | [statements](statements.md) |
| `hafen.store():transaction(fn, ...)` | what `fn` answers, everything it ran committed as one | [statements](statements.md) |
| `hafen.store():vacuum()` | the store, once the file is rebuilt in place | [below](#the-file) |

## The file

### `hafen.store():info()`

`{file, bytes}`: `file` is the path of `savedata/<id>/<id>.sqlite`, `bytes` the size of the database — its
pages, which count what is committed to the log as well as what is in the file, and the free pages a
`:vacuum()` would give back. The one snapshot the store hands out; everything else here is live.
Unprotected. Raises when the file is [unavailable](#when-the-file-cannot-be-opened).

### `hafen.store():vacuum()`

Rebuilds the file, giving back the pages its deleted rows held, and answers the store. Unprotected. Refused
inside a [`:transaction`](statements.md#the-transaction), naming it, and when the file is
unavailable. Under no time limit: the file is what sizes it, and stopping it half-way is the one outcome
nobody meant.

**One file per addon, named by the id, in a folder named by the id.** There is no path of your choosing,
and no file per account or per character: a file per login is one schema in as many files as you have
logins, which no query reads together — a record that forgets every node when you log in as your other
account. The folder and the file are created the first time your addon loads, whether or not it ever
names a document, because a remembered window and a table need it as much as a document does. Two addons
never share one: each reads and writes its own, and nothing of another's is reachable from it.

**Beside it, in the same folder, stand two sidecars while it is open**: `<id>.sqlite-wal`, the log every
write lands in first, and `<id>.sqlite-shm`, its index. They are part of the database — a copy of the
`.sqlite` alone, taken while the client runs, lacks whatever the log holds. Closing the file folds the log
into it and removes both, so the folder holds the file whole and alone once the client has quit or your
addon has been disabled. Copy or move the folder then.

## When it is written, and when it is closed

| What | Is in the file |
|---|---|
| a row a [table](tables.md) or a [statement](statements.md) writes | when the call returns — or, inside a `:transaction`, when `fn` returns |
| a [document](documents.md) | on the timer, every thirty seconds; when your addon is disabled or reloaded; when the client quits; on `:flush()`. A character's, also when the session holding it ends or picks another character |
| a [remembered placement](documents.md#where-a-widget-sits-is-saved-for-you) | with the documents of the scope its widget stands in, and when the screen changes |
| a [held slot](../actionbar.md#a-hold-is-remembered) | never: it is a row of the client's own file, not of yours, written on the tick the hold is taken or ended by hand — no `:flush()` writes it |

The file is **closed** when your addon is disabled or reloaded and when the client quits, after its
documents are written. A crash or a kill closes nothing: every row a call committed is in the log and is
folded in at the next open, while a document's changes since its last write are gone. A power cut can
lose the last committed rows as well; it cannot lose the file.

## The sandbox, in three facts

1. **The connection holds one file, yours.** No second database can be attached to it, so `ATTACH`,
   `DETACH` and `VACUUM INTO` are refused: every addon's data is its own file, and none reads another's.
2. **No extension loads.** `load_extension` is switched off, so the functions a statement has are SQLite's
   own.
3. **The `hafen_` tables are the client's.** `hafen_documents` holds your documents and `hafen_placements`
   your remembered placements, reached through `:get(name)` and `w:remember(name)` and never through a
   statement. The action-bar slots [held](../actionbar.md#a-hold-is-remembered) for your entries are not in
   your file at all: they are the client's own rows. A table of yours takes any other prefix; `sqlite_` is
   SQLite's.

A `CREATE TABLE` or `CREATE INDEX` through `:exec` is refused too, naming the [builder](tables.md): a table
made there is one whose rows come back typed. A virtual table — `CREATE VIRTUAL TABLE … USING fts5` — has
no columns to type, and runs through `:exec`.

## The two caps

| Cap | Default | Launch property | Past it |
|---|---|---|---|
| how long one statement may run | 5000 ms | `-Dhaven.addon.sqlite.timeout` | the statement is stopped and the call raises naming the timeout; the file is as it was before it |
| how many rows one call reads | 50000 | `-Dhaven.addon.sqlite.maxrows` | `:query`, `:list` and `:find` raise naming `LIMIT` |

One call into the store costs the [watchdog](../../runtime.md#budgets-and-the-watchdog) one instruction
whatever the statement does, so these two are what stands between a statement and the frame. The deadline
counts the statement alone: the Lua between two statements of a transaction is not under it. The fix for
either is the same — an index over what the `WHERE` reads, a tighter clause, a `LIMIT` paged with `OFFSET`
or over the key — and `count(*)` is how many without reading them.

## When the file cannot be opened

An open that fails — a folder that cannot be written, a file that is not a database, a lock another
process holds, a runtime without the driver — leaves the store **unavailable** and your addon loaded: one
log line names the file and the cause, your documents are empty and are never written, and every other
verb here refuses naming the cause. Fix it and `:reload`.

> **A scope whose row could not be read is read-only for the session.** A document's row that is there and
> will not parse leaves that scope's tables empty because the client could not read them, not because you
> saved nothing — and a write would replace the only copy of your data with that empty set. So nothing is
> written back to that scope: the timer, `:flush()` and the teardown skip it, and the log names the row. A
> load that succeeds lifts it, which is a `:reload` or the next launch once the row is readable. A row that
> is simply **not there yet** is not this case: an empty document is the whole truth, and the first write
> creates it.

## See also

- [documents](documents.md) — the two doors as the two scopes, what survives, when it is written, the placements
- [tables](tables.md) — the builder, the Table, the types both ways, and how a declaration evolves
- [statements](statements.md) — `:exec`, `:query`, binding, what is refused, and `:transaction`
- [saved data](../../guides/saved-data.md) — the guide: which shape, and when to read each
- [the manifest](../../manifest.md) — the id that names the file, and the fields beside it

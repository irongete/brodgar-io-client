# hafen.store: Your Addon's File

Everything your addon stores between sessions lives in one SQLite file, `savedata/<id>/<id>.sqlite`. Every account this client logs in with, and every character they play, reads and writes it. `hafen.store()` is that file. Every verb here and on the pages under it is unprotected: nothing reaches the server.

```lua
local seen = hafen.store():var("seen")                -- a var: a live table, saved for you
seen.launches = (seen.launches or 0) + 1

local trees = hafen.store():table("trees")            -- a table you declare: typed rows in and out
  :column("id", "integer"):column("kind", "text"):key("id"):create()
trees:put{ id = 1, kind = "fir" }

for _, row in ipairs(hafen.store():query("SELECT kind, count(*) AS n FROM trees GROUP BY kind")) do
  hafen.log():write(row.kind .. ": " .. row.n)        -- a statement: SQL, for what only SQL says
end
```

---

## The shapes

| Shape | What it is | Use it for | Page |
|---|---|---|---|
| A var | A live Lua table you name at `var` and assign into, written for you as one row. | Settings: a handful of values read at load and changed now and then. Wrong for what grows: ten thousand nodes in a var are ten thousand entries serialised at every save. | [Vars](vars.md) |
| A table | Columns, a key and indexes you declare. Rows go in and come out typed. Each `:put` is one row, each `:list` reads what its clause keeps. | A record: many rows of one shape, looked up by key or by clause. | [Tables](tables.md) |
| A statement | One SQL statement, a value bound to each `?`. | What only SQL says: `count(*)`, `GROUP BY`, a `JOIN` across your tables, an `UPDATE` of one column, a `DELETE` by clause. | [Statements](statements.md) |

A character is a column or a row key, never a file. A var reached through a character's [session](../session.md) is a row keyed by that character. A table's rows are one character's where you declare a column for it. It is one file, so one `SELECT` reads across all of it.

## The section

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.store():var(name)` | live table | Unprotected | Your addon's own var `name`, empty until something is saved under it ([vars](vars.md)). |
| `hafen.store():list()` | `string[]` | Unprotected | The names that exist in your addon's own scope, sorted ([vars](vars.md)). |
| `hafen.store():flush()` | the store | Unprotected | Once your addon's own changed vars are written ([vars](vars.md)). |
| `hafen.store():info()` | `{file, bytes}` | Unprotected | [Below](#the-file). |
| `hafen.store():table(name)` | declaration | Unprotected | A bare declaration of one of your own tables ([tables](tables.md)). |
| `hafen.store():exec(sql, ...)` | `number` | Unprotected | How many rows one statement changed ([statements](statements.md)). |
| `hafen.store():query(sql, ...)` | `table[]` | Unprotected | The rows one statement answers, keyed by column ([statements](statements.md)). |
| `hafen.store():transaction(fn, ...)` | what `fn` answers | Unprotected | Everything `fn` ran, committed as one ([statements](statements.md)). |
| `hafen.store():vacuum()` | the store | Unprotected | Once the file is rebuilt in place ([below](#the-file)). |

A character's vars are reached through [`session:store()`](vars.md#read-and-write).

## The file

| Method | Detail |
|---|---|
| `hafen.store():info()` | `file` is the path of `savedata/<id>/<id>.sqlite`. `bytes` is the size of the database, its pages. That counts what is committed to the log as well as what is in the file, and the free pages a `:vacuum()` would give back. The one snapshot the store hands out. Raises when the file is [unavailable](#when-the-file-cannot-be-opened). |
| `hafen.store():vacuum()` | Rebuilds the file, giving back the pages deleted rows held. Refused inside a [`:transaction`](statements.md#the-transaction), naming it, and when the file is unavailable. Under no time limit. |

| Rule | Detail |
|---|---|
| One file per addon | Named by the id, in a folder named by the id. No path of your choosing, no file per account or character. A file per login would be one schema in as many files as logins, which no query reads together. Two addons never share one. |
| Made by the first verb that needs it | The file and its folder are made the first time you call a verb of the store, and never before: a `:var` in `Load` makes them in `Load`, so a var is readable there and a table is there to declare. An addon that stores nothing leaves nothing under `savedata/`, and being enabled is not storing anything. |
| The client's own file | Where the user put your windows and which action-bar slots hold your entries are in `savedata/client.sqlite`, never in yours. |
| Two sidecars while open | `<id>.sqlite-wal`, the log every write lands in first, and `<id>.sqlite-shm`, its index. A copy of the `.sqlite` alone taken while the client runs lacks what the log holds. Closing the file folds the log in and removes both: copy or move the folder once the client has quit or your addon is disabled. |

## When it is written, and when it is closed

| What | Is in the file |
|---|---|
| A row a [table](tables.md) or a [statement](statements.md) writes | When the call returns. Inside a `:transaction`, when `fn` returns. |
| A [var](vars.md) | On the timer, every thirty seconds. When your addon is disabled or reloaded. When the client quits. On `:flush()`. A character's, also when the session holding it ends or picks another character. |
| A [remembered placement](vars.md#where-a-widget-sits-is-saved-for-you) | Never in yours: a row of the client's file, written when the gesture lands, when the screen changes and when the widget goes. No `:flush()` writes it. |
| A [held slot](../actionbar.md#a-hold-is-remembered) | Never in yours: a row of the client's file, written on the tick the hold is taken or ended by hand. |

The file is closed when your addon is disabled or reloaded and when the client quits, after its vars are written. A crash or a kill closes nothing: every committed row is in the log and folded in at the next open. A var's changes since its last write are gone. A power cut can lose the last committed rows. It cannot lose the file.

## The sandbox

| Rule | Detail |
|---|---|
| One file, yours | `ATTACH`, `DETACH` and `VACUUM INTO` are refused. |
| No extension loads | `load_extension` is off. The functions a statement has are SQLite's own. |
| The `hafen_` prefix is the client's | `hafen_vars` holds your vars, reached through `:var(name)` and never through a statement: a statement or declaration naming a `hafen_` table is refused naming it. Any other prefix is yours. `sqlite_` is SQLite's. |
| `CREATE TABLE`, `CREATE INDEX` through `:exec` | Refused naming the [builder](tables.md), whose rows come back typed. A virtual table (`CREATE VIRTUAL TABLE … USING fts5`) has no columns to type and runs through `:exec`. |

## The caps

| Cap | Default | Launch property | Past it |
|---|---|---|---|
| How long one statement may run | 5000 ms | `-Dhaven.addon.sqlite.timeout` | The statement is stopped and the call raises naming the timeout. The file is as it was. |
| How many rows one call reads | 50000 | `-Dhaven.addon.sqlite.maxrows` | `:query`, `:list` and `:find` raise naming `LIMIT`. |

One call into the store costs the [watchdog](../../runtime.md#budgets-and-the-watchdog) one instruction whatever the statement does, so these two stand between a statement and the frame. The deadline counts the statement alone, not the Lua between two statements of a transaction. The fix for either: an index over what the `WHERE` reads, a tighter clause, a `LIMIT` paged with `OFFSET` or over the key. `count(*)` is how many without reading them.

## When the file cannot be opened

The file is opened by the first verb that needs it, so that verb is where an open that fails is told, and it leaves the store unavailable and your addon running. It fails on a folder that cannot be written, or a file that is not a database. It fails on a lock another process holds, or a runtime without the driver. One log line names the file and the cause. Your vars are empty and never written. Every other verb refuses naming the cause. Fix it and `:reload`.

> **A scope whose row could not be read is read-only for the session.** A var's row that is there and will not parse leaves that scope's tables empty. A write would replace the only copy of your data with that empty set, so the timer, `:flush()` and the teardown skip it. The log names the row. A load that succeeds lifts it (a `:reload` or the next launch once the row is readable). A row not there yet is not this case: an empty var is the whole truth, and the first write creates it.

---

## See Also

- [Vars](vars.md) — the scopes, what survives, when it is written, and where a widget sits.
- [Tables](tables.md) — the builder, the Table, the types both ways, and how a declaration evolves.
- [Statements](statements.md) — `:exec`, `:query`, binding, what is refused, and `:transaction`.
- [Saved data](../../guides/saved-data.md) — the guide: which shape, and when to read each.
- [The manifest](../../manifest.md) — the id that names the file, and the fields beside it.

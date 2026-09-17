# hafen.store: Statements

One SQL statement run on your [file](README.md), with one value bound to each `?`. It is for what only SQL says (an aggregate, a join across your tables, a bulk `UPDATE` or `DELETE`), where a [table](tables.md)'s own verbs stop. Unprotected.

```lua
local store = hafen.store()
local prices = store:table("prices"):column("item", "text"):column("price", "integer")
  :key("item"):create()
prices:put{ item = "flax", price = 12 }

store:exec("UPDATE prices SET price = price + ? WHERE item = ?", 3, "flax")
for _, row in ipairs(store:query("SELECT item, price FROM prices WHERE price > ?", 10)) do
  hafen.log():write(row.item .. " costs " .. row.price)    -- flax costs 15
end
```

---

## The verbs

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.store():exec(sql, ...)` | `number` | Unprotected | How many rows the statement changed: what it inserted, updated or deleted, triggers and cascades included. `0` for one that changes no row (`DROP TABLE`, a `PRAGMA`). |
| `hafen.store():query(sql, ...)` | `table[]` | Unprotected | The rows the statement answers, a plain array of tables keyed by column. |

| Rule | Detail |
|---|---|
| What a statement answers decides the verb | Known before it runs. One answering rows through `:exec` is refused naming `:query`. One answering none through `:query` is refused naming `:exec`. Nothing runs either way. A `SELECT`, a `PRAGMA` that answers and `INSERT … RETURNING` go through `:query`. `INSERT`, `UPDATE`, `DELETE`, `DROP`, `CREATE VIEW` through `:exec`. |
| Unavailable file | Both raise ([when the file cannot be opened](README.md#when-the-file-cannot-be-opened)). |
| A driver's refusal | A table that is not there, a constraint: the verb's error, in the driver's words. |
| `query` rows | Keyed by the column's label: the alias where given, the name otherwise. Two columns under one label keep the last (SQL's own answer), so a join reading `id` from both sides aliases one. `NULL` is an absent key. Read whole up to the [row cap](README.md#the-caps), past which the call raises naming `LIMIT`. `count(*)` is how many without reading them. |

## Binding

A `?` takes exactly the values below, and the count is held: two `?`s handed three values is refused naming both counts.

| You pass | It binds as |
|---|---|
| A string | `TEXT` |
| A whole number, within 2^53 | `INTEGER` |
| Any other number | `REAL` |
| `true`, `false` | `1`, `0` |
| `nil` | `NULL` |
| Anything else (a table, a function, a handle) | Refused, naming these. |

A `?` is the only place a value goes. Text spliced into the statement is SQL injection. It also defeats the one plan SQLite makes per statement text (`"WHERE kind = ?"` is compiled once and bound many times).

| The file holds | You get |
|---|---|
| An integer | A number, exact to 2^53. |
| A real | A number. |
| Text | A string. |
| A blob | A string of its bytes. |
| `NULL` | An absent key. |

A cell has no declaration to type it here: a [`boolean` column](tables.md#the-types) reads `1` and a `json` column its text. The Table's verbs are where the types are.

## One statement per call

A `;` with anything after it is refused: the driver would run the first statement and drop the rest silently. Run each in its own call, inside a [`:transaction`](#the-transaction) where they belong together. A trigger's body, `BEGIN …; …; END`, is one statement and passes.

## What is refused

SQLite holds the [sandbox](README.md#the-sandbox). The words below are refused before they reach it, naming what to write instead.

| Statement | Refused naming |
|---|---|
| `CREATE TABLE` | `hafen.store():table(name)` and its `:create()`, whose rows come back typed. A virtual table (`CREATE VIRTUAL TABLE`) has no columns to type and runs here. |
| `CREATE INDEX` | `declaration:index(column, ...)`, whose `:create()` makes it on a table already in the file too. |
| Any `hafen_` name | The client's own table, `hafen_vars`: your vars, reached through `:var(name)`. |
| `ATTACH`, `DETACH` | The sandbox: this connection holds one file, yours. |
| `load_extension(...)` | The sandbox: no extension loads. |
| `VACUUM` | [`hafen.store():vacuum()`](README.md#the-file), which rebuilds the file in place. `VACUUM INTO` would write a second file. |
| `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`, `RELEASE` | `:transaction(fn, ...)`: a bracket a statement opened would outlive the frame. |
| A `PRAGMA` writing `user_version`, `journal_mode`, `synchronous` or `foreign_keys` | The client's own cells, set as the file is opened. A `PRAGMA` that reads answers through `:query`. |
| An empty string | One statement, with an example. |

Everything else SQLite runs, runs: `CREATE VIEW`, `CREATE TRIGGER`, `WITH RECURSIVE`, `PRAGMA`, `EXPLAIN`, the `json_*` functions, `RETURNING`. A [Table's clause](tables.md#the-clause) is read the same way, naming the Table verb.

## The transaction

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.store():transaction(fn, ...)` | what `fn` answers | Unprotected | Run `fn(...)` inside one transaction. Everything it ran (a Table's puts, a statement's writes) is committed when `fn` returns and rolled back when it raises. The error comes out of the call as `fn` raised it. |

```lua
local written = store:transaction(function(rows)
  for _, row in ipairs(rows) do prices:put(row) end            -- one commit for all of them, or none
  return #rows
end, { { item = "hemp", price = 9 }, { item = "wool", price = 20 } })
```

| Rule | Detail |
|---|---|
| One write to the log | Ten thousand puts inside one bracket are one write. Outside it, ten thousand. |
| Does not nest | A second `:transaction` inside `fn` is refused naming this one. `:vacuum()` inside `fn` is refused likewise. `fn` that is not a function is refused naming it. Raises when the file is unavailable. |
| A statement the deadline stops ends the bracket | SQLite rolls the whole transaction back and the bracket refuses at `fn`'s return naming the timeout, even where `fn` caught the error and carried on. Let that error out of `fn`, or keep the statement under the [timeout](README.md#the-caps). |

## Threading

| Rule | Detail |
|---|---|
| Synchronous | Every verb runs on the calling thread and answers before it returns. Nothing here schedules anything. |
| Serialised | Your addon is entered by [one thread at a time](../threading.md), and the store serialises the file under that. |
| A transaction holds the file for the whole of `fn` | A verb arriving from another thread (an inbound update's handler, a `Draw` pass) waits at the door until `fn` returns. `fn` is where the writes go and nothing else: no HTTP wait, no work the frame waits on. |
| No flush | A row is on disk when the call returns. A [var](vars.md) is the shape that waits for its write. |

---

## See Also

- [The file](README.md) — the sandbox, the caps, and when the file is closed.
- [Tables](tables.md) — the builder every `CREATE TABLE` points at, and the rows that come back typed.
- [Vars](vars.md) — the shape for settings, held in memory and written for you.
- [Threading](../threading.md) — where your handler runs and what it may reach.

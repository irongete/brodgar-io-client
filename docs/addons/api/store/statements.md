# hafen.store: statements

A **statement** is one SQL statement run on your [file](README.md), with one value bound to each `?`. Reach
for it for what only SQL says — an aggregate, a join across two of your tables, a bulk `UPDATE` or `DELETE`
— where a [table](tables.md)'s own verbs stop. Every verb here is **unprotected**.

```lua
local store = hafen.store()
local prices = store:table("prices"):column("item", "text"):column("price", "integer")
  :key("item"):create()
prices:put{ item = "flax", price = 12 }

store:exec("UPDATE prices SET price = price + ? WHERE item = ?", 3, "flax")
for _, r in ipairs(store:query("SELECT item, price FROM prices WHERE price > ?", 10)) do
  hafen.log():write(r.item .. " costs " .. r.price)    -- flax costs 15
end
```

## The two verbs

| Verb | Answers | Permission |
|---|---|---|
| `hafen.store():exec(sql, ...)` | how many rows the statement changed | unprotected |
| `hafen.store():query(sql, ...)` | the rows the statement answers, a plain array of tables keyed by column | unprotected |

**What a statement answers decides the verb**, and the client knows it before the statement runs: one that
answers rows through `:exec` is refused naming `:query`, and one that answers none through `:query` is
refused naming `:exec`, with nothing run either way. So a `SELECT`, a `PRAGMA` that answers, and an
`INSERT … RETURNING` go through `:query`; an `INSERT`, `UPDATE`, `DELETE`, `DROP` or `CREATE VIEW` through
`:exec`. Both raise when the file is [unavailable](README.md#when-the-file-cannot-be-opened).

### `hafen.store():exec(sql, ...)`

The count is **this statement's own**: what it inserted, updated or deleted, its triggers and cascades
included, and `0` for one that changes no row — a `DROP TABLE`, a `PRAGMA`. A driver's refusal — a table
that is not there, a constraint — comes out as the verb's error, in the driver's words.

### `hafen.store():query(sql, ...)`

Each row is keyed by the column's **label**: the alias where the statement gave one, the column's name
where it did not. Two columns under one label keep the last, which is SQL's own answer, so a join that
reads `id` from both sides aliases one. A `NULL` is an absent key. The rows are read whole, up to the
[row cap](README.md#the-two-caps), past which the call raises naming `LIMIT`; `count(*)` is how many
without reading them.

## Binding

A `?` takes exactly the values below, and the count is held: a statement with two `?`s handed three values is
refused naming both counts.

| You pass | It binds as |
|---|---|
| a string | `TEXT` |
| a whole number, within 2^53 | `INTEGER` |
| any other number | `REAL` |
| `true`, `false` | `1`, `0` |
| `nil` | `NULL` |
| anything else — a table, a function, a handle | refused, naming these |

A `?` is the only place a value goes. Text spliced into the statement is a value the engine reads as SQL,
which is the injection every database has a name for, and it also defeats the one plan the engine makes for
one statement text: `"WHERE kind = ?"` is compiled once and bound many times.

**What a cell reads back as** has no declaration to type it, so it is what the file holds:

| The file holds | You get |
|---|---|
| an integer | a number, exact to 2^53 |
| a real | a number |
| text | a string |
| a blob | a string of its bytes |
| `NULL` | an absent key |

So a [`boolean` column](tables.md#the-types) reads `1` here and `true` through its Table, and a `json`
column reads its text; the Table's verbs are where the types are.

## One statement per call

A `;` that has anything after it is refused: the driver runs the first statement and drops the rest with
nothing said, and a silent drop is the one thing a refusal exists to replace. Run each in a call of its
own — inside a [`:transaction`](#the-transaction) where they belong together. A trigger's body, `BEGIN …;
…; END`, is one statement and passes.

## What is refused

The engine holds the [sandbox](README.md#the-sandbox-in-three-facts); the words below are refused before
they reach it, so the message names what to write instead.

| The statement | Is refused naming |
|---|---|
| `CREATE TABLE` | `hafen.store():table(name)` and its `:create()` — a table made through the builder is one whose rows come back typed. A virtual table (`CREATE VIRTUAL TABLE`) has no columns to type and runs here |
| `CREATE INDEX` | `decl:index(col, ...)` on the declaration, whose `:create()` makes it on a table already in the file too |
| any `hafen_` name | the client's own table, `hafen_vars` — your vars, reached through `:var(name)` and never through a statement |
| `ATTACH`, `DETACH` | the sandbox: this connection holds one file, yours |
| `load_extension(...)` | the sandbox: no extension loads on this connection |
| `VACUUM` | [`hafen.store():vacuum()`](README.md#hafenstorevacuum), which rebuilds the file in place; `VACUUM INTO` would write a second file |
| `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`, `RELEASE` | `:transaction(fn, ...)` below — a bracket a statement opened would outlive the frame |
| a `PRAGMA` that writes `user_version`, `journal_mode`, `synchronous` or `foreign_keys` | the client's own cells, set as the file is opened — a write would undo the open, or leave a file no client opens again. A `PRAGMA` that reads answers through `:query` |
| an empty string | one statement, with an example |

Everything else SQLite runs, runs: `CREATE VIEW`, `CREATE TRIGGER`, `WITH RECURSIVE`, `PRAGMA`, `EXPLAIN`, the
`json_*` functions, `RETURNING`. A [Table's clause](tables.md#the-clause) is read the same way, naming the
Table verb: a second statement, a `hafen_` name and `load_extension` are refused there too.

## The transaction

### `hafen.store():transaction(fn, ...)`

Runs `fn(...)` inside one transaction and answers what `fn` answers. Everything `fn` ran — a Table's puts, a
statement's writes — is **committed when `fn` returns** and **rolled back when it raises**, the error out of
the call as `fn` raised it. Unprotected; raises when the file is unavailable.

```lua
local n = store:transaction(function(rows)
  for _, r in ipairs(rows) do prices:put(r) end            -- one commit for all of them, or none
  return #rows
end, { { item = "hemp", price = 9 }, { item = "wool", price = 20 } })
```

Ten thousand puts inside one bracket are one write to the log; the same ten thousand outside it are ten
thousand. It **does not nest**: a second `:transaction` inside `fn` is refused naming this one, since what it
would run is inside the open bracket already, and `:vacuum()` inside `fn` is refused for the same reason.
`fn` that is not a function is refused naming it.

**A statement the deadline stops ends the bracket.** The engine rolls the whole transaction back, and the
bracket refuses at `fn`'s return naming the timeout — even where `fn` caught the error and carried on,
because nothing `fn` ran after it can be kept. Let that error out of `fn`, or keep the statement under the
[timeout](README.md#the-two-caps).

## Threading

Every verb here runs on the thread that calls it and answers before it returns: a statement is synchronous,
and nothing here schedules anything. Your addon is entered by [one thread at a time](../threading.md), and
under that the store serialises the file too, so a call of yours never meets another half-way. A
`:transaction` **holds the file for the whole of `fn`**: a verb of yours arriving from another thread — an
inbound update's handler, a `Draw` pass — waits at the door until `fn` returns, so `fn` is where the writes
go and nothing else: no HTTP wait, no work the frame is waiting on.

A row is on disk when the call returns, so there is no flush to call after a statement; a
[var](vars.md) is the shape that waits for its write.

## See also

- [the file](README.md) — the sandbox, the two caps, and when the file is closed
- [tables](tables.md) — the builder every `CREATE TABLE` points at, and the rows that come back typed
- [vars](vars.md) — the shape for settings, held in memory and written for you
- [threading](../threading.md) — where your handler runs and what it may reach

# hafen.store: tables

A **table** you declare is your addon's record: columns, a key and indexes in your [file](README.md), whose
rows go in and come out **typed** by the declaration. Reach for it where a [document](documents.md) would
grow — map nodes, prices seen, a log of what happened — and look rows up by key or by clause. Every verb
here is **unprotected**.

```lua
local nodes = hafen.store():table("nodes")
  :column("grid", "text"):column("x", "integer"):column("y", "integer")
  :column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :key("grid", "x", "y")
  :index("kind")
  :create()

nodes:put{ grid = "g1", x = 1, y = 2, kind = "fir", seen = true, flags = { a = 1 } }
local row = nodes:get("g1", 1, 2)                 -- row.seen == true, row.flags.a == 1
for _, r in ipairs(nodes:list("WHERE kind = ? ORDER BY x", "fir")) do
  -- your code here
end
```

## Declare it

`hafen.store():table(name)` is a bare **declaration**. You configure it with the setters below and dispatch it
with `:create()`, the one call that touches the file, which answers the **Table**. Every setter is legal
until then and none after, and each is refused naming what is allowed.

| Verb | Does | Refused when |
|---|---|---|
| `hafen.store():table(name)` | a bare declaration of the table `name` | the name is not letters, digits and underscores starting with a letter or an underscore, or takes the `hafen_` or `sqlite_` prefix; the file is [unavailable](README.md#when-the-file-cannot-be-opened) |
| `decl:column(name, type)` | one column, of a [type](#the-types) below | the name breaks the rule above, the column is declared already, or the type is not one of the five |
| `decl:key(col, ...)` | the columns that identify a row, in the order `:get` and `:remove` take them | a key is declared already, a column is not declared, is named twice or is `json` |
| `decl:index(col, ...)` | one index over declared columns, `<table>_<cols>` in the file | a column is not declared or named twice; the same columns are indexed already |
| `decl:create()` | the dispatch: the table is in the file, and the interned Table is answered | there is no key; the table is in the file with another key |

A table has one key, and it is what `:put` upserts by: two rows with one key are one row. Any setter after
`:create()` is refused naming the Table it answered; another declaration comes from `hafen.store():table`.

### The types

| Word | In the file | Takes | Answers |
|---|---|---|---|
| `text` | `TEXT` | a string | a string |
| `integer` | `INTEGER` | a whole number, exact to 2^53 | a number |
| `real` | `REAL` | a number | a number |
| `boolean` | `INTEGER` | `true` or `false` | `true` or `false` |
| `json` | `TEXT` | a table a [document](documents.md#what-survives) can hold | the table that was put; a Position inside it comes back a Position |

**The Lua types live in your declaration alone.** The file holds an `INTEGER` where you said `boolean` and a
`TEXT` where you said `json`, and says nothing about which column is which — so the declaration is what
reads them back, and it is re-read every load because your code declares it every load. A row `:put` writes
is typed on the way in — a string in an `integer` column is refused naming the column and its type — and
typed on the way out. A column no declaration names any more comes back as the file holds it: the number, the
string, the `1` a boolean once was.

### Evolution

`:create()` over a table already in the file brings the file up to the declaration: a column the file lacks
is added, an index it lacks is made, and a column nobody declares any more is left alone — it is data, and a
row still carries it. What does not change in place is the **key**: a declaration whose key differs from the
file's is refused naming both, because the key is what identifies a row. Declare the key the file has, or a
table under another name.

```lua
-- the same declaration with one more column: the file gains it; an older row reads it as absent
local nodes = hafen.store():table("nodes"):column("grid", "text"):column("x", "integer")
  :column("y", "integer"):column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :column("quality", "integer"):key("grid", "x", "y"):create()
nodes:put{ grid = "g1", x = 1, y = 3, kind = "fir", quality = 31 }
```

## The Table

The object `:create()` answers, **interned per addon by name** — `"Nodes"` and `"nodes"` are one table in the
file and so one Table here — so two `:create()`s of one name are `==`, and the later declaration is the one
both read with. It is a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)
whose members are rows and whose filter is a **clause**: `#nodes`, `nodes[1]` and `ipairs(nodes)` are
refused naming `:list`, which is the array.

A **row** is a plain table keyed by column name, exactly as you would write it: `NULL` is an absent key, a
`boolean` column is `true` or `false`, a `json` column is its table. A column a clause joins in, or one the
declaration does not name, comes as the file holds it.

The Table's one [snapshot](../conventions.md#snapshots-vs-handles) is `nodes:info()`: the live declaration as
a plain table, `{name, columns, key, indexes}` — `columns` an array of `{name, type}` in declaration order,
`type` the word the declaration wrote (`boolean`, not the file's `INTEGER`), `key` and each entry of
`indexes` an array of column names. It is a copy, built on every call: assigning into it changes nothing,
and after a second `:create()` of the name it reads the later declaration.

## Read

| Verb | Answers |
|---|---|
| `nodes:get(k, ...)` | the row with that key, or `nil` |
| `nodes:list(clause, ...)` | every row the clause keeps, as a plain array — empty rather than `nil` |
| `nodes:count(clause, ...)` | how many rows the clause keeps, counted by the file |
| `nodes:find(clause, ...)` | the first row the clause keeps, or `nil` |
| `nodes:info()` | the declaration as a plain table, `{name, columns, key, indexes}` — a copy, every call |

`:get` takes the key's values in `:key` order, as many as the key has columns, none `nil`; a wrong count is
refused naming the key. `:list` and `:find` read at most the [row cap](README.md#the-two-caps), and raise
naming `LIMIT` past it; `:count` reads nothing.

### The clause

The clause is **SQL**: what follows `FROM <table>` — `"WHERE kind = ? ORDER BY x"`, `"LIMIT 10"`,
`"WHERE x > ? AND y > ?"` — with one value bound to each `?` after it,
[as a statement binds](statements.md#binding). Left out, it keeps everything. A function is refused naming
SQL: there is no predicate form, because a `GROUP BY` or the nearest twenty rows has no spelling in one, and
two spellings for one question is what this API does not have. An aggregate or a join is a [statement](statements.md).

```lua
local near = nodes:list("WHERE grid = ? AND abs(x - ?) <= 2 ORDER BY y", "g1", 1)
local firs = nodes:count("WHERE kind = ?", "fir")
```

## Write (unprotected)

| Verb | Answers |
|---|---|
| `nodes:put(row)` | the row as the file now holds it |
| `nodes:remove(k, ...)` | the Table, whether or not the row was there |

### `nodes:put(row)`

Upserts by key: a row whose key is in the file replaces that row **whole**, and a column the row leaves out
is `NULL` in the file — an absent key on the way back. Changing one column of a row you are not holding is
an `UPDATE` through [`:exec`](statements.md). Refused, naming the nearest thing, when a key of the row names
no column (naming the columns there are), a value is not of its column's type (naming the column and the
type), a key column is missing (naming the key), or a key of the row is not a string. What it answers is
the stored row read back through the declaration, so `put{ seen = true }` answers `seen == true`.

Names are compared two ways, and each is the rule of the side it faces: against the file a column name is
case-insensitive, because SQLite folds identifiers; against a row it is exact, because Lua tables are, so a
row keyed `Kind` does not name the column `kind`.

## See also

- [the file](README.md) — the two caps, the sandbox, and when a row is on disk
- [statements](statements.md) — the aggregate, the join and the bulk write a Table's verbs do not say
- [documents](documents.md) — the shape for settings, and what a `json` column may hold
- [conventions](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection grammar

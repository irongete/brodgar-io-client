# hafen.store: Tables

A table you declare is your addon's record: columns, a key and indexes in your [file](README.md), whose rows go in and come out typed by the declaration. For what a [var](vars.md) would grow into (map nodes, prices seen, a log), looked up by key or by clause. Unprotected.

```lua
local nodes = hafen.store():table("nodes")
  :column("grid", "text"):column("x", "integer"):column("y", "integer")
  :column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :key("grid", "x", "y")
  :index("kind")
  :create()
nodes:put{ grid = "g1", x = 1, y = 2, kind = "fir", seen = true, flags = { a = 1 } }
local row = nodes:get("g1", 1, 2)                 -- row.seen == true, row.flags.a == 1
for _, fir in ipairs(nodes:list("WHERE kind = ? ORDER BY x", "fir")) do
  hafen.log():write(fir.grid .. " " .. fir.x .. "," .. fir.y)
end
```

---

## Declare it

`hafen.store():table(name)` is a bare declaration, configured with the setters and dispatched with `:create()`, the one call that touches the file. Every setter is legal until then and none after; each is refused naming what is allowed.

| Method | Returns | Permission | Description | Refused when |
|---|---|---|---|---|
| `hafen.store():table(name)` | declaration | Unprotected | A bare declaration of the table `name`. | The name is not letters, digits and underscores starting with a letter or underscore, or takes the `hafen_` prefix (the client's `hafen_vars`) or `sqlite_`; the file is [unavailable](README.md#when-the-file-cannot-be-opened). |
| `declaration:column(name, type)` | declaration | Unprotected | One column, of a [type](#the-types) below. | The name breaks the rule above, the column is declared already, or the type is not one of the types. |
| `declaration:key(column, ...)` | declaration | Unprotected | The columns that identify a row, in the order `:get` and `:remove` take them. | A key is declared already; a column is not declared, named twice or is `json`. |
| `declaration:index(column, ...)` | declaration | Unprotected | One index over declared columns, `<table>_<cols>` in the file. | A column is not declared or named twice; the same columns are indexed already. |
| `declaration:create()` | the Table | Unprotected | The dispatch: the table is in the file, and the interned Table is answered. | There is no key; the table is in the file with another key. |

A table has one key, and `:put` upserts by it: two rows with one key are one row. A setter after `:create()` is refused naming the Table; another declaration comes from `hafen.store():table`.

### The types

| Word | In the file | Takes | Answers |
|---|---|---|---|
| `text` | `TEXT` | A string. | A string. |
| `integer` | `INTEGER` | A whole number, exact to 2^53. | A number. |
| `real` | `REAL` | A number. | A number. |
| `boolean` | `INTEGER` | `true` or `false`. | `true` or `false`. |
| `json` | `TEXT` | A table a [var](vars.md#what-survives) can hold. | The table that was put; a Position inside comes back a Position. |

The Lua types live in your declaration alone: the file holds an `INTEGER` where you said `boolean` and says nothing about which column is which, so the declaration reads them back, re-read every load because your code declares it every load. A `:put` is typed on the way in (a string in an `integer` column is refused naming the column and its type) and on the way out. A column no declaration names any more comes back as the file holds it.

### Evolution

| Rule | Detail |
|---|---|
| `:create()` over a table in the file | Brings the file up to the declaration: a column the file lacks is added, an index it lacks is made, a column nobody declares any more is left alone (a row still carries it). |
| The key does not change in place | A declaration whose key differs from the file's is refused naming both. Declare the key the file has, or a table under another name. |

```lua
-- the same declaration with one more column: the file gains it; an older row reads it as absent
local nodes = hafen.store():table("nodes"):column("grid", "text"):column("x", "integer")
  :column("y", "integer"):column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :column("quality", "integer"):key("grid", "x", "y"):create()
nodes:put{ grid = "g1", x = 1, y = 3, kind = "fir", quality = 31 }
```

## The Table

| Rule | Detail |
|---|---|
| Interned per addon by name | `"Nodes"` and `"nodes"` are one table in the file and one Table here: two `:create()`s of one name are `==`, and the later declaration is the one both read with. |
| A collection whose filter is a clause | `#nodes`, `nodes[1]` and `ipairs(nodes)` are refused naming `:list` ([collections](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)). |
| A row | A plain table keyed by column name: `NULL` is an absent key, a `boolean` column is `true` or `false`, a `json` column is its table. A column a clause joins in, or one the declaration does not name, comes as the file holds it. |
| The one snapshot | `nodes:info()`: `{name, columns, key, indexes}`, `columns` an array of `{name, type}` in declaration order with the declared word (`boolean`, not `INTEGER`), `key` and each entry of `indexes` an array of column names. A copy built every call; after a second `:create()` it reads the later declaration. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `nodes:get(key, ...)` | `table \| nil` | Unprotected | The row with that key. Takes the key's values in `:key` order, as many as the key has columns, none `nil`; a wrong count is refused naming the key. |
| `nodes:list(clause, ...)` | `table[]` | Unprotected | Every row the clause keeps; empty rather than `nil`. Reads at most the [row cap](README.md#the-two-caps), raising naming `LIMIT` past it. |
| `nodes:count(clause, ...)` | `number` | Unprotected | How many rows the clause keeps, counted by the file; reads nothing. |
| `nodes:find(clause, ...)` | `table \| nil` | Unprotected | The first row the clause keeps; under the row cap as `:list`. |
| `nodes:info()` | `table` | Unprotected | The declaration as a plain table, `{name, columns, key, indexes}`, a copy every call. |

### The clause

The clause is SQL, what follows `FROM <table>` (`"WHERE kind = ? ORDER BY x"`, `"LIMIT 10"`, `"WHERE x > ? AND y > ?"`), with one value bound to each `?` after it [as a statement binds](statements.md#binding). Left out, it keeps everything.

```lua
local near = nodes:list("WHERE grid = ? AND abs(x - ?) <= 2 ORDER BY y", "g1", 1)
local fir_count = nodes:count("WHERE kind = ?", "fir")
```

| Rule | Detail |
|---|---|
| No predicate form | A function is refused naming SQL: a `GROUP BY` or the nearest twenty rows has no spelling in one. An aggregate or a join is a [statement](statements.md). |
| Refused as a statement is | Naming the verb it was handed to: a `;` with anything after it, a `hafen_` name, `load_extension` ([what is refused](statements.md#what-is-refused)). |

## Write (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `nodes:put(row)` | `table` | Unprotected | Upsert by key; answers the row as the file now holds it, read back through the declaration (`put{ seen = true }` answers `seen == true`). |
| `nodes:remove(key, ...)` | the Table | Unprotected | Remove the row with that key, whether or not it was there. |

| Rule | Detail |
|---|---|
| `put` replaces whole | A row whose key is in the file replaces that row; a column the row leaves out is `NULL` in the file, an absent key on the way back. Changing one column of a row you are not holding is an `UPDATE` through [`:exec`](statements.md). |
| `put` refuses | A key of the row naming no column (naming the columns there are); a value not of its column's type (naming the column and the type); a key column missing (naming the key); a key of the row that is not a string. |
| Names compared two ways | Against the file a column name is case-insensitive (SQLite folds identifiers); against a row it is exact (Lua tables are), so a row keyed `Kind` does not name the column `kind`. |

---

## See Also

- [The file](README.md) — the two caps, the sandbox, and when a row is on disk.
- [Statements](statements.md) — the aggregate, the join and the bulk write a Table's verbs do not say.
- [Vars](vars.md) — the shape for settings, and what a `json` column may hold.
- [Conventions](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection grammar.

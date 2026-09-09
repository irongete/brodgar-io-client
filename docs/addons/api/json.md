# hafen.json: parsing and encoding JSON

Convert between JSON strings and Lua values. `hafen.json()` is **unprotected** — pure computation, with
no I/O — and independent of the network: parse a string you loaded from [`hafen.store`](store.md) or
got from anywhere, and encode a table to send or save.

```lua
local s = hafen.json():encode({ name = "ore", qty = 5, tags = { "raw", "metal" } })
-- s == '{"name":"ore","qty":5,"tags":["raw","metal"]}'
local t = hafen.json():parse(s)
hafen.log():write(t.name .. " x" .. t.qty)        -- ore x5
```

## Convert

### `hafen.json():parse(str)`

Parses a JSON document and returns the equivalent Lua value.

| JSON | Lua |
|---|---|
| object `{…}` | table with **string** keys |
| array `[…]` | table, **1-indexed** and sequential |
| string | string |
| `true` / `false` | boolean |
| number | Lua number; an integral value that fits a **32-bit** integer comes back as one, so `{"n":5}` is `5` and not `5.0`, and anything wider — `2147483648`, `1e18` — stays a float, which is the widest integer this Lua holds |
| `null` | **`nil`**, with the consequence below |
| `{"gridId": …, "x": …, "y": …}` | a [Position](position.md) — the durable form of a place, and the one object this parser rebuilds |

**A JSON `null` disappears.** Lua cannot hold `nil` as a live table value, so inside an object the key
is simply **absent** — `parse('{"a":1,"b":null}').b` is `nil` — and inside an array it leaves a
**hole**: `parse('[1,null,3]')` yields `{[1]=1, [3]=3}`, and the `#` length of a table with a hole is
undefined. Do not rely on `null` array elements round-tripping. This is the standard trade-off between
the two languages; there is no sentinel value standing in for `null`.

**A key written twice is the last one.** `parse('{"a":1,"a":2}').a` is `2`, silently: an object becomes a
Lua table and a table holds one value per key. Nothing tells you it happened, so validate a document you
did not write if a repeated key would mean something to you.

Malformed input raises a Lua error reading `JSON: <message> at offset <n>`, so wrap untrusted input.
**Malformed means JSON's own grammar**, not what a lenient reader would take: `01`, `+5`, `.5`, `5.`
and `1e` are each refused by that message, and a `\u` escape that is not exactly four hex digits
(`\uZZZZ`, `\u-123`) is refused too rather than yielding a character nobody asked for.

```lua
local ok, result = pcall(function() return hafen.json():parse(untrusted) end)
if not ok then hafen.log():write("bad JSON: " .. tostring(result)); return end
```

`encode` writes a Position back as that same three-key object, so a place survives a file or a message.
One that is not `:durable()` cannot be written and raises saying so, rather than going out as something
that reads like a place and is not one.

Input longer than **8 million characters**, or nested deeper than **256** levels, raises instead of
risking the stack or the heap. Both caps are one cap for both directions: `encode` refuses a table
nested past the depth, and stops with the same "too large" error once the document it is building
passes the length. Both are set at launch with `-Dhaven.addon.json.maxlen=<chars>` and
`-Dhaven.addon.json.maxdepth=<levels>`; a value outside what the client can hold is clamped rather
than wrapped.

### `hafen.json():encode(value)`

Serializes a Lua value to **compact**, single-line JSON.

- A table whose keys are exactly `1..#t` is written as a JSON **array**; any other table is a JSON
  **object**, with its keys stringified. An empty table has no keys, so it satisfies that rule and is
  written as `[]`. Lua does not distinguish an empty list from an empty map, so a document that needs
  `{}` in that place is one you assemble as a string.
- An integral number is written without a trailing `.0`, however large it is; a non-integral one
  keeps its decimal form.
- Booleans and strings map across with the usual escaping. A lone surrogate — half of an astral
  character, which cannot be encoded as UTF-8 — is escaped as `\uXXXX` so the document stays a
  document.
- **An object's names are strings, and a number key spells one.** Any other kind of key raises,
  because `{[{}] = 1}` has no JSON name and stringifying it would put a heap address in your file.
- **Two keys that spell the same name raise**: `{[1] = "a", ["1"] = "b"}` is one JSON name twice,
  and a document with a repeated key is not valid JSON.

**`encode` is strict**: it only ever produces valid JSON, so a **function**, **userdata**, **thread**, a
**reference cycle**, a table nested deeper than the **depth cap** above, a **non-finite number**, a key
of a kind JSON has no name for, two keys that spell one name, or a document past the **size cap**
raises a Lua error rather than emitting a placeholder. Wrap it if the value might hold one:

```lua
local ok, s = pcall(function() return hafen.json():encode(value) end)
if not ok then hafen.log():write("cannot encode: " .. tostring(s)); return end
```

Encoding and then parsing round-trips a table of scalars, arrays and objects faithfully, apart from the
`null` rule and the integer normalization above. JSON object key order is not significant and is not
preserved.

> **What [`hafen.store`](store.md) writes is not strict.** A saved-variables file must not be lost to one
> bad table, so the flush that writes it puts the literal string `"<cycle>"` or `"<too deep>"` where
> `encode` would have raised, and says nothing. It reads back as that string, so a table you saved can
> come back as text: `encode` it yourself first if you want to be told.

## See also

- [`hafen.store`](store.md) — persisting addon data, which uses this serializer for you
- [`hafen.http`](http.md) — fetching a JSON document to parse, and posting a table as JSON

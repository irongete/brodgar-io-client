# hafen.json: Parsing and Encoding JSON

Convert between JSON strings and Lua values: parse a string from [`hafen.store`](store/README.md) or anywhere else, encode a table to send or save. Pure computation, no I/O, unprotected.

```lua
local encoded = hafen.json():encode({ name = "ore", qty = 5, tags = { "raw", "metal" } })
-- encoded == '{"name":"ore","qty":5,"tags":["raw","metal"]}'
local decoded = hafen.json():parse(encoded)
hafen.log():write(decoded.name .. " x" .. decoded.qty)        -- ore x5
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.json():parse(text)` | Lua value | Unprotected | Parse a JSON document. |
| `hafen.json():encode(value)` | `string` | Unprotected | Serialise a Lua value to compact, single-line JSON. |

## `parse(text)`

| JSON | Lua |
|---|---|
| Object `{…}` | A table with string keys. |
| Array `[…]` | A table, 1-indexed and sequential. |
| String | A string. |
| `true` / `false` | A boolean. |
| Number | An integral value fitting a 32-bit integer comes back as one (`{"n":5}` is `5`, not `5.0`); anything wider (`2147483648`, `1e18`) stays a float, the widest integer this Lua holds. |
| `null` | `nil`: inside an object the key is absent (`parse('{"a":1,"b":null}').b` is `nil`); inside an array it leaves a hole (`parse('[1,null,3]')` is `{[1]=1, [3]=3}`, whose `#` is undefined). No sentinel stands in for `null`. |
| `{"gridId": …, "x": …, "y": …}` | A [Position](position.md), the one object this parser rebuilds. |

| Rule | Detail |
|---|---|
| A key written twice is the last one | `parse('{"a":1,"a":2}').a` is `2`, silently. Validate a document you did not write if a repeated key would matter. |
| Malformed input raises | `JSON: <message> at offset <n>`. Malformed means JSON's own grammar: `01`, `+5`, `.5`, `5.` and `1e` are refused, as is a `\u` escape that is not exactly four hex digits (`\uZZZZ`, `\u-123`). Wrap untrusted input in `pcall`. |
| Caps | Input longer than 8 million characters, or nested deeper than 256 levels, raises. Both caps hold for both directions and are set at launch with `-Dhaven.addon.json.maxlen=<chars>` and `-Dhaven.addon.json.maxdepth=<levels>`; a value outside what the client can hold is clamped. |

```lua
local ok, result = pcall(function() return hafen.json():parse(untrusted) end)
if not ok then hafen.log():write("bad JSON: " .. tostring(result)); return end
```

## `encode(value)`

| Lua | JSON |
|---|---|
| A table whose keys are exactly `1..#t` | An array. An empty table satisfies the rule and is `[]`; a document needing `{}` there is one you assemble as a string. |
| Any other table | An object, keys stringified. A number key spells a string name; any other kind of key raises (`{[{}] = 1}` has no JSON name). Two keys spelling one name raise (`{[1] = "a", ["1"] = "b"}`). |
| An integral number | Written without a trailing `.0`, however large. |
| A non-integral number | Its decimal form. |
| Booleans, strings | With the usual escaping; a lone surrogate is escaped as `\uXXXX`. |
| A Position | The same three-key object, so a place survives a file or a message. One that is not `:durable()` raises. |

| Rule | Detail |
|---|---|
| Strict | A function, userdata, thread, reference cycle, a table past the depth cap, a non-finite number, a key of a kind JSON cannot name, two keys spelling one name, or a document past the size cap raises rather than emitting a placeholder. Wrap it in `pcall` if the value might hold one. |
| Round trip | Encode then parse round-trips scalars, arrays and objects, apart from the `null` rule and the integer normalisation. Object key order is not preserved. |

```lua
local ok, encoded = pcall(function() return hafen.json():encode(value) end)
if not ok then hafen.log():write("cannot encode: " .. tostring(encoded)); return end
```

> **What [`hafen.store`](store/vars.md) writes is not strict.** The timer's write puts the literal string `"<cycle>"` or `"<too deep>"` where `encode` would raise, and logs the path it degraded, so a saved table can come back as text. `hafen.store():flush()` refuses instead, naming the path.

---

## See Also

- [`hafen.store`](store/README.md) — persisting addon data, which uses this serialiser for you.
- [`hafen.http`](http.md) — fetching a JSON document to parse, and posting a table as JSON.

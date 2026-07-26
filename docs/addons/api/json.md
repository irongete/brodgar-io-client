# hafen.json — JSON parse / encode

Convert between JSON strings and Lua values. `hafen.json` is **ungated** (pure CPU — no I/O, no
permission needed) and independent of the network: you can `parse` a string you loaded from
[`hafen.store`](store.md) or received from anywhere, and `encode` a table to send or save.

| Member | Description |
|---|---|
| `hafen.json.parse(str)` | JSON string → Lua value (raises on malformed input) |
| `hafen.json.encode(value)` | Lua value → compact JSON string (raises on non-serializable input) |

```lua
local s = hafen.json.encode({ name = "ore", qty = 5, tags = { "raw", "metal" } })
-- s == '{"name":"ore","qty":5,"tags":["raw","metal"]}'
local t = hafen.json.parse(s)
hafen.log(t.name .. " x" .. t.qty)        -- ore x5
```

## `parse(str)` → Lua value

Parses a JSON document and returns the equivalent Lua value:

| JSON | Lua |
|---|---|
| object `{…}` | table with **string** keys |
| array `[…]` | table, **1-indexed** sequential |
| string | string |
| `true` / `false` | boolean |
| number | Lua number — an integral value comes back as an **int** (`{"n":5}` → `5`, not `5.0`) |
| `null` | **`nil`** — see the caveat below |

**`null` → `nil` (the caveat).** Lua cannot hold `nil` as a live table value, so a JSON `null`
disappears:

- inside an object, its key is simply **absent** — `parse('{"a":1,"b":null}').b` is `nil`;
- inside an array it leaves a **hole** — `parse('[1,null,3]')` yields `{[1]=1, [3]=3}`, and the `#`
  length of a table with a hole is undefined. Don't rely on `null` array elements round-tripping.

This is the standard Lua↔JSON trade-off; there is no `json.null` sentinel (one canonical way).

**Errors.** Malformed input raises a Lua error (`"JSON: <msg> at offset <n>"`), so wrap untrusted input
in `pcall`:

```lua
local ok, result = pcall(hafen.json.parse, untrusted)
if not ok then hafen.log("bad JSON: " .. tostring(result)); return end
```

**Caps (hostile-input guards).** Input longer than **8 MB** or nested deeper than **256** levels raises
an error instead of risking a stack/heap blow-up. Both are tunable at launch with
`-Dhaven.addon.json.maxlen=<chars>` and `-Dhaven.addon.json.maxdepth=<levels>`.

## `encode(value)` → string

Serializes a Lua value to **compact** (single-line) JSON.

- A table whose keys are exactly `1..#t` is written as a **JSON array**; any other table is a **JSON
  object** (its keys are stringified). An empty table is written as `{}`.
- Integral numbers are written without a trailing `.0` (`5`, not `5.0`); non-integral numbers keep their
  decimal form.
- Booleans, strings (with the usual escaping), and `nil` (→ `null`, only meaningful as a value passed
  directly) map across as expected.

**`encode` is strict** — unlike the forgiving `:lua` console echo, it only ever produces **valid JSON**:
a **function**, **userdata**, **thread**, a **reference cycle**, or a **non-finite number** (`NaN`/`inf`)
raises a Lua error rather than emitting a placeholder. Wrap in `pcall` if the value might contain one:

```lua
local ok, s = pcall(hafen.json.encode, value)
if not ok then hafen.log("cannot encode: " .. tostring(s)); return end
```

## Notes

- Same numbers, both ways: `encode` then `parse` round-trips a table of scalars/arrays/objects
  faithfully (modulo the `null`/`nil` caveat and integer normalization above).
- JSON object key order is not significant and is not guaranteed to survive a round-trip.
- For persisting addon data across sessions, prefer [`hafen.store`](store.md) (it uses this same
  serializer under the hood) — reach for `hafen.json` when you need the string itself.

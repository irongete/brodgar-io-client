# hafen.json: JSON Parsing & Serialization

Strict JSON serialization and deserialization for addon configuration, networking payloads, and stored data.

```lua
local json_service = hafen.json()

-- Serialize a table to compact JSON
local player_data = {
  character_name = "Bjorn",
  level = 42,
  attributes = { strength = 30, agility = 25 }
}

local json_string = json_service:encode(player_data)
hafen.log():write("Serialized JSON: " .. json_string)

-- Parse a JSON string back into a Lua value
local parsed_table = json_service:parse(json_string)
hafen.log():write("Parsed character name: " .. parsed_table.character_name)
```

---

## Methods on `hafen.json()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:parse(str)` | `string` | `any` | Parses a JSON string into corresponding Lua tables, numbers, strings, or booleans. Raises a Lua error if JSON is malformed or exceeds max length/depth. |
| `:encode(value)` | `any` | `string` | Serializes a Lua table or primitive value into a compact JSON string. Strictly rejects non-finite numbers, cyclic tables, functions, or userdata. |

---

## See Also

- [`hafen.http`](http.md) — Sending and receiving JSON payloads over HTTP.
- [`hafen.store`](store/README.md) — Persistent SQLite storage for structured tables and variables.

# hafen.json: JSON Encoding & Decoding

Fast JSON serialization and deserialization for data exchange and debugging.

## Quick Example

```lua
local json_service = hafen.json()

-- Serialize a table to a JSON string
local player_data = {
  character_name = "Bjorn",
  level = 42,
  attributes = { strength = 30, agility = 25 }
}

local json_string = json_service:encode(player_data)
hafen.log():write("Serialized JSON: " .. json_string)

-- Parse a JSON string back into a Lua table
local parsed_table = json_service:decode(json_string)
hafen.log():write("Parsed character name: " .. parsed_table.character_name)
```

## Methods on `hafen.json()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:encode(data, [pretty])` | `any, [boolean]` | `string` | Serializes a Lua table or primitive value into a JSON string. If `pretty` is `true`, formats with indentation. |
| `:stringify(data, [pretty])` | `any, [boolean]` | `string` | Alias for `:encode()`. |
| `:decode(json_string)` | `string` | `any` | Parses a JSON string into corresponding Lua tables, numbers, strings, or booleans. Raises an error if JSON is malformed. |
| `:parse(json_string)` | `string` | `any` | Alias for `:decode()`. |

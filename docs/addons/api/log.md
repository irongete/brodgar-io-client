# hafen.log: Logging and Output

Outputs diagnostic messages and status alerts tagged with your addon's ID to both the in-game console/chat and the system terminal.

## Quick Example

```lua
-- Simple message
hafen.log():write("Addon initialized successfully.")

-- Format numbers and strings
local nearby_trees = 14
hafen.log():write(string.format("Detected %d trees in range.", nearby_trees))
```

## Methods on `hafen.log()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:write(message)` | `string` | `self` | Formats and outputs `message` prefixed with `[addon_id]`. |

## Notes
* Messages are visible in the in-game System chat tab and in the terminal window from which the client was started.
* To print structured tables, convert them to a string first using [`hafen.json():encode(table)`](json.md).

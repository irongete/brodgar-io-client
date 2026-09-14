# session:buff: Buffs & Status Effects

Inspect active character status effects, debuffs, durations, and tooltips.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Iterate over all active status buffs
for _, active_buff in ipairs(session:buff():list()) do
  local buff_name = active_buff:name() or active_buff:res() or "Unknown"
  local remaining_duration = active_buff:duration()
  hafen.log():write(string.format("Buff: %s, duration: %.1fs", buff_name, remaining_duration or 0))
end
```

---

## Methods on `session:buff()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Buff[]` | Array of all active buffs matching the filter. |
| `:count(filter?)` | `[string \| function]` | `number` | Count of active buffs matching the filter. |
| `:get(res_or_name)` | `string` | `Buff \| nil` | Finds a specific buff by resource path or display name. |
| `:find(filter)` | `string \| function` | `Buff \| nil` | Returns the first matching buff. |

---

## Methods on `Buff`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Display name of the status effect. |
| `:res()` | `string \| nil` | Resource path of the buff icon. |
| `:duration()` | `number \| nil` | Remaining duration in seconds (`nil` if indefinite). |
| `:tooltip()` | `string \| nil` | Detailed tooltip description text. |
| `:info()` | `table` | Plain table snapshot `{ name, res, duration, tooltip }`. |

## Events

Subscribe to `BuffAdded` and `BuffRemoved` on `hafen.event()`:

```lua
hafen.event():on("BuffAdded", function(new_buff)
  hafen.log():write("Acquired new buff: " .. (new_buff:name() or "Unknown"))
end)
```

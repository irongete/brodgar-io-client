# session:buff: Buffs & Status Effects

Read active status effects, debuffs, and meters displayed on a character's buff bar.

```lua
local session = hafen.session():current()
if not session then return end

-- Check for a specific status effect
local poison_buff = session:buff():find("poison")
if poison_buff then
  hafen.log():write("Character is poisoned!")
end

-- Inspect all active buffs
for _, buff in ipairs(session:buff():list()) do
  local buff_name = buff:name() or buff:res() or "Unknown"
  local fraction_remaining = buff:remaining() or 1.0
  hafen.log():write(string.format("Buff: %s (%.0f%% remaining)", buff_name, fraction_remaining * 100))
end
```

---

## Methods on `session:buff()`

The buff bar is indexed as a collection without unique keys, as identical buff resources can appear multiple times simultaneously.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Buff[]` | 1-based array of all active buffs in arrival order. |
| `:count(filter?)` | `[string \| function]` | `number` | Total number of active buffs matching the filter. |
| `:find(filter)` | `string \| function` | `Buff \| nil` | First active buff matching resource path or display name. |

---

## Methods on `Buff`

| Method | Returns | Description |
|---|---|---|
| `:res()` | `string \| nil` | Resource path of the buff icon (e.g. `"paginae/buff/poison"`). |
| `:name()` | `string \| nil` | Display name of the status effect once resolved. |
| `:amount()` | `number \| nil` | Content-defined meter fraction (`0.0..1.0`). |
| `:remaining()` | `number \| nil` | Radial overlay progress fraction (`0.0..1.0`). Not seconds. |
| `:duration()` | `number \| nil` | Alias for `:remaining()`. Remaining time fraction (`0.0..1.0`). |
| `:number()` | `number \| nil` | Integer badge count drawn over the icon. |
| `:widget()` | `Widget \| nil` | The underlying UI widget rendering this buff icon. |
| `:exists()` | `boolean` | `true` if this buff is currently active on the buff bar. |
| `:info()` | `table \| nil` | Plain table snapshot `{ res, name, amount, duration, number }`. See [`Buff`](types/character.md#buff). |

> **Duration Fraction:** The client does not receive absolute countdown seconds from the server. `:duration()` and `:remaining()` return a `0.0..1.0` fraction representing the remaining portion of the status effect.

---

## Events

Subscribe to `BuffAdded`, `BuffRemoved`, and `BuffChanged` on `hafen.event()`:

```lua
hafen.event():on("BuffRemoved", function(buff)
  local buff_name = buff:name() or buff:res() or "Buff"
  hafen.log():write(buff_name .. " expired or was removed.")
end)
```

---

## See Also

- [`Buff` Snapshot Schema](types/character.md#buff) — Structure of `buff:info()`.
- [`session:meter`](meter.md) — Vital meters and HUD gauges.

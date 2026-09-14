# session:study: Study Desk & Curiosities

Inspect curiosities currently placed on the character's study desk, attention capacity, and total learning-point generation.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local study_desk = session:study()
local attention_info = study_desk:attention()

if attention_info then
  hafen.log():write(string.format(
    "Study Attention: %d/%d (Learning Points: +%d/hr)",
    attention_info.used or 0, attention_info.total or 0, study_desk:learning() or 0
  ))
end

-- Inspect studying curiosities
for _, curiosity_item in ipairs(study_desk:curio():list()) do
  local curiosity_name = curiosity_item:name() or "Unknown Curio"
  local remaining_time = curiosity_item:duration() or 0
  hafen.log():write(string.format("Studying %s (Remaining: %.1fs)", curiosity_name, remaining_time))
end
```

---

## Methods on `session:study()`

| Method | Returns | Description |
|---|---|---|
| `:curio()` | `CurioCollection` | All curiosities currently placed in study slots. |
| `:attention()`| `{used, total} \| nil`| Used and total available Attention capacity. |
| `:learning()` | `number \| nil` | Projected total Learning Points generated per hour. |

---

## Methods on `Curio`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Display name of the curiosity item. |
| `:lp()` | `number \| nil` | Total Learning Points yielded upon completion. |
| `:attention()`| `number \| nil` | Attention cost consumed while studying. |
| `:duration()` | `number \| nil` | Remaining study duration in seconds. |
| `:weight()` | `number \| nil` | Experience point cost. |
| `:info()` | `table` | Plain table snapshot. |

## Events

Subscribe to `StudyChanged` on `hafen.event()`:

```lua
hafen.event():on("StudyChanged", function()
  hafen.log():write("Study desk inventory or completion progress updated.")
end)
```

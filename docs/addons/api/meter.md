# session:meter: Character HUD Meters

Read character vital status meters: health, stamina, energy, and water.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local stamina_meter = session:meter():get("stamina")
if stamina_meter then
  local percentage = math.floor((stamina_meter:value() or 0) * 100)
  hafen.log():write("Current stamina: " .. percentage .. "%")
end
```

---

## Methods on `session:meter()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Meter[]` | All active HUD meters. |
| `:get(meter_name)`| `string` | `Meter \| nil` | Finds a specific meter by name (e.g. `"health"`, `"stamina"`, `"energy"`, `"water"`). |
| `:count()` | None | `number` | Total number of visible meters. |

---

## Methods on `Meter`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string` | Identifier name of the meter. |
| `:value()` | `number \| nil` | Current fill fraction as a float `0.0..1.0`. |
| `:res()` | `string \| nil` | Underlying resource path. |
| `:tooltip()` | `string \| nil` | Display text shown on hover. |
| `:info()` | `table` | Plain table snapshot `{ name, value, res, tooltip }`. |

## Events

Subscribe to `MeterChanged` on `hafen.event()`:

```lua
hafen.event():on("MeterChanged", function(changed_meter)
  if changed_meter:name() == "health" and (changed_meter:value() or 1) < 0.3 then
    hafen.log():write("WARNING: Health critically low (< 30%)!")
  end
end)
```

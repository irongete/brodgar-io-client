# session:meter: Character HUD Meters

Read character vital status meters: health, stamina, energy, and water.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Find the stamina meter by its resource path substring
local stamina_meter = session:meter():find("stamina")
if stamina_meter then
  local percentage = math.floor((stamina_meter:value() or 0) * 100)
  hafen.log():write("Current stamina: " .. percentage .. "%")
end
```

---

## Methods on `session:meter()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Meter[]` | All active HUD meters matching the filter. |
| `:count(filter?)`| `[string \| function]` | `number` | Total count of active HUD meters matching the filter. |
| `:find(filter)` | `string \| function` | `Meter \| nil` | First meter matching the resource substring or predicate. |

---

## Methods on `Meter`

| Method | Returns | Description |
|---|---|---|
| `:res()` | `string \| nil` | Underlying meter background resource path. |
| `:index()` | `number \| nil` | 1-based layout position in the HUD meter stack. |
| `:value()` | `number \| nil` | Current fill fraction of the primary segment (`0.0..1.0`). |
| `:color()` | `{r, g, b, a} \| nil` | Active color tint of the primary segment. |
| `:segments()` | `table[] \| nil` | Array of all segment snapshots `{ value, color }`. |
| `:segment()` | `SegmentCollection` | Sub-collection of individual `MeterSegment` handles. |
| `:widget()` | `Widget \| nil` | Direct `Widget` handle in the HUD tree. |
| `:exists()` | `boolean` | `true` if this meter is currently mounted on the HUD. |
| `:info()` | `table \| nil` | Snapshot `{ res, index, value, color, segments }`. |

---

## Methods on `MeterSegment`

| Method | Returns | Description |
|---|---|---|
| `:index()` | `number` | 1-based index of this segment in the meter. |
| `:value()` | `number \| nil` | Fill fraction of this segment (`0.0..1.0`). |
| `:color()` | `{r, g, b, a} \| nil` | Color tint of this segment. |
| `:info()` | `table \| nil` | Snapshot `{ index, value, color }`. |

---

## Events

Subscribe to `MeterChanged`, `MeterAdded`, or `MeterRemoved` on `hafen.event()`:

```lua
hafen.event():on("MeterChanged", function(changed_meter)
  if changed_meter:res() and changed_meter:res():find("health") then
    if (changed_meter:value() or 1) < 0.3 then
      hafen.log():write("WARNING: Health critically low (< 30%)!")
    end
  end
end)
```

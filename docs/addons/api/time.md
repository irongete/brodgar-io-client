# hafen.time: Game Clock & Astronomy

Read the in-game world time, daylight status, seasons, and lunar phases.

## Quick Example

```lua
local time_info = hafen.time():info()

if time_info.night then
  hafen.log():write("Nighttime in the game world. Season: " .. tostring(time_info.season))
else
  local day_percentage = math.floor((time_info.dayFraction or 0) * 100)
  hafen.log():write("Daytime progress: " .. day_percentage .. "%")
end
```

## Methods on `hafen.time()`

All methods are read-only and return `nil` if no character is logged in or before the first astronomy update arrives from the server.

| Method | Returns | Description |
|---|---|---|
| `:clock()` | `number \| nil` | In-game clock time in seconds since world creation (runs at ~3x real-world speed). |
| `:dayFraction()` | `number \| nil` | Time of day progress (`0.0..1.0`). |
| `:night()` | `boolean \| nil` | `true` if it is currently nighttime in-game. |
| `:season()` | `string \| nil` | Current season name (`"spring"`, `"summer"`, `"autumn"`, `"winter"`). |
| `:moon()` | `number \| nil` | Current moon phase (`0.0..1.0`). |
| `:yearFraction()` | `number \| nil` | Progress of the in-game year (`0.0..1.0`). |
| `:info()` | `table` | Snapshot containing `{ clock, dayFraction, night, season, moon, yearFraction }`. |

> To measure real-world durations or schedule callbacks, use [`hafen.timer()`](timer.md) or `os.clock()`, not `:clock()`.

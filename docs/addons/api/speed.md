# session:speed: Movement Speed

Inspect and adjust the active character's movement speed (crawl, walk, run, sprint).

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local speed_manager = session:speed()
local active_speed = speed_manager:current()

if active_speed then
  hafen.log():write(string.format("Current speed: %s (Tier %d)", active_speed:name(), active_speed:index()))
end

-- Switch to Run if available (requires "speed.set" permission)
local run_speed = speed_manager:get("run")
if run_speed and run_speed:available() then
  speed_manager:set(run_speed)
end
```

---

## Methods on `session:speed()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:current()` | None | `Speed \| nil` | `-` | Currently selected speed tier. |
| `:list()` | None | `Speed[]` | `-` | All 4 speed tiers (`1` crawl, `2` walk, `3` run, `4` sprint). |
| `:available()` | None | `SpeedCollection` | `-` | Subset of speed tiers currently unlocked and selectable. |
| `:get(index_or_name)`| `number \| string`| `Speed \| nil` | `-` | Looks up speed tier by 1-based index (`1..4`) or name (`"crawl"`, `"walk"`, `"run"`, `"sprint"`). |
| `:set(target_speed)` | `Speed \| number \| string` | `self` | `speed.set` | Switches character movement speed. |

---

## Methods on `Speed`

| Method | Returns | Description |
|---|---|---|
| `:index()` | `number` | 1-based speed tier index (`1..4`). |
| `:name()` | `string` | Display name (e.g. `"Crawl"`, `"Walk"`, `"Run"`, `"Sprint"`). |
| `:available()` | `boolean` | `true` if this tier is unlocked and can be selected. |
| `:info()` | `table` | Plain table snapshot `{ index, name, available }`. |

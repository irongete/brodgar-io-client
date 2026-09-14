# hafen.timer: Timers & Scheduling

Schedule recurring or delayed tasks measured in real-world seconds.

## Quick Example

```lua
-- Periodic timer running every 5 seconds
local repeating_timer = hafen.timer():every(5, function()
  local current_session = hafen.session():current()
  if current_session then
    hafen.log():write("Heartbeat check for: " .. (current_session:character() or "Unknown"))
  end
end)

-- One-shot delayed timer running after 10 seconds
local delayed_timer = hafen.timer():after(10, function()
  hafen.log():write("One-shot delayed timer triggered.")
end)

-- Cancel a timer when no longer needed
repeating_timer:cancel()
```

## Methods on `hafen.timer()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:every(seconds, callback)` | `number, function()` | `TimerHandle` | Schedules `callback` to run repeatedly every `seconds` (real-world wall-clock seconds). |
| `:after(seconds, callback)` | `number, function()` | `TimerHandle` | Schedules `callback` to execute once after `seconds` have elapsed. |

## Methods on `TimerHandle`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:cancel()` | None | `self` | Cancels the timer immediately. Safe to call multiple times. |
| `:active()` | None | `boolean` | Returns `true` if the timer is currently running and has not finished or been cancelled. |

> All timers are automatically cancelled when your addon is reloaded or unloaded.

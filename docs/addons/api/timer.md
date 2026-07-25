# hafen.timer — scheduling

Schedule a function to run later, once or repeatedly. Timers run on the UI thread and are owned by your
addon (cancelled automatically on reload/disable).

| Function | Returns | Description |
|---|---|---|
| `hafen.timer.after(sec, fn)` | [`{ :cancel() }`](#handle) | run `fn()` once, `sec` seconds from now |
| `hafen.timer.every(sec, fn)` | [`{ :cancel() }`](#handle) | run `fn()` every `sec` seconds |

### Handle

| Method | Description |
|---|---|
| `:cancel()` | stop the timer |

```lua
hafen.timer.after(3, function() hafen.log("3 seconds later") end)

local tick = hafen.timer.every(1, function() hafen.log("tick") end)
-- later:
tick:cancel()
```

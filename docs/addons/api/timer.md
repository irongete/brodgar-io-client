# hafen.timer: scheduling

Run a function later, once or repeatedly. Reach for a timer when the [event bus](events.md) has
nothing to tell you — polling a value that has no change event, or waiting out the beat after
`OnEnterWorld` during which character data is still streaming in. Timers are **ungated**, run on the UI
thread, and are cancelled for you on reload or disable.

```lua
hafen.timer.after(3, function() hafen.log("3 seconds later") end)

local tick = hafen.timer.every(1, function() hafen.log("tick") end)
-- later:
tick:cancel()
```

## Schedule

| Function | Returns | Description |
|---|---|---|
| `hafen.timer.after(seconds, fn)` | [handle](#the-timer-handle) | run `fn()` once, `seconds` from now |
| `hafen.timer.every(seconds, fn)` | [handle](#the-timer-handle) | run `fn()` every `seconds`, starting `seconds` from now |

Both raise an error unless they get a number and a function. A negative delay counts as `0`, which runs
the function on the next tick. Timing is tick-resolution, not exact: a callback fires on the first tick
at or after its due time, so treat the interval as a floor rather than a promise. A repeating timer's
next run is due one interval after the *previous due time*, not after the callback finished, so a slow
callback does not push the schedule out; it also fires at most once per tick, so a stall is not made up
for afterwards. It keeps running until you cancel it or the addon goes away.

An error inside `fn` is logged and isolated, and it does not cancel the timer — a repeating timer whose
body throws will throw again on every tick, so cancel it yourself when the failure is permanent.

## The timer handle

| Method | Description |
|---|---|
| `:cancel()` | stop the timer; safe to call more than once, and on one that has already fired |

## See also

- [events](events.md) — the bus, for everything the client can tell you without polling
- [conventions](conventions.md#threading) — why a timer body must not block
- [`hafen.time`](time.md) — the *game* clock, which is not what this schedules against

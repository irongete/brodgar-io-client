# hafen.timer: scheduling

Run a function later, once or repeatedly. Reach for a timer when the [event bus](event.md) has
nothing to tell you — polling a value that has no change event, or waiting out the beat after
`EnterWorld` during which character data is still streaming in. Timers are **unprotected**, run on the UI
thread, and are cancelled for you on reload or disable.

```lua
hafen.timer():after(3, function() hafen.log():write("3 seconds later") end)

local tick = hafen.timer():every(1, function() hafen.log():write("tick") end)
-- later:
tick:cancel()
```

## Schedule

| Function | Returns | Description |
|---|---|---|
| `hafen.timer():after(seconds, fn)` | [handle](#the-timer-handle) | run `fn()` once, `seconds` from now |
| `hafen.timer():every(seconds, fn)` | [handle](#the-timer-handle) | run `fn()` every `seconds`, starting `seconds` from now |

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

## Read what is scheduled

`hafen.timer()` is a **collection** of the timers your addon has running, so the section you schedule
with is the section you read. It holds the same handles `:after` and `:every` gave you, so `==` finds
one you kept.

| Function | Returns | Description |
|---|---|---|
| `hafen.timer():list(filter)` | array of handles | every timer still scheduled |
| `hafen.timer():count(filter)` | number | how many are still scheduled |
| `hafen.timer():find(filter)` | handle \| nil | the first one that matches |

`filter` is omitted for all of them, or a **function** called with each handle. A timer has no name, so
the string form of the [filter](conventions.md#the-filter-argument) is refused here rather than
matching nothing. A collection is an object, not an array: `#` and `[n]` on it are refused, and
`:list()` is the array you index.

```lua
hafen.timer():every(1, tick)
hafen.log():write(hafen.timer():count() .. " timer(s) running")
for _, t in ipairs(hafen.timer():list()) do t:cancel() end
```

## See also

- [events](event.md) — the bus, for everything the client can tell you without polling
- [conventions](conventions.md#threading) — why a timer body must not block
- [`hafen.time`](time.md) — the *game* clock, which is not what this schedules against

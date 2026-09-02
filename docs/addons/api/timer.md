# hafen.timer: scheduling

Run a function later, once or repeatedly. Reach for a timer when the [event bus](event/bus/README.md) has
nothing to tell you — polling a value that has no change event, or waiting out the beat after
`SessionEnteredWorld` during which character data is still streaming in. Timers are **unprotected**, run on
the UI thread, and are cancelled for you on reload or disable.

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
for afterwards. An interval of `0` is due again the moment it has run, so it fires once a tick — the
most often anything can, and you pay for its body every frame. It keeps running until you cancel it or
the addon goes away.

An error inside `fn` is logged and isolated, and it does not cancel the timer — a repeating timer whose
body throws will throw again on every tick, so cancel it yourself when the failure is permanent. A body that
fails the client itself — off the end of the stack, out of memory — stops
[your addon instead](../runtime.md#when-a-failure-is-fatal), timer and all.

## The timer handle

What `:after` and `:every` hand back: the timer itself, which answers for its own schedule, so a
`:list()` predicate can ask any of these.

| Method | Description |
|---|---|
| `:interval()` | the seconds between runs, and `0` for a one-shot, which has none |
| `:repeats()` | `true` for one made with `:every`, `false` for one made with `:after` |
| `:due()` | seconds until it next runs, `0` when it is due on this tick, `nil` once it is dead |
| `:alive()` | still scheduled: `false` once cancelled, and once a one-shot has run |
| `:cancel()` | stop the timer and hand it back; safe to call more than once, and on one that has already fired |
| `:info()` | a snapshot table carrying `interval`, `repeats`, `due` and `alive` |

`tostring(t)` reads `Timer(every 5s)`, `Timer(after 2s, fired)` or `Timer(after 2s, cancelled)`, so a
log line of your own timers says which is which. A verb the timer has not got raises naming the ones it
has, and writing to the handle — `t.cancel = nil` — is refused: the verb that stops your timer cannot be
taken off it.

## Read what is scheduled

`hafen.timer()` is a **collection** of the timers your addon has running, so the section you schedule
with is the section you read. It holds the same handles `:after` and `:every` gave you, so `==` finds
one you kept.

| Function | Returns | Description |
|---|---|---|
| `hafen.timer():list(filter)` | array of handles | every timer still scheduled |
| `hafen.timer():count(filter)` | number | how many are still scheduled |
| `hafen.timer():find(filter)` | handle \| nil | the first one that matches |

`filter` is omitted for all of them, or a **function** called with each handle — which answers the
[reads above](#the-timer-handle), so a predicate can ask about the schedule and not only about
identity. A timer has no name, so the string form of the
[filter](conventions.md#the-filter-argument) is refused here rather than matching nothing. A
collection is an object, not an array: `#` and `[n]` on it are refused, and `:list()` is the array you
index.

```lua
hafen.timer():every(1, tick)
hafen.log():write(hafen.timer():count() .. " timer(s) running, "
                  .. hafen.timer():count(function(t) return t:repeats() end) .. " of them repeating")
for _, t in ipairs(hafen.timer():list()) do t:cancel() end
```

## See also

- [events](event/bus/README.md) — the bus, for everything the client can tell you without polling
- [threading](threading.md) — a timer body runs on the step, so it may reach any character
- [`hafen.time`](time.md) — the *game* clock, which is not what this schedules against

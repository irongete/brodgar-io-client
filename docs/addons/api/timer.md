# hafen.timer: Scheduling

Run a function later, once or repeatedly: for polling a value with no change event, or waiting out the beat after `SessionEnteredWorld` while character data streams in. Unprotected, run on the UI thread, cancelled for you on reload or disable.

```lua
hafen.timer():after(3, function() hafen.log():write("3 seconds later") end)

local tick_timer = hafen.timer():every(1, function() hafen.log():write("tick") end)
tick_timer:cancel()
```

---

## Schedule

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.timer():after(seconds, fn)` | [handle](#the-timer-handle) | Unprotected | Run `fn()` once, `seconds` from now. |
| `hafen.timer():every(seconds, fn)` | [handle](#the-timer-handle) | Unprotected | Run `fn()` every `seconds`, starting `seconds` from now, until cancelled or the addon goes away. |

| Rule | Detail |
|---|---|
| Arguments | Both raise unless given a number and a function. A negative delay counts as `0`. |
| Tick resolution | A callback fires on the first tick at or after its due time: the interval is a floor. |
| A repeater's schedule | The next run is due one interval after the previous due time, not after the callback finished, so a slow callback does not push the schedule out; it fires at most once per tick, so a stall is not made up for. An interval of `0` fires once a tick, and you pay for its body every frame. |
| A delay of `0` | Due on the tick it was made on. The client fires [`Update`](event/bus/lifecycle.md#lifecycle) (the bus's and every surface's) before the due timers, so a `0` scheduled from an `Update` handler runs later in the same step; one scheduled from a file body, a console line or a handler dispatched into a widget tree waits for the next step, which makes `:after(0, fn)` the way [off a held tree](threading.md#getting-onto-the-step-from-a-handler-that-holds-a-tree). |
| Order | Your timers due on one tick fire in the order you made them. Between addons there is no order, nor between a timer and another addon's handler for the same moment. |
| An error in `fn` | Logged and isolated; it does not cancel the timer, so a repeater whose body throws throws again every tick: cancel it yourself when the failure is permanent. A body that fails the client itself (stack, memory) stops [your addon](../runtime.md#when-a-failure-is-fatal), timer and all. |

## The timer handle

What `:after` and `:every` hand back: the timer itself, answering for its own schedule.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `timer:interval()` | `number` | Unprotected | The seconds between runs; `0` for a one-shot, and for a repeater made with a period of `0` or less (`:repeats()` tells them apart). |
| `timer:repeats()` | `boolean` | Unprotected | `true` for one made with `:every`, `false` for `:after`. |
| `timer:due()` | `number \| nil` | Unprotected | Seconds until it next runs; `0` when due on this tick; `nil` once dead. |
| `timer:alive()` | `boolean` | Unprotected | Still scheduled: `false` once cancelled, and once a one-shot has run. |
| `timer:cancel()` | the handle | Unprotected | Stop the timer; safe to call more than once, and on one that has fired. |
| `timer:info()` | `table` | Unprotected | A snapshot carrying `interval`, `repeats`, `alive`, and `due` while alive. |

| Rule | Detail |
|---|---|
| `tostring(timer)` | `Timer(every 5s)`, `Timer(after 2s, fired)` or `Timer(after 2s, cancelled)`. |
| Closed | A verb the timer lacks raises naming the ones it has; `timer.cancel = nil` is refused. |

## Read what is scheduled

`hafen.timer()` is a collection of the timers your addon has running, holding the handles `:after` and `:every` gave you, so `==` finds one you kept.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.timer():list(filter)` | handle`[]` | Unprotected | Every timer still scheduled. |
| `hafen.timer():count(filter)` | `number` | Unprotected | How many are still scheduled. |
| `hafen.timer():find(filter)` | handle `\| nil` | Unprotected | The first that matches. |

| Rule | Detail |
|---|---|
| `filter` | Omitted, or a function called with each handle, which answers the [reads above](#the-timer-handle). A timer has no name, so the string [filter](conventions.md#the-filter-argument) is refused. |
| A collection is an object | `#` and `[n]` on it are refused; `:list()` is the array. |

```lua
hafen.timer():every(1, tick)
hafen.log():write(hafen.timer():count() .. " timer(s) running, "
                  .. hafen.timer():count(function(timer) return timer:repeats() end) .. " of them repeating")
for _, timer in ipairs(hafen.timer():list()) do timer:cancel() end
```

---

## See Also

- [Events](event/bus/README.md) — the bus, for everything the client can tell you without polling.
- [Threading](threading.md) — a timer body runs on the step, so it may reach any character.
- [`hafen.time`](time.md) — the game clock, which is not what this schedules against.

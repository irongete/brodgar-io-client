# hafen.time: the game clock

The in-game clock and the day, night and season state. Reach for it to schedule around dusk, or to
label something with the game's own time rather than the wall clock.

```lua
if hafen.time():isNight() then hafen.log():write("it's dark out") end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.time():clock()` | number \| nil | interpolated game-time seconds |
| `hafen.time():dayFraction()` | number \| nil | time of day, `0..1` |
| `hafen.time():isNight()` | bool \| nil | whether it is night |
| `hafen.time():season()` | number \| nil | season index |
| `hafen.time():moon()` | number \| nil | moon phase, `0..1` |
| `hafen.time():yearFraction()` | number \| nil | position in the year, `0..1` |

`clock()` answers as soon as a session is up. The astronomy readers answer `nil` until the first
astronomy update arrives from the server, which is a beat after entering the world. Nothing here throws
and nothing is protected.

There is no `TimeChanged` event: the clock moves every frame, so read it when you need it, or poll it
on a [timer](timer.md).

## See also

- [`hafen.timer`](timer.md) — scheduling against real seconds rather than game time
- [events](event.md#lifecycle) — `EnterWorld`, the point from which astronomy answers

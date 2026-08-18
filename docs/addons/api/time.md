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

**The clock belongs to the world, not to a character.** There is one world however many characters you
have logged in, and each of them reads the same server time out of it — so these answer from any session
the client holds rather than from the one on screen, and tabbing between characters never blanks them.
They answer `nil` when the client holds no session at all, which is the login screen.

`clock()` answers as soon as a session is up. The astronomy readers answer `nil` until the first
astronomy update arrives from the server, which is a beat after entering the world.

Nothing here is protected, and **every verb is a read: passing one an argument raises**. Arity is the
verb across the API, so a call written like a setter must not quietly read instead — there is nothing
here to set, and that refusal is the only thing on this page that throws.

There is no `TimeChanged` event: the clock moves every frame, so read it when you need it, or poll it
on a [timer](timer.md).

## See also

- [`hafen.timer`](timer.md) — scheduling against real seconds rather than game time
- [events](event/bus.md#sessions) — `SessionEnteredWorld`, the point from which astronomy answers

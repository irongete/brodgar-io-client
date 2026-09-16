# hafen.time: the game clock

The in-game clock and the day, night and season state. Reach for it to schedule around dusk, or to
label something with the game's own time rather than the wall clock.

```lua
if hafen.time():night() then hafen.log():write("it's dark out") end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.time():clock()` | number \| nil | interpolated game-time seconds since the world began, running at about **3** game seconds per real one |
| `hafen.time():dayFraction()` | number \| nil | time of day, `0..1` |
| `hafen.time():night()` | bool \| nil | whether it is night |
| `hafen.time():season()` | string \| nil | one of `"spring"`, `"summer"`, `"autumn"`, `"winter"` |
| `hafen.time():moon()` | number \| nil | moon phase, `0..1` |
| `hafen.time():yearFraction()` | number \| nil | position in the year, `0..1` |
| `hafen.time():info()` | table | all six as **one reading**: `{ clock, dayFraction, night, season, moon, yearFraction }`, each key absent wherever its own verb answers `nil` |

**The clock belongs to the world, not to a character.** There is one world however many characters you
have logged in, and each of them reads the same server time out of it — so these answer from any session
the client holds rather than from the one on screen, and tabbing between characters never blanks them.
They answer `nil` when the client holds no session at all, which is the login screen.

`clock()` answers as soon as a session is up. The astronomy readers answer `nil` until the first
astronomy update arrives from the server, which is a beat after entering the world.

**Read them together with `:info()`.** Each verb asks the client for the astronomy afresh, and the
server replaces that whole reading at once — so two verbs on one line can land either side of an
update and describe two different moments. `:info()` takes one reading and answers all six out of it,
which is what you want whenever more than one of them is going into the same decision.

> **`clock()` is not a stopwatch.** Each login runs its own copy of the world time forward and steers it
> towards what the server last said, so two logins are a fraction of a second apart and the number can
> **go backwards**: a resync, or the client's first word from the server about the time, sets it outright
> rather than easing it. Two calls a moment apart may read from different logins as well. Use it to
> label a moment or to ask what time of day it is, never to measure how long something took —
> [`hafen.timer`](timer.md) and `os.clock()` are what measure.

The four fractions are the server's own numbers passed straight through, and the client neither clamps
them nor checks them: `0..1` is what they mean, not a range anything enforces. Guard a value you are
about to index or multiply with.

**These verbs are a part of what the server publishes, not the whole of it.** The astronomy update
carries more than the day, the night, the season, the moon and the year — where the sun stands among
them — and the client keeps every field of it. The verbs in the table are what this API names of it.

`season()` names the season rather than numbering it, so `== "winter"` reads exactly as it looks. The
server publishes one of the four above and nothing else; anything outside them answers `nil` too — and
so does a server that publishes no season at all, which the client cannot guess and does not.

Nothing here is protected, and **every verb is a read: passing one an argument raises**. Arity is the
verb across the API, so a call written like a setter must not quietly read instead — there is nothing
here to set, and that refusal is the only thing on this page that throws.

There is no `TimeChanged` event: the clock moves every frame, so read it when you need it, or poll it
on a [timer](timer.md).

## See also

- [`hafen.timer`](timer.md) — scheduling against real seconds rather than game time
- [events](event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, the point from which astronomy answers

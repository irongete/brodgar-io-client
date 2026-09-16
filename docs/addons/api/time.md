# hafen.time: The Game Clock

The in-game clock and the day, night and season state, for scheduling around dusk or labelling something with the game's own time.

```lua
if hafen.time():night() then hafen.log():write("it's dark out") end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.time():clock()` | `number \| nil` | Unprotected | Interpolated game-time seconds since the world began, running at about 3 game seconds per real one. |
| `hafen.time():dayFraction()` | `number \| nil` | Unprotected | Time of day, `0..1`. |
| `hafen.time():night()` | `boolean \| nil` | Unprotected | Whether it is night. |
| `hafen.time():season()` | `string \| nil` | Unprotected | `"spring"`, `"summer"`, `"autumn"` or `"winter"`. |
| `hafen.time():moon()` | `number \| nil` | Unprotected | Moon phase, `0..1`. |
| `hafen.time():yearFraction()` | `number \| nil` | Unprotected | Position in the year, `0..1`. |
| `hafen.time():info()` | `table` | Unprotected | One reading of all of them: `{ clock, dayFraction, night, season, moon, yearFraction }`, each key absent where its own verb answers `nil`. |

| Rule | Detail |
|---|---|
| The clock belongs to the world | One world however many characters are logged in: the verbs answer from any session the client holds, and tabbing between characters never blanks them. `nil` when the client holds no session, the login screen. |
| When they answer | `clock()` as soon as a session is up; the astronomy readers `nil` until the first astronomy update from the server, a beat after entering the world. |
| Read together with `:info()` | Each verb asks the client afresh and the server replaces the whole reading at once, so two verbs on one line can land either side of an update. `:info()` takes one reading. |
| `clock()` is not a stopwatch | Each login runs its own copy forward and steers towards what the server last said: two logins are a fraction of a second apart, and the number can go backwards on a resync or the client's first word from the server. Label a moment with it; measure with [`hafen.timer`](timer.md) or `os.clock()`. |
| The fractions are the server's | Passed straight through, neither clamped nor checked: `0..1` is what they mean, not a range anything enforces. Guard a value you index or multiply with. |
| Part of the update | The astronomy update carries more (where the sun stands) and the client keeps every field; the verbs above are what this API names of it. |
| `season()` | Names the season, so `== "winter"` reads as it looks. Anything outside the four answers `nil`, as does a server that publishes no season. |
| Reads only | Passing an argument to any verb raises: the one thing on this page that throws. Nothing is protected. |
| No `TimeChanged` | The clock moves every frame: read it when you need it, or poll on a [timer](timer.md). |

---

## See Also

- [`hafen.timer`](timer.md) — scheduling against real seconds rather than game time.
- [Events](event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, the point from which astronomy answers.

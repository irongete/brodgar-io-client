# hafen.time — game clock & astronomy

The in-game clock and day/night/season state.

| Function | Returns | Description |
|---|---|---|
| `hafen.time.clock()` | number \| nil | interpolated game-time seconds |
| `hafen.time.dayFraction()` | number \| nil | time of day, 0..1 |
| `hafen.time.isNight()` | bool \| nil | whether it is currently night |
| `hafen.time.season()` | number \| nil | season index |
| `hafen.time.moon()` | number \| nil | moon phase, 0..1 |
| `hafen.time.yearFraction()` | number \| nil | position in the year, 0..1 |

```lua
if hafen.time.isNight() then hafen.log("it's dark out") end
```

> `clock()` is available as soon as a session is up. The astronomy readers (`dayFraction`, `isNight`,
> `season`, `moon`, `yearFraction`) return `nil` until the first astronomy update arrives.

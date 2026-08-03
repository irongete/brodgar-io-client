# hafen.log: printing a line

Print a line from your addon. It goes to the in-game console, the same place the client's own notices
appear, and to the terminal the client was started from. `hafen.log` is **ungated**, and it is the
first tool you reach for when an addon is not doing what you expected.

```lua
hafen.log("started")
hafen.log("gobs in view: " .. #hafen.world.gobs())
```

## Print

| Function | Description |
|---|---|
| `hafen.log(msg)` | print `msg`, tagged with your addon's id |

`msg` is a string, or any value, which is converted to one — a table prints as a Lua table
description, not as its contents, so encode it with [`hafen.json`](json.md) if you want to see inside.
`nil` prints as `nil` rather than raising.

The in-game line is **clipped at 500 characters**, with a note giving the real length, because one very
long line has to fit into a single texture. The terminal always gets the whole thing, so print large
values there and read them off the terminal.

Every line is prefixed with your addon's id, which is what makes several addons logging at once
readable. Errors your addon raises are logged the same way, with the same prefix, whether they come
from an event handler, a timer or a draw callback.

## See also

- [`hafen.slash`](slash.md) — a console command, the other half of the console
- [`hafen.json`](json.md) — turning a table into something worth printing
- [`hafen.client:profiling()`](client/profiling/README.md) — for questions about cost rather
  than about values

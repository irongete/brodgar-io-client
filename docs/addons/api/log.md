# hafen.log: printing a line

Print a line from your addon. It goes to the in-game console, the same place the client's own notices
appear, and to the terminal the client was started from. `hafen.log()` is **unprotected**, and it is the
first tool you reach for when an addon is not doing what you expected.

```lua
hafen.log():write("started")
hafen.log():write("gobs in view: " .. #hafen.session():current():world():gob():list())
```

## Print

| Function | Returns | Description |
|---|---|---|
| `hafen.log():write(msg)` | the section, so lines chain | print `msg`, tagged with your addon's id |

`msg` is a string, or any value, which is converted to one the way `tostring` does — so a handle
prints as itself (`Gob(gfx/borka/body, 1234)`), and a plain table prints as a Lua table description
rather than as its contents, so encode it with [`hafen.json`](json.md) if you want to see inside.
A missing `msg`, or an explicit `nil`, raises: printing the word `nil` hides the mistake that produced
it. Because the verb hands the section back, a run of lines chains:

```lua
hafen.log():write("first"):write("second")
```

**The in-game half goes to the character on screen.** A notice is drawn by the session you are looking
at, so that is where a line lands, whichever character your addon was watching when it wrote one. The
terminal half is written whatever the client is showing — the login screen included — which is what
carries a line when no character is up at all.

The prefix names your **addon**, never the character. With several characters up, a line says nothing about
which one your handler was running for — so if that matters, write it into the line yourself:

```lua
hafen.log():write(s:user() .. ": entered the world")
```

The in-game line is **clipped at 500 characters**, with a note giving the real length, because one very
long line has to fit into a single texture. The terminal always gets the whole thing, so print large
values there and read them off the terminal.

**The in-game half is also rate-limited: eight lines a second, per addon.** Past that the lines are
counted rather than drawn, and one line at the end of the second says how many were dropped and that
the terminal has them. A notice is a timed line over the world and an entry in the System channel, so
a handler writing one every frame is the screen, not a log. The terminal half is never limited: it is
the record, and a record with lines missing is not one.

Every line is prefixed with your addon's id, which is what makes several addons logging at once
readable. Errors your addon raises are logged the same way, with the same prefix, whether they come
from an event handler, a timer or a draw callback.

## See also

- [`hafen.console`](console.md) — a console command, the other half of the console
- [`hafen.json`](json.md) — turning a table into something worth printing
- [`hafen.client():profiling()`](client/profiling/README.md) — for questions about cost rather
  than about values

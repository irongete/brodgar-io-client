# hafen.log: Printing a Line

Print a line from your addon to the in-game console (where the client's own notices appear) and to the terminal the client was started from. Unprotected; the first tool for an addon not doing what you expected.

```lua
hafen.log():write("started")
hafen.log():write("gobs in view: " .. #hafen.session():current():world():gob():list())
```

---

## Print

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.log():write(message)` | the section | Unprotected | Print `message`, tagged with your addon's id. Lines chain: `hafen.log():write("first"):write("second")`. |

| Rule | Detail |
|---|---|
| `message` | A string, or any value converted as `tostring` does: a handle prints as itself (`Gob(gfx/borka/body, 1234)`), a plain table as a Lua table description, so encode it with [`hafen.json`](json.md) to see inside. A missing `message`, or an explicit `nil`, raises. |
| The in-game half goes to the character on screen | A notice is drawn by the session you look at, whichever character your addon was watching. The terminal half is written whatever the client shows, the login screen included. |
| The prefix names your addon, never the character | With several characters up, write the character into the line yourself: `hafen.log():write(session:user() .. ": entered the world")`. |
| Clipped at 500 characters in-game | With a note giving the real length, since one line fits one texture. The terminal gets the whole thing. |
| Rate-limited in-game | Eight lines a second per addon; past that the lines are counted, and one line at the end of the second says how many were dropped and that the terminal has them. The terminal half is never limited. |
| Errors | Errors your addon raises are logged with the same prefix, from an event handler, a timer or a draw callback alike. |

---

## See Also

- [`hafen.console`](console.md) — a console command, the other half of the console.
- [`hafen.json`](json.md) — turning a table into something worth printing.
- [`hafen.client():profiling()`](client/profiling/README.md) — questions about cost rather than values.

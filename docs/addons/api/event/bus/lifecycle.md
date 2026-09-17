# hafen.event: Your Addon and the Sessions

Your addon being loaded, ticked and disabled, and a character connecting, reaching the world, taking the screen and ending. These keys say when the rest of the API has something in it. Part of [the catalogue](README.md). `hafen.event():on(key, fn)` is the door.

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write(session:user() .. " is playing " .. (session:character() or "nobody yet"))
end)
```

---

## Lifecycle

Your addon's own keys, each about the addon rather than a character.

| Event | Payload | Fires |
|---|---|---|
| `Load` | — | Once for the client, when the addon is loaded, before any character exists. |
| `Update` | `dt` (`number`) | Every frame. `dt` is seconds since the last frame. |
| `Disable` | — | Once for the client, when the addon is disabled or reloaded, or the client closes. |

| Rule | Detail |
|---|---|
| Once for the client | One addon, one Lua state, however many sessions are logged in. |
| `Update` cost | Runs on the [step](../../threading.md) once per frame, not once per session. Keep it cheap. |

## Sessions

The moments of a session, each handing that [`Session`](../../session.md): the address every read your handler goes on to make is named by.

| Event | Payload | Fires |
|---|---|---|
| `SessionAdded` | [`Session`](../../session.md) | A session connects, before it has a character or a world. |
| `SessionEnteredWorld` | [`Session`](../../session.md) | Its HUD is up, so that character can be read. |
| `SessionSelected` | [`Session`](../../session.md) | The screen changed to this session. |
| `SessionRemoved` | [`Session`](../../session.md) | This session ended, however it ended. |

| Rule | Detail |
|---|---|
| `SessionRemoved` names a session already gone | `session:user()` answers (the account name is the whole of a `Session`). `session:exists()` is `false` and everything else reads `nil`. The payload is the key to drop your own tables by. |
| What `SessionEnteredWorld` guarantees | The HUD exists, the [action menu](../../menugrid.md) included, so your entries go in from there. Much character-sheet data streams in for a few seconds after ([missing data returns nil](../../conventions.md#missing-data-returns-nil)). |
| That session's vars | Its [own vars](../../store/vars.md) read back from this point, on screen or not. Tabbing there brings nothing more, since each session keeps its own tables, and what a screen change writes is [the placements the user made](../../store/vars.md#where-a-widget-sits-is-saved-for-you). |
| The action-menu wait is bounded at five seconds | A session whose menu never arrives is announced anyway with a console line, and `session:menugrid():add(id)` refuses in that handler. Nothing announces the menu's later arrival. Re-try on a [timer](../../timer.md). |
| Not one queue | `SessionEnteredWorld` is delivered from that character's own step, the other three from your addon's. That session's own vars and held action-bar slots are readable before your handler runs, because the same step put them there. Read each event for what it says about its own payload, never as a report of where another has got to. |
| A session the client cannot name announces nothing | The account name is the identity: a login the client holds no name for fires neither `SessionAdded` nor `SessionRemoved`. One that ends between reaching the world and being named is logged, not announced. [`hafen.session():list()`](../../session.md) says who is up. |
| Taking the screen is not entering the world | Going between two characters in the world fires `SessionSelected` once per change, whether the player tabbed or an addon wrote [`hafen.session():current(session)`](../../session.md#write-unprotected). Naming the session already drawn fires nothing. Ending the session on screen fires its `SessionRemoved` first, then `SessionSelected` for the one taking over. |
| The login screen | Going there fires nothing (after dropping your last session, after [`hafen.session():current(nil)`](../../session.md#hafensessioncurrentnil), or a login performed there). [`hafen.session():current()`](../../session.md) reads `nil` there. |
| Entering the world is not being looked at | A session reaching the world while another holds the screen fires `SessionEnteredWorld` there and then. Read that character through the `Session` handed to you, not through `hafen.session():current()`. |
| One account, one character at a time | Picking another character on the same account keeps the session alive: `SessionEnteredWorld` fires again for the same `Session` with no `SessionRemoved` between. Key tables by `session:user()` for the account. Rebuild what was the character's on every `SessionEnteredWorld`. |
| Changes, not state | An addon loaded while characters are already up receives no event for them. [`hafen.session():list()`](../../session.md) reads what is already there. A `:reload` rebuilds every addon and announces `SessionEnteredWorld` for every session in the world, the one on screen first, each once. |

> **Your state survives a character switch.** Nothing of yours is torn down when the screen moves. A widget handle, a Gob or an [item](../../ui/items.md) kept from one character is still in your table under the next. It still belongs to the character it came from. Drop what belonged to a session at `SessionRemoved`, by its payload.

---

## See Also

- [The catalogue](README.md) — the other families, and whose character an event was.
- [`hafen.session`](../../session.md) — the payload the session events hand you, and the collection of the rest.
- [When your code runs](../../../runtime.md) — the life of an addon, of which these are the moments.
- [Saved data](../../store/vars.md) — what is read back at `SessionEnteredWorld`, and when it is written.
- [Events and timers](../../../guides/events-and-timers.md) — the guide that puts a handler to work.

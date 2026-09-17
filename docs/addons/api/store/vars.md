# hafen.store: Vars

A var is a Lua table you name at `var`. It is filled from your [file](README.md) the first time it is named, and written back when it changes. Assigning into it is the whole of saving. The shape for settings. A record that grows is a [table](tables.md). Unprotected.

```lua
local settings = hafen.session():current():store():var("settings")   -- the live table, not a copy
settings.enabled = true
settings.count = (settings.count or 0) + 1

local seen = hafen.store():var("seen")                                -- your addon's own
seen.lastLogin = os.time()
```

---

## The doors are the scopes

| Door | Whose | Where it lands |
|---|---|---|
| `hafen.store()` | Your addon's own, one for the whole client, whichever account or character is up. | A row of your file, keyed by nobody. |
| `session:store()` | One character's, the character that session plays, on screen or not. | A row of your file, keyed by that character. |

The same name through each door is a var of its own. A character's key is named by the server: the world's name and the character's, reduced to letters, digits, `.` and `-`, joined by `_`. One character is one key in every file. One who has not reached the world has none yet.

## Read and write

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:store():var(name)` | live table | Unprotected | That character's var `name`, empty until something is saved under it. |
| `session:store():list()` | `string[]` | Unprotected | The names that exist in that character's scope, sorted. |
| `session:store():flush()` | the store | Unprotected | Write that character's changed vars now. |
| `hafen.store():var(name)` | live table | Unprotected | Your addon's own var `name`, empty until something is saved under it. |
| `hafen.store():list()` | `string[]` | Unprotected | The names that exist in your addon's own scope, sorted. |
| `hafen.store():flush()` | the store | Unprotected | Write your addon's own changed vars now. |

| Rule | Detail |
|---|---|
| The table itself, not a copy | No "put it back" step. A reference kept in a local goes on being the one written. Stable for the addon's life: the same object every call, refilled in place by a restore. Assign into it. You cannot assign over it. The [third kind of value](../conventions.md#snapshots-vs-handles) beside a snapshot and a handle, and the one place a typo on a key is silent and then persisted. |
| A name is what you call it | A misspelt name is an empty var. `name` is a non-empty string: `nil`, a number and `""` are refused naming the parameter. A var nobody names in a session is neither read nor written. |
| What `list()` answers | A name with a row in your file, or a table handed out this session (a var written and not yet flushed is on it). Nothing of the other scope. |
| Each character's are their own | Two characters read and write two sets of rows. A handle cached from one session stays that character's while the session lives. A session that picks another character refills every table it handed out from the new character's rows, in place. |
| Two states raise | A session that is not live: its rows were written when it ended, and `session:exists()` tells. A session that has not reached the world: no character, no key, until `SessionEnteredWorld`. Neither hands back an empty table that would take writes and never save them. |
| When each scope is ready | Your addon's own in the file body and in `Load`. A character's from that session's `SessionEnteredWorld`, not in `Load`. |

## What survives

| Value | Round-trips as |
|---|---|
| Tables, strings, numbers, booleans | Themselves: a var is stored as JSON. Keys become strings unless the table is a `1..n` array. A `nil` value is an absent key. |
| A [Position](../position.md) | Its durable form, back as a Position. One on ground never visited has no durable form and is not written. `position:durable()` says. |
| A function, a widget handle, a table nested past [`hafen.json`](../json.md)'s depth cap | A placeholder string, back as that string, so one unwritable corner does not cost the row. |
| A cycle | The literal text `"<cycle>"`, back as text. |
| A function, table or boolean key | Named at the same door as an unwritable value. |

Store plain data and rebuild on load.

## When it is written

| Scope | Written |
|---|---|
| Your addon's own | On the timer, when your addon is disabled or reloaded, and when the client quits. |
| A character's | On the timer, when that session ends or picks another character, and when the client quits. |

| Rule | Detail |
|---|---|
| Quitting writes both | A value set and never flushed is there when you log back in. The timer, roughly every thirty seconds, covers a crash or a kill. A row whose content has not changed is not rewritten. Each scope's rows go in one transaction, so an interrupted write leaves the row it had. |
| Tabbing writes nothing and loses nothing | Each session keeps its own tables. Dropping a session writes it. |
| `flush()` | Writes its scope immediately: call it after a change the user would be upset to lose. The one write that refuses: it names the path of the first value a var cannot hold, and refuses first when the file is [unavailable](README.md#when-the-file-cannot-be-opened). The timer and the teardown write the placeholder instead and log the path of the first value degraded. |
| A row that cannot be read or parsed | Leaves the table as it is, logged not raised. That gives empty settings rather than an addon that does not start. That scope is [read-only for the session](README.md#when-the-file-cannot-be-opened). A row that parses into something that is not a var (a single value, `null`) is the same failure. |

## Where a widget sits is saved for you

[`widget:remember(name)`](../ui/native.md#remembering-where-the-user-put-it-unprotected) keeps where a widget sits and how big it is, with no var and no code of yours. It is written the moment the gesture lands, under the name you gave once. The row is in the client's own file `savedata/client.sqlite`, keyed by your addon, never in yours.

| The widget | Its row | Because |
|---|---|---|
| One of a session's own, `session:ui():match("@ChatUI")` | Keyed by that character. | Where the user dragged that character's chat window is a fact about that character. |
| One you built, `hafen.ui():window()` | Keyed by nobody, like your addon's own vars. | It stands in your layer, which belongs to no character and outlives all of them. |

A session's own window has nothing to put back until that session is in world, and tabbing moves nothing. No `flush()` and no timer writes a placement. The row is written when a drag or a resize lands and when the screen changes. It is written when the widget goes: destroyed, closed, torn down with your addon. A place you wrote with `widget:position(x, y)` lands with the widget.

---

## See Also

- [The file](README.md) — where it is, when it is written and closed, and the state when it cannot be opened.
- [Tables](tables.md) — the shape for a record, where a var would grow.
- [`hafen.session`](../session.md) — the address a character's vars are reached through.
- [`hafen.json`](../json.md) — the same serialiser, when you want the string yourself.
- [Events](../event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, where a character's vars are readable.

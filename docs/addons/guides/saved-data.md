# Saved Data

Anything your addon should still know next week goes in [your addon's file](../api/store/README.md), one per addon for the whole client, in three shapes: a var (a Lua table you name at `var`, restored when you first name it and written back for you: settings), a table you declare (typed rows looked up by key or clause: a record), and a statement (SQL). Start with a var; move to a table the day the var would grow.

```lua
hafen.session():current():store():var("settings").window = { x = 40, y = 200 }   -- that character's own
hafen.store():var("seen").lastLogin = os.time()                                   -- your addon's own
```

---

## Name it, and it exists

| Rule | Detail |
|---|---|
| The door is the scope | A var through [a session](../api/session.md) is that character's own; through `hafen.store()` it is your addon's own, one for the whole client. The same name through the two doors is two vars. |
| Always a usable table | Empty when nothing is saved yet, so nothing to create and no `nil` check; a misspelt name is an empty var. The table object never changes (a restore refills it in place), so a cached local stays valid ([vars](../api/store/vars.md)). |

## Read it at the right moment

| Scope | Readable from |
|---|---|
| Your addon's own | Your file bodies and `Load`. |
| Per character | That session's `SessionEnteredWorld` onwards. |

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  local saved_position = session:store():var("settings").window
  if saved_position then window:position(saved_position.x, saved_position.y) end
end)
```

| Rule | Detail |
|---|---|
| In `Load` a character's table is empty | Not an error; the bug that looks like "my settings do not load". |
| Every character has its own | Your addon is loaded once for a client holding several characters; each session keeps its own tables, `session:store()` answers about the character it plays whether or not you look at it, and a table cached from one session stays that character's. A session with no character (ended, or not yet in the world) raises; the [session events](../api/event/bus/lifecycle.md#sessions) say when. |

## Store data, not objects

The tables are written as JSON: tables, strings, numbers and booleans survive; a function or a handle comes back as a placeholder string; keys become strings unless the table is a plain `1..n` array; a `nil` value is an absent key. Keep the description of a thing (a colour is three numbers, a layout a table of positions, a chosen action [a resource name](../api/ui/custom.md#drop-makes-a-widget-a-drop-target)) and rebuild the live objects on load. Anything positional is a [Position](../api/position.md), since a raw `x, y` is meaningless next session.

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  for _, prop in ipairs(session:store():var("settings").props or {}) do
    local position = session:world():position(prop.at)          -- :x() is nil until that grid is reachable
    if position then hafen.virtual():ghost():add(prop.res, position) end
  end
end)
```

## A record is a table

A var is held and written whole, so a thousand map nodes in one are a thousand entries serialised at every save. Declare a [table](../api/store/tables.md): columns, a key and an index, rows typed both ways.

```lua
local nodes = hafen.store():table("nodes")
  :column("grid", "text"):column("x", "integer"):column("y", "integer"):column("kind", "text")
  :key("grid", "x", "y"):index("kind"):create()
nodes:put{ grid = "g1", x = 4, y = 9, kind = "fir" }              -- one row in the file, now
for _, node in ipairs(nodes:list("WHERE kind = ?", "fir")) do
  hafen.log():write(node.grid .. " " .. node.x .. "," .. node.y)
end
```

A row is in the file when `:put` returns. Where a character's rows are theirs alone, the character is a column you declare; what every character saw is one `SELECT` away through a [statement](../api/store/statements.md).

## When it is written

| Rule | Detail |
|---|---|
| Vars | Flushed on a timer and when the client quits (an ordinary quit loses nothing, a crash at most the last half-minute); your addon's own again on disable or reload; a character's when the session ends or picks another character. Tabbing writes nothing and loses nothing. |
| Rows | In the file when the call returns. |
| `flush()` | `session:store():flush()` and `hafen.store():flush()` write their scope now: worth it after a change the user would be annoyed to lose. Either refuses a value a var cannot hold, naming where it sits, the fastest way to find out you stored the widget instead of its place. |
| A row the client cannot parse | Leaves that var empty and logs it: default settings instead of an addon that does not start, and the scope is not written back until a load succeeds. |

## Where a window sits is saved for you

[`widget:remember(name)`](../api/ui/native.md#remembering-where-the-user-put-it-unprotected) keeps a widget's place and box under a name of yours, puts them back when called, and saves them again in the client's own file each time the user moves the thing: no var, no table, no handler. Per character, so it belongs in `SessionEnteredWorld`; the character is the one whose window it is, while a window you built is your addon's own.

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.session():current():ui():match("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")
end)
```

## The other two kinds of file

| Kind | Door |
|---|---|
| Files you ship (an image, a font, a model, a data file) | [`hafen.asset`](../api/asset/README.md), relative to your folder; yours to read, not to write. |
| Data from elsewhere | [`hafen.http`](../api/http.md) or a [`hafen.websocket`](../api/websocket.md) connection, each needing a host allowlist and the user's approval, landing in a var if it should survive the session. |

Neither is a general file system: an addon reads what it ships and writes its own file.

**Next:** [hotkeys, commands and settings](hotkeys-and-commands.md) — letting the user drive what you have built.

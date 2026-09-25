# Saved Data

Anything your addon keeps between sessions goes in [your addon's file](../api/store/README.md), one per addon for the whole client. A var is a Lua table you name at `var`, restored when you first name it and written back for you: the shape for settings. A table you declare holds typed rows looked up by key or clause: the shape for a record. A statement is SQL. Start with a var. Move to a table when the var would grow.

```lua
hafen.session():current():store():var("settings").window = { x = 40, y = 200 }   -- that character's own
hafen.store():var("seen").lastLogin = os.time()                                   -- your addon's own
```

---

## Name it, and it exists

| Rule | Detail |
|---|---|
| The door is the scope | A var through [a session](../api/session.md) is that character's own. Through `hafen.store()` it is your addon's own, one for the whole client. The same name through the two doors is two vars. |
| Always a usable table | Empty when nothing is saved yet, so nothing to create and no `nil` check. A misspelt name is an empty var. The table object never changes (a restore refills it in place), so a cached local stays valid ([vars](../api/store/vars.md)). |

## Read it at the right moment

| Scope | Readable from |
|---|---|
| Your addon's own | Your file bodies and `Load`. |
| Per character | That session's `SessionEnteredWorld` onwards. |

```lua
local window = hafen.ui():window():title("Scout"):size(200, 120)
hafen.event():on("SessionEnteredWorld", function(session)
  local saved_position = session:store():var("settings").window
  if saved_position then window:position(saved_position.x, saved_position.y) end
end)
```

| Rule | Detail |
|---|---|
| In `Load` a character's table is empty | Not an error. The bug that looks like "my settings do not load". |
| Every character has its own | Your addon is loaded once for a client holding several characters. Each session keeps its own tables. `session:store()` answers about the character it plays whether or not you look at it. A table cached from one session stays that character's. A session with no character (ended, or not yet in the world) raises. The [session events](../api/event/bus/lifecycle.md#sessions) say when. |

## Store data, not objects

The tables are written as JSON: tables, strings, numbers and booleans survive. A function or a handle comes back as a placeholder string. Keys become strings unless the table is a plain `1..n` array. A `nil` value is an absent key. Keep the description of a thing and rebuild the live objects on load. A colour is three numbers, a layout a table of positions, a chosen action [a resource name](../api/ui/custom.md#drop-makes-a-widget-a-drop-target). Anything positional is a [Position](../api/position.md), since a raw `x, y` is meaningless next session. A value belongs in the file of the addon it describes: a [library](libraries.md) keeps its own state and lends functions, never its file.

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

A row is in the file when `:put` returns. Where a character's rows are theirs alone, the character is a column you declare. One `SELECT` through a [statement](../api/store/statements.md) reads what every character saw.

## When it is written

| Rule | Detail |
|---|---|
| Vars | Flushed on a timer and when the client quits (an ordinary quit loses nothing, a crash at most the last half-minute). Your addon's own again on disable or reload. A character's when the session ends or picks another character. Tabbing writes nothing and loses nothing. |
| Rows | In the file when the call returns. |
| `flush()` | `session:store():flush()` and `hafen.store():flush()` write their scope now: for a change that must not be lost. Either refuses a value a var cannot hold, naming where it sits. That is the fastest way to find out you stored the widget instead of its place. |
| A row the client cannot parse | Leaves that var empty and logs it. That gives default settings instead of an addon that does not start. The scope is not written back until a load succeeds. |

## Where a window sits is saved for you

[`widget:remember(name)`](../api/ui/native.md#remembering-where-the-user-put-it-unprotected) keeps a widget's place and box under a name of yours. It puts them back when called, and saves them again in the client's own file each time the user moves the thing. No var, no table, no handler. Per character, so it belongs in `SessionEnteredWorld`. The character is the one whose window it is, while a window you built is your addon's own. Pass a store to choose: `widget:remember(name, hafen.store())` keeps one place every character shares.

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.session():current():ui():match("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")
end)
```

## The other kinds of file

| Kind | Door |
|---|---|
| Files you ship (an image, a font, a model, a data file) | [`hafen.asset`](../api/asset/README.md), relative to your folder. Yours to read, not to write. |
| Data from elsewhere | [`hafen.http`](../api/http.md) or a [`hafen.websocket`](../api/websocket.md) connection, each needing a host allowlist and the user's approval, landing in a var if it should survive the session. |

Neither is a general file system: an addon reads what it ships and writes its own file.

**Next:** [hotkeys, commands and settings](hotkeys-and-commands.md) — letting the user drive what you have built.

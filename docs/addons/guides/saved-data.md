# Saved data

Anything your addon should still know next week goes in [your addon's file](../api/store/README.md), one per
addon for the whole client, and it takes three shapes. A **document** is a Lua table you name at `get`,
which the client restores when you first name it and writes back for you: settings. A **table** you declare
holds rows, typed, looked up by key or by clause: a record. A **statement** is SQL, for what only SQL says.
Start with a document; move to a table the day the document would grow.

## Name it, and it exists

```lua
hafen.session():current():store():get("settings").window = { x = 40, y = 200 }
hafen.store():get("seen").lastLogin = os.time()
```

The door is the scope. A document reached through [a session](../api/session.md) is **that character's
own**; one reached through `hafen.store()` is your addon's own, one for the whole client whichever account
or character is up. That is the whole of the difference: nothing declares a scope, and the same name through
the two doors is two documents.

A name is always a usable table, empty when there is nothing saved yet, so there is nothing to create and
no `nil` check to write — and a misspelt name is an empty document, since there is nothing to hold it
against. The table object itself never changes — a restore refills it in place — so a local reference you
cache stays valid. See [documents](../api/store/documents.md) for the whole surface.

## Read it at the right moment

The two scopes become readable at different times, because the client does not know which character you
are until you are in the world.

| Scope | Readable from |
|---|---|
| your addon's own | your file bodies and `Load` |
| per character | that session's `SessionEnteredWorld` onwards |

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  local pos = s:store():get("settings").window
  if pos then window:position(pos.x, pos.y) end
end)
```

Reading a character's table in `Load` is not an error; it is simply empty, which is the bug that looks like
"my settings do not load".

**Every character has its own.** Your addon is loaded once for the client, which can have several
characters logged in at once, and each of those sessions keeps its own set of per-character tables. So
`s:store()` answers about the character that session is playing whether or not you are looking at it, two
characters write two sets of rows, and a table you cached from one session stays that character's. What raises
is a session with no character to have variables for — one that has ended, and one that has not reached the
world yet; the [session events](../api/event/bus/lifecycle.md#sessions) are how you hear about both.

## Store data, not objects

The tables are written as JSON, so tables, strings, numbers and booleans survive and nothing else does. A
function or a handle comes back as a placeholder string. Keys become strings unless the table is a plain
`1..n` array, and a `nil` value is just an absent key.

So keep the *description* of a thing rather than the thing: a colour is three numbers, a layout is a table
of positions, a chosen action is [a resource name](../api/ui/custom.md#drop-makes-a-widget-a-drop-target)
you can draw again. Rebuild the live objects from that on load.

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  for _, prop in ipairs(s:store():get("settings").props or {}) do
    local p = s:world():position(prop.at)          -- :x() is nil until that grid is reachable
    if p then hafen.virtual():ghost():add(prop.res, p) end
  end
end)
```

That example is the general shape of saving anything positional: a raw `x, y` is meaningless next session,
so store a [Position](../api/position.md) instead.

## A record is a table

A document is held whole and written whole, so a thousand map nodes in one are a thousand entries serialised
at every save. Declare a [table](../api/store/tables.md) for them instead: columns, a key and an index, and
rows that go in and come out typed.

```lua
local nodes = hafen.store():table("nodes")
  :column("grid", "text"):column("x", "integer"):column("y", "integer"):column("kind", "text")
  :key("grid", "x", "y"):index("kind"):create()

nodes:put{ grid = "g1", x = 4, y = 9, kind = "fir" }              -- one row in the file, now
for _, n in ipairs(nodes:list("WHERE kind = ?", "fir")) do
  -- your code here
end
```

A row is in the file when `:put` returns, so there is nothing to flush; and where a character's rows are
theirs alone, the character is a column you declare — the file is one for every character, so what all of
them saw is one `SELECT` away through a [statement](../api/store/statements.md).

## When it is written

A document's changes are flushed on a timer, and again when the client quits, so an ordinary quit loses
nothing and a crash loses at most the last half-minute. Your addon's own documents are written again when
your addon is disabled or reloaded; a character's are written when the session holding them **ends** or
picks another character, because that is the last moment their data is the data in those tables. A row a
table or a statement writes waits for none of this: it is in the file when the call returns. Tabbing between
characters writes nothing and loses nothing — each session keeps its own the whole time.

`s:store():flush()` and `hafen.store():flush()` each force a write of their own scope now, which is worth
doing after a change the user would be annoyed to lose and unnecessary the rest of the time. Either refuses a
value a document cannot hold, naming where in your table it sits, which is the fastest way to find out that
you stored the widget instead
of its place.

A row the client cannot parse leaves that document empty and logs the failure rather than raising it: your
addon starts with default settings instead of not starting, and that scope is not written back until a
load succeeds.

## Where a window sits is saved for you

One kind of saved data needs none of the above.
[`w:remember(name)`](../api/ui/native.md#remembering-where-the-user-put-it-unprotected) keeps a widget's
place and box under a name of yours, puts them back the moment you call it, and saves them again after
every time the user moves the thing:

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.session():current():ui():match("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")
end)
```

There is no document, no table and no handler, because every addon that saved a layout by hand wrote
the same ten lines of packing a position into a table and unpacking it on load. It is per character, like
the tables above, which is why it belongs in `SessionEnteredWorld` for the same reason they do — and the
character is the one **on screen**, because a window stands over whichever session you are looking at.

## The other two kinds of file

- **Files you ship** — an image, a font, a model, a data file — are read with
  [`hafen.asset`](../api/asset/README.md), relative to your own folder. They are yours to read, not to write.
- **Data from elsewhere** comes through [`hafen.http`](../api/http.md) or a
  [`hafen.websocket`](../api/websocket.md) connection, each needing a host allowlist in the manifest and the
  user's approval of it, and lands in a document if you want it to survive the session.

Neither one gives you a general file system: an addon reads what it ships and writes its own file, and that
is the whole of it.

**Next:** [hotkeys, commands and settings](hotkeys-and-commands.md) — letting the user drive what you
have built.

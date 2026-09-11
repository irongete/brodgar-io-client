# Saved data

Anything your addon should still know next week goes in a **saved variable**: a Lua table you declare in the
manifest, which the engine restores when you load and writes back to disk for you. There is no file to open
and no format to choose.

## Declare it, then use it

```json
"saved_variables": ["settings", { "name": "seen", "scope": "account" }]
```

```lua
hafen.session():current():store():get("settings").window = { x = 40, y = 200 }
hafen.store():get("seen").lastLogin = os.time()
```

A bare name is **per character**; the object form with `"scope": "account"` is shared by all your characters
on the account. That is also the whole of the difference in how you reach one: a character's saved variables
are that character's own folder, so you name [the session](../api/session.md) they belong to, and an
account's are your addon's one file, so you name nobody. Ask for either through the other's door and you get
an error naming the right one.

A declared name is always a usable table, empty when there is nothing saved yet, so there is nothing to
create and no `nil` check to write. The table object itself never changes — a restore refills it in place —
so a local reference you cache stays valid. See [`hafen.store`](../api/store.md) for the whole surface.

## Read it at the right moment

The two scopes become readable at different times, because the client does not know which character you
are until you are in the world.

| Scope | Readable from |
|---|---|
| account | your file bodies and `Load` |
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
characters write two folders, and a table you cached from one session stays that character's. What raises
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

## When it is written

Changes are flushed on a timer, and again when the client quits, so an ordinary quit loses nothing and a
crash loses at most the last half-minute. Your account tables are written again when your addon is disabled
or reloaded; a character's own tables are written when the session holding them **ends** or picks another
character, because that is the last moment their data is the data in those tables. Tabbing between
characters writes nothing and loses nothing — each session keeps its own the whole time.

`s:store():flush()` and `hafen.store():flush()` each force a write of their own scope now, which is worth
doing after a change the user would be annoyed to lose and unnecessary the rest of the time. Either refuses
a value a saved variable cannot hold,
naming where in your table it sits, which is the fastest way to find out that you stored the widget instead
of its place.

A file the engine cannot parse leaves your tables empty and logs the failure rather than raising it: your
addon starts with default settings instead of not starting.

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

There is no declaration, no table and no handler, because every addon that saved a layout by hand wrote
the same ten lines of packing a position into a table and unpacking it on load. It is per character, like
the tables above, which is why it belongs in `SessionEnteredWorld` for the same reason they do — and the
character is the one **on screen**, because a window stands over whichever session you are looking at.

## The other two kinds of file

- **Files you ship** — an image, a font, a model, a data file — are read with
  [`hafen.asset`](../api/asset/README.md), relative to your own folder. They are yours to read, not to write.
- **Data from elsewhere** comes through [`hafen.http`](../api/http.md), which needs a host allowlist in the
  manifest and the user's approval of it, and lands in a saved variable if you want it to survive the session.

Neither one gives you a general file system: an addon reads what it ships and writes what it declared, and
that is the whole of it.

**Next:** [hotkeys, commands and settings](hotkeys-and-commands.md) — letting the user drive what you
have built.

# Saved data

Anything your addon should still know next week goes in a **saved variable**: a Lua table you declare in
the manifest, which the engine restores when you load and writes back to disk for you. There is no file to
open and no format to choose.

## Declare it, then use it

```json
"saved_variables": ["settings", { "name": "seen", "scope": "account" }]
```

```lua
hafen.store():get("settings").window = { x = 40, y = 200 }
hafen.store():get("seen").lastLogin  = os.time()
```

A bare name is **per character**; the object form with `"scope": "account"` is shared by all your
characters on the account. A declared name is always a usable table, empty when there is nothing saved yet,
so there is nothing to create and no `nil` check to write. The table object itself never changes — a
restore refills it in place — so a local reference you cache stays valid. See
[`hafen.store`](../api/store.md) for the whole surface.

## Read it at the right moment

The two scopes become readable at different times, because the client does not know which character you
are until you are in the world.

| Scope | Readable from |
|---|---|
| account | your file bodies and `Load` |
| per character | `SessionEnteredWorld` onwards |

```lua
hafen.event():on("SessionEnteredWorld", function()
  local pos = hafen.store():get("settings").window
  if pos then window:position(pos.x, pos.y) end
end)
```

Reading a per-character table in `Load` is not an error; it is simply empty, which is the bug that looks
like "my settings do not load".

## Store data, not objects

The tables are written as JSON, so tables, strings, numbers and booleans survive and nothing else does. A
function or a handle comes back as a placeholder string. Keys become strings unless the table is a plain
`1..n` array, and a `nil` value is just an absent key.

So keep the *description* of a thing rather than the thing: a colour is three numbers, a layout is a table
of positions, a chosen action is [a resource name](../api/ui/custom.md#drop-makes-a-widget-a-drop-target)
you can draw again. Rebuild the live objects from that on load.

```lua
hafen.event():on("SessionEnteredWorld", function()
  for _, prop in ipairs(hafen.store():get("settings").props or {}) do
    local p = hafen.world():position(prop.at)      -- :x() is nil until that grid is reachable
    if p then hafen.vr():ghost():add(prop.res, p) end
  end
end)
```

That example is the general shape of saving anything positional: a raw `x, y` is meaningless next session,
so store a [Position](../api/world.md#the-position-type) instead.

## When it is written

Changes are flushed on a timer, and again when your addon is disabled or reloaded and when the session
ends — so an ordinary quit loses nothing. `hafen.store():flush()` forces a write now, which is worth doing
after a change the user would be annoyed to lose and unnecessary the rest of the time.

A file the engine cannot parse leaves your tables empty and logs the failure rather than raising it: your
addon starts with default settings instead of not starting.

## Where a window sits is saved for you

One kind of saved data needs none of the above.
[`w:remember(name)`](../api/ui/native.md#remembering-where-the-user-put-it-unprotected) keeps a widget's
place and box under a name of yours, puts them back the moment you call it, and saves them again after
every time the user moves the thing:

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.ui():find("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")
end)
```

There is no declaration, no table and no handler, because every addon that saved a layout by hand wrote
the same ten lines of packing a position into a table and unpacking it on load. It is per character, like
the tables above, which is why it belongs in `SessionEnteredWorld` for the same reason they do.

## The other two kinds of file

- **Files you ship** — an image, a font, a model, a data file — are read with
  [`hafen.asset`](../api/asset.md), relative to your own folder. They are yours to read, not to write.
- **Data from elsewhere** comes through [`hafen.http`](../api/http.md), which needs a declared host
  allowlist in the manifest, and lands in a saved variable if you want it to survive the session.

Neither one gives you a general file system: an addon reads what it ships and writes what it declared, and
that is the whole of it.

**Next:** [hotkeys and commands](hotkeys-and-commands.md) — letting the user drive what you have built.

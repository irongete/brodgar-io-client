# Saved data

Anything your addon should still know next week goes in a **saved variable**: a Lua table you declare in
the manifest, which the engine restores when you load and writes back to disk for you. There is no file to
open and no format to choose.

## Declare it, then use it

```json
"saved_variables": ["settings", { "name": "seen", "scope": "account" }]
```

```lua
hafen.store.settings.window = { x = 40, y = 200 }
hafen.store.seen.lastLogin  = os.time()
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
| account | your file bodies and `OnLoad` |
| per character | `OnEnterWorld` onwards |

```lua
hafen.event():on("OnEnterWorld", function()
  local pos = hafen.store.settings.window
  if pos then window:pos(pos.x, pos.y) end
end)
```

Reading a per-character table in `OnLoad` is not an error; it is simply empty, which is the bug that looks
like "my settings do not load".

## Store data, not objects

The tables are written as JSON, so tables, strings, numbers and booleans survive and nothing else does. A
function or a handle comes back as a placeholder string. Keys become strings unless the table is a plain
`1..n` array, and a `nil` value is just an absent key.

So keep the *description* of a thing rather than the thing: a colour is three numbers, a layout is a table
of positions, a chosen action is [a resource name](../api/ui/custom.md#ondrop-makes-a-widget-a-drop-target)
you can draw again. Rebuild the live objects from that on load.

```lua
hafen.event():on("OnEnterWorld", function()
  for _, p in ipairs(hafen.store.settings.props or {}) do
    local w = hafen.world.fromGridPos(p.anchor)                 -- nil until that grid streams in
    if w then hafen.ghost.new{ res = p.res, x = w.x, y = w.y } end
  end
end)
```

That example is the general shape of saving anything positional: a raw `x, y` is meaningless next session,
so store the [grid anchor](../api/world.md#saving-a-world-position-across-sessions) instead.

## When it is written

Changes are flushed on a timer, and again when your addon is disabled or reloaded and when the session
ends — so an ordinary quit loses nothing. `hafen.store.flush()` forces a write now, which is worth doing
after a change the user would be annoyed to lose and unnecessary the rest of the time.

A file the engine cannot parse leaves your tables empty and logs the failure rather than raising it: your
addon starts with default settings instead of not starting.

## The other two kinds of file

- **Files you ship** — an image, a font, a model, a data file — are read with
  [`hafen.asset`](../api/asset.md), relative to your own folder. They are yours to read, not to write.
- **Data from elsewhere** comes through [`hafen.http`](../api/http.md), which needs a declared host
  allowlist in the manifest, and lands in a saved variable if you want it to survive the session.

Neither one gives you a general file system: an addon reads what it ships and writes what it declared, and
that is the whole of it.

**Next:** [hotkeys and commands](hotkeys-and-commands.md) — letting the user drive what you have built.

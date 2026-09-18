# Libraries

An addon that exports a table is a library; another addon reads it with `hafen.client():addons():get(id):api()`. This page writes one, `toast`, and one addon that uses it. The verbs are in [addons and libraries](../api/client/addons.md).

## Writing one

A library is an ordinary addon: a folder, a manifest, files. What makes it a library is one call in its file body.

```json
{ "id": "toast", "name": "Toast", "version": "1.0.0", "author": "you", "api_version": "1.0", "files": ["toast.lua"],
  "description": "Notices at the top of the screen, for other addons to show" }
```

```lua
-- toast.lua
local open = 0

local function show(text)
  open = open + 1
  local box = hafen.ui():widget():size(240, 28):position(400, 40 + open * 32)
  hafen.ui():label():text(text):parent(box):position(8, 6)
  hafen.timer():after(3, function() box:destroy(); open = open - 1 end)
  return open
end

hafen.client():addons():export({
  show  = show,
  count = function() return open end,
})
```

| Rule | Why |
|---|---|
| Export in the file body | Every addon that names you in a dependency list runs after you, so your export is there before their first line. |
| Export functions, not state | The export is copied once, at the call; a number in it never changes for the reader. `count()` answers a value, `count = open` would not. |
| Return values, never handles | The box `show` builds is yours; it cannot cross. Answer an id, a table, a function. |
| Your code runs as you | Under your permissions, your store, your budget — whoever calls. A library that walks needs `player.move` in its own manifest, and the user consents to it when enabling the library. |
| A breaking change is a new id | Inside `toast`, only add. Rename or reshape a function and publish `toast2`; consumers move when they choose. |
| Data stays with its owner | Your store holds your own state and what is shared by nature (an index every consumer feeds). A consumer's settings go in the consumer's `hafen.store()` — lend it functions, not files. |

## Using one

```json
{ "id": "farm-helper", "version": "0.3.0", "api_version": "1.0", "files": ["main.lua"],
  "optional_dependencies": ["toast>=1.0.0"] }
```

```lua
-- main.lua
local toast = hafen.client():addons():get("toast")     -- a handle, installed or not

local function notify(text)
  local api = toast:api()                              -- the export, or nil
  if api then api.show(text) else hafen.log():write(text) end
end

hafen.console():on("hello", function(args)
  notify("Hello " .. (args[1] or ""))
end)
```

| Choice | Effect |
|---|---|
| `optional_dependencies` | `toast` runs before you when it is installed; nothing happens when it is not. Ask `:api()` when you need it. |
| `dependencies` | You do not run without it: `error: needs toast, which is not installed` on your row. Use it when a missing library means your addon means nothing. |
| `toast>=1.2.0` | The version you need. Below it a hard dependency is an error and an optional one answers `nil`. |
| A callback | A function you pass (`api.on("click", function() hafen.log():write("clicked") end)`, when a library offers one) runs as you, through the client, when the library calls it. |

## What happens

| Moment | Detail |
|---|---|
| Load | `toast` runs, exports; `farm-helper` runs after it. |
| `:hello Ada` | `farm-helper` calls `api.show`; the notice is built inside `toast`, with `toast`'s budget and consent. |
| `toast` removed | The next reload: `farm-helper` still loads, `:api()` is `nil`, `notify` writes to the log. |
| `toast` stopped by the watchdog | `farm-helper` keeps running; its `:api()` is `nil` from then on, a kept copy's functions refuse `toast.show: toast is disabled`. A hard dependant would stop with it. |

## See Also

- [Addons and libraries](../api/client/addons.md) — every verb, and what crosses.
- [The manifest](../manifest.md) — the two dependency lists.
- [Saved data](saved-data.md) — whose file a value belongs in.
- [Permissions](permissions.md) — whose consent a call runs under.

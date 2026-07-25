# hafen.key — global hotkeys

Bind a remappable, persisted global hotkey that runs a Lua handler on press.

| Function | Returns | Description |
|---|---|---|
| `hafen.key.bind(name, defaultKey, fn)` | [`{ :remove(), :key() }`](#handle) | bind a hotkey named `name` to run `fn()` on press |

- `name` — an addon-local binding name (registered as `addon/<id>/<name>`, so it can't collide). It
  appears under a section named after your addon in **Options → Keybindings**, where the user can
  remap it.
- `defaultKey` — a key string like `"F5"`, `"Ctrl+M"`, or `"Shift+Alt+Left"`, or `nil` / `"None"` for
  unbound by default (the user assigns it later). Modifiers: `Ctrl`/`Shift`/`Alt` (aliases accepted).
- `fn` — runs when the key is pressed.

### Handle

| Method | Description |
|---|---|
| `:remove()` | unbind (auto on reload/disable) |
| `:key()` | the current key's display name |

```lua
hafen.key.bind("toggle", "Ctrl+H", function() win:visible() and win:hide() or win:show() end)
```

Register any time — no live target is needed, so the file body is fine. The hotkey fires only when no
focused widget consumed the keypress first (so **not** while typing in chat) and no client binding on
the same key took it. A user's remapping persists across restarts and reloads; the `defaultKey` applies
only when the binding is first created.

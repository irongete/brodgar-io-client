# hafen.client: keybindings

`hafen.client():options():keybindings()` is the client's hotkey registry: declare your addon's hotkeys, and
read or remap any binding, yours or the client's own. Unprotected.

```lua
local keys = hafen.client():options():keybindings()

local hot = keys:on("toggle", function()
  hafen.log():write("toggled")
end)
```

| Method | Returns | Description |
|---|---|---|
| `on(name, fn)` | a [subscription](../event/README.md#subscribe) | declare a hotkey owned by your addon; `fn` runs when it fires |
| `key(name)` | string \| nil | current key as a display string (`"Ctrl+M"`), or `nil` if unbound or unknown |
| `key(name, k)` | the handle | remap a binding; `"None"` unbinds it |
| `list()` | `{ [id] = key }` | every binding in the client, unbound ones reading `"None"` |

One name reads a binding's key and writes it: the argument says which you meant, so there is no
`get`/`set` pair here any more than anywhere else. The write half of `key` returns the handle, so writes
chain.

A hotkey is a subscription like every other `:on` in the API. What `on` gives back is a `Sub`:
`hot:key()` is the name you declared it under and `hot:off()` drops it, which is how you stop a hotkey
while your addon keeps running. The user's assignment survives that ending — the binding belongs to the
client, and only the handler behind it is yours.

## Addon hotkeys start unbound

`on` takes **no default key**. Your addon names an action; **the user assigns the key** in
Options ▸ Keybindings, where every addon that declared a hotkey gets its own section, listed by addon
name. That is the only model consistent with the client's one-key-one-action exclusivity: an addon-chosen
default could not claim a key already in use, so it would lose the collision and leave you with a hotkey
that never fires.

So **advertise a suggested key in your README instead of claiming one**:

> *Suggested key: `Ctrl+H` — assign it in Options ▸ Keybindings ▸ myaddon.*

The user's assignment is persisted by the client and survives `:reload` and restarts; declaring the same
name again after a reload picks the existing binding back up.

## Names

Your own hotkeys are namespaced to your addon, so `on("test", fn)` and `key("test")` refer to the
same binding without you ever spelling your addon id. A name that is not one of yours falls back to the
client's own registry id — that is how you reach a built-in hotkey, `key("inv")` or `key("inv", "Ctrl+I")`.
Your scope is tried first, so a client binding can never shadow yours.

`list()` uses the **full registry ids**, so your hotkeys appear there as `addon/<your-addon-id>/<name>`.

A `key(name, k)` write on a name that matches no binding is an error; a `key(name)` read of one is plain
`nil`.

## Key strings

A key is the last `+`-separated token, with optional modifiers before it: `Ctrl`/`Control`/`Ctl`, `Shift`,
`Alt`/`Meta`, case-insensitive. Named keys are `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`, `Esc`,
`Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`, `Right`;
anything else is a single character. `"None"` means unbound.

```lua
"F5"   "Ctrl+M"   "Shift+Alt+Left"   "None"
```

Modifier matching is exact: `"M"` fires only on a bare `M`, never on `Ctrl+M`.

## Example

```lua
local keys = hafen.client():options():keybindings()

local hot = keys:on("toggle", function() hafen.log():write("toggled") end)

hafen.log():write("my key: " .. tostring(keys:key("toggle")))     -- nil until the user assigns one
hafen.log():write("inventory: " .. tostring(keys:key("inv")))     -- a client binding, e.g. "Tab"

for id, key in pairs(keys:list()) do
  if key ~= "None" then hafen.log():write(id .. " = " .. key) end
end
```

Hotkeys are torn down with your addon on reload or disable, so there is nothing to end in `Disable`. Use
`hot:off()` only to drop a hotkey while your addon keeps running.

> A global hotkey runs through the client's binding registry, after the client's own bindings. To
> intercept a mouse event before the widget under it sees it, subscribe on the widget instead — see
> [subscribing](../ui/widget.md#subscribing). Keyboard input is not a widget option, so a hotkey is still
> the only door onto a key.

## See also

- [`hafen.client():options()`](README.md) — the rest of the settings surface
- [the Widget object](../ui/widget.md#subscribing) — intercepting a mouse event before the widget does
- [`hafen.slash`](../slash.md) — a console command, the other way an addon is invoked by hand
- [`hafen.event`](../event/README.md) — the `Sub` a hotkey hands back, and every other subscription

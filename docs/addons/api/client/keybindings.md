# hafen.client: keybindings

`hafen.client():options():keybindings()` is the client's hotkey registry: declare your addon's hotkeys, and
read, remap or reset any binding, yours or the client's own. Declaring and reading are unprotected; a
**remap** needs [`client.settings`](../../guides/permissions.md).

```lua
local keys = hafen.client():options():keybindings()

local hot = keys:on("toggle", function()
  hafen.log():write("toggled")
end)
```

| Method | Returns | Description |
|---|---|---|
| `on(name, fn)` | a [subscription](../event/README.md#subscribe) | declare a hotkey owned by your addon; `fn` runs when it fires |
| `binding()` | a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of [Binding](#the-binding-object) | every binding the client knows: yours, other addons' and its own |

A hotkey is a subscription like every other `:on` in the API. What `on` gives back is a `Sub`:
`hot:key()` is the name you declared it under and `hot:off()` drops it, which is how you stop a hotkey
while your addon keeps running. The user's assignment survives that ending — the binding belongs to the
client, and only the handler behind it is yours.

`binding()` takes no arguments: it **is** the collection, and everything about a key — reading it, writing
it, putting it back — lives on the member rather than on this handle.

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

## The binding collection

`keys:binding()` holds every binding the client currently knows — your hotkeys, other addons' and the
client's own — ordered by id. It carries the
[collection verbs](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many):
`:list(filter)`, `:count(filter)`, `:find(filter)` and `:get(id)`, and a string filter is a substring
test on the id.

```lua
for _, b in ipairs(keys:binding():list()) do
  if b:key() then hafen.log():write(b:id() .. " = " .. b:key()) end
end
```

**`:get(id)` always hands you a Binding**, and `b:exists()` is the question a `nil` would have answered.
The registry fills in as the client's own windows are built and as addons declare their hotkeys, so an id
you address in your file body is often not there yet and is there after login — the handle you took is the
same object either way.

Your own hotkeys are namespaced to your addon, so `on("test", fn)` and `binding():get("test")` name the
same binding without you ever spelling your addon id. A name that is not one of yours is the registry id as
you wrote it — that is how you reach a built-in hotkey, `binding():get("inv")`. Your scope is tried first,
so a client binding can never shadow yours. `b:id()` is always the **full** registry id, so your own read
back as `addon/<your-addon-id>/<name>`.

## The binding object

| Method | Returns | Description |
|---|---|---|
| `id()` | string | the registry id, its identity |
| `key()` | string \| nil | the key it fires on, as a display string; `nil` when it is unbound |
| `key(k)` | the binding | assign a key; `"None"` unbinds it |
| `key(nil)` | the binding | put it back on the client's own default |
| `default()` | string \| nil | the key the client gives it, `nil` where that default is unbound |
| `assigned()` | boolean | whether the current key is the user's choice or the client's default |
| `exists()` | boolean | whether anything has declared this id yet |
| `info()` | table \| nil | `{id=, key=, default=, assigned=}`, `nil` for an id nothing has declared |

Writing a key needs the [`client.settings` permission](../../guides/permissions.md), like every other
setting here, and it persists exactly as the same edit made in Options ▸ Keybindings does — which is why it
is keyed: `binding:key("Ctrl+I")` on `inv` takes the **client's own** inventory key, and the user has to undo
that by hand. A write on a binding nothing has declared is an error; a read of one answers `nil`, except
`id()`, which is what you addressed it by, and the two booleans, which are `false`.

**A binding has three states, and two of them read as no key.** It is on the client's default, or the user
has assigned a key, or the user has unbound it — and `key()` collapses the first and the last to `nil`, so
`assigned()` is what tells them apart. That is also why `key(nil)` exists: without it, an addon that saved a
key and wrote it back would turn a default into an assignment it could never take off again.

```lua
local b = keys:binding():get("toggle")

-- the user's choice in a form that writes straight back; nil is "on the client's default"
local saved = b:assigned() and (b:key() or "None") or nil

b:key(saved)                              -- restores all three states, the default included
```

> Assigning a key takes it off every other binding that fires on it, and there is no undo for the
> binding you took it from. Reverting runs no such pass, so putting two bindings back on defaults that
> share a key leaves both firing.

## Key strings

A key is the last `+`-separated token, with optional modifiers before it: `Ctrl`/`Control`/`Ctl`, `Shift`,
`Alt`/`Meta`, case-insensitive. Named keys are `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`, `Esc`,
`Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`, `Right`;
anything else is a single character. `"None"` means unbound.

```lua
"F5"   "Ctrl+M"   "Shift+Alt+Left"   "None"
```

A key you read back is spelled the same way, with the modifiers in the order `Shift`, `Ctrl`, `Alt`.
Modifier matching is exact: `"M"` fires only on a bare `M`, never on `Ctrl+M`.

## Example

```lua
local keys = hafen.client():options():keybindings()

local hot = keys:on("toggle", function() hafen.log():write("toggled") end)

local mine = keys:binding():get("toggle")
hafen.log():write("my key: " .. tostring(mine:key()))          -- nil until the user assigns one

local inv = keys:binding():get("inv")
if inv:exists() then
  hafen.log():write("inventory: " .. tostring(inv:key()) .. ", assigned: " .. tostring(inv:assigned()))
end

hafen.log():write("bindings: " .. keys:binding():count())
```

Hotkeys are torn down with your addon on reload or disable, so there is nothing to end in `Disable`. Use
`hot:off()` only to drop a hotkey while your addon keeps running. A key the user assigned is the client's
and stays.

> A global hotkey runs through the client's binding registry, after the client's own bindings. To
> intercept a mouse event before the widget under it sees it, subscribe on the widget instead — see
> [subscribing](../ui/widget.md#subscribing). Keyboard input is not a widget option, so a hotkey is still
> the only door onto a key.

## See also

- [`hafen.client():options()`](README.md) — the rest of the settings surface
- [conventions](../conventions.md) — the collection verbs, and what `:get` answers for a key nothing holds
- [the Widget object](../ui/widget.md#subscribing) — intercepting a mouse event before the widget does
- [`hafen.slash`](../slash.md) — a console command, the other way an addon is invoked by hand
- [`hafen.event`](../event/README.md) — the `Sub` a hotkey hands back, and every other subscription

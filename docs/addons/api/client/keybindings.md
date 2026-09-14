# hafen.client: Keybindings

`hafen.client():options():keybindings()` manages global hotkey registrations, keyboard state polling, and key remapping.

```lua
local keybindings = hafen.client():options():keybindings()

-- Declare an addon hotkey action
local hotkey_subscription = keybindings:on("toggle_radar", function()
  hafen.log():write("Toggle radar pressed")
end)
```

---

## Permissions

- **Declaring** and **reading** keybindings is **unprotected**.
- **Remapping** keys programmatically (`binding:key(...)`) requires the [`client.settings`](../../guides/permissions.md) permission in `manifest.json`.

---

## Declaring Addon Hotkeys

### `keybindings:on(name, callback)`

Registers a named action belonging to this addon. Returns a `Subscription` handle.

```lua
local keybindings = hafen.client():options():keybindings()

local toggle_subscription = keybindings:on("toggle_hud", function()
  -- Handler executes on UI thread when hotkey is triggered
  hafen.log():write("HUD toggled")
end)

-- To unregister during runtime:
-- toggle_subscription:off()
```

- **Unbound by default**: Addon hotkeys do not claim default keys in code to prevent collisions. Users assign keys in **Options ▸ Game ▸ Keybindings ▸ [Addon Name]**.
- **Lifecycle**: Subscriptions are automatically cleaned up on addon reload or disable. The user's key assignment persists in client preferences.
- **Consumption**: When an assigned hotkey is pressed, the event is consumed and will not trigger underlying client actions.

---

## The Binding Collection

Access all registered bindings (client-native and addon-defined) via `keybindings:binding()`:

| Method | Returns | Description |
|---|---|---|
| `keybindings:binding():list(filter?)` | `Binding[]` | List all registered keybindings. |
| `keybindings:binding():get(id)` | `Binding` | Retrieve binding by ID. Always returns a `Binding` object. |
| `keybindings:binding():find(filter)` | `Binding \| nil` | Find first matching binding. |
| `keybindings:binding():count(filter?)` | `number` | Count registered bindings. |

```lua
local binding_collection = hafen.client():options():keybindings():binding()

for _, binding in ipairs(binding_collection:list()) do
  if binding:key() then
    hafen.log():write(string.format("%s = %s", binding:id(), binding:key()))
  end
end
```

Addon-declared bindings are automatically namespaced: `binding_collection:get("toggle_hud")` addresses `addon/<addon_id>/toggle_hud`.

---

## The Binding Object

| Method | Returns | Description |
|---|---|---|
| `binding:id()` | `string` | Full registry identifier. |
| `binding:key()` | `string \| nil` | Current key assignment string, or `nil` if unbound. |
| `binding:key(key_string)` | `Binding` | Assign key. `"None"` unbinds. Requires `client.settings`. |
| `binding:key(nil)` | `Binding` | Reset to client default binding. Requires `client.settings`. |
| `binding:default()` | `string \| nil` | Default key assignment, or `nil` if none. |
| `binding:assigned()` | `boolean` | `true` if current key was customized by user or script. |
| `binding:down()` | `boolean` | `true` if the assigned key is physically held down right now. |
| `binding:exists()` | `boolean` | `true` if the binding ID has been registered. |
| `binding:info()` | `table \| nil` | Metadata snapshot: `{id, assigned, down, key?, default?}`. |

---

## Polling Held Keys: `down()`

`binding:down()` queries the instantaneous state of the key. Use this for push-to-talk, continuous movement, or modifier toggles:

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("sprint_action", function() end) -- Register so user can bind it

local sprint_binding = keybindings:binding():get("sprint_action")

hafen.timer():every(0.05, function()
  if sprint_binding:down() then
    -- Key is actively held down
  end
end)
```

Unlike OS key repeat, `down()` accurately reports the physical hold state regardless of desktop repeat delays or other keys being pressed.

---

## Key Format Strings

Key combination strings consist of optional modifier prefixes and a primary key token:

- **Modifiers**: `Ctrl+`, `Shift+`, `Alt+` (case-insensitive)
- **Named keys**: `F1`–`F12`, `Space`, `Enter`, `Tab`, `Esc`, `Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`, `Right`
- **Single characters**: `"A"`, `"1"`, `"M"`, etc.
- **Unbound**: `"None"`

Examples: `"Ctrl+H"`, `"Shift+Alt+M"`, `"F4"`, `"Space"`, `"None"`.

---

## Complete Example

```lua
local keybindings = hafen.client():options():keybindings()

-- Register hotkey action
local show_tracker_sub = keybindings:on("show_tracker", function()
  hafen.log():write("Hotkey triggered!")
end)

-- Query binding status
local tracker_binding = keybindings:binding():get("show_tracker")
if tracker_binding:key() then
  hafen.log():write("Bound to: " .. tracker_binding:key())
else
  hafen.log():write("Action is currently unbound. Assign a key in Options.")
end

-- Inspect a native client binding
local inventory_binding = keybindings:binding():get("inv")
if inventory_binding:exists() then
  hafen.log():write("Inventory key: " .. tostring(inventory_binding:key()))
end
```

---

## See Also

- [`hafen.client():options()`](README.md) — Client settings and options panels.
- [Addon Options](addon.md) — Custom persistent addon preferences.
- [`hafen.console`](../console.md) — Console commands and manual triggers.

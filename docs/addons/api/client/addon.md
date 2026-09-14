# hafen.client: Addon Options

`hafen.client():options():addon()` allows addons to declare persistent user settings and construct custom configuration panels inside the client's **Options ▸ AddOns** menu. All methods in this subsystem are unprotected.

```lua
local addon_options = hafen.client():options():addon()

local show_overlay_option = addon_options:boolean("show-overlay"):default(true):add()

show_overlay_option:on("Changed", function(new_value)
  hafen.log():write("Overlay visible: " .. tostring(new_value))
end)
```

---

## Declaring Options

Declare options in the top-level scope of your addon script. Options persist across addon reloads and game restarts.

### Option Builders

| Builder | Type | Value Stored | Extra Configuration |
|---|---|---|---|
| `addon_options:boolean(name)` | Boolean | `boolean` (`true` \| `false`) | None |
| `addon_options:number(name)` | Number | Whole integer within range | `:range(min, max)` |
| `addon_options:choice(name)` | Choice | `string` from valid choices | `:choices(choice_array)` |
| `addon_options:text(name)` | Text | `string` | None |

### Builder Setters

Chain configuration setters and finish declaration with `:add()`:

| Setter | Applicable To | Description |
|---|---|---|
| `:default(value)` | All builders | Initial value before user modification. Required. |
| `:range(min, max)` | `number` | Inclusive integer bounds (`min < max`). Required for numbers. |
| `:choices(table)` | `choice` | Array of choice strings. Required for choices. |
| `:add()` | All builders | Registers the option and returns the active `Option` handle. |

```lua
local auto_harvest = addon_options:boolean("auto_harvest"):default(false):add()
local scan_radius  = addon_options:number("scan_radius"):range(1, 15):default(5):add()
local sort_mode    = addon_options:choice("sort_mode"):choices({"name", "quality", "quantity"}):default("quality"):add()
local label_text   = addon_options:text("hud_label"):default("Nearby Items"):add()
```

---

## The Option Handle

Calling `:add()` or querying via `:option():get(name)` returns an `Option` handle.

| Method | Returns | Description |
|---|---|---|
| `option:name()` | `string` | Unique identifier of the option. |
| `option:type()` | `string` | `"boolean"`, `"number"`, `"choice"`, or `"text"`. |
| `option:value(value?)` | `any` / `Option` | Reads value (no args) or updates it (1 arg). Chains on write. |
| `option:default()` | `any` | Returns default value. |
| `option:on("Changed", fn)` | `Subscription` | Subscribes to value modifications. Receives `new_value`. |
| `option:info()` | `table` | Metadata snapshot: `{name, type, value, default, min?, max?, choices?}`. |

```lua
scan_radius:value(10) -- Updates value and persists to preferences

local subscription = scan_radius:on("Changed", function(new_value)
  hafen.log():write("Scan radius changed to: " .. tostring(new_value))
end)
```

---

## Addon Options Panel

Register a panel callback to display UI controls in the **Options ▸ AddOns** window:

| Method | Returns | Description |
|---|---|---|
| `addon_options:panel(callback)` | `AddonOptionRegistry` | Registers `callback(root_column)`. Invoked each time the user visits the page. |
| `addon_options:panel()` | `function \| nil` | Returns current registered panel callback. |
| `addon_options:panel(nil)` | `AddonOptionRegistry` | Unregisters panel; removes entry from AddOns list. |

```lua
addon_options:panel(function(root_column)
  root_column:gap(6)

  hafen.ui():label():parent(root_column):text("General Configuration")
  hafen.ui():check():parent(root_column):text("Enable Automation"):bind(auto_harvest)
  
  hafen.ui():label():parent(root_column):text("Scan Range")
  hafen.ui():slider():parent(root_column):size(200):bind(scan_radius)
  
  hafen.ui():dropdown():parent(root_column):size(150):bind(sort_mode)
  hafen.ui():entry():parent(root_column):size(200):bind(label_text)
end)
```

---

## Two-Way Control Binding

Calling `control:bind(option)` synchronizes a UI control with an `Option` handle:

| Control | Bound Option Type | Behavior |
|---|---|---|
| `hafen.ui():check()` | `boolean` | Checkbox toggles boolean value. |
| `hafen.ui():slider()` | `number` | Slider range and position sync to number option. |
| `hafen.ui():dropdown()` | `choice` | Dropdown choices and selection sync to choice option. |
| `hafen.ui():radio()` | `choice` | Radio group options sync to choice option. |
| `hafen.ui():entry()` | `text` | Text input syncs to text option. |

Binding methods on controls:
- `control:bind(option)`: Attaches control to option and updates control to reflect current value.
- `control:bind()`: Returns currently bound `Option` handle (or `nil`).
- `control:bind(nil)`: Detaches control from option.

---

## Option Collection

Query existing options declared by this addon:

```lua
local option_collection = hafen.client():options():addon():option()

for _, option in ipairs(option_collection:list()) do
  hafen.log():write(string.format("Option: %s = %s", option:name(), tostring(option:value())))
end

local existing_option = option_collection:get("scan_radius")
```

---

## Complete Example

```lua
local addon_options = hafen.client():options():addon()

local enabled_option = addon_options:boolean("enabled"):default(true):add()
local volume_option  = addon_options:number("alert_volume"):range(0, 100):default(75):add()
local alert_channel  = addon_options:choice("channel"):choices({"chat", "hud", "audio"}):default("hud"):add()

addon_options:panel(function(root_column)
  root_column:gap(6)

  hafen.ui():check():parent(root_column):text("Enable Alerts"):bind(enabled_option)
  
  hafen.ui():label():parent(root_column):text("Volume:")
  hafen.ui():slider():parent(root_column):size(180):bind(volume_option)
  
  hafen.ui():label():parent(root_column):text("Alert Target:")
  hafen.ui():dropdown():parent(root_column):size(140):bind(alert_channel)
end)

hafen.console():on("resetoptions", function()
  for _, option in ipairs(addon_options:option():list()) do
    option:value(option:default())
  end
  hafen.log():write("All options reset to default.")
end)
```

---

## See Also

- [`hafen.client():options()`](README.md) — Built-in client preference panels.
- [Keybindings](keybindings.md) — Registering custom hotkeys and key remapping.
- [Controls](../ui/controls/README.md) — Standard UI interactive widgets.
- [Columns](../ui/column.md) — Layout containers for option panels.

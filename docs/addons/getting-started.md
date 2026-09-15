# Getting Started

Build your first functional addon in 5 minutes. We will create a small window with a hotkey to toggle it, a timer that counts nearby trees, and state persistence across game sessions.

## 1. Directory Structure

Create a folder named `my_addon` inside the client's `addons/` directory:

```text
addons/
  my_addon/
    manifest.json
    main.lua
```

The directory name (`my_addon`) must match the `id` declared in `manifest.json`.

## 2. Define the Manifest

Create `addons/my_addon/manifest.json`:

```json
{
  "id": "my_addon",
  "name": "My Addon",
  "version": "0.1.0",
  "author": "Developer",
  "description": "My first client addon.",
  "api_version": "1.0",
  "files": ["main.lua"]
}
```

* `id`: Unique identifier (must match folder name).
* `api_version`: Target API version (`"1.0"` for this client).
* `files`: Lua entry points loaded in order.

## 3. Basic Hello World

Write to `addons/my_addon/main.lua`:

```lua
hafen.log():write("my_addon loaded successfully")
```

Open the in-game console by pressing `:`, type `:reload`, and press Enter. You should see `[my_addon] my_addon loaded successfully` in the chat/console.

> **Development Workflow**: Make your code changes, save the file, type `:reload` in the console, and check the output. You do not need to restart the client or log out.

## 4. Reacting to the World Lifecycle

Top-level file code runs when the client loads (at the login screen). To interact with a character, listen for `SessionEnteredWorld`:

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Entered world with character: " .. character_name)
end)
```

## 5. Creating a Custom Window

Create a draggable window and paint custom graphics on its `Draw` event:

```lua
local window_handle = nil
local tree_count = 0

hafen.event():on("SessionEnteredWorld", function(session)
  window_handle = hafen.ui():window()
    :title("Tree Tracker")
    :size(160, 30)
    :position(80, 80)

  window_handle:on("Draw", function(event)
    local graphics = event:g()
    graphics:color(255, 220, 120)
    graphics:text("Nearby trees: " .. tree_count, 8, 8)
  end)
end)
```

## 6. Querying the World with a Timer

World queries should run periodically on a timer rather than on every render frame:

```lua
hafen.timer():every(1, function()
  local current_session = hafen.session():current()
  if current_session then
    tree_count = current_session:world():gob():count("terobjs/tree")
  else
    tree_count = 0
  end
end)
```

## 7. Adding a Keybinding

Register an action under Options ▸ Game ▸ Keybindings:

```lua
hafen.client():options():keybindings():on("toggle_window", function()
  if not window_handle then return end
  window_handle:visible(not window_handle:visible())
end)
```

## 8. Persisting State Across Sessions

Use character variables (`store():var()`) to remember whether the window was open:

```lua
local saved_settings = nil

hafen.event():on("SessionEnteredWorld", function(session)
  -- Existing window creation...
  
  -- Retrieve character-scoped persistent table
  saved_settings = session:store():var("settings")
  if saved_settings.is_open == false then
    window_handle:visible(false)
  end
end)

hafen.client():options():keybindings():on("toggle_window", function()
  if not window_handle then return end
  local next_state = not window_handle:visible()
  window_handle:visible(next_state)
  if saved_settings then
    saved_settings.is_open = next_state
  end
end)
```

---

## Complete `main.lua` Code

```lua
-- addons/my_addon/main.lua

local window_handle = nil
local saved_settings = nil
local tree_count = 0

hafen.log():write("my_addon initializing...")

hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Entered world as: " .. character_name)

  -- Create UI Window
  window_handle = hafen.ui():window()
    :title("Tree Tracker")
    :size(160, 30)
    :position(80, 80)

  window_handle:on("Draw", function(event)
    local graphics = event:g()
    graphics:color(255, 220, 120)
    graphics:text("Nearby trees: " .. tree_count, 8, 8)
  end)

  -- Load persistent settings
  saved_settings = session:store():var("settings")
  if saved_settings.is_open == false then
    window_handle:visible(false)
  end
end)

-- Query nearby trees once per second
hafen.timer():every(1, function()
  local current_session = hafen.session():current()
  if current_session then
    tree_count = current_session:world():gob():count("terobjs/tree")
  else
    tree_count = 0
  end
end)

-- Register hotkey handler
hafen.client():options():keybindings():on("toggle_window", function()
  if not window_handle then return end
  local is_visible = not window_handle:visible()
  window_handle:visible(is_visible)
  if saved_settings then
    saved_settings.is_open = is_visible
  end
end)
```

## Next Steps

- **[Addon Guides](guides/README.md)**: In-depth guides for UI, events, timers, permissions, and networking.
- **[Manifest Reference](manifest.md)**: Explore all available manifest configuration fields.
- **[API Reference](api/README.md)**: Browse all namespaces and methods.

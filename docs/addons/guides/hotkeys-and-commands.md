# Hotkeys and Console Commands

Allow players to control your addon via configurable keybindings and custom console commands.

---

## 1. Configurable Keybindings

Addons register actions by name. Keybindings appear in the game's native **Options ▸ Game ▸ Keybindings** menu under your addon's name, where players can assign keys to them.

```lua
-- Register a keybinding action
hafen.client():options():keybindings():on("toggle_radar", function()
  if my_radar_window then
    local current_visibility = my_radar_window:visible()
    my_radar_window:visible(not current_visibility)
  end
end)
```

* Keybindings start **unbound** until the player configures them in Options.
* If your addon unloads or reloads, registered keybinding actions are cleanly re-bound without losing the user's assigned keys.

---

## 2. In-Game Console Commands

Register custom commands that players can execute from the `:` in-game console:

```lua
-- Simple command: :radar
hafen.console():on("radar", function(argument_string)
  hafen.log():write("Radar command executed with argument: " .. tostring(argument_string))
end)

-- Command with parameters: :setrange 50
hafen.console():on("setrange", function(argument_string)
  local target_range = tonumber(argument_string)
  if target_range then
    my_radar_range = target_range
    hafen.log():write("Detection range set to: " .. target_range)
  else
    hafen.log():write("Usage: :setrange <number>")
  end
end)
```

### Reserved Commands
The following built-in commands cannot be overridden by addons:
* `:reload`
* `:addons`
* `:lua`

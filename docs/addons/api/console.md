# hafen.console: Console Commands

Register custom commands runnable from the in-game `:` prompt, or execute client console commands programmatically.

## Quick Example

```lua
-- Register a custom console command: :scan
hafen.console():on("scan", function(argument_string)
  local current_session = hafen.session():current()
  if not current_session then return end

  local player_count = current_session:world():gob():count("gfx/borka/body")
  hafen.log():write("Players detected: " .. player_count)
end)
```

## Methods on `hafen.console()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:on(command, handler)` | `string, function(arg_string)` | `self` | Registers a custom console command. Handlers receive the raw argument string. |
| `:remove(command)` | `string` | `self` | Unregisters a previously registered console command. |

## Methods on `Session:console()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:run(command_line)` | `string` | `self` | `console.run` | Executes a console command on behalf of the character session. |

## Reserved Commands
Addons cannot override the following built-in engine commands:
* `:reload`
* `:addons`
* `:lua`

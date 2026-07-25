# hafen.slash / hafen.log — console commands & logging

## hafen.log — logging

| Function | Description |
|---|---|
| `hafen.log(msg)` | write `msg` to the console + the per-addon log |

```lua
hafen.log("started")
```

## hafen.slash — console commands

Register a `:name`-style console command, WoW's `SlashCmdList` pattern.

| Function | Returns | Description |
|---|---|---|
| `hafen.slash.register(name, fn)` | [`{ :remove() }`](#handle) | route the console command `:name args…` to `fn(args)` |

`fn(args)` receives `args`, a 1-based table of the whitespace-split words after the command name
(`"quoted words"` group; `\` escapes). Returns a handle with `:remove()`.

```lua
hafen.slash.register("greet", function(args)
  hafen.log("hello, " .. (args[1] or "world"))
end)
-- in the console:  :greet Alice
```

### Handle

| Method | Description |
|---|---|
| `:remove()` | unregister (auto on reload/disable) |

Register any time — the file body is fine. It is reload-safe: editing and `:reload` swaps the handler
with no duplicate or leaked command. Reserved engine names (`lua`, `addons`, `reload`) and names an
existing client command already owns are refused with a clear error.

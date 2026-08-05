# hafen.slash: console commands

Register a `:name`-style console command, so the user can drive your addon by typing at it. This is
WoW's `SlashCmdList` pattern: the addon names a command, the engine routes it, and your function gets
the words that followed. `hafen.slash()` is **ungated** — it adds a way to call your own code.

```lua
hafen.slash():register("greet", function(args)
  hafen.log():write("hello, " .. (args[1] or "world"))
end)
-- in the console:  :greet Alice
```

## Register

| Function | Returns | Description |
|---|---|---|
| `hafen.slash():register(name, fn)` | [handle](#the-command-handle) | route the console command `:name args…` to `fn(args)` |

`name` is a single word: no spaces, and not empty. `fn(args)` receives `args`, a 1-based table of the
whitespace-split words *after* the command name — `"quoted words"` group into one, and `\` escapes the
next character. The command name itself is not in the table.

Register any time; the file body is fine, and there is nothing to wait for. It is reload-safe: editing
your file and running `:reload` swaps the handler with no duplicate and no leaked command.

**When registration fails.** The engine's own commands — `lua`, `addons` and `reload` — are reserved,
and a name an existing client command already owns is refused; both raise an error naming the command.
If another *addon* holds the name, the newest registration wins and takes it over, and the console
records the reassignment — so two addons claiming `:sort` is a first-come, last-served race rather than
an error.

**Where it runs.** The console dispatches on the UI thread, the same thread as everything else your
addon does, so a command never races your own handlers. It runs under the same watchdog and error
isolation: an error in it is logged, not propagated.

## The command handle

| Method | Description |
|---|---|
| `:remove()` | unregister; also done for you on reload or disable |

After the last handler for a name is gone, typing `:name` reports that no addon handles it.

## See also

- [`hafen.log`](log.md) — printing back to the console the command was typed into
- [`hafen.client():options():keybindings()`](client/keybindings.md) — a hotkey, the other way a user
  invokes an addon by hand
- [`hafen.ui`](ui/README.md) — a window, for anything the user does more than occasionally

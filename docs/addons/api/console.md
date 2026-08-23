# hafen.console: commands the user types

Subscribe to a **console command**, so the user can drive your addon by typing at it. That is the
client's own word for the thing: `:` opens the console line, and everything typed there — `:lua`,
`:reload`, `:fs`, yours — is a console command, dispatched by the client's console. If you come from
WoW this is the `SlashCmdList` pattern: the addon names a command, the engine routes it, and your
function gets the words that followed. `hafen.console()` is **unprotected** — it adds a way to call your
own code.

```lua
local cmd = hafen.console():on("greet", function(args)
  hafen.log():write("hello, " .. (args[1] or "world"))
end)
-- in the console:  :greet Alice
-- later:           cmd:off()
```

## Subscribe

| Function | Returns | Description |
|---|---|---|
| `hafen.console():on(name, fn)` | a [subscription](event/README.md#subscribe) | route the console command `:name args…` to `fn(args)` |

`name` is a single word: no spaces, and not empty. `fn(args)` receives `args`, a 1-based table of the
whitespace-split words *after* the command name — `"quoted words"` group into one, and `\` escapes the
next character. The command name itself is not in the table.

A command is a subscription like every other `:on` in the API. What you get back is a `Sub`: `cmd:key()`
is the command name and `cmd:off()` stops it, after which typing `:name` reports that no addon handles
it. Ending it is idempotent, and it is done for you on reload or disable.

Subscribe any time; the file body is fine, and there is nothing to wait for. It is reload-safe: editing
your file and running `:reload` swaps the handler with no duplicate and no leaked command.

**When it fails.** The engine's own commands — `lua`, `addons` and `reload` — are reserved, and a name
an existing client command already owns is refused; both raise an error naming the command. If another
*addon* holds the name, the newest subscription wins and takes it over, and the console records the
reassignment — so two addons claiming `:sort` is a first-come, last-served race rather than an error.

**A command you subscribe to answers from every character.** The name is routed once, for the client
rather than for a login, so with several characters up it runs from whichever one's console you type it
into and goes on running as you tab between them. There is nothing to re-subscribe on a switch.

**Where it runs.** The console dispatches on the UI thread, the same thread as everything else your
addon does, so a command never races your own handlers. It runs under the same watchdog and error
isolation: an error in it is logged, not propagated.

## Read what you registered

`hafen.console()` **is** the
[collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your addon's own
commands, so the section you subscribe through is the section you read back:

| Verb | Gives you |
|---|---|
| `hafen.console():list(filter)` | every command of yours, as an array of `Sub`s |
| `hafen.console():count(filter)` | how many |
| `hafen.console():find(filter)` | the first that matches, or `nil` |
| `hafen.console():get(name)` | the command registered under `name`, or `nil` |

The members are the very `Sub`s `:on` handed you, so `==` finds the one you are holding and `sub:key()`
is its name. A string `filter` is a substring match on that name; a function filter is called with each
`Sub`. Only your addon's commands are here — another addon's are its own, and the client's built-in
commands were never subscriptions.

`:get(name)` answers `nil` for a name you never registered: there is nothing for it to be a handle to,
since the registration *is* the subscription. All four verbs are **unprotected**.

```lua
for _, cmd in ipairs(hafen.console():list()) do
  hafen.log():write(":" .. cmd:key())
end

local greet = hafen.console():get("greet")
if greet then greet:off() end
```

## See also

- [`hafen.event`](event/README.md) — the `Sub` this hands back, and every other subscription in the API
- [`hafen.log`](log.md) — printing back to the console the command was typed into
- [`hafen.client():options():keybindings()`](client/keybindings.md) — a hotkey, the other way a user
  invokes an addon by hand
- [`hafen.ui`](ui/README.md) — a window, for anything the user does more than occasionally

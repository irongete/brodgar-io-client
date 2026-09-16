# hafen.console: The Console Command Line

Both directions onto the client's command line (`:` opens it; `:lua`, `:reload`, `:fs` and yours are console commands): `hafen.console()` registers a command, unprotected, and [`session:console():run(line)`](#run-a-line-protected) says a line at one character's console, protected.

```lua
local greet_command = hafen.console():on("greet", function(args)
  hafen.log():write("hello, " .. (args[1] or "world"))
end)
-- in the console:  :greet Alice
greet_command:off()
```

Registering is the client's (a name is routed once, so a command answers from every character); saying a line is one character's (the console a line runs in belongs to the character whose widget tree holds it, so that verb takes an address).

---

## Subscribe

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.console():on(name, fn)` | [`Sub`](event/README.md#subscribe) | Unprotected | Route the console command `:name args…` to `fn(args)`. |

| Rule | Detail |
|---|---|
| `name` | A single word: no spaces, not empty. |
| `args` | A 1-based table of the whitespace-split words after the command name; `"quoted words"` group into one, `\` escapes the next character. The name itself is not in the table. |
| A subscription | `command:key()` is the name, `command:off()` stops it, after which typing `:name` reports that no addon handles it. Idempotent; done for you on reload or disable. |
| A name stays the addon layer's | For the rest of the client's run. Ending the subscription frees it for another addon, but the console routes it here until restart: `:name` with nobody holding it says no addon handles it, not that the command is unknown. A name the client itself owns cannot be taken, so nothing lies underneath yours. |
| When to subscribe | Any time; the file body is fine. Reload-safe: `:reload` swaps the handler with no duplicate. |
| Refusals | `lua`, `addons` and `reload` are reserved, and a name an existing client command owns is refused; both raise naming the command. |
| Two addons, one name | The newest subscription wins and takes it over, and the console records the reassignment. The loser's subscription ends: it leaves that addon's `:list()`, `command:off()` on it does nothing, nothing of it reads alive. |
| Every character | Routed once for the client: it runs from whichever console you type it into and goes on running as you tab. Nothing to re-subscribe on a switch. |
| Where it runs | Inside the UI of the console you typed into, the character on screen: it reads and writes that character freely; reaching a different character or one of your own windows is refused naming both. Do that work from the [step](threading.md): `hafen.timer():after(0, fn)` at the top of the body. Same watchdog and error isolation as everything else; an error is logged. |

## Read what you registered

`hafen.console()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your addon's own commands.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.console():list(filter)` | `Sub[]` | Unprotected | Every command of yours. |
| `hafen.console():count(filter)` | `number` | Unprotected | How many. |
| `hafen.console():find(filter)` | `Sub \| nil` | Unprotected | The first that matches. |
| `hafen.console():get(name)` | `Sub \| nil` | Unprotected | The command registered under `name`; `nil` for one you never registered, since the registration is the subscription. |

| Rule | Detail |
|---|---|
| Identity | The members are the `Sub`s `:on` handed you, so `==` finds the one you hold and `command:key()` is its name. |
| `filter` | A string is a substring match on the name; a function is called with each `Sub`. |
| Scope | Your addon's commands alone; the client's built-in commands were never subscriptions. |

```lua
for _, command in ipairs(hafen.console():list()) do
  hafen.log():write(":" .. command:key())
end
local greet_command = hafen.console():get("greet")
if greet_command then greet_command:off() end
```

## Run a line (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:console():run(line)` | the section | `console.run` | Run `line` through that character's console, as though typed there: same dispatcher, same tree walk, same command resolution. |

```lua
hafen.client():options():keybindings():on("logout", function()
  for _, session in ipairs(hafen.session():list()) do
    if session ~= hafen.session():current() then session:console():run("lo") end   -- drop the alts, keep this one
  end
end)
```

| Rule | Detail |
|---|---|
| `line` | The command and the rest of the line without the opening colon: `run("reload")`, not `run(":reload")`. The client's own splitter splits it, so `args` arrive as for a typed command; `:lua` reads the literal text back. |
| A line belongs to a character | `:lo` logs out the character it runs at, `:gl` writes that character's graphics settings, `:act`, `:belt`, `:afk`, `:cam` and `:exportmap` answer for the character whose window registered them: `session:console():run("lo")` logs out the character `session` names, drawn or not. One command line per login, which is why the verb hangs on a [session](session.md). |
| Output goes to the character on screen | The result, an error and anything `:lua` prints are notices, drawn by the session you look at. What the line did is read back through `session`. |
| A command that fails is not your error | `run("zzz")` returns normally and writes `zzz: no such command` to that character's System log and on-screen notice. Nothing is raised. Before the HUD is up the log half is dropped, the notice half shows. Once the HUD is up the client logs notices to System as well, so one failed command leaves two identical System lines: read the newest, do not count lines. |
| `run("reload")` | Queues the reload; the call returns and the following lines still run in the layer that made it. |
| Synchronous, on your own thread | As a typed line. A command body may run a line of its own; a command that runs itself recurses until the instruction watchdog cuts it. |
| Raises | A missing or non-string `line`; one empty or only whitespace; one opening with `:`, naming the spelling without it; a session the client holds no console for (ended, or between widget trees; [`session:exists()`](session.md#read) is the test). `hafen.console():run(...)` raises too, naming this verb. |
| Why protected | `console.run` is the widest key in the [catalogue](../guides/permissions.md#the-catalogue): every command the client dispatches, on any character, `:lua` included, which evaluates against the whole standard library outside your sandbox. The line the user reads when they enable you says so. |

```lua
hafen.session():current():console():run('lua hafen.session():count()')
```

---

## See Also

- [`hafen.session`](session.md) — the address a line is said at.
- [`hafen.event`](event/README.md) — the `Sub` this hands back, and every other subscription in the API.
- [`hafen.log`](log.md) — printing back to the console the command was typed into.
- [`hafen.client():options():keybindings()`](client/keybindings.md) — a hotkey, the other way a user invokes an addon by hand.
- [`hafen.ui`](ui/README.md) — a window, for anything the user does more than occasionally.

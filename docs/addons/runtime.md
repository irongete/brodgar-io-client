# The Runtime

What the client does with your addon once it has read [the manifest](manifest.md). When your code runs. What your Lua may touch. What it costs if it misbehaves. The commands you drive it with.

```lua
hafen.event():on("Load", function() hafen.log():write("loaded once, on the login screen") end)
hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write(session:user() .. " can be read from here on")
end)
hafen.event():on("Disable", function() hafen.store():flush() end)   -- the last write before teardown
```

---

## When your code runs

The client loads the enabled addons once when it starts, on the login screen, and again at every `:reload`. The files named in `files` run in order, top to bottom, once. Then `Load` fires. From there your addon does what its [event handlers and timers](guides/events-and-timers.md) do.

| Moment | What is ready |
|---|---|
| Your file bodies | The whole `hafen` API. Your addon's own vars, each when named. No character. |
| `Load` | The same, once every file has run. Once for the client. |
| `SessionEnteredWorld` | The HUD, the map view, the player, that character's own vars. Once per character reaching the world: again for the same session when it picks another, and for every login in the world at a `:reload`. |
| `Disable` | Your last chance to write before the client flushes and tears down: on a reload, on being disabled, on the way out of the client. Once for the client. |

| Rule | Detail |
|---|---|
| Before a session reaches the world | Everything a character owns (HUD, world, map, its vars) is absent. Each read verb's page states what it gives back then. |
| Errors | An error while a file runs stops that addon's file and marks it errored in the [AddOns manager](panel.md). An error inside a handler, a timer or a draw callback is logged with your addon's id. It is isolated from your other handlers, other addons and the client. A callback failing in a way that is not an error (off the end of the stack, out of memory) is contained too. It [costs you the addon](#when-a-failure-is-fatal). |

## Your addon outlives the character

The addon system is its own layer above the sessions. It is loaded once for the client. It draws above whichever character is on screen, and reaches the drawn one through the API.

| Rule | Detail |
|---|---|
| Switching character changes nothing about your addon | The same Lua environment, every value held, windows keeping their place, focus and any drag, timers counting. `Load` fired once. `Disable` has not fired. |
| What changes is underneath | The [session events](api/event/bus/lifecycle.md#sessions): `SessionAdded` when one connects, `SessionEnteredWorld` when its character can be read, `SessionSelected` when the screen moves to it, `SessionRemoved` when it ends. Tabbing between two characters in the world fires `SessionSelected` only. |
| Keeping your state valid is yours | A widget handle taken under one character names a widget of that character's tree, which dies with them. Nothing clears what you cached. Read the widget you want when you want it ([`session:ui():match`](api/ui/README.md)). |

## The sandbox

| Library | Available | Absent |
|---|---|---|
| The base functions | The safe ones: `pairs`, `ipairs`, `next`, `select`, `type`, `tostring`, `tonumber`, `pcall`, `xpcall`, `error`, `assert` and their neighbours. | `require`, `package`, `load`, `loadfile`, `dofile`, `loadstring`. |
| `string`, `table`, `math` | Whole. | — |
| `os` | The clock half: `time`, `clock`, `date`, `difftime`. | The process and filesystem half: `execute`, `exit`, `getenv`, `remove`, `rename`, `tmpname`, `setlocale`. |
| `io`, `debug`, `coroutine`, the Java bridge | — | Never installed, so `type(io)` is `"nil"`. |
| The globals | `hafen`. `ADDON` (`ADDON.id`, `ADDON.dir`, informational). | — |
| Another addon's code | Through [`hafen.client():addons()`](api/client/addons.md): its export, as a copy, and its functions through the client. | `require`. |

| Rule | Detail |
|---|---|
| LuaJ is 5.2 | `unpack` is `table.unpack`, with no base-level alias. |
| `os.setlocale` | Process-wide C state that would change every other addon's `string.format` and `os.date`. A translation is [`hafen.locale`](api/locale.md), one catalogue per addon, scoped and dropped with it, touching no number, date or sort order. |
| The string metatable is shared | `getmetatable("")` answers from any addon, and there is one for the whole client. An addon that writes to it changes `("x"):upper()` for every other addon and the console. Never write to it. |
| `string.format` is LuaJ's | `%d`, `%i`, `%u`, `%x`, `%X`, `%o` and `%c` honour flags and width (`%5d`, `%-5d`, `%05d`) and take a fraction whole, towards zero (`("%d"):format(3.7)` is `3`). `%f`, `%e`, `%g` and `%s` honour neither width nor precision. `("%.1f"):format(2.25)` is `2.25`, `("%.0f"):format(12)` is `12.0`, `("%-8s\|"):format("ab")` is `ab\|`. `%f` writes the double whole: `("%.2f"):format(1/3)` is `0.3333333333333333`. |
| Round before you print | `%d` for a whole number, `math.floor(x * 10 + 0.5) / 10` under `%s` for one decimal. Pad with `(" "):rep(math.max(0, width - #text))`, since a negative count raises rather than answering `""`. |
| A number prints with float precision | `tostring`, `..` and `%s` write a whole number whole (`12`, never `12.0`) and a fraction to eight significant digits (`1/3` is `0.33333334`). They use exponent form from ten million up (`12345678.9` is `1.2345679E7`) and below a thousandth (`0.0001` is `1.0E-4`). `%d` is the way to print an epoch time or an id whole. |
| Reading a file | [`hafen.asset`](api/asset/README.md)'s job. |
| Where it runs | Almost everything on the client's step, one callback after another in the frame. A draw, a press and the two [message streams](api/event/streams.md) run where the thing they answer is ([threading](api/threading.md)). |

## Budgets and the watchdog

An addon that never returned would freeze the client. The limits below make that impossible, none reachable by ordinary code.

| Limit | Detail |
|---|---|
| Per entry: ten million instructions | Every entry into your Lua (a handler, a timer, a draw, a file body) gets a fresh budget. Exhausting it aborts the call with an error naming the runaway. It counts instructions, not time, so a loop over a few hundred `hafen.*` calls burns a frame without nearing it. |
| An entry inside an entry | Two callbacks running at once on the [two threads that can be inside your Lua](api/threading.md) each spend their own. A callback your callback calls into is an entry too and hands the budget back on the way out. A call into another addon's exported function, or a callback it calls back, is an entry into that addon. |
| Per tick: 10 ms, sustained | An addon whose total Lua time in one tick exceeds the budget for thirty consecutive ticks is auto-disabled. The reason is on its [AddOns manager](panel.md) row and in the console. One heavy load or a stalled frame resets the count. |

An auto-disable lasts until the next load: fix it, then `:reload`. A library's auto-disable takes its loaded hard dependants with it, each reading `auto-disabled (needs <id>)`. The enable state is untouched. [`hafen.client():profiling()`](api/client/profiling/README.md) reports what each addon spends per frame.

## When a failure is fatal

A handler that recurses without bound overflows the client's stack. One that builds a table without bound exhausts its memory. Both are raised by the JVM, below Lua, and `pcall` never catches them.

| Rule | Detail |
|---|---|
| Contained, paid for with your addon | The failure is logged with your addon's id and the stack goes to the terminal. At the end of that tick your addon is torn down as the CPU budget tears one down. `Disable` fires, vars are flushed, everything owned is given back. Its row reads `auto-disabled (…)`, naming what was raised, until the next load. |
| The whole addon stops, not the one callback | A client that has run out of stack under your handler holds nothing you could read. Every other addon, the client and the frame keep running. |

## The console commands

Press `:` to open the client's command line.

| Command | Does |
|---|---|
| `:reload` | Rebuild the addon layer from disk: the developer loop. |
| `:addons` | List every discovered addon with its status. |
| `:addons enable <id>` | Enable an addon, applied on the next `:reload`. |
| `:addons disable <id>` | Disable one, applied on the next `:reload`. |
| `:lua <expression>` | Evaluate Lua against the live API and print the result. |

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua for _, session in ipairs(hafen.session():list()) do print(session:user(), session:chat():count()) end
```

| Rule | Detail |
|---|---|
| `:lua` | Prints its result as JSON prefixed `lua=`, in the console and in full on the terminal. Your own console, not sandboxed: every protected verb answers, the fastest way to try a call and to break something. The instruction watchdog applies. |
| `:reload` undoes anything typed there | The console owns what it puts up as an addon does: windows, gob labels, overlays, hotkeys, clips. A reload takes it back by the same teardown. |
| `print` inside `:lua` | Answers in the System channel too, one line per `print`, tab-separated, where `lua=` and `:threads` land. The terminal keeps its copy whole. A line shows once finished, so an `io.write` with no newline waits. Before a HUD is up the terminal is the whole of it. In an addon, `print` goes to the terminal alone. [`hafen.log():write`](api/log.md) reaches the player. |
| Reserved | `lua`, `addons` and `reload` cannot be taken over. Addons add commands with [`hafen.console`](api/console.md). |
| Saying a line | [`session:console():run(line)`](api/console.md#run-a-line-protected) runs any of these and any client command at the character you address, without the colon (`run("reload")`). `run("lo")` logs out the character it was said at. Needs `console.run`, the widest key, since `:lua` is among the commands it reaches. |

## What a reload keeps, and what it drops

`:reload` rebuilds the addon layer only: every session stays connected, the world loaded, the client's own windows as they are.

| Step | Detail |
|---|---|
| Teardown | Each addon is torn down. Every folder the [AddOns manager](panel.md#how-an-install-lands) marked for removal is deleted and every staged install moved into `addons/`, with nothing loaded. |
| Rebuild | The folder and the enabled set are re-read. The enabled addons run from disk and `Load` fires. `SessionEnteredWorld` fires for every session in the world, the one on screen first, each once. A session not yet in the world is not announced. |
| Dropped and re-created | Event subscriptions, timers, hotkeys, console commands and input hooks. Your windows and overlays. World ghosts, sprites and objects. Loaded assets, your stylesheet, sounds you started. The client's widgets you hid, moved or replaced, handed back as the user saw them. Your vars are written first, at `Disable`. |
| Released on its own | Each holding is released separately: one that fails is a console line naming it, and everything else is released, so a teardown finishes. A disable frees what that addon had and touches no other's. |
| Two things stay | A font family you load goes into the one namespace the client shares. `$font[…]` resolves it. A registered name draws the face that took it first. Your handle draws your file. A sound of several clips leaves an entry in the client's audio cache. |
| Kept | Everything outside the addon layer: a `:reload` never costs you your login. |

## What quitting writes

| Rule | Detail |
|---|---|
| Vars first | Closing the window or `:q` writes every logged-in character's own vars and your addon's own to your file, whatever the timer or `flush()` did. A row a table or statement wrote is in the file already, and the quit closes it. |
| `Disable` fires first | An addon computing its state at teardown has that write picked up by the flush that follows. |
| `Disable` cannot hold the exit open | The addons share one wall-clock budget on the way out. A handler still running when it is spent is abandoned with a terminal line naming the addon. The flush runs no code of yours and happens either way. |
| A crash or a kill | The client runs nothing. The automatic save every thirty seconds is the whole of what covers it. |

---

## See Also

- [The manifest](manifest.md) — where an addon lives, the manifest field by field, and the API version.
- [The AddOns manager](panel.md) — the Installed rows a reload applies, and the Browse tab that reads the hub.
- [Getting started](getting-started.md) — the first addon, end to end.
- [Debugging](guides/debugging.md) — the reload loop in practice, the inspector, and reading the log.
- [`hafen.store`](api/store/README.md) — your addon's file, which a quit writes and closes.
- [Permissions](guides/permissions.md) — the permission the manifest declares.
- [The maintainer's addons](examples.md) — where the addons are, and the tools among them.

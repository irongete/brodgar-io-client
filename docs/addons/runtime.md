# The runtime

What the client does with your addon once it has read [the manifest](manifest.md): when your code runs,
what your Lua may touch, what it costs you if it misbehaves, and the commands you drive it with. The API
pages say what your code can *call*; this page says what it *runs inside*.

## When your code runs

The client loads the enabled addons **once, when it starts** — before you log in, on the login screen —
and again at every `:reload`. For each one it runs the files named in `files`, in order, top to bottom,
once, then fires `Load`. Nothing else is automatic: from there your addon does what its
[event handlers and timers](guides/events-and-timers.md) do.

| Moment | What is ready |
|---|---|
| your file bodies | the whole `hafen` API is callable; your addon's own documents are readable, each when you name it; there is no character |
| `Load` | the same, once every file has run. **Once for the client** |
| `SessionEnteredWorld` | the HUD, the map view, the player, and that character's own documents. **Once per character reaching the world**: again for the same session when it picks another, and again for every login in the world at a `:reload` |
| `Disable` | your last chance to write, before the engine flushes and tears down — on a reload, on being disabled, and on the way out of the client. **Once for the client** |

So your addon starts on the login screen, and everything a character owns — the HUD, the world, the map,
that character's documents — is absent until a session reaches the world. The read verbs say so rather
than guessing: each one's reference page states what it gives back when there is no character yet.

An error while a file runs stops **that** addon's file and marks it errored in the [AddOns manager](panel.md);
an error inside a handler, a timer or a draw callback is logged with your addon's id and isolated, so it takes
down neither your other handlers nor another addon nor the client. A **callback** that fails in a way that is
not an error at all — running the client off the end of its stack or out of memory — is contained as well, and
[costs you the addon](#when-a-failure-is-fatal).

## Your addon outlives the character

**The addon system is its own layer, above the sessions, and it never lives inside one.** The client can
hold several accounts logged in at once and draws one of them; your addon is loaded once for the client,
draws above whichever character is on screen, and reaches the drawn one through the API.

Switching character therefore **changes nothing about your addon**. Its Lua environment is the same
environment, every value it holds is still held, its windows keep their place, their focus and any drag
still in progress, and its timers keep counting. `Load` fired once and `Disable` has not fired.

What does change is underneath you, and the [session events](api/event/bus/lifecycle.md#sessions) are how you
hear it: `SessionAdded` when one connects, `SessionEnteredWorld` when its character can be read,
`SessionSelected` when the screen moves to it, `SessionRemoved` when it ends. Each hands you that session's
account name. Tabbing between two characters already in the world fires `SessionSelected` and nothing else —
tabbing is not entering.

> **Your state survives a character switch, and keeping it valid is therefore yours.** A widget handle
> you took under one character means nothing under another: it names a widget of that character's own
> tree, which dies with them. Nothing tears your addon down between the two, so nothing clears what you
> cached — read the widget you want when you want it, which is what
> [`s:ui():match`](api/ui/README.md) costs and no more.

## The sandbox

Addon code runs in a restricted Lua environment, so an addon you install cannot touch anything but the
game.

**Available**: `string`, `table`, `math`, the clock half of `os` (`time`, `clock`, `date`, `difftime`), and
the safe base functions — `pairs`, `ipairs`, `next`, `select`, `type`, `tostring`, `tonumber`, `pcall`,
`xpcall`, `error`, `assert` and their neighbours. LuaJ is 5.2, so `unpack` is `table.unpack` and there is
no base-level alias for it. Plus the global `hafen` table, which is the whole of what reaches the client.

**Absent**: `io`, the process and filesystem half of `os` (`execute`, `exit`, `getenv`, `remove`, `rename`,
`tmpname`, `setlocale`), `require`, `package`, `load`, `loadfile`, `dofile`, `loadstring`, `debug`,
`coroutine`, and the Java bridge. They are not hidden or stubbed, they are never installed — so `type(io)`
is `"nil"`, and code that reaches for one fails where it stands.

`os.setlocale` is absent for the ordinary reason — it is process-wide C state, and an addon that changed it
would change how every other addon's `string.format` and `os.date` behave. It is also not the door to a
translation: [`hafen.locale`](api/locale.md) says what the client **displays**, one catalogue per addon,
scoped to the addon that installed it and dropped with it, and it touches no number, date or sort order at
all.

> **The string metatable is shared, and the environment does not wall it off.** `getmetatable("")`
> answers from any addon, and Lua keeps one of them for the whole client rather than one per
> environment — so an addon that writes to it changes what `("x"):upper()` means for every other addon
> and for the client's own console. It is the language's state, not the API's, so there is nothing here
> that can partition it. Read it if you must; never write to it.

Your addon also gets a global `ADDON` table with two fields: `ADDON.id`, its id, and `ADDON.dir`, the
absolute path of its folder. Both are informational — reading a file is
[`hafen.asset`](api/asset/README.md)'s job.

Almost everything your addon does runs on the client's **step**, one callback after another in the same
frame as the drawing; a draw, a press and the two [message streams](api/event/streams.md) run where the
thing they answer is. Which is which, and what each may reach, is [threading](api/threading.md).

## Budgets and the watchdog

Because your Lua runs inside the client's own frame, an addon that never returns would freeze the client. Two limits make that
impossible, and neither one is reachable by ordinary code.

- **Per entry: ten million instructions.** Every entry into your Lua — a handler, a timer, a draw, a file
  body — gets a fresh budget of its own, and a call that exhausts it is aborted with an error naming the
  runaway. A legitimate callback is thousands of instructions, not millions. The budget belongs to the
  **entry**, not to your addon: two of your callbacks running at once, on the [two threads that can be
  inside your Lua](api/threading.md), each spend their own, so neither is aborted for what the other did
  and neither is given a fresh one by the other starting. A callback your own callback calls into — a
  handler that fires an event you subscribe to — is an entry too, and hands the budget back on the way
  out, so the outer call goes on spending what it had left rather than starting over. It counts Lua
  instructions and not time, so a loop over a few hundred `hafen.*` calls — each one cheap in Lua and
  expensive in the client — burns a frame without coming near it. That shape is the second budget's
  business, and it is why there are two.
- **Per tick: about ten milliseconds, sustained.** An addon whose total Lua time within one tick — every
  handler, timer and draw of that tick added up — goes over the budget for thirty **consecutive** ticks is
  auto-disabled, with the reason on its row in the [AddOns manager](panel.md) and in the console. One heavy
  load or a single janky frame resets the count, so only sustained overrun trips it.

> An auto-disable lasts until the next load: fix what stopped it, then `:reload`. The addon's own enable
> state is untouched.

[`hafen.client():profiling()`](api/client/profiling/README.md) reports what each addon spends per frame,
which is how you find out *before* the engine does.

## When a failure is fatal

Some failures are not errors your Lua can see. A handler, a timer or a draw callback that recurses without
bound runs the client off the end of its stack; one that builds a table without bound runs it out of memory.
Both raise something from underneath the language, which your own `pcall` never catches and which no
callback can be written to survive.

The client contains those where it isolates every other error, and pays for them with your addon:

- the failure is logged with your addon's id, and the stack behind it goes to the terminal — for this kind
  of failure that stack is the only description of it there is;
- at the end of that tick your addon is torn down, exactly as the CPU budget tears one down: `Disable`
  fires, your documents are flushed, and everything the addon owns is given back;
- its row in the [AddOns manager](panel.md) reads `auto-disabled (…)`, naming what was raised, until the
  next load.

> **The whole addon stops, not the one callback.** That is the difference from an ordinary error, and it is
> deliberate: a client that has just run out of stack under your handler holds nothing you could go on
> reading. Every other addon and the client itself keep running, and so does the frame it happened in.

## The console commands

Press `:` to open the client's command line. Three commands drive the addon layer:

| Command | Does |
|---|---|
| `:reload` | rebuild the addon layer from disk — the developer loop |
| `:addons` | list every discovered addon with its status |
| `:addons enable <id>` | enable an addon, applied on the next `:reload` |
| `:addons disable <id>` | disable one, applied on the next `:reload` |
| `:lua <expression>` | evaluate Lua against the live API and print the result |

`:lua` prints its result as JSON, prefixed `lua=`, in the console and in full on the terminal. It is your
own console rather than shared addon code, so it is **not** sandboxed and every protected verb answers there:
it is the fastest way to try a call before you write it, and the fastest way to break something. The
instruction watchdog still applies, so a stray infinite loop aborts instead of freezing the client.

**`:reload` is the way back out of anything you type there.** The console owns what it puts up exactly as an
addon owns what it puts up — its windows, its labels on game objects, its overlays, its hotkeys, its clips —
and a reload takes all of it back, by the same teardown an addon gets. So a `:lua` line you cannot undo by
hand is one `:reload` away from undone.

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua hafen.session():current():ui():match("window[title=Inventory]"):size()
```

**`print` inside `:lua` answers in the chat too**, in the *System* channel, one line per `print` and
tab-separated exactly as Lua writes it — the same place the command's own `lua=` result lands and the same
place `:threads` dumps to, because that is the console's output rather than a notice. The terminal keeps
its copy, and keeps it whole where a very long line is shortened in game. A line is only shown once it is
finished, so an `io.write` with no newline waits for the one that ends it.

```text
:lua for _, s in ipairs(hafen.session():list()) do print(s:user(), s:chat():count()) end
```

Before a character's HUD is up there is nowhere in the client to put it and the terminal is the whole of
it — which is what a typed command's output does there too. **In an addon, `print` is not this**: it goes
to the terminal alone, and [`hafen.log():write`](api/log.md) is the line that reaches the player, tagged
with your addon's id so they can tell whose it is.

Addons add commands of their own with [`hafen.console`](api/console.md); `lua`, `addons` and `reload` are
reserved and cannot be taken over.

**An addon can also say a line rather than wait for one.**
[`s:console():run(line)`](api/console.md#run-a-line-protected) runs any of these, and any command the
client itself has, at the character you address — the colon opening the line is not part of it, so it is
`run("reload")`. The console a line runs in belongs to a character rather than to the client, which is why
`run("lo")` logs out the character it was said at, drawn or not. It needs the `console.run`
[permission](guides/permissions.md), the widest key in the catalogue, because `:lua` is one of the
commands it reaches.

## What a reload keeps, and what it drops

`:reload` rebuilds **the addon layer only**, and it is the one thing that does. Every session you have
logged in stays connected, the world stays loaded, the client's own windows stay as they are, and each
addon is torn down, every folder the [AddOns manager](panel.md#how-an-install-lands) marked for removal is
deleted and every install it has staged is moved into `addons/` — with nothing loaded, which is the one
moment a folder can change under no addon at all — the folder and the enabled set are re-read, the enabled
addons run again from disk, `Load` fires, and `SessionEnteredWorld` fires again for **every session that is
in the world** — the one on screen first, then the rest, each exactly once. The addons that were rebuilt are
the client's rather than any character's, so every login gets the announcement and an addon that holds
something per login has nothing to catch up on. A session that has not reached the world is not announced:
there is no character to re-initialize for.

Torn down and re-created, so your addon starts clean: event subscriptions, timers, hotkeys, console
commands, input hooks, your windows and overlays, world ghosts, sprites and objects, loaded assets, your
stylesheet, sounds you started, and the client's own widgets you hid, moved or replaced, which are handed
back as the user was seeing them. Written first: your documents, flushed at `Disable`.

Everything your addon holds lives on the addon, or on the login it was made in, and goes with it — a
disable frees what that addon had and touches no other's. **Each of those is released on its own**: one that
fails to release is a line on the console naming it, and everything else is released all the same, so a
teardown always finishes. Two things you reach are the client's own and stay: a **font family** you load goes into the one namespace the whole client shares, and nothing takes a
family back, so `$font[…]` still resolves it and a name already registered draws the face that took it
first (your own handle always draws your file); and a **sound** of several clips leaves an entry in the
client's audio cache, beside the ones its own sounds leave.

Kept: everything outside the addon layer. The client itself is not reloaded, so a `:reload` never costs you
your login.

## What quitting writes

Quitting — by closing the window, or with `:q` — writes your documents before the process ends. Every
logged-in character's own and your addon's own go to your addon's file, whether or not thirty seconds have
passed since the last automatic save and whether or not anything called `flush()`; a row a table or a
statement wrote is in the file already, and the quit closes it.

`Disable` fires on the way out as well, and it fires first, so an addon that computes its state at teardown
rather than keeping it in the store has that write picked up by the flush that follows it.

> **`Disable` cannot hold the exit open.** The addons share one wall-clock budget on the way out, and a
> handler still running when it is spent is abandoned, with a line on the terminal naming the addon. The
> flush happens either way — it is the client's own work and runs no code of yours. A quit is never blocked
> by an addon, and never silently drops one.

Nothing helps a crash or a kill, where the client gets to run nothing at all: the automatic save every
thirty seconds is the whole of what covers those.

## See also

- [the manifest](manifest.md) — where an addon lives, the manifest field by field, and the API version
- [the AddOns manager](panel.md) — the Installed rows a reload applies, and the Browse tab that reads the hub
- [getting started](getting-started.md) — the first addon, end to end
- [debugging](guides/debugging.md) — the reload loop in practice, the inspector, and reading the log
- [`hafen.store`](api/store/README.md) — your addon's file, which a quit writes and closes
- [permissions](guides/permissions.md) — the permission the manifest declares
- [the maintainer's addons](examples.md) — where the addons are, and the tools among them

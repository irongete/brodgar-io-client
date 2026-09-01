# The runtime

What the client does with your addon: where it looks for it, what it reads, what your Lua may touch, what
it costs you if it misbehaves, and the three commands you drive it with. The API pages say what your code
can *call*; this page says what it *runs inside*.

## Where an addon lives

The client reads addons from the `addons/` folder beside it. One folder per addon, and a folder is an
addon when it holds a `manifest.json`; anything else there is ignored.

```text
addons/
  myaddon/
    manifest.json      the metadata, and the list of files to run
    main.lua           your code
    icon.png           anything else you ship, loaded with hafen.asset
savedata/
  account/myaddon.json         your account-wide saved variables
  <genus>_<char>/myaddon.json  your per-character ones
```

The folder name **is** the addon's id, and the manifest has to repeat it: a mismatch is a load error, not a
rename. Files your addon ships are read through [`hafen.asset`](api/asset.md), which resolves paths inside
your own folder and rejects everything outside it. The `savedata/` tree is written for you — see
[`hafen.store`](api/store.md).

## The manifest

`manifest.json` is a JSON object. `id` and `files` are required; everything else is optional.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | unique id; must equal the folder name |
| `files` | array of strings | the `.lua` files to run, in this order; at least one |
| `name` | string | display name in the AddOns panel; defaults to `id` |
| `version` | string | shown in the panel and in `:addons` |
| `author` | string | shown in the panel |
| `description` | string | the panel row's tooltip |
| `api_version` | number | the API level you target; recorded, and nothing rejects a mismatch |
| `saved_variables` | array | the tables the engine persists — see [`hafen.store`](api/store.md) |
| `permissions` | array of strings | one key per protected verb you call, or a `<prefix>.*` group — the catalogue is in [permissions](guides/permissions.md) |
| `network` | object | `{"hosts": [...]}` — **the argument of the `http.get`/`http.post` key**: the key says whether, this says where. Declaring it without the key is a load error. See [`hafen.http`](api/http.md) |
| `dependencies` | array of strings | addon ids, recorded; the loader neither orders nor requires them |
| `optional_dependencies` | array of strings | the same |

Each addon runs in an environment of its own and cannot see another addon's globals, so there is nothing
for a dependency to import: two addons that cooperate do it through the client — a container, a marker, a
console command — or not at all.

A manifest the client cannot read is a load error naming what is wrong: bad JSON, a missing `id` or
`files`, an id that does not match the folder, a `permissions` entry that is neither a key nor a group,
a `network` block that is not an object with a `hosts` array, one whose hosts no `http.*` permission asks
to reach, or a `hosts` entry of `"*"`. The addon then
shows an error row in the panel and runs nothing; the others are unaffected.

## When your code runs

The client loads the enabled addons **once, when it starts** — before you log in, on the login screen —
and again at every `:reload`. For each one it runs the files named in `files`, in order, top to bottom,
once, then fires `Load`. Nothing else is automatic: from there your addon does what its
[event handlers and timers](guides/events-and-timers.md) do.

| Moment | What is ready |
|---|---|
| your file bodies | the whole `hafen` API is callable; account saved variables are filled; there is no character |
| `Load` | the same, once every file has run. **Once for the client** |
| `SessionEnteredWorld` | the HUD, the map view, the player, and that character's own saved variables. **Once per character reaching the world**: again for the same session when it picks another, and again for every login in the world at a `:reload` |
| `Disable` | your last chance to write, before the engine flushes and tears down — on a reload, on being disabled, and on the way out of the client. **Once for the client** |

So your addon starts on the login screen, and everything a character owns — the HUD, the world, the map,
per-character saved variables — is absent until a session reaches the world. The read verbs say so rather
than guessing: each one's reference page states what it gives back when there is no character yet.

An error while a file runs stops **that** addon's file and marks it errored in the panel; an error inside a
handler, a timer or a draw callback is logged with your addon's id and isolated, so it takes down neither
your other handlers nor another addon nor the client.

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

Your addon also gets a global `ADDON` table with two fields: `ADDON.id`, its id, and `ADDON.dir`, the
absolute path of its folder. Both are informational — reading a file is
[`hafen.asset`](api/asset.md)'s job.

Almost everything your addon does runs on the client's **step**, one callback after another in the same
frame as the drawing; a draw, a press and the two [message streams](api/event/streams.md) run where the
thing they answer is. Which is which, and what each may reach, is [threading](api/threading.md).

## Budgets and the watchdog

Because your Lua runs inside the client's own frame, an addon that never returns would freeze the client. Two limits make that
impossible, and neither one is reachable by ordinary code.

- **Per call: ten million instructions.** Every entry into your Lua — a handler, a timer, a draw, a file
  body — gets a fresh budget, and a call that exhausts it is aborted with an error naming the runaway. A
  legitimate callback is thousands of instructions, not millions.
- **Per tick: about ten milliseconds, sustained.** An addon whose total Lua time within one tick — every
  handler, timer and draw of that tick added up — goes over the budget for thirty **consecutive** ticks is
  auto-disabled, with the reason on its row in the AddOns panel and in the console. One heavy load or a
  single janky frame resets the count, so only sustained overrun trips it.

> An auto-disable lasts until the next load: fix what was burning the frame, then `:reload`. The addon's
> own enable state is untouched.

[`hafen.client():profiling()`](api/client/profiling/README.md) reports what each addon spends per frame,
which is how you find out *before* the engine does.

## The AddOns panel

**AddOns**, on the game menu that `Ctrl+O` opens, lists every addon the client discovered, sorted by id,
one row each: a checkbox, the name, version and author, and a live status. The description is the row's
tooltip.

| Row shows | Meaning |
|---|---|
| `loaded v<version>` | running |
| `disabled` | switched off, and not loaded |
| `not loaded` | enabled, but not running — usually an enable that no reload has applied yet |
| `error: …` | its manifest or its Lua failed; the message says how |
| `auto-disabled (…)` | the CPU budget stopped it |
| `[protected: N]` | it asked for N permission entries — it can act on your behalf; the tooltip names them |
| `[net]` | it declared network hosts; the tooltip names every host it may reach |

The count is the entries the addon wrote, so a `<prefix>.*` group counts as the one line you read rather
than as the keys it covers.

**A checkbox is applied on the next reload**, never mid-session: ticking one and pressing **Reload UI** is
the whole gesture, and a "changes pending" line says so until you do. **Enable all** turns on every addon
that is not marked `[protected: N]`; a write addon is only ever enabled one at a time, through the consent
dialog that ticking it raises. **Open addons folder** opens `addons/` in your file browser.

An addon that declares a permission key is disabled the first time the client sees it, so a write addon
never runs because it was merely installed. After that its state is yours — see
[permissions](guides/permissions.md).

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
addon is torn down, the folder and the enabled set are re-read, the enabled addons run again from disk,
`Load` fires, and `SessionEnteredWorld` fires again for **every session that is in the world** — the one
on screen first, then the rest, each exactly once. The addons that were rebuilt are the client's rather
than any character's, so every login gets the announcement and an addon that holds something per login
has nothing to catch up on. A session that has not reached the world is not announced: there is no
character to re-initialize for.

Torn down and re-created, so your addon starts clean: event subscriptions, timers, hotkeys, console
commands, input hooks, your windows and overlays, world ghosts, sprites and objects, loaded assets, your
stylesheet, sounds you started, and the client's own widgets you hid, moved or replaced, which are handed
back as the user was seeing them. Written first: your saved variables, flushed at `Disable`.

Kept: everything outside the addon layer. The client itself is not reloaded, so a `:reload` never costs you
your login.

## What quitting writes

Quitting — by closing the window, or with `:q` — writes your saved variables before the process ends. Every
logged-in character's own tables go to their own folder and your account file goes to yours, whether or not
thirty seconds have passed since the last automatic save and whether or not anything called `flush()`.

`Disable` fires on the way out as well, and it fires first, so an addon that computes its state at teardown
rather than keeping it in the store has that write picked up by the flush that follows it.

> **`Disable` cannot hold the exit open.** The addons share one wall-clock budget on the way out, and a
> handler still running when it is spent is abandoned, with a line on the terminal naming the addon. The
> flush happens either way — it is the client's own work and runs no code of yours. A quit is never blocked
> by an addon, and never silently drops one.

Nothing helps a crash or a kill, where the client gets to run nothing at all: the automatic save every
thirty seconds is the whole of what covers those.

## See also

- [getting started](getting-started.md) — the first addon, end to end
- [debugging](guides/debugging.md) — the reload loop in practice, the inspector, and reading the log
- [`hafen.store`](api/store.md) — the saved variables the manifest declares
- [permissions](guides/permissions.md) — the permission the manifest declares
- [the bundled addons](examples.md) — the addons installed already, and what each is for

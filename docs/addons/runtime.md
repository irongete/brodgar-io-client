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
| `permissions` | array of strings | `["actions"]` to declare the write tier — see [`hafen.act`](api/act.md) |
| `network` | object | `{"hosts": [...]}`, the allowlist for [`hafen.http`](api/http.md) |
| `dependencies` | array of strings | addon ids, recorded; the loader neither orders nor requires them |
| `optional_dependencies` | array of strings | the same |

Each addon runs in an environment of its own and cannot see another addon's globals, so there is nothing
for a dependency to import: two addons that cooperate do it through the client — a container, a marker, a
console command — or not at all.

A manifest the client cannot read is a load error naming what is wrong: bad JSON, a missing `id` or
`files`, an id that does not match the folder, a `network` block that is not an object with a `hosts`
array, or a `hosts` entry of `"*"`. The addon then shows an error row in the panel and runs nothing; the
others are unaffected.

## When your code runs

The client loads the enabled addons at login and at every `:reload`. For each one it runs the files named
in `files`, in order, top to bottom, once — then fires `OnLoad`. Nothing else is automatic: from there your
addon does what its [event handlers and timers](guides/events-and-timers.md) do.

| Moment | What is ready |
|---|---|
| your file bodies | the whole `hafen` API is callable; account saved variables are filled; you are not in the world |
| `OnLoad` | the same, once every file has run |
| `OnEnterWorld` | the HUD, the map view, the player, and your per-character saved variables |
| `OnDisable` | your last chance to write, before the engine flushes and tears down |

An error while a file runs stops **that** addon's file and marks it errored in the panel; an error inside a
handler, a timer or a draw callback is logged with your addon's id and isolated, so it takes down neither
your other handlers nor another addon nor the client.

## The sandbox

Addon code runs in a restricted Lua environment, so an addon you install cannot touch anything but the
game.

**Available**: `string`, `table`, `math`, the clock half of `os` (`time`, `clock`, `date`, `difftime`), and
the safe base functions — `pairs`, `ipairs`, `next`, `select`, `type`, `tostring`, `tonumber`, `pcall`,
`xpcall`, `error`, `assert`, `unpack` and their neighbours. Plus the global `hafen` table, which is the
whole of what reaches the client.

**Absent**: `io`, the process and filesystem half of `os` (`execute`, `exit`, `getenv`, `remove`, `rename`,
`tmpname`, `setlocale`), `require`, `package`, `load`, `loadfile`, `dofile`, `loadstring`, `debug`,
`coroutine`, and the Java bridge. They are not hidden or stubbed, they are never installed — so `type(io)`
is `"nil"`, and code that reaches for one fails where it stands.

Your addon also gets a global `ADDON` table with two fields: `ADDON.id`, its id, and `ADDON.dir`, the
absolute path of its folder. Both are informational — reading a file is
[`hafen.asset`](api/asset.md)'s job.

Everything your addon does runs on the client's one UI thread, in the same frame as the drawing: see
[threading](api/conventions.md#threading).

## Budgets and the watchdog

Because Lua runs on that thread, an addon that never returns would freeze the client. Two limits make that
impossible, and neither one is reachable by ordinary code.

- **Per call: ten million instructions.** Every entry into your Lua — a handler, a timer, a draw, a file
  body — gets a fresh budget, and a call that exhausts it is aborted with an error naming the runaway. A
  legitimate callback is thousands of instructions, not millions.
- **Per tick: about ten milliseconds, sustained.** An addon whose total Lua time within one tick — every
  handler, timer and draw of that tick added up — goes over the budget for thirty **consecutive** ticks is
  auto-disabled for the rest of the session, with the reason on its row in the AddOns panel and in the
  console. One heavy `OnEnterWorld` or a single janky frame resets the count, so only sustained overrun
  trips it.

> An auto-disable lasts the session and clears on the next load: fix what was burning the frame, then
> `:reload`. The addon's own enable state is untouched.

[`hafen.client():profiling()`](api/client/profiling/README.md) reports what each addon spends per frame,
which is how you find out *before* the engine does.

## The AddOns panel

**Options ▸ AddOns** lists every addon the client discovered, sorted by id, one row each: a checkbox, the
name, version and author, and a live status. The description is the row's tooltip.

| Row shows | Meaning |
|---|---|
| `loaded v<version>` | running this session |
| `disabled` | switched off, and not loaded |
| `not loaded` | enabled, but not running — usually an enable that no reload has applied yet |
| `error: …` | its manifest or its Lua failed; the message says how |
| `auto-disabled (…)` | the CPU budget stopped it this session |
| `[actions]` | it declared the write tier — it can act on your behalf |
| `[net]` | it declared network hosts; the tooltip names every host it may reach |

**A checkbox is applied on the next reload**, never mid-session: ticking one and pressing **Reload UI** is
the whole gesture, and a "changes pending" line says so until you do. **Enable all** turns on every addon
that is not marked `[actions]`; a write addon is only ever enabled one at a time, through the consent
dialog that ticking it raises. **Open addons folder** opens `addons/` in your file browser.

An addon that declares `actions` is disabled the first time the client sees it, so a write addon never runs
because it was merely installed. After that its state is yours — see
[actions and permissions](guides/actions-and-permissions.md).

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
own console rather than shared addon code, so it is **not** sandboxed and every gated verb answers there:
it is the fastest way to try a call before you write it, and the fastest way to break something. The
instruction watchdog still applies, so a stray infinite loop aborts instead of freezing the client.

```text
:lua hafen.world():gob():count("terobjs/tree")
:lua hafen.ui():find("window[title=Inventory]"):size()
```

Addons add commands of their own with [`hafen.slash`](api/slash.md); `lua`, `addons` and `reload` are
reserved and cannot be taken over.

## What a reload keeps, and what it drops

`:reload` rebuilds **the addon layer only**. Your session stays connected, the world stays loaded, the
client's own windows stay as they are, and no addon can tell the difference from a fresh login: each one is
torn down, the folder and the enabled set are re-read, the enabled addons run again from disk, `OnLoad`
fires, and — if you are in the world — per-character saved variables are restored and `OnEnterWorld` fires
again.

Torn down and re-created, so your addon starts clean: event subscriptions, timers, hotkeys, console
commands, input hooks, your windows and overlays, world ghosts, sprites and objects, loaded assets, your
stylesheet, sounds you started, and the client's own widgets you hid, moved or replaced, which are handed
back as the user was seeing them. Written first: your saved variables, flushed at `OnDisable`.

Kept: everything outside the addon layer. The client itself is not reloaded, so a `:reload` never costs you
your login.

## See also

- [getting started](getting-started.md) — the first addon, end to end
- [debugging](guides/debugging.md) — the reload loop in practice, the inspector, and reading the log
- [`hafen.store`](api/store.md) — the saved variables the manifest declares
- [`hafen.act`](api/act.md) — the permission the manifest declares, and what it gates
- [the example addons](examples.md) — installed and running already, one per part of the API

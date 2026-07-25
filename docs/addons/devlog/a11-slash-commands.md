# A11 — Console / slash commands (`hafen.slash`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **41/41 headless logic
> checks** (`invoke` args marshalling; register/dispatch state machine; the reload-safety property — one
> engine-lifetime dispatcher, re-register reuses it, teardown keeps it; cross-addon takeover is
> identity-safe; reserved/whitespace/empty/non-function refusals) + LuaJ parse of the harness under
> `Sandbox.create()`. **In-game DoD pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.slash`
> gap-subsystem A11), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A11 — "Console / slash commands for addons" + **C1** — the reload-leak risk),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`Console`).

WoW-style `/command`s are how an addon gives the player a typed verb ("`/rl`", "`/pos`", "`/tp home`").
Haven has the machinery already — the `:command` **console** (`Console`), the same one that powers the
engine's `:lua` / `:addons` / `:reload` and the client's own `:gc` / `:sfx` / …. `hafen.slash` lets an
addon register a `:name` routed to a Lua handler: the addon-scriptable version of `Console.setscmd`.

## `hafen.slash.register(name, fn)` — register a `:command`

```lua
local h = hafen.slash.register("pos", function(args)
  local p = hafen.gob.pos("player")
  if p then hafen.log(("you are at %.0f, %.0f"):format(p.x, p.y)) end
end)
-- ...later, if you want it gone before reload/disable:
h:remove()
```

Type `:pos` in the console (open it in-game with the chat/console key) and the handler runs. The handler
receives **`args`** — a **1-based Lua table** of the whitespace-split arguments **after** the command
name (the name itself is excluded):

| you type | `fn` receives |
|---|---|
| `:pos` | `args = {}` (`#args == 0`) |
| `:tp home` | `args = {"home"}` |
| `:give 3 log` | `args = {"3", "log"}` — all strings; convert with `tonumber` as needed |
| `:note "a b" c` | `args = {"a b", "c"}` — `"quoted words"` group, `\` escapes (Haven's `Utils.splitwords`) |

`register` returns a **handle** with **`:remove()`** (drop the command early). Registration needs **no
live target**, so the **file body** is the natural place (like `hafen.key.bind`); an addon may register
as many commands as it likes.

## Reload-safety — one engine-lifetime dispatcher per name (coverage-gaps C1)

`Console.setscmd` has **no unregister**. The naïve implementation — register a console command per
`hafen.slash.register`, and re-run that on every `:reload` — would **leak and duplicate** commands: each
reload adds another registration routing to a **stale** (torn-down) handler. The audit flagged exactly
this ([coverage-gaps C1](../../specs/addons/coverage-gaps.md#c1--reload-can-leak-consoleglobal-registrations)).

The fix (spec: *"a single engine-lifetime dispatcher that routes to current addon state, never
re-registers"*): the **first** time a name is registered, the bridge installs **one** `Console` command
that forever routes to `slashHandlers.get(name)` — the **current live handler** — and **never touches
`Console` again** for that name. Reload / disable only **swaps** (or drops) the entry in `slashHandlers`:

- **Register** → put the handler in `slashHandlers[name]`; install the dispatcher **only if** this name
  has none yet (tracked in `slashDispatched`, which **grows only** — engine-lifetime, deliberately *not*
  reset per session, unlike the session-scoped hook maps).
- **Re-register the same name** (the `:reload` case, or a takeover) → **swap** the live handler; **no new
  dispatcher** (`slashDispatched` unchanged). Last registration wins (WoW-like); a cross-addon takeover
  logs a reassignment notice.
- **`:remove()` / teardown** (reload / disable / auto-disable) → mark the handler dead and drop it from
  `slashHandlers` (identity-checked — a name a *different* addon has since taken over is left alone). The
  **dispatcher stays installed**; typing `:name` now replies **`no addon currently handles :name`**.

So the number of `Console` commands is bounded by the **distinct names ever used**, and each always
routes to live state or to that friendly "nobody handles this" reply — **never a leak, never a
duplicate, never a stale closure**. The whole property is covered by the headless checks (register →
dispatch → teardown → re-register → dispatch reuses the same dispatcher and hits the fresh handler).

## Refused names

`register` throws a clear Lua error (wrap in `pcall` to tolerate) when the name is:

- a **reserved engine command** — `lua`, `addons`, `reload` (they live in the same static command map, so
  claiming one would clobber the addon dev loop);
- **already a client command** (a built-in like `:gc` / `:sfx`, or another addon's) — refused so an addon
  can't silently shadow or clobber it;
- **empty** or **contains whitespace** (a console command is a single whitespace-split word);
- registered with a **non-function** `fn`.

## Zero core edits — reuses `Console.setscmd`

No `haven` file changed. `Console.setscmd` / `findcmd` / `run` / `rawcmd` are all public and already the
seam `:lua` uses. The bridge adds one dispatcher per name and its own `slashHandlers` registry — new code
lives entirely in `io.brodgar.addon`.

## The `hello` example (`addons/hello/main.lua`) — a `:hello` command

Registered in the **file body**, `:hello` demonstrates **sub-command dispatch** off `args[1]`:

| command | effect |
|---|---|
| `:hello` | greets + prints the usage line |
| `:hello toggle` | shows/hides the 2a window — a slash command **driving live addon state** |
| `:hello ping` | plays `sfx/msg` |
| `:hello echo a "b c" d` | logs `"a b c d"` — shows the args rejoined (quoting survives the split) |
| `:hello xyz` | falls through: prints the arg count + the args joined by `|` |

It stays the standing regression harness (one login re-checks every prior slice **and** this one), bumped
to **v0.25.0**. The reload-safety DoD is a manual step (edit the handler text, `:reload`, `:hello` shows
the new text with no duplicate command).

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

Then, in the console (chat/console key, then type without the leading space):

- `:hello` → the greeting + usage line. The command is live as soon as the addon loads (registered in the
  file body, not gated on `OnEnterWorld`); the in-game console itself is reached from the chat/console key.
- `:hello toggle` (in-world) → the "Hello" window shows/hides; `:hello ping` → a sound; `:hello echo "a b" c`
  → logs `a b c` (quoting grouped `a b`).
- **Reload-safety (the C1 DoD):** edit the `:hello` greeting text in `main.lua`, run `:reload`, then
  `:hello` again → the **new** text appears, and there is **no** duplicate output (one dispatcher, swapped
  handler).
- **Disable:** `:addons disable hello` + `:reload`, then `:hello` → `no addon currently handles :hello`
  (the dispatcher persists but has no handler — the leak-free behaviour). Re-enable + `:reload` restores it.
- **Refusals:** `:lua hafen.slash.register("lua", function() end)` → errors (reserved); a name with a
  space or an existing client command likewise.

## Files

- `src/io/brodgar/addon/LuaSlashCommand.java` — **new.** The Java half: `owner`/`name`/`fn`/`alive` + the
  `invoke(words)` that builds the 1-based `args` table (name excluded) and calls through `callLua`
  (watchdog-armed, error-isolated, CPU-accounted).
- `src/io/brodgar/addon/AddonManager.java` — the `hafen.slash.register` facade; the `slashHandlers`
  (name → live handler) + `slashDispatched` (names with a dispatcher installed) registries;
  `newSlashCommand` / `dispatchSlash` / `removeSlashCommand` / `teardownSlashCommands` + the
  `isReservedSlash` / `hasWhitespace` / `idOf` helpers; `teardownSlashCommands` wired into `teardown`.
- `src/io/brodgar/addon/Addon.java` — the `slashCommands` owned-resource list (P2).
- `addons/hello/` — the `:hello` slash command + manifest bumped to **v0.25.0**.

**No `haven` core edit.**

## Threading & safety

- The in-game `:` console dispatches on the **UI thread** (input handling) — the same thread as the tick
  and draw, so a slash handler **never races other Lua**. This matches the `:lua` REPL exactly (a terminal
  stdin build dispatches on the reader thread — the same trust/threading profile the REPL already accepts).
- All handler calls route through **`callLua`** → the D-018 instruction watchdog bounds a runaway handler,
  errors are isolated (a broken command never escapes the console), and the time counts toward the addon's
  soft CPU budget.
- The `alive` flag makes a dispatch that races teardown a no-op; the identity-checked `slashHandlers`
  remove means one addon's teardown can't steal a name another addon has since taken over.
- Bridge-owned (P2): `Addon.slashCommands` is the owned list; teardown drops the live handlers on
  reload/disable. The `Console` dispatchers are **engine-lifetime by design** (C1) — never removed.

## Limitations / deferred

- **`args` is the split words only.** The **raw remainder** (the line after `:name ` with quotes/spacing
  intact — `cons.rawcmd()`/`stripCmd`) is not passed. Most commands want the split form (and quoting is
  handled), so this is deferred; a `{raw = true}` option or an `args.raw` field can add it if a real
  free-text command needs it.
- **One handler per name** (last registration wins) — matching `Console`'s single-command-per-name model.
- **No tab-completion / help integration** and **no `:reload <one addon>`** granularity.
- **Case-sensitive names** (the `Console` `TreeMap` is case-sensitive) — register the exact casing you want
  the user to type.

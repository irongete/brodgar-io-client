# 111 — Plan

## Approach

**The address is the whole design.** `UI.cons` is a `WidgetConsole` — a private inner class of `UI`,
one per tree — and it is where `:lo` and `:gl` are `setcmd`, with `sess.close()` and `setgprefs(...)`
for bodies. Its `findcmd(String)` walks `root` first, children before the widget itself, and only
then falls through to `Console.findcmd`'s static → instance → directory tiers. So which `UI` a line
runs on decides both *which* commands resolve and *what they act on*. The verb is therefore a
session's, not the client's, and takes the shape `channel:send` already has.

**Three pieces, and one of them is a refusal.**

1. `Permission.CONSOLE_RUN` — key `console.run`, Lua `s:console():run`, line *"run any of the
   client's console commands, on any of your characters, including ones that run code outside the
   addon sandbox"*. `Permission`'s javadoc states the contract: one entry here plus one gate call,
   and `PermissionSet`, `requirePermission`, `Manifest.internal` and the consent dialog all follow.
2. `s:console()` — a lazily minted, interned `LuaValue` on `LuaSession`, exactly as `chatObj` is: a
   `consoleObj` field, a `OneArgFunction` in the session's method table, and a
   `HookApi.console(owner, user)` that builds the section beside the client-wide one already in that
   file. Its one verb, in order (the gate is always the first statement, D-213, so an addon that
   declared nothing hears about its manifest even when its argument was wrong too):
   `requirePermission(owner, Permission.CONSOLE_RUN)` · the receiver check ·
   `Args.str(a, 2, "s:console():run", "line", …)` · the empty and leading-colon refusals · resolve
   that session's `UI`, refusing a session with none · `u.cons.run(u.root, line)` inside the mirror
   of `ConsoleHost.done` · return the section.
3. `hafen.console():run(...)` **throws naming `s:console():run(line)`**. Registration is client-wide
   because `Console.setscmd` is static; running is not. Without this an author meets *has no verb
   'run'*, which says the call is wrong without saying what is right — the exact defect
   `ROADMAP.md` already files against the keyless collections.

**No `src/haven/` edit.** `UI.cons` and `Console.run(Console.Host, String)` are public,
`RootWidget` already implements `Console.Host`, and the one fork edit this path needs —
`Console.rawcmd()`, tagged `// addon:`, how `:lua` recovers the literal text `Utils.splitwords`
stripped — is already in the tree.

**Failure is the console's, not the caller's.** The body mirrors `ConsoleHost.done` exactly: catch
`Exception`, take `getMessage()` with a `toString()` fallback, then write to **both** exits —
`u.cons.out.println(msg)` and `u.error(msg)`, on the target session's own `UI`. It catches
`Exception` and not `Throwable`, which is what leaves `:die`'s `Error` propagating exactly as it
does from a typed line.

## Files to create / modify

| File | What |
|---|---|
| `src/io/brodgar/addon/Permission.java` | `CONSOLE_RUN`, beside `CLIENT_SETTINGS` — the other key that rewrites the client rather than the world |
| `src/io/brodgar/addon/HookApi.java` | `console(owner, user)`, the per-session section and its `run`; and the `run` refusal on the client-wide one |
| `src/io/brodgar/addon/LuaSession.java` | the `consoleObj` field and the `console` entry in the session's method table, copied from `chat` |
| `docs/addons/api/console.md` | the page becomes the console's two halves: the commands you register (client-wide) and one character's line. A `## Run a line (protected)` section, and the opening line stops calling the section unprotected |
| `docs/addons/api/session.md` | the `s:console()` row in the sections table |
| `docs/addons/guides/permissions.md` | the catalogue row, `console.*` among the legal groups, and `console.run` named as the widest key — the sentence that says so today is `widget.send`'s |
| `docs/addons/api/README.md` | the section's one-liner |
| `docs/addons/guides/hotkeys-and-commands.md` | the other direction — a button of your own doing what one console word already does |
| `docs/addons/runtime.md` | `## The console commands` |
| `docs/client/services.md` | the console row gains the per-`UI` `WidgetConsole` (that `:lo` is `setcmd` on it and closes *that* session), `Console.run(Host, String)`, and the fork's `rawcmd()` |
| `addons/111-the-command-and-the-line.1/` | the suite (`:t111`) |

## Risks & gotchas

- **`ConsoleHost.done` is copied, not called** — it is an instance method of a widget owning a
  `ReadLine`, and no line is read here.
- **Monitors: take none, and that is deliberate.** `docs/client/multi-session.md` records that the
  console *already* nests them — it runs inside the drawn UI's monitor and reaches into another
  session's — and gives anchor-then-member as the only direction anything may take. Synchronising
  on the target would invent a second order (member→anchor, member→member) that nothing in the tree
  takes today. `Console.run` locks nothing; a typed line merely happens to hold the drawn UI's. So
  this verb runs on its caller's thread exactly as a typed one does, and the page says so.
- **`Console.out` is a black hole until `GameUI.added` re-points it** at that character's System log
  (`Console.clearout` installs the sink). A line run at a character still on the character-selection
  screen works and its output is not seen — the notice half still is.
- **Nesting is safe, depth is not bounded.** `Console.run(Host, String)` sets its `host` and
  `rawtext` `ThreadLocal`s and restores the previous in a `finally`, so a `:run` from inside a
  command body is fine; a command that runs *itself* recurses until `Sandbox`'s watchdog cuts it. No
  guard is added — a bound would be invented here.
- **`:reload` is `AddonRegistry.queueReload()`**, not an immediate teardown, so `run("reload")` from
  a Lua handler returns and the layer is rebuilt at the next safe point. The page says so — the
  opposite is what an author will assume.
- **The suite cannot prove its own gate.** It declares `console.run`, so it proves the *effect*;
  the gate site is verified by reading that `requirePermission` is the first statement.

## Discarded alternatives

- **A client-wide `hafen.console():run(line)` on the drawn UI.** It could only ever reach the
  character on screen: `:lo` is `setcmd` on the per-`UI` `WidgetConsole` and its body is
  `sess.close()`, and `UI.Console.findcmd` walks that tree before any shared table, so `:act`,
  `:belt`, `:cam` and `:exportmap` are each one login's. "Log this alt out" would have had no
  spelling at all.
- **Raise a Lua error for a command that failed.** Every caller would reprint the message behind its
  own addon tag, so `zzz` would read as the addon's mistake rather than the console's answer. The
  console already owns a refusal channel and it is the one the user reads.
- **Accept a leading colon as a second spelling.** The colon *opens* the line and `Console` never
  sees it, so two spellings would be a dual style for one line. A refusal that names the right one
  costs nothing and teaches what the colon is.
- **`run(name, args…)`, pre-split.** `Utils.splitwords` handles quoting and escapes, and a pre-split
  form would either reimplement it or lose the literal text `Console.rawcmd()` exists to preserve —
  which is exactly what `:lua` reads.
- **Refuse `lua`.** It is the most useful command to reach, and refusing it would put the command
  typed most often outside the verb while the consent line can simply say what the key covers.
- **Leave `run` unprotected, as `on` is.** A verb that reaches `:lua` reaches the full standard
  library outside the sandbox, which is the widest thing an addon can do; ungated it would make the
  sandbox a formality.
- **Queue the line and run it on the next tick.** A typed line runs synchronously on the thread that
  read it; a queued one could not report having run at all.

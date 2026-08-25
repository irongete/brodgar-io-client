# 111 — The command and the line

## What & why

`hafen.console()` **registers** a command and nothing more. An addon puts `:scout` on the console and
is called when the user types it; nothing can type a line itself. The section is a door the API opens
inwards only.

That asymmetry costs three things at once. An addon that builds a surface of its own — a panel, a
button, a hotkey — cannot make it do what one console word already does, so `:lo`, `:act`, `:reload`
and every command another addon registered are reachable by hand alone. A suite cannot drive a client
command, so anything a command changes is checked by eye. And the two halves of this very section
never meet: `:on` names a word, `:list` reads the words named, and nothing says one.

**A console line belongs to a character, not to the client.** `UI.cons` is a `WidgetConsole` per
`UI`: `:lo` is `setcmd` on it and its body is `sess.close()`, `:gl` writes that tree's own graphics
prefs, and `UI.Console.findcmd` walks **that** widget tree before it ever reaches the static table —
so `:act`, `:belt`, `:afk`, `:cam` and `:exportmap` each answer for the character whose tree holds
the widget that registered them. A line is therefore said *at* a login, exactly as `channel:send` is,
and the verb that says one is addressed the same way.

This feature ships that verb: one line run through one character's own console, exactly as though the
user had typed it there. The colon is the console's *opener*, never part of the line the client runs.

## Acceptance criteria

1. `s:console()` is that character's console line — a per-session section, the same object every
   call, beside `s:chat()` and `s:ui()` in the session's own table. It refuses a session that has no
   `UI` yet, naming `s:exists()` as the test.
2. `s:console():run(line)` runs `line` through **that character's** console — the same dispatcher,
   the same tree walk, the same three resolution tiers — and hands the **section** back, so writes
   chain. A character the player is not looking at runs its own line: `s:console():run("lo")` logs
   out the character `s` names, drawn or not.
3. It is **protected** by a new catalogue key `console.run`, whose consent line says plainly that it
   covers every console command on any of your characters, `:lua` included, and therefore code
   outside the addon's sandbox. It is the widest key in the catalogue and the page says so. The
   refusal names the verb, the key and the manifest line to paste.
4. It refuses, naming what is wrong: a missing or non-string `line`, an empty or whitespace-only one,
   and one that **opens with `:`** — the last states the spelling without it.
5. A command that **fails** is not the caller's mistake and does not raise: its message goes exactly
   where the console puts it — that UI's `cons.out`, which after login is that character's System
   log, and its own on-screen notice. `run("zzz")` writes `zzz: no such command` there and returns
   normally.
6. A command registered with `hafen.console():on(name, fn)` is reachable through
   `s:console():run(name .. " …")`, with the same 1-based `args` split — `"quoted words"` grouping
   into one and `\` escaping the next character, because it is the client's own splitter doing it.
7. **`hafen.console():run(...)` throws naming `s:console():run(line)`.** Registration is client-wide
   and running is not, so the section an author reaches for first has to say where the other half
   lives rather than answer *has no verb 'run'*.

## Out of scope

- **The login screen's console.** It has a `UI` and no session, so `hafen.session()` holds no member
  to address it through. Every command that matters there is `Console.setscmd`-static and answers
  through any live character's console anyway.
- **Reading what a character's console resolves.** `Console.scommands`/`commands` are private with no
  accessor, so `s:console():list()` would need a `haven` edit. The section carries `run` alone.
- **A history on the entry.** `ConsoleHost.kb_histprev` remembers what was typed;
  `hafen.ui():entry()` does not. That is the entry control's business and would reach every entry in
  the API rather than any caller of this verb.
- **Unregistering a client command.** `Console` has no removal and none is added.

## Docs impact

Pages written: `docs/addons/api/console.md` (the section becomes both halves — your commands, and one
character's line) · `docs/addons/api/session.md` (the `s:console()` row) ·
`docs/addons/guides/permissions.md` (the catalogue row, and `console.*` among the legal groups) ·
`docs/addons/api/README.md` (the section's one-liner) · `docs/addons/guides/hotkeys-and-commands.md` ·
`docs/addons/runtime.md` (`## The console commands`) · `docs/client/services.md` (the per-`UI`
`WidgetConsole`, `Console.run(Host, String)`, and the fork's `rawcmd()`).

Derived impact set — `grep -rln "hafen\.console\|console command\|the console" docs/` gives 32 files.
Read against the two claims this falsifies — *"the section is unprotected"* (`console.md:7` alone)
and *"an addon adds a command"*, silent on running one (`console.md`, `api/README.md:160`,
`runtime.md:197`, `hotkeys-and-commands.md`) — the pages carrying either are exactly the five under
`docs/addons/` above. The remaining 27 name the console only as **where output is printed**
(`log.md`, `debugging.md`, `attribution.md`, `client/chat.md`, …), untouched here.

## Context files

Everything below is read by the feature's one task.

- `src/io/brodgar/addon/HookApi.java` · `Permission.java` · `Args.java` · `Section.java`
- `src/io/brodgar/addon/AddonManager.java` (`requirePermission`) · `LuaSession.java` (the session's
  own section table, and how `s:chat()` is minted and interned)
- `src/haven/UI.java` (`WidgetConsole`, `findcmd`) · `Console.java` · `ConsoleHost.java`
- `docs/addons/api/console.md` · `session.md` · `chat.md` (the suite reads the System log back) ·
  `README.md` · `guides/permissions.md` · `guides/hotkeys-and-commands.md` · `runtime.md`
- `docs/client/console.md` (the console's own map page) · `docs/client/multi-session.md` (the one legal
  monitor direction)
- `tools/docverbs.py`, `tools/refusalverbs.py` · `DOCUMENTATION.md`

# 111 — Tasks

One task. The feature is one verb, and its pages and its proof are that verb's.

- [x] **111.1 — `s:console():run(line)` runs a console command at one character.** Adds
      `CONSOLE_RUN` to `Permission` (key `console.run`, Lua `s:console():run`, consent line *"run any
      of the client's console commands, on any of your characters, including ones that run code
      outside the addon sandbox"*); `HookApi.console(owner, user)`, the per-session section, with
      `run` gated first, then the receiver check, `Args.str`, the empty and leading-colon refusals,
      that session's `UI`, and `cons.run(root, line)` wrapped in `ConsoleHost.done`'s own catch,
      returning the section; the `consoleObj` field and `console` entry on `LuaSession`, copied from
      `chat`; and the refusal on `hafen.console():run(...)` naming `s:console():run(line)`. Writes
      the seven pages `spec.md` names. Retires nothing — `on`, `list`, `count`, `find` and `get` are
      untouched, and registration stays client-wide because `Console.setscmd` is static. Run
      `tools/docverbs.py` and `tools/refusalverbs.py`, which now have a section, a verb and two
      refusals more to resolve.

      *Its suite* declares `console.run`, registers `t111probe` through `hafen.console():on`, and
      drives everything through `s:console():run` on the character it is run from. It asserts that
      **the two halves of the section meet** — `run("t111probe")` fires the handler the suite
      registered, which is the claim, not that a command exists; that the **client's own splitter**
      is what ran, by `run('t111probe one "two three"')` landing `args[1] == "one"` and
      `args[2] == "two three"`, quotes stripped and the pair kept whole; that `run` **hands the
      section back** and the section is **interned**, by
      `s:console():run("t111probe") == s:console()`.

      The claim the whole reshape rests on gets its own check: `run("gl")` — `gl` is `setcmd` on
      `UI`'s **own** `WidgetConsole`, not in the static table — must reach that character's
      per-instance tier and fail with `usage: gl SETTING VALUE`. A line that resolved against a
      shared registry could not produce that message at all, so it is what says the console was the
      session's. `:lo` is the same tier and is not driven, for the obvious reason.

      It `pcall`s five refusals and asserts each failed *and said why*: no argument (names `line`), a
      number (names the type), `""` (names that there is no command in it), `":reload"` — whose
      message must name the spelling **without** the colon, since that is the whole of what that
      refusal teaches — and `hafen.console():run("x")`, whose message must name `s:console():run`.

      Last, that a failing command is **not** the caller's error: it reads `s:chat()` for the
      `chat.system` channel, takes `message():count()`, calls `run("t111zzz")` inside a `pcall`, and
      asserts the `pcall` **succeeded** while the newest System line reads `t111zzz: no such
      command` — the console's refusal channel, in the console's own words, with no addon tag in
      front of it.

      `[manual]`: run `:t111` and read the on-screen notice — expect one error notice reading
      `t111zzz: no such command`, the same text the suite just found in the System log. A notice is
      drawn and gone; nothing can read one back.

      **The addressing is proven when a second login exists, and only then.** If
      `hafen.session():count() > 1`, the suite takes a session that is not `:current()`, runs `gl` at
      it, and asserts the `usage:` line landed in **that** character's System log while the drawn
      character's count did not move — one line, two chats, and the wrong answer is visible either
      way round. With one login there is no second chat to distinguish, so it prints instead
      `[manual] log a second character in and re-run :t111 -- expect one more [pass]`, which is the
      honest score rather than a check quietly not run.

      The gate itself is **not** in the suite and cannot be: an addon that declares `console.run` is
      granted it, so nothing it calls can reach the refusal. Verify it by reading that
      `requirePermission(owner, Permission.CONSOLE_RUN)` is the first statement of the verb.

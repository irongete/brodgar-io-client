# Testing protocol — area `addons`

> Read before writing or verifying a task's tests. `/plan` writes acceptance criteria against
> this file, `/implement` ships the task's suite, `/end` validates the log the maintainer pastes
> back.

## The rule: one task, one test addon

Every task ships its own addon under `addons/<NNN>-<feature>.<X>/`, where `<NNN>-<feature>` is the
task's spec folder and `<X>` its task number — e.g. task `033.2` of `specs/addons/033-ui-stylesheet/`
ships `addons/033-ui-stylesheet.2/`. The folder name IS the manifest `id` (the loader enforces it),
so every line the suite prints already carries the task it belongs to.

The suite **tests itself**. It asserts through the very `hafen.*` API the task shipped — the surface
under test is also the test tooling — and prints one verdict line per check. What a program cannot
do it hands to the maintainer as an explicit `[manual]` line.

**`/end` archives it (D-183).** Once the maintainer's verification passes, `/end`'s close step moves
the folder from the client's live `addons/` into the task's own spec folder —
`addons/<NNN>-<feature>.<X>/` becomes `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` (an
ordinary move; `git add -A` registers it as a rename) — and deletes
`bin/addons/<NNN>-<feature>.<X>/`.

**`bin/addons/` is the directory that matters at runtime.** The addon dir resolves beside the running
`bin/hafen.jar` (jar-sibling, not the process's working directory), so `bin/addons/` is what the
client scans at login regardless of launch method (`ant run` or `bin/run.bat`). A suite left there
still appears in-game even after the source-side move above, so `/end` re-lists `bin/addons/` to
confirm the folder is actually gone before closing the task — a running client holds its loaded
addon's files open on Windows, so a delete attempted mid-session can fail silently. If the folder is
still there, `/end` stops and asks the maintainer to close the client first.

The suite itself is untouched by archiving — same files, same `id`, still runnable — it just stops
being one of the folders the client scans at login. See *Regression* below for how to run it again.

**And it stands ALONE (D-085).** Running `:t<NNN>-<X>` and nothing else is the whole verification of
that task: the suite installs what it needs, asserts what its task claims, and cleans up after
itself. It never asks for an earlier task's command to be run first, and it never rests on an
assertion that lives only in another suite. **Where its own proof needs something an earlier suite
already checks, DUPLICATE the assertion** — a repeated line costs one line and buys a suite you can
run in isolation, which is the only kind worth having when one task is what you are testing. So the
suites are independent by construction: any order, one at a time, or one alone.

## What a suite prints

Three markers, one line per check, nothing else:

```
[pass] the sheet accepts a positional colour
[fail] an unknown property is refused -- got: <no error>
[manual] open the chat and read a line -- expect: grey-blue text, font unchanged
[summary] 12 pass, 1 fail, 2 manual
```

- `[pass] <what was proven>` — past tense of the claim, not "check 4 ok".
- `[fail] <the claim that did not hold> -- got: <what actually happened>`. A fail must say enough to
  be diagnosed from the pasted line alone.
- `[manual] <what to do> -- expect: <the exact expected result>`. Written so it can be executed
  without the conversation: which key, which window, what should appear.
- `[summary] N pass, N fail, N manual` closes every run.

The engine prefixes each line with the addon id (`[033-ui-stylesheet.2] [pass] …` on the terminal,
`033-ui-stylesheet.2: [pass] …` in-game), so the block identifies its task by itself.

**The maintainer pastes the whole block back into the conversation**, writing the observed result on
each `[manual]` line, and the agent validates it directly. That round trip is the point of the
format: nothing needs interpreting.

## Automate everything the API can see

- Every acceptance criterion that is **readable through `hafen.*` becomes an assertion**. If a value
  can be read back, do not print it for a human to eyeball — compare it and emit `[pass]`/`[fail]`.
- **Refusals are checks too**: `pcall` the bad call and assert that it failed *and* said why. A
  hard-cut API is checked as `hafen.font.setFont == nil`, not as prose.
- **Round-trip what you write**: install, read back through a different door, undo, read again.
- `[manual]` is only for what a program genuinely cannot do — press a key, judge how something
  looks, confirm a server-side effect, drive a gated action on the maintainer's real character.
  Everything else is a missing assertion, not a manual step.
- **Re-assert whatever your own proof rests on, however old it is.** A suite is read alone and must convince
  alone, so a check another task's suite also makes is not a duplicate to delete — it is *this* task's
  premise, stated where it can fail. What does not belong here is coverage of a prior feature none of this
  task's claims rest on: that is the other suite's job.
- **Assume the other suite is never run.** Past suites stay installed, but in practice a login runs
  only the current task's command. A task that changes old behaviour **brings the old assertion into
  its own suite** — every spelling it retires, every premise it leans on, every row an older suite
  happens to pin — and **never reports "also run `:tNNN-X`" as part of its own proof**. If verifying
  this task needs another task's command, the check is missing from this suite. (Example, 041.2: it
  moved `hafen.hook():action/:message`, whose *dotted* forms `:t039-1` also pins, so `:t041-2` asserts
  all four spellings itself and leans on nothing external.)

## Skeleton (copy this into a new suite)

```lua
-- 033.2 — colour in the UI stylesheet. Self-checking suite; see specs/addons/TESTING.md.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local ok = pcall(hafen.ui.skin, { chat = { color = { 200, 210, 220 } } })
  check(ok, "the sheet accepts a positional colour")
  eq("the hard cut is still cut: hafen.font.setFont", hafen.font.setFont, nil)
  refuses("an unknown property is refused",
          function() hafen.ui.skin{ chat = { colour = { 1, 2, 3 } } } end, "unknown property")
  manualCheck("open the chat and read a line", "grey-blue text (200,210,220), font unchanged")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t033-2", run)   -- the only way in: a suite does not start itself
```

**Copy the shape, verify the calls.** This example is 033.2's own suite and was written against the API
at the time; a section is always `hafen.<name>():<verb>(...)` (a colon call on the CALLED section — see
`docs/addons/api/conventions.md`), never `hafen.<name>.<verb>(...)`. The dotted form throws, naming the
colon-call replacement (`Retired.NAMES`) — and if that throw happens at file scope (not inside a function),
the whole addon fails to load and its slash command never registers, which surfaces in-game as a bare
"no such command" with no hint why (042.11, `learnings/testing-tooling.md`). Before handing a new suite to
the maintainer, load it through the 033.3-style headless probe (`learnings/testing-tooling.md`) and check
`owner.error == null` and the command is in `owner.slashCommands` — that catches this class of bug in one
run, before a verification round is spent on it.

- **A suite NEVER starts itself.** No `OnEnterWorld`, no login timer. The maintainer runs it when
  they want it, and that is the whole scheduling model — nothing needs a startup slot to avoid
  colliding with another suite, because nothing else starts on its own either.
- **One short command, `:t<NNN>-<X>`** (`:t033-2`) — unique per task, so suites never collide.
- **Why no auto-run**: every suite installs a client-wide sheet and bumps `Fonts.gen()` while it
  runs, so two suites firing near each other race on that shared state. A hand-run command has
  nothing to race against.
- **The one ordering rule is manual**: let a running suite finish before starting the next one.
- Keep a suite short (roughly ≤ 15 lines of output). If a task needs more, it was two tasks.

## manifest.json

```json
{
  "name": "033.2 — colour in the UI stylesheet",
  "id": "033-ui-stylesheet.2",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 033.2: <what it proves>.",
  "api_version": 1,
  "files": ["main.lua"]
}
```

- `id` **must** equal the folder name. `version` starts at `1.0.0` and only moves if the suite does.
- **A suite is READ-ONLY: never declare `permissions`.** A write-declaring addon is disabled by
  default (D-027/D-028) and would silently drop out of the regression. Test a gated verb by asserting
  that the **gate refuses**; the firing demo belongs in an example addon (`walker`) or a `[manual]`
  line.
- **Never mutate persistent state** — real client settings, map markers, another addon's saved
  variables. A suite runs on the maintainer's real character, as often as they care to ask.

## Regression

**A closed task's suite is archived, not left installed** (see above): at any moment `addons/` holds only
the example addons, frozen `hello`, and whichever suite is currently in flight (implemented but not yet
`/end`-ed). Every closed suite still exists, unchanged, under
`specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` — copy the folder back into the client's
`addons/` and `:reload` if you ever want to run it again.

There is no fixed list of commands here — it would go stale the moment the next task closes. To see
exactly what is archived and runnable:

```
ls specs/addons/*/addons/
```

Each folder name `<NNN>-<feature>.<X>` is one command: `:t<NNN>-<X>`.

**Running the full regression is something the maintainer opts into, not a step any task performs.**
The working assumption for every task is that none of it runs. That is why the duplication rule
exists: a task that changes old behaviour carries the old assertion into its own suite (see *Automate
everything*, above) instead of relying on an older suite ever being run again. A red line names the
task that broke, but **verifying ONE task is running ONE command** — no suite is a precondition for
another, and none has to be run to make a different one meaningful.

**Never edit an old suite just to make it pass** — a red line in an old suite is the regression doing
its job, and only the maintainer decides to remove or disable one. The one standing exception is a
suite's own schedule: bumping its version to change *how or when* it runs, without touching what it
checks, needs no finding behind it. Archiving is not disabling — an archived suite is exactly as
green as the day it closed, just no longer loaded at login.

## Headless pre-check

A per-task suite is small and loads without the whole API, so `/implement` can dry-run one under the
LuaJ jar against a stub `hafen` before handing over (see `learnings/testing-tooling.md`, 030.4 /
031.3). Do it whenever the suite's own logic is non-trivial, and report the counts in `HANDOFF.md`.

## Not tests: example addons and `hello`

- `bags`, `walker`, `planner`, `widgetstack`, `hogtest`, `netdemo`, `optionstest`, `profiler` are
  **documentation demos** referenced from `docs/addons/`. They keep that role and are not suites.
- **`addons/hello/` is FROZEN — no new task extends it.** Touch it only when a change breaks it,
  then fix it, do not grow it. Its coverage runs 001–033.
- **None of the above are per-task suites, so `/end`'s archiving step (D-183) never touches them** —
  they stay in the client's `addons/` permanently, unlike `<NNN>-<feature>.<X>/` folders.

# Testing protocol — area `addons`

> Read before writing or verifying a task's tests. `/plan` writes acceptance criteria against
> this file, `/implement` ships the task's suite, `/end` validates the log the maintainer pastes
> back. It replaces the old "every task extends `hello`" harness.

## The rule: one task, one test addon

Every task ships its own addon under `addons/<NNN>-<feature>.<X>/`, where `<NNN>-<feature>` is the
task's spec folder and `<X>` its task number — e.g. task `033.2` of `specs/addons/033-ui-stylesheet/`
ships `addons/033-ui-stylesheet.2/`. The folder name IS the manifest `id` (the loader enforces it),
so every line the suite prints already carries the task it belongs to.

The suite **tests itself**. It asserts through the very `hafen.*` API the task shipped — the surface
under test is also the test tooling — and prints one verdict line per check. What a program cannot
do it hands to the maintainer as an explicit `[manual]` line.

**And it stands ALONE (D-085).** Running `:t<NNN>-<X>` and nothing else is the whole verification of that
task: the suite installs what it needs, asserts what its task claims, and cleans up after itself. It never
asks for an earlier task's command to be run first, and it never rests on an assertion that lives only in
another suite. **Where its own proof needs something an earlier suite already checks, DUPLICATE the
assertion** — a repeated line costs one line and buys a suite you can run in isolation, which is the only
kind worth having when one task is what you are testing. So the suites are independent by construction:
any order, one at a time, or one alone.

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
  task's claims rest on: that is the other suite's job, and it still runs.

## Skeleton (copy this into a new suite)

```lua
-- 033.2 — colour in the UI stylesheet. Self-checking suite; see specs/addons/TESTING.md.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log("[fail] " .. what .. " -- got: " .. tostring(got))
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
  hafen.log("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local ok = pcall(hafen.ui.skin, { chat = { color = { 200, 210, 220 } } })
  check(ok, "the sheet accepts a positional colour")
  eq("the hard cut is still cut: hafen.font.setFont", hafen.font.setFont, nil)
  refuses("an unknown property is refused",
          function() hafen.ui.skin{ chat = { colour = { 1, 2, 3 } } } end, "unknown property")
  manualCheck("open the chat and read a line", "grey-blue text (200,210,220), font unchanged")
  hafen.log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash.register("t033-2", run)   -- the only way in: a suite does not start itself
```

- **A suite NEVER starts itself.** No `OnEnterWorld`, no login timer. The maintainer runs it when they want
  it, and that is the whole scheduling model.
- **One short command, `:t<NNN>-<X>`** (`:t033-2`) — unique per task, so suites never collide.
- **Why (035.3, learned the hard way).** Suites used to auto-run on login and therefore needed a *slot*
  (`+3`, `+6`, `+9`, …) to stay out of each other's way, because every suite installs a client-wide sheet and
  bumps `Fonts.gen()` while it runs. 034.2's round staged 3.4 s from `+6` and 034.3 started at `+9`, so a
  0.4 s overlap made 034.2 read two text-cache keys where it expects one — a **race** that passed for two
  whole features and then reddened a line in a suite nobody had touched. Each fix was one more constant to
  get wrong. Auto-start created the schedule, the schedule created the race; running by hand deletes both.
- **The one ordering rule left is the operator's**: let a staged suite finish before starting the next
  (034.2 ~3.4 s, 035.2 ~2.5 s). It is no longer a number in a file.
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

Every past suite stays installed, and **the full regression is running their commands** — one at a time, in
any order:

```
:t033-3  :t034-1  :t034-2  :t034-3  :t035-1  :t035-2  :t035-3  :t035-4
:t036-1  :t036-2  :t036-3  :t036-4  :t037-1  :t037-2  :t037-3  :t037-4  :t037-5
:t038-1  :t038-2  :t038-3  :t038-4
:t039-1  :t039-2  :t039-3  :t039-4  :t039-5  :t039-6  :t039-7  :t039-8  :t039-9
```

A red line names the task that broke. **But verifying ONE task is running ONE command** — no suite is a
precondition for another, and none has to be run to make a different one meaningful. That is what the
duplication rule above buys, and it is the difference between a regression you *choose* to run and a
protocol you have to obey. Never edit an old suite to make it green: that line is the regression
doing its job. (A suite's *schedule* is not an assertion — 035.3 removed every suite's auto-start, with a
version bump each, and that is the only kind of edit an old suite takes without a reason of its own.) Only
the maintainer removes or disables a suite.

## Headless pre-check

A per-task suite is small and loads without the whole API, so `/implement` can dry-run one under the
LuaJ jar against a stub `hafen` before handing over (see `learnings/testing-tooling.md`, 030.4 /
031.3). Do it whenever the suite's own logic is non-trivial, and report the counts in `HANDOFF.md`.

## Not tests: example addons and `hello`

- `bags`, `walker`, `planner`, `widgetstack`, `hogtest`, `netdemo`, `optionstest`, `profiler` are
  **documentation demos** referenced from `docs/addons/`. They keep that role and are not suites.
- **`addons/hello/` is FROZEN.** It was the single growing harness through feature 033 and stays
  installed for the coverage it already carries (001–033), but **no new task extends it**. Touch it
  only when a change breaks it — then fix it, do not grow it.

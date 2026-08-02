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
- Cheap re-checks of *prior* features belong in their own task's suite; do not re-assert them here.

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

hafen.events.on("OnEnterWorld", function() hafen.timer.after(3, run) end)  -- HUD data streams in
hafen.slash.register("t033-2", run)                                       -- re-run on demand
```

- **Runs itself on every login** (`OnEnterWorld`, which also re-fires on `:reload`), after the short
  delay the streaming HUD/character data needs.
- **Re-runnable on demand** via one short command, `:t<NNN>-<X>` (`:t033-2`) — unique per task, so
  suites never collide.
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
  variables. A suite runs on every login, on the maintainer's real character.

## Regression

Every past suite stays installed and re-runs on every login, so **one login is still the full
regression** — but now it is per-task, and a red line names the task that broke. Never edit an old
suite to make it green: that line is the regression doing its job. Only the maintainer removes or
disables a suite (the AddOns panel is the escape hatch if a login ever gets too noisy).

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

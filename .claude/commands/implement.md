# /implement — execute ONE task

Usage: `/implement` → the first unchecked task of the active feature · `/implement <NNN.X>` → force
a specific one.

**Commits NOTHING**, not even after a fix round. `/end` commits the whole task once the maintainer's
verification passes.

## 1. Read — and nothing else

1. **Derive the active feature** — no file records it:
   ```bash
   grep -l '^- \[ \]' specs/[0-9]*/tasks.md
   ```
   Report every folder it returns. One is the normal case; more than one means an earlier feature
   was re-opened, so name them all and ask which to take. No unchecked box anywhere means there is
   nothing to implement — say so and stop.
2. That feature's **`tasks.md`** → the first unchecked task (or the one named), then its
   **`spec.md`** and **`plan.md`**.
3. The spec's **`Context files:`**, plus this task's own **`extra context:`** if it carries one. If
   the task genuinely needs a file the list does not name, read it **and report that the budget was
   short**, naming the file and why it was needed: the list is what keeps the next task from
   searching, and it stays true only if a gap is said out loud.
4. The **`docs/addons/` pages** of every surface the task touches, the **`docs/client/` pages** of
   the subsystems it works against, and **`DOCUMENTATION.md`** — before writing a page, not after.

The working tree may hold other work in flight — a task someone else has open, an edit of the
maintainer's, a scratch file. **Leave every bit of it alone: never stage it, never report it, never
ask about it.** `/end` commits the explicit list of paths THIS task writes and nothing besides, so
what is already in the tree cannot reach your commit. Nothing records that a task is in flight, and
nothing needs to: what you wrote in this context is the list.

## 2. Implement the ONE task

`ant hafen-client` must pass. Pre-check the logic headlessly where feasible (`jshell`, the `:lua`
REPL). Remember a Java change needs the rebuild **and a full client restart**.

## 3. Ship the task's suite

The rules are in `CLAUDE.md` — one addon at `addons/<NNN>-<feature>.<X>/`, standing alone,
automating everything the API can read back. The shape:

```lua
-- 033.2 — colour in the UI stylesheet. Self-checking suite.

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

hafen.console():on("t033-2", run)   -- the only way in: a suite does not start itself
```

`manifest.json` — `id` **must** equal the folder name, `version` starts at `1.0.0`:

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

**Copy the shape, verify the calls** against today's API: a section is `hafen.<name>():<verb>(...)`,
never `hafen.<name>.<verb>(...)`. The dotted form throws — and a throw at **file scope** kills the
whole addon, so the console command never registers and the maintainer sees a bare "no such command"
with no hint why. A suite MAY declare the permission keys its own task needs to prove; the
enable-and-approve is then part of the verification, and the keys it did *not* ask for must still
refuse.

## 4. Ship the task's documentation, in this same task

*The task that publishes a surface publishes that surface's pages.* Retiring a spelling may have its
docs sweep as the next task of the same feature, and the close charges it. Nothing else waits.

- Write the pages to `DOCUMENTATION.md`'s standard, and re-point every link into any page you move
  or retitle, in this same task.
- Run its §11 checks over the pages you touched and report the counts. The **impact set** the
  `spec.md` derived is discharged **once per feature**, by the task whose surface owns it — each
  page of it revised, or explicitly discharged with its reason.
- **Run the checkers over what you moved**: `python tools/docverbs.py` and
  `python tools/retiredverbs.py`. They hold `docs/` to `src/` by **receiver type**, which is the
  drift a task that renames a verb or an event key creates and cannot see in its own diff. Both exit
  non-zero; fix here, not at the close.
- **A retired spelling is retired everywhere, `addons/` included**: `grep -rl` the old name over
  `addons/*/` and fix every demo in this same task. Nothing runs them, so the alternative detector
  is a reader of `examples.md` months from now.
- **Pay the map toll, in this same task**: if this task **or the `plan.md` it works from** leaned on
  upstream `haven` that no `docs/client/` page covers, write or extend that page now, to
  `DOCUMENTATION.md` §12 — **opening the source yourself**, not transcribing the plan, because the
  map is checked against `src/`. Class and member names, **never a line number**, upstream only,
  ≤150 lines. If a page you leaned on turned out to be wrong about `src/`, fix it here too: `src/`
  always wins.
- **Every finding is routed, and every route lands inside this feature.** Nothing is left as a note
  for later, and **no command writes `specs/ROADMAP.md`** — that file is the maintainer's:
  - **A gap or a defect in the surface THIS FEATURE ships** — the `hafen.*` spellings its tasks add
    or change, and the `src/io/brodgar/**` those run through — **is not a finding, it is a task that
    was missing.** Raise it at the close: it becomes a new task in this feature's `tasks.md`, or the
    maintainer takes it out of scope and its reason goes into `plan.md`'s *Discarded alternatives*.
    The feature does not close while one of them stands undecided.
  - **A defect or a trap in upstream `haven`** becomes a **gotcha on that subsystem's `docs/client/`
    page**, written here, in this same task, to `DOCUMENTATION.md` §12 — unless this task cannot
    make its own claim true without the fix, in which case the fix is part of the task and the page
    records what it now does.
  - **A page your own writing pushes over its ceiling** is **split here**, by `DOCUMENTATION.md`
    §9: split by subject, price it by the inbound anchors, re-point them in this same task.
  - **Anything else** — a capability the client has not got, an idea past this feature's boundary —
    is **one sentence in the close report**, for the maintainer to queue or drop.

## 5. Stop, and iterate — in THIS context, through to the close

Do NOT check the task off: **this context implements the task, verifies it and closes it.** `/end`
runs here, holding everything you just did; the uncommitted working tree is the record, and it is
the one record that cannot go stale.

**Install the suite where the client reads it, and re-install after every fix round.** The running
client scans `bin/addons/`, not `addons/` — say which copy you made, and repeat it each round. An
un-synced fix has the maintainer re-run the previous build of your suite and paste a result that is
not yours.

**A question the three files already answer is not a finding — it is a part of the task you have
not built yet.** Before anything reaches the report, put it against `plan.md`'s *Discarded
alternatives*, this task's own line in `tasks.md`, `spec.md`'s *out of scope*, and the API grammar
in `CLAUDE.md` — which settles far more naming, arity and shape than a question tends to assume.
If any of them decides it, **build what they decided and say nothing about it.** What survives is
raised **quoting the sentence that came closest and saying what it leaves open**; where there is no
such sentence to quote, there is nothing to raise. Handing a settled question back costs the
maintainer a round trip to answer it out of a file this context had open, and this command counts
that exactly as it counts missing a real gap.

**Report what the task found, beside its one command.** A gap in this feature's own surface is a
decision the maintainer takes before the feature can close: name it, and say whether it reads as a
new task or as a line for *Discarded alternatives*. A task added mid-feature is written to
`tasks.md`'s own template and density; if the feature stops fitting that budget it was mis-scoped,
and the maintainer splits it. Anything past the boundary is one sentence in the same report.

**Report exactly one command: `:t<NNN>`.** Never ask for a second one — if verifying this task
needs another suite, the assertion is missing from this one. Then stay with the maintainer for as
many rounds as it takes: they run it and paste the log, you read every `[fail]` and every answered
`[manual]` line, fix, re-install, they re-run. Still one task, still no commit — when they are
satisfied, they run `/end`, and you must still be here.

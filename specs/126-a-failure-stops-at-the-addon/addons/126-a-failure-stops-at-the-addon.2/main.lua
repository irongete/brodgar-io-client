-- 126.2 -- the instruction budget is one per entry, whatever thread entered. Suite: run with :t126.
--
-- The cap is documented as ten million instructions PER ENTRY into your Lua -- a handler, a timer, a
-- draw, a file body -- and this task moves it from one counter per environment to one per entry, held
-- on the thread that entered. Two halves are readable from Lua:
--
--   * the cap still stops a runaway, and still says so. A budget that was made per entry and stopped
--     firing would be no budget at all, so this is asserted first, and again at the end -- after the
--     twenty entries below -- because the watchdog resets itself when it trips and a budget left reset
--     would leave the client with nothing standing between it and the next infinite loop.
--   * twenty entries in a row, each burning a QUARTER of the cap, all return. Four such entries add up
--     to the whole cap, so a budget that drained across entries instead of being armed afresh would
--     abort the fifth and every one after it. That is the check, and it is written as twenty separate
--     TIMER callbacks because a timer body is an entry through the client's own choke point -- twenty
--     calls inside one entry would prove nothing.
--
-- The remaining half -- two THREADS inside one addon's Lua at once, which is what a shared counter got
-- wrong -- an addon cannot cause: it needs an action or an inbound message handler landing off the
-- step while the step is inside the same environment, which is the server's timing and not this
-- suite's. It is verified at the site instead.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local CAP     = 10000000   -- the documented per-call cap, in VM instructions
local BURN    = 1250000    -- iterations of the loop below; two instructions each, so a QUARTER of the cap
local ENTRIES = 20

local completed, sink = 0, 0

-- A loop with no bound the cap does not impose: the counter runs past CAP iterations, which is twice
-- CAP instructions, so it can only end by being aborted.
local function runaway()
  local s = 0
  for i = 1, CAP do s = s + i end
  return s
end

-- One entry's worth of work: a quarter of the cap, deliberately close enough to it that four of these
-- would exhaust a budget that was shared between them.
local function burner()
  local s = 0
  for i = 1, BURN do s = s + i end
  sink = sink + s
  completed = completed + 1
end

local function finish()
  check(completed == ENTRIES,
        "twenty entries in a row each got a full budget (" .. ENTRIES .. " x a quarter of the cap)",
        completed)
  refuses("...and the cap is still armed after them",
          runaway, "instruction budget exceeded")
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

local function body()
  refuses("a loop past the instruction cap is aborted, naming the budget",
          runaway, "instruction budget exceeded")
  for _ = 1, ENTRIES do
    hafen.timer():after(0, burner)   -- all due on the next tick: one tick, twenty entries
  end
  hafen.timer():after(0.5, finish)   -- a later tick, so every burner above has been and gone
end

-- EVERYTHING ABOVE RUNS ON THE STEP, not on the console line: a command runs inside the UI of the
-- console it was typed into, holding that character's tree monitor.
local function run()
  hafen.timer():after(0, body)
end

hafen.console():on("t126", run)   -- the only way in: a suite does not start itself

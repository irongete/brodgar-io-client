-- 130.2 -- the overhead figure says what its method cannot see. Self-checking suite.
--
-- overhead() measures what profiling SPENDS: the median work time of a batch of armed frames minus its
-- control frame's. A control frame disarms the probes and leaves everything the profiler HOLDS exactly
-- where it was, so a cost that lives in retention stands on both sides of that subtraction and cancels to
-- zero. ringFrames is what the surface reports the held side as. Nothing here can weigh that retention --
-- no verb reports a collection pause -- so what this run proves is the key itself: that it answers while
-- armed, that it is ABSENT rather than 0 while disarmed, that the armed-only figures beside it still
-- answer, and that it goes away again with the switch rather than standing on the table for ever.

local SETTLE = 2        -- seconds of armed frames before the reads, so the mean frame time has samples

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- A bridge refusal reaches Lua as "@chunk.lua:189 the message", so the chunk stamp comes off first.
local function why(err)
  local m = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
  m = m:gsub("%s+", " ")                       -- a traceback is many lines; a log line is one
  if #m > 160 then m = m:sub(1, 160) .. "..." end
  return m
end

-- How many keys the table has, so an "empty" that is not empty says what it held instead.
local function keys(t)
  local n, first = 0, nil
  for k in pairs(t) do
    n = n + 1
    if first == nil then first = tostring(k) end
  end
  return (n == 0) and "empty" or (n .. " keys, e.g. " .. tostring(first))
end

-- The armed half: the key this task adds, and the two figures the page says stand beside it.
local function armedReads(p)
  local o = p:overhead()
  local r = o.ringFrames
  check((type(r) == "number") and (r > 0) and (r == math.floor(r)),
        "armed, overhead() answers ringFrames as a positive whole number of frames (" .. tostring(r) .. ")",
        tostring(r) .. " -- the retention the control-frame method cannot see has no other reader")
  check((type(o.budget) == "number") and (o.budget > 0),
        "armed, budget still answers (" .. tostring(o.budget) .. ")", tostring(o.budget))
  check(type(o.withinBudget) == "boolean",
        "armed, withinBudget still answers (" .. tostring(o.withinBudget) .. ")",
        tostring(o.withinBudget) .. " -- absent means no frame has been folded yet, so wait and re-run")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local p = hafen.client():profiling()
  local opts = hafen.client():options():client()
  local was = opts:profiling()          -- the switch is persisted, so this run puts back what it found

  local ok, err = pcall(function() opts:profiling(false) end)
  check(ok and (opts:profiling() == false), "profiling disarms through the client options",
        ok and opts:profiling()
           or ("refused (" .. why(err) .. ") -- grant this addon client.settings and re-run"))
  if not (ok and (opts:profiling() == false)) then
    summary()
    return
  end

  local off = p:overhead()
  check(next(off) == nil, "disarmed, overhead() is the empty table", keys(off))
  check(off.ringFrames == nil,
        "disarmed, ringFrames is absent rather than 0 -- an absent key means not measured, never zero",
        tostring(off.ringFrames))

  ok = pcall(function() opts:profiling(true) end)
  check(ok and (opts:profiling() == true), "profiling arms through the same option", opts:profiling())
  if not (ok and (opts:profiling() == true)) then
    opts:profiling(was)
    summary()
    return
  end

  hafen.log():write("[note] " .. SETTLE .. " s of armed frames, then the reads -- wait for the summary")

  local elapsed, sub = 0, nil
  sub = hafen.event():on("Update", function(dt)
    elapsed = elapsed + (dt or 0)
    if elapsed < SETTLE then return end
    sub:off()

    armedReads(p)

    -- And off again: the key follows the switch. A constant the surface always emitted would pass every
    -- check above and still break the armed-only rule the whole page rests on.
    opts:profiling(false)
    local o = p:overhead()
    check((next(o) == nil) and (o.ringFrames == nil),
          "disarmed again, the table empties and ringFrames goes with it", keys(o))

    opts:profiling(was)
    check(opts:profiling() == was,
          "the switch is left as this run found it (" .. tostring(was) .. ")", opts:profiling())
    summary()
  end)
end

hafen.console():on("t130", run)   -- the only way in: a suite does not start itself

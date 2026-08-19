-- 081.1 — the screen is a value an addon can write. Self-checking suite.
--
-- Two accounts must be logged in (`:session add`) before this runs: the whole of what it proves is
-- that the screen MOVES, and one session has nowhere to move to.

local pass, fail, manual = 0, 0, 0
-- The verdict is HELD and printed in one block at the end. The in-game half of a line goes to the
-- character on screen, and this suite moves the screen: printed as they ran, three of the lines
-- would land in the other character's console and the block would arrive in two pieces.
local lines = {}

local function say(line)
  lines[#lines + 1] = line
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    say("[pass] " .. what)
  else
    fail = fail + 1
    say("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why. The match is against the whole
-- error and the trim is for the report alone, so a traceback carrying the message cannot hide it.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  local raw = ok and "<no error>" or tostring(err)
  local shown = raw:gsub("\nstack traceback.*", ""):gsub("^.-%.lua:%d+:?%s*", "")
  check((not ok) and (raw:find(wantMsg, 1, true) ~= nil), what, shown)
end

-- Printed at once, and not held: it is what the maintainer has to be watching WHILE the run moves
-- the screen, and it goes up before the first switch, so it lands in the same console as the rest.
local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  say(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  for _, line in ipairs(lines) do
    hafen.log():write(line)
  end
end

-- The events are drained on the tick, so nothing the write causes has happened when it returns:
-- poll for it over a bounded window and score over what the run reached.
local function waitFor(test, window, done)
  local left, t = window, nil
  t = hafen.timer():every(0.2, function()
    left = left - 0.2
    if test() then
      t:cancel()
      done(true)
    elseif left <= 0 then
      t:cancel()
      done(false)
    end
  end)
end

local function run()
  local coll = hafen.session()
  local start = coll:current()
  local other
  for _, s in ipairs(coll:list()) do
    if s ~= start then other = s end
  end
  if (start == nil) or (other == nil) then
    check(false, "two accounts are logged in, so there is a screen to move",
          coll:count() .. " session(s)")
    return summary()
  end

  manualCheck("watch the screen while this runs -- the verdict lands about 8s in",
              "the screen moves to the other character and comes back")

  refuses("a non-Session argument is refused",
          function() coll:current(start:user()) end, "must be a Session")
  refuses("an explicit nil is refused",
          function() coll:current(nil) end, "must not be nil")
  refuses("a session the client does not hold is refused",
          function() coll:current(hafen.session():get("nobody-is-here")) end, "holds no session")

  local seen = {}
  local sub = hafen.event():on("SessionSelected", function(s) seen[#seen + 1] = s end)

  local back = coll:current(other)
  check(coll:current() == other, "the screen is the session that was written",
        tostring(coll:current()) .. " ~= " .. tostring(other))
  check(back == coll, "the write hands the collection back, so writes chain", tostring(back))

  waitFor(function() return seen[1] ~= nil end, 3, function(got)
    check(got and (seen[1] == other), "SessionSelected was handed that same Session",
          tostring(seen[1]))
    coll:current(start)
    check(coll:current() == start, "the screen goes back to the session it started on",
          tostring(coll:current()))
    -- The return trip's own event has to LAND before the no-op below can be measured: what proves
    -- the no-op fired nothing is that the count stops moving, and a queued event still in flight
    -- would be counted against it.
    waitFor(function() return seen[2] ~= nil end, 3, function(again)
      check(again and (seen[2] == start), "...and SessionSelected named the session it went back to",
            tostring(seen[2]))
      local fired = #seen
      coll:current(start)
      check(coll:current() == start, "writing the session already on screen leaves it there",
            tostring(coll:current()))
      waitFor(function() return #seen > fired end, 1.5, function(more)
        check(not more, "...and fires nothing", (#seen - fired) .. " extra SessionSelected")
        sub:off()
        summary()
      end)
    end)
  end)
end

hafen.slash():register("t081-1", run)

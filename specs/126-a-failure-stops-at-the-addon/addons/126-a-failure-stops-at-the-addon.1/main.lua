-- 126.1 -- a failure stops at the addon. Self-checking suite: run with :t126.
--
-- The half a program can read back is the containment that ALREADY worked, and it is here because this
-- task adds a third catch behind the two that do it: an ordinary refusal thrown by a bridge verb must
-- still be caught, logged and left behind, with the addon that raised it running afterwards. A change
-- that quarantined THAT would pass a test written only around the fatal case, so the regression is
-- caught here.
--
-- Nothing is pcall'd where the point is that the error ESCAPES: the refusal is raised inside a timer
-- callback and left alone, so the client's own choke point is what catches it. Two facts then prove the
-- addon survived -- an ordinary handler of ours goes on counting frames after that tick, and this whole
-- block is printed from a second timer scheduled behind the first. If the addon had been torn down,
-- neither the counter nor the block would exist to read.
--
-- The fatal half is [manual] because the addon is what stops: once the client contains a
-- StackOverflowError raised under this suite's Lua, this suite is torn down and can print nothing more.
-- :126crash is that failure, offered only after the checks above have printed.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local frames, watch = 0, nil       -- an ordinary handler of ours, ticking through the whole run
local entered, returned = false, false
local atRaise = -1

-- The escape: a bridge verb's own refusal, raised inside a callback and DELIBERATELY not caught here,
-- so the client's choke point is what catches it.
local function raiser()
  entered = true
  atRaise = frames
  hafen.event():on("NoSuchKey", function() end)
  returned = true                  -- never reached; a returned raiser proves nothing about containment
end

local function finish()
  check(entered and not returned,
        "a bridge verb's refusal escaped an uncaught callback", entered and "returned" or "never ran")
  check(frames > atRaise,
        "...and the addon's other handlers went on firing after the tick that failed",
        tostring(frames) .. " frames vs " .. tostring(atRaise))
  check(true, "...and its own timers did too, which is this block being printed at all")
  manualCheck("run :126crash, then open Options -> AddOns",
              "the client still running, and this addon's row reading auto-disabled")
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  if watch ~= nil then
    watch:off()
  end
end

local function body()
  refuses("an unknown bus key is refused, saying why",
          function() hafen.event():on("NoSuchKey", function() end) end, "unknown event")
  watch = hafen.event():on("Update", function() frames = frames + 1 end)
  hafen.timer():after(0, raiser)
  hafen.timer():after(0.5, finish)
end

-- EVERYTHING ABOVE RUNS ON THE STEP, not on the console line: a command runs inside the UI of the
-- console it was typed into, holding that character's tree monitor.
local function run()
  hafen.timer():after(0, body)
end

-- The fatal failure, unbounded recursion: not a tail call, so it grows the stack until the client is
-- out of it. No pcall of this addon's can see what that raises, which is the whole point of the task.
local function crash()
  local function recurse(n)
    return 1 + recurse(n + 1)
  end
  return recurse(1)
end

hafen.console():on("t126", run)        -- the only way in: a suite does not start itself
hafen.console():on("126crash", crash)  -- ...and the fatal half is a second gesture, never automatic

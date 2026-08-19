-- 081.3 — the client's own Sessions window is retired. Self-checking suite.
--
-- Two accounts must be logged in (`:session add`) before this runs: the window only ever appeared
-- with a second session to list, so with one login "there is no such window" proves nothing.

local SEL = "window[title=Sessions]"
-- Longer than the beat the window was rebuilt on, and a frame over: the check has to be made after
-- the client has had every chance to put one back, not before.
local SETTLE = 1.0

local pass, fail, manual = 0, 0, 0
-- The verdict is HELD and printed in one block at the end. The in-game half of a line goes to the
-- character on screen, and this suite hands the screen to every session in turn: printed as they
-- ran, the block would arrive in as many pieces as there are logins.
local lines = {}

-- Held state is a run's own, and the command can be run again -- the first go is often before the
-- second account is up. Without this the tally and the block are the sum of every run since the
-- last `:reload`, which reads as a failure the current run did not have.
local function reset()
  pass, fail, manual = 0, 0, 0
  lines = {}
end

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

-- Printed at once, and not held: it is the one thing the maintainer types themselves, and it goes
-- up before the first switch so it lands in the same console as the block that follows.
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

-- Every session's own tree, not just the drawn one's: a widget belongs to exactly one tree, and the
-- window was built into whichever character held the screen. `nil` means no tree holds one; anything
-- else is the offender, named by the account whose tree it stands in. A `find` that RAISES is the
-- window twice over, which is a failure and not a crash, so it is caught and reported as one.
local function offender(list)
  for _, s in ipairs(list) do
    local ok, w = pcall(function() return s:ui():find(SEL) end)
    if not ok then
      say("[note] " .. s:user() .. ": " .. tostring(w))
      return s:user() .. ": two or more matched " .. SEL
    elseif w ~= nil then
      return s:user() .. ": a " .. tostring(w:type()) .. " titled Sessions"
    end
  end
  return nil
end

local function scanned(what, list)
  local bad = offender(list)
  check(bad == nil, what .. " (" .. #list .. " tree(s) read)", bad)
end

local function run()
  reset()
  local coll = hafen.session()
  local start = coll:current()
  local list = coll:list()

  if (start == nil) or (#list < 2) then
    check(false, "two accounts are logged in, so a switcher would have had a list to draw",
          #list .. " session(s), screen on " .. tostring(start and start:user()))
    return summary()
  end
  check(true, "two accounts are logged in, so a switcher would have had a list to draw (" .. #list .. ")")

  manualCheck("run ':session wnd'",
              "the usage line 'usage: session add|drop|list|anchor|users' -- no window, and no 'wnd' in it")

  scanned("no session's tree holds a window titled Sessions, before any switch", list)

  -- The rebuild path, walked: the window stood on the DRAWN session's HUD and was built again every
  -- time the anchor moved, so the screen is handed to each session in turn and every tree is read
  -- once the beat it rebuilt on has gone by.
  local function step(i)
    if i > #list then
      coll:current(start)
      hafen.timer():after(SETTLE, function()
        local back = coll:current()
        check(back == start, "the screen is back on the session this run started from (" .. start:user() .. ")",
              back and back:user())
        scanned("...and none holds one after the screen has been round every session", list)
        summary()
      end)
      return
    end
    local s = list[i]
    coll:current(s)
    hafen.timer():after(SETTLE, function()
      scanned("none holds one with " .. s:user() .. " on screen", list)
      step(i + 1)
    end)
  end
  step(1)
end

hafen.slash():register("t081-3", run)   -- the only way in: a suite does not start itself

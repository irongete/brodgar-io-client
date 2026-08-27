-- 116.2 — a hidden ring is one the pointer cannot be over. Self-checking suite.
--
-- Type :t116 standing in the world, in the open, with a few objects around you.
--
-- The run raises TWO radial menus of its own by right-clicking nearby objects, hides each one, and
-- then asks you for ONE gesture at a time. Do each the moment its line appears -- every gesture is
-- something no addon can cause, and the run scores what it did.
--
--   1. press `1`      -- expect nothing at all to happen: the ring stays open, nothing is picked
--   2. click anywhere -- expect the ring to end, with nothing chosen
--   3. press Esc      -- expect the second ring to end, with nothing chosen
--
-- Nothing is on screen while you do any of it: that is the point. If gesture 1 DOES pick a petal
-- the guard is broken and the first petal of whatever was clicked fires, so stand somewhere that is
-- harmless -- beside a tree its first petal is a chop you can walk away from.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the
-- strip has to allow both shapes or the message is scored with its own location glued to the front.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local TRIES, TRY_EVERY = 6, 1.5      -- objects to try a right-click on, and how far apart
local DIGIT_FOR = 6                  -- how long you get to press 1, and how long the ring must last
local GESTURE_FOR = 25               -- how long you get to click, and to press Esc
local GIVE_UP = 120                  -- the whole run's bounded window

local s, addSub, remSub              -- the character, and the two bus subscriptions
local cands, phase, hunting          -- what to right-click, which ring we are on, whether a click is owed
local stage, ringCount, ended        -- the gesture waited on, the hidden ring's petals, and the label
local done, runId = true, 0          -- FlowerMenuRemoved carried if it fired at all

local function report()
  if done then return end
  done = true
  if addSub then addSub:off() end
  if remSub then remSub:off() end
  addSub, remSub = nil, nil
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- raising a ring at all ---------------------------------------------------------------------
local function hunt(forPhase, i, mine)
  if done or (runId ~= mine) or (phase ~= forPhase) or not hunting then return end
  if i > #cands then
    check(false, "a right-click raised ring " .. forPhase .. " to work on",
          "none of the " .. #cands .. " objects near you opened a menu -- stand somewhere with"
          .. " trees or bushes around you and re-run")
    return report()
  end
  local ok, err = pcall(function() s:world():click(cands[i], 3) end)
  if not ok then
    hafen.log():write("[note] right-clicking " .. tostring(cands[i]:name()) .. " was refused: "
                      .. why(err))
  end
  hafen.timer():after(TRY_EVERY, function() hunt(forPhase, i + 1, mine) end)
end

-- A ring that has been ended is NOT gone: the close is a three-quarter-second animation and the
-- widget stands in the tree for all of it, so a second ring raised inside that window really does
-- make the tree hold two, and every verb here then addresses the dead one. visible() is nil exactly
-- when that character has no menu in its tree at all, so wait for that before raising the next.
local function whenSettled(mine, tries, fn)
  if done or (runId ~= mine) then return end
  if s:flowermenu():visible() == nil then return fn() end
  if tries <= 0 then
    check(false, "the ended ring left the tree, so the next one is raised into a clear tree",
          "it was still fading after 4s")
    return report()
  end
  hafen.timer():after(0.25, function() whenSettled(mine, tries - 1, fn) end)
end

local function startHunt(forPhase)
  phase = forPhase
  hunting = true
  hunt(forPhase, 1, runId)
end

-- ---- gesture 3: Esc ends the second hidden ring --------------------------------------------------
local function askEsc(mine)
  stage = "esc"
  ended = nil
  manualCheck("press Esc now", "the second hidden ring ends, and nothing else happens at all")
  hafen.timer():after(GESTURE_FOR, function()
    if done or (runId ~= mine) or (stage ~= "esc") then return end
    check(false, "Esc ends a hidden ring, carrying nil",
          "no FlowerMenuRemoved in " .. GESTURE_FOR .. "s -- Esc was not pressed, or it was eaten")
    report()
  end)
end

-- ---- gesture 2: a click ends the first hidden ring -----------------------------------------------
local function askClick(mine)
  stage = "click"
  ended = nil
  manualCheck("click anywhere now", "the hidden ring ends, and no action of its own starts")
  hafen.timer():after(GESTURE_FOR, function()
    if done or (runId ~= mine) or (stage ~= "click") then return end
    check(false, "a click on a hidden ring ends it, carrying nil",
          "no FlowerMenuRemoved in " .. GESTURE_FOR .. "s -- nothing was clicked, or the click"
          .. " never reached the ring")
    report()
  end)
end

-- ---- gesture 1: a digit picks nothing at all -----------------------------------------------------
local function askDigit(mine)
  stage = "digit"
  ended = nil
  manualCheck("press 1 now", "nothing whatever happens -- no action starts and the ring stays up")
  hafen.timer():after(DIGIT_FOR, function()
    if done or (runId ~= mine) or (stage ~= "digit") then return end
    stage = nil
    local fm = s:flowermenu()
    check(ended == nil, "a 1..9 key on a hidden ring picks nothing: no FlowerMenuRemoved fired",
          "it ended carrying " .. tostring(ended))
    check((fm:visible() == false) and (fm:count() == ringCount),
          "...and the ring is still open, still hidden, with its " .. tostring(ringCount)
          .. " petals",
          "visible() " .. tostring(fm:visible()) .. ", count() " .. tostring(fm:count()))
    if ended ~= nil then
      -- The digit took the ring with it, so there is none left to click on -- and the ring is still
      -- in the tree for its own closing animation, so the flag cannot be what tells us. Score that
      -- check against what happened and go on to the second ring, which the Esc gesture needs.
      check(false, "a click on a hidden ring ends it, carrying nil",
            "the digit had already ended the ring, so there was nothing left to click on")
      return whenSettled(mine, 16, function() startHunt(2) end)
    end
    askClick(mine)
  end)
end

-- ---- a ring, hidden ------------------------------------------------------------------------------
local function hide(mine)
  local fm = s:flowermenu()
  fm:visible(false)
  ringCount = fm:count()
  if phase == 1 then
    eq("the ring the run raised is hidden before any gesture is asked for", fm:visible(), false)
    askDigit(mine)
  else
    askEsc(mine)
  end
end

local function run()
  if addSub then addSub:off() end            -- a run that never reported leaves nothing listening
  if remSub then remSub:off() end
  addSub, remSub = nil, nil
  pass, fail, manual = 0, 0, 0               -- a second :t116 scores its own run, not both
  cands, phase, hunting, stage, ended, done = {}, 1, false, nil, nil, false
  runId = runId + 1
  local mine = runId

  s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t116 in the world")
    return report()
  end

  local near = s:world():gob():within(40, function(g)
    return (g:name() ~= nil) and not g:player()
  end)
  table.sort(near, function(a, b) return (a:distance() or 1e9) < (b:distance() or 1e9) end)
  for i = 1, math.min(TRIES, #near) do cands[i] = near[i] end
  if #cands == 0 then
    check(false, "something of the game's is standing near you", "nothing named within 40 units")
    return report()
  end

  addSub = hafen.event():on("FlowerMenuAdded", function(petals, sess)
    if done or (runId ~= mine) or (sess ~= s) then return end
    hunting = false                          -- one ring is enough; stop clicking things
    hide(mine)
  end)

  remSub = hafen.event():on("FlowerMenuRemoved", function(label, sess)
    if done or (runId ~= mine) or (sess ~= s) then return end
    ended = label or false                   -- false: it ended, and carried nothing
    if stage == "click" then
      stage = nil
      eq("a click on a hidden ring ends it, carrying nil", label, nil)
      whenSettled(mine, 16, function() startHunt(2) end)
    elseif stage == "esc" then
      stage = nil
      eq("Esc ends a hidden ring, carrying nil", label, nil)
      report()
    end
  end)

  startHunt(1)
  -- The bounded window: whatever the world and the gestures did or did not give us, the run reports.
  hafen.timer():after(GIVE_UP, function()
    if done or (runId ~= mine) then return end
    check(false, "the run reached all three gestures inside " .. GIVE_UP .. "s",
          "it was still waiting on ring " .. tostring(phase) .. ", gesture " .. tostring(stage))
    report()
  end)
end

hafen.console():on("t116", run)   -- the only way in: a suite does not start itself

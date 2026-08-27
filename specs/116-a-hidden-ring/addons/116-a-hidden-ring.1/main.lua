-- 116.1 — s:flowermenu():visible(b): the ring the client does not paint. Self-checking suite.
--
-- Type :t116 standing in the world, in the open, with a few objects around you.
--
-- The run raises TWO radial menus of its own, by right-clicking nearby objects until one answers.
-- It PICKS the first petal of the first ring, so stand somewhere that pick is harmless -- beside a
-- tree its first petal is a chop you can walk away from. The second ring it leaves up for you:
-- press Esc once the run has reported.
--
-- Watch the screen while it runs: the two [manual] lines are about what you see, and the whole point
-- of the verb is that the first ring is never drawn at all.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local TRIES, TRY_EVERY, SHOW_AT, GIVE_UP = 6, 1.5, 1.5, 30

local s, addSub, remSub          -- the character, and the two bus subscriptions
local cands, phase, hunting      -- what to right-click, which ring we are on, whether a click is
local wanted, done, runId = nil, true, 0   -- still owed; the label the first ring was picked by

local function report()
  if done then return end
  done = true
  if addSub then addSub:off() end
  if remSub then remSub:off() end
  addSub, remSub = nil, nil
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The labels on a ring, joined, so two readings of it compare as one value.
local function labels(fm)
  local out = {}
  for i, p in ipairs(fm:list()) do out[i] = p:label() end
  return table.concat(out, ", ")
end

-- ---- ring one: the whole pair, on a ring that is never painted ---------------------------------
local function ringOne()
  local fm = s:flowermenu()
  eq("the ring the client just raised is painted", fm:visible(), true)

  -- what it answers PAINTED, to compare against hidden
  local wasLabels, wasCount, wasGob = labels(fm), fm:count(), fm:gob()

  local chained = fm:visible(false)
  check(chained == fm, "visible(b) hands the section back, so the write chains", tostring(chained))
  eq("visible() reads back false on the ring it just hid", fm:visible(), false)

  check((labels(fm) == wasLabels) and (fm:count() == wasCount) and (fm:gob() == wasGob),
        "list(), count() and gob() answer hidden what they answered painted (" .. wasLabels
        .. ", " .. wasCount .. ")",
        labels(fm) .. ", " .. tostring(fm:count()) .. ", gob "
        .. (((fm:gob() == wasGob) and "same") or "CHANGED"))

  fm:visible(true)
  eq("visible(true) paints it again, mid-life", fm:visible(), true)

  -- The argument is wrong whether or not a ring is up, so these are refused with one open.
  refuses("visible(0) is refused, naming b -- 0 is TRUE in Lua",
          function() fm:visible(0) end, "b must be true or false")
  refuses("visible(\"false\") is refused, naming b",
          function() fm:visible("false") end, "b must be true or false")
  refuses("visible(nil) is refused, naming b", function() fm:visible(nil) end, "b must not be nil")

  -- ...and a ring nobody can see still picks, through its own caption.
  local first = fm:list()[1]
  wanted = first and first:label()
  if not wanted then
    check(false, "the ring the client raised offers a petal to pick", "an empty ring")
    return report()
  end
  fm:visible(false):select(wanted)          -- hidden, and the chain is the page's own example
end

-- ---- ring two: nothing was left behind, and a hide is undone mid-life ---------------------------
local function ringTwo()
  local fm = s:flowermenu()
  local mine = runId
  eq("the NEXT ring is painted: hiding one leaves nothing behind", fm:visible(), true)
  fm:visible(false)
  hafen.timer():after(SHOW_AT, function()
    if done or (runId ~= mine) then return end
    local ok, err = pcall(function() fm:visible(true) end)
    check(ok and (fm:visible() == true), "the second ring is painted again a beat later",
          ok and tostring(fm:visible()) or why(err))
    report()
  end)
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

local function startHunt(forPhase)
  hunting = true
  hunt(forPhase, 1, runId)
end

-- A picked ring does NOT leave when FlowerMenuRemoved fires: that seam is the server's "act", which
-- only STARTS the three-quarter-second closing animation, and the widget stands in the tree for all
-- of it. Raise the second ring inside that window and the tree really does hold two, so every verb
-- on this section addresses the first one it is holding -- the dead one. So wait it out: visible()
-- is nil exactly when that character has no menu in its tree at all.
local function whenSettled(mine, tries, fn)
  if done or (runId ~= mine) then return end
  if s:flowermenu():visible() == nil then return fn() end
  if tries <= 0 then
    check(false, "the picked ring left the tree, so the next one is raised into a clear tree",
          "it was still fading after 3s")
    return report()
  end
  hafen.timer():after(0.25, function() whenSettled(mine, tries - 1, fn) end)
end

local function run()
  if addSub then addSub:off() end            -- a run that never reported leaves nothing listening
  if remSub then remSub:off() end
  addSub, remSub = nil, nil
  pass, fail, manual = 0, 0, 0               -- a second :t116 scores its own run, not both
  cands, phase, hunting, wanted, done = {}, 1, false, nil, false
  runId = runId + 1
  local mine = runId

  s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t116 in the world")
    return report()
  end

  -- ---- with no menu open --------------------------------------------------------------------
  local fm = s:flowermenu()
  eq("visible() is nil with no menu open", fm:visible(), nil)
  refuses("visible(false) with no menu open raises, naming the character",
          function() fm:visible(false) end, s:user())

  -- ---- something to right-click ---------------------------------------------------------------
  local near = s:world():gob():within(40, function(g)
    return (g:name() ~= nil) and not g:player()
  end)
  table.sort(near, function(a, b) return (a:distance() or 1e9) < (b:distance() or 1e9) end)
  for i = 1, math.min(TRIES, #near) do cands[i] = near[i] end
  if #cands == 0 then
    check(false, "something of the game's is standing near you", "nothing named within 40 units")
    return report()
  end

  manualCheck("watch the screen while the FIRST ring is raised", "no ring at all -- not one frame"
              .. " of it, from the right-click to the pick")
  manualCheck("watch the screen while the SECOND is raised", "the ring appears a beat late, about "
              .. SHOW_AT .. "s after it was raised, and stays up for you to Esc")

  addSub = hafen.event():on("FlowerMenuAdded", function(petals, sess)
    if done or (runId ~= mine) or (sess ~= s) then return end
    hunting = false                          -- one ring is enough; stop clicking things
    if phase == 1 then ringOne() else ringTwo() end
  end)

  remSub = hafen.event():on("FlowerMenuRemoved", function(label, sess)
    if done or (runId ~= mine) or (sess ~= s) or (phase ~= 1) then return end
    check(label == wanted, "a :select(label) on a HIDDEN ring picks, and FlowerMenuRemoved carries"
          .. " that very label (" .. tostring(wanted) .. ")", label)
    phase = 2
    whenSettled(mine, 12, function() startHunt(2) end)
  end)

  startHunt(1)
  -- The bounded window: whatever the world did or did not give us, the run reports.
  hafen.timer():after(GIVE_UP, function()
    if done or (runId ~= mine) then return end
    check(false, "the run reached both rings inside " .. GIVE_UP .. "s",
          "it was still waiting on ring " .. phase .. " -- the right-clicks reached no menu, or the"
          .. " server never answered the pick")
    report()
  end)
end

hafen.console():on("t116", run)   -- the only way in: a suite does not start itself

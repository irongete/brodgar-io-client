-- 170.1 — the buffs a fight draws. Self-checking suite.
--
-- Run :t170, attack an animal and kill it. The suite watches the fight from its combat row coming up to its
-- going down, logs everything it sees on the way (each opening event with the opponent it names, each change of
-- what the doors list, the row, the target, and the life of every buff at the end), and judges what arrived,
-- whatever the animal.

local INTERVAL = 0.25   -- seconds between two looks
local SETTLE = 6        -- looks with the row down before the fight counts as over
local STALE = 4         -- looks a buff may be listed before its OpeningAdded has to have come

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel, sweeper, statusLabel, countLabel
local subs = {}
-- The buffs of the fight, by the OpeningAdded that announced them: in arrival order, whose ("yours" | "theirs"),
-- the opponent each life was announced with (false for yours), and how many of each edge fired.
local seen, order, fought, lifeOpponent = {}, {}, {}, {}
local addedCount, removedCount, changedCount = {}, {}, {}
local barEvents = 0
local finished, phase, ticks, rowGone = false, "waiting", 0, 0
local rowWas, targetWas, lastCounts, sawRow = false, nil, "", false
local peakYours, peakTheirs, namedAfterGone, theirsRemoved = 0, 0, 0, 0
local flagged, reported = {}, {}
local stale = {}                            -- buff -> looks it has been listed with no OpeningAdded
local pendingNow = {}                       -- what the last look suspected, which the next must confirm

-- ---- what the log says -----------------------------------------------------------------------------

local function stamp()
  return ("t=+%d.%02ds"):format(math.floor(ticks / 4), (ticks % 4) * 25)
end

-- Not a verdict: what happened, so the whole fight can be read afterwards.
local function info(text)
  hafen.log():write("[info] " .. text)
end

local function note(text)
  info(stamp() .. " " .. text)
end

local function describe(buff)
  return ("res=%s name=%s amount=%s remaining=%s number=%s opponent=%s exists=%s"):format(
    tostring(buff:res()), tostring(buff:name()), tostring(buff:amount()), tostring(buff:remaining()),
    tostring(buff:number()), tostring(buff:opponent()), tostring(buff:exists()))
end

local function dumpList(title, list)
  info("  " .. title .. ": " .. #list)
  for position, buff in ipairs(list) do
    info("    " .. position .. ". " .. describe(buff))
  end
end

-- What a look found wrong, once per subject, kept for the verdict.
local function flag(kind, key, text)
  if reported[key] then return end
  reported[key] = true
  local list = flagged[kind]
  if not list then list = {}; flagged[kind] = list end
  list[#list + 1] = text
  note("!! " .. kind .. ": " .. text)
end

local function flags(kind)
  return flagged[kind] and #flagged[kind] or 0
end

local function why(kind)
  return flagged[kind] and table.concat(flagged[kind], " | ") or ""
end

-- A fault a single look could have caught mid-change is flagged only when the next look finds it again.
local function suspect(kind, key, text, previous)
  pendingNow[key] = true
  if previous[key] then flag(kind, key, text) end
end

-- ---- reading the fight ----------------------------------------------------------------------------

local function listed(collection, buff)
  return collection:find(function(each) return each == buff end) ~= nil
end

local function snapshot(reason)
  local target = session:fight():target()
  info("  -- " .. reason .. ": the doors of the fight now")
  dumpList("session:fight():opening()", session:fight():opening():list())
  if target then
    dumpList("target:opening() of " .. tostring(target), target:opening():list())
  else
    info("  no target")
  end
end

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if sweeper then pcall(function() sweeper:cancel() end) end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

-- ---- the verdict, once the fight is over -----------------------------------------------------------

local function finalize(reason)
  if finished then return end
  local total, yours, theirs, balanced, gone = 0, 0, 0, 0, 0
  local off, sample = {}, nil
  info("-- the life of every buff of the fight")
  for _, buff in ipairs(order) do
    local where = fought[buff]
    total = total + 1
    if where == "yours" then yours = yours + 1 else theirs = theirs + 1 end
    local added, removed = addedCount[buff] or 0, removedCount[buff] or 0
    if added == removed then
      balanced = balanced + 1
    else
      off[#off + 1] = tostring(buff:res()) .. " added " .. added .. ", removed " .. removed
    end
    if not buff:exists() then gone = gone + 1 end
    if not sample then sample = buff end
    info(("  %s res=%s added=%d removed=%d changed=%d"):format(where, tostring(buff:res()), added, removed,
      changedCount[buff] or 0))
  end
  info("  " .. total .. " buffs of the fight: " .. yours .. " yours, " .. theirs .. " the opponent's; " ..
    "most the doors listed at once: yours=" .. peakYours .. " theirs=" .. peakTheirs .. "; " ..
    barEvents .. " events of the bar's own buffs")
  if reason then
    check(false, "the fight ran from its combat row coming up to its going down", reason)
    return finish()
  end
  check(sawRow, "the fight ran from its combat row coming up to its going down", "the row never came up")
  check(total > 0, "the fight drew buffs: " .. yours .. " yours, " .. theirs .. " the opponent's",
    "none arrived -- kill an animal that draws some")
  if theirs == 0 then
    info("no buff of the opponent's appeared in this fight: opponent:opening(), buff:opponent() and the opponent " ..
      "argument of the opening events naming the animal were not exercised")
  end
  check(flags("unannounced") == 0, "every buff a door of the fight listed was announced by OpeningAdded",
    why("unannounced"))
  check(flags("barkey") == 0, "the Buff keys stayed with the bar: none fired for a buff of the fight",
    why("barkey"))
  check(flags("exists") == 0 and flags("bar") == 0 and flags("stray") == 0 and flags("rowdown") == 0,
    "a door lists a buff exactly while it answers :exists() true, off the bar, and only while the row is up",
    why("exists") .. why("bar") .. why("stray") .. why("rowdown"))
  check(flags("opponent") == 0, "buff:opponent() was nil for yours and the target for the opponent's, at every look",
    why("opponent"))
  check(flags("named") == 0,
    "each opening event named the opponent its buff was drawn beside, nil for yours, " .. namedAfterGone ..
      " of them after buff:opponent() could no longer say",
    why("named"))
  check(total > 0 and balanced == total,
    "each of the " .. total .. " buffs of the fight fired OpeningRemoved once for each OpeningAdded, with the object OpeningAdded handed",
    balanced .. " of " .. total .. ": " .. table.concat(off, "; "))
  local target = session:fight():target()
  local left = session:fight():opening():count() + (target and target:opening():count() or 0)
  check(total > 0 and gone == total and left == 0 and session:ui():match("@Fightsess") == nil,
    "after the fight the row is gone, every buff answers :exists() false and both doors are empty",
    gone .. " of " .. total .. " gone, " .. left .. " listed")
  local surplus = sample and refusal(function() return sample:res(1) end)
  check(surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "a buff refuses a surplus argument, naming the verb", surplus)
  manualCheck("compare the number the suite's window showed with the buff icons in the row over the map to the LEFT of your character (not the top-left bar, not the animal's icons on the right)",
    "the window's number for session:fight():opening() and the icons on the left matched whenever you looked")
  finish()
end

-- ---- one look ---------------------------------------------------------------------------------------

local function sweepOnce()
  ticks = ticks + 1
  local previous = pendingNow
  pendingNow = {}
  local row = session:ui():match("@Fightsess") ~= nil
  local target = session:fight():target()
  local mine = session:fight():opening():list()
  local theirs = target and target:opening():list() or {}
  local changed = false
  if #mine > peakYours then peakYours = #mine end
  if #theirs > peakTheirs then peakTheirs = #theirs end
  if row ~= rowWas then
    rowWas, changed = row, true
    note("the combat row is " .. (row and "UP" or "DOWN"))
  end
  if target ~= targetWas then
    targetWas, changed = target, true
    note("the target is now " .. tostring(target))
  end
  local counts = ("yours=%d theirs=%d"):format(#mine, #theirs)
  if counts ~= lastCounts then
    lastCounts, changed = counts, true
    note("the doors list " .. counts .. "; the bar lists " .. session:buff():count())
  end
  if changed then snapshot("at " .. stamp()) end
  countLabel:text("session:fight():opening() lists " .. #mine .. ", target:opening() lists " .. #theirs)
  statusLabel:text(phase .. ", combat row " .. (row and "up" or "down"))

  local listedNow = {}
  local function look(list, door)
    for _, buff in ipairs(list) do
      listedNow[buff] = true
      local name = tostring(buff:res())
      if not seen[buff] then
        stale[buff] = (stale[buff] or 0) + 1
        if stale[buff] >= STALE then
          flag("unannounced", buff, door .. " lists " .. name .. " and its OpeningAdded never came")
        end
      end
      if not buff:exists() then
        suspect("exists", "exists" .. tostring(buff), door .. " lists " .. name .. " but :exists() is false", previous)
      end
      local opponent = buff:opponent()
      if door == "yours" and opponent ~= nil then
        suspect("opponent", "opp" .. tostring(buff), "yours " .. name .. " names " .. tostring(opponent), previous)
      elseif door == "theirs" and opponent ~= target then
        suspect("opponent", "opp" .. tostring(buff), "theirs " .. name .. " names " .. tostring(opponent) ..
          ", the target is " .. tostring(target), previous)
      end
      if listed(session:buff(), buff) then
        suspect("bar", "bar" .. tostring(buff), door .. " " .. name .. " is on session:buff() as well", previous)
      end
    end
  end
  look(mine, "yours")
  look(theirs, "theirs")
  if not row and (#mine > 0 or #theirs > 0) then
    suspect("rowdown", "rowdown", "a door lists " .. counts .. " while the combat row is down", previous)
  end
  for _, buff in ipairs(order) do
    local where = fought[buff]
    if not listedNow[buff] and buff:exists() and listed(session:buff(), buff) == false
        and (where == "yours" or buff:opponent() == target) then
      suspect("stray", "stray" .. tostring(buff), where .. " " .. tostring(buff:res()) ..
        " answers :exists() true and no door lists it", previous)
    end
  end

  if row then
    sawRow, rowGone = true, 0
    if phase == "waiting" then
      phase = "fighting"
      note("the fight started: the combat row is up")
    end
  elseif phase == "fighting" then
    rowGone = rowGone + 1
    if rowGone >= SETTLE then
      phase = "over"
      note("the fight is over: the combat row has been down for " .. SETTLE .. " looks")
      finalize(nil)
    end
  end
end

-- ---- the run ---------------------------------------------------------------------------------------

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  seen, order, fought, lifeOpponent, subs = {}, {}, {}, {}, {}
  addedCount, removedCount, changedCount = {}, {}, {}
  phase, ticks, rowGone, rowWas, targetWas, lastCounts, sawRow = "waiting", 0, 0, false, nil, "", false
  peakYours, peakTheirs, namedAfterGone, theirsRemoved, barEvents = 0, 0, 0, 0, 0
  flagged, reported, stale, pendingNow = {}, {}, {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  check(true, "this suite declares api_version 1.3 and runs")
  local door = session:fight():opening()
  local noGet = refusal(function() return door:get(1) end)
  local surplus = refusal(function() return session:fight():opening(1) end)
  check(door == session:fight():opening() and noGet ~= nil and noGet:find("has no key", 1, true) ~= nil
      and surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "session:fight():opening() is one object, and :get and an argument are refused naming why",
    tostring(noGet) .. " / " .. tostring(surplus))

  -- The bar's own keys: logged, and none may name a buff of the fight.
  for _, key in ipairs({ "BuffAdded", "BuffChanged", "BuffRemoved" }) do
    subs[#subs + 1] = hafen.event():on(key, function(buff, where)
      if where ~= session then return end
      barEvents = barEvents + 1
      note(key .. " (the bar) -- " .. describe(buff))
      if seen[buff] or listed(session:fight():opening(), buff) or buff:opponent() ~= nil then
        flag("barkey", key .. tostring(buff), key .. " fired for " .. tostring(buff:res()) .. ", a buff of the fight")
      end
    end)
  end

  -- Every opening event of this character is logged and counted per object. The second argument says whose:
  -- an Opponent, or nil for yours. It is what the buff was drawn beside when it was announced, so it is
  -- compared with what the buff answers at the time and, on the removal, with what the opening announced.
  subs[#subs + 1] = hafen.event():on("OpeningAdded", function(buff, opponent, where)
    if where ~= session then return end
    addedCount[buff] = (addedCount[buff] or 0) + 1
    lifeOpponent[buff] = opponent or false
    if buff:opponent() ~= opponent then
      flag("named", "add" .. tostring(buff) .. addedCount[buff], "OpeningAdded named " .. tostring(opponent) ..
        " for " .. tostring(buff:res()) .. ", which answers buff:opponent() " .. tostring(buff:opponent()))
    end
    if seen[buff] then
      note("OpeningAdded AGAIN (" .. addedCount[buff] .. ") -- opponent=" .. tostring(opponent) .. " " .. describe(buff))
      return
    end
    seen[buff] = true
    order[#order + 1] = buff
    fought[buff] = opponent and "theirs" or "yours"
    note("OpeningAdded -- opponent=" .. tostring(opponent) .. " " .. describe(buff))
  end)
  subs[#subs + 1] = hafen.event():on("OpeningRemoved", function(buff, opponent, where)
    if where ~= session then return end
    removedCount[buff] = (removedCount[buff] or 0) + 1
    if lifeOpponent[buff] == nil then
      flag("unannounced", "remnone" .. tostring(buff), "OpeningRemoved fired for " .. tostring(buff:res()) ..
        " with no OpeningAdded before it")
    elseif lifeOpponent[buff] ~= (opponent or false) then
      flag("named", "rem" .. tostring(buff) .. removedCount[buff], "OpeningRemoved named " .. tostring(opponent) ..
        " for " .. tostring(buff:res()) .. ", which OpeningAdded named " .. tostring(lifeOpponent[buff] or nil))
    end
    if opponent ~= nil and buff:opponent() == nil then namedAfterGone = namedAfterGone + 1 end
    note("OpeningRemoved (" .. removedCount[buff] .. ") -- opponent=" .. tostring(opponent) .. " " .. describe(buff))
  end)
  subs[#subs + 1] = hafen.event():on("OpeningChanged", function(buff, opponent, where)
    if where ~= session then return end
    changedCount[buff] = (changedCount[buff] or 0) + 1
    if lifeOpponent[buff] ~= nil and lifeOpponent[buff] ~= (opponent or false) then
      flag("named", "chg" .. tostring(buff) .. changedCount[buff], "OpeningChanged named " .. tostring(opponent) ..
        " for " .. tostring(buff:res()) .. ", which OpeningAdded named " .. tostring(lifeOpponent[buff] or nil))
    end
    note("OpeningChanged (" .. changedCount[buff] .. ") -- opponent=" .. tostring(opponent) .. " " .. describe(buff))
  end)

  -- A fight already going when the suite starts has its buffs announced to nobody. They are alive, so they
  -- count as added once, and an OpeningRemoved for one of them is the removal of a buff this run knows.
  local function adopt(list, opponent)
    for _, buff in ipairs(list) do
      seen[buff] = true
      order[#order + 1] = buff
      addedCount[buff] = 1
      lifeOpponent[buff] = opponent or false
      fought[buff] = opponent and "theirs" or "yours"
      note("already listed when the suite started, " .. fought[buff] .. " -- " .. describe(buff))
    end
  end
  adopt(session:fight():opening():list(), nil)
  local opponent = session:fight():target()
  if opponent then adopt(opponent:opening():list(), opponent) end
  info("at the start the bar lists " .. session:buff():count())
  dumpList("session:buff(), the buff bar", session:buff():list())

  panel = hafen.ui():window():title("170.1"):size(520, 96)
  local stop = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Stop")
  hafen.ui():label():parent(panel):position(96, 12):text("Attack an animal and kill it. Stop only aborts.")
  statusLabel = hafen.ui():label():parent(panel):position(8, 40):text(phase)
  countLabel = hafen.ui():label():parent(panel):position(8, 62):text("")
  stop:on("Pressed", function()
    after(0, function() finalize("stopped by hand in the phase: " .. phase) end)
  end)
  sweeper = hafen.timer():every(INTERVAL, guarded(sweepOnce))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)

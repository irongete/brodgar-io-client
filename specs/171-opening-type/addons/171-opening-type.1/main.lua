-- 171.1 — the opening is its own type. Self-checking suite.
--
-- Run :t171 with a buff on your bar, then attack an animal and kill it. The suite watches the fight from its
-- combat row coming up to its going down, logs what it sees (each opening event, each change of what the doors
-- list), and judges what arrived, whatever the animal: a fight's doors and events hand Opening objects, the bar
-- hands Buff objects, and neither ever hands the other's.

local INTERVAL = 0.25   -- seconds between two looks
local SETTLE = 6        -- looks with the row down before the fight counts as over
local STALE = 4         -- looks an opening may be listed before its OpeningAdded has to have come

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

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(text, ...)
  if text == nil then return false end
  for _, needle in ipairs({ ... }) do
    if not text:find(needle, 1, true) then return false end
  end
  return true
end

local function isOpening(value) return tostring(value):sub(1, 8) == "Opening(" end
local function isBuff(value) return tostring(value):sub(1, 5) == "Buff(" end

local session, panel, sweeper, statusLabel, countLabel
local subs = {}
-- The openings of the fight, by the OpeningAdded that announced them: in arrival order, whose ("yours" |
-- "theirs"), the opponent each life was announced with (false for yours), and how many of each edge fired.
local seen, order, fought, lifeOpponent = {}, {}, {}, {}
local addedCount, removedCount, changedCount = {}, {}, {}
local finished, phase, ticks, rowGone, rowWas, lastCounts, sawRow = false, "waiting", 0, 0, false, "", false
local flagged, reported, stale, pendingNow = {}, {}, {}, {}
local barBuff, barRefusal, barEvents, sample = nil, nil, 0, nil

-- ---- what the log says -----------------------------------------------------------------------------

local function info(text)
  hafen.log():write("[info] " .. text)
end

local function note(text)
  info(("t=+%d.%02ds "):format(math.floor(ticks / 4), (ticks % 4) * 25) .. text)
end

local function describe(opening)
  return ("%s res=%s name=%s amount=%s remaining=%s number=%s opponent=%s exists=%s"):format(
    tostring(opening), tostring(opening:res()), tostring(opening:name()), tostring(opening:amount()),
    tostring(opening:remaining()), tostring(opening:number()), tostring(opening:opponent()),
    tostring(opening:exists()))
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

local function flags(...)
  local total = 0
  for _, kind in ipairs({ ... }) do total = total + (flagged[kind] and #flagged[kind] or 0) end
  return total
end

local function why(...)
  local parts = {}
  for _, kind in ipairs({ ... }) do
    if flagged[kind] then parts[#parts + 1] = table.concat(flagged[kind], " | ") end
  end
  return table.concat(parts, " | ")
end

-- A fault a single look could have caught mid-change is flagged only when the next look finds it again.
local function suspect(kind, key, text, previous)
  pendingNow[key] = true
  if previous[key] then flag(kind, key, text) end
end

local function listed(collection, member)
  return collection:find(function(each) return each == member end) ~= nil
end

-- Every opening a door of the fight lists now, with the opponent whose door it is (false for yours).
local function doors()
  local out = {}
  for _, opening in ipairs(session:fight():opening():list()) do out[#out + 1] = { opening, false } end
  for _, opponent in ipairs(session:fight():opponent():list()) do
    for _, opening in ipairs(opponent:opening():list()) do out[#out + 1] = { opening, opponent } end
  end
  return out
end

local function inDoors(member)
  for _, entry in ipairs(doors()) do
    if entry[1] == member then return true end
  end
  return false
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

-- ---- the snapshot shape ------------------------------------------------------------------------------

local ALLOWED = { res = true, name = true, amount = true, remaining = true, number = true }

local function checkInfo(opening)
  local snapshot = opening:info()
  if type(snapshot) ~= "table" then
    flag("info", "infotype" .. tostring(opening), tostring(opening) .. ":info() is " .. tostring(snapshot))
    return
  end
  for key in pairs(snapshot) do
    if not ALLOWED[key] then
      flag("info", "infokey" .. tostring(opening) .. key, tostring(opening) .. ":info() has the field " .. key)
    end
  end
  local live = opening:remaining()
  if snapshot.remaining ~= nil and live ~= nil and math.abs(snapshot.remaining - live) > 0.02 then
    flag("info", "inforem" .. tostring(opening), tostring(opening) .. ":info().remaining " ..
      tostring(snapshot.remaining) .. " against :remaining() " .. tostring(live))
  end
end

-- ---- the verdict, once the fight is over -----------------------------------------------------------

local function finalize(reason)
  if finished then return end
  local total, yours, theirs, balanced, gone, unnamed = 0, 0, 0, 0, 0, 0
  local off = {}
  info("-- the life of every opening of the fight")
  for _, opening in ipairs(order) do
    total = total + 1
    if fought[opening] == "yours" then yours = yours + 1 else theirs = theirs + 1 end
    local added, removed = addedCount[opening] or 0, removedCount[opening] or 0
    if added == removed then
      balanced = balanced + 1
    else
      off[#off + 1] = tostring(opening) .. " added " .. added .. ", removed " .. removed
    end
    if not opening:exists() then gone = gone + 1 end
    if opening:opponent() == nil then unnamed = unnamed + 1 end
    info(("  %s %s added=%d removed=%d changed=%d"):format(fought[opening], tostring(opening), added, removed,
      changedCount[opening] or 0))
  end
  info("  " .. total .. " openings: " .. yours .. " yours, " .. theirs .. " the opponent's; " .. barEvents ..
    " events of the bar")
  if reason then
    check(false, "the fight ran from its combat row coming up to its going down", reason)
    return finish()
  end
  check(sawRow and total > 0, "the fight ran from its combat row coming up to its going down, drawing " ..
    yours .. " openings of yours and " .. theirs .. " of the opponent's",
    sawRow and "no opening arrived -- kill an animal that draws some" or "the row never came up")
  if theirs == 0 then
    info("no opening of the opponent's appeared: opponent:opening() and a non-nil opponent were not exercised")
  end
  check(flags("doortype", "doorexists", "doorbar", "bartype") == 0,
    "at every look the fight's doors listed Opening objects that exist and are off the bar, and the bar listed Buff objects that are in no door of the fight",
    why("doortype", "doorexists", "doorbar", "bartype"))
  check(flags("unannounced") == 0, "every opening a door listed was announced by OpeningAdded", why("unannounced"))
  check(flags("eventtype", "named", "notlisted") == 0,
    "each opening event handed an Opening, then the opponent it is drawn beside (nil for yours), then the session, and OpeningAdded handed what the door lists",
    why("eventtype", "named", "notlisted"))
  check(total > 0 and balanced == total,
    "each of the " .. total .. " openings fired OpeningRemoved once for each OpeningAdded, with the same object",
    balanced .. " of " .. total .. ": " .. table.concat(off, "; "))
  check(flags("barkey") == 0, "no Buff key handed an Opening, and every Buff key handed a Buff (" .. barEvents ..
    " fired)", why("barkey"))
  check(flags("opponent") == 0, "opening:opponent() was nil for yours and that opponent for theirs, at every look",
    why("opponent"))
  check(flags("info") == 0 and sample ~= nil,
    "opening:info() held only res, name, amount, remaining and number, and remaining matched :remaining()",
    sample and why("info") or "no opening was seen")
  local surplus = sample and refusal(function() return sample:res(1) end)
  local unknown = sample and refusal(function() return sample:frob() end)
  check(says(surplus, "takes no arguments") and says(unknown, "has no verb 'frob'", ":opponent()"),
    "an opening refuses a surplus argument and an unknown verb, listing its own vocabulary",
    tostring(surplus) .. " / " .. tostring(unknown))
  check(barRefusal ~= nil and says(barRefusal, "has no verb 'opponent'", ":exists()"),
    "a buff of the bar refuses :opponent() as a verb it does not have, listing its own",
    barBuff and tostring(barRefusal) or "no buff on the bar during the run -- have one up and run again")
  local left = #doors()
  check(total > 0 and gone == total and unnamed == total and left == 0 and session:ui():match("@Fightsess") == nil,
    "after the fight the row is gone, both doors are empty, and every opening answers :exists() false and :opponent() nil",
    gone .. " of " .. total .. " gone, " .. unnamed .. " unnamed, " .. left .. " listed")
  finish()
end

-- ---- one look ---------------------------------------------------------------------------------------

local function lookAtBar(previous)
  for _, buff in ipairs(session:buff():list()) do
    if not isBuff(buff) then
      flag("bartype", "bartype" .. tostring(buff), "session:buff() lists " .. tostring(buff))
    elseif inDoors(buff) then
      flag("bartype", "barindoor" .. tostring(buff), tostring(buff) .. " is on the bar and in a door of the fight")
    end
    if not barBuff then
      barBuff = buff
      barRefusal = refusal(function() return buff:opponent() end)
      note("the bar holds " .. tostring(buff) .. "; buff:opponent() says: " .. tostring(barRefusal))
    end
  end
end

local function sweepOnce()
  ticks = ticks + 1
  local previous = pendingNow
  pendingNow = {}
  local row = session:ui():match("@Fightsess") ~= nil
  local entries = doors()
  local mine, theirs = 0, 0
  for _, entry in ipairs(entries) do
    if entry[2] then theirs = theirs + 1 else mine = mine + 1 end
  end
  if row ~= rowWas then
    rowWas = row
    note("the combat row is " .. (row and "UP" or "DOWN"))
  end
  local counts = ("yours=%d theirs=%d bar=%d"):format(mine, theirs, session:buff():count())
  if counts ~= lastCounts then
    lastCounts = counts
    note("the doors list " .. counts)
  end
  countLabel:text("openings: yours " .. mine .. ", theirs " .. theirs)
  statusLabel:text(phase .. ", combat row " .. (row and "up" or "down"))

  lookAtBar(previous)
  for _, entry in ipairs(entries) do
    local opening, opponent = entry[1], entry[2] or nil
    local name = tostring(opening)
    if not isOpening(opening) then
      flag("doortype", "doortype" .. name, "a door of the fight lists " .. name)
    else
      if not sample then sample = opening end
      checkInfo(opening)
      if not opening:exists() then
        suspect("doorexists", "exists" .. name, name .. " is listed and answers :exists() false", previous)
      end
      if listed(session:buff(), opening) then
        flag("doorbar", "doorbar" .. name, name .. " is in a door of the fight and on session:buff()")
      end
      if opening:opponent() ~= opponent then
        suspect("opponent", "opp" .. name, name .. " in the door of " .. tostring(opponent) ..
          " answers :opponent() " .. tostring(opening:opponent()), previous)
      end
      if not seen[opening] then
        stale[opening] = (stale[opening] or 0) + 1
        if stale[opening] >= STALE then
          flag("unannounced", "unann" .. name, name .. " is listed and its OpeningAdded never came")
        end
      end
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
  phase, ticks, rowGone, rowWas, lastCounts, sawRow = "waiting", 0, 0, false, "", false
  flagged, reported, stale, pendingNow = {}, {}, {}, {}
  barBuff, barRefusal, barEvents, sample = nil, nil, 0, nil
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t171 logged in")
    return finish()
  end
  local door = session:fight():opening()
  local noGet = refusal(function() return door:get(1) end)
  local surplus = refusal(function() return session:fight():opening(1) end)
  check(door == session:fight():opening() and says(noGet, "an opening has no key", ":find(needle)")
      and says(surplus, "takes no arguments"),
    "session:fight():opening() is one object, and :get and an argument are refused naming why",
    tostring(noGet) .. " / " .. tostring(surplus))

  -- The bar's own keys: logged, and none may hand an Opening.
  for _, key in ipairs({ "BuffAdded", "BuffChanged", "BuffRemoved" }) do
    subs[#subs + 1] = hafen.event():on(key, function(buff, where)
      if where ~= session then return end
      barEvents = barEvents + 1
      note(key .. " " .. tostring(buff))
      if not isBuff(buff) or seen[buff] then
        flag("barkey", key .. tostring(buff), key .. " handed " .. tostring(buff))
      end
    end)
  end

  -- The opening keys: an Opening, then whose (an Opponent, or nil for yours), then the session.
  local function typed(key, opening, opponent, where)
    if not isOpening(opening) then
      flag("eventtype", key .. tostring(opening), key .. " handed " .. tostring(opening))
    end
    if opponent ~= nil and tostring(opponent):sub(1, 9) ~= "Opponent(" then
      flag("eventtype", key .. "opp" .. tostring(opponent), key .. " named " .. tostring(opponent))
    end
  end
  subs[#subs + 1] = hafen.event():on("OpeningAdded", function(opening, opponent, where)
    if where ~= session then return end
    typed("OpeningAdded", opening, opponent, where)
    addedCount[opening] = (addedCount[opening] or 0) + 1
    lifeOpponent[opening] = opponent or false
    if opening:opponent() ~= opponent then
      flag("named", "add" .. tostring(opening) .. addedCount[opening], "OpeningAdded named " .. tostring(opponent) ..
        " for " .. tostring(opening) .. ", which answers :opponent() " .. tostring(opening:opponent()))
    end
    local door = opponent and opponent:opening() or session:fight():opening()
    if opening:exists() and not listed(door, opening) then
      flag("notlisted", "notlisted" .. tostring(opening), "OpeningAdded handed " .. tostring(opening) ..
        ", which its door does not list")
    end
    if not seen[opening] then
      seen[opening] = true
      order[#order + 1] = opening
      fought[opening] = opponent and "theirs" or "yours"
    end
    note("OpeningAdded (" .. addedCount[opening] .. ") " .. describe(opening))
  end)
  subs[#subs + 1] = hafen.event():on("OpeningRemoved", function(opening, opponent, where)
    if where ~= session then return end
    typed("OpeningRemoved", opening, opponent, where)
    removedCount[opening] = (removedCount[opening] or 0) + 1
    if lifeOpponent[opening] == nil then
      flag("unannounced", "remnone" .. tostring(opening), "OpeningRemoved fired for " .. tostring(opening) ..
        " with no OpeningAdded before it")
    elseif lifeOpponent[opening] ~= (opponent or false) then
      flag("named", "rem" .. tostring(opening) .. removedCount[opening], "OpeningRemoved named " ..
        tostring(opponent) .. " for " .. tostring(opening) .. ", which OpeningAdded named " ..
        tostring(lifeOpponent[opening] or nil))
    end
    if opening:exists() then
      flag("named", "remexists" .. tostring(opening), "OpeningRemoved fired for " .. tostring(opening) ..
        ", which still answers :exists() true")
    end
    note("OpeningRemoved (" .. removedCount[opening] .. ") " .. describe(opening))
  end)
  subs[#subs + 1] = hafen.event():on("OpeningChanged", function(opening, opponent, where)
    if where ~= session then return end
    typed("OpeningChanged", opening, opponent, where)
    changedCount[opening] = (changedCount[opening] or 0) + 1
    if lifeOpponent[opening] ~= nil and lifeOpponent[opening] ~= (opponent or false) then
      flag("named", "chg" .. tostring(opening) .. changedCount[opening], "OpeningChanged named " ..
        tostring(opponent) .. " for " .. tostring(opening) .. ", which OpeningAdded named " ..
        tostring(lifeOpponent[opening] or nil))
    end
  end)

  -- A fight already going when the suite starts has its openings announced to nobody. They are alive, so
  -- they count as added once, and an OpeningRemoved for one of them is the removal of an opening this run knows.
  for _, entry in ipairs(doors()) do
    local opening, opponent = entry[1], entry[2]
    seen[opening] = true
    order[#order + 1] = opening
    addedCount[opening] = 1
    lifeOpponent[opening] = opponent
    fought[opening] = opponent and "theirs" or "yours"
    note("already listed when the suite started -- " .. describe(opening))
  end

  panel = hafen.ui():window():title("171.1"):size(560, 96)
  local stop = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Stop")
  hafen.ui():label():parent(panel):position(96, 12):text("Have a buff on your bar. Attack an animal and kill it.")
  statusLabel = hafen.ui():label():parent(panel):position(8, 40):text(phase)
  countLabel = hafen.ui():label():parent(panel):position(8, 62):text("")
  stop:on("Pressed", function()
    after(0, function() finalize("stopped by hand in the phase: " .. phase) end)
  end)
  sweeper = hafen.timer():every(INTERVAL, guarded(sweepOnce))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t171", function() after(0, run) end)

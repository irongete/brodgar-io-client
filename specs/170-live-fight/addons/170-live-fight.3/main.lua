-- 170.3 — the combat row and the manoeuvres used. Self-checking suite.

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

local session, panel, sampler
local subs = {}
local changes = {}   -- every CombatActionChanged: { action, session, res }
local used = {}      -- every ManeuverUsed: { res, opponent, session }
local peak = 0       -- the highest cooldown seen while the key step runs
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if sampler then pcall(function() sampler:cancel() end) end
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

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.3"):size(480, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function skipped(what)
  return function()
    check(false, what, "skipped")
    finish()
  end
end

local function filledNow()
  local filled = {}
  for _, action in ipairs(session:fight():action():list()) do
    if not action:empty() then filled[#filled + 1] = action end
  end
  return filled
end

local function fraction(value)
  return type(value) == "number" and value >= 0 and value <= 1
end

local filledAtStart = {}

local function ended()
  local empty = 0
  for _, action in ipairs(session:fight():action():list()) do
    if action:empty() then empty = empty + 1 end
  end
  local cleared = 0
  for _, action in ipairs(filledAtStart) do
    for _, change in ipairs(changes) do
      if change.action == action and change.res == nil then cleared = cleared + 1; break end
    end
  end
  check(empty == 10 and #filledAtStart > 0 and cleared == #filledAtStart,
    "every action is :empty() again, and CombatActionChanged said so for each that was filled",
    empty .. " of 10 empty, " .. cleared .. " of " .. #filledAtStart .. " announced")
  finish()
end

local function pressed()
  if sampler then sampler:cancel(); sampler = nil end
  local fight = session:fight()
  local mine, same = 0, true
  local first
  for _, use in ipairs(used) do
    if use.opponent == nil and use.session == session then
      mine = mine + 1
      first = first or use.res
      if use.res ~= first then same = false end
    end
  end
  check(mine >= 2 and same and fight:last() == first,
    "ManeuverUsed fired for each of your uses, the same resource each time, nil as the opponent, and :last() is it",
    mine .. " of yours, last " .. tostring(fight:last()))
  local spurious = 0
  for index, change in ipairs(changes) do
    if change.stepped and change.res ~= nil and change.res == change.before then spurious = spurious + 1 end
  end
  check(peak > 0 and spurious == 0,
    "a cooldown ran (the highest seen was " .. string.format("%d%%", math.floor(peak * 100)) .. ") and no"
      .. " CombatActionChanged fired for a place whose manoeuvre stayed", peak .. ", " .. spurious .. " spurious")
  local target = fight:opponent():current()
  local theirs
  for _, use in ipairs(used) do
    if use.opponent ~= nil and use.opponent == target then theirs = use.res end
  end
  check(theirs ~= nil and target:last() == theirs,
    "a ManeuverUsed named the animal as the opponent, and its :last() is that resource",
    tostring(theirs) .. " / " .. tostring(target and target:last()))
  prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
end

local function fighting()
  local fight = session:fight()
  local actions = fight:action()
  filledAtStart = filledNow()
  local named, announced, sameObject, sessions = 0, 0, true, 0
  for _, action in ipairs(filledAtStart) do
    if action:res() and action:name() then named = named + 1 end
    for _, change in ipairs(changes) do
      if change.action == action then announced = announced + 1; break end
    end
  end
  for _, change in ipairs(changes) do
    if actions:get(change.action:index()) ~= change.action then sameObject = false end
    if change.session ~= session then sessions = sessions + 1 end
  end
  check(#filledAtStart > 0 and named == #filledAtStart and announced == #filledAtStart and sameObject and sessions == 0,
    #filledAtStart .. " actions are filled, each with a resource and a name, and CombatActionChanged announced each"
      .. " as :get(its index) with this session last",
    named .. " named, " .. announced .. " announced, " .. sessions .. " with another session")
  local first = filledAtStart[1]
  local maneuver = first and first:maneuver()
  local wanted = first and fight:maneuver():find(function(each) return each:res() == first:res() end)
  check(first ~= nil and maneuver ~= nil and maneuver == wanted,
    "a filled action's :maneuver() is the entry session:fight():maneuver() finds by the same resource",
    tostring(maneuver) .. " / " .. tostring(wanted))
  check(first ~= nil and actions:find(first:res()) ~= nil and actions:find(first:name()) ~= nil,
    ":find(res) and :find(name) reach a filled action", first and first:res())
  check(fraction(fight:cooldown()) and first ~= nil and fraction(first:cooldown()) and first:info().res == first:res(),
    "both cooldowns read within 0..1, and :info() carries the same resource", tostring(fight:cooldown()))
  local one = actions:get(1)
  manualCheck("look at the first icon of the row drawn under your character",
    one:empty() and "no icon there" or ("the manoeuvre " .. tostring(one:name())))
  for _, change in ipairs(changes) do change.stepped = false end
  sampler = hafen.timer():every(0.05, guarded(function()
    local now = math.max(fight:cooldown() or 0, actions:get(1):cooldown() or 0)
    if now > peak then peak = now end
  end))
  prompt("Press combat key 1, wait for its cooldown to end, press it again, then press Done.", pressed,
    skipped("combat key 1 was pressed twice"))
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, changes, used, peak, filledAtStart = {}, {}, {}, 0, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  local fight = session:fight()
  local actions = fight:action()
  local third = actions:get(3)
  check(actions == fight:action() and actions:count() == 10 and third == actions:list()[3] and third:index() == 3
      and third:wire() == 2 and tostring(third) == "CombatAction(3)",
    "session:fight():action() is one object of ten actions, and :get(3) is :list()[3], index 3, wire 2",
    actions:count() .. " " .. tostring(third))
  local empty = 0
  for _, action in ipairs(actions:list()) do
    if action:empty() and action:res() == nil and action:cooldown() == nil and action:info() == nil then
      empty = empty + 1
    end
  end
  check(empty == 10 and fight:cooldown() == nil and fight:last() == nil,
    "out of a fight every action is :empty() and reads nil, and fight:cooldown() and fight:last() are nil",
    empty .. " empty, " .. tostring(fight:cooldown()) .. " " .. tostring(fight:last()))
  local zero = refusal(function() return actions:get(0) end)
  local eleven = refusal(function() return actions:get(11) end)
  check(zero ~= nil and zero:find("wire()", 1, true) ~= nil and eleven ~= nil and eleven:find("1..10", 1, true) ~= nil
      and refusal(function() return actions:get("1") end) ~= nil and refusal(function() return third:res(1) end) ~= nil,
    ":get(0) names action:wire(), :get(11) names 1..10, and :get(\"1\") and a surplus argument are refused",
    tostring(zero) .. " / " .. tostring(eleven))
  subs[#subs + 1] = hafen.event():on("CombatActionChanged", function(action, where)
    local before
    for index = #changes, 1, -1 do
      if changes[index].action == action then before = changes[index].res; break end
    end
    changes[#changes + 1] = { action = action, session = where, res = action:res(), before = before, stepped = true }
  end)
  subs[#subs + 1] = hafen.event():on("ManeuverUsed", function(res, opponent, where)
    used[#used + 1] = { res = res, opponent = opponent, session = where }
  end)
  prompt("Attack ONE chicken, and press Done once the row of actions shows under your character.", fighting,
    skipped("a fight with an animal was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)

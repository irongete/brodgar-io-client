-- 170.4 — using a combat action. Self-checking suite; declares "fight.use".

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

local session, panel, waiter
local subs = {}
local sent = {}      -- every "use" and "rel" the combat row sends: { msg, widget type, args, place }
local used = {}      -- every ManeuverUsed of this character's own: the resource names
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if waiter then pcall(function() waiter:cancel() end) end
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
  panel = hafen.ui():window():title("170.4"):size(480, 70)
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

local function record(msg)
  return function(event)
    local args = event:args()
    local place = nil
    if msg == "use" and #args >= 4 then place = event:position(4) end
    sent[#sent + 1] = { msg = msg, widget = event:widget():type(), args = args, place = place }
  end
end

local function shape(entry)
  local parts = {}
  for index, value in ipairs(entry.args) do
    parts[index] = type(value) == "table" and "place" or tostring(value)
  end
  return entry.msg .. " {" .. table.concat(parts, ", ") .. "}"
end

-- Poll every 0.2 s until `ready()` answers true, for at most `limit` seconds, then run `go(ok)`.
local function waitFor(limit, ready, go)
  local spent = 0
  waiter = hafen.timer():every(0.2, guarded(function()
    spent = spent + 0.2
    if ready() or spent >= limit then
      waiter:cancel()
      waiter = nil
      go(ready())
    end
  end))
end

local function readyAction()
  local fight = session:fight()
  if (fight:cooldown() or 1) > 0 then return nil end
  return fight:action():find(function(action) return (not action:empty()) and action:cooldown() == 0 end)
end

local function ended()
  finish()
end

local function secondUse(action)
  local wire = action:wire()
  local here = session:player():gob():position()
  local from = #sent
  action:use(1, here)
  local use, rel = sent[from + 1], sent[from + 2]
  check(use ~= nil and use.widget == "Fightsess" and use.args[1] == wire and use.args[2] == 1 and use.args[3] == 1
      and use.place ~= nil and use.place:distance(here) < 11 and rel ~= nil and rel.msg == "rel"
      and rel.args[1] == wire and #sent == from + 2,
    "action:use(1, your position) sent use {" .. wire .. ", 1, 1, that place} and then rel {" .. wire .. "}",
    use and (shape(use) .. " / " .. (rel and shape(rel) or "nothing")) or "nothing")
  manualCheck("watch your character as the suite used " .. tostring(action:name()),
    "it performs that manoeuvre, twice in all")
  prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
end

local function firstUse(action)
  local wire, res = action:wire(), action:res()
  local from = #sent
  action:use()
  local use, rel = sent[from + 1], sent[from + 2]
  check(use ~= nil and use.widget == "Fightsess" and use.msg == "use" and use.args[1] == wire and use.args[2] == 1
      and use.args[3] == 0 and #use.args == 3 and rel ~= nil and rel.msg == "rel" and rel.args[1] == wire
      and #sent == from + 2,
    "action:use() sent exactly use {" .. wire .. ", 1, 0} and then rel {" .. wire .. "} from the combat row",
    use and (shape(use) .. " / " .. (rel and shape(rel) or "nothing")) or "nothing")
  local peak = 0
  waitFor(3, function()
    peak = math.max(peak, session:fight():cooldown() or 0, action:cooldown() or 0)
    for _, name in ipairs(used) do
      if name == res then return peak > 0 end
    end
    return false
  end, function(ok)
    check(ok, "the use took: a cooldown started and ManeuverUsed named " .. tostring(res),
      string.format("peak %d%%, %d uses", math.floor(peak * 100), #used))
    waitFor(15, function() return readyAction() == action end, function(ready)
      if not ready then
        check(false, "the action was ready again for a second use", "still cooling down after 15 s")
        return finish()
      end
      secondUse(action)
    end)
  end)
end

local function fighting()
  local actions = session:fight():action()
  local blank
  for _, action in ipairs(actions:list()) do
    if action:empty() then blank = action; break end
  end
  local from = #sent
  local refused = blank and refusal(function() return blank:use() end)
  local badMods = refusal(function() return actions:get(1):use(8) end)
  check(blank ~= nil and refused ~= nil and (refused:find("empty", 1, true) or refused:find("row has", 1, true))
      and badMods ~= nil and badMods:find("0..7", 1, true) ~= nil and #sent == from,
    "an empty action and mods 8 are refused, naming why, and nothing is sent",
    blank and (tostring(refused) .. " / " .. tostring(badMods))
      or "every action holds a manoeuvre -- load a school with an empty hotkey")
  waitFor(10, function() return readyAction() ~= nil end, function(ready)
    if not ready then
      check(false, "an action was ready to use", "none within 10 s")
      return finish()
    end
    firstUse(readyAction())
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, sent, used = {}, {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  subs[#subs + 1] = hafen.event():action():on("use", record("use"))
  subs[#subs + 1] = hafen.event():action():on("rel", record("rel"))
  subs[#subs + 1] = hafen.event():on("ManeuverUsed", function(res, opponent, where)
    if opponent == nil and where == session then used[#used + 1] = res end
  end)
  local first = session:fight():action():get(1)
  local surplus = refusal(function() return first:use(0, session:player():gob():position(), 1) end)
  local notPlace = refusal(function() return first:use(0, { x = 1, y = 2 }) end)
  check(surplus ~= nil and surplus:find("at most 2", 1, true) ~= nil and notPlace ~= nil and #sent == 0,
    "a surplus argument and a table for a place are refused, and nothing is sent",
    tostring(surplus) .. " / " .. tostring(notPlace))
  local outside = refusal(function() return first:use() end)
  check(outside ~= nil and outside:find("not in a fight", 1, true) ~= nil and #sent == 0,
    "out of a fight action:use() is refused naming the fight, and nothing is sent", outside)
  prompt("Attack ONE chicken, and press Done once the row of actions shows under your character.", fighting,
    skipped("a fight with an animal was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)

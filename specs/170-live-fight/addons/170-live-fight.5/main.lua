-- 170.5 — switching the target, pursuing, the give button. Self-checking suite; declares fight.set, fight.pursue
-- and fight.give.

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
local sent = {}       -- every bump, prs and give the combat view sends: { msg, widget type, args }
local selected = {}   -- every OpponentSelected payload of this character
local stashed         -- an opponent kept past the fight's end
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
  panel = hafen.ui():window():title("170.5"):size(500, 70)
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

-- The wire carries a gob id as a signed 32-bit number, so compare it modulo 2^32.
local function sameId(wire, id)
  return type(wire) == "number" and (wire % 4294967296) == (id % 4294967296)
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

local function ended()
  local fight = session:fight()
  local from = #sent
  local setGone = stashed and refusal(function() return fight:opponent():set(stashed) end)
  local pursueGone = stashed and refusal(function() return fight:pursue(stashed) end)
  check(stashed ~= nil and setGone ~= nil and setGone:find(":exists()", 1, true) ~= nil and pursueGone ~= nil
      and pursueGone:find(":exists()", 1, true) ~= nil and #sent == from,
    "after the fight, :set() and :pursue() refuse the opponent it had, naming :exists(), and nothing is sent",
    tostring(setGone) .. " / " .. tostring(pursueGone))
  finish()
end

local function giving(target)
  local fight = session:fight()
  local id = target:id()
  local before = target:give()
  local from = #sent
  fight:give(target)
  fight:give(target, 3)
  local tooMany = refusal(function() return fight:give(target, 4) end)
  local left, right = sent[from + 1], sent[from + 2]
  check(left ~= nil and left.msg == "give" and sameId(left.args[1], id) and left.args[2] == 1 and right ~= nil
      and right.msg == "give" and sameId(right.args[1], id) and right.args[2] == 3 and tooMany ~= nil
      and tooMany:find("1, 2 or 3", 1, true) ~= nil and #sent == from + 2,
    ":give(target) sent give {id, 1}, :give(target, 3) give {id, 3}, and :give(target, 4) is refused",
    tostring(left and left.args[2]) .. " " .. tostring(right and right.args[2]) .. " / " .. tostring(tooMany))
  waitFor(3, function()
    local now = target:give()
    return before ~= nil and now ~= nil and (now.mine ~= before.mine or now.theirs ~= before.theirs)
  end, function(moved)
    local now = target:give()
    check(moved, "the give state target:give() reads moved after the left click",
      (before and (tostring(before.mine) .. "/" .. tostring(before.theirs)) or "-") .. " -> "
        .. (now and (tostring(now.mine) .. "/" .. tostring(now.theirs)) or "-"))
    local half = (moved and now.mine ~= before.mine) and "left" or "right"
    manualCheck("look at the give button beside the target's portrait",
      "its " .. half .. " half is the one that changed when the suite clicked it")
    stashed = target
    prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
  end)
end

local function pursuing(target)
  local fight = session:fight()
  local from = #sent
  fight:pursue(target)
  local prs = sent[from + 1]
  check(prs ~= nil and prs.msg == "prs" and prs.widget == "Fightview" and sameId(prs.args[1], target:id())
      and #prs.args == 1,
    ":pursue(target) sent prs {its gob id} from the combat view", prs and prs.msg or "nothing")
  manualCheck("watch your character now", "it runs after the animal the fight has picked, as Pursue does")
  after(2, function() giving(target) end)
end

local function switching()
  local fight = session:fight()
  local opponents = fight:opponent()
  local first = opponents:current()
  local other = opponents:find(function(each) return each ~= first end)
  if not (first and other) then
    check(false, ":set() switches the target", "one opponent -- attack two animals")
    return finish()
  end
  local from, seen = #sent, #selected
  opponents:set(other)
  local bump = sent[from + 1]
  waitFor(3, function() return opponents:current() == other end, function(ok)
    local named = false
    for index = seen + 1, #selected do
      if selected[index] == other then named = true end
    end
    check(ok and named and bump ~= nil and bump.msg == "bump" and bump.widget == "Fightview"
        and sameId(bump.args[1], other:id()),
      ":set(other) sent bump {its gob id}, and :current() and OpponentSelected followed",
      bump and (bump.msg .. " " .. tostring(opponents:current())) or "nothing")
    local back = #sent
    opponents:set(first:id())
    waitFor(3, function() return opponents:current() == first end, function(again)
      local bumped = sent[back + 1]
      check(again and bumped ~= nil and bumped.msg == "bump" and sameId(bumped.args[1], first:id()),
        ":set(a gob id) switched back the same way", bumped and bumped.msg or "nothing")
      pursuing(first)
    end)
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, sent, selected, stashed = {}, {}, {}, nil
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  for _, msg in ipairs({ "bump", "prs", "give" }) do
    subs[#subs + 1] = hafen.event():action():on(msg, function(event)
      local args = event:args()
      sent[#sent + 1] = { msg = msg, widget = event:widget():type(), args = args }
      -- The right click's meaning is the server's: record it, and keep it from reaching the server.
      if msg == "give" and args[2] == 3 then event:preventDefault() end
    end)
  end
  subs[#subs + 1] = hafen.event():on("OpponentSelected", function(opponent, where)
    if where == session then selected[#selected + 1] = opponent end
  end)
  local fight = session:fight()
  local gob = session:player():gob()
  local refused = {
    refusal(function() return fight:opponent():set(gob) end),
    refusal(function() return fight:opponent():set("x") end),
    refusal(function() return fight:pursue(gob) end),
    refusal(function() return fight:give(nil) end),
  }
  local named = 0
  for index = 1, 4 do if refused[index] then named = named + 1 end end
  check(named == 4 and refused[1]:find("Opponent", 1, true) ~= nil and #sent == 0,
    ":set() and :pursue() refuse a Gob and a string, :give() refuses nil, naming why, and nothing is sent",
    named .. " of 4: " .. tostring(refused[1]))
  prompt("Attack TWO animals (a chicken and another) so both fight you, then press Done.", switching,
    skipped("a fight with two animals was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)

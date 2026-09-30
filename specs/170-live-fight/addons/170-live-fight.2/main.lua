-- 170.2 — the opponents. Self-checking suite.

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

local session, panel
local subs = {}
local log = {}   -- every opponent event, in order, with what the payload read at fire time
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
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
  panel = hafen.ui():window():title("170.2"):size(460, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function pairText(value)
  if value == nil then return "-" end
  return tostring(value.mine) .. "/" .. tostring(value.theirs)
end

local function record(key)
  return function(opponent, where)
    log[#log + 1] = { key = key, opponent = opponent, session = where, ip = pairText(opponent:ip()),
                      give = pairText(opponent:give()), exists = opponent:exists() }
  end
end

local function whole(value)
  return type(value) == "number" and value >= 0 and value == math.floor(value)
end

local function ended()
  local added, removed, strangers, alive = {}, {}, 0, 0
  for _, entry in ipairs(log) do
    if entry.key == "OpponentAdded" then added[entry.opponent] = true end
  end
  for _, entry in ipairs(log) do
    if entry.key == "OpponentRemoved" then
      removed[entry.opponent] = (removed[entry.opponent] or 0) + 1
      if not added[entry.opponent] then strangers = strangers + 1 end
      if entry.exists then alive = alive + 1 end
    end
  end
  local total, once, sample = 0, 0, nil
  for opponent in pairs(added) do
    total = total + 1
    if removed[opponent] == 1 then once = once + 1 end
    sample = sample or opponent
  end
  check(total > 0 and once == total and strangers == 0 and alive == 0,
    "OpponentRemoved came once for each opponent added, with the same object and :exists() false in the handler",
    once .. " of " .. total .. ", " .. strangers .. " unknown, " .. alive .. " alive")
  local opponents = session:fight():opponent()
  local info = sample and sample:info()
  check(sample ~= nil and sample:ip() == nil and sample:give() == nil and sample:opening():count() == 0
      and info.ip == nil and info.id == sample:id() and opponents:current() == nil and opponents:count() == 0,
    "after the fight: :ip() and :give() are nil, :opening() is empty, :info() is { id } and no opponent is left",
    sample and (pairText(sample:ip()) .. " " .. opponents:count() .. " left"))
  finish()
end

local function fighting()
  local opponents = session:fight():opponent()
  local target = opponents:current()
  local addedAt, selectedAt, sessions = nil, nil, 0
  for i, entry in ipairs(log) do
    if entry.session ~= session then sessions = sessions + 1 end
    if entry.opponent == target then
      if entry.key == "OpponentAdded" and not addedAt then addedAt = i end
      if entry.key == "OpponentSelected" and not selectedAt then selectedAt = i end
    end
  end
  check(target ~= nil and addedAt ~= nil and selectedAt ~= nil and addedAt < selectedAt and sessions == 0,
    "OpponentAdded came before OpponentSelected for the target, each with this session last",
    tostring(addedAt) .. " / " .. tostring(selectedAt) .. ", " .. sessions .. " with another session")
  local id = target and target:id()
  check(target ~= nil and opponents:get(id) == target
      and opponents:find(function(each) return each == target end) == target,
    "the target is :current(), :get(its gob id) and the payload those events handed: one object", target)
  local ip, give, info = target and target:ip(), target and target:give(), target and target:info()
  check(ip ~= nil and whole(ip.mine) and whole(ip.theirs) and give ~= nil and type(give.mine) == "boolean"
      and type(give.theirs) == "boolean" and info.id == id and info.ip.mine == ip.mine
      and info.give.theirs == give.theirs,
    "ip() is two whole numbers, give() two booleans, and info() carries the same", pairText(ip) .. " " .. pairText(give))
  local changes, repeats, last = 0, 0, {}
  for _, entry in ipairs(log) do
    if entry.key == "OpponentChanged" then
      changes = changes + 1
      local previous = last[entry.opponent]
      if previous and previous.ip == entry.ip and previous.give == entry.give then repeats = repeats + 1 end
    end
    last[entry.opponent] = entry
  end
  check(changes > 0 and repeats == 0, "OpponentChanged fired " .. changes .. " times, each for an IP or give that moved",
    changes .. " fired, " .. repeats .. " with nothing moved -- let a few blows land")
  local surplus = target and refusal(function() return target:ip(1) end)
  check(surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "an opponent refuses a surplus argument, naming the verb", surplus)
  manualCheck("read the two IP numbers the fight view paints now, beside you and beside the animal",
    "yours " .. (ip and ip.mine or "?") .. ", the animal's " .. (ip and ip.theirs or "?") .. " (they may have moved since)")
  prompt("End the fight (win it or walk away), then press Done.", ended, function()
    check(false, "the end of the fight was reached", "skipped")
    finish()
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  log, subs = {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  local opponents = session:fight():opponent()
  local refused = {
    refusal(function() return opponents:get("x") end),
    refusal(function() return opponents:list("x") end),
    refusal(function() return opponents:get(1.5) end),
    refusal(function() return opponents:current(1) end),
    refusal(function() return session:fight():target() end),
  }
  local named = 0
  for index = 1, 5 do if refused[index] then named = named + 1 end end
  check(opponents == session:fight():opponent() and named == 5 and refused[5]:find(":opponent()", 1, true) ~= nil,
    "session:fight():opponent() is one object, and :get(\"x\"), a string filter, :get(1.5), :current(1) and :target()"
      .. " are refused", named .. " of 5 refused: " .. tostring(refused[5]))
  check(opponents:count() == 0 and opponents:current() == nil, "out of a fight there is no opponent and no :current()",
    opponents:count())
  for _, key in ipairs({ "OpponentAdded", "OpponentRemoved", "OpponentChanged", "OpponentSelected" }) do
    subs[#subs + 1] = hafen.event():on(key, record(key))
  end
  prompt("Attack ONE chicken or rabbit, let a few blows land, and press Done while you fight.", fighting, function()
    check(false, "a fight with an animal was reached", "skipped")
    finish()
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)

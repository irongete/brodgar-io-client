-- 152.1 — gob:materials(): the slots read. Self-checking suite.
-- Stand by an object drawn in variable materials (a cupboard, a chest) and run :t152.

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

-- A refusal is a check: the call must fail, and fail SAYING why (every word of `wantMsg`).
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local said = not ok
  for _, want in ipairs({...}) do
    if err:find(want, 1, true) == nil then said = false end
  end
  check(said, what, err)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The nearest loaded object the server dressed in variable materials, or nil.
local function dressed(session)
  local best, bestDist
  for _, gob in ipairs(session:world():gob():list()) do
    local dist = gob:distance()
    if dist and gob:materials():count() > 0 and (not bestDist or dist < bestDist) then
      best, bestDist = gob, dist
    end
  end
  return best
end

local function checks(session, gob)
  local materials = gob:materials()
  local count = materials:count()
  local list = materials:list()
  eq("list() has count() members", #list, count)
  local ordered = true
  for n, slot in ipairs(list) do
    if slot:index() ~= n or slot:wire() ~= n - 1 or materials:get(n) ~= slot then ordered = false end
  end
  check(ordered, "list()[n]:index() == n, wire() == n - 1, get(n) == list()[n] (interned)", count)

  local slot = materials:get(1)
  local native = slot:native()
  local name = native and native:name()
  eq("native():name() is a string", type(name), "string")
  eq("native():loaded() -- the server dressed it, so the client holds it", native and native:loaded(), true)
  check(slot:material() == native and slot:drawn() == native, "material() and drawn() are native() (the same handle)",
        tostring(slot:material()) .. " / " .. tostring(slot:drawn()))
  local info = slot:info()
  check(info and info.native == name and info.index == 1 and info.wire == 0,
        "info() reads {index = 1, wire = 0, native = native():name()}", info and info.native)
  check(materials:count(name) >= 1 and materials:find(name) == slot,
        "count(native name) >= 1 and find(native name) answers slot 1", materials:count(name))
  eq("get(count + 1) is nil", materials:get(count + 1), nil)

  local player = session:player():gob()
  check(player and player:materials():count() == 0 and player:info().materials == nil,
        "the player's own gob counts 0 and its info() has no materials", player and player:materials():count())
  eq("gob:info().materials[1] is native():name()", gob:info().materials[1], name)

  refuses("get(0) is refused naming the 1-based rule and wire()", function() materials:get(0) end, "1-based", "wire()")
  refuses("get(\"a\") is refused naming a number", function() materials:get("a") end, "must be a number")
  refuses("get(1.5) is refused naming a whole number", function() materials:get(1.5) end, "whole number")
  summary()
end

local function run()
  local session = hafen.session():current()
  if not session then
    check(false, "a session is on screen", "none")
    return summary()
  end
  -- The object is the server's to send: retry on a timer over a bounded window, then score what the run reached.
  local tries = 0
  local poll
  poll = hafen.timer():every(0.5, function()
    tries = tries + 1
    local gob = dressed(session)
    if gob then
      poll:cancel()
      hafen.log():write("[info] dressed object: " .. tostring(gob:name()) .. " id " .. gob:id())
      return checks(session, gob)
    end
    if tries >= 20 then
      poll:cancel()
      check(false, "an object with variable materials is in view within 10 s", "none -- stand by a cupboard or chest")
      summary()
    end
  end)
end

hafen.console():on("t152", run)   -- the only way in: a suite does not start itself

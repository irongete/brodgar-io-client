-- 152.2 — slot:material(name[, id]): the write, and what is drawn. Self-checking suite.
-- Stand where two objects (or two slots of one) wear DIFFERENT materials — two cupboards of different
-- woods, a chest beside a cupboard — and run :t152. It leaves slot 1 of the nearest one dressed.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local BAD = "gfx/terobjs/no-such-material"

-- Every loaded object the server dressed in variable materials, nearest first.
local function dressed(session)
  local out = {}
  for _, gob in ipairs(session:world():gob():list()) do
    local dist = gob:distance()
    if dist and gob:materials():count() > 0 then out[#out + 1] = { gob = gob, dist = dist } end
  end
  table.sort(out, function(a, b) return a.dist < b.dist end)
  return out
end

-- A material in view that differs from `nativeName` — another slot's or another object's native — as its
-- name and layer id, so the write names exactly what that slot wears.
local function secondName(objects, nativeName)
  for _, entry in ipairs(objects) do
    for _, slot in ipairs(entry.gob:materials():list()) do
      local native = slot:native()
      if native and native:name() ~= nativeName then return native:name(), slot:info().id end
    end
  end
  return nil
end

-- The write lands on a frame the client holds the resource: poll `pred` on a timer over a bounded window.
local function waitFor(pred, what, andThen)
  local tries = 0
  local poll
  poll = hafen.timer():every(0.25, function()
    tries = tries + 1
    local got = pred()
    if got == true then
      poll:cancel()
      check(true, what)
      return andThen()
    end
    if tries >= 40 then
      poll:cancel()
      check(false, what .. " within 10 s", got)
      return andThen()
    end
  end)
end

local function refusals(slot, name)
  refuses("material(nil) is refused naming release", function() slot:material(nil) end, "release")
  refuses("material(42) is refused naming a string", function() slot:material(42) end, "string", "not a number")
  refuses("material(\"gfx//x\") is refused naming the name rule", function() slot:material("gfx//x") end, "empty segment")
  refuses("material(name, 1.5) is refused naming a whole number", function() slot:material(name, 1.5) end, "whole number")
end

local checks

local function run()
  local session = hafen.session():current()
  if not session then
    check(false, "a session is on screen", "none")
    return summary()
  end
  local tries = 0
  local poll
  poll = hafen.timer():every(0.5, function()
    tries = tries + 1
    local objects = dressed(session)
    if #objects > 0 then
      poll:cancel()
      return checks(session, objects)
    end
    if tries >= 20 then
      poll:cancel()
      check(false, "an object with variable materials is in view within 10 s", "none -- stand by a cupboard or chest")
      summary()
    end
  end)
end

checks = function(session, objects)
  local gob = objects[1].gob
  local slot = gob:materials():get(1)
  local nativeName = slot:native():name()
  local name, id = secondName(objects, nativeName)
  refusals(slot, name or nativeName)

  if not name then
    -- Only the server can put a second material in view: the swap is not checkable on this run.
    manualCheck("no second material in view: stand where two objects (or two slots) wear different materials and re-run :t152",
                "the swap checks run")
    return summary()
  end
  hafen.log():write("[info] " .. tostring(gob:name()) .. " id " .. gob:id() .. ": slot 1 " .. nativeName .. " -> " .. name .. " id " .. tostring(id))

  -- 1. The write: chains, material() follows at once, native() does not.
  check(slot:material(name, id) == slot and slot:material():name() == name,
        "slot:material(name, id) hands the slot back and material():name() is the name at once", slot:material():name())
  check(slot:native():name() == nativeName and slot:material() ~= slot:native(),
        "native() is unchanged, and is a different handle from material() now", slot:native():name())

  -- 2. The swap lands: drawn() follows when the client holds the resource (it does: another object wears it).
  waitFor(function() return slot:drawn():name() == name or slot:drawn():name() end,
          "drawn():name() follows the write", function()
    local info = slot:info()
    check(info and info.material == name and info.drawn == name and info.native == nativeName and info.id == id,
          "info() reads {material = written, drawn = written, native = server's, id = written}",
          info and (info.material .. " / " .. tostring(info.drawn) .. " / " .. tostring(info.id)))
    eq("gob:info().materials[1] is the written name", gob:info().materials[1], name)

    -- 3. A second write wins: a name the server has not got is ACCEPTED, and material() follows it at once.
    check(slot:material(BAD) == slot and slot:material():name() == BAD,
          "a second write (a name the server has not got) chains, and material() follows it at once", slot:material():name())
    waitFor(function() return slot:material():error() ~= nil or "error() nil, loaded() " .. tostring(slot:material():loaded()) end,
            "material():error() is non-nil once the fetch fails", function()
      -- The failure leaves the slot drawn in the server's material, not in the write before it.
      waitFor(function() return slot:drawn():name() == nativeName or slot:drawn():name() end,
              "drawn() is the server's once the name failed", function()
        eq("info().drawn is the server's name after the failure", slot:info().drawn, nativeName)

        -- 4. Back to the good name, so the object stays dressed for the look.
        slot:material(name, id)
        waitFor(function() return slot:drawn():name() == name or slot:drawn():name() end,
                "drawn() follows a write after a failed one", function()
          manualCheck("look at the object", "slot 1's part drawn in the second material, the rest unchanged")
          summary()
        end)
      end)
    end)
  end)
end

hafen.console():on("t152", run)   -- the only way in: a suite does not start itself

-- 152.3 — the endings: slot:release(), gob:materials():release(), teardown. Self-checking suite.
-- Stand where two objects (or two slots of one) wear DIFFERENT materials — two cupboards of different
-- woods, a chest beside a cupboard — and run :t152. It leaves slot 1 of the nearest one dressed, for
-- the :reload check.

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

-- A swap lands on a frame: poll `pred` on a timer over a bounded window.
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

local function drawnName(slot)
  local drawn = slot:drawn()
  return drawn and drawn:name()
end

-- Every slot of `gob` reads the server's material again, in force and in gob:info().
local function allNative(gob, what)
  local slots = gob:materials()
  local names = gob:info().materials or {}
  local back, got = true, ""
  for _, slot in ipairs(slots:list()) do
    if slot:material() ~= slot:native() or names[slot:index()] ~= slot:native():name() then
      back = false
      got = got .. " slot " .. slot:index() .. " " .. tostring(slot:material() and slot:material():name())
        .. " / info " .. tostring(names[slot:index()])
    end
  end
  check(back, what, got)
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
  local slots = gob:materials()
  local slot = slots:get(1)
  local nativeName, nativeId = slot:native():name(), slot:info().id

  -- 0. On an untouched slot and object both endings are no-ops that chain; the arity is checked.
  check(slot:release() == slot and slot:material() == slot:native(),
        "slot:release() on an untouched slot chains and material() stays native()", slot:material():name())
  check(slots:release() == slots, "gob:materials():release() on an untouched object chains", tostring(slots))
  refuses("slot:release(1) is refused naming the arity", function() slot:release(1) end, "no arguments")
  refuses("gob:materials():release(1) is refused naming the arity", function() slots:release(1) end, "no arguments")

  local name, id = secondName(objects, nativeName)
  if not name then
    -- Only the server can put a second material in view: the swap is not checkable on this run.
    manualCheck("no second material in view: stand where two objects (or two slots) wear different materials and re-run :t152",
                "the release checks run")
    return summary()
  end
  hafen.log():write("[info] " .. tostring(gob:name()) .. " id " .. gob:id() .. ": slot 1 " .. nativeName .. " -> " .. name .. " id " .. tostring(id))

  -- 1. Dress slot 1 as 152.2 does, and wait for the swap to land.
  slot:material(name, id)
  waitFor(function() return drawnName(slot) == name or drawnName(slot) end,
          "drawn():name() follows the write", function()
    -- 2. slot:release(): chains, material() is native() at once, drawn() follows on the rebuild.
    check(slot:release() == slot and slot:material() == slot:native(),
          "slot:release() chains and material() == native() at once", slot:material() and slot:material():name())
    waitFor(function() return drawnName(slot) == nativeName or drawnName(slot) end,
            "drawn() is the server's again after the release", function()
      check(slot:release() == slot and slot:material() == slot:native(),
            "a second slot:release() is a no-op that chains", slot:material() and slot:material():name())

      -- 3. The collection's release, after two slots written (one, when the object has one slot).
      local second = slots:get(2)
      local written = { slot }
      slot:material(name, id)
      if second then
        -- The second slot takes whichever of the two names is not its own, so both are writes.
        if second:native():name() ~= name then second:material(name, id) else second:material(nativeName, nativeId) end
        written[2] = second
      else
        hafen.log():write("[info] the object has one slot: the collection's release is checked over it alone")
      end
      local dressedAll = true
      for _, s in ipairs(written) do
        if s:material() == s:native() then dressedAll = false end
      end
      check(dressedAll, (#written) .. " slot(s) written before the collection's release", tostring(dressedAll))
      check(slots:release() == slots, "gob:materials():release() chains", tostring(slots))
      allNative(gob, "every material() reads native() again, and gob:info().materials the natives")
      waitFor(function()
        for _, s in ipairs(written) do
          if drawnName(s) ~= s:native():name() then return "slot " .. s:index() .. " drawn " .. tostring(drawnName(s)) end
        end
        return true
      end, "every released slot is drawn in the server's material again", function()
        -- 4. Leave slot 1 dressed for the reload check: teardown is verified through it.
        slot:material(name, id)
        waitFor(function() return drawnName(slot) == name or drawnName(slot) end,
                "slot 1 is dressed again for the reload check", function()
          manualCheck("slot 1 is left dressed: type :reload and look at the object",
                      "it wears the server's material again, and no console error")
          summary()
        end)
      end)
    end)
  end)
end

hafen.console():on("t152", run)   -- the only way in: a suite does not start itself

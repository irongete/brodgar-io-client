-- 059.4 — drag it onto the action bar. Self-checking suite.

local SLOT = 143              -- the last slot of the bar: the one least likely to hold anything of the player's
local NAME = "059.4 Bar test"

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

-- A refusal is a check: the call must fail, and fail SAYING why. nil when it did, the message when it did not.
local function refusal(fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  if (not ok) and (err:find(wantMsg, 1, true) ~= nil) then return nil end
  return err
end

-- One verdict line over a group of refusals that make ONE claim; a failure names which case broke it.
local function refuses(what, cases)
  for _, c in ipairs(cases) do
    local bad = refusal(c[2], c[3])
    if bad then
      check(false, what, c[1] .. " -- " .. bad)
      return
    end
  end
  check(true, what)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- One of the client's OWN entries, for the refusals: the first root that is not an addon's.
local function serverEntry()
  for _, r in ipairs(hafen.menugrid():roots()) do
    if r:res():sub(1, 6) ~= "addon/" then return r end
  end
  return nil
end

local function run()
  pass, fail, manual = 0, 0, 0                 -- the command is run again after every fix round
  local mg   = hafen.menugrid()
  local slot = hafen.actionbar():get(SLOT)

  slot:pagina(nil)                             -- a re-run starts clean: the slot first, then the entry
  mg:remove("dig")                             -- (both are inert when there is nothing to undo)

  local before = slot:res()                    -- what the server has in this slot, to be handed back
  local pag = mg:add("dig"):name(NAME):icon(hafen.asset():get("dig.png"))
  pag:on("use", function() hafen.log():write("[click] 059.4: the bar button ran this line") end)

  local fired = {}
  local sub = hafen.event():on("ActionbarChanged", function(s)
    fired[s:index()] = (fired[s:index()] or 0) + 1
  end)

  slot:pagina(pag)
  eq("a held slot names the ENTRY in :res() and in :info(), never the stand-in it is drawn over",
     slot:res() .. " | " .. tostring(slot:info().res), pag:res() .. " | " .. pag:res())
  check(slot:pagina() == pag, "slot:pagina() hands back the very entry the slot is held for", slot:pagina())
  eq("a held slot is not empty, and it reads the name the entry was given",
     tostring(slot:empty()) .. " | " .. tostring(slot:name()), "false | " .. NAME)

  local theirs = serverEntry()
  local cases = {
    {"a number",     function() slot:pagina(7) end,     "expected the Pagina object"},
    {"a key string", function() slot:pagina("dig") end, "expected the Pagina object"},
  }
  if theirs ~= nil then
    table.insert(cases, 1, {theirs:res(), function() slot:pagina(theirs) end, "is the client's own entry"})
  end
  refuses("slot:pagina refuses an entry this addon did not add, and anything that is not an entry", cases)
  refuses("slot:res refuses a custom entry's id, naming the verb that holds a slot instead", {
    {pag:res(), function() slot:res(pag:res()) end, "slot:pagina(pag)"},
  })

  -- The event is delivered on a tick, as every event in this API is, so both edges are read off a timer.
  hafen.timer():after(0.5, function()
    check((fired[SLOT] or 0) > 0, "ActionbarChanged fired for the slot the hold took", fired[SLOT])
    fired[SLOT] = 0

    slot:pagina(nil)
    eq("ending the hold puts back exactly what the server has in the slot", slot:res(), before)
    eq("and the slot is nobody's hold again", slot:pagina(), nil)

    hafen.timer():after(0.5, function()
      check((fired[SLOT] or 0) > 0, "ActionbarChanged fired again on the releasing edge", fired[SLOT])
      sub:off()
      manualCheck("open the action menu, drag the \"" .. NAME .. "\" button onto a slot on the bar,"
                  .. " then press that slot's key",
                  "the same PNG drawn in the slot, and one \"[click] 059.4\" line in the log per press")
      manualCheck("right-click that slot", "the button gone and whatever the slot held before back in it")
      summary()
    end)
  end)
end

hafen.slash():register("t059-4", run)   -- the only way in: a suite does not start itself

-- 073.3 — the character readers are one HUD's, not the client's. Self-checking suite.
--
-- CharApi's nine change-detection adapters and BeltHold's holds and placements are one per session now:
-- the adapters are built with their session's state and read THAT session's GameUI, and a slot index
-- names one character's action bar. With one session live that index holds exactly one entry, so nothing
-- an addon can see changes -- which is the claim, and what every line below is: the same surfaces, read
-- back through the very paths that were rewired.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- An adapter's surface ANSWERS OR IS HONESTLY ABSENT: the call must not throw, and the line says which
-- of the two it was. A window the player has never opened has nothing to read, and that is not a failure --
-- what would be one is the read raising, which is what a reader that lost its HUD does. `kind` is "list" or
-- "one", because a collection's emptiness and an object's absence are different answers.
local function answers(what, kind, fn)
  local ok, v = pcall(fn)
  local d
  if not ok then
    d = tostring(v):gsub("^.-%.lua:%d+:%s*", "")
  elseif kind == "list" then
    d = ((v == nil) and "no list" or (#v .. " entries"))
  else
    d = ((v == nil) and "absent" or "present")
  end
  check(ok and ((kind ~= "list") or (v ~= nil)), what .. " (" .. d .. ")", d)
end

local WINDOW, STEP = 8.0, 0.5        -- how long a meter has to move on its own; the poll

local function run()
  pass, fail, manual = 0, 0, 0

  -- THE METER ADAPTER'S EVENT, armed FIRST so the whole run is inside its window. A meter moves by
  -- itself in the world -- stamina on a step, hunger on a bite -- so this is the one adapter whose
  -- firing can be watched rather than only read, and it is the one the moved dirty-set feeds.
  local fired = 0
  local sub = hafen.event():on("MeterChanged", function() fired = fired + 1 end)
  manualCheck("within the next " .. WINDOW .. "s, eat something or take a few steps to move stamina",
              "the [meter] line below reports: fired")

  -- THE NINE ADAPTERS' READ SURFACES, one line each. Meters are the one with a floor: in the world the
  -- HUD always has bars, so an empty list here is the adapter having lost its HUD, not a quiet moment.
  local meters = hafen.meter():list()
  check((meters ~= nil) and (#meters > 0),
        "hafen.meter():list() has the HUD's bars (" .. ((meters == nil) and "nil" or #meters) .. ")",
        (meters == nil) and "nil" or #meters)
  answers("hafen.buff():list()", "list", function() return hafen.buff():list() end)
  answers("hafen.char():food()", "one", function() return hafen.char():food() end)
  answers("hafen.study():slot():list()", "list", function() return hafen.study():slot():list() end)
  answers("hafen.actionbar():get(0)", "one", function() return hafen.actionbar():get(0) end)
  answers("hafen.ui():equipment():items()", "list", function()      -- nil when the window was never opened
    local e = hafen.ui():equipment()
    return (e == nil) and {} or e:items()
  end)
  answers("hafen.kin():list()", "list", function() return hafen.kin():list() end)
  answers("hafen.quest():list()", "list", function() return hafen.quest():list() end)
  answers("hafen.wound():list()", "list", function() return hafen.wound():list() end)

  -- THE BAR: hold a slot, read it back as the entry, give it back. Both edges in ONE synchronous run,
  -- so the placement is taken and forgotten inside a single tick and the character's holds file is never
  -- written -- a suite does not mutate persistent state. An EMPTY slot, so what goes back is emptiness.
  local slot, seat
  for _, s in ipairs(hafen.actionbar():list()) do
    if s:empty() then slot = s; break end
  end
  if slot == nil then
    check(false, "the bar has an empty slot to borrow", "all 144 occupied")
  else
    seat = slot:index()
    local ok, err = pcall(function()
      local pag = hafen.menugrid():add("s073-3"):name("073.3 probe")
      slot:pagina(pag)
      check(slot:res() == "addon/073-caches-know-their-session.3/s073-3",
            "a held slot reads back the entry's own identity, on slot " .. seat, slot:res())
      refuses("slot:pagina() refuses anything that is not the Pagina object",
              function() slot:pagina("s073-3") end, "Pagina")
      slot:pagina(nil)
      check(slot:empty() and (slot:res() == nil),
            "...and ending the hold gives the server's own content back", tostring(slot:res()))
      hafen.menugrid():remove(pag)
    end)
    if not ok then
      check(false, "hold and release one action-bar slot",
            tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    end
  end

  -- THE WINDOW closes on a timer, and what it saw is REPORTED rather than scored: a run in which no
  -- meter happened to move proves nothing either way, which is what the [manual] line above is for.
  local waited = 0
  local function score()
    if (fired == 0) and (waited < WINDOW) then
      waited = waited + STEP
      hafen.timer():after(STEP, score)
      return
    end
    sub:off()
    hafen.log():write(("[meter] MeterChanged within %.1fs: %s"):format(
      WINDOW, (fired > 0) and ("fired (" .. fired .. ")") or "not seen"))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
  hafen.timer():after(STEP, score)
end

hafen.slash():register("t073-3", run)   -- the only way in: a suite does not start itself

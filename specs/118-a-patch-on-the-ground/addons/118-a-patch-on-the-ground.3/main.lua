-- 118.3 -- a patch answers a click. Self-checking suite.
--
-- It declares NO permissions: every call below succeeding is what unprotected means here. A patch has no
-- server id, never reaches the wire and grants nothing -- a click on one is detected on your own screen and
-- consumed there, so nothing about it is sent.
--
-- Nothing in the client can deliver a click from Lua, so the FIRING itself is the maintainer's: the two
-- manual lines below are the whole of it, and what arrives is printed as one [click] line each.

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

-- A refusal is a check: the call must fail, and fail SAYING why. The strip covers both shapes LuaJ writes --
-- "@chunk.lua:12: msg" for a Lua error and "@chunk.lua:12 msg" for one raised across the bridge.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- A square ring of Positions of side 2r, centred on p -- the same shape gob:hitbox() hands back one ring at
-- a time, and what :add takes with no projection and no conversion by the caller.
local function square(p, r)
  return { p:offset(-r, -r), p:offset(r, -r), p:offset(r, r), p:offset(-r, r) }
end

-- LuaJ's string.format ignores a precision, so a world coordinate is rounded by hand to read as one.
local function num(v)
  if type(v) ~= "number" then return tostring(v) end
  return tostring(math.floor((v * 10) + 0.5) / 10)
end

-- What the last PatchClicked handed this addon, kept for the :onClick that runs right behind it so the two
-- halves of one click are reported as one line rather than as two.
local seen = nil
-- The live subscription and the patch left standing for the manual clicks, so a re-run starts from nothing.
local live, standing = nil, nil

local function run()
  if live then live:off() end
  live = nil
  local s = hafen.session():current()
  local pg = s and s:player():gob()
  local here = pg and pg:position()
  if not here then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  local patches = hafen.vr():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end
  standing = nil

  -- ---- THE COLLECTION, re-asserted: this suite stands alone -----------------------------------------
  local p = patches:add(square(here, 11), here)
  check((p ~= nil) and p:exists(), "a ring of Positions and a place lay a patch", p)
  check(patches:count() == 1, "the collection holds exactly it", patches:count())

  -- ---- THE CLICK PAIR ------------------------------------------------------------------------------
  check(p:clickable() == false, "a fresh patch is not clickable", p:clickable())
  check((p:clickable(true) == p) and (p:clickable() == true),
        ":clickable(b) round-trips and hands the patch back", p:clickable())

  local fn = function() end
  local back = p:onClick(fn)
  check(back == p, ":onClick(fn) hands the patch back, so a chain continues", back)
  check(p:onClick() == fn, ":onClick() reads back the function it was set to", p:onClick())
  refuses(":onClick(x) is refused naming what it expects",
          function() p:onClick(5) end, "expects a function")

  -- The snapshot carries a key per reader, so the pair above put one in it.
  check(p:info().clickable == true, ":info() carries clickable", p:info().clickable)

  -- ---- THE BUS -------------------------------------------------------------------------------------
  local probe = hafen.event():on("PatchClicked", function() end)
  check((probe ~= nil) and (probe:off() == probe),
        "PatchClicked hands back a Sub, and sub:off() hands it back", probe)

  summary()

  -- ---- WHAT THE MAINTAINER FIRES -------------------------------------------------------------------
  -- The bus fires before the patch's own callback, so the event is recorded here and the callback prints
  -- the one line that says both halves arrived and agreed.
  live = hafen.event():on("PatchClicked", function(ev)
    seen = { patch = ev:patch(), button = ev:button(), x = ev:x(), y = ev:y(),
             ghost = ev:ghost(), sprite = ev:sprite(), object = ev:object() }
  end)
  standing = p
  p:tint({ 40, 200, 120 })
  p:onClick(function(patch, button, x, y)
    local e = seen
    seen = nil
    hafen.log():write("[click] inside -- ev:patch() is the patch: "
      .. tostring((e ~= nil) and (e.patch == standing))
      .. ", only that noun answers: "
      .. tostring((e ~= nil) and (e.ghost == nil) and (e.sprite == nil) and (e.object == nil))
      .. ", :onClick got the same patch: " .. tostring(patch == standing)
      .. ", button " .. tostring(button) .. "/" .. tostring(e and e.button)
      .. ", at " .. num(x) .. "," .. num(y))
  end)

  manualCheck("left-click INSIDE the green patch under your character",
              "exactly one [click] inside line, every `true` in it true, and the character does NOT walk"
              .. " -- the click is consumed")
  manualCheck("left-click a hand's width OUTSIDE the green patch's edge",
              "no [click] line at all, and the character walks there as it always did")
end

hafen.console():on("t118", run)   -- the only way in: a suite does not start itself

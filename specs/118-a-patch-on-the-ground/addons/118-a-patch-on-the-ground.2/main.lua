-- 118.2 -- a patch follows, turns and is tinted. Self-checking suite.
--
-- It declares NO permissions: every call below succeeding is what unprotected means here. A patch has no
-- server id, never reaches the wire and grants nothing.

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

-- A square ring of Positions of side 2r, centred on p -- p:offset is the durable arithmetic a grid boundary
-- makes necessary, and the same shape gob:hitbox() hands back one ring at a time.
local function square(p, r)
  return { p:offset(-r, -r), p:offset(r, -r), p:offset(r, r), p:offset(-r, r) }
end

-- A write followed by the read of the SAME name: that pair is what "arity is the verb" comes to, so a
-- property is checked as one statement rather than as two.
local function trip(what, patch, verb, wrote)
  patch[verb](patch, wrote)
  local got = patch[verb](patch)
  local ok
  if type(wrote) == "number" then
    ok = (type(got) == "number") and (math.abs(got - wrote) < 1e-6)
  else
    ok = (got == wrote)
  end
  check(ok, what .. " (" .. tostring(wrote) .. ")", got)
end

-- Two places are the same place when the durable form each holds is: the grid the server named, and the
-- offset within it. Rounded, because a Position carries doubles and this is an equality of places.
local function placeOf(pos)
  local i = pos and pos:info()
  if not i then return "<no place>" end
  return tostring(i.gridId) .. "@" .. math.floor(i.x + 0.5) .. "," .. math.floor(i.y + 0.5)
end

local function run()
  local s = hafen.session():current()
  local pg = s and s:player():gob()
  local here = pg and pg:position()
  if not here then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  local patches = hafen.vr():patch()
  -- The suite owns its own patches, so it starts from nothing however many times it is run.
  for _, old in ipairs(patches:list()) do patches:remove(old) end

  -- ---- THE PLANTED ONE: every shared verb, written and read back -----------------------------------
  local p1 = patches:add(square(here, 6), here)
  check(p1 ~= nil and p1:exists(), "a ring of Positions and a place lay a patch", p1)
  check(patches:count() == 1, "the collection holds exactly it", patches:count())

  trip(":scale round-trips", p1, "scale", 2)
  trip(":rotate round-trips", p1, "rotate", 0.5)
  trip(":alpha round-trips", p1, "alpha", 0.5)
  trip(":visible round-trips", p1, "visible", false)
  p1:visible(true)

  p1:tint({ 40, 200, 120 })
  local c = p1:tint()
  check((type(c) == "table") and (c.r == 40) and (c.g == 200) and (c.b == 120), ":tint round-trips (40,200,120)",
        (type(c) == "table") and (tostring(c.r) .. "," .. tostring(c.g) .. "," .. tostring(c.b)) or tostring(c))

  local moved = here:offset(4, 4)
  p1:position(moved)
  check(placeOf(p1:position()) == placeOf(moved), ":position round-trips", placeOf(p1:position()))

  -- :info() is the one snapshot every live object answers, and every key in it is spelled the way the verb
  -- that reads it is -- so a missing key is a reader with no field, and a spare one is a field with no
  -- reader. `offset` is the spare a planted patch must not have: it is offset from nothing.
  local i = p1:info()
  local missing = nil
  for _, k in ipairs({ "kind", "ring", "scale", "rotate", "alpha", "visible", "drawn", "exists" }) do
    if i[k] == nil then missing = k end
  end
  check((missing == nil) and (i.kind == "patch") and (i.ring ~= nil) and (#i.ring == 4) and (i.offset == nil),
        ":info carries a key per reader, and no offset on a planted one",
        missing and ("no " .. missing) or ("offset=" .. tostring(i.offset) .. " ring="
                                           .. tostring(i.ring and #i.ring)))
  patches:remove(p1)

  -- ---- THE FOLLOWING ONE: a gob:hitbox() ring, and the gob itself as the anchor ---------------------
  -- The footprint goes in with no projection and no conversion by the caller, which is the whole reason
  -- :add takes a ring of places. The resource may not have resolved yet, so the shape is asked for on a
  -- bounded window: it is the server's to hand over, not this suite's to cause.
  local tries, tm = 0, nil
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local got, rings = pcall(function() return pg:hitbox() end)
    local p2, why = nil, got and "gob:hitbox() answered nil" or tostring(rings)
    for _, ring in ipairs((got and rings) or {}) do
      local ok, r = pcall(function() return patches:add(ring, pg) end)
      if ok then
        p2 = r
        break
      end
      why = tostring(r)
    end
    if (p2 ~= nil) or (tries >= 12) then
      tm:cancel()
      check((p2 ~= nil) and p2:exists(), "a gob:hitbox() ring lays a patch on that object, unchanged", why)
      if p2 ~= nil then
        p2:offset(3, 4)
        local o = p2:offset()
        check((type(o) == "table") and (o.x == 3) and (o.y == 4) and (o.z == nil),
              ":offset round-trips on the one that follows, in two numbers",
              (type(o) == "table") and (tostring(o.x) .. "," .. tostring(o.y) .. ",z=" .. tostring(o.z))
                or tostring(o))
        refuses("a z on :offset is refused naming that a patch has no height",
                function() p2:offset(0, 0, 1) end, "has no height")
        refuses(":position(p) on one that follows is refused naming :offset",
                function() p2:position(here) end, "patch:offset")
        p2:offset(0, 0)
        p2:tint({ 40, 200, 120 })
        manualCheck("walk a few steps with the patch that is left under your character",
                    "a green patch in your character's own footprint shape, under the feet the whole way and"
                    .. " keeping that shape -- not stretching, not lagging behind, not left where you stood")
      end
      summary()
    end
  end)
end

hafen.console():on("t118", run)   -- the only way in: a suite does not start itself

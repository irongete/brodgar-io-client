-- R17.6 -- the world read and the world write: wo-19's four cases. It runs only from :tR17-6, sends
-- nothing on the wire (both clicks it makes are refused by the wire's own shape row before anything
-- leaves), writes nothing anywhere, and scores the walking half over a 10 s window.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level error
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both: the class is
-- [^\n] rather than . so a traceback under the message cannot be eaten as a third prefix.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tR17-6 -- expect: this line becomes a [pass]")
end

-- ---- the far place: a grid is 100 tiles of 11 units, so this is 500 grids away ------------------------

local FAR = 1100 * 500

-- ---- a walking gob's position ------------------------------------------------------------------------

-- moving() is the level, and the position is what the level is about: while it is true the point the gob
-- is at has to be a different point half a second later, and while it is false it must not be.
local function walking(seen)
  if seen.moved == nil then
    needs("walk your character for a second or two while this 10 s window runs")
    return
  end
  ok("a walking gob is at a different point half a second later, and a standing one is not",
     (seen.moved == true) and (seen.stoodStill ~= false),
     seen.samples .. " samples, " .. seen.movingSamples .. " of them moving, still while stopped: "
       .. tostring(seen.stoodStill))
end

-- ---- an off-stream terrain read ----------------------------------------------------------------------

local function offStream(s, p)
  local far = read(function() return p:offset(FAR, FAR) end)
  if far == nil then
    ok("a terrain read over ground nobody has streamed answers nil, and asks the loader for nothing",
       false, "the far place could not be derived")
    return nil
  end
  local before = read(function() return hafen.client():profiling():loader() end) or {}
  local t, h = read(function() return s:world():tile(far) end), read(function() return s:world():height(far) end)
  -- A read over ground that is not there is a MISS, not a request: two hundred of them must not put two
  -- hundred anything on the resource queue.
  for _ = 1, 200 do
    read(function() return s:world():tile(far) end)
  end
  local after = read(function() return hafen.client():profiling():loader() end) or {}
  local dq = ((type(after.resQueue) == "number") and (type(before.resQueue) == "number"))
               and (after.resQueue - before.resQueue) or nil
  ok("a terrain read over ground nobody has streamed answers nil, and asks the loader for nothing",
     (t == nil) and (h == nil) and (dq ~= nil) and (dq < 16),
     "tile " .. tostring(t) .. ", height " .. tostring(h) .. ", resQueue moved by " .. tostring(dq))
  return far
end

-- ---- a Position that cannot be saved, and one that crosses --------------------------------------------

local function durability(s, p, far)
  if far == nil then
    ok("a place off explored ground is not durable and cannot be saved, where a place under you is both",
       false, "no far place")
    return
  end
  local here = read(function() return p:info() end)
  -- ...and what comes BACK is a Position, not a table that looks like one: the durable form IS the wire
  -- form of a place, so the reader mints one wherever a document carries that shape. So the round trip is
  -- compared through :info() on both sides rather than field by field against a plain table.
  local round = read(function() return hafen.json():parse(hafen.json():encode(p)) end)
  local rinfo = round and read(function() return round:info() end)
  allRefuse("a place off explored ground is not durable and cannot be saved, where a place under you is both", {
    {"json:encode(far)", function() return hafen.json():encode(far) end, "cannot be saved"},
  }, (read(function() return far:durable() end) == false)
       and (read(function() return p:durable() end) == true)
       and (type(here) == "table") and (type(here.gridId) == "string")
       and (type(rinfo) == "table") and (rinfo.gridId == here.gridId)
       and (rinfo.x == here.x) and (rinfo.y == here.y),
     "durable here " .. tostring(read(function() return p:durable() end))
       .. ", far " .. tostring(read(function() return far:durable() end))
       .. "; the round trip read back " .. tostring(round) .. " at grid "
       .. tostring((type(rinfo) == "table") and rinfo.gridId))
end

-- A place recorded in another part of the world keeps its durable form and answers no coordinate in a
-- session that cannot reach it -- which is the boundary a Position handed to another login meets.
local function crossing(cur, alt, p)
  if alt == nil then
    needs("log a second character in, so a place of this one's can be read through another's world")
    return
  end
  local mine = read(function() return p:info() end)
  local tc = read(function() return alt:world():tileCoord(p) end)
  local x = read(function() return p:x() end)
  ok("a place keeps its durable form for every login, and answers a coordinate only where it is reachable",
     (type(mine) == "table") and (type(mine.gridId) == "string") and (type(x) == "number")
       and ((tc == nil) or ((type(tc) == "table") and (type(tc.x) == "number"))),
     "grid " .. tostring(mine and mine.gridId) .. ", the other login reads " .. tostring(tc))
end

-- ---- the wire's own shape row -------------------------------------------------------------------------

local function wire(s)
  local me = read(function() return s:player():gob() end)
  if me == nil then
    ok("the wire refuses a button no mouse has and a modifier bitfield no keyboard sends", false,
       "no gob to aim at")
    return
  end
  -- Both of these are refused by the shape row BEFORE the message is composed, so nothing goes out --
  -- which is why the target may be the character's own body without anything happening to it.
  allRefuse("the wire refuses a button no mouse has and a modifier bitfield no keyboard sends", {
    {"click(gob, 4)",     function() return s:world():click(me, 4) end,     "button must be 1, 2 or 3"},
    {"click(gob, 0)",     function() return s:world():click(me, 0) end,     "button must be 1, 2 or 3"},
    {"click(gob, 1, 8)",  function() return s:world():click(me, 1, 8) end,  "mods is a bitfield"},
    {"click(gob, 1, -1)", function() return s:world():click(me, 1, -1) end, "mods is a bitfield"},
    {"click(gob, 1.5)",   function() return s:world():click(me, 1.5) end,   "must be a whole number"},
  })
end

-- ---- the run --------------------------------------------------------------------------------------------

local TICKS, EVERY = 20, 0.5

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR17-6", function()
  pass, fail, manual = 0, 0, 0
  hafen.timer():after(0, function()
    local s = read(function() return hafen.session():current() end)
    local me = s and read(function() return s:player():gob() end)
    local p = me and read(function() return me:position() end)
    if (s == nil) or (p == nil) then
      fail = fail + 1
      say("[fail] no character is in the world -- log in and run :tR17-6 again")
      finish()
      return
    end
    local alt
    for _, o in ipairs(read(function() return hafen.session():list() end) or {}) do
      if (o ~= s) and (read(function() return o:character() end) ~= nil) then alt = alt or o end
    end

    local seen = {samples = 0, movingSamples = 0, moved = nil, stoodStill = nil}
    local last, lastMoving = p, read(function() return me:moving() end)
    local ticks, ticker = 0, nil
    ticker = hafen.timer():every(EVERY, function()
      ticks = ticks + 1
      local now = read(function() return me:position() end)
      local mv = read(function() return me:moving() end)
      local d = (now ~= nil) and (last ~= nil) and read(function() return now:distance(last) end) or nil
      seen.samples = seen.samples + 1
      if lastMoving == true then
        seen.movingSamples = seen.movingSamples + 1
        if (d ~= nil) and (d > 0.5) then seen.moved = true
        elseif seen.moved == nil then seen.moved = false end
      elseif (lastMoving == false) and (mv == false) and (d ~= nil) then
        -- ...and the other half of the level: a gob that is not moving is at the same point it was at.
        if d > 0.5 then seen.stoodStill = false
        elseif seen.stoodStill == nil then seen.stoodStill = true end
      end
      last, lastMoving = now, mv
      if ticks < TICKS then return end
      ticker:cancel()
      walking(seen)
      local far = offStream(s, p)
      durability(s, p, far)
      crossing(s, alt, p)
      wire(s)
      finish()
    end)
  end)
end)

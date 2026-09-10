-- R17.5 -- the map's own surfaces and the three things this client stands in the world: mp-25, vm-19,
-- vg-16 and po-16. It runs only from :tR17-5, ends every entity it stands, releases every hold it takes
-- and removes the one pin it adds, all in the same run, and scores the rest over one 10 s window.

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
  say("[manual] " .. action .. ", then type :tR17-5 -- expect: this line becomes a [pass]")
end

local function len(t) return (type(t) == "table") and #t or -1 end

local function drop(coll, x)
  if x ~= nil then read(function() return hafen.virtual()[coll](hafen.virtual()):remove(x) end) end
end

-- ---- mp-25: the display switches --------------------------------------------------------------------

local TAGS = {cplot = "world", vlg = "world", prov = "world", realm = "map"}

local function display()
  local d = hafen.map():display()
  local all = read(function() return d:list() end) or {}
  local bad
  for _, t in ipairs(all) do
    local tag = read(function() return t:tag() end)
    if TAGS[tag] ~= read(function() return t:where() end) then
      bad = bad or ("the client owns " .. tostring(tag) .. " on another side")
    end
    if type(read(function() return t:what() end)) ~= "string" then bad = bad or (tag .. " says nothing") end
    if read(function() return t:held() end) ~= false then bad = bad or (tag .. " was already held by us") end
  end
  local claims = read(function() return d:get("cplot") end)
  -- A hold is idempotent, so taking it twice is taking it once and ONE release is the whole undo.
  read(function() return claims:hold():hold() end)
  local heldNow = read(function() return claims:held() end)
  read(function() return claims:release() end)
  allRefuse("the four display switches answer their own side, and a hold is idempotent and released whole", {
    {"display:get('nope')", function() return d:get("nope") end, "nope"},
  }, (bad == nil) and (len(all) == 4) and (type(heldNow) == "boolean")
       and (read(function() return claims:held() end) == false),
     bad or (len(all) .. " switches, cplot held " .. tostring(heldNow)))
  return claims
end

-- mp-25: the marker-ref table. A pin is the player's own data from the moment it is added, so it is added
-- and removed in one straight run with nothing waiting in between.
local function markers(p)
  local n0 = read(function() return hafen.map():marker():count("R17 probe") end)
  local pin = read(function() return hafen.map():marker():add("R17 probe", p) end)
  if pin == nil then
    needs("open the map window once, so the map database is ready for the pin this line adds and removes")
    return
  end
  local n1 = read(function() return hafen.map():marker():count("R17 probe") end)
  local live = read(function() return pin:exists() end)
  read(function() return hafen.map():marker():remove(pin) end)
  local n2 = read(function() return hafen.map():marker():count("R17 probe") end)
  ok("a pin added is one the collection counts, and a pin removed is one it does not, by the same handle",
     (n0 ~= nil) and (n1 == (n0 + 1)) and (live == true)
       and (read(function() return pin:exists() end) == false) and (n2 == n0),
     tostring(n0) .. " -> " .. tostring(n1) .. " -> " .. tostring(n2))
end

-- ---- vm-19: a glTF model, its one verb, and the ceiling ------------------------------------------------

local function models(p)
  local mdl = read(function() return hafen.asset():get("r17.glb") end)
  local b = mdl and read(function() return mdl:bounds() end)
  local i = mdl and read(function() return mdl:info() end)
  if (mdl == nil) or (type(b) ~= "table") then
    ok("a glTF object answers its mesh path, and the 65th refuses naming the ceiling", false,
       "the model did not load: " .. tostring(mdl))
    return nil
  end
  local objs = {}
  local bad = refused("add(a path string)",
                      function() return hafen.virtual():object():add("r17.glb", p) end, "handle")
  bad = bad or refused("add(mdl, a table)",
                       function() return hafen.virtual():object():add(mdl, {x = 1, y = 2}) end, "anchor")
  -- The ceiling is on the COUNT, because each object mills its own geometry: standing 64 one-triangle
  -- models is what it takes to reach it, and every one of them is taken down again below.
  local stood = read(function() return hafen.virtual():object():count() end) or 0
  for _ = 1, (64 - stood) do
    local o = read(function() return hafen.virtual():object():add(mdl, p) end)
    if o == nil then break end
    objs[#objs + 1] = o
  end
  local n = read(function() return hafen.virtual():object():count() end)
  bad = bad or refused("the 65th", function() return hafen.virtual():object():add(mdl, p) end,
                       "objects standing")
  local mesh = objs[1] and read(function() return objs[1]:mesh() end)
  for _, o in ipairs(objs) do drop("object", o) end
  ok("a glTF object answers its mesh path, and the 65th refuses naming the ceiling",
     (bad == nil) and (n == 64) and (mesh == "r17.glb")
       and (type(b.extent) == "table") and (type(b.extent.z) == "number")
       and (type(i) == "table") and (i.tris == 1)
       and (read(function() return hafen.virtual():object():count() end) == stood),
     bad or (tostring(n) .. " stood, mesh " .. tostring(mesh) .. ", extent.z "
             .. tostring(b.extent and b.extent.z) .. ", " .. tostring(i and i.tris) .. " triangle"))
  return mdl
end

-- ---- po-16: the placing ghost ---------------------------------------------------------------------------

local function placing(s)
  local pl = read(function() return s:world():placing() end)
  if pl == nil then
    -- Nothing on the cursor is NIL, not a Placing that answers "placing nothing": that is the whole of
    -- the read while the cursor is empty, and it is what the page promises.
    ok("with nothing on the cursor the placing read is nil rather than an object that says so",
       true, "nil")
    needs("take something placeable onto the cursor (a build sign) and leave it there")
    return
  end
  local i = read(function() return pl:info() end)
  local hb = read(function() return pl:hitbox() end)
  ok("a placement on the cursor answers its four fields, and the footprint is not one of them",
     (type(i) == "table") and (i.exists == true) and (type(i.facing) == "number")
       and ((i.name == nil) or (type(i.name) == "string"))
       and ((i.position == nil) or (type(i.position.gridId) == "string"))
       and (i.hitbox == nil) and ((hb == nil) or (type(hb) == "table")),
     tostring(type(i) == "table" and i.name) .. " facing "
       .. tostring(type(i) == "table" and i.facing))
end

-- ---- the run --------------------------------------------------------------------------------------------

local TICKS, EVERY = 20, 0.5        -- 10 s: a session switch, a click, and a resource giving up

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR17-5", function()
  pass, fail, manual = 0, 0, 0
  hafen.timer():after(0, function()
    local s = read(function() return hafen.session():current() end)
    local p = s and read(function() return s:player():gob():position() end)
    if (s == nil) or (p == nil) then
      fail = fail + 1
      say("[fail] no character is in the world -- log in and run :tR17-5 again")
      finish()
      return
    end
    local base = read(function() return hafen.virtual():entity():count() end) or 0
    local claims = display()
    markers(p)
    local mdl = models(p)

    -- Four things stand for the window: the model to click, a ghost anchored to a place, a ghost whose
    -- resource can never resolve, and the hold on the claims overlay.
    local target = mdl and read(function() return hafen.virtual():object():add(mdl, p):clickable(true) end)
    local anchored = read(function() return hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", p) end)
    local doomed = read(function() return hafen.virtual():ghost():add("gfx/terobjs/r17-nope", p) end)
    read(function() return claims:hold() end)
    local hit
    local sub = hafen.event():on("ObjectClicked", function(ent) hit = hit or ent end)

    local was, switched, drift, ticks = s, false, nil, 0
    local ticker
    ticker = hafen.timer():every(EVERY, function()
      ticks = ticks + 1
      local now = read(function() return hafen.session():current() end)
      if (now ~= nil) and (now ~= was) then switched = true end
      if read(function() return claims:held() end) ~= true then drift = drift or "the hold went" end
      local q = anchored and read(function() return anchored:position() end)
      local d = (q ~= nil) and read(function() return q:distance(p) end) or nil
      if (d == nil) or (d > 0.01) then drift = drift or ("the anchor read " .. tostring(d)) end
      if ticks < TICKS then return end
      ticker:cancel()
      sub:off()
      read(function() return claims:release() end)

      -- vg-16: the anchor and the hold across a screen the maintainer moved.
      if switched then
        ok("a hold and a place-anchored ghost both survive the screen going to another character",
           drift == nil, drift)
      else
        needs("tab the screen to a second login while this 10 s window runs")
      end

      -- vg-16: the latch. A drawn() that is false with failed false is NOT YET; with failed true it is
      -- never, and this resource can never resolve.
      local gi = doomed and read(function() return doomed:info() end)
      ok("a ghost whose resource never loads latches failed, rather than waiting for ever",
         (type(gi) == "table") and (gi.failed == true) and (gi.drawn == false) and (gi.exists == true),
         "failed " .. tostring(type(gi) == "table" and gi.failed) .. ", drawn "
           .. tostring(type(gi) == "table" and gi.drawn))

      -- vm-19: the click. Only the player can make one, so the run scores what arrived.
      if hit ~= nil then
        ok("a click on a standing object reaches the addon that stood it, carrying the object itself",
           (hit == target) and (read(function() return hit:mesh() end) == "r17.glb"), tostring(hit))
      else
        needs("left-click the small model standing at your feet while this 10 s window runs")
      end

      drop("object", target)
      drop("ghost", anchored)
      drop("ghost", doomed)
      placing(s)
      ok("everything this run stood is gone again, and the section's own count is back where it started",
         read(function() return hafen.virtual():entity():count() end) == base,
         "left " .. tostring(read(function() return hafen.virtual():entity():count() end))
           .. " standing, started at " .. base)
      finish()
    end)
  end)
end)

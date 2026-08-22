-- 092 -- The address reaches the end. The whole feature's ten rows, under one command.
--
-- Every other feature of this sweep moved names, shapes and collections over the bridge. This one is the
-- ENGINE: the seams where a read or a write said "the character on screen" and should have said "the
-- character you named". Four of its rows are severe, and each of those four is the same sentence --
-- something addressed end to end that dropped its address at the last hop.
--
--   A-085  a Position's own verbs answer for the screen, and the addressed twins did not exist
--   A-086  gob:overlay() refused for every session but the drawn one         (ALREADY TRUE -- 079.3/080.1)
--   A-087  a visual write reached the copies that existed AT THE MOMENT OF THE WRITE
--   A-088  w:remember(name) filed a placement under the character ON SCREEN
--   A-089  eight bus keys carried the DRAWN character's payload, off adapters held per session
--   A-090  Layout.sweep() re-derived the drawn tree alone; capDirty was one flag for the client
--   A-091  opts:video() wrote the drawn session's gprefs, and there is one of those per tree
--   A-092  worldToScreen projected at the PLAYER's height, so the round trip did not close
--   A-093  screenToWorld took two loose numbers and lived on another section
--   A-094  a standing vr widget stays with the character that stood it     (the vr/widgets.md line stands)
--
-- Four of the ten need a SECOND CHARACTER logged in to be observed at all: what a program can read back in
-- one login is that the drawn session agrees with itself, which is exactly what was never broken. Those are
-- the manual lines, and each names the one gesture that shows it.
--
-- The summary comes LAST and comes ONCE: the A-092 round trip is a GPU readback, so its verdict arrives a
-- frame later and everything waits for it.

local pass, fail, manual = 0, 0, 0
local armed = false                                   -- a suite does not start itself: nothing until :t092

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

local finished = false
local function finish()
  if finished then return end
  finished = true
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- A group of refusals, scored as one line: each must raise AND say the thing that teaches the fix.
local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then n = n + 1
    else why = why or (label .. " -> " .. err) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
  return g
end

-- A group of reads, scored over what the run REACHED: a check whose subject is not up returns nil and is
-- not counted, so a run made before the world is in is honest rather than red.
local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what)
    if total == 0 then
      pass = pass + 1
      hafen.log():write("[pass] " .. what .. " (0/0 reached -- nothing of the kind was up)")
    else
      check(n == total, what .. " (" .. n .. "/" .. total .. " reached)", why or n)
    end
  end
  return g
end

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) <= tol) end

-- A-089, armed here and read by the maintainer: the event says WHICH character it is about, and before 092
-- it said the one on screen whichever character actually ate.
hafen.event():on("FepChanged", function(food, s)
  if not armed then return end
  hafen.log():write("[092] FepChanged fired for: " .. ((s and s:user()) or "<no session>"))
end)

local function run()
  armed = true
  local s = hafen.session():current()
  local w = s and s:world()
  local pl = s and s:player() and s:player():gob()
  local here = pl and pl:position()

  ---------------------------------------------------------------------------------------------------
  -- A-085: the three addressed twins. With one login they must AGREE with the Position's own verbs --
  -- the drawn session being one of the sessions they answer for is the whole point.
  ---------------------------------------------------------------------------------------------------
  section("A-085", function()
    local g = scored()
    g.want("components(p) is what p:x()/p:y() answer", function()
      if not here then return nil end
      local c = w:components(here)
      return (type(c) == "table") and near(c.x, here:x(), 0.001) and near(c.y, here:y(), 0.001)
    end)
    g.want("tileCoord(p) is what p:tileCoord() answers", function()
      if not here then return nil end
      local a, b = w:tileCoord(here), here:tileCoord()
      return (type(a) == "table") and (a.x == b.x) and (a.y == b.y)
    end)
    g.want("distance(p) with no other measures from THIS character", function()
      if not here then return nil end
      return near(w:distance(here), 0, 0.001)             -- `here` IS this character's own place
    end)
    g.want("distance(p, other) is between the two places", function()
      if not here then return nil end
      local q = here:offset(30, 40)
      return near(w:distance(here, q), 50, 0.001) and near(w:distance(here, q), here:distance(q), 0.001)
    end)
    g.done("the addressed twins answer in the session's own frame")
  end)

  section("A-085 refusals", function()
    local anyP = w:position(0, 0)                          -- a Position exists whether or not the world is
    local r = refusals()
    r.ask("components()", function() return w:components() end, "p is required")
    r.ask("components({x, y})", function() return w:components({x = 1, y = 2}) end, "p must be a Position")
    r.ask("tileCoord()", function() return w:tileCoord() end, "p is required")
    r.ask("distance(p, 3)", function() return w:distance(anyP, 3) end, "other must be a Position")
    r.done("a twin refuses what is not a place, and names the type")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-093: one conversion, two directions, one section, one shape.
  ---------------------------------------------------------------------------------------------------
  section("A-093", function()
    local g = scored()
    g.want("s:world():worldToScreen(p) answers {x, y}", function()
      if not here then return nil end
      local pt = w:worldToScreen(here)
      if pt == nil then return nil end                    -- no map view yet: nothing reached
      return (type(pt.x) == "number") and (type(pt.y) == "number")
    end)
    g.want("s:player() names where it went", function()
      local ok, err = pcall(function() return s:player():worldToScreen(here) end)
      return (not ok) and (tostring(err):find("s:world():worldToScreen", 1, true) ~= nil)
    end)
    g.done("worldToScreen lives on s:world(), beside its inverse")
  end)

  section("A-093 refusals", function()
    local pt = (here and w:worldToScreen(here)) or {x = 10, y = 20}
    local r = refusals()
    r.ask("screenToWorld(sx, sy, fn)", function() return w:screenToWorld(10, 20, function() end) end,
          "pt must be a {x = , y = } table")
    r.ask("screenToWorld(pt) with no fn", function() return w:screenToWorld(pt) end,
          "comes back a frame later")
    r.ask("screenToWorld(pt, 7)", function() return w:screenToWorld(pt, 7) end, "fn must be a function")
    r.done("screenToWorld takes the shape the other half hands back, and says it is asynchronous")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-090: the layout sweep reaches EVERY tree. The discriminating case is a window of the ADDON'S OWN
  -- LAYER: the old sweep walked the drawn session's tree, which the layer is not, so a rule installed
  -- after the window was built never reached it. The window is built FIRST, so only a sweep can move it.
  ---------------------------------------------------------------------------------------------------
  section("A-090", function()
    local win = hafen.ui():window():title("092sweep"):size(120, 60)
    local sheet = hafen.ui():sheet()
    sheet:rule("window[title=092sweep]"):position(311, 217)
    sheet:install()                                        -- ...and THIS is the sweep
    local at = win:position()
    local moved = (type(at) == "table") and (at.x == 311) and (at.y == 217)
    sheet:release()                                        -- ...and so is this, backwards
    local back = win:position()
    local restored = (type(back) == "table") and not ((back.x == 311) and (back.y == 217))
    win:destroy()
    check(moved and restored, "installing a sheet re-lays out a window of the ADDON LAYER's tree",
          moved and "release left it at the rule" or ("the sweep did not reach it: "
            .. tostring(at and at.x) .. "," .. tostring(at and at.y)))
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-088: a remembered placement round-trips through THE FILE OF THE TREE ITS WIDGET STANDS IN.
  --
  -- The half a program can drive is the CHARACTER scope, on a widget of this session's own tree: a level
  -- written by hand is what the capture records, so the record can be filled, dropped and put back inside
  -- one call. Everything the user's window had is given back -- the record is deleted and the level
  -- dropped -- so the suite mutates nothing. The ACCOUNT scope needs a DRAG, which is the manual below.
  ---------------------------------------------------------------------------------------------------
  section("A-088", function()
    local win = s and s:ui():matchAll("window")[1]
    if not win then
      pass = pass + 1
      hafen.log():write("[pass] a remembered window comes back out of its own character's file"
                          .. " (0/0 reached -- no client window is open)")
      return
    end
    win:remember("092-placement")
    win:position(263, 149)                                 -- a level of ours: what the capture reads
    s:store():flush()                                      -- ...into THIS character's folder
    win:position(nil)                                      -- the level goes: the window is back at stock
    win:remember("092-placement")                          -- ...and this puts back what the name holds
    local at = win:position()
    local ok = (type(at) == "table") and (at.x == 263) and (at.y == 149)
    win:remember(nil)                                      -- the record is DELETED, on disk in this call
    win:position(nil)                                      -- ...and the user's window is where it was
    check(ok, "a remembered window comes back out of its own character's file",
          (type(at) == "table") and (tostring(at.x) .. "," .. tostring(at.y)) or at)
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-086: gob:overlay() is reached through the session that HOLDS the object, not the one being drawn.
  -- 079.3/080.1 already made this true -- the audit's evidence was filed at 076 and is stale -- so this is
  -- the regression guard, and the two-session manual below is the whole of the observation.
  ---------------------------------------------------------------------------------------------------
  local gob = w and w:gob():nearest(nil)
  section("A-086", function()
    local g = scored()
    g.want("gob:overlay() answers through the session holding the object", function()
      if not gob then return nil end
      return type(gob:overlay():count()) == "number"
    end)
    g.want("gob:scale() reads back what was written", function()
      if not gob then return nil end
      gob:scale(1.4)
      local k = gob:scale()
      gob:scale(1)
      return near(k, 1.4, 0.001)
    end)
    g.done("a gob's visual surface is addressed at the object")
  end)

  ---------------------------------------------------------------------------------------------------
  -- The four that need a second character. Each names one gesture and what it must produce.
  ---------------------------------------------------------------------------------------------------
  if gob then gob:scale(2.5) end
  manualCheck("with a second character logged in, walk it INTO VIEW of the object 092 just scaled ("
                .. tostring((gob and gob:name()) or "nothing in range")
                .. ") -- the write happened before that character could see it",
              "it is drawn 2.5x there too. Before 092 the write reached the copies that existed when it was"
                .. " made, so a character arriving later drew it its own size. :reload puts it back")
  local grip = hafen.ui():window():title("092 drag me"):size(150, 44)
  grip:draggable(hafen.ui():label():text("drag this window, then look"):parent(grip):position(6, 6))
  grip:remember("092-account")
  manualCheck("drag the '092 drag me' window somewhere and look in savedata/",
              "'092-account' in account/092-the-address-reaches-the-end.all.layout.json and in NO"
                .. " <genus>_<char>/ folder -- that window stands in the addon's LAYER, which belongs to no"
                .. " character. The scored line above is the other scope: a window of this session's own"
                .. " tree, filed under this character. :reload takes the window away")
  manualCheck("eat something on the character that is NOT on screen",
              "a [092] FepChanged line naming THAT character's account, not the drawn one. Before 092 all"
                .. " eight of these bus keys carried the drawn character's payload")
  manualCheck("with two characters up, run :lua hafen.client():options():video():shadows(false) and tab"
                .. " between them",
              "both scenes lost their shadows. Before 092 the write moved the drawn tree alone, and which"
                .. " value survived the next client start was whichever tree published last")

  ---------------------------------------------------------------------------------------------------
  -- A-092 + the async half of A-093: THE ROUND TRIP CLOSES, and it closes over a height difference. The
  -- old projection filled z in from the player, so a point up a slope answered where it would be at the
  -- player's altitude and a raycast back down landed elsewhere. This finds the steepest ground within a
  -- few tiles, projects it, raycasts back, and asks whether it returned to where it went. Last, because
  -- the answer is a GPU readback and the summary waits for it.
  ---------------------------------------------------------------------------------------------------
  section("A-092", function()
    if not here then
      pass = pass + 1
      hafen.log():write("[pass] the world round trip closes (0/0 reached -- not in the world)")
      return finish()
    end
    local z0, best, dz = w:height(here), nil, 0
    for _, d in ipairs({{22, 0}, {-22, 0}, {0, 22}, {0, -22}, {44, 44}, {-44, 44}, {44, -44}, {-44, -44}}) do
      local q = here:offset(d[1], d[2])
      local z = w:height(q)
      if z0 and z and (math.abs(z - z0) > dz) then best, dz = q, math.abs(z - z0) end
    end
    local target = best or here
    local pt = w:worldToScreen(target)
    if pt == nil then
      pass = pass + 1
      hafen.log():write("[pass] the world round trip closes (0/0 reached -- nothing projected)")
      return finish()
    end
    w:screenToWorld(pt, function(back)
      local gap = back and w:distance(target, back)
      check((gap ~= nil) and (gap <= 11),                  -- one tile is 11 world units
            ("the round trip closes over a height difference of %.1f (gap %.2f)"):format(dz, gap or -1),
            (back == nil) and "fn(nil) -- that pixel hit no terrain" or gap)
      finish()
    end)
    hafen.timer():after(2, function()                      -- the readback failed, or there is no view
      if finished then return end
      check(false, "the round trip closes", "fn never fired within two seconds")
      finish()
    end)
  end)
end

hafen.slash():on("t092", run)                  -- the only way in: a suite does not start itself

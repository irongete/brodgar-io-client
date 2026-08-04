-- 037.1 — the restructure: LIVE is hafen.world, RECORDED is hafen.map. Self-checking suite; see
-- specs/addons/TESTING.md.
--
-- The task moves code and moves nothing else, so the only claims worth making are about WHERE things are
-- and whether they still answer. Two of them are cheap to state and easy to get wrong:
--
--   * the thirteen terrain/coordinate functions read MCache — the terrain streamed around the player — and
--     never the map database, so they belong to hafen.world. Each is checked HERE against a fact this suite
--     derives itself (the tile grid is 11x11 world units, a grid is 100x100 tiles), not by reading the
--     engine back to itself, because "it answers" and "it answers the same thing it used to" are different
--     claims and only the second one is the move being correct.
--   * hafen.map is now the map DATABASE, and the two surfaces that were always reading it — the markers and
--     the minimap icon registry — are its relations rather than namespaces beside it. hafen.markers and
--     hafen.radar are hard cuts and must read plain nil.
--
-- IT PUTS EVERYTHING BACK. The marker check writes to the user's real on-disk map DB and the icon check to
-- their real icon configuration, so each is undone in the same run and the restoration is itself asserted:
-- a suite that leaves a pin on your map is not read-only however green it prints.

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

local TILE, CMAPS = 11, 100         -- MCache.tilesz / MCache.cmaps, the two constants the checks derive from

-- The thirteen names 037.1 moved, in the order the docs list them.
local THIRTEEN = { "tile", "height", "grid", "gridPos", "fromGridPos", "worldToTile", "tileToWorld",
                   "tileToGrid", "screenToWorld", "snapPlace", "placeGrid", "snapAngle", "placeAngle" }

local function names(t)
  return (#t == 0) and "<none>" or table.concat(t, " ")
end

local function near(a, b, tol)
  return a and b and (math.abs(a - b) <= tol)
end

local function run()
  -- ---- the two hard cuts, and the empty half of the split -------------------------------------------
  check((hafen.markers == nil) and (hafen.radar == nil),
        "hafen.markers and hafen.radar are gone",
        ("markers=%s radar=%s"):format(tostring(hafen.markers), tostring(hafen.radar)))

  local left, missing = {}, {}
  for _, n in ipairs(THIRTEEN) do
    if hafen.map[n] ~= nil then left[#left + 1] = n end
    if type(hafen.world[n]) ~= "function" then missing[#missing + 1] = n end
  end
  check(#left == 0, "all thirteen terrain names read nil on hafen.map", names(left))
  check(#missing == 0, "all thirteen are functions on hafen.world", names(missing))
  check((type(hafen.map.markers) == "table") and (hafen.map.icons ~= nil),
        "hafen.map carries the two relations instead: markers and icons",
        ("markers=%s icons=%s"):format(type(hafen.map.markers), type(hafen.map.icons)))

  -- ---- the thirteen, at the player's own position ---------------------------------------------------
  local me = hafen.player() and hafen.player():gob()
  local p = me and me:pos()
  if not p then
    check(false, "the player's position is readable (every check below stands on it)", "no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local t = hafen.world.tile(p.x, p.y)
  check((t ~= nil) and (type(t.id) == "number"),
        "world.tile answers under the player" .. (t and (" (" .. tostring(t.name or t.id) .. ")") or ""),
        t and t.id)

  local h = hafen.world.height(p.x, p.y)
  check(type(h) == "number", "world.height answers under the player", h)

  local g = hafen.world.grid(p.x, p.y)
  check((g ~= nil) and (type(g.id) == "string") and (tonumber(g.id) ~= nil) and (g.gc ~= nil),
        "world.grid answers a decimal-string id and a grid coord",
        g and (tostring(g.id) .. " gc=" .. tostring(g.gc and g.gc.x)))

  local gp = hafen.world.gridPos()          -- no args = the player
  check((gp ~= nil) and (gp.gridId == (g and g.id))
          and (gp.x >= 0) and (gp.x < TILE * CMAPS) and (gp.y >= 0) and (gp.y < TILE * CMAPS),
        "world.gridPos anchors the player on that same grid, offset inside it",
        gp and ("%s @%.0f,%.0f"):format(gp.gridId, gp.x, gp.y))

  local back = gp and hafen.world.fromGridPos(gp)
  check(near(back and back.x, p.x, 0.001) and near(back and back.y, p.y, 0.001),
        "world.fromGridPos round-trips that anchor back to the player's world position",
        back and ("%.2f,%.2f vs %.2f,%.2f"):format(back.x, back.y, p.x, p.y))

  -- The three pure conversions are ONE claim: they compose into the tile and grid the player stands on.
  local tc = hafen.world.worldToTile(p.x, p.y)
  local ul = tc and hafen.world.tileToWorld(tc.x, tc.y)
  local gc = tc and hafen.world.tileToGrid(tc.x, tc.y)
  check(tc and ul and gc and g
          and (tc.x == math.floor(p.x / TILE)) and (tc.y == math.floor(p.y / TILE))
          and (ul.x == tc.x * TILE) and (ul.y == tc.y * TILE)
          and (gc.x == math.floor(tc.x / CMAPS)) and (gc.y == math.floor(tc.y / CMAPS))
          and (gc.x == g.gc.x) and (gc.y == g.gc.y),
        "world.worldToTile / tileToWorld / tileToGrid compose onto the tile and grid under the player",
        tc and ("tile %d,%d ul %s grid %d,%d vs %d,%d"):format(tc.x, tc.y, ul and ul.x or "nil",
                                                               gc.x, gc.y, g.gc.x, g.gc.y))

  local s = hafen.world.snapPlace(p.x, p.y)
  check(near(s and s.x, (math.floor(p.x / TILE) * TILE) + (TILE / 2), 0.001)
          and near(s and s.y, (math.floor(p.y / TILE) * TILE) + (TILE / 2), 0.001)
          and (type(hafen.world.placeGrid()) == "number"),
        "world.snapPlace snaps to the tile centre, and placeGrid reads the live setting",
        s and ("%.2f,%.2f grid=%s"):format(s.x, s.y, tostring(hafen.world.placeGrid())))

  local a = hafen.world.snapAngle(1.0)      -- 1.0 rad snaps to pi/4 on the coarse grid
  check(near(a, math.pi / 4, 0.0001) and (type(hafen.world.placeAngle()) == "number"),
        "world.snapAngle snaps to the 45 degree grid, and placeAngle reads the live setting",
        tostring(a) .. " angle=" .. tostring(hafen.world.placeAngle()))

  -- ---- hafen.map.markers: add, find, remove, and leave the DB as we found it ------------------------
  local NAME = "037.1 suite marker"
  local before = #hafen.map.markers.list()
  local ref = hafen.map.markers.add(NAME, p.x, p.y)
  local found
  for _, m in ipairs(hafen.map.markers.list()) do
    if m.name == NAME then found = m end
  end
  check((ref ~= nil) and (found ~= nil) and (type(found.seg) == "string") and (found.tc ~= nil),
        "map.markers.add returned a ref and list found the marker, anchored on a segment",
        (ref == nil) and "no ref" or (found and ("seg=" .. tostring(found.seg)) or "not in list"))

  local removed = ref and hafen.map.markers.remove(ref)
  local still = false
  for _, m in ipairs(hafen.map.markers.list()) do
    if m.name == NAME then still = true end
  end
  check((removed == true) and (not still) and (#hafen.map.markers.list() == before),
        "map.markers.remove took it back out, and the DB is exactly as it was found",
        ("removed=%s still=%s count=%d vs %d"):format(tostring(removed), tostring(still),
                                                      #hafen.map.markers.list(), before))

  -- ---- hafen.map.icons: the entity, and the user's configuration put back ---------------------------
  local cats = hafen.map.icons()
  local cat = cats[1]
  check((cat ~= nil) and (type(cat:res()) == "string") and (cat:res():find("/") ~= nil)
          and (type(cat:name()) == "string") and (cat:exists() == true)
          and (hafen.map.icons(cat:res()) == cat),
        ("map.icons() answered %d categories; the first is an entity and icons(res) is the SAME object")
          :format(#cats),
        (cat == nil) and "the registry is empty (is the HUD up?)" or tostring(cat:res()))

  refuses("map.icons refuses an index — a category's identity is its resource name",
          function() return hafen.map.icons(1) end, "no index")

  if cat then
    local wasShow, wasNotify = cat:show(), cat:notify()
    check((type(wasShow) == "boolean") and (type(wasNotify) == "boolean")
            and (cat:show(not wasShow) == cat) and (cat:show() == (not wasShow)),
          "a category's show flag reads a boolean, writes, and the write chains back self",
          tostring(wasShow) .. " -> " .. tostring(cat:show()))
    cat:notify(not wasNotify)
    check(cat:notify() == (not wasNotify),
          "a category's notify flag round-trips through the same arity",
          tostring(cat:notify()))
    cat:show(wasShow):notify(wasNotify)
    local info = cat:info()
    check((cat:show() == wasShow) and (cat:notify() == wasNotify)
            and (info ~= nil) and (info.show == wasShow) and (info.notify == wasNotify)
            and (info.res == cat:res()),
          "the user's icon configuration is back exactly as it was found, :info() agreeing",
          ("show=%s notify=%s"):format(tostring(cat:show()), tostring(cat:notify())))
  end

  -- ---- the one asynchronous name, staged: screenToWorld raycasts through hafen.world ----------------
  -- It reads the terrain point from the GPU, so the answer arrives a frame later. Fire it at the player's
  -- own pixel and wait BEFORE judging (035.2: a step that runs inline with what it checks reads the frame
  -- before). The summary closes the run from inside the callback's wake.
  local px = hafen.player():worldToScreen(p.x, p.y)
  local hit, fired = nil, false
  if px then
    hafen.world.screenToWorld(px.x, px.y, function(w) fired = true; hit = w end)
  end
  hafen.timer():after(0.6, function()
    check(fired and hit and near(hit.x, p.x, 2 * TILE) and near(hit.y, p.y, 2 * TILE),
          "world.screenToWorld called back with the ground under the player",
          (not px) and "the player is not on screen" or
            (fired and (hit and ("%.1f,%.1f vs %.1f,%.1f"):format(hit.x, hit.y, p.x, p.y) or "nil")
                   or "no callback"))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t037-1", run)   -- the only way in: a suite does not start itself

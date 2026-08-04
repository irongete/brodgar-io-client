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
--     the minimap icon registry — are its relations rather than namespaces beside it (today, its :marker()
--     and :icon() collections). hafen.markers and hafen.radar are hard cuts and must read plain nil.
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

-- The thirteen names 037.1 moved off hafen.map, in the order the docs listed them. Four of them have since
-- been spelled away entirely (a position is an object now, and the two placement settings had a second door
-- that also writes), so what lives on the world SECTION is the nine below -- but all thirteen must still read
-- nil on hafen.map, which is the claim this suite exists to make.
local THIRTEEN = { "tile", "height", "grid", "gridPos", "fromGridPos", "worldToTile", "tileToWorld",
                   "tileToGrid", "screenToWorld", "snapPlace", "placeGrid", "snapAngle", "placeAngle" }

-- Reading a name off a section is not always safe now: a name the grammar RETIRED throws from the field read
-- itself, which is the point of retiring it. So "not a live terrain verb here" is two outcomes, plain nil and
-- a refusal, and never a function.
local function absent(t, n)
  local ok, v = pcall(function() return t[n] end)
  return (not ok) or (v == nil)
end
local ONWORLD = { "tile", "height", "grid", "position", "tileToWorld", "tileToGrid", "screenToWorld",
                  "snapPlace", "snapAngle" }

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
    if not absent(hafen.map, n) then left[#left + 1] = n end
  end
  for _, n in ipairs(ONWORLD) do
    if type(hafen.world()[n]) ~= "function" then missing[#missing + 1] = n end
  end
  check(#left == 0, "none of the thirteen terrain names is a verb on hafen.map", names(left))
  check(#missing == 0, "the nine survivors are verbs on the hafen.world() section object", names(missing))
  check((hafen.map():marker() ~= nil) and (hafen.map():icon() ~= nil)
          and (hafen.map():marker() == hafen.map():marker()),
        "hafen.map carries the two relations instead, as collections: :marker() and :icon()",
        tostring(hafen.map():marker()) .. " / " .. tostring(hafen.map():icon()))

  -- ---- the thirteen, at the player's own position ---------------------------------------------------
  local me = hafen.player() and hafen.player():gob()
  local p = me and me:position()
  if not p then
    check(false, "the player's position is readable (every check below stands on it)", "no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local px, py = p:x(), p:y()

  local t = hafen.world():tile(p)
  check((t ~= nil) and (type(t.id) == "number"),
        "world:tile answers under the player" .. (t and (" (" .. tostring(t.name or t.id) .. ")") or ""),
        t and t.id)

  local h = hafen.world():height(p)
  check(type(h) == "number", "world:height answers under the player", h)

  local g = hafen.world():grid():at(p)
  local gid = g and g:id()
  local gsc = g and g:segmentCoord()
  check((g ~= nil) and (type(gid) == "string") and (tonumber(gid) ~= nil) and (g:live() == true),
        "world:grid():at answers the Grid under the player, live, with a decimal-string id",
        g and (tostring(gid) .. " live=" .. tostring(g:live())))

  local gp = p:info()                       -- the durable form of the player's own position
  check((gp ~= nil) and (gp.gridId == gid)
          and (gp.x >= 0) and (gp.x < TILE * CMAPS) and (gp.y >= 0) and (gp.y < TILE * CMAPS),
        "p:info() anchors the player on that same grid, offset inside it",
        gp and ("%s @%.0f,%.0f"):format(gp.gridId, gp.x, gp.y))

  local back = gp and hafen.world():position(gp)
  check(near(back and back:x(), px, 0.001) and near(back and back:y(), py, 0.001),
        "world:position(saved) round-trips that anchor back to the player's world position",
        back and ("%.2f,%.2f vs %.2f,%.2f"):format(back:x(), back:y(), px, py))

  -- The three pure conversions are ONE claim: they compose into the tile and grid the player stands on.
  local tc = p:tileCoord()
  local ul = tc and hafen.world():tileToWorld(tc.x, tc.y)
  local gc = tc and hafen.world():tileToGrid(tc.x, tc.y)
  check(tc and ul and gc and g
          and (tc.x == math.floor(px / TILE)) and (tc.y == math.floor(py / TILE))
          and (ul.x == tc.x * TILE) and (ul.y == tc.y * TILE)
          and (gc.x == math.floor(tc.x / CMAPS)) and (gc.y == math.floor(tc.y / CMAPS)),
        "p:tileCoord / world:tileToWorld / :tileToGrid compose onto the tile and grid under the player",
        tc and ("tile %d,%d ul %s grid %d,%d"):format(tc.x, tc.y, ul and ul.x or "nil", gc.x, gc.y))

  local iface = hafen.client:options():interface()
  local s = hafen.world():snapPlace(p)
  check(near(s and s:x(), (math.floor(px / TILE) * TILE) + (TILE / 2), 0.001)
          and near(s and s:y(), (math.floor(py / TILE) * TILE) + (TILE / 2), 0.001)
          and (type(iface:posGran()) == "number"),
        "world:snapPlace snaps to the tile centre, and the interface option reads the live setting",
        s and ("%.2f,%.2f gran=%s"):format(s:x(), s:y(), tostring(iface:posGran())))

  local a = hafen.world():snapAngle(1.0)      -- 1.0 rad snaps to pi/4 on the coarse grid
  check(near(a, math.pi / 4, 0.0001) and (type(iface:angGran()) == "number"),
        "world:snapAngle snaps to the 45 degree grid, and the interface option reads the live setting",
        tostring(a) .. " angGran=" .. tostring(iface:angGran()))

  -- ---- the marker collection: add, find, remove, and leave the DB as we found it --------------------
  local NAME = "037.1 suite marker"
  local markers = hafen.map():marker()
  local before = markers:count()
  local ref = markers:add(NAME, p)
  local found = markers:find(NAME)
  local finfo = found and found:info()
  check((ref ~= nil) and (found == ref) and (finfo ~= nil) and (type(finfo.seg) == "string")
          and (finfo.tc ~= nil),
        "marker:add handed back the pin, :find(name) found that same object, anchored on a segment",
        (ref == nil) and "no marker" or (found and ("seg=" .. tostring(finfo and finfo.seg))
                                               or "not in the collection"))

  markers:remove(ref)
  check((markers:find(NAME) == nil) and (ref:exists() == false) and (markers:count() == before),
        "marker:remove took it back out, and the DB is exactly as it was found",
        ("still=%s exists=%s count=%d vs %d"):format(tostring(markers:find(NAME)),
                                                     tostring(ref:exists()), markers:count(), before))

  -- ---- the icon collection: the entity, and the user's configuration put back -----------------------
  local icons = hafen.map():icon()
  local cats = icons:list()
  local cat = cats[1]
  check((cat ~= nil) and (type(cat:res()) == "string") and (cat:res():find("/") ~= nil)
          and (type(cat:name()) == "string") and (cat:exists() == true)
          and (icons:get(cat:res()) == cat),
        ("icon:list() answered %d categories; the first is an entity and :get(res) is the SAME object")
          :format(#cats),
        (cat == nil) and "the registry is empty (is the HUD up?)" or tostring(cat:res()))

  refuses("icon:get refuses an index — a category's identity is its resource name",
          function() return icons:get(1) end, "no index")

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
  local sc = hafen.player():worldToScreen(px, py)
  local hit, fired = nil, false
  if sc then
    hafen.world():screenToWorld(sc.x, sc.y, function(w) fired = true; hit = w end)
  end
  hafen.timer():after(0.6, function()
    check(fired and hit and near(hit:x(), px, 2 * TILE) and near(hit:y(), py, 2 * TILE),
          "world:screenToWorld called back with a Position on the ground under the player",
          (not sc) and "the player is not on screen" or
            (fired and (hit and ("%.1f,%.1f vs %.1f,%.1f"):format(hit:x(), hit:y(), px, py) or "nil")
                   or "no callback"))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t037-1", run)   -- the only way in: a suite does not start itself

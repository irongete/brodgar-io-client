-- 037.2 — segments and grids, and the anchor bridge both ways. Self-checking suite; see
-- specs/addons/TESTING.md.
--
-- The task opens the map DATABASE — the map the player has explored, on disk — and the claim worth proving
-- is not that it answers but that it lines up with the world you are standing in:
--
--   * THE CROSS-CHECK NO OTHER TASK CAN MAKE. hafen.world():tile(p) reads the terrain streamed around the
--     player; grid:tile(c) reads what the client WROTE DOWN about that same ground. They come from two
--     different subsystems and they must name the same tileset for the tile under the player's feet.
--   * THE ANCHOR BRIDGE, BOTH WAYS. Live -> recorded: p:info() hands out a grid id the SERVER
--     minted, and hafen.map():grid():get(id) finds that grid in the database. Recorded -> live:
--     grid:position() says where that grid's corner is in this session, so the player's own within-grid
--     offset lands back on the player. Two independent coordinate paths (MCache's live grids and the map
--     file's sessloc arithmetic) agreeing to the millimetre.
--   * marker:position() IS WHY. A marker's own coordinates are a segment id this client invented with
--     rnd.nextLong() plus a tile coord inside it, and a segment merge rewrites BOTH in place — so they
--     cannot be saved or sent: they would not go nil, they would point at the wrong place. A Position can
--     be, because it holds the server's grid id. This suite stores exactly that, nothing in segment space.
--   * THE LOAD MODEL. The database is on disk: a read kicks the load and answers nil, the next one answers.
--     The proof is that a sweep of the neighbourhood answers FEWER grids than the same sweep does a second
--     later — which is also the proof that the first sweep did not block waiting for them.
--
-- IT PUTS EVERYTHING BACK: the one pin it drops in the real map DB is removed in the same run and the
-- restoration is itself asserted.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local TILE, CMAPS = 11, 100         -- MCache.tilesz / MCache.cmaps, the two constants every check derives from
local NAME = "037.2 suite pin"
local SWEEP = 4                     -- half-width, in grids, of the neighbourhood the load-model check walks

local function near(a, b, tol)
  return a and b and (math.abs(a - b) <= tol)
end

-- The grids of a (2*SWEEP+1)^2 square around one segment coord that answer RIGHT NOW, as a set of ids. The
-- ones still coming off the disk are simply absent — and present next time, which is the whole check.
local function sweep(seg, sc)
  local seen, n = {}, 0
  for _, g in ipairs(seg:grid():list{ x = sc.x - SWEEP, y = sc.y - SWEEP,
                                      w = (SWEEP * 2) + 1, h = (SWEEP * 2) + 1 }) do
    seen[g:id()] = true
    n = n + 1
  end
  return seen, n
end

-- ---- the relog half: run AFTER a logout/login, on the anchor the main run stored ---------------------
local function relogCheck()
  local a = hafen.store():get("anchor")
  if not (a and a.gridId) then
    check(false, "an anchor was stored by an earlier ':t037-2' run", "nothing in the store -- run ':t037-2' first")
    return summary()
  end
  local g = hafen.map():grid():get(a.gridId)
  local w = hafen.world():position(a)
  check((g ~= nil) and (g:id() == a.gridId) and (g:segment() ~= nil),
        "the anchor stored before the relog still resolves in the map database, on the same grid id",
        (g == nil) and ("no grid for " .. tostring(a.gridId)) or g:id())
  check((w ~= nil) and (type(w:x()) == "number"),
        "...and back to a world position in THIS session, where the raw coordinate could not have",
        w and ("%.1f,%.1f"):format(w:x(), w:y()))
  summary()
end

-- ---- the main run -----------------------------------------------------------------------------------
local function run(args)
  if args and (args[1] == "relog") then return relogCheck() end
  pass, fail, manual = 0, 0, 0

  local me = hafen.player() and hafen.player():gob()
  local p = me and me:position()
  local seg = hafen.map():segment():current()
  if (not p) or (not seg) then
    check(false, "the player and the map database are both up (every check below stands on them)",
          (not p) and "no player gob" or "no segment yet -- the map DB streams in a beat after enter-world")
    return summary()
  end

  -- 1. the segment: an exact decimal string, and the same object however you ask for it
  local sid = seg:id()
  check((type(sid) == "string") and (sid:match("^%-?%d+$") ~= nil)
          and (hafen.map():segment():get(sid) == seg)
          and (hafen.map():segment():current() == seg),
        "the player's segment answers an exact decimal-string id, and :get(id) is the SAME object",
        sid)

  -- 2. a marker of our own, and the id it publishes is that same segment's
  local m = hafen.map():marker():add(NAME, p)
  local inSeg
  for _, x in ipairs(seg:markers(NAME)) do inSeg = x end
  check((m ~= nil) and (inSeg == m) and (m:segment() == seg) and (m:info().seg == sid),
        "a marker added here is found through seg:markers() and publishes that very segment id",
        (m == nil) and "add answered nil" or ((inSeg ~= m) and "not in seg:markers()" or m:info().seg))

  -- 3. the bridge, live -> recorded: the server's grid id finds the grid in the database
  local gp = p:info()                                  -- the player's own position, in its durable form
  local g = gp and hafen.map():grid():get(gp.gridId)
  check((g ~= nil) and (g:id() == gp.gridId) and (g:segment() == seg),
        "the player's LIVE grid id resolves into the recorded map, in that same segment",
        (gp == nil) and "not durable here" or ((g == nil) and ("the DB carries no grid " .. gp.gridId) or g:id()))

  -- 4. ...and back: the grid's own corner plus the player's within-grid offset IS the player
  local ul = g and g:position()
  check(near(ul and (ul:x() + gp.x), p:x(), 0.001) and near(ul and (ul:y() + gp.y), p:y(), 0.001),
        "grid:position() puts that grid where the player's own offset says it is (recorded -> live)",
        ul and ("%.1f,%.1f + %.1f,%.1f vs %.1f,%.1f"):format(ul:x(), ul:y(), gp.x, gp.y, p:x(), p:y()))

  -- 5. THE CROSS-CHECK: the recorded ground and the live ground name the same tile
  local c = gp and { x = math.floor(gp.x / TILE), y = math.floor(gp.y / TILE) }
  local rec = (g and c) and g:tile(c)
  local live = hafen.world():tile(p)
  check((rec ~= nil) and (live ~= nil) and (rec.name ~= nil) and (rec.name == live.name)
          and (type(g:modified()) == "number"),
        "the RECORDED grid and the LIVE terrain name the same tile under the player",
        ("recorded=%s live=%s"):format(rec and tostring(rec.name) or "nil (not loaded yet?)",
                                       live and tostring(live.name) or "nil"))

  -- 6. marker:position(): the place that may be saved or sent, and it IS the old anchor
  local mpos = m and m:position()
  local a = mpos and mpos:info()
  check((a ~= nil) and (a.gridId == (gp and gp.gridId))
          and near(a.x, (math.floor(gp.x / TILE) * TILE) + (TILE / 2), 0.001),
        "marker:position():info() names the SERVER grid id and the tile centre inside it",
        a and ("%s @%.1f,%.1f"):format(a.gridId, a.x, a.y))

  -- 7. and it round-trips: back through the DB onto the very tile the marker reports
  local tc = m and m:segmentTile()
  local ag = a and hafen.map():grid():get(a.gridId)
  local asc = ag and ag:segmentCoord()
  check((asc ~= nil) and (tc ~= nil)
          and ((asc.x * CMAPS) + math.floor(a.x / TILE) == tc.x)
          and ((asc.y * CMAPS) + math.floor(a.y / TILE) == tc.y),
        "the anchor round-trips through the database back to the tile the marker reports",
        (asc and tc) and ("%d,%d vs %d,%d"):format((asc.x * CMAPS) + math.floor(a.x / TILE),
                                                   (asc.y * CMAPS) + math.floor(a.y / TILE), tc.x, tc.y)
                      or "no grid for the anchor")

  -- 8. ...and through the LIVE half too, onto the marker's own world position
  local back = a and hafen.world():position(a)
  check(near(back and back:x(), mpos and mpos:x(), 0.001)
          and near(back and back:y(), mpos and mpos:y(), 0.001),
        "world:position(anchor) on that same anchor lands on the marker's world position",
        (back and mpos) and ("%.1f,%.1f vs %.1f,%.1f"):format(back:x(), back:y(), mpos:x(), mpos:y())
                        or "nil")

  -- 9./10. the two refusals that guard the two coordinate spaces
  refuses("a 64-bit id is a decimal STRING, and a number is refused rather than rounded",
          function() return hafen.map():segment():get(1122) end, "decimal STRING")
  refuses("a within-grid tile coord is refused when it is plainly a segment one",
          function() return g:tile{ x = CMAPS, y = 0 } end, "WITHIN-grid tile coord")

  -- 11. the store, for the relog check below
  local kept = hafen.store():get("anchor")   -- the LIVE persisted table: write THROUGH it, never replace it
  kept.gridId, kept.x, kept.y = a and a.gridId, a and a.x, a and a.y
  hafen.store():flush()

  -- 12./13. the load model, staged: the first sweep answers only what is already in memory and does NOT
  -- wait for the rest; a second sweep a moment later answers more. (035.2: wait FIRST, then judge.)
  local sc = g and g:segmentCoord()
  local seen1, n1 = sweep(seg, sc or { x = 0, y = 0 })
  hafen.timer():after(1.5, function()
    local seen2, n2 = sweep(seg, sc or { x = 0, y = 0 })
    local lost = 0
    for id in pairs(seen1) do if not seen2[id] then lost = lost + 1 end end
    check((n2 > n1) and (lost == 0),
          "a grid not yet loaded answered nil and a later call answered it -- so the first sweep never blocked",
          ("%d grids at once, %d a moment later, %d lost"):format(n1, n2, lost))
    check((sc ~= nil) and (seg:grid():get(sc) == g) and (hafen.world():grid():at(p) == g),
          "seg:grid():get(sc) and hafen.world():grid():at(p) hand back that same Grid -- one entity, two doors",
          sc and ("%d,%d"):format(sc.x, sc.y))

    -- 14. put the DB back exactly as it was found
    if m then hafen.map():marker():remove(m) end
    local still = false
    for _, x in ipairs(seg:markers(NAME)) do still = true end
    check((not still) and (m:exists() == false),
          "the pin is out of the map DB again, and the marker says so itself",
          ("still=%s exists=%s"):format(tostring(still), tostring(m:exists())))

    manualCheck("log out, log back in at the same spot, then run ':t037-2 relog'",
                "2 pass -- the anchor stored before the relog resolves to the same grid id and back to a"
                .. " world position, which the raw coordinate could not do")
    summary()
  end)
end

hafen.slash():register("t037-2", run)   -- the only way in: a suite does not start itself

-- 037.5 — the close: the docs' claims about the anchor, as assertions; and what the example addon costs.
-- Self-checking suite; see specs/addons/TESTING.md.
--
-- The feature's load-bearing rule is a sentence in the documentation — "save the anchor, never the segment
-- coordinate" — and a sentence is exactly the kind of claim that rots. So the first half of this suite is
-- map.md's own prose turned into checks: the anchor is the door from the live world into the database, it
-- round-trips through hafen.world and back, a 64-bit id is a decimal STRING and a number is refused, a
-- segment is interned on the id it publishes, and the two names the feature cut read plain nil.
--
-- The second half is the example addon's bill. `atlas` puts a live map on the screen out of the database
-- alone, and it does it by handing grid:image(lvl) to the stylesheet — a grid drawing is an ordinary image
-- handle, so the ENGINE paints it and no Lua runs at the draw. That is a categorical zero, and a zero is only
-- worth asserting in a scene where the counter CAN read something else: this suite therefore paints the very
-- same picture from Lua in a window of its own and reads BOTH rows out of one sample.
--
-- READ-ONLY apart from one write it undoes in the same run: it drops a marker into the real map database to
-- prove marker:position() round-trips, then removes it and asserts the removal. It declares no permissions,
-- destroys every window it opens and drops its sheet.

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

local CMAPS = 100                       -- MCache.cmaps: a grid is 100x100 tiles, a drawing 100x100 pixels
local TILE = 11                         -- MCache.tilesz: one tile is 11x11 world units
local ID    = "037-map-database.5"
local ATLAS = "atlas"                   -- the example addon this feature ships, and its row in addons()
local PANEL = "window[title=Atlas]"     -- ...and how to tell that its panel is actually on screen
local MINE  = "037.5 the same map, drawn from Lua"

local wins = {}

local function cleanup()
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

local function summary()
  cleanup()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Ask until it lands (the load model from the caller's side: the frame IS the retry loop) or give up.
local function until_(get, tries, step, done)
  local v = get()
  if (v ~= nil) or (tries <= 0) then return done(v) end
  hafen.timer():after(step, function() until_(get, tries - 1, step, done) end)
end

local function rowOf(id)
  for _, r in ipairs(hafen.client():profiling():addons()) do
    if r.id == id then return r end
  end
end

local costRound

-- ---- the docs' anchor claims, as assertions -----------------------------------------------------------
local function run()
  pass, fail, manual = 0, 0, 0          -- a re-run reports its own counts, not the last one's
  cleanup()

  local me = hafen.player() and hafen.player():gob()
  local pp = me and me:position()
  local gp = pp and pp:info()
  local grid = gp and hafen.map():grid():get(gp.gridId)
  local seg = grid and grid:segment()
  if not (gp and grid and seg) then
    check(false, "the player's own grid is in the map database (every check below stands on it)",
          (gp == nil) and "no anchor -- no player, or the map has not streamed in yet"
                       or "the grid the player stands on is not recorded yet")
    return summary()
  end

  -- 1. "A grid id is the only thing the two halves share", which is why they hand back ONE entity: the
  --    anchor's gridId is that grid's identity, and the live door reaches the very same object.
  eq("the anchor's gridId is the identity of the grid it names in the database", grid:id(), gp.gridId)
  check((hafen.world():grid():at(pp) == grid) and (grid:live() == true) and (grid:exists() == true),
        "the live door and the recorded door hand back the SAME Grid -- live and written down at once",
        ("live=%s exists=%s"):format(tostring(grid:live()), tostring(grid:exists())))

  -- 2. "A stored anchor rebuilds into a Position" -- and that Position hands back the SAME anchor.
  local w = hafen.world():position(gp)
  local back = w and w:info()
  check(back and (back.gridId == gp.gridId) and (back.x == gp.x) and (back.y == gp.y),
        "an anchor rebuilds into a Position this session and back to the SAME anchor -- exactly, not nearly",
        (back == nil) and "the rebuilt position is not durable here"
                       or ("%s %.3f,%.3f"):format(back.gridId, back.x, back.y))

  -- 3. "A 64-bit id is a decimal string, and a number is refused" -- the one failure a silent lookup would hide.
  refuses("a 64-bit id passed as a Lua number is refused, saying why",
          function() return hafen.map():grid():get(1234) end, "decimal STRING")

  -- 4. A segment id is a decimal string, and the segment it names is interned on it (D-094: the engine's own
  --    published id, because a Segment lives in a cache that evicts).
  local sid = seg:id()
  check((type(sid) == "string") and (sid:match("^%-?%d+$") ~= nil)
          and (hafen.map():segment():get(sid) == seg),
        "a segment id is an exact decimal string, and it is what the segment interns on",
        tostring(sid) .. " / " .. tostring(hafen.map():segment():get(sid) == seg))

  -- 5. The cut the page opens with: a marker lives in the map database, and the engine has no radar.
  check((hafen.markers == nil) and (hafen.radar == nil) and (hafen.map.tile == nil),
        "hafen.markers, hafen.radar and the live terrain reads on hafen.map are all plain nil",
        tostring(hafen.markers) .. " / " .. tostring(hafen.radar) .. " / " .. tostring(hafen.map.tile))

  -- 6./7. "marker:position() is how a marker leaves this client." The claim is that its durable form lands
  --    back on the tile the marker itself reports -- so the suite drops a pin in the REAL database, reads it
  --    back and removes it again in the same run, asserting the removal (this is the one write it makes).
  local mk = pp and hafen.map():marker():add("037.5 anchor probe", pp)
  if mk then
    mk:color(200, 80, 80)
    local a = mk:position() and mk:position():info()
    local ag = a and hafen.map():grid():get(a.gridId)
    -- The durable x,y are WITHIN-grid world units, so the tile is plain arithmetic on them: p:tileCoord()
    -- answers for a place in the session's own space, which this need not be.
    local at = ag and { x = math.floor(a.x / TILE), y = math.floor(a.y / TILE) }
    local tc = mk:segmentTile()
    local asc = ag and ag:segmentCoord()
    check(at and asc and (((asc.x * CMAPS) + at.x) == tc.x) and (((asc.y * CMAPS) + at.y) == tc.y),
          "marker:position() round-trips onto the very tile the marker reports -- a pin can be saved or sent",
          (a == nil) and "the marker's position answered nil (its grid was still loading)"
                      or ("grid %s tile %d,%d vs tc %d,%d"):format(a.gridId, at and at.x or -1,
                                                                   at and at.y or -1, tc.x, tc.y))
    check((mk:color() ~= nil) and (mk:color().r == 200) and (mk:onMap() == false),
          "the pin was created bare and the chained setter took: it reads back the colour it was given",
          ("r=%s onMap=%s"):format(tostring(mk:color() and mk:color().r), tostring(mk:onMap())))
    hafen.map():marker():remove(mk)
    check(mk:exists() == false,
          "...and the pin this suite dropped is gone again -- the database is left as it was found",
          tostring(mk:exists()))
  else
    manualCheck("stand in the world (the map database must be ready) and run ':t037-5' again",
                "two more [pass]: a marker dropped at your feet has a Position that lands back on the tile"
                .. " the marker reports, and is then removed again")
  end

  -- 8. "Segments, grids, masks, markers and icon categories are interned objects ... and any of them works as
  --    a table key." The identity claim the whole page's stash-a-handle advice rests on.
  local key = {}
  key[grid] = "yes"
  check((hafen.map():grid():get(gp.gridId) == grid) and (key[hafen.map():grid():get(gp.gridId)] == "yes")
          and (grid:segment() == seg),
        "a grid and its segment are interned handles -- the same id is the same object, and it keys a table",
        tostring(hafen.map():grid():get(gp.gridId) == grid)
          .. " / " .. tostring(key[hafen.map():grid():get(gp.gridId)]))

  costRound(grid)
end

-- ---- the example addon's bill --------------------------------------------------------------------------
-- `atlas` paints a live recorded map through the stylesheet, so the engine does the painting. The claim is
-- categorical -- 0 draw and 0 widget callbacks of its own while a map is on screen -- and it is only a
-- measurement if the same sample can read a number that is not zero, so this suite draws the identical
-- picture from Lua beside it.
costRound = function(grid)
  local up = hafen.ui():find(PANEL)
  if not (up and up:exists()) then
    manualCheck("run ':atlas' (the example addon this feature ships) and then ':t037-5' again",
                "two more [pass] lines: with its panel on screen `atlas` runs 0 draw and 0 widget callbacks"
                .. " of its own, while this suite drawing the same picture from Lua runs one a frame")
    return summary()
  end
  if not hafen.client():options():client():profiling() then
    manualCheck("tick Options > Client > \"Enable profiling\" and run ':t037-5' again",
                "the same two [pass] lines -- the per-addon counters are armed only")
    return summary()
  end

  until_(function() return grid:image(0) end, 40, 0.1, function(img)
    if not img then
      check(false, "the recorded ground under the player renders, so both panels draw the same picture",
            "still nil after 4 s")
      return summary()
    end
    wins[#wins + 1] = hafen.ui():window()
      :title(MINE)
      :size(CMAPS, CMAPS)
      :position(260, 80)
      :onDraw(function(g) g:image(img, 0, 0) end)
    hafen.timer():after(0.5, function()
      local a, m = rowOf(ATLAS), rowOf(ID)
      local painted = a and (a.calls.draw + a.calls.widgets)
      check(painted == 0,
            "the `atlas` panel is on screen and `atlas` ran 0 draw and 0 widget callbacks -- a grid drawing is"
            .. " an image handle, so the stylesheet hands it to the engine and no Lua is at the draw",
            (a == nil) and "no addons() row for `atlas`" or painted)
      check(m and (m.calls.draw > 0),
            "...while this suite painting the SAME picture from Lua costs a callback every frame -- the zero"
            .. " above is a measurement, not a blind spot",
            m and m.calls.draw or "no addons() row for this suite")
      manualCheck("with ':atlas' open, walk a hundred tiles, then try ':atlas zoom 2' and ':atlas pins'",
                  "the panel is the corner minimap for the ground you are on and follows you into the next"
                  .. " grid; 'zoom 2' keeps the same 100x100 square and puts 4x4 grids of explored ground in"
                  .. " it; 'pins' draws your map markers on top -- and makes this addon's own row in"
                  .. " ':profiler' start reading one draw callback a frame, which is the zero above falsified")
      summary()
    end)
  end)
end

hafen.slash():register("t037-5", run)   -- the only way in: a suite does not start itself

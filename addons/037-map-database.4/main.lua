-- 037.4 — the imagery: a recorded grid becomes a PICTURE. Self-checking suite; see specs/addons/TESTING.md.
--
-- The map database's last surface is the one the player actually recognises — the minimap drawing — and the
-- claims worth proving are about WHEN it arrives and WHAT it costs, not about how it looks:
--
--   * IT IS AN IMAGE HANDLE, not a new kind of thing. grid:image(lvl) hands back exactly what
--     hafen.asset("icon.png") hands back, so everything that already draws an image draws a map: g:image, a
--     world sprite, and a stylesheet's bg = { image = … }. That last one is why the cost round below can read
--     ZERO — the engine paints it, no Lua runs at the draw.
--   * THE LOAD MODEL, ONE LAST TIME. Rendering a grid is tens of thousands of pixels out of the tileset
--     resources; it runs on Defer, exactly where the client runs its own. So the first ask KICKS the render
--     and answers nil, and a later one answers — never a block, never a throw.
--   * A LEVEL IS A SCALE, NOT A SIZE. Every level is the same 100x100 pixels: level 0 is one pixel per tile,
--     level 1 one per four, level 2 one per sixteen. Which is also why two neighbouring grids under one
--     level-1 zoom grid are ONE picture and hand back ONE handle.
--   * AND IT IS OWNED. :dispose() frees the texture and drops the cache entry, so the next read renders anew;
--     the disposed handle stays inert rather than becoming an error.
--
-- What a program cannot judge is whether the picture is the RIGHT ground, so that is parked for a human:
-- ':t037-4 show' puts levels 0, 1 and 2 on screen and leaves them there, ':t037-4 drop' takes them away.
--
-- READ-ONLY: declares no permissions and writes nothing persistent. It reads the profiling switch and never
-- writes it, destroys every window it opens (the parked ones on ':t037-4 drop') and drops its sheet.

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

local CMAPS = 100          -- MCache.cmaps: a grid is 100x100 tiles, and every rendering is 100x100 pixels

-- Where the player is, in the durable {gridId, x, y} form: the one thing the live world and the recorded
-- map share, and the door into the database.
local function playerAnchor()
  local me = hafen.player() and hafen.player():gob()
  local p = me and me:position()
  return p and p:info()
end
local SWEEP = 2            -- half-width, in grids, of the neighbourhood searched for a recorded overlay
local ID = "037-map-database.4"
local TITLE_BG, TITLE_DRAW = "037.4 painted by the sheet", "037.4 painted by Lua"
local SHOW = { "037.4 level 0", "037.4 level 1", "037.4 level 2" }

local wins = {}

local function win(w)
  wins[#wins + 1] = w
  return w
end

local function cleanup()
  hafen.ui.skin(nil)
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function sz(img)
  if not img then return "nil" end
  local s = img:size()
  return ("%dx%d"):format(s.w, s.h)
end

-- Ask until it lands (the load model, from the caller's side: the frame IS the retry loop) or give up.
local function until_(get, tries, step, done)
  local v = get()
  if (v ~= nil) or (tries <= 0) then return done(v) end
  hafen.timer():after(step, function() until_(get, tries - 1, step, done) end)
end

local function ownRow()
  for _, r in ipairs(hafen.client:profiling():addons()) do
    if r.id == ID then return r end
  end
end

local afterLevels, costRound, finish, showRound, dropRound   -- forward: the staged rounds

-- ---- the run ----------------------------------------------------------------------------------------
local function run(args)
  local arg = args and args[1]
  if arg == "show" then return showRound() end
  if arg == "drop" then return dropRound() end
  pass, fail, manual = 0, 0, 0     -- a re-run reports its own counts, not the last one's
  cleanup()

  local gp = playerAnchor()
  local seg = hafen.map():segment():current()
  local grid = gp and hafen.map():grid():get(gp.gridId)
  if (not gp) or (not seg) or (not grid) then
    check(false, "the player's own grid is in the map database (every check below stands on it)",
          (gp == nil) and "no anchor -- no player, or the map has not streamed in yet"
                       or "the grid the player stands on is not recorded yet")
    return summary()
  end

  -- 1. THE LOAD MODEL. Drop whatever an earlier run of this command left cached, then ask cold: the answer
  --    is nil because the render was kicked, not waited for. (Disposing IS the first half of check 11.)
  local stale = grid:image(0)
  if stale then stale:dispose() end
  check(grid:image(0) == nil,
        "a cold grid:image(0) kicks the render and answers nil -- the frame is never blocked on a bitmap",
        tostring(grid:image(0)))

  -- 2./3./4. the refusals: a level is a whole number in range, and an overlay is named by a tag STRING
  refuses("a negative zoom level is refused, naming the range",
          function() return grid:image(-1) end, "zoom level")
  refuses("a fractional zoom level is refused too -- a level is a whole number",
          function() return grid:image(1.5) end, "zoom level")
  refuses("grid:overlayImage takes a tag, not a number",
          function() return grid:overlayImage(3) end, "overlay tag string")

  -- 5. an unknown tag is plain nil here, exactly as grid:overlay():get(tag) is: the tags are the server's
  check(grid:overlayImage("no-such-overlay-tag") == nil,
        "an overlay tag the grid does not carry is nil, never an error -- the tags belong to the resources",
        tostring(grid:overlayImage("no-such-overlay-tag")))

  until_(function() return grid:image(0) end, 40, 0.1, function(img)
    -- 6. ...and a later ask answers. Two calls, two different answers, no block in between.
    check(img ~= nil, "a later ask answers the rendered handle -- the load model's other half",
          "still nil after 4 s")
    if not img then return summary() end

    -- 7. it is an ORDINARY image handle, and one pixel per tile
    check((img:type() == "image") and (img:size().w == CMAPS) and (img:size().h == CMAPS),
          "the drawing is an ordinary image handle, 100x100 -- one pixel per tile of the grid",
          img:type() .. " " .. sz(img))

    -- 8. interned per (grid, level): the same ask is the same object, so a panel re-asking every frame
    --    allocates nothing and renders nothing
    check((grid:image(0) == img) and (hafen.map():grid():get(gp.gridId):image(0) == img),
          "the same (grid, level) hands back the SAME handle -- a panel may ask every frame",
          tostring(grid:image(0)))

    grid:image(1)                         -- kick the zoom level; it composites four grids and takes longer
    until_(function() return grid:image(1) end, 60, 0.1, function(zimg)
      -- 9. A LEVEL IS A SCALE, NOT A SIZE.
      check((zimg ~= nil) and (zimg:size().w == CMAPS) and (zimg:size().h == CMAPS) and (zimg ~= img),
            "level 1 is a different picture at the SAME 100x100 -- a level changes the scale, not the size",
            sz(zimg))

      grid:image(2)                       -- ...and the same again one level up: sixteen grids into one square
      until_(function() return grid:image(2) end, 100, 0.1, function(z2)
        -- 10. a third level says the rule is a rule and not a coincidence of the first pair
        check((z2 ~= nil) and (z2:size().w == CMAPS) and (z2:size().h == CMAPS)
                and (z2 ~= zimg) and (z2 ~= img),
              "level 2 is a third picture at that same 100x100 -- 400x400 tiles of ground, the map zoomed out",
              sz(z2))
        afterLevels(grid, seg, img, zimg)
      end)
    end)
  end)
end

-- ---- the neighbour, the dispose, the overlay ---------------------------------------------------------
afterLevels = function(grid, seg, img, zimg)
  -- 11. two grids under ONE level-1 zoom grid are one picture, so they share the handle. (Parked as a
  --     [manual] when the ground next door was never recorded — a pass there would say nothing.)
  local sc = grid:segmentCoord()
  local nx = ((sc.x % 2) == 0) and (sc.x + 1) or (sc.x - 1)
  local nb = seg:grid():get{ x = nx, y = sc.y }
  if nb and zimg then
    check(nb:image(1) == zimg,
          "the neighbouring grid under that same level-1 zoom grid IS the same handle, not a second render",
          tostring(nb:image(1)))
  else
    manualCheck("walk one grid west or east (100 tiles) so the neighbouring ground is recorded, then run"
                .. " ':t037-4' again",
                "the grid beside the player was not in the database this run, so the shared-zoom check had"
                .. " nothing to pair with; next to recorded ground it runs and passes -- both grids under"
                .. " one level-1 zoom grid hand back ONE handle")
  end

  -- 12. OWNED: dispose frees the texture and drops the cache entry, so the next read renders anew -- and the
  --     disposed handle answers rather than throwing.
  img:dispose()
  check((img:info().disposed == true) and (img:size().w == CMAPS) and (grid:image(0) == nil),
        "a disposed drawing is inert -- it still answers its own size, and the next read renders anew",
        tostring(img:info().disposed) .. " / " .. tostring(grid:image(0)))

  -- 13. the recorded overlay masks, drawn. Staged like 037.3's: an overlay resource may still be loading.
  local found, ftag
  for _, cand in ipairs(seg:grid():list{ x = sc.x - SWEEP, y = sc.y - SWEEP,
                                         w = (SWEEP * 2) + 1, h = (SWEEP * 2) + 1 }) do
    local ts = cand:overlay():list()
    if #ts > 0 then found, ftag = cand, ts[1]:tag() break end
  end
  if found then
    found:overlayImage(ftag)
    until_(function() return found:overlayImage(ftag) end, 40, 0.1, function(oimg)
      check((oimg ~= nil) and (oimg:size().w == CMAPS) and (oimg:size().h == CMAPS)
              and (found:overlayImage(ftag) == oimg),
            ("grid:overlayImage(\"%s\") renders that recorded mask in the overlay's own colour, interned"
             .. " like the ground under it"):format(tostring(ftag)), sz(oimg))
      costRound(zimg)
    end)
  else
    manualCheck("stand next to a personal claim or inside a village and run ':t037-4' again",
                "no grid within " .. SWEEP .. " of the player recorded any overlay, so grid:overlayImage"
                .. " had nothing to draw (its nil and its refusal are checked above); beside a claim it"
                .. " renders that mask in the overlay's own colour and passes")
    costRound(zimg)
  end
end

-- ---- the cost: who paints it ------------------------------------------------------------------------
-- The claim is categorical, and it needs a scene in which the counter CAN read non-zero (036.4's lesson): so
-- the same picture is put on screen twice, once through the stylesheet and once through a Lua draw callback,
-- and the two are read one after the other. Zero alone would only mean "this suite drew nothing".
costRound = function(img)
  if not (img and hafen.client:options():client():profiling()) then
    if img then
      manualCheck("tick Options > Client > \"Enable profiling\" and run ':t037-4' again",
                  "two more [pass] lines: a map drawn by the stylesheet runs 0 draw callbacks of ours, and the"
                  .. " very same picture drawn by Lua runs one per frame -- which is what makes the zero mean"
                  .. " something")
    end
    return finish()
  end
  win(hafen.ui.window{ title = TITLE_BG, size = { CMAPS, CMAPS }, pos = { 40, 40 } })
  hafen.ui.skin{ ["window[title=" .. TITLE_BG .. "]"] = { bg = { image = img } } }
  hafen.timer():after(0.5, function()
    local row = ownRow()
    local paints = row and (row.calls.draw + row.calls.widgets)
    check(paints == 0,
          "a recorded map painted every frame by the stylesheet ran 0 draw/widget callbacks of ours -- a grid"
          .. " image is a handle, so the engine paints it and no Lua is at the draw",
          (row == nil) and "no addons() row for this suite" or paints)
    win(hafen.ui.window{ title = TITLE_DRAW, size = { CMAPS, CMAPS }, pos = { 40 + CMAPS + 20, 40 },
                         onDraw = function(g) g:image(img, 0, 0) end })
    hafen.timer():after(0.5, function()
      local r2 = ownRow()
      local drew = r2 and r2.calls.draw
      check((drew ~= nil) and (drew > 0),
            "...and the same picture drawn from Lua costs a callback every frame -- the zero above is a"
            .. " measurement, not a blind spot", tostring(drew))
      finish()
    end)
  end)
end

finish = function()
  cleanup()
  manualCheck("run ':t037-4 show', compare the three panels it leaves on screen with the corner minimap,"
              .. " then run ':t037-4 drop'",
              "three 100x100 panels labelled level 0 / 1 / 2. Level 0 is the recorded ground under the player,"
              .. " tile transitions and all -- the same square the corner minimap draws for that spot. Level 1"
              .. " is the same place at half the scale and level 2 at a quarter, each covering four times the"
              .. " ground in the same square. ':t037-4 drop' removes all three and prints 1 pass")
  summary()
end

-- ---- the parked round: what only an eye can judge ----------------------------------------------------
showRound = function()
  pass, fail, manual = 0, 0, 0
  cleanup()
  local gp = playerAnchor()
  local grid = gp and hafen.map():grid():get(gp.gridId)
  if not grid then
    check(false, "the player's own grid is in the map database", "no anchor / not recorded yet")
    return summary()
  end
  -- kick all three and wait for the slowest (level 2 composites sixteen grids): the frame is the retry loop
  until_(function()
    local all = true
    for lvl = 0, 2 do
      if grid:image(lvl) == nil then all = false end
    end
    return all or nil
  end, 100, 0.1, function()
    local up = 0
    for lvl = 0, 2 do
      local im = grid:image(lvl)
      if im then
        up = up + 1
        win(hafen.ui.window{ title = SHOW[lvl + 1], size = { CMAPS, CMAPS },
                             pos = { 40 + (lvl * (CMAPS + 24)), 40 },
                             onDraw = function(g) g:image(im, 0, 0) end })
      end
    end
    check(up == 3, "the three levels are on screen and stay there until ':t037-4 drop'",
          up .. " of 3 rendered in time -- run ':t037-4 show' again")
    manualCheck("look at the three panels beside the corner minimap, then run ':t037-4 drop'",
                "level 0 is the ground under the player exactly as the corner minimap draws it; level 1 and"
                .. " level 2 are the same place at half and a quarter of the scale, each square covering four"
                .. " times the ground")
    summary()
  end)
end

dropRound = function()
  pass, fail, manual = 0, 0, 0
  local n = #wins
  cleanup()
  check(n > 0, "the parked panels are gone and the suite left nothing on screen (" .. n .. " closed)",
        "nothing was parked -- run ':t037-4 show' first")
  summary()
end

hafen.slash():register("t037-4", run)   -- the only way in: a suite does not start itself

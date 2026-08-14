-- 067.4 -- the pages say which space every coordinate is in. Self-checking suite.
--
-- A documentation task is proved by RUNNING what the pages claim. Two claims are executable:
--
--   1. api/ui/mouse.md's grab example feeds ev:x(), ev:y() straight into screenToWorld and gets the
--      ground under the cursor. Run verbatim, and scored by projecting that Position BACK through
--      worldToScreen: if either door spoke the map view's device pixels the mark would land the scale
--      factor -- a third of the screen at 1.5 -- from the cursor, not within a tile of it.
--   2. every hafen.* name these pages write resolves to a callable.
--
-- It also duplicates the round trip an earlier suite asserts: a suite stands alone, so nothing here
-- rests on another command having been run.

local pass, fail, manual = 0, 0, 0

local TILE         = 11     -- world units per tile
local ROUND_WINDOW = 6      -- seconds the round-trip raycast is given
local DRAG_WINDOW  = 60     -- seconds the maintainer has to take the drag
local FINAL_WINDOW = 3      -- seconds the raycast at the release point is given
local INTERVAL     = 0.25   -- seconds between retries

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

local function dist(a, b)
  return math.sqrt(((a.x - b.x) ^ 2) + ((a.y - b.y) ^ 2))
end

-- ---- the names the pages write ------------------------------------------------------------------
-- Indexed rather than called: "resolves to a callable" is the claim, and a page that names a verb the
-- engine does not have is wrong whether or not this run would have reached the call.

local NAMES = {
  { "hafen.player():worldToScreen", function() return hafen.player().worldToScreen end },
  { "hafen.world():screenToWorld",  function() return hafen.world().screenToWorld end },
  { "hafen.world():snapPlace",      function() return hafen.world().snapPlace end },
  { "hafen.ui():at",                function() return hafen.ui().at end },
  { "hafen.ui():scale",             function() return hafen.ui().scale end },
  { "hafen.ui():mouse():x",         function() return hafen.ui():mouse().x end },
  { "hafen.ui():mouse():y",         function() return hafen.ui():mouse().y end },
  { "hafen.ui():mouse():grab",      function() return hafen.ui():mouse().grab end },
  { "hafen.log():write",            function() return hafen.log().write end },
}

local function checkNames()
  local bad = {}
  for _, n in ipairs(NAMES) do
    local ok, v = pcall(n[2])
    if (not ok) or (type(v) ~= "function") then
      bad[#bad + 1] = n[1] .. " (" .. (ok and type(v) or "raised") .. ")"
    end
  end
  check(#bad == 0, "every hafen.* name these pages write resolves to a callable",
        table.concat(bad, "; "))
end

-- ---- the read-out ---------------------------------------------------------------------------------
-- The eye half of the same claim: a cyan cross at the pixel the grab reports, and a red box at where
-- the Position it produced projects back to. One space means one mark.

local readout, lastPos

local function showReadout()
  if readout and readout:exists() then readout:destroy() end
  readout = hafen.ui():overlay()
  readout:onDraw(function(g, w, h)
    local m = hafen.ui():mouse()
    local cx, cy = m:x(), m:y()
    if not cx then return end
    g:color(60, 200, 255, 220)
    g:line(cx - 16, cy, cx + 16, cy)
    g:line(cx, cy - 16, cx, cy + 16)
    local sc = lastPos and hafen.player():worldToScreen(lastPos)
    if sc then
      g:color(255, 80, 80, 230)
      g:rect(sc.x - 7, sc.y - 7, 14, 14)
      local t = lastPos:tileCoord()
      if t then g:text(("tile %d, %d"):format(t.x, t.y), sc.x + 12, sc.y + 10) end
    end
    g:color()
  end)
end

local function hideReadout()
  if readout and readout:exists() then readout:destroy() end
  readout = nil
end

-- ---- a tile, measured on the screen the client is drawing right now -------------------------------
-- The tolerance has to follow the zoom, so it is read off the projection itself rather than guessed:
-- the longer of one tile east and one tile north, as those two land in pixels at this camera.

local function tilePixels(p)
  local here = hafen.player():worldToScreen(p)
  if not here then return nil end
  local best
  for _, d in ipairs({ { TILE, 0 }, { 0, TILE } }) do
    local q = p:offset(d[1], d[2])
    local sc = q and hafen.player():worldToScreen(q)
    if sc then
      local n = dist(sc, here)
      if (best == nil) or (n > best) then best = n end
    end
  end
  return best
end

-- worldToScreen fills the third axis in from the PLAYER's height, so a cursor up a slope projects back
-- to where that spot would be at your own altitude. Said in the got: line rather than left to be guessed.
local function slopeNote(p)
  local me = hafen.player():gob()
  local home = me and me:position()
  local h0, h1 = home and hafen.world():height(home), hafen.world():height(p)
  if (h0 == nil) or (h1 == nil) or (math.abs(h1 - h0) <= 1) then return "" end
  return (", and that ground is %.1f units off your own height -- worldToScreen projects at YOURS")
         :format(h1 - h0)
end

-- ---- the round trip, duplicated so this suite stands alone ---------------------------------------

local function roundTrip(done)
  local me = hafen.player():gob()
  local home = me and me:position()
  if not home then
    check(false, "round trip: screenToWorld(worldToScreen(p)) comes back within a tile",
          "hafen.player():gob() is nil -- log in first")
    return done(false)
  end
  local elapsed, back, why, timer = 0, nil, nil, nil
  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    if not back then
      local sc = hafen.player():worldToScreen(home)
      if sc then
        hafen.world():screenToWorld(sc.x, sc.y, function(q)
          if q then back = back or q else why = "the pixel hit no terrain (sky, or off-map)" end
        end)
      else
        why = "worldToScreen answered nil for the player's own position"
      end
    end
    if back or (elapsed >= ROUND_WINDOW) then
      timer:cancel()
      local d = back and back:distance(home)
      check((d ~= nil) and (d <= TILE),
            "round trip: screenToWorld(worldToScreen(p)) comes back within a tile",
            d and ("%.1f units, a tile is %d"):format(d, TILE)
              or ("nothing answered in %ds -- %s"):format(ROUND_WINDOW, why or "no callback"))
      done(true)
    end
  end)
end

-- ---- the grab example, verbatim -------------------------------------------------------------------

local running = false

local function summary()
  hideReadout()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  running = false
end

local function score(ux, uy, samples, mismatch)
  check((samples > 0) and (mismatch == nil),
        "the grab reports the pointer in the mouse's own pair, to within a pixel",
        (samples == 0) and "no Move reached the suite" or mismatch)

  local elapsed, got, timer = 0, nil, nil
  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    if not got then
      hafen.world():screenToWorld(ux, uy, function(p) got = got or p end)
    end
    if got or (elapsed >= FINAL_WINDOW) then
      timer:cancel()
      lastPos = got or lastPos
      check(got ~= nil, "the grab example answers a Position for the pixel it was released on",
            ("nothing in %ds -- the release pixel hit no terrain, or you are not in the world")
            :format(FINAL_WINDOW))
      if got then
        local sc = hafen.player():worldToScreen(got)
        local tol = tilePixels(got)
        local d = sc and dist(sc, { x = ux, y = uy })
        check((d ~= nil) and (tol ~= nil) and (d <= tol),
              "that Position projects back within a tile of the cursor pixel",
              (d and tol) and (("%.1f px away, a tile is %.1f px here"):format(d, tol) .. slopeNote(got))
                or "worldToScreen answered nil for it")
      else
        check(false, "that Position projects back within a tile of the cursor pixel",
              "there was no Position to project")
      end
      summary()
    end
  end)
end

local function takeGrab()
  local samples, mismatch, inflight, done = 0, nil, false, false
  lastPos = nil
  showReadout()

  local g = hafen.ui():mouse():grab()

  local function finish(ux, uy)
    if done then return end
    done = true
    score(ux, uy, samples, mismatch)
  end

  g:on("Move", function(ev)
    samples = samples + 1
    -- A pixel of slack, and no more: the two round the same device pair through their own conversion,
    -- while a SPACE mismatch would be off by the whole interface scale -- tens of pixels, not one.
    local m = hafen.ui():mouse()
    local mx, my = m:x(), m:y()
    if (mx == nil) or (math.abs(ev:x() - mx) > 1) or (math.abs(ev:y() - my) > 1) then
      mismatch = mismatch or ("grab says %d, %d and the mouse says %s, %s")
                             :format(ev:x(), ev:y(), tostring(mx), tostring(my))
    end
    -- The page's own line, unchanged: the pair the grab reports goes into the door as it comes.
    if not inflight then
      inflight = true
      hafen.world():screenToWorld(ev:x(), ev:y(), function(p)
        inflight = false
        if p then lastPos = p end
      end)
    end
  end)

  g:on("Up", function(ev) finish(ev:x(), ev:y()) end)

  hafen.timer():after(DRAG_WINDOW, function()
    if done then return end
    g:release()
    local m = hafen.ui():mouse()
    finish(m:x() or 0, m:y() or 0)
  end)
end

local function run()
  if running then
    hafen.log():write("[skip] a run is already in flight -- wait for its [summary]")
    return
  end
  running, pass, fail, manual = true, 0, 0, 0

  checkNames()

  roundTrip(function(inWorld)
    if not inWorld then return summary() end
    manualCheck("the pointer is captured for the next " .. DRAG_WINDOW
                .. "s: drag across FLAT ground near your character, then click to release",
                "the red box stays centred on the cyan cross the whole way, with no offset")
    manualCheck("set \"Interface scale (requires restart)\" to 1.5, restart, run :t067-4 again",
                "the same verdicts as at 1.0, and the box still on the cross")
    takeGrab()
  end)
end

hafen.slash():register("t067-4", run)   -- the only way in: a suite does not start itself

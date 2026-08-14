-- 067.1 -- the projection verbs answer and take root design pixels. Self-checking suite.
--
-- The proof that needs no eye is the ROUND TRIP: worldToScreen answers a screen point, screenToWorld
-- takes one back, and if both name the same space the Position comes home. Feed either of them the
-- wrong space and the trip lands the scale factor away -- which on an unscaled client is exactly
-- nothing, so the [manual] line below repeats the run at 1.5 where a mismatch is visible.
--
-- screenToWorld is ASYNCHRONOUS (a GPU readback, answered a frame later), so each point is retried on
-- a timer for a bounded window and the run scores what it reached.

local pass, fail, manual = 0, 0, 0

local TILE     = 11      -- world units per tile (MCache.tilesz)
local OFFSET   = 2       -- tiles away the two extra points sit -- close enough to stay on screen
local WINDOW   = 8       -- seconds the raycasts are given, in total
local INTERVAL = 0.25    -- seconds between retries
local DOT_LIFE = 30      -- seconds the eye-check dot stays up

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

-- The eye half: a dot painted over the HUD at the LIVE projected player point, from a painter that
-- speaks root design pixels and does no arithmetic of its own. If worldToScreen still answered the
-- map view's device pixels the dot would sit up and left of the character, further at every scale.
local dot

local function showDot()
  if dot and dot:exists() then dot:destroy() end
  dot = hafen.ui():overlay()
  dot:onDraw(function(g, w, h)
    local me = hafen.player():gob()
    local p = me and me:position()
    local sc = p and hafen.player():worldToScreen(p)
    if not sc then return end
    g:color(255, 60, 60, 230)
    g:prect(sc.x, sc.y, 5, 1)
    g:color()
  end)
  hafen.timer():after(DOT_LIFE, function()
    if dot and dot:exists() then dot:destroy() end
  end)
end

-- Why a round trip can miss through no fault of the seam: screenxf(Coord2d) projects at the PLAYER's
-- own z (MapView.getcc), not at the terrain height under the point, so on a slope the ray comes back
-- down somewhere else along itself. Said in the got: line rather than left to be guessed at.
local function slopeNote(home, there)
  local h0, h1 = hafen.world():height(home), hafen.world():height(there)
  if (h0 == nil) or (h1 == nil) or (math.abs(h1 - h0) <= 1) then
    return ""
  end
  return (", and the ground there is %.1f units off the player's -- worldToScreen projects at the"
          .. " PLAYER's height"):format(h1 - h0)
end

local function roundTrip(home, targets, done)
  local elapsed, timer = 0, nil

  local function report()
    for _, tg in ipairs(targets) do
      local what = "round trip comes back within a tile -- " .. tg.name
      if tg.back then
        local d = tg.back:distance(tg.p)
        check((d ~= nil) and (d <= TILE), what,
              d and (("%.1f units, a tile is %d"):format(d, TILE) .. slopeNote(home, tg.p))
                or "the Position that came back has no distance")
      else
        check(false, what, ("nothing answered in %ds -- %s"):format(WINDOW, tg.why or "no callback"))
      end
    end
    done()
  end

  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    local left = 0
    for _, tg in ipairs(targets) do
      if not tg.back then
        left = left + 1
        -- Re-projected every retry, so a camera that pans mid-run raycasts the CURRENT pixel while
        -- the Position it is scored against stays the one this run started with.
        local sc = hafen.player():worldToScreen(tg.p)
        if sc then
          hafen.world():screenToWorld(sc.x, sc.y, function(q)
            if q then tg.back = tg.back or q else tg.why = "the pixel hit no terrain (sky, or off-map)" end
          end)
        else
          tg.why = "worldToScreen answered nil for it"
        end
      end
    end
    if (left == 0) or (elapsed >= WINDOW) then
      timer:cancel()
      report()
    end
  end)
end

-- The run is asynchronous, so a second :t067-1 on top of a first would interleave two sets of
-- raycasts into one tally. The counters reset per run and a run in flight refuses a second.
local running = false

local function run()
  if running then
    hafen.log():write("[skip] a run is already in flight -- wait for its [summary]")
    return
  end
  running, pass, fail, manual = true, 0, 0, 0

  local me = hafen.player():gob()
  local home = me and me:position()
  if not home then
    check(false, "the character is in the world", "hafen.player():gob() is nil -- log in first")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    running = false
    return
  end

  -- A screen point is not a place, and the door that takes places says so.
  refuses("worldToScreen refuses a plain {x=, y=} table, naming Position",
          function() hafen.player():worldToScreen({x = home:x(), y = home:y()}) end,
          "must be a Position")

  local sc = hafen.player():worldToScreen(home)
  check(sc ~= nil, "worldToScreen answers for the player's own position", sc)

  -- The box the answer must lie in is the ROOT's, in the root's own unit -- which is the whole claim:
  -- a device-pixel answer on a scaled client runs off the bottom right of it.
  local sz = hafen.ui():root():size()
  check(sc and (sc.x >= 0) and (sc.y >= 0) and (sc.x <= sz.x) and (sc.y <= sz.y),
        ("the projected player point is inside the root box (%d x %d)"):format(sz.x, sz.y),
        sc and ("%.1f, %.1f"):format(sc.x, sc.y) or "nil")

  showDot()

  local targets = {
    { name = "the player's own position", p = home },
    { name = OFFSET .. " tiles east",     p = home:offset(OFFSET * TILE, 0) },
    { name = OFFSET .. " tiles north",    p = home:offset(0, -OFFSET * TILE) },
  }
  for _, tg in ipairs(targets) do
    if not tg.p then tg.p = home end   -- offset answers nil only for a place with no coordinate
  end

  roundTrip(home, targets, function()
    manualCheck("watch the red dot for the next " .. DOT_LIFE .. " seconds, and walk a few steps",
                "it sits on your character's feet and tracks them, with no offset up or left")
    manualCheck("set \"Interface scale (requires restart)\" to 1.5, restart, run :t067-1 again",
                "the same verdicts as at 1.0, and the dot still on the feet")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    running = false
  end)
end

hafen.slash():register("t067-1", run)   -- the only way in: a suite does not start itself

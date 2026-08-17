-- 070.2 -- the pick pass reads the session cache; it never builds it. Self-checking suite.
--
-- The layer caches where every session stands. That cache is built by walking a widget tree the frame
-- is mutating, so the frame's own thread builds it and everything else reads what the frame published.
-- The pick pass is the everything else: a click on the ground is resolved in a GPU readback callback,
-- on a thread of the graphics environment's own, and the client counts every time such a caller arrived
-- before the frame had published anything. That count staying at zero IS the fix, and it is readable
-- with the profiler off, which is the only state it is worth anything in.
--
-- hafen.world():screenToWorld(sx, sy, fn) walks exactly that route, so firing it at every tick across a
-- spread of pixels drives the pick hundreds of times while the frame rebuilds the cache underneath it.
-- The run therefore needs the world to HOLD STILL: the same pixel must name the same patch of ground
-- from the first sample to the last, or the coherence check is measuring the camera instead.

local SETUP    = 45     -- seconds the profiler switch is waited for
local INTERVAL = 0.5    -- seconds between polls while waiting
local WINDOW   = 6      -- seconds the pick is hammered
local GRACE    = 1.0    -- seconds after the last shot, for answers still in flight
local TILE     = 11     -- world units across one tile: the coherence bound
-- Every tick, said as an interval rather than as 0: a repeating timer fires at most once per tick, so
-- anything under one frame is one shot per frame -- and 0 is the encoding of a timer that does not repeat.
local TICK     = 0.001
local MINSHOTS = 100    -- below this the run has not driven the route hard enough to have proved anything

local pass, fail, manual = 0, 0, 0
local running = false

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

local function note(s)
  hafen.log():write("[note] " .. s)
end

local function summary()
  running = false
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function counters()
  return hafen.client():profiling():session()
end

-- Wait for something the maintainer has to set, for a bounded time, and carry on either way.
local function waitFor(ready, limit, done)
  if ready() then return done(true) end
  local left, t = limit, nil
  t = hafen.timer():every(INTERVAL, function()
    left = left - INTERVAL
    if ready() then
      t:cancel()
      done(true)
    elseif left <= 0 then
      t:cancel()
      done(false)
    end
  end)
end

-- ---- the shape of the group, with nothing armed --------------------------------------------------

local function shape(armedOff)
  local s = counters()
  local n = (type(s) == "table")
        and (type(s.groundAnswered) == "number") and (s.groundAnswered >= 0)
        and (type(s.groundMissed) == "number") and (s.groundMissed >= 0)
        and (type(s.placedRebuiltOffTick) == "number") and (s.placedRebuiltOffTick >= 0)
  check(n and armedOff,
        ("the group reads three counters with the profiler off (%s answered, %s missed, %s early)")
        :format(tostring(s.groundAnswered), tostring(s.groundMissed), tostring(s.placedRebuiltOffTick)),
        ("profilerOff=%s shape=%s"):format(tostring(armedOff), tostring(n)))

  -- Pull-only is a measurement, not a claim: the neighbouring frame surface has nothing to read in the
  -- very same state the three numbers above came out of.
  check(next(hafen.client():profiling():frame()) == nil,
        "frame() is empty in the same state, so the group answers where the frame surface cannot",
        "frame() answered")

  refuses("session(1) is refused as read-only",
          function() return hafen.client():profiling():session(1) end, "read-only")
  return n
end

-- ---- hammering the pick --------------------------------------------------------------------------

local function spread()
  local sz = hafen.ui():root():size()
  local px = {}
  -- Below the middle of the view: the upper band is sky under most camera pitches, and a pixel that
  -- never hits ground proves nothing either way.
  for _, fx in ipairs({0.35, 0.50, 0.65}) do
    for _, fy in ipairs({0.45, 0.55, 0.65}) do
      px[#px + 1] = {x = math.floor(sz.x * fx), y = math.floor(sz.y * fy),
                     hits = 0, misses = 0, dev = 0}
    end
  end
  return px
end

local function record(p, pos)
  if pos == nil then
    p.misses = p.misses + 1
    return
  end
  p.hits = p.hits + 1
  local x, y = pos:x(), pos:y()
  if p.fx == nil then
    p.fx, p.fy = x, y
  else
    local d = math.sqrt((x - p.fx) ^ 2 + (y - p.fy) ^ 2)
    if d > p.dev then p.dev = d end
  end
end

local function verdict(px, shots, base)
  local ground, hits, misses, worst = 0, 0, 0, 0
  for _, p in ipairs(px) do
    if p.hits > 0 then
      ground = ground + 1
      hits, misses = hits + p.hits, misses + p.misses
      if p.dev > worst then worst = p.dev end
    end
  end

  -- The floor is the check, not decoration: every assertion below is worth exactly as many times as the
  -- route was actually walked, and one sample of a race proves nothing at all.
  check((shots >= MINSHOTS) and (ground > 0) and (hits > 0),
        ("the pick was driven hard over drawn ground: %d shots, %d answers, %d of %d pixels on terrain")
        :format(shots, hits, ground, #px),
        ("%d shots (wanted %d+), %d answers, %d pixels on terrain -- too few to have put the readback on"
         .. " the route while the frame rebuilt underneath it"):format(shots, MINSHOTS, hits, ground))

  if ground > 0 then
    check(misses == 0,
          ("every answer for a pixel over drawn ground was a Position (%d of %d)"):format(hits, hits + misses),
          ("%d of %d came back nil"):format(misses, hits + misses))
    check(worst <= TILE,
          ("no pixel's answers disagree by more than a tile (worst %.2f of %d units)"):format(worst, TILE),
          ("%.2f units apart -- an answer lost its session's translation, or the view moved"):format(worst))
  end

  local now = counters().placedRebuiltOffTick
  check(now == 0,
        ("the pick never reached the cache builder: %d, +%d over the run"):format(now, now - base),
        ("%d, +%d over the run -- the readback thread found nothing published and answered in the drawn"
         .. " session's frame"):format(now, now - base))
  summary()
end

local function hammer()
  local px = spread()
  local base = counters().placedRebuiltOffTick
  local i, shots, t = 0, 0, nil

  -- One pixel per tick, round-robin: every shot is a full click-map pass, and the point is to be ON
  -- that route while the frame rebuilds, not to queue as many readbacks at once as the client will take.
  t = hafen.timer():every(TICK, function()
    i = (i % #px) + 1
    local p = px[i]
    shots = shots + 1
    hafen.world():screenToWorld(p.x, p.y, function(pos) record(p, pos) end)
  end)

  hafen.timer():after(WINDOW, function()
    t:cancel()
    -- The answers come back a frame or more after the shot, so the last of them land after the timer
    -- has stopped. Counting them is the difference between measuring the route and measuring the wait.
    hafen.timer():after(GRACE, function() verdict(px, shots, base) end)
  end)
end

-- ---- the run -------------------------------------------------------------------------------------

local function run()
  if running then
    hafen.log():write("[skip] a run is already in flight -- wait for its [summary]")
    return
  end
  running, pass, fail, manual = true, 0, 0, 0

  -- Said BEFORE anything is asserted, and then waited for: an instruction printed beside the check it
  -- gates is an instruction that arrives too late to follow.
  note(("set up now, within %ds: profiling OFF in Options, in the world, then STAND STILL -- do not"):format(SETUP)
       .. " move the character or the camera until [summary] prints")
  manualCheck("after [summary], with a second session (:session add) standing far enough away that its"
              .. " ground is merged into your scene rather than shared, left-click that merged ground"
              .. " several times",
              "the character walks to the point clicked every time, with no click landing at an offset"
              .. " from the cursor")

  refuses("screenToWorld refuses a receiver that is not a function, naming the frame it costs",
          function() return hafen.world():screenToWorld(10, 10, 5) end, "a frame later")

  local function ready()
    return hafen.client():options():client():profiling() == false
  end

  waitFor(ready, SETUP, function(set)
    if not set then
      note(("profiling is still %s after %ds"):format(
           tostring(hafen.client():options():client():profiling()), SETUP))
    end
    if not shape(hafen.client():options():client():profiling() == false) then return summary() end
    hammer()
  end)
end

hafen.slash():register("t070-2", run)   -- the only way in: a suite does not start itself

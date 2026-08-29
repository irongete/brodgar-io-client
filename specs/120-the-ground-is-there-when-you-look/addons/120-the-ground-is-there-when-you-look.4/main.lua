-- 120.4 -- what is built is kept, and what is dropped is dropped safely. Self-checking suite.
--
-- It runs to the end on its own, in about ten seconds, and asks nothing of the camera. That is the whole
-- design of it: this task's claim is that what has been read is KEPT, and the two things that used to
-- drop it are both switches this can throw itself. Taking the ground out of the scene is what panning
-- off it does to every cut it holds; writing the range down is what used to trim the record to a square
-- around a centre. So the pan is not needed to test either -- it was only ever needed to fill the cache
-- past a cap, and a cap the session has not reached is a cap that has nothing to drop.
--
-- The two caps are computed here rather than read back: what is asserted is that the client scales them
-- the way the contract says, and a cap read back out of the thing under test agrees with itself whatever
-- it happens to be.

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

-- A check whose reading is the point: the counts behind it print whether it passed or failed, because a
-- verdict without the number it was reached on is the eye's verdict again.
local function measured(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what .. " -- " .. got)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. got)
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local HI     = 8    -- the widest range there is, and where the run sits unless a step says otherwise
local MIN    = 1    -- the narrowest, whose cap is the smallest one the client has
local FILL   = 80   -- ticks the read side is given to answer the camera it was just handed
local STILL  = 6    -- ticks with what is held unmoved that count as the read side having caught up
local GAP    = 6    -- ticks with the ground out of the scene, so every cut has left it before it counts
local RETURN = 20   -- ticks for the scene to hold what it held before, once the ground is back in it.
                    -- One or two is the answer being looked for: a mesh outlives the slot that drew it,
                    -- so coming back is re-slotting, and anything more than that IS a rebuild.
local HOLD   = 8    -- ticks a written range is left standing before the counts are read against it
local FALL   = 40   -- ticks for a cap that bites to bring what is held down to itself
local SETTLE = 4    -- ticks after a range write during which a count is not held to a cap: the write and
                    -- the client's own tick are not the same tick, so one reading either way is honest

-- The two caps, restated. Grids held: three read squares, and a read square is the drawn range plus the
-- one-grid fill margin either side. Cuts drawn: two fifths of the drawn square, and a grid is 4x4 cuts.
local KEEP, CUTN = 3, 4 * 4

local function gridcap(range)
  local side = ((range + 1) * 2) + 1
  return side * side * KEEP
end

local function cutcap(range)
  local side = (range * 2) + 1
  return math.floor((side * side * CUTN * 2) / 5)
end

local function run()
  local c = hafen.client():options():client()
  local cam = hafen.client():options():camera()
  local wason, wasrange, wasgrey = c:recall(), c:recallRange(), c:recallGrey()
  local wasprof, wascam, switched = c:profiling(), cam:mode(), false

  local rangenow, settle, readings = wasrange, 0, 0
  local seen, overg, overc = {}, nil, nil

  local function render() return hafen.client():profiling():render() end

  -- The ceiling every reading below is held to, whatever step took it: neither count may stand above the
  -- cap of the range in force at the time. Raised by every tick of the driver, read once at the end.
  local function watch(r)
    if settle > 0 then
      settle = settle - 1
      return r
    end
    readings = readings + 1
    if (overg == nil) and (r.recallGridsHeld > gridcap(rangenow)) then
      overg = r.recallGridsHeld .. " held of " .. gridcap(rangenow) .. " at range " .. rangenow
    end
    if (overc == nil) and (r.recallCutsDrawn > cutcap(rangenow)) then
      overc = r.recallCutsDrawn .. " drawn of " .. cutcap(rangenow) .. " at range " .. rangenow
    end
    return r
  end

  local function setrange(n)
    c:recallRange(n)
    seen[n] = true
    rangenow, settle = n, SETTLE
  end

  -- All four counts on a verdict line. A zero on each says something different: nothing held is a read
  -- side that never got going, nothing wanted is a camera framing only ground the live map already draws,
  -- and held standing at read is a source that has dropped nothing at all.
  local function state(r)
    return r.recallGridsHeld .. " held, " .. r.recallGridsRead .. " read, "
           .. r.recallCutsDrawn .. " of " .. r.recallCutsWanted .. " cuts"
  end

  local function restore()
    c:recall(wason):recallRange(wasrange):recallGrey(wasgrey):profiling(wasprof)
    -- Only if we moved it: setcam builds a NEW camera, so putting the name back would reset a zoom and an
    -- angle the suite never touched.
    if switched and (wascam ~= nil) then cam:mode(wascam) end
  end

  local function finish()
    local nr = 0
    for _ in pairs(seen) do nr = nr + 1 end
    check(overg == nil, "grids held never stood above its cap, over " .. readings .. " readings at "
                        .. nr .. " of the " .. HI .. " ranges", overg)
    check(overc == nil, "cuts drawn never stood above its cap, over the same readings", overc)
    restore()
    manualCheck("with :cam rts, pan two screens off your character and straight back",
                "the ground already there on the return, with nothing growing into place a second time")
    manualCheck("pan far in one direction for a minute, then run :recall and paste its lines",
                "grids held and cuts drawn each under the cap printed beside it")
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end

  -- Every count below is read the way every counter is read, with the profiler disarmed, so it is worth a
  -- line of its own that they are numbers at all -- and nothing below can be read if they are not.
  if wasprof then c:profiling(false) end
  local r0 = render()
  local ok0 = (type(r0) == "table") and (type(r0.recallGridsHeld) == "number")
              and (type(r0.recallCutsDrawn) == "number")
  check(ok0, "the four counters answer numbers with profiling disarmed",
        (type(r0) == "table") and (tostring(r0.recallGridsHeld) .. "/" .. tostring(r0.recallCutsDrawn))
                               or tostring(r0))
  if not ok0 then
    restore()
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  -- The camera IS the switch: the raster goes into the scene with the rts camera and comes out with it,
  -- so nothing below means anything until this holds. Installed only if it is not already there -- how
  -- much ground is wanted is how much the camera frames, and that is the maintainer's own zoom.
  if cam:mode() ~= "rts" then
    cam:mode("rts")
    switched = true
  end
  c:recall(true)
  setrange(HI)

  -- What the steps below measure against, taken once the read side has answered the camera it was handed.
  local held0, read0, cuts0, safe, bindfrom = 0, 0, 0, MIN, 0
  local was, steady = -1, 0
  local driver, plan
  local function stop()
    driver:cancel()
    finish()
  end

  plan = {
    -- 1. Let the read side settle, and refuse to go on without ground to keep: every step after this one
    --    is a statement about what is held, and all of them are vacuous over an empty cache.
    function(r, t)
      if r.recallGridsHeld == was then steady = steady + 1 else was, steady = r.recallGridsHeld, 0 end
      if (t < FILL) and ((was <= 0) or (steady < STILL)) then return false end
      held0, read0, cuts0 = r.recallGridsHeld, r.recallGridsRead, r.recallCutsDrawn
      measured((cam:mode() == "rts") and (held0 > 0),
               "the rts camera is up and the remembered ground is in the scene",
               (held0 > 0) and state(r)
                            or (state(r) .. " -- nothing below can be measured over an empty record;"
                                .. " walk a little, then re-run"))
      if held0 <= 0 then
        stop()
        return true
      end
      -- The range the round trip below uses: the narrowest whose cap still admits everything held, so
      -- that trip provably drops nothing and a drop is the client's fault and not the budget's.
      while (safe < HI) and (gridcap(safe) < held0) do safe = safe + 1 end
      return true
    end,

    -- 2. The ground leaves the scene, which is what panning off every cut of it does -- and not one grid
    --    goes with it. A source trimming to a square around a centre dropped the lot here.
    function(r, t)
      if t == 1 then c:recall(false) return false end
      if t < GAP then return false end
      -- Held may only fail to FALL: a sweep already in flight when the switch flipped may still land its
      -- grid, and one more held is not a grid dropped. What must be exact is the other count -- every cut
      -- has left the scene, which is what makes the reading about keeping and not about drawing.
      measured((r.recallGridsHeld >= held0) and (r.recallCutsDrawn == 0),
               "the ground leaves the scene and not one grid is dropped with it",
               "held " .. r.recallGridsHeld .. " of the " .. held0 .. " it stood at, " .. state(r))
      return true
    end,

    -- 3. And back, with nothing to rebuild: the meshes were never disposed, so putting them into the
    --    scene again is re-slotting and not re-meshing. This is the second visit being immediate.
    function(r, t)
      if t == 1 then c:recall(true) return false end
      local met = r.recallCutsDrawn >= cuts0
      if (not met) and (t < RETURN) then return false end
      measured(met, "and comes back with nothing to rebuild, in a tick or two rather than a second",
               (met and (cuts0 .. " cuts again after " .. (t - 1) .. " ticks, ")
                     or ("only " .. r.recallCutsDrawn .. " of " .. cuts0 .. " back after " .. t
                         .. " ticks, ")) .. state(r))
      return true
    end,

    -- 4. The range written down to where its cap still admits everything held. The old record was trimmed
    --    to a square of the range around the camera every tick, so this is where it lost the ground the
    --    camera had been over; the keep set is by last wanted instead, and the cap is what it answers to.
    function(r, t)
      if t == 1 then setrange(safe) return false end
      if t < HOLD then return false end
      measured(r.recallGridsHeld >= held0,
               "the range written down to " .. safe .. " drops nothing its cap still admits ("
               .. gridcap(safe) .. ")",
               "held " .. r.recallGridsHeld .. " of the " .. held0 .. " it stood at, " .. state(r))
      return true
    end,

    -- 5. And back up, with nothing read a second time. Grids read is cumulative, so it standing still
    --    across the whole trip is the exact statement that no grid had to be fetched off the disk again.
    function(r, t)
      if t == 1 then setrange(HI) return false end
      if t < HOLD then return false end
      -- Every grid read during the trip is still held, which is the exact statement that none of them
      -- was a second reading of one that had been dropped. Cumulative against gauge, so a grid that
      -- merely landed late counts on both sides and does not read as a re-read.
      measured((r.recallGridsRead - read0) == (r.recallGridsHeld - held0),
               "and back up to " .. HI .. " with nothing read a second time",
               "read " .. (r.recallGridsRead - read0) .. " more, holding "
               .. (r.recallGridsHeld - held0) .. " more, " .. state(r))
      return true
    end,

    -- 6. And the one thing no switch can cause: a cap that actually bites. It only can where the session
    --    has been over more ground than the smallest range keeps, so it runs when that is already true
    --    and stays silent when it is not -- the second [manual] below is what reports it then. It reads
    --    what it is falling FROM at its own first tick, and runs before the sweep below: a fall measured
    --    after something else has already caused it is a tick count about nothing.
    function(r, t)
      if t == 1 then
        bindfrom = r.recallGridsHeld
        if bindfrom <= gridcap(MIN) then return true end
        setrange(MIN)
        return false
      end
      local met = r.recallGridsHeld <= gridcap(MIN)
      if (not met) and (t < FALL) then return false end
      measured(met, "and the smallest range's cap of " .. gridcap(MIN) .. " takes grids held down to it",
               "from " .. bindfrom .. " to " .. r.recallGridsHeld .. " in " .. (t - 1) .. " ticks, "
               .. state(r))
      return true
    end,

    -- 7. Every range the dial has, one after another, so that both caps are exercised over their whole
    --    domain rather than at the one range the run happened to sit at. The verdict is the two lines
    --    finish() prints: watch() has held every reading taken here to the cap in force for it.
    function(r, t)
      local at = MIN + math.floor((t - 1) / (SETTLE + 2))
      if at > HI then return true end
      if ((t - 1) % (SETTLE + 2)) == 0 then setrange(at) end
      return false
    end,
  }

  local step, stick = 1, 0
  driver = hafen.timer():every(0, function()
    local r = watch(render())
    local f = plan[step]
    if f == nil then
      stop()
      return
    end
    stick = stick + 1
    if f(r, stick) then step, stick = step + 1, 0 end
  end)
end

hafen.console():on("t120", run)   -- the only way in: a suite does not start itself

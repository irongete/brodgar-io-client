-- 120.3 -- the build side is a concurrency target, not a per-tick quota. Self-checking suite.
--
-- The claim is a rate, so every check here is a poll and the unit is the tick: the raster decides what
-- it is holding once a ctick, so a window in ticks says how many decisions the drawn side was given.
-- Twenty ticks is a second, which is the number acceptance asks about.
--
-- What this run cannot cause is the receiver: remembered ground is what the live map is NOT drawing, and
-- the camera sits on the character, where the live map draws everything on screen. No verb moves a camera
-- and no addon may run :cam, so the run waits for the pan on a timer and scores what it reached.

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

-- A check whose reading is the point: the tick count and the counts behind it print whether it passed
-- or failed, because "within the window" without the number is the eye's verdict again.
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

local WAIT   = 400  -- ticks the run waits for the camera to frame ground the live map is not drawing
local GAP    = 4    -- ticks with the system off, so every cut has left the scene before it is counted
local STILL  = 8    -- ticks with the wanted set unmoved that count as the camera having come to rest
local RETURN = 40   -- ticks to fill the scene again once the raster is back in it
local RANGE  = 1    -- the range this runs at, and it is the smallest on purpose: three grids across is
                    -- 144 cuts at most, fewer than the cut cap, so what the two numbers meet at is the
                    -- schedule alone and never the cap.

local function render()
  return hafen.client():profiling():render()
end

local function run()
  local c = hafen.client():options():client()
  local cam = hafen.client():options():camera()
  local wason, wasrange, wasgrey = c:recall(), c:recallRange(), c:recallGrey()
  local wascam, wasprof, switched = cam:mode(), c:profiling(), false
  -- The one ceiling a run can read: what is drawn is a subset of what was wanted, tick by tick, whatever
  -- the budget admitted this time. Raised by every poll below, read once at the end.
  local over = nil

  local function watch(r)
    if (over == nil) and (r.recallCutsDrawn > r.recallCutsWanted) then
      over = r.recallCutsDrawn .. " drawn of " .. r.recallCutsWanted .. " wanted"
    end
    return r
  end

  -- Both other counters on every verdict line, because a zero on this side says nothing on its own: no
  -- grid held is a read side that never got going, and grids held with nothing wanted is a camera framing
  -- ground the live map is already drawing.
  local function state(r)
    return r.recallCutsDrawn .. " of " .. r.recallCutsWanted .. " cuts, "
           .. r.recallGridsHeld .. " grids held, " .. r.recallGridsRead .. " read"
  end

  local function restore()
    c:recall(wason):recallRange(wasrange):recallGrey(wasgrey):profiling(wasprof)
    -- Only if we moved it: setcam builds a NEW camera, so putting the name back would reset a zoom and
    -- an angle the suite never touched.
    if switched and (wascam ~= nil) then cam:mode(wascam) end
  end

  local function finish()
    check(over == nil, "cuts drawn never exceeds cuts wanted, over every reading taken", over)
    restore()
    manualCheck("pan onto remembered ground and look at where it meets live ground",
                "grey ground with no objects and no grass on it, and a seam with no step and no shimmer")
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end

  -- Both numbers are read the way every counter is read, with the profiler disarmed, and everything below
  -- is those two read over and over -- so it is worth a line of its own that they are numbers at all.
  if wasprof then c:profiling(false) end
  local r0 = render()
  check((type(r0.recallCutsDrawn) == "number") and (type(r0.recallCutsWanted) == "number"),
        "cuts drawn and cuts wanted answer numbers with profiling disarmed",
        tostring(r0.recallCutsDrawn) .. "/" .. tostring(r0.recallCutsWanted))

  -- The camera IS the switch: the raster goes into the scene with the rts camera and comes out with it,
  -- so nothing below means anything until this holds. Installed only if it is not already there -- how
  -- much ground is wanted is how much the camera frames, and that is the maintainer's own zoom.
  if cam:mode() ~= "rts" then
    cam:mode("rts")
    switched = true
  end
  check(cam:mode() == "rts", "the rts camera is installed", cam:mode())
  c:recall(true):recallRange(RANGE)
  hafen.log():write("[setup] pan the camera off your character now, over ground you have walked, and "
                    .. "leave it still -- watching for up to " .. (WAIT / 20) .. " seconds")

  local ticks, caught, moved, peak = 0, nil, 0, 0
  local last, seen = nil, nil
  local poll
  poll = hafen.timer():every(0, function()
    ticks = ticks + 1
    local r = watch(render())
    seen = r
    if r.recallCutsWanted > peak then peak = r.recallCutsWanted end
    -- Where the measurement starts: the tick what was wanted last changed on. A pan grows the wanted set
    -- every tick it lasts, and so does each grid the read side lands, and the drawn side chases both -- so
    -- the number worth reporting is the ticks from the last of those to the drawn side catching it, and
    -- not how long the maintainer spent panning.
    if r.recallCutsWanted ~= last then
      last, moved, caught = r.recallCutsWanted, ticks, nil
    end
    if (caught == nil) and (r.recallCutsWanted > 0) and (r.recallCutsDrawn >= r.recallCutsWanted) then
      caught = ticks
    end
    -- Held until the wanted set has stood still: caught on the first tick of a pan is three cuts of three,
    -- which is a reading about nothing.
    if (ticks < WAIT) and ((caught == nil) or ((ticks - moved) < STILL)) then
      return
    end
    poll:cancel()
    local met = caught
    local rest = (caught ~= nil) and (caught - moved) or nil
    -- Its own line, because a zero here is not this task failing: a camera left on the character frames
    -- only ground the live map is already drawing, and every number below would be a verdict on an empty
    -- set. The grid counts say which of the two it was.
    measured(peak > 0, "the camera frames remembered ground the live map is not drawing", state(r))
    measured(met ~= nil, "cuts drawn reaches cuts wanted",
             (met ~= nil) and ("met " .. rest .. " ticks after what was wanted last changed, " .. state(r))
                           or ("still short at " .. ticks .. " ticks, " .. state(r)))

    -- And again from nothing, which is the measurement that owes the camera nothing: out of the scene
    -- every cut loses its slot, and back in the scene every one of them has to be admitted again through
    -- the same budget, with the camera never touched between the two readings. At six a fifth of a second
    -- that was seconds of waiting; a target in flight at the frame rate is the same set back in a few
    -- ticks.
    c:recall(false)
    local offticks = 0
    local off
    off = hafen.timer():every(0, function()
      offticks = offticks + 1
      local ro = watch(render())
      if offticks < GAP then
        return
      end
      off:cancel()
      check(ro.recallCutsDrawn == 0, "with the system off the scene holds no remembered ground at all",
            ro.recallCutsDrawn)

      local want = seen.recallCutsDrawn      -- what was standing in the scene before it was switched off
      c:recall(true)
      local back, remet = 0, nil
      local again
      again = hafen.timer():every(0, function()
        back = back + 1
        local rb = watch(render())
        if (remet == nil) and (rb.recallCutsWanted > 0) and (rb.recallCutsDrawn >= rb.recallCutsWanted) then
          remet = back
        end
        if (remet == nil) and (back < RETURN) then
          return
        end
        again:cancel()
        measured(remet ~= nil, "and fills the scene again within " .. RETURN .. " ticks of coming back",
                 (remet ~= nil) and ("met after " .. remet .. " ticks, " .. state(rb) .. ", " .. want .. " before")
                                 or ("still short at " .. back .. " ticks, " .. state(rb) .. ", " .. want .. " before"))
        finish()
      end)
    end)
  end)
end

hafen.console():on("t120", run)   -- the only way in: a suite does not start itself

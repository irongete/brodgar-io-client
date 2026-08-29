-- 120.6 -- nothing is wanted while nothing is drawn. Self-checking suite.
--
-- It runs to the end on its own, in a few seconds, and asks nothing of the camera beyond having the one
-- that draws remembered ground installed. The claim is about a GAUGE: cuts wanted is what the client wants
-- drawn at this instant, and the switch this suite throws is the whole of what takes the ground out of the
-- scene. Every other camera leaves it by the same branch and the same line of client code, so the switch
-- proves both and the run leaves the maintainer's own zoom and angle alone.
--
-- The pair is what is read, never one of them: a stale want reads as nothing drawn against a number that
-- never moves, and that shape -- zero against a figure standing still -- is exactly what an addon polling
-- the two to know when the ground has arrived would wait on for ever.

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

local HI    = 8    -- the widest range there is, and where the run sits: the most ground the dial can want
local MIN   = 1    -- the narrowest, written under the counts while the ground is out of the scene
local FILL  = 80   -- ticks the client is given to want cuts at the camera it was just handed
local OFF   = 40   -- ticks for both counts to reach zero once the ground is switched off. One or two is
                   -- the answer being looked for -- the branch zeroes them as it drops the raster -- and
                   -- anything past that is a number left standing by something that stopped ticking
local HOLD  = 8    -- ticks a range written while it is off is left standing before it is scored
local BACK  = 60   -- ticks for the ground put back into the scene to want cuts again

local function run()
  local c = hafen.client():options():client()
  local cam = hafen.client():options():camera()
  local wason, wasrange, wasgrey = c:recall(), c:recallRange(), c:recallGrey()
  local wasprof, wascam, switched = c:profiling(), cam:mode(), false

  local function render() return hafen.client():profiling():render() end

  -- The invariant behind steps 2 to 4, raised by every tick taken while the ground is out of the scene:
  -- neither gauge may read anything but zero there, whatever else the run does to the dial meanwhile.
  local offwatch, offreadings, offbad = false, 0, nil

  local function watch(r)
    if offwatch then
      offreadings = offreadings + 1
      if (offbad == nil) and ((r.recallCutsDrawn > 0) or (r.recallCutsWanted > 0)) then
        offbad = r.recallCutsDrawn .. " drawn, " .. r.recallCutsWanted .. " wanted"
      end
    end
    return r
  end

  -- Both gauges on a verdict line, and the two grid counts behind them, because a zero on each says
  -- something different: nothing wanted with grids held standing is the reading this task exists to make
  -- honest, and nothing held at all is a record with nothing in it to want.
  local function state(r)
    return r.recallCutsDrawn .. " of " .. r.recallCutsWanted .. " cuts, "
           .. r.recallGridsHeld .. " grids held, " .. r.recallGridsRead .. " read"
  end

  local function restore()
    c:recall(wason):recallRange(wasrange):recallGrey(wasgrey):profiling(wasprof)
    -- Only if we moved it: setting the mode builds a NEW camera, so putting the name back would reset a
    -- zoom and an angle the suite never touched.
    if switched and (wascam ~= nil) then cam:mode(wascam) end
  end

  local function finish()
    restore()
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end

  -- Every count below is read the way every counter is read, with the profiler disarmed, so it is worth a
  -- line of its own that they are numbers at all -- and nothing below can be read if they are not.
  if wasprof then c:profiling(false) end
  local r0 = render()
  local ok0 = (type(r0) == "table") and (type(r0.recallCutsDrawn) == "number")
              and (type(r0.recallCutsWanted) == "number") and (type(r0.recallGridsHeld) == "number")
  check(ok0, "the counters answer numbers with profiling disarmed",
        (type(r0) == "table") and (tostring(r0.recallCutsDrawn) .. "/" .. tostring(r0.recallCutsWanted))
                               or tostring(r0))
  if not ok0 then
    restore()
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  -- The camera IS the switch the client reads: the raster goes into the scene with the rts camera and comes
  -- out with it, so nothing below means anything until this holds. Installed only if it is not already
  -- there -- how much ground is wanted is how much the camera frames, and that is the maintainer's own zoom.
  if cam:mode() ~= "rts" then
    cam:mode("rts")
    switched = true
  end
  c:recall(true):recallRange(HI)

  local wanted0, drawn0 = 0, 0
  local driver, plan
  local function stop()
    driver:cancel()
    finish()
  end

  plan = {
    -- 1. Ground in the scene, and cuts actually wanted there. Every step after this one is a statement
    --    about a number falling to zero, and all of them are vacuous over a gauge already at zero.
    function(r, t)
      if (t < FILL) and ((r.recallCutsWanted <= 0) or (r.recallCutsDrawn <= 0)) then return false end
      wanted0, drawn0 = r.recallCutsWanted, r.recallCutsDrawn
      measured(wanted0 > 0, "the rts camera is up and the remembered ground wants cuts in the scene",
               (wanted0 > 0) and state(r)
                              or (state(r) .. " -- nothing below can be measured over a gauge already at"
                                  .. " zero; walk a little, then re-run"))
      if wanted0 <= 0 then
        stop()
        return true
      end
      return true
    end,

    -- 2. Switched off, which takes the raster out of the scene. Cuts drawn falls of its own accord -- the
    --    scene stops holding what it removed -- and cuts wanted is the half that used to keep the number it
    --    left with, because a raster out of the scene is never ticked again to say otherwise.
    function(r, t)
      if t == 1 then c:recall(false) return false end
      local met = (r.recallCutsDrawn == 0) and (r.recallCutsWanted == 0)
      if (not met) and (t < OFF) then return false end
      measured(met, "switched off, cuts wanted goes to zero with cuts drawn",
               (met and ("both zero " .. (t - 1) .. " ticks after the write, ")
                     or ("still " .. r.recallCutsDrawn .. " drawn and " .. r.recallCutsWanted
                         .. " wanted after " .. t .. " ticks, "))
               .. "from " .. drawn0 .. " of " .. wanted0 .. " -- now " .. state(r))
      offwatch = true
      return true
    end,

    -- 3. And the dial moved under them while the ground is out of the scene. This is the write that reaches
    --    the client's own recall tick without putting anything back on screen, so it is where a gauge
    --    recomputed by the wrong owner would show itself.
    function(r, t)
      if t == 1 then c:recallRange(MIN) return false end
      if t < HOLD then return false end
      measured((r.recallCutsDrawn == 0) and (r.recallCutsWanted == 0),
               "and the range written down to " .. MIN .. " under them moves neither", state(r))
      return true
    end,

    -- 4. ...and back up, which of the two is the write that would recompute the widest square.
    function(r, t)
      if t == 1 then c:recallRange(HI) return false end
      if t < HOLD then return false end
      measured((r.recallCutsDrawn == 0) and (r.recallCutsWanted == 0),
               "and back up to " .. HI .. " moves neither either", state(r))
      return true
    end,

    -- 5. The other direction, which is what makes it a gauge rather than a number zeroed once: the ground
    --    goes back into the scene and the want comes back with it.
    function(r, t)
      if t == 1 then
        offwatch = false
        c:recall(true)
        return false
      end
      local met = r.recallCutsWanted > 0
      if (not met) and (t < BACK) then return false end
      measured(met, "and switched back on, cuts wanted rises again",
               (met and (r.recallCutsWanted .. " wanted after " .. (t - 1) .. " ticks, against the "
                         .. wanted0 .. " it stood at, ")
                     or ("nothing wanted after " .. t .. " ticks, against the " .. wanted0
                         .. " it stood at, ")) .. state(r))
      -- The invariant the three steps above ran under, scored once, over every reading taken while the
      -- ground was out of the scene rather than only at the ticks a verdict was read on.
      measured(offbad == nil, "and neither gauge read anything but zero at any tick out of the scene",
               (offbad == nil) and (offreadings .. " readings, all zero") or offbad)
      return true
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

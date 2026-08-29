-- 120.2 -- the read side asks once, and asks for what is wanted. Self-checking suite.
--
-- The whole claim is a number over time, so every check here is a poll: grids read is cumulative, and
-- what it does from one tick to the next is what "asks once" and "nothing is read while it is out of
-- the scene" mean when a program rather than an eye is watching.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Ticks, not seconds: one sweep runs per ctick, so the tick is the read side's own unit and a window
-- in ticks says how many sweeps it was given.
local SETTLE = 80   -- the window: how many ticks the read side gets to catch up with the camera
local STILL  = 10   -- consecutive ticks with the count unmoved that count as caught up
local GRACE  = 5    -- ticks allowed for a sweep already in flight to install what it had asked for
local OFF    = 40   -- ticks watched with the system off, while the range grows under it
local RANGE  = 4    -- the known range the read side is asked to answer
local WIDE   = 8    -- and the widest one it must ignore, because nothing is wanted by then

local function gridsRead()
  return hafen.client():profiling():render().recallGridsRead
end

local function run()
  local c = hafen.client():options():client()
  local cam = hafen.client():options():camera()
  local wason, wasrange, wasgrey = c:recall(), c:recallRange(), c:recallGrey()
  local wascam, wasprof, switched = cam:mode(), c:profiling(), false

  local function restore()
    c:recall(wason):recallRange(wasrange):recallGrey(wasgrey):profiling(wasprof)
    -- Only if we moved it: setcam builds a NEW camera, so putting the name back would reset a zoom
    -- and an angle the suite never touched.
    if switched and (wascam ~= nil) then cam:mode(wascam) end
  end

  local function finish()
    restore()
    manualCheck("walk a while with the rts camera up, then run :recall",
                "requests sent 0 -- the record is read off the disk and never off the wire")
    manualCheck("pan the camera well off the character, then walk into a house or a cave",
                "the remembered ground goes at once and comes back in the right place, never in the wrong one")
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end

  -- Everything below is this one number read over and over, so it is worth a line of its own that it is
  -- a number at all -- and read the way every counter is read, with the profiler disarmed.
  if wasprof then c:profiling(false) end
  check(type(gridsRead()) == "number", "grids read answers a number with profiling disarmed", gridsRead())

  -- The camera IS the switch: the raster goes into the scene with the rts camera and comes out with it,
  -- so nothing below means anything until this holds. Installed only if it is not already there -- what
  -- the read side has to answer is how much ground the camera frames, and that is the maintainer's zoom.
  if cam:mode() ~= "rts" then
    cam:mode("rts")
    switched = true
  end
  c:recall(true)
  check(cam:mode() == "rts", "the rts camera is installed", cam:mode())

  -- Shrink first, so that growing has ground to go and read. The trim follows the range on the very
  -- next tick, and the raster hands over its wider set in the same frame the range moves.
  c:recallRange(1)
  hafen.timer():after(1, function()
    local base = gridsRead()
    c:recallRange(RANGE)

    local ticks, still, last, settled = 0, 0, nil, nil
    local poll
    poll = hafen.timer():every(0, function()
      ticks = ticks + 1
      local r = gridsRead()
      if r ~= last then last, still = r, 0 else still = still + 1 end
      if (settled == nil) and (still >= STILL) then settled = ticks end
      if (settled ~= nil) or (ticks >= SETTLE) then
        poll:cancel()
        -- What the run reached is on the line either way: a camera zoomed in on its own character
        -- frames one grid and has nothing to read, and the count beside the verdict says so.
        local reached = (last - base) .. " grids read at range " .. RANGE
        check(settled ~= nil, "grids read settles within " .. SETTLE .. " ticks",
              (settled ~= nil) and ("settled after " .. settled .. " ticks, " .. reached)
                                or ("still climbing at " .. ticks .. " ticks, " .. reached))

        -- Out of the scene, and the range grows to its widest underneath it. A square around the centre
        -- would read every grid that wider square just added; a wanted set the raster is not there to
        -- hand over is empty, and an empty set reads nothing.
        c:recall(false)
        c:recallRange(WIDE)
        local offbase, offticks, moved = nil, 0, nil
        local watch
        watch = hafen.timer():every(0, function()
          offticks = offticks + 1
          if offticks <= GRACE then
            -- A sweep started before the switch may still install what it had already asked for. The
            -- claim is that reading stops, not that an ask in flight is torn up, so the count is taken
            -- once those have landed.
            offbase = gridsRead()
            return
          end
          local now = gridsRead()
          if (moved == nil) and (now ~= offbase) then moved = now end
          if (moved ~= nil) or (offticks >= (GRACE + OFF)) then
            watch:cancel()
            check(moved == nil,
                  "with the system off, grids read does not move while the range grows to " .. WIDE,
                  (moved ~= nil) and (offbase .. " then " .. moved)
                                 or (offbase .. " over " .. (offticks - GRACE) .. " ticks"))
            finish()
          end
        end)
      end
    end)
  end)
end

hafen.console():on("t120", run)   -- the only way in: a suite does not start itself

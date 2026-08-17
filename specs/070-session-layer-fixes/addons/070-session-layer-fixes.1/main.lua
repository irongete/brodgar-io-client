-- 070.1 -- the ground query asks every session, in the frame each one is in. Self-checking suite.
--
-- The fix is inside a camera callback no addon can call, so the proof is a COUNTER. The client counts
-- both exits of the query -- one session had that ground, or none did -- and the pair is readable with
-- the profiler off, which is the only way it could be read at all: nobody arms a profiler to watch a
-- camera panned over another character's surroundings.
--
-- The run therefore SAYS WHAT IT NEEDS FIRST and then waits for it, because both preconditions are the
-- maintainer's to set and neither can be set from here: arming is a saved preference, and a suite does
-- not write one. Each wait is bounded and scores over what the run reached. groundAnswered rising is
-- the whole claim -- the query is reached ONLY because the drawn session's own map threw for that
-- place, so an answer means another session was asked and had it.

local SETUP    = 45     -- seconds each precondition is waited for
local INTERVAL = 0.5    -- seconds between polls
local SETTLE   = 1.5    -- seconds after the query starts before the measured window opens
local WINDOW   = 6      -- seconds the counters are watched
local RING     = 44     -- world units per probe step (four tiles)
local REACH    = 60     -- probe steps outward, so ~2640 units

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

-- Wait for something the maintainer has to do, for a bounded time, and carry on either way.
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

-- How far the drawn session's own map reaches. hafen.world():height(p) reads THAT map and nothing else,
-- so the nearest point where it answers nil is where the query this task fixes starts being asked --
-- and the precondition it rests on: the camera can only reach the layer by looking past this edge.
local function edge()
  local g = hafen.player():gob()
  if not (g and g:exists()) then return nil end
  local home = g:position()
  if not (home and home:x()) then return nil end
  local dirs = { {1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {1,-1}, {-1,1}, {-1,-1} }
  for step = 1, REACH do
    for _, d in ipairs(dirs) do
      local p = home:offset(d[1] * step * RING, d[2] * step * RING)
      if p and (hafen.world():height(p) == nil) then
        return step * RING, p
      end
    end
  end
  return nil
end

-- ---- the shape of the group, once the profiler is off ------------------------------------------

local function shape(armedOff)
  local s = counters()
  check(type(s) == "table", "profiling():session() answers a table", type(s))
  if type(s) ~= "table" then return false end

  -- The group is pull-only: it answers with nothing armed, which is the only state it is worth
  -- anything in. The frame surface beside it answering EMPTY in the same breath is what makes that a
  -- measurement rather than a claim -- one verb reads, its neighbour has nothing to read.
  eq("the profiling switch is off, so the counters answer unarmed", armedOff, true)
  check(next(hafen.client():profiling():frame()) == nil,
        "frame() is empty in the same state, so the group answers where the frame surface cannot",
        "frame() answered")
  check((type(s.groundAnswered) == "number") and (s.groundAnswered >= 0)
        and (type(s.groundMissed) == "number") and (s.groundMissed >= 0),
        ("both counters are numbers (%s, %s)"):format(tostring(s.groundAnswered), tostring(s.groundMissed)),
        tostring(s.groundAnswered) .. ", " .. tostring(s.groundMissed))

  refuses("session(1) is refused as read-only",
          function() return hafen.client():profiling():session(1) end, "read-only")
  return true
end

-- ---- the query itself ---------------------------------------------------------------------------

local function verdict(a)
  local b = counters()
  local da, dm = b.groundAnswered - a.groundAnswered, b.groundMissed - a.groundMissed
  local moved = ("answered +%d, missed +%d over %ds"):format(da, dm, WINDOW)

  if da > 0 then
    check(true, "groundAnswered rose while the camera sat over another session's ground (" .. moved .. ")")
    -- Only worth asserting once the query has run: with nothing asked, a zero here measures nothing.
    check(dm == 0, "groundMissed did not rise where the drawn session's map is the one that threw",
          moved .. " -- the view also covered ground no session has loaded")
  else
    check(false, "groundAnswered rose while the camera sat over another session's ground",
          moved .. " -- the layer was asked and no session had that ground; with a single session up"
                .. " that is the expected shape, since the only placed session is the one already looking")
  end
  summary()
end

local function window()
  local ok, at, p = pcall(edge)   -- before the world is up there is no player to measure from
  if not ok then at = nil end
  if at then
    check(true, ("the drawn session's own map ends %d units out -- height() reads nil at %.0f, %.0f")
                :format(at, p:x() or 0, p:y() or 0))
  else
    check(false, "the drawn session's own map has an edge within reach of the probe",
          ("height() answered everywhere within %d units -- the camera cannot reach the query from here")
          :format(RING * REACH))
  end

  -- Wait for the query to START, rather than assuming it already is: the camera reaches it only while
  -- it is held over ground the drawn session has never loaded, and that is a pan the maintainer makes.
  local base = counters()
  local function asked()
    local n = counters()
    return (n.groundAnswered ~= base.groundAnswered) or (n.groundMissed ~= base.groundMissed)
  end
  note(("waiting up to %ds for the ground query to start -- pan the rts camera onto the other session's"
        .. " ground and HOLD it there"):format(SETUP))

  waitFor(asked, SETUP, function(started)
    if not started then
      check(false, "the ground query ran at all",
            ("nothing counted in %ds -- the camera never looked past the drawn session's own map, so"
             .. " nothing about the fix was measured"):format(SETUP))
      return summary()
    end
    -- The pan itself sweeps over void on the way; the window opens once it has settled.
    hafen.timer():after(SETTLE, function()
      local a = counters()   -- the verdict prints both deltas, so the baseline needs no line of its own
      hafen.timer():after(WINDOW, function() verdict(a) end)
    end)
  end)
end

-- ---- the run ------------------------------------------------------------------------------------

local function run()
  if running then
    hafen.log():write("[skip] a run is already in flight -- wait for its [summary]")
    return
  end
  running, pass, fail, manual = true, 0, 0, 0

  -- Said BEFORE anything is asserted, and then waited for: an instruction printed beside the check it
  -- gates is an instruction that arrives too late to follow.
  note(("set up now, within %ds each: profiling OFF in Options, a second account through"):format(SETUP)
       .. " :session add and in the world, :cam rts")
  manualCheck("pan the rts camera well away from your character, onto ground only the OTHER session has,"
              .. " and leave it there for the whole run",
              "the ground stays solid under the view and the camera holds its height instead of"
              .. " flattening out")
  manualCheck("with only ONE session up, run this again and read the last verdict",
              "the shape checks still run and the window says the layer was asked and could not answer,"
              .. " rather than passing silently")

  local function ready()
    return (hafen.client():options():client():profiling() == false)
       and (hafen.client():options():camera():mode() == "rts")
  end

  waitFor(ready, SETUP, function(set)
    if not set then
      note(("still not set after %ds: profiling=%s camera=%s"):format(SETUP,
           tostring(hafen.client():options():client():profiling()),
           tostring(hafen.client():options():camera():mode())))
    end
    eq("the rts camera is installed, so the query can be reached at all",
       hafen.client():options():camera():mode(), "rts")
    if not shape(hafen.client():options():client():profiling() == false) then return summary() end
    window()
  end)
end

hafen.slash():register("t070-1", run)   -- the only way in: a suite does not start itself

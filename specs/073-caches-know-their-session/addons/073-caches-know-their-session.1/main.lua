-- 073.1 — the engine's state gets a session to belong to. Self-checking suite.
--
-- The engine's own per-session state is now one object per session, keyed on the UI that session runs in.
-- With one session live that index holds exactly one entry, so every claim below held before this task too:
-- what it proves is that the state is minted per session and RELEASED with its UI (states == live), and that
-- the marshalling queues still carry an event end to end now that each enqueue picks the state of the
-- session it was handed rather than the one on screen.

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

local RAYS, WINDOW, STEP = 9, 10.0, 0.25   -- probes fired; how long a receiver has to arrive; the poll

local function run()
  pass, fail, manual = 0, 0, 0

  -- THE COUNTER, read with profiling untouched: this group is pull-only, so it answers armed or not.
  local s = hafen.client():profiling():session()
  check(type(s.states) == "number",
        "session():states reads as a number with profiling untouched", type(s.states))
  check(s.states == s.live,
        ("every live session holds engine state, and nothing else does (live=%s states=%s)")
          :format(tostring(s.live), tostring(s.states)), tostring(s.states))
  refuses("the group is still read-only, and says so",
          function() hafen.client():profiling():session(1) end, "read-only")

  -- THE WIDGET-REMOVAL QUEUE, end to end and with nothing to wait for the server about: a window of our
  -- own goes into the tree, its removal is enqueued at the seam under the widget's OWN ui, and the tick
  -- drains that session's queue and fires Destroy. It is the whole path this task rewired, in one round trip.
  local win = hafen.ui():window():title("073.1 probe"):size(120, 40):position(60, 60)
  local destroyed = false

  -- THE GOB QUEUE, whose receiver only the server can produce: subscribe and let the world stream. BOTH
  -- edges, because they are one queue and one enqueue -- a gob leaving proves what a gob arriving proves,
  -- and standing still in an empty place is a run that legitimately sees neither.
  local adds, gone = 0, 0
  local subA = hafen.event():on("GobAdded", function() adds = adds + 1 end)
  local subR = hafen.event():on("GobRemoved", function() gone = gone + 1 end)

  -- THE RAYCAST, re-asserted because this task moved the queues those callbacks feed.
  local sz = hafen.ui():root():size()
  local before = hafen.client():profiling():session().placedRebuiltOffTick
  local done, hits = 0, 0
  local cx, cy = math.floor(sz.x / 2), math.floor(sz.y / 2)
  for i = 1, RAYS do
    hafen.world():screenToWorld(cx + ((i - 5) * 12), cy, function(p)
      done = done + 1
      if (p ~= nil) and (p:x() ~= nil) then hits = hits + 1 end
    end)
  end

  -- A builder is armed on the next tick, so the window is in the tree by the time this runs.
  hafen.timer():after(STEP, function()
    win:on("Destroy", function() destroyed = true end)
    win:destroy()
  end)

  local waited = 0
  local function score()
    if ((done < RAYS) or (not destroyed) or ((adds + gone) == 0)) and (waited < WINDOW) then
      waited = waited + STEP
      hafen.timer():after(STEP, score)
      return
    end
    subA:off()
    subR:off()
    check(destroyed, "a window of our own reached the removal queue and came back as Destroy", destroyed)
    -- The gob queue is fed by the server alone, so a run that saw nothing SAYS so rather than failing: the
    -- assertion is real whenever the world produced anything for it, and a step is what produces one.
    if (adds + gone) > 0 then
      check(true, ("an enqueue found its session's state: %d GobAdded, %d GobRemoved in %.1fs")
                    :format(adds, gone, waited))
    else
      manualCheck(("nothing entered or left the scene in %.1fs -- walk a few steps and re-run :t073-1")
                    :format(waited), "the GobAdded line reports the queue carried an event")
    end
    check(hits > 0, ("a screenToWorld burst reached the ground (%d of %d answered, %d hit)")
                      :format(done, RAYS, hits), hits)
    local after = hafen.client():profiling():session().placedRebuiltOffTick
    check((after == before) and (after == 0),
          ("placedRebuiltOffTick is still 0 across the burst (was %s)"):format(tostring(before)), after)

    manualCheck(":session add <a second account>, let it reach the world, then re-run :t073-1",
                "the live/states line reports live=2 states=2")
    manualCheck(":session drop <that account>, then re-run :t073-1",
                "the same line is back to live=1 states=1 -- nothing was left behind")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
  hafen.timer():after(STEP, score)
end

hafen.slash():register("t073-1", run)   -- the only way in: a suite does not start itself

-- 071.2 -- one list of sessions, and the second copy of everything deleted. Self-checking suite.

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

local function summary(live)
  -- The number the deletion could have changed without any refusal: buildplaced() now walks one list,
  -- so a session missing from it is a session the client holds and cannot locate.
  manualCheck(("count the characters you have in the world right now (`live` read %s)"):format(tostring(live)),
              "the same number")
  manualCheck("with TWO sessions up, run `:session list` and paste the whole block",
              "both rows name an ACCOUNT, and neither of them reads `main`")
  manualCheck("with two sessions apart, `:cam rts` and pan (middle drag) over ground only the OTHER session has loaded",
              "the ground stays solid and the camera holds its height -- it never drops or blacks out")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The pixels the burst raycasts: the character's own screen point and a ring around it, which is ground
-- the drawn session has certainly loaded because it is standing on it.
local ring = {{0, 0}, {40, 0}, {-40, 0}, {0, 40}, {0, -40}, {36, 36}, {-36, 36}, {-36, -36}}

local function run()
  -- The whole group, from scratch: no other suite is assumed to have been run, and every one of these
  -- four numbers is answered out of the layer this task rewrote.
  local s = hafen.client():profiling():session()
  check(type(s.live) == "number" and type(s.groundAnswered) == "number"
        and type(s.groundMissed) == "number" and type(s.placedRebuiltOffTick) == "number",
        "the session group answers with the profiler off: live, groundAnswered, groundMissed, placedRebuiltOffTick",
        tostring(s.live) .. "/" .. tostring(s.groundAnswered) .. "/" .. tostring(s.groundMissed)
          .. "/" .. tostring(s.placedRebuiltOffTick))
  -- The session on screen is a member like every other, so it is counted like every other.
  check(type(s.live) == "number" and s.live >= 1,
        ("the client holds the session on screen: live = %s"):format(tostring(s.live)), s.live)
  check(s.placedRebuiltOffTick == 0,
        ("no click was answered before the frame said where the sessions stand: placedRebuiltOffTick = %s")
          :format(tostring(s.placedRebuiltOffTick)), s.placedRebuiltOffTick)
  refuses("the session group refuses an argument, naming itself read-only",
          function() hafen.client():profiling():session(1) end, "read-only")

  local me = hafen.player():gob()
  local at = me and me:position() and hafen.player():worldToScreen(me:position())
  if not at then
    check(false, "the character is in the world, so there is ground to raycast", "not in the world")
    summary(s.live)
    return
  end
  -- The regression this deletion could plausibly cause: the published cache is now built by ONE loop
  -- over the members, and the raycasts answer on the graphics environment's own callback thread, which
  -- must go on being served from what the frame published rather than building it there.
  local want, got, placed = #ring, 0, 0
  for _, d in ipairs(ring) do
    hafen.world():screenToWorld(at.x + d[1], at.y + d[2], function(p)
      got = got + 1
      if p and p:x() and p:y() then placed = placed + 1 end
    end)
  end
  hafen.timer():after(3, function()
    check(got == want and placed == want,
          ("every raycast came back with a Position (%d/%d answered, %d placed)"):format(got, want, placed),
          ("%d answered, %d placed"):format(got, placed))
    local b = hafen.client():profiling():session()
    check(b.placedRebuiltOffTick == 0,
          ("the raycasts read the published cache and never built it: placedRebuiltOffTick = %s after %d")
            :format(tostring(b.placedRebuiltOffTick), want), b.placedRebuiltOffTick)
    check(b.live == s.live,
          ("live held still across the run (%s)"):format(tostring(b.live)), b.live)
    summary(b.live)
  end)
end

hafen.slash():register("t071-2", run)   -- the only way in: a suite does not start itself

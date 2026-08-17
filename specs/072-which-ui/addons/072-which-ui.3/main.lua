-- 072.3 — the screen is one, and the fields are gone. Self-checking suite.
--
-- The two ambient fields are deleted, so the pointer, the modifier keys and the drawn 3D view are read
-- through names that say THE SCREEN. Every claim here held before this task: what it proves is that
-- deriving those three from the session on screen answers the same thing the fields did -- including
-- 070.2's own guarantee, re-proved because this task rewrote who reads the drawn view.

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

local CAMERAS = { follow = true, worse = true, bad = true, ortho = true, rts = true }

local RAYS, WINDOW = 9, 4.0    -- probes fired, and how long the GPU has to answer before we score it

local function run()
  pass, fail, manual = 0, 0, 0

  -- THE POINTER, and the box it has to be inside: one pointer, over the one screen.
  local m = hafen.ui():mouse()
  local sz = hafen.ui():root():size()
  local mx, my = m:x(), m:y()
  check((mx ~= nil) and (my ~= nil) and (mx >= 0) and (my >= 0) and (mx <= sz.x) and (my <= sz.y),
        ("the pointer (%s, %s) is inside the client's own %sx%s box")
          :format(tostring(mx), tostring(my), tostring(sz.x), tostring(sz.y)),
        tostring(mx) .. "," .. tostring(my))
  check((type(m:shift()) == "boolean") and (type(m:ctrl()) == "boolean") and (type(m:alt()) == "boolean"),
        ("the modifier flags read live (shift=%s ctrl=%s alt=%s)")
          :format(tostring(m:shift()), tostring(m:ctrl()), tostring(m:alt())))

  -- THE DRAWN VIEW, asked through a converted file: the camera in force names a camera the client has.
  local mode = hafen.client():options():camera():mode()
  check(CAMERAS[mode] == true, "camera:mode() names a camera the client has", mode)

  -- Two refusals, because the verb has two doors and only one of them explains itself: an OMITTED
  -- callback meets the arity check, a non-function one meets the reason the callback exists at all.
  refuses("screenToWorld with no callback is refused, naming what is missing",
          function() hafen.world():screenToWorld(0, 0) end, "fn is required")
  refuses("...and a non-function callback is refused, saying why it cannot answer inline",
          function() hafen.world():screenToWorld(0, 0, 7) end, "the answer comes back a frame later")

  -- THE RAYCAST: a burst over drawn ground, scored when the GPU has answered or the window is up.
  -- Only the GPU can produce this receiver, so it is a bounded retry rather than a manual step.
  local before = hafen.client():profiling():session().placedRebuiltOffTick
  local done, hits = 0, 0
  local cx, cy = math.floor(sz.x / 2), math.floor(sz.y / 2)
  for i = 1, RAYS do
    local sx = cx + ((i - 5) * 12)
    hafen.world():screenToWorld(sx, cy, function(p)
      done = done + 1
      if (p ~= nil) and (p:x() ~= nil) then hits = hits + 1 end
    end)
  end

  local waited = 0
  local function score()
    if (done < RAYS) and (waited < WINDOW) then
      waited = waited + 0.25
      hafen.timer():after(0.25, score)
      return
    end
    check(hits > 0, ("a screenToWorld burst reached the ground (%d of %d answered, %d hit)")
                      :format(done, RAYS, hits), hits)
    local after = hafen.client():profiling():session().placedRebuiltOffTick
    check((after == before) and (after == 0),
          ("placedRebuiltOffTick is still 0 across the burst (was %s)"):format(tostring(before)), after)

    manualCheck("put the mouse in a corner of the window and run :t072-3 again",
                "the pointer line reports that corner, not the centre and not 0, 0")
    manualCheck(":session add <a second account>, let it reach the world, tab between the two",
                "no addon window vanishes and no click lands on the wrong character")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
  hafen.timer():after(0.25, score)
end

hafen.slash():register("t072-3", run)   -- the only way in: a suite does not start itself

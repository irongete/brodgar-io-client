-- 042.11 -- map markers. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. MapApi.pollMarkers() -- the per-frame markerseq comparison -- is gone.
-- MarkersChanged now fires from a notify placed at the DB's own mutation points (MapFile.add/remove/
-- update, a Marker's own update(false), and the segment-merge re-key), marshalled onto the tick. The
-- initial load at login does not fire (it primes silently); every real change after that does.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. TESTING.md forbids a suite from mutating persistent state -- map
-- markers included -- even though the write verbs are ungated, so the actual add/remove is a [manual]
-- step. What CAN be automated: the payload's shape (a plain number, matching hafen.map():marker():count()),
-- idle silence, and (because the subscription below is at file scope, not inside run()) counting fires
-- that happen after :t042-11's own summary has printed.

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

-- Fired for as long as the suite is installed, so a manual add/remove after :t042-11's own summary
-- still shows up as a log line the maintainer can read directly.
local changed = 0

hafen.event():on("MarkersChanged", function(count)
  changed = changed + 1
  check(type(count) == "number", "MarkersChanged carries a plain number payload (not a wrapper)",
        type(count))
  local live = hafen.map():marker():count()
  check(count == live, "the payload matches hafen.map():marker():count()",
        ("payload=%s live=%s"):format(tostring(count), tostring(live)))
  hafen.log():write(("MarkersChanged fired (#%d) -- count=%s"):format(changed, tostring(count)))
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseChanged = changed

  -- Idle: the feature's whole claim is that nothing fires on its own once nothing is changing.
  hafen.timer():after(3, function()
    local dChanged = changed - baseChanged
    check(dChanged == 0, "idle: no MarkersChanged fires over 3s with nothing changing",
          ("changed=%d"):format(dChanged))

    manualCheck("place a map marker (open the map window, click to set one)",
      "exactly one 'MarkersChanged fired' line above, count = the new total")
    manualCheck("delete that marker",
      "exactly one more 'MarkersChanged fired' line, count = the total minus one")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-11", run)   -- the only way in: a suite does not start itself

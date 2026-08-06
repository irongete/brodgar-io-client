-- 042.1 -- the two mechanisms (the removal seam, Resolve), with the meters as their first consumer.
-- Self-checking suite; see specs/addons/TESTING.md and specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's MeterAdapter.poll() is gone: MeterAdded/MeterRemoved now fire from the
-- widget-placement/removal seams (M1/M3) at the moment a HUD bar appears or disappears, not from a per-frame
-- diff against a cache. MeterChanged is UNCHANGED -- it was already event-driven off the "set"/"col" uimsg.
-- A removed meter's payload keeps answering its read verbs and reports :exists() false.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Resolve (M2) ships in this task as infrastructure, per the plan's
-- instruction that a mechanism built for later tasks ships with a consumer or ships unproven -- but every
-- MeterAdapter read is already Loading-guarded to nil (LuaMeter's own contract), so it throws no Loading and
-- gives Resolve nothing to wrap here. Its cancel-on-:reload guarantee has no hafen.* surface to probe without
-- adding one, which this feature's acceptance criteria forbid. Left for the first task with a real
-- Loading-guarded read (042.3 equipment's GItem.info(), or 042.4 study) to prove; flagged in HANDOFF.md.
--
-- The MeterAdded/MeterRemoved/MeterChanged subscriptions below are made at FILE-LOAD time (never :off()'d,
-- like :t041-1's Disable listener) so they keep logging for as long as this suite is installed -- the manual
-- checks below need them alive well after :t042-1's own run finishes.

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

-- Fired for as long as the suite is installed, so a manual action after :t042-1's own summary still shows up.
local added, removed, changed = 0, 0, 0

hafen.event():on("MeterAdded", function(m)
  added = added + 1
  hafen.log():write(("MeterAdded fired (#%d) -- res=%s"):format(added, tostring(m:res())))
end)
hafen.event():on("MeterRemoved", function(m)
  removed = removed + 1
  hafen.log():write(("MeterRemoved fired (#%d) -- res=%s value=%s exists=%s")
    :format(removed, tostring(m:res()), tostring(m:value()), tostring(m:exists())))
end)
hafen.event():on("MeterChanged", function(m)
  changed = changed + 1
  hafen.log():write(("MeterChanged fired (#%d) -- res=%s value=%s")
    :format(changed, tostring(m:res()), tostring(m:value())))
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseAdded, baseRemoved, baseChanged = added, removed, changed

  -- 1. The read surface already up (hp/stamina/energy, whatever the server sent) still answers its verbs --
  --    the reads themselves did not move, only how the ADD/REMOVE events are detected.
  local list = hafen.meter():list()
  check(#list > 0, "hafen.meter():list() sees at least one HUD bar", #list)
  if #list > 0 then
    local m = list[1]
    check(m:exists(), "a live meter reports :exists() true", m:exists())
    check(type(m:segments()) == "table", "a live meter's :segments() is a table", type(m:segments()))
  end

  -- 2. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing.
  hafen.timer():after(3, function()
    local dAdded, dRemoved, dChanged = added - baseAdded, removed - baseRemoved, changed - baseChanged
    check((dAdded == 0) and (dRemoved == 0) and (dChanged == 0),
          "idle: no meter event fires over 3s with nothing changing",
          ("added=%d removed=%d changed=%d"):format(dAdded, dRemoved, dChanged))

    manualCheck("take a hit, or drink/eat so a bar visibly moves, then watch this log",
                "exactly one 'MeterChanged fired (#N)' line per real change -- no repeats while the bar"
                .. " then sits still")
    manualCheck("mount a horse, then dismount, watching this log",
                "two 'MeterAdded fired' lines when you mount (the horse's own bar + 'mount'; res=nil is"
                .. " fine right at that instant -- the resource is still loading) and two 'MeterRemoved"
                .. " fired ... exists=false' lines when you dismount -- never a stream, and the removed"
                .. " lines still show a real value")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-1", run)   -- the only way in: a suite does not start itself

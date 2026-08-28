-- 119.1 -- the overlay work has a number. Self-checking suite.
--
-- Two counters, and every claim about them is a number this suite reads back: they are there, they are
-- readable with profiling DISARMED (the property the whole feature's proof rests on -- a counter you must
-- arm the profiler to see cannot witness what the client does while nobody watches), laying one patch of
-- our own moves both, and no reading of either is ever lower than the one before it, which is what
-- cumulative means.
--
-- Reading the counters and laying a patch are unprotected. The one permission it declares is
-- `client.settings`, and only the disarmed read needs it: the profiling switch is the client's own
-- persisted option, so the suite reads it, disarms it for exactly one read IF it was found armed, and puts
-- back what it was handed -- an arm state the maintainer has to arrange by hand is a missing assertion.

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function run()
  local prof = hafen.client():profiling()

  -- ---- the two keys are there, and they are numbers -------------------------------------------------
  local r0 = prof:render()
  check((type(r0.overlayMeshes) == "number") and (type(r0.overlayOutlines) == "number"),
        "render() answers overlayMeshes and overlayOutlines as numbers",
        tostring(r0.overlayMeshes) .. "/" .. tostring(r0.overlayOutlines))

  -- ---- ...with profiling disarmed ------------------------------------------------------------------
  local c = hafen.client():options():client()
  local was = c:profiling()
  local moved, moveErr = true, nil
  if was then
    moved, moveErr = pcall(function() c:profiling(false) end)   -- borrowed, and given back four lines down
  end
  local armed = c:profiling()
  local rd = prof:render()
  local got = "armed=" .. tostring(armed) .. ", "
              .. type(rd.overlayMeshes) .. "/" .. type(rd.overlayOutlines)
  if was then pcall(function() c:profiling(true) end) end
  check((armed == false) and (type(rd.overlayMeshes) == "number") and (type(rd.overlayOutlines) == "number"),
        "the counters answer numbers with profiling disarmed",
        (not moved) and ("the switch would not move -- is client.settings granted? " .. tostring(moveErr))
          or got)

  -- ---- laying one patch builds cut overlay meshes ---------------------------------------------------
  local s = hafen.session():current()
  local me = s and s:player():gob()
  if not (me and me:position()) then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  local patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- the suite owns its own patches

  local here = me:position()
  local ring = { here:offset(-6, -6), here:offset(6, -6), here:offset(6, 6), here:offset(-6, 6) }
  local before = prof:render()
  local patch = patches:add(ring, here)

  -- The ground under the patch is cut on the client's own tick, so the reading is taken on a bounded
  -- window rather than in this call, and the run is scored over what it reached. Every reading in that
  -- window is compared against the one before it: a cumulative counter never falls, and this watches it
  -- over a stretch in which the client is actively building cuts.
  local tries, fell, low = 0, nil, before
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local r = prof:render()
    if (r.overlayMeshes < low.overlayMeshes) or (r.overlayOutlines < low.overlayOutlines) then
      fell = fell or (low.overlayMeshes .. "/" .. low.overlayOutlines
                      .. " then " .. r.overlayMeshes .. "/" .. r.overlayOutlines)
    end
    low = r
    local rose = (r.overlayMeshes > before.overlayMeshes) and (r.overlayOutlines > before.overlayOutlines)
    if (rose and patch:drawn()) or (tries >= 20) then
      tm:cancel()
      check(r.overlayMeshes > before.overlayMeshes,
            "laying one patch built cut overlay meshes",
            "overlayMeshes " .. before.overlayMeshes .. " -> " .. r.overlayMeshes
              .. " after " .. tries .. " ticks")
      check(r.overlayOutlines > before.overlayOutlines,
            "...and an outline mesh per cut beside each",
            "overlayOutlines " .. before.overlayOutlines .. " -> " .. r.overlayOutlines)
      check(patch:drawn(), "the patch it laid is on the terrain being drawn",
            "not drawn after " .. tries .. " readings")
      local r2 = prof:render()
      check((fell == nil) and (r2.overlayMeshes >= r.overlayMeshes)
            and (r2.overlayOutlines >= r.overlayOutlines),
            "neither counter fell across " .. (tries + 1) .. " readings",
            fell or ("last " .. r2.overlayMeshes .. "/" .. r2.overlayOutlines))
      patches:remove(patch)
      summary()
    end
  end)
end

hafen.console():on("t119", run)   -- the only way in: a suite does not start itself

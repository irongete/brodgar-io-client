-- Example addon (Phase 1b): demonstrates the event bus and timers.
-- `hafen` is the API facade; `ADDON` describes this addon ({ id, dir }).
-- The file body runs once at load; then OnLoad fires, then (on entering the world) OnEnterWorld.

hafen.log("hello loaded (v0.2.0)")

hafen.events.on("OnLoad", function()
  hafen.log("OnLoad fired")
end)

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world")
  local p = hafen.gob.pos("player")
  if p then
    hafen.log(("player at %.1f, %.1f"):format(p.x, p.y))
  end
end)

-- OnUpdate fires every frame; throttle a heartbeat to once every 5 seconds so it is readable.
local acc = 0
hafen.events.on("OnUpdate", function(dt)
  acc = acc + dt
  if acc >= 5 then
    acc = acc - 5
    hafen.log(("tick heartbeat (dt=%.3f s)"):format(dt))
  end
end)

-- Count gob spawns; log only the first few so it does not flood.
local spawned = 0
hafen.events.on("GobAdded", function(g)
  spawned = spawned + 1
  if spawned <= 3 then
    hafen.log(("GobAdded id=%s (%d so far)"):format(tostring(g.id), spawned))
  end
end)

-- One-shot timer: proves the timer wheel fires exactly once, ~2s after load.
hafen.timer.after(2, function()
  hafen.log("timer.after(2) fired once")
end)

hafen.events.on("OnDisable", function()
  hafen.log("OnDisable fired")
end)

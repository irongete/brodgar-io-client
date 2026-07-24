-- Example addon (Phase 1c): adds the Glob-backed read API (hafen.gob.* + hafen.world.*) on top of
-- the Phase 1b event bus and timers. `hafen` is the API facade; `ADDON` describes this addon
-- ({ id, dir }). The file body runs once at load; then OnLoad fires, then (on entering the world)
-- OnEnterWorld.

hafen.log("hello loaded (v0.3.0)")

hafen.events.on("OnLoad", function()
  hafen.log("OnLoad fired")
end)

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world")
  -- Read the player through the canonical per-gob accessor. info() is a full snapshot in one call.
  local me = hafen.gob.info("player")
  if me then
    hafen.log(("player: name=%s hp=%s facing=%.2f at %.1f,%.1f")
      :format(tostring(me.name), tostring(me.hp), me.angle or 0, me.x or 0, me.y or 0))
  end
  -- Enumerate the world: how many gobs are loaded, and what's the nearest thing to me.
  hafen.log(("world has %d gob(s)"):format(hafen.world.count()))
  local near = hafen.world.nearest()
  if near then
    hafen.log(("nearest gob: id=%s name=%s dist=%.1f")
      :format(tostring(near.id), tostring(near.name), hafen.gob.distance(near.id)))
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

-- Count gob spawns; log only the first few so it does not flood. The GobAdded payload is now a full
-- snapshot (same shape as hafen.gob.info), so we can log the gob's type name too.
local spawned = 0
hafen.events.on("GobAdded", function(g)
  spawned = spawned + 1
  if spawned <= 3 then
    hafen.log(("GobAdded id=%s name=%s (%d so far)"):format(tostring(g.id), tostring(g.name), spawned))
  end
end)

-- One-shot timer: proves the timer wheel fires exactly once, ~2s after load.
hafen.timer.after(2, function()
  hafen.log("timer.after(2) fired once")
end)

hafen.events.on("OnDisable", function()
  hafen.log("OnDisable fired")
end)

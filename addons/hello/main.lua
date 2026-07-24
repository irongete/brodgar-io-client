-- Example addon (Phase 1c-2): extends the Glob-backed read API with hafen.map / hafen.player /
-- hafen.time / hafen.sound on top of the 1c-1 gob+world reads, the 1b event bus, and timers.
-- `hafen` is the API facade; `ADDON` describes this addon ({ id, dir }). The file body runs once at
-- load; then OnLoad fires, then (on entering the world) OnEnterWorld.

hafen.log("hello loaded (v0.4.0)")

hafen.events.on("OnLoad", function()
  hafen.log("OnLoad fired")
end)

-- 1c-2: read the map/projection data at the player's position and log it with a tag. The grid, terrain
-- height, and camera for the current spot stream in shortly AFTER entering the world, so right at
-- OnEnterWorld these may be nil (the reads are Loading-guarded); we call this again after a short delay
-- to show them resolve. worldToTile is pure math and always works.
local function readPlace(tag)
  local p = hafen.gob.pos("player")
  if not p then return end
  local tile = hafen.map.tile(p.x, p.y)
  local gp = hafen.map.gridPos()               -- no args = player: the persistent grid anchor
  local t = hafen.map.worldToTile(p.x, p.y)
  local s = hafen.player.worldToScreen(p.x, p.y)
  hafen.log(("[%s] tile=%s height=%s worldToTile=%d,%d"):format(tag,
    tile and (tile.name or tile.id) or "nil", tostring(hafen.map.height(p.x, p.y)), t.x, t.y))
  hafen.log(("[%s] gridPos=%s worldToScreen=%s"):format(tag,
    gp and (gp.gridId .. " @" .. ("%.0f,%.0f"):format(gp.x, gp.y)) or "nil",
    s and ("%.0f,%.0f"):format(s.x, s.y) or "nil"))
end

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world")

  -- 1c-1: read the player through the canonical per-gob accessor. info() is a full snapshot. NB: hp is
  -- nil for the player — GobHealth is object integrity, not the player's vitals (those land in 1d).
  local me = hafen.gob.info("player")
  if me then
    hafen.log(("player gob: name=%s hp=%s at %.1f,%.1f")
      :format(tostring(me.name), tostring(me.hp), me.x or 0, me.y or 0))
  end
  hafen.log(("world has %d gob(s)"):format(hafen.world.count()))
  local near = hafen.world.nearest()
  if near then
    hafen.log(("nearest gob: id=%s name=%s dist=%.1f")
      :format(tostring(near.id), tostring(near.name), hafen.gob.distance(near.id)))
  end

  -- 1c-2: player identity (data with no per-gob equivalent — the local character name).
  hafen.log(("player: exists=%s id=%s name=%s")
    :format(tostring(hafen.player.exists()), tostring(hafen.player.id()),
            tostring(hafen.player.name())))

  -- 1c-2: time + astronomy (astronomy readers are nil until the first astro update).
  hafen.log(("time: clock=%.1f day=%s night=%s season=%s moon=%s")
    :format(hafen.time.clock() or 0, tostring(hafen.time.dayFraction()),
            tostring(hafen.time.isNight()), tostring(hafen.time.season()),
            tostring(hafen.time.moon())))

  -- 1c-2: map + projection reads — now (often still loading) and again after 3s (resolved).
  readPlace("now")
  hafen.timer.after(3, function() readPlace("+3s") end)

  -- 1c-2: an audible confirmation ping (a client-bundled sound), proving hafen.sound.play works.
  hafen.sound.play("sfx/msg")
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

-- Count gob spawns; log only the first few so it does not flood. The GobAdded payload is a full
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

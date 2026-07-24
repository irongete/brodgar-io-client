-- Example addon (Phase 1d-1): adds hafen.player.vitals() + the VitalsChanged event — the first
-- surface backed by the widget-tree read mechanism (Locator + Adapter + inbound-uimsg hook) — on top
-- of the 1c items/char/party reads, the gob/world/map/player/time/sound reads, the 1b event bus, and
-- timers. `hafen` is the API facade; `ADDON` describes this addon ({ id, dir }). The file body runs
-- once at load; then OnLoad fires, then (on entering the world) OnEnterWorld.

hafen.log("hello loaded (v0.6.0)")

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

-- 1c-3: read the inventory / equipment / cursor through hafen.items. Item NAMES come from resolved
-- item info, which (like the inventory widget itself) can stream in a beat after enter-world, so this
-- is read twice — immediately and after a short delay — the same pattern as the map reads above.
local function readInv(tag)
  local inv = hafen.items.inventory()   -- array of Item snapshots {name,res,num,wear,pos}
  local eq = hafen.items.equipment()    -- array of Item snapshots {..., slot}
  local hand = hafen.items.hand()       -- Item snapshot or nil (cursor item)
  local first = inv[1]
  hafen.log(("[%s] inventory=%d item(s), first=%s x%s")
    :format(tag, #inv, first and tostring(first.name or first.res) or "nil",
            first and tostring(first.num or 1) or "-"))
  hafen.log(("[%s] equipment=%d slot(s), hand=%s")
    :format(tag, #eq, hand and tostring(hand.name or hand.res) or "empty"))
end

-- 1c-3: character attributes + learning points + weight, and the party size. Like items and map, the
-- char data (Glob cattrs, CharWnd.exp/enc) STREAMS IN a beat after enter-world, so this too is read at
-- OnEnterWorld (often still nil) and again after the delay (resolved).
local function readChar(tag)
  local str = hafen.char.attr("str")   -- {base, comp} or nil
  hafen.log(("[%s] char: str=%s lp=%s weight=%s"):format(tag,
    str and (str.base .. "/" .. str.comp) or "nil",
    tostring(hafen.char.lp()), tostring(hafen.char.weight())))
  hafen.log(("[%s] party: %d member(s)"):format(tag, #hafen.party.members()))
end

-- 1d-1: player vitals — hp/stamina/energy as 0..1 bar fractions (no absolute numbers exist). Read
-- through the widget-tree mechanism (the HUD meters). The meters stream in a beat after enter-world
-- (like char/items), so the "now" pass is usually nil and "+3s" has the bars.
local function readVitals(tag)
  local v = hafen.player.vitals()
  if v then
    hafen.log(("[%s] vitals: hp=%s stamina=%s energy=%s"):format(tag,
      tostring(v.hp), tostring(v.stamina), tostring(v.energy)))
  else
    hafen.log(("[%s] vitals: nil (meters not up yet)"):format(tag))
  end
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

  -- 1c-2/1c-3: map, projection, item and char/party reads — now (often still loading/streaming) and
  -- again after 3s (resolved). char attrs, lp/weight and the inventory all stream in shortly AFTER
  -- enter-world (same as the map data), so the "now" pass typically shows nil/0 and "+3s" the real data.
  readPlace("now"); readInv("now"); readChar("now"); readVitals("now")
  hafen.timer.after(3, function()
    readPlace("+3s"); readInv("+3s"); readChar("+3s"); readVitals("+3s")
  end)

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

-- 1d-1: VitalsChanged fires when the server updates a vital bar (stamina drain, energy change,
-- taking damage) — the payload is the same {hp,stamina,energy} snapshot as hafen.player.vitals().
-- Stamina/energy change often, so log only the first few to avoid flooding.
local vitalsSeen = 0
hafen.events.on("VitalsChanged", function(v)
  vitalsSeen = vitalsSeen + 1
  if vitalsSeen <= 5 then
    hafen.log(("VitalsChanged: hp=%s stamina=%s energy=%s (%d)"):format(
      tostring(v.hp), tostring(v.stamina), tostring(v.energy), vitalsSeen))
  end
end)

-- One-shot timer: proves the timer wheel fires exactly once, ~2s after load.
hafen.timer.after(2, function()
  hafen.log("timer.after(2) fired once")
end)

hafen.events.on("OnDisable", function()
  hafen.log("OnDisable fired")
end)

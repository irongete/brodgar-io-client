-- Minimal example addon (Phase 1a). Runs once when the addon is loaded.
-- `hafen` is the API facade; `ADDON` describes this addon ({ id, dir }).

hafen.log("hello from the '" .. ADDON.id .. "' addon (v0.1.0)")

local p = hafen.gob.pos("player")
if p then
  hafen.log(("player is at %.1f, %.1f"):format(p.x, p.y))
else
  hafen.log("no player yet (loaded before entering the world)")
end

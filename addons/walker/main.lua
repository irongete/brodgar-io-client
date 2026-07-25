-- Walker — the Phase-4b WRITE-ACTIONS demo addon (dormant, opt-in).
--
-- It DECLARES "permissions": ["actions"] in its manifest, so under D-027 it is DISABLED BY DEFAULT when first
-- discovered (write addons are opt-in per addon), and it does NOT load at all while the global master switch
-- ("Allow addon actions (writes)" in Options > AddOns) is OFF. Because it only ever loads when that switch is ON
-- AND it declared the permission, hafen.act.enabled() is always true here — the clean place to exercise the gated
-- write tier without gating the always-on read-only `hello` regression harness (`hello` declares no permissions,
-- so it loads regardless of the switch). hafen.act is the ONE part of hafen.* that DRIVES the character: it sends
-- player-action wdgmsgs to the server (everything else only observes). It stays server-authoritative — an addon
-- can only send what a player click could send; the permission exists so YOU control which addons act for you.

hafen.log("walker loaded (v0.1.0) -- the write-actions demo")

-- At login, confirm we're granted (we only load when the master switch is on and we declared the permission).
hafen.events.on("OnEnterWorld", function()
  hafen.log(("walker: write-actions %s -- run  :walker  to walk ~2 tiles south (hafen.act.moveTo)")
    :format(hafen.act.enabled() and "GRANTED" or "NOT granted"))
end)

-- :walker -- deliberately walk ~2 tiles south via the gated hafen.act.moveTo verb (the Phase-4 click-macro).
hafen.slash.register("walker", function()
  if not hafen.act.enabled() then                    -- gated: no-op with a hint if somehow not granted
    hafen.log(":walker -> write-actions not granted. Turn on \"Allow addon actions (writes)\" in Options > AddOns.")
    return
  end
  local p = hafen.gob.pos("player")
  if not p then hafen.log(":walker -> no player position yet"); return end
  local dx, dy = p.x, p.y + 22                        -- 22 world units ~ 2 tiles (tilesz = 11) to the south
  hafen.act.moveTo(dx, dy)                            -- exactly the MapView "click" a left-click on that spot sends
  hafen.log((":walker -> moveTo(%.1f, %.1f)  [~2 tiles south of you -- watch your character walk]"):format(dx, dy))
end)

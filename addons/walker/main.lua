-- Walker — the WRITE-ACTIONS demo addon (dormant, opt-in).
--
-- It DECLARES "permissions": ["actions"] in its manifest, so it is DISABLED BY DEFAULT when first discovered
-- (write-actions are a PER-ADDON permission, opt-in per addon — D-027/D-028). To use it, enable it in
-- Options > AddOns: because it can act on your behalf, the panel asks you to CONFIRM first (the consent dialog,
-- slice 4c). Once you enable it and Reload UI it loads like any addon, and hafen.act.enabled() is true here
-- (it declared the permission). There is NO global switch (D-028): the permission is granted purely by YOUR
-- enabling this one addon. hafen.act is the ONE part of hafen.* that DRIVES the character: it sends
-- player-action wdgmsgs to the server (everything else only observes). It stays server-authoritative — an addon
-- can only send what a player click could send; the permission exists so YOU control which addons act for you.
-- Kept SEPARATE from the always-on read-only `hello` regression harness (which declares no permissions).

hafen.log("walker loaded (v0.2.0) -- the write-actions demo")

-- At login, confirm we're granted (we only load once YOU enabled us, and we declared the permission).
hafen.events.on("OnEnterWorld", function()
  hafen.log(("walker: write-actions %s -- run  :walker  to walk ~2 tiles south (hafen.act.moveTo)")
    :format(hafen.act.enabled() and "GRANTED" or "NOT granted"))
end)

-- :walker -- deliberately walk ~2 tiles south via the gated hafen.act.moveTo verb (the Phase-4 click-macro).
hafen.slash.register("walker", function()
  if not hafen.act.enabled() then                    -- gated: no-op with a hint if somehow not granted
    hafen.log(":walker -> write-actions not granted (this addon did not declare the \"actions\" permission).")
    return
  end
  local p = hafen.gob.pos("player")
  if not p then hafen.log(":walker -> no player position yet"); return end
  local dx, dy = p.x, p.y + 22                        -- 22 world units ~ 2 tiles (tilesz = 11) to the south
  hafen.act.moveTo(dx, dy)                            -- exactly the MapView "click" a left-click on that spot sends
  hafen.log((":walker -> moveTo(%.1f, %.1f)  [~2 tiles south of you -- watch your character walk]"):format(dx, dy))
end)

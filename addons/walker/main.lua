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
--
-- Slice 4d adds the rest of the MapView action verbs on top of moveTo (4a). Each is a DELIBERATE, opt-in
-- trigger — a `:walker <sub>` command — so nothing acts unless you ask. Sub-commands:
--   :walker walk    -- moveTo: walk ~2 tiles south  (the original 4a demo)
--   :walker click   -- clickGob: RIGHT-click the nearest object (opens its context menu — safe/cancelable)
--   :walker use     -- useItemOn: use the item on your cursor on the ground under you (no-op if empty-handed)
--   :walker sel     -- select: area-select the ~3x3 tiles around you (drives tile-area tools)
--   :walker place   -- place: drop the object on your cursor at your feet facing north (no-op if not placing)
--   :walker raw     -- raw: send the same walk "click" straight to the MapView (the escape hatch == moveTo)

hafen.log("walker loaded (v0.3.0) -- the write-actions demo (moveTo + the 4d MapView verbs)")

-- At login, confirm we're granted (we only load once YOU enabled us, and we declared the permission).
hafen.events.on("OnEnterWorld", function()
  hafen.log(("walker: write-actions %s -- run  :walker  for the list of action demos")
    :format(hafen.act.enabled() and "GRANTED" or "NOT granted"))
end)

local SOUTH = 22   -- world units ~ 2 tiles (tilesz = 11) to the south

-- One command with sub-verbs, each exercising one gated hafen.act.* MapView verb.
hafen.slash.register("walker", function(args)
  local sub = args[1] or "help"

  if sub == "help" then
    hafen.log(":walker sub-commands -> walk | click | use | sel | place | raw   (each is a gated hafen.act verb)")
    hafen.log("   walk=moveTo  click=clickGob(right)  use=useItemOn  sel=select  place=place  raw=raw escape hatch")
    return
  end

  -- Every verb is gated: bail with a clear hint if this addon somehow isn't granted (it declared the perm,
  -- so this only trips if you edited the manifest). enabled() never throws, so no pcall is needed.
  if not hafen.act.enabled() then
    hafen.log((":walker %s -> write-actions not granted (enable this addon + confirm the consent dialog)."):format(sub))
    return
  end

  local p = hafen.gob.pos("player")
  if not p then hafen.log(":walker -> no player position yet"); return end

  if sub == "walk" then
    hafen.act.moveTo(p.x, p.y + SOUTH)                 -- the MapView "click" a left-click on that spot sends
    hafen.log((":walker walk -> moveTo(%.1f, %.1f)  [~2 tiles south -- watch your character walk]"):format(p.x, p.y + SOUTH))

  elseif sub == "click" then
    local g = hafen.world.nearest(function(o) return not o.isplayer end)   -- nearest non-player object
    if not g then hafen.log(":walker click -> no object nearby"); return end
    hafen.act.clickGob(g.id, 3)                         -- button 3 = RIGHT-click => its context menu (safe/cancelable)
    hafen.log((":walker click -> right-clicked %s (id %d) -- its context menu should open"):format(g.name or "?", g.id))

  elseif sub == "use" then
    hafen.act.useItemOn(p.x, p.y)                       -- apply the cursor item to the ground under you
    hafen.log(":walker use -> useItemOn at your feet (hold something on your cursor first, else the server ignores it)")

  elseif sub == "sel" then
    hafen.act.select(p.x - 11, p.y - 11, p.x + 11, p.y + 11)   -- ~3x3 tiles centred on you
    hafen.log(":walker sel -> area-selected the ~3x3 tiles around you (visible only with a tile-area tool active)")

  elseif sub == "place" then
    hafen.act.place(p.x, p.y, 0)                        -- angle 0 rad = north; no-op unless something is on your cursor
    hafen.log(":walker place -> place at your feet facing north (start building something first, else nothing happens)")

  elseif sub == "raw" then
    -- The escape hatch: send the walk "click" straight to the MapView, building the wire args by hand.
    -- Server units = world * 1024/11 (posres = 11/1024 world-units per server-unit), floored — exactly what
    -- moveTo does internally. So this walks ~2 tiles south too, proving raw(target, msg, ...) reaches the widget.
    local su = 1024 / 11
    local mc = { x = math.floor(p.x * su), y = math.floor((p.y + SOUTH) * su) }
    hafen.act.raw("mapview", "click", { x = 0, y = 0 }, mc, 1, 0)
    hafen.log((":walker raw -> raw('mapview','click', pc, {x=%d,y=%d}, 1, 0)  [== moveTo ~2 tiles south]"):format(mc.x, mc.y))

  else
    hafen.log((":walker -> unknown sub-command '%s'  (try  :walker help)"):format(sub))
  end
end)

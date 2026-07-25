-- Planner (V4): the LAYOUT + GRID-ANCHORED PERSISTENCE example for client-only world ghosts (hafen.ghost).
--
-- It is a small city/base PLANNER: place translucent "blueprint" ghosts over the real terrain, CLICK them to
-- select (V2), rotate/remove them, and — the point of V4 — SAVE the layout so it comes back at the SAME spot
-- after a relog. Ghosts are SAFE-tier (a Gob with no server id, D-029): nothing ever reaches the server, so this
-- addon declares NO permissions and is default-enabled, exactly like `hello`. It is kept SEPARATE from `hello`
-- (which stays the read-only regression harness) as the dedicated V-series example (spec 16-virtual-entities §8).
--
-- WHY GRID ANCHORING. World coords (gob.rc, ghost x/y) are LOGIN-RELATIVE: the origin is re-randomized every
-- login, so a raw (x,y) saved this session points somewhere else next session. The persistent anchor is the GRID
-- ID — a stable 64-bit id, the same for every session and every player (see the hafen-positioning rule). So we
-- save each ghost as hafen.map.gridPos(x,y) => {gridId, x, y} (grid id + within-grid offset) and, on load,
-- re-resolve it with the V4 inverse hafen.map.fromGridPos(anchor) => {x,y} world (nil until that grid streams in
-- — we retry as the map loads). This is the same rule map markers follow (coverage-gaps C4).
--
-- COMMANDS (:planner <sub>):
--   place [name|res]  -- place the current (or named) blueprint at your feet; it is clickable + persisted
--   blueprint [name]  -- show / set the current blueprint (bare = list the palette)
--   list              -- list placed ghosts (index, resource, grid id, resolved?)
--   select <n>        -- select ghost #n (or just CLICK it in the world — V2)
--   gizmo             -- attach the Unity-style transform gizmo; DRAG the ghost BY ITS ARROWS (X/Y) or centre (V5b)
--   grab              -- MOVE the selected ghost by the BODY: follows the cursor snapped to the placegrid (V5a)
--   rotate [deg]      -- rotate the selected ghost (default +45 deg); the new facing persists
--   remove [n]        -- remove the selected ghost (or #n)
--   clear             -- remove every ghost + wipe the saved layout
--   save              -- force a flush now (also autosaved + flushed on relog)
-- DoD: place several, click to select, relog -> they reload at the same grid position (rotation preserved too).
--
-- V5 (grab / move): ":planner grab" starts a drag of the SELECTED ghost. The engine's own placement primitives are
-- reused so it feels IDENTICAL to placing a building: each mouse move raycasts the ground under the cursor
-- (hafen.map.screenToWorld) and snaps it to the client's :placegrid (hafen.map.snapPlace) -- tile centre by default,
-- SHIFT = the fine sub-tile grid (D-033). The mouse is captured (hafen.hook.grab) so the CAMERA STAYS PUT while you
-- drag; a CLICK drops it (re-anchored to the new grid + persisted). This is the "drag the body" move-mode.
--
-- V5b (gizmo / "by its arrows"): ":planner gizmo" attaches a Unity-style TRANSFORM GIZMO (gizmo.lua, a bundled Lua
-- library over the V5a primitives -- D-031) to the selected ghost: RED = world-X, GREEN = world-Y arrows + a yellow
-- free centre, drawn with g:draw() (NO game resources -- the arrow-heads are FILLED TRIANGLES via the new g:poly).
-- Press an arrow to drag the ghost ALONG THAT AXIS (the centre = free), snapped to the :placegrid (SHIFT = fine),
-- camera fixed. The arrows re-project each frame so they track the ghost + the camera. See gizmo.lua for the mechanism.

hafen.log("planner loaded (v0.3.0) -- blueprint ghosts + :planner gizmo (Unity-style, drag by its arrows) / grab, grid-anchored")

-- The blueprint palette. Keys are short names for ':planner place <name>'; values are client resource paths.
-- logcabin + timberhouse are verified to resolve in-game (V3); ':planner place <res-path>' also takes any raw path.
local PALETTE = {
  cabin  = "gfx/terobjs/arch/logcabin",
  timber = "gfx/terobjs/arch/timberhouse",
}
local DEFAULT_BP = "cabin"

-- Two looks so selection is visible: idle = bluish tint; sel = brighter warm highlight. OPAQUE (alpha 1) so the
-- blueprint ghosts read clearly against the terrain — the translucent look was too faint to see (maintainer note).
local LOOK = {
  idle = { alpha = 1.0, tint = { r = 120, g = 180, b = 255, a = 120 } },
  sel  = { alpha = 1.0, tint = { r = 255, g = 225, b = 110, a = 170 } },
}

-- Runtime state. `items` is the single source of truth: each record = { res, a, anchor = {gridId, x, y}, ghost }.
-- `ghost` is the live handle (nil while its grid has not streamed in yet). `blueprint` is the current palette key,
-- `selected` the selected record (or nil). Module-level, so a :reload starts clean and OnEnterWorld repopulates.
local items     = {}
local blueprint = DEFAULT_BP
local selected  = nil
local retry     = nil   -- the re-resolve retry timer handle while ghosts are still streaming in (nil = idle)
local drag      = nil   -- V5: the active move-drag { it, grab, pending }, or nil when not dragging
local activeGizmo = nil -- V5b: the transform gizmo attached to the selected ghost (from gizmo.lua), or nil

-- Detach the active gizmo, if any (idempotent). Called whenever its target (the selection) changes or goes away.
local function detachGizmo()
  if activeGizmo then activeGizmo:detach(); activeGizmo = nil end
end

local function shortRes(res) return (tostring(res):gsub("^.*/", "")) end

-- The palette names, sorted, as a "cabin/timber" string for help/usage text.
local function paletteNames()
  local ks = {}
  for k in pairs(PALETTE) do ks[#ks + 1] = k end
  table.sort(ks)
  return table.concat(ks, "/")
end

-- Resolve a blueprint argument: a palette key -> its resource; anything with "/" -> a raw resource path; nil ->
-- the current blueprint. Returns the resource string, or nil for an unknown palette name.
local function resolveBlueprint(arg)
  if not arg then return PALETTE[blueprint] or PALETTE[DEFAULT_BP] end
  if PALETTE[arg] then return PALETTE[arg] end
  if arg:find("/") then return arg end                     -- a raw resource path (advanced)
  return nil
end

-- The 1-based index of a record in `items` (records are compared by identity, stable across reorders), or -1.
local function indexOf(it)
  for i, r in ipairs(items) do
    if r == it then return i end
  end
  return -1
end

-- Apply the ghost's look for its current selection state (no-op while it has not streamed in yet).
local function applyLook(it)
  if not it.ghost then return end
  local look = (it == selected) and LOOK.sel or LOOK.idle
  it.ghost:alpha(look.alpha):tint(look.tint)
end

-- Select a record (or nil to clear): de-highlight the old one, highlight the new one, and log it.
local function selectItem(it)
  if selected == it then return end
  detachGizmo()                      -- the gizmo was on the previous selection; drop it when selection changes
  local prev = selected
  selected = it
  if prev then applyLook(prev) end
  if it then
    applyLook(it)
    hafen.log((":planner selected #%d %s -- :planner rotate | remove"):format(indexOf(it), shortRes(it.res)))
  end
end

-- Create the live ghost for a record at world (wx, wy). It is CLICKABLE (V2) so a click selects it; the onClick
-- closes over the RECORD, so it always selects the right one even after the list is reordered by a remove.
local function spawn(it, wx, wy)
  it.ghost = hafen.ghost.new{
    res = it.res, x = wx, y = wy, a = it.a,
    alpha = LOOK.idle.alpha, tint = LOOK.idle.tint,
    clickable = true,
    onClick = function(g, button) selectItem(it) end,
  }
  applyLook(it)                                            -- keep the highlight if this record is the selected one
  return it.ghost
end

-- Write the layout to the per-char store (JSON). Only the serializable fields (res/a/anchor) are stored — the live
-- ghost handle stays out of it. Autosave + relog also flush; we flush on every edit so an unclean exit keeps it.
local function persist()
  local out = {}
  for i, it in ipairs(items) do
    out[i] = {
      res = it.res, a = it.a,
      anchor = { gridId = it.anchor.gridId, x = it.anchor.x, y = it.anchor.y },
    }
  end
  hafen.store.layout.items = out
  hafen.store.layout.blueprint = blueprint
  hafen.store.flush()
end

-- Re-resolve every still-pending saved ghost (grid id -> current world coord) and spawn it. Returns how many are
-- still pending (their grid is not loaded here yet). Called at login and by the retry timer as the map streams in.
local function resolvePending()
  local pending = 0
  for _, it in ipairs(items) do
    if not it.ghost then
      local w = hafen.map.fromGridPos(it.anchor)           -- {x,y} world, or nil if that grid is not loaded yet
      if w then
        spawn(it, w.x, w.y)
      else
        pending = pending + 1
      end
    end
  end
  return pending
end

-- V5: end the active move-drag (drop the ghost where it is). Releases the mouse grab, re-anchors the record to the
-- ghost's new grid position, and persists. Idempotent + safe to call with no active drag. The grab's own mouse-up
-- also calls this; calling it again (e.g. a second ":planner grab") just stops the drag cleanly.
local function commitDrag()
  local d = drag
  if not d then return end
  drag = nil
  if d.grab then d.grab:release() end                      -- idempotent (the up handler may have released already)
  local it = d.it
  if it and it.ghost then
    local p = it.ghost:pos()                                -- {x,y,a} -- the snapped drop position
    local anchor = hafen.map.gridPos(p.x, p.y)              -- re-anchor to the grid it now sits on (persistent id)
    if anchor then it.anchor = anchor end
    persist()
    hafen.log((":planner grab -> dropped #%d at %.0f,%.0f (grid %s, persisted)")
      :format(indexOf(it), p.x, p.y, it.anchor.gridId))
  end
end

-- At login the per-char store is already restored (1e), so store.layout is ready here. Rebuild `items` from it and
-- re-resolve each grid anchor to a world coord, retrying for a few seconds while the map around us streams in.
hafen.events.on("OnEnterWorld", function()
  if retry then retry:cancel(); retry = nil end            -- guard against a re-entry (relog/:reload re-fires this)
  if drag then commitDrag() end                            -- V5: never carry a half-finished drag across a relog
  detachGizmo()                                            -- V5b: drop any gizmo before rebuilding the layout
  selected = nil
  items = {}
  blueprint = hafen.store.layout.blueprint or DEFAULT_BP
  for _, s in ipairs(hafen.store.layout.items or {}) do
    if s.res and s.anchor and s.anchor.gridId then          -- skip a malformed record rather than crash the load
      items[#items + 1] = {
        res = s.res, a = s.a or 0,
        anchor = { gridId = s.anchor.gridId, x = s.anchor.x or 0, y = s.anchor.y or 0 },
      }
    end
  end
  if #items == 0 then
    hafen.log("planner: no saved layout -- ':planner place' to start, ':planner' for help")
    return
  end
  local pending = resolvePending()
  hafen.log(("planner: loading layout -- %d placed, %d pending (grid streaming in)"):format(#items - pending, pending))
  if pending > 0 then
    local tries = 0
    retry = hafen.timer.every(1, function()                -- retry as grids load; give up after ~15s (far away)
      tries = tries + 1
      local left = resolvePending()
      if (left == 0) or (tries >= 15) then
        if retry then retry:cancel(); retry = nil end
        local placed = 0
        for _, it in ipairs(items) do if it.ghost then placed = placed + 1 end end
        hafen.log(("planner: layout resolved -- %d/%d ghost(s) placed%s"):format(placed, #items,
          (left > 0) and (" (" .. left .. " out of loaded range -- kept for a closer login)") or ""))
      end
    end)
  end
end)

-- V2: a click on a clickable ghost is detected CLIENT-SIDE and CONSUMED before any server click (no walk/interact,
-- nothing reaches the server -- still SAFE-tier). The per-ghost onClick above does the selecting; this owner-scoped
-- event just logs the world point, mirroring `hello` (both fire on the same click).
hafen.events.on("GhostClicked", function(ev)
  hafen.log((":planner GhostClicked -> ghost at %.0f,%.0f (button %d) -- client-only, no server click sent")
    :format(ev.x, ev.y, ev.button))
end)

hafen.events.on("OnDisable", function()
  detachGizmo()                                            -- V5b: (the bridge tears down the overlay/hooks too)
  hafen.log("planner: OnDisable -- ghosts torn down + layout flushed on teardown (grid-anchored, so a relog restores them)")
end)

-- A11-style slash command: WoW-style ":planner <sub>" with args as a 1-based table. Reload-safe (a single
-- engine-lifetime dispatcher routes to the current handler), so editing this file + :reload swaps it cleanly.
hafen.slash.register("planner", function(args)
  local sub = args[1] or "help"

  if (sub == "help") or (sub == "") then
    hafen.log(":planner -> place | blueprint | list | select | gizmo | grab | rotate | remove | clear | save")
    hafen.log("   place [name|res] = drop the current/named blueprint at your feet (clickable + saved)")
    hafen.log("   blueprint [name] = show/set the blueprint (bare = list palette); list = show placed ghosts")
    hafen.log("   select <n> = select #n (or CLICK a ghost)")
    hafen.log("   gizmo = attach the Unity-style transform gizmo; drag the ghost BY ITS ARROWS (X/Y axis) or centre (free). SHIFT=fine")
    hafen.log("   grab = move it by the BODY with the mouse (placegrid-snapped, CLICK to drop) -- V5a")
    hafen.log("   rotate [deg] = turn the selected one; remove [n]; clear; save")

  elseif sub == "place" then
    local p = hafen.gob.pos("player")
    if not p then hafen.log(":planner place -> no player position yet"); return end
    local res = resolveBlueprint(args[2])
    if not res then
      hafen.log((":planner place -> unknown blueprint '%s' (palette: %s, or a raw res path)"):format(tostring(args[2]), paletteNames()))
      return
    end
    local anchor = hafen.map.gridPos(p.x, p.y)             -- {gridId, x, y} -- the persistent anchor
    if not anchor then hafen.log(":planner place -> no map grid loaded here yet; move a moment and retry"); return end
    local it = { res = res, a = 0, anchor = anchor }
    items[#items + 1] = it
    spawn(it, p.x, p.y)
    persist()
    selectItem(it)                                          -- auto-select the freshly placed ghost
    hafen.log((":planner place -> %s at grid %s (#%d, %d total) -- click to select; relog to test persistence")
      :format(shortRes(res), anchor.gridId, indexOf(it), #items))

  elseif sub == "blueprint" then
    if not args[2] then
      hafen.log((":planner blueprint -> current = %s (%s). palette: %s")
        :format(blueprint, shortRes(PALETTE[blueprint] or "?"), paletteNames()))
      return
    end
    if PALETTE[args[2]] then
      blueprint = args[2]; persist()
      hafen.log((":planner blueprint -> now %s (%s)"):format(blueprint, shortRes(PALETTE[blueprint])))
    else
      hafen.log((":planner blueprint -> unknown '%s' (palette: %s). Or ':planner place <res-path>' for a raw resource.")
        :format(args[2], paletteNames()))
    end

  elseif sub == "list" then
    if #items == 0 then hafen.log(":planner list -> empty (':planner place' to add one)"); return end
    hafen.log((":planner list -> %d ghost(s):"):format(#items))
    for i, it in ipairs(items) do
      hafen.log(("   #%d %s  grid=%s  %s%s"):format(i, shortRes(it.res), it.anchor.gridId,
        it.ghost and "placed" or "pending", (it == selected) and "  [selected]" or ""))
    end

  elseif sub == "select" then
    local n = tonumber(args[2])
    if not n or not items[n] then hafen.log(":planner select <n> -> a valid index is required (see :planner list)"); return end
    selectItem(items[n])

  elseif sub == "grab" then
    -- V5: move the selected ghost with the mouse, snapping like a real building placement (D-033).
    if drag then commitDrag(); return end                  -- toggle: a second :planner grab drops the current one
    if not selected then hafen.log(":planner grab -> nothing selected (click a ghost or :planner select <n>)"); return end
    if not selected.ghost then hafen.log(":planner grab -> that ghost has not streamed in yet; try again in a moment"); return end
    detachGizmo()                                           -- V5b: the body-grab and the gizmo are mutually exclusive
    local it = selected
    drag = { it = it, pending = false }
    drag.grab = hafen.hook.grab{
      -- Each mouse move: raycast the ground under the cursor (async) -> snap to the placegrid -> move the ghost.
      -- `pending` coalesces so at most one raycast is in flight (one per frame, like the client's own placement).
      move = function(sx, sy, mods)
        if not drag or drag.pending then return end
        drag.pending = true
        local fine = mods.shift                            -- SHIFT = the fine sub-tile placegrid (D-033)
        hafen.map.screenToWorld(sx, sy, function(w)
          if not drag then return end                      -- released mid-flight
          drag.pending = false
          if not w then return end                         -- cursor hit no terrain (sky/off-map)
          local s = hafen.map.snapPlace(w.x, w.y, fine)
          if it.ghost then it.ghost:move(s.x, s.y, it.a) end   -- keep facing; :move is snapped
        end)
      end,
      -- Mouse-up (the click that drops it): commit + persist + release.
      up = function() commitDrag() end,
    }
    hafen.log((":planner grab -> moving #%d: cursor drags it (placegrid=%s, SHIFT=fine); CLICK to drop. Camera stays put.")
      :format(indexOf(it), tostring(hafen.map.placeGrid())))

  elseif sub == "gizmo" then
    -- V5b: attach the Unity-style TRANSFORM GIZMO (gizmo.lua) to the selected ghost -- a toggle. Then DRAG the
    -- ghost BY ITS ARROWS: the red arrow locks to world-X, the green to world-Y, the yellow centre is a free
    -- ground-plane move. It snaps to the :placegrid (SHIFT = fine) and the camera stays put -- the V5b DoD.
    if activeGizmo then detachGizmo(); hafen.log(":planner gizmo -> detached"); return end
    if not selected then hafen.log(":planner gizmo -> nothing selected (click a ghost or :planner select <n>)"); return end
    if not selected.ghost then hafen.log(":planner gizmo -> that ghost has not streamed in yet; try again in a moment"); return end
    if drag then commitDrag() end                          -- exclusive with the V5a body-grab
    local it = selected
    activeGizmo = gizmo(it.ghost, {                         -- calls the constructor from gizmo.lua (loaded first)
      -- On release: re-anchor the record to the grid it now sits on (the persistent id) + persist, exactly like
      -- the V5a body-drag's commitDrag. Closed over the RECORD so a later reorder can't mis-target it.
      onCommit = function(p)
        if (it ~= selected) or (not it.ghost) then return end
        local anchor = hafen.map.gridPos(p.x, p.y)
        if anchor then it.anchor = anchor end
        persist()
        hafen.log((":planner gizmo -> dropped #%d at %.0f,%.0f (grid %s, persisted)")
          :format(indexOf(it), p.x, p.y, it.anchor.gridId))
      end,
    })
    hafen.log((":planner gizmo -> attached to #%d: drag the RED(X)/GREEN(Y) arrows or the yellow centre (free); SHIFT=fine; camera stays. ':planner gizmo' again to detach.")
      :format(indexOf(it)))

  elseif sub == "rotate" then
    if not selected then hafen.log(":planner rotate -> nothing selected (click a ghost or :planner select <n>)"); return end
    local deg = tonumber(args[2]) or 45
    selected.a = (selected.a + math.rad(deg)) % (2 * math.pi)
    if selected.ghost then selected.ghost:rotate(selected.a) end
    persist()
    hafen.log((":planner rotate -> #%d now a=%.2f rad (+%d deg, persisted)"):format(indexOf(selected), selected.a, deg))

  elseif sub == "remove" then
    local it = selected
    if args[2] then                                        -- an explicit index must be valid (no silent fallback)
      it = items[tonumber(args[2]) or 0]
      if not it then hafen.log((":planner remove -> no ghost #%s (see :planner list)"):format(tostring(args[2]))); return end
    end
    if not it then hafen.log(":planner remove [n] -> nothing selected and no index given"); return end
    local idx = indexOf(it)
    if selected == it then selected = nil; detachGizmo() end  -- V5b: drop the gizmo before its target ghost dies
    if it.ghost then it.ghost:destroy() end
    table.remove(items, idx)
    persist()
    hafen.log((":planner remove -> removed #%d %s (%d left)"):format(idx, shortRes(it.res), #items))

  elseif sub == "clear" then
    detachGizmo()                                          -- V5b: drop the gizmo before its target ghost dies
    for _, it in ipairs(items) do if it.ghost then it.ghost:destroy() end end
    items = {}; selected = nil
    persist()
    hafen.log(":planner clear -> removed all ghosts + wiped the saved layout")

  elseif sub == "save" then
    persist()
    hafen.log((":planner save -> flushed %d ghost(s) to the per-char store"):format(#items))

  else
    hafen.log((":planner -> unknown sub-command '%s' (try :planner help)"):format(sub))
  end
end)
hafen.log("planner: ':planner' registered -- type it in the console (chat). Ghosts reload grid-anchored after a relog.")

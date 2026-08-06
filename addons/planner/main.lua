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
-- save each ghost as hafen.world():position(x, y):info() => {gridId, x, y} (grid id + within-grid offset) and, on
-- load, rebuild it with hafen.world():position(anchor), whose :x()/:y() are nil until that grid is reachable here
-- — we retry as the map loads. This is the same rule map markers follow (coverage-gaps C4).
--
-- COMMANDS (:planner <sub>):
--   place [name|res]  -- place the current (or named) blueprint GHOST at your feet; it is clickable + persisted
--   sprite [billboard]-- place a custom-PNG SPRITE at your feet (R2b): fixed upright quad, or 'billboard' = camera-facing
--   object            -- place a custom glTF MODEL (cube.glb) at your feet (R3b-2): clickable + gizmo-driven + persisted
--   blueprint [name]  -- show / set the current blueprint (bare = list the palette)
--   list              -- list placed entities (index, label, grid id, resolved?)
--   select <n>        -- select entity #n (or CLICK a ghost / fixed sprite in the world — V2)
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
-- (hafen.world():screenToWorld) and snaps it to the client's :placegrid (hafen.world():snapPlace) -- tile centre by default,
-- SHIFT = the fine sub-tile grid (D-033). The mouse is captured (hafen.hook():grab) so the CAMERA STAYS PUT while you
-- drag; a CLICK drops it (re-anchored to the new grid + persisted). This is the "drag the body" move-mode.
--
-- V5b (gizmo / "by its arrows"): ":planner gizmo" attaches a Unity-style TRANSFORM GIZMO (gizmo.lua, a bundled Lua
-- library over the V5a primitives -- D-031) to the selected ghost: RED = world-X, GREEN = world-Y arrows + a yellow
-- free centre, drawn with g:draw() (NO game resources -- the arrow-heads are FILLED TRIANGLES via the new g:poly).
-- Press an arrow to drag the ghost ALONG THAT AXIS (the centre = free), snapped to the :placegrid (SHIFT = fine),
-- camera fixed. The arrows re-project each frame so they track the ghost + the camera. See gizmo.lua for the mechanism.
--
-- V6 (gizmo rotate + scale): the gizmo now also draws a cyan ROTATE ring (drag it -> the facing snaps to :placeangle,
-- 45 deg default / SHIFT fine) + a magenta SCALE box (drag OUT/IN -> uniform scale, g:scale). ":planner gizmo" shows
-- all three at once (mode "all"); ":planner gizmo move|rotate|scale" focuses one. The gizmo's onCommit now persists
-- the new facing AND scale, and ":planner scale <s>" sets scale directly. Rotation/scale ride the SAME grid-anchored
-- persistence, so a relog restores position + facing + scale. Full move/rotate/scale = the V6 DoD.
--
-- R2b (custom-PNG sprites): the planner now places SPRITES too (hafen.render():sprite() -- our own icon.png, NOT a .res
-- model), on the SAME client-only world-entity core as a ghost (D-013). Because a sprite handle is identical to a
-- ghost handle, selection, the gizmo, grab, and grid-anchored persistence are all KIND-AGNOSTIC -- one code path
-- drives both. ":planner sprite" stands a FIXED upright quad (clickable, so a click selects it); ":planner sprite
-- billboard" stands a CAMERA-FACING screen blit (always squares up to the camera, constant screen size -- it has no
-- world mesh, so it is NOT clickable: select it with ":planner select <n>", then ":planner gizmo" to move it). Both
-- persist grid-anchored, so a relog restores them alongside the ghosts. Records carry a `kind` ("ghost"/"sprite").
--
-- R3b-2 (custom glTF models): the planner now also places 3D MODELS (hafen.render():object() -- our own cube.glb, a glTF
-- model, NOT a .res game model), on the SAME client-only world-entity core as a ghost/sprite (D-013). A model handle is
-- the identical transform handle, so it rides the SAME kind-agnostic code path: ":planner object" stands the cube at
-- your feet, CLICKABLE (its mesh is in the clickmap, so a click selects it), gizmo-driven, and grid-anchored persisted
-- like everything else. Records now carry `kind` = "ghost" / "sprite" / "object" (+ a `model` path for an object, the
-- analog of `img` for a sprite). ":hello object" (in the hello harness) proves the textured-model RENDER; this slice
-- proves the EDITOR flow (select + gizmo + persist) on a mesh, completing R3's planner integration.

hafen.log():write("planner loaded -- blueprint ghosts + custom-PNG sprites + glTF models + Unity gizmo (move/rotate/scale) + grab, grid-anchored")

-- The persistent anchor for a place in the world: {gridId, x, y}, or nil on ground never visited. Everything
-- here speaks Positions now -- the world reads, the entity placements and the store alike -- so this is just a
-- Position asked for its durable form, and the two-number overload is for the gizmo's own flat value.
local function anchorAt(p)
  return p:info()
end

local function anchorXY(x, y)
  return hafen.world():position(x, y):info()
end

-- The blueprint palette. Keys are short names for ':planner place <name>'; values are client resource paths.
-- logcabin + timberhouse are verified to resolve in-game (V3); ':planner place <res-path>' also takes any raw path.
local PALETTE = {
  cabin  = "gfx/terobjs/arch/logcabin",
  timber = "gfx/terobjs/arch/timberhouse",
}
local DEFAULT_BP = "cabin"

-- R2b: the addon's own PNG used by ':planner sprite' -- a custom (non-.res) world sprite (hafen.render():sprite()) on
-- the SAME world-entity core as a ghost, so it selects + gizmos + persists identically. Ships in this addon's folder.
local SPRITE_IMG = "icon.png"

-- R3b-2: the addon's own glTF model used by ':planner object' -- a custom (non-.res) world model (hafen.render():object())
-- on the SAME world-entity core, so it selects + gizmos + persists identically too. A tiny 1-tile cube; ships here.
local OBJECT_MODEL = "cube.glb"

-- Two looks so selection is visible: idle = bluish tint; sel = brighter warm highlight. OPAQUE (alpha 1) so the
-- blueprint ghosts read clearly against the terrain — the translucent look was too faint to see (maintainer note).
local LOOK = {
  idle = { alpha = 1.0, tint = { r = 120, g = 180, b = 255, a = 120 } },
  sel  = { alpha = 1.0, tint = { r = 255, g = 225, b = 110, a = 170 } },
}

-- Runtime state. `items` is the single source of truth: each record = { res, a, scale, anchor = {gridId, x, y}, ghost }.
-- `ghost` is the live handle (nil while its grid has not streamed in yet). `blueprint` is the current palette key,
-- `selected` the selected record (or nil). Module-level, so a :reload starts clean and EnterWorld repopulates.
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

-- A short human label for a record in logs/lists: the .res leaf for a ghost, or the image (+ a "*" for a billboard)
-- for a sprite. Kind-agnostic call sites use this instead of shortRes(it.res) so sprites read sensibly.
local function recLabel(it)
  if it.kind == "sprite" then return (it.billboard and "billboard " or "sprite ") .. tostring(it.img or SPRITE_IMG) end
  if it.kind == "object" then return "object " .. tostring(it.model or OBJECT_MODEL) end
  return shortRes(it.res)
end

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

-- Apply the entity's look for its current selection state (no-op while it has not streamed in yet). A ghost gets
-- the bluish/warm tint highlight; a sprite highlights by ALPHA only (a colour tint would recolour the PNG itself).
local function applyLook(it)
  local e = it.entity
  if not e then return end
  local sel = (it == selected)
  if it.kind == "sprite" or it.kind == "object" then
    e:alpha(sel and 1.0 or 0.7)                            -- R2b/R3b-2: alpha-only highlight for a custom render asset
                                                            -- (a colour tint would recolour the PNG / the model's texture)
  else
    local look = sel and LOOK.sel or LOOK.idle
    e:alpha(look.alpha):tint(look.tint)
  end
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
    hafen.log():write((":planner selected #%d %s -- :planner rotate | remove"):format(indexOf(it), recLabel(it)))
  end
end

-- Create the live entity for a record at the Position p -- a ghost (a .res game model), a sprite (the addon's
-- own PNG), OR an object (the addon's own glTF model), all on the SAME client-only
-- world-entity core (D-013): every one returns the identical handle, so selection, the gizmo, grab, persistence, and
-- teardown are all kind-agnostic below. A ghost, a FIXED sprite, and an object are CLICKABLE (V2) so a click selects
-- them (the onClick closes over the RECORD, robust to list reorders); a BILLBOARD sprite has no world mesh so it is
-- never picked -- select it with ':planner select <n>'.
local function spawn(it, p)
  if it.kind == "sprite" then
    -- 028.2: sprite/object are HANDLE-ONLY (D-012). The RECORD still stores a PATH (that is what persists to
    -- JSON); hafen.asset turns it into the handle here, at spawn -- interned, so re-spawning costs nothing.
    it.entity = hafen.render():sprite():add(hafen.asset():get(it.img or SPRITE_IMG), p)
      :rotate(it.a):scale(it.scale or 1)
      :billboard(it.billboard or false)
      :clickable(not it.billboard)                        -- fixed sprites are pickable; billboards are not
      :onClick(function(s, button) selectItem(it) end)
  elseif it.kind == "object" then                         -- R3b-2: a glTF model on the same world-entity core
    it.entity = hafen.render():object():add(hafen.asset():get(it.model or OBJECT_MODEL), p)
      :rotate(it.a):scale(it.scale or 1)
      :clickable(true)                                    -- its mesh renders into the clickmap -> a click selects it
      :onClick(function(o, button) selectItem(it) end)
  else
    it.entity = hafen.ghost():add(it.res, p)
      :rotate(it.a):scale(it.scale or 1)                  -- V6: restore the saved scale on (re)spawn
      :alpha(LOOK.idle.alpha):tint(LOOK.idle.tint)
      :clickable(true)
      :onClick(function(g, button) selectItem(it) end)
  end
  applyLook(it)                                            -- keep the highlight if this record is the selected one
  return it.entity
end

-- End a record's live entity. Whatever placed it ends it (R7), so the one thing this has to know is the kind --
-- which is the same switch spawn() makes, and the last place in this addon where the three differ at all.
local function unspawn(it)
  local e = it.entity
  it.entity = nil
  if not e then return end
  if it.kind == "sprite" then
    hafen.render():sprite():remove(e)
  elseif it.kind == "object" then
    hafen.render():object():remove(e)
  else
    hafen.ghost():remove(e)
  end
end

-- Write the layout to the per-char store (JSON). Only the serializable fields (kind/res/img/billboard/model/a/scale/
-- anchor) are stored — the live handle stays out of it. Autosave + relog also flush; we flush on every edit so an unclean exit keeps it.
local function persist()
  local out = {}
  for i, it in ipairs(items) do
    out[i] = {
      kind = it.kind or "ghost", res = it.res, img = it.img, billboard = it.billboard or false,   -- R2b: kind + sprite fields
      model = it.model,                                    -- R3b-2: the glTF model path (nil for a ghost/sprite)
      a = it.a, scale = it.scale or 1,                     -- V6: persist the uniform scale alongside the facing
      anchor = { gridId = it.anchor.gridId, x = it.anchor.x, y = it.anchor.y },
    }
  end
  hafen.store():get("layout").items = out
  hafen.store():get("layout").blueprint = blueprint
  hafen.store():flush()
end

-- Re-resolve every still-pending saved ghost (grid id -> current world coord) and spawn it. Returns how many are
-- still pending (their grid is not loaded here yet). Called at login and by the retry timer as the map streams in.
local function resolvePending()
  local pending = 0
  for _, it in ipairs(items) do
    if not it.entity then
      local w = hafen.world():position(it.anchor)             -- a Position; :x() is nil until that grid is reachable
      if w:x() then
        spawn(it, w)
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
  if it and it.entity then
    local p = it.entity:position()                           -- a Position -- the snapped drop place
    local anchor = anchorAt(p)                                -- re-anchor to the grid it now sits on (persistent id)
    if anchor then it.anchor = anchor end
    persist()
    hafen.log():write((":planner grab -> dropped #%d at %.0f,%.0f (grid %s, persisted)")
      :format(indexOf(it), p:x(), p:y(), it.anchor.gridId))
  end
end

-- At login the per-char store is already restored (1e), so store.layout is ready here. Rebuild `items` from it and
-- re-resolve each grid anchor to a world coord, retrying for a few seconds while the map around us streams in.
hafen.event():on("EnterWorld", function()
  if retry then retry:cancel(); retry = nil end            -- guard against a re-entry (relog/:reload re-fires this)
  if drag then commitDrag() end                            -- V5: never carry a half-finished drag across a relog
  detachGizmo()                                            -- V5b: drop any gizmo before rebuilding the layout
  selected = nil
  items = {}
  blueprint = hafen.store():get("layout").blueprint or DEFAULT_BP
  for _, s in ipairs(hafen.store():get("layout").items or {}) do
    local kind = s.kind or "ghost"                          -- R2b: default old (pre-sprite) layouts to ghosts
    if s.anchor and s.anchor.gridId and ((kind == "sprite") or (kind == "object") or s.res) then   -- skip a malformed record rather than crash
      items[#items + 1] = {
        kind = kind, res = s.res, img = s.img or SPRITE_IMG, billboard = s.billboard or false,
        model = s.model or OBJECT_MODEL,                     -- R3b-2: restore the glTF model path (default for other kinds)
        a = s.a or 0, scale = s.scale or 1,                  -- V6: restore the saved scale (default 1 for old layouts)
        anchor = { gridId = s.anchor.gridId, x = s.anchor.x or 0, y = s.anchor.y or 0 },
      }
    end
  end
  if #items == 0 then
    hafen.log():write("planner: no saved layout -- ':planner place' to start, ':planner' for help")
    return
  end
  local pending = resolvePending()
  hafen.log():write(("planner: loading layout -- %d placed, %d pending (grid streaming in)"):format(#items - pending, pending))
  if pending > 0 then
    local tries = 0
    retry = hafen.timer():every(1, function()                -- retry as grids load; give up after ~15s (far away)
      tries = tries + 1
      local left = resolvePending()
      if (left == 0) or (tries >= 15) then
        if retry then retry:cancel(); retry = nil end
        local placed = 0
        for _, it in ipairs(items) do if it.entity then placed = placed + 1 end end
        hafen.log():write(("planner: layout resolved -- %d/%d ghost(s) placed%s"):format(placed, #items,
          (left > 0) and (" (" .. left .. " out of loaded range -- kept for a closer login)") or ""))
      end
    end)
  end
end)

-- V2/R2b: a click on a clickable ghost OR fixed sprite is detected CLIENT-SIDE and CONSUMED before any server click
-- (no walk/interact, nothing reaches the server -- still SAFE-tier). The per-entity onClick above does the selecting;
-- these owner-scoped events just log the world point, mirroring `hello` (both fire on the same click).
hafen.event():on("GhostClicked", function(ev)
  hafen.log():write((":planner GhostClicked -> ghost at %.0f,%.0f (button %d) -- client-only, no server click sent")
    :format(ev.x, ev.y, ev.button))
end)
hafen.event():on("SpriteClicked", function(ev)
  hafen.log():write((":planner SpriteClicked -> sprite at %.0f,%.0f (button %d) -- client-only, no server click sent")
    :format(ev.x, ev.y, ev.button))
end)
hafen.event():on("ObjectClicked", function(ev)
  hafen.log():write((":planner ObjectClicked -> model at %.0f,%.0f (button %d) -- client-only, no server click sent")
    :format(ev.x, ev.y, ev.button))
end)

hafen.event():on("Disable", function()
  detachGizmo()                                            -- V5b: (the bridge tears down the overlay/hooks too)
  hafen.log():write("planner: Disable -- ghosts torn down + layout flushed on teardown (grid-anchored, so a relog restores them)")
end)

-- A11-style slash command: WoW-style ":planner <sub>" with args as a 1-based table. Reload-safe (a single
-- engine-lifetime dispatcher routes to the current handler), so editing this file + :reload swaps it cleanly.
hafen.slash():register("planner", function(args)
  local sub = args[1] or "help"

  if (sub == "help") or (sub == "") then
    hafen.log():write(":planner -> place | sprite | object | blueprint | list | select | gizmo | grab | rotate | scale | remove | clear | save")
    hafen.log():write("   place [name|res] = drop the current/named blueprint GHOST at your feet (clickable + saved)")
    hafen.log():write("   sprite [billboard] = drop a custom-PNG SPRITE at your feet (fixed, or 'billboard' = camera-facing) -- R2b")
    hafen.log():write("   object = drop a custom glTF MODEL (cube.glb) at your feet (clickable + gizmo + saved) -- R3b-2")
    hafen.log():write("   blueprint [name] = show/set the blueprint (bare = list palette); list = show placed entities")
    hafen.log():write("   select <n> = select #n (or CLICK a ghost / fixed sprite / model)")
    hafen.log():write("   gizmo [move|rotate|scale|all] = Unity gizmo: arrows=move, ring=rotate, box=scale. SHIFT=fine (bare = toggle, mode all)")
    hafen.log():write("   grab = move it by the BODY with the mouse (placegrid-snapped, CLICK to drop) -- V5a")
    hafen.log():write("   rotate [deg] = turn it (persisted); scale <s> = uniform scale (1 = original); remove [n]; clear; save")

  elseif sub == "place" then
    local me = hafen.player():gob()                        -- your character's Gob OBJECT (nil before enter-world)
    local p = me and me:position()
    if not p then hafen.log():write(":planner place -> no player position yet"); return end
    local res = resolveBlueprint(args[2])
    if not res then
      hafen.log():write((":planner place -> unknown blueprint '%s' (palette: %s, or a raw res path)"):format(tostring(args[2]), paletteNames()))
      return
    end
    local anchor = anchorAt(p)                               -- {gridId, x, y} -- the persistent anchor
    if not anchor then hafen.log():write(":planner place -> no map grid loaded here yet; move a moment and retry"); return end
    local it = { kind = "ghost", res = res, a = 0, scale = 1, anchor = anchor }
    items[#items + 1] = it
    spawn(it, p)
    persist()
    selectItem(it)                                          -- auto-select the freshly placed ghost
    hafen.log():write((":planner place -> %s at grid %s (#%d, %d total) -- click to select; relog to test persistence")
      :format(shortRes(res), anchor.gridId, indexOf(it), #items))

  elseif sub == "sprite" then
    -- R2b: place a CUSTOM-PNG SPRITE (hafen.render():sprite()) at your feet, on the SAME world-entity core as a ghost --
    -- so it selects (fixed = click / billboard = ':planner select'), gizmos, and PERSISTS grid-anchored identically.
    -- ':planner sprite' = a FIXED upright quad (clickable); ':planner sprite billboard' = a CAMERA-FACING screen blit.
    local me = hafen.player():gob()                        -- your character's Gob OBJECT (nil before enter-world)
    local p = me and me:position()
    if not p then hafen.log():write(":planner sprite -> no player position yet"); return end
    local billboard = (args[2] == "billboard") or (args[2] == "bb")
    if args[2] and not billboard then
      hafen.log():write((":planner sprite [billboard] -> the only option is 'billboard' (camera-facing); got '%s'"):format(tostring(args[2]))); return
    end
    local anchor = anchorAt(p)                               -- {gridId, x, y} -- the persistent anchor (like a ghost)
    if not anchor then hafen.log():write(":planner sprite -> no map grid loaded here yet; move a moment and retry"); return end
    local it = { kind = "sprite", img = SPRITE_IMG, billboard = billboard, a = 0, scale = billboard and 2 or 3, anchor = anchor }
    items[#items + 1] = it
    local ok = pcall(spawn, it, p)                       -- placing RAISES when there is no map view (D-114)
    if not ok then
      table.remove(items, indexOf(it)); hafen.log():write(":planner sprite -> could not create the sprite (not in the world yet?)"); return
    end
    persist()
    selectItem(it)                                          -- auto-select it (a billboard can't be clicked -> pre-select)
    hafen.log():write((":planner sprite -> %s %s at grid %s (#%d, %d total) -- :planner gizmo to move it; relog to test persistence")
      :format(billboard and "billboard" or "fixed", SPRITE_IMG, anchor.gridId, indexOf(it), #items))

  elseif sub == "object" then
    -- R3b-2: place a CUSTOM glTF MODEL (hafen.render():object() -- our own cube.glb, NOT a .res game model) at your feet,
    -- on the SAME world-entity core as a ghost/sprite (D-013) -- so it selects, gizmos, and PERSISTS grid-anchored
    -- identically. The model is CLICKABLE (its mesh renders into the clickmap), so a click selects it, like a ghost.
    local me = hafen.player():gob()                        -- your character's Gob OBJECT (nil before enter-world)
    local p = me and me:position()
    if not p then hafen.log():write(":planner object -> no player position yet"); return end
    local anchor = anchorAt(p)                               -- {gridId, x, y} -- the persistent anchor (like a ghost)
    if not anchor then hafen.log():write(":planner object -> no map grid loaded here yet; move a moment and retry"); return end
    local it = { kind = "object", model = OBJECT_MODEL, a = 0, scale = 1, anchor = anchor }   -- cube.glb = ~1 tile at scale 1
    items[#items + 1] = it
    local ok = pcall(spawn, it, p)                       -- placing RAISES when there is no map view (D-114)
    if not ok then
      table.remove(items, indexOf(it)); hafen.log():write(":planner object -> could not create the model (not in the world yet?)"); return
    end
    persist()
    selectItem(it)                                          -- auto-select the freshly placed model
    hafen.log():write((":planner object -> %s at grid %s (#%d, %d total) -- click to select; :planner gizmo to move it; relog to test persistence")
      :format(OBJECT_MODEL, anchor.gridId, indexOf(it), #items))

  elseif sub == "blueprint" then
    if not args[2] then
      hafen.log():write((":planner blueprint -> current = %s (%s). palette: %s")
        :format(blueprint, shortRes(PALETTE[blueprint] or "?"), paletteNames()))
      return
    end
    if PALETTE[args[2]] then
      blueprint = args[2]; persist()
      hafen.log():write((":planner blueprint -> now %s (%s)"):format(blueprint, shortRes(PALETTE[blueprint])))
    else
      hafen.log():write((":planner blueprint -> unknown '%s' (palette: %s). Or ':planner place <res-path>' for a raw resource.")
        :format(args[2], paletteNames()))
    end

  elseif sub == "list" then
    if #items == 0 then hafen.log():write(":planner list -> empty (':planner place' / ':planner sprite' / ':planner object' to add one)"); return end
    hafen.log():write((":planner list -> %d entit%s:"):format(#items, (#items == 1) and "y" or "ies"))
    for i, it in ipairs(items) do
      hafen.log():write(("   #%d %s  grid=%s  %s%s"):format(i, recLabel(it), it.anchor.gridId,
        it.entity and "placed" or "pending", (it == selected) and "  [selected]" or ""))
    end

  elseif sub == "select" then
    local n = tonumber(args[2])
    if not n or not items[n] then hafen.log():write(":planner select <n> -> a valid index is required (see :planner list)"); return end
    selectItem(items[n])

  elseif sub == "grab" then
    -- V5: move the selected ghost with the mouse, snapping like a real building placement (D-033).
    if drag then commitDrag(); return end                  -- toggle: a second :planner grab drops the current one
    if not selected then hafen.log():write(":planner grab -> nothing selected (click a ghost or :planner select <n>)"); return end
    if not selected.entity then hafen.log():write(":planner grab -> that ghost has not streamed in yet; try again in a moment"); return end
    detachGizmo()                                           -- V5b: the body-grab and the gizmo are mutually exclusive
    local it = selected
    drag = { it = it, pending = false }
    drag.grab = hafen.hook():grab{
      -- Each mouse move: raycast the ground under the cursor (async) -> snap to the placegrid -> move the ghost.
      -- `pending` coalesces so at most one raycast is in flight (one per frame, like the client's own placement).
      move = function(sx, sy, mods)
        if not drag or drag.pending then return end
        drag.pending = true
        local fine = mods.shift                            -- SHIFT = the fine sub-tile placegrid (D-033)
        hafen.world():screenToWorld(sx, sy, function(w)
          if not drag then return end                      -- released mid-flight
          drag.pending = false
          if not w then return end                         -- cursor hit no terrain (sky/off-map)
          local s = hafen.world():snapPlace(w, fine)       -- a Position in, a Position out
          if it.entity then it.entity:position(s, it.a) end   -- keep facing; the Position is already snapped
        end)
      end,
      -- Mouse-up (the click that drops it): commit + persist + release.
      up = function() commitDrag() end,
    }
    hafen.log():write((":planner grab -> moving #%d: cursor drags it (placegrid=%s, SHIFT=fine); CLICK to drop. Camera stays put.")
      :format(indexOf(it), tostring(hafen.client():options():interface():posGran())))

  elseif sub == "gizmo" then
    -- V5b/V6: attach the Unity-style TRANSFORM GIZMO (gizmo.lua) to the selected ghost. DRAG the RED(X)/GREEN(Y)
    -- arrows (or the yellow centre = free) to MOVE, the cyan RING to ROTATE (snaps to :placeangle, SHIFT=fine), the
    -- magenta BOX out/in to SCALE. Snaps to the :placegrid/:placeangle (SHIFT=fine), camera stays -- the V6 DoD.
    -- ":planner gizmo" with no arg toggles attach/detach (mode "all"); ":planner gizmo move|rotate|scale|all"
    -- (re)attaches focused on that handle group.
    local modeArg = args[2]
    if modeArg and not ({ move = true, rotate = true, scale = true, all = true })[modeArg] then
      hafen.log():write((":planner gizmo [mode] -> mode must be move|rotate|scale|all (got '%s')"):format(tostring(modeArg))); return
    end
    if activeGizmo and (not modeArg) then detachGizmo(); hafen.log():write(":planner gizmo -> detached"); return end
    if not selected then hafen.log():write(":planner gizmo -> nothing selected (click a ghost or :planner select <n>)"); return end
    if not selected.entity then hafen.log():write(":planner gizmo -> that ghost has not streamed in yet; try again in a moment"); return end
    if drag then commitDrag() end                          -- exclusive with the V5a body-grab
    local it = selected
    if activeGizmo then
      activeGizmo:setMode(modeArg)                          -- already attached: just switch the handle group
    else
      activeGizmo = gizmo(it.entity, {                       -- calls the constructor from gizmo.lua (loaded first)
        mode = modeArg or "all",
        -- On release: sync the record's facing + scale from the ghost (the gizmo may have rotated/scaled it), then
        -- re-anchor to the grid it now sits on (the persistent id) + persist. Closed over the RECORD so a later
        -- reorder can't mis-target it.
        onCommit = function(p)
          if (it ~= selected) or (not it.entity) then return end
          it.a = p.a or it.a                               -- V6: persist a gizmo rotate
          it.scale = p.scale or it.scale                   -- V6: persist a gizmo scale
          local anchor = anchorXY(p.x, p.y)   -- the gizmo's own flat value
          if anchor then it.anchor = anchor end
          persist()
          hafen.log():write((":planner gizmo -> dropped #%d at %.0f,%.0f a=%.2f s=%.2f (grid %s, persisted)")
            :format(indexOf(it), p.x, p.y, it.a, it.scale, it.anchor.gridId))
        end,
      })
    end
    hafen.log():write((":planner gizmo -> #%d mode=%s: drag arrows(move) / ring(rotate) / box(scale); SHIFT=fine; camera stays. ':planner gizmo' to detach.")
      :format(indexOf(it), activeGizmo:mode()))

  elseif sub == "rotate" then
    if not selected then hafen.log():write(":planner rotate -> nothing selected (click a ghost or :planner select <n>)"); return end
    local deg = tonumber(args[2]) or 45
    selected.a = (selected.a + math.rad(deg)) % (2 * math.pi)
    if selected.entity then selected.entity:rotate(selected.a) end
    persist()
    hafen.log():write((":planner rotate -> #%d now a=%.2f rad (+%d deg, persisted)"):format(indexOf(selected), selected.a, deg))

  elseif sub == "scale" then
    -- V6: set the selected ghost's uniform scale directly (the gizmo scale box is the interactive way).
    if not selected then hafen.log():write(":planner scale -> nothing selected (click a ghost or :planner select <n>)"); return end
    local s = tonumber(args[2])
    if not s then hafen.log():write(":planner scale <s> -> a number is required (1 = original size, e.g. 1.5 / 0.5)"); return end
    selected.scale = s
    if selected.entity then selected.entity:scale(s) end
    persist()
    hafen.log():write((":planner scale -> #%d now scale=%.2f (persisted)"):format(indexOf(selected), selected.scale))

  elseif sub == "remove" then
    local it = selected
    if args[2] then                                        -- an explicit index must be valid (no silent fallback)
      it = items[tonumber(args[2]) or 0]
      if not it then hafen.log():write((":planner remove -> no ghost #%s (see :planner list)"):format(tostring(args[2]))); return end
    end
    if not it then hafen.log():write(":planner remove [n] -> nothing selected and no index given"); return end
    local idx = indexOf(it)
    if selected == it then selected = nil; detachGizmo() end  -- V5b: drop the gizmo before its target ghost dies
    if it.entity then unspawn(it) end
    table.remove(items, idx)
    persist()
    hafen.log():write((":planner remove -> removed #%d %s (%d left)"):format(idx, recLabel(it), #items))

  elseif sub == "clear" then
    detachGizmo()                                          -- V5b: drop the gizmo before its target ghost dies
    for _, it in ipairs(items) do unspawn(it) end
    items = {}; selected = nil
    persist()
    hafen.log():write(":planner clear -> removed all ghosts + wiped the saved layout")

  elseif sub == "save" then
    persist()
    hafen.log():write((":planner save -> flushed %d ghost(s) to the per-char store"):format(#items))

  else
    hafen.log():write((":planner -> unknown sub-command '%s' (try :planner help)"):format(sub))
  end
end)
hafen.log():write("planner: ':planner' registered -- type it in the console (chat). Ghosts reload grid-anchored after a relog.")

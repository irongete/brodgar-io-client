-- gizmo.lua -- a bundled Lua TRANSFORM-GIZMO library over the V5a primitives (spec 16-virtual-entities §4,
-- decision D-031: "Java exposes primitives, the gizmo BEHAVIOUR lives in a bundled Lua library, shipped in the
-- planner example addon"). This is the V5b slice: a Unity-style, 3D-anchored MOVE gizmo drawn entirely with the
-- g:draw() surface -- NO game resources. (V6 adds rotate + scale.)
--
-- WHAT IT LOOKS LIKE. Two axis arrows anchored at the target ghost -- RED = world +X, GREEN = world +Y (Unity's
-- axis colours) -- plus a small yellow CENTRE square for a free ground-plane move. The arrows are drawn by
-- projecting the target's world position and the world-axis tips to the screen (hafen.player.worldToScreen), so
-- they foreshorten with the camera and genuinely look "in the 3D world" even though they are 2D triangles on the
-- HUD overlay. Each arrow = a shaft line (g:line) + a FILLED triangle head (g:poly, added to the draw surface for
-- V5b). The head is a fixed SCREEN size, so it is always a grabbable target regardless of zoom.
--
-- HOW YOU DRAG IT (the "by its arrows" DoD). Press an arrow (or the centre) -> the gizmo consumes that mousedown
-- (hafen.hook.input + ev:preventDefault, so the map neither clicks nor pans) and starts a mouse GRAB
-- (hafen.hook.grab -- the camera stays put). While you drag, each mouse move raycasts the ground under the cursor
-- (hafen.map.screenToWorld), CONSTRAINS it to the picked axis (X arrow -> only world-X changes; Y arrow -> only
-- world-Y; centre -> both), and SNAPS it to the client's :placegrid (hafen.map.snapPlace -- tile centre by
-- default, SHIFT = the fine sub-tile grid, D-033) exactly like placing a building. Release to drop. The arrows
-- re-project every frame, so they follow the ghost as it moves.
--
-- WHY THIS IS THE "2D-projected" path (spec §4). V2 gives us pixel-perfect 3D ghost picking, so the ORIGINAL V5
-- plan was 3D arrow-mesh handles. The maintainer chose the spec's documented fallback instead: draw the handles
-- by projecting to screen and hit-test in screen space -- so the gizmo needs no bespoke arrow resource, just the
-- draw surface. Same drag pipeline either way.
--
-- API (mirrors the api-reference `hafen.ghost.gizmo(target, opts)` shape; here it is a planner-local Lua library,
-- promotable to a shared hafen.ghost.gizmo later with no behaviour change):
--   local gz = gizmo(target, { mode="move", len=<world units>, onChange=fn, onCommit=fn })
--     target   -- anything with :pos() -> {x,y,a} and :move(x,y[,a]) (a ghost handle qualifies).
--     onChange -- called during the drag with {x,y,a}; onCommit -- called on release with the final {x,y,a}.
--   gz:setMode("move")   -- V5b: only "move" (rotate/scale = V6); stored, others are a no-op for now.
--   gz:detach() / gz:destroy()   -- remove the overlay + input hook + any active grab (idempotent).
--   gz:isDragging() -> bool
-- The gizmo is bridge-owned at the engine layer (overlay/hooks/grab all torn down on :reload/disable), but the
-- OWNER (planner) should still detach it when its target changes or is removed (see main.lua).

-- ---- tunables (all in the module scope) --------------------------------------------------------------------
local TILE          = 11.0          -- Haven tilesz (world units per tile); default arrow length is a few tiles
local AXIS_LEN      = TILE * 2.0    -- default world length of each axis arrow (opts.len overrides)
local SHAFT_W       = 3             -- shaft line width (px); highlighted axis is thicker
local SHAFT_W_HI    = 5
local HEAD_LEN      = 15            -- arrow-head length (SCREEN px, so it stays grabbable at any zoom)
local HEAD_W        = 8            -- arrow-head half-width (px)
local HEAD_LEN_HI   = 19
local HEAD_W_HI     = 10
local CENTER_R      = 6            -- centre free-move handle half-size (px)
local PICK_AXIS     = 8            -- click within this many px of a shaft selects that axis
local PICK_TIP      = 16           -- ...or within this many px of the arrow-head tip (generous grab target)
local PICK_CENTER   = 11           -- click within this many px of the centre selects the free (xy) handle

-- Unity axis colours: X red, Y green, centre yellow; the "hi" variants light up on hover / during a drag.
local COL = {
  x  = {225,  70,  60}, xhi = {255, 135, 120},
  y  = { 70, 200,  95}, yhi = {150, 255, 175},
  c  = {235, 220,  90}, chi = {255, 245, 150},
}

-- ---- pure screen-space geometry ----------------------------------------------------------------------------

-- Distance from point (px,py) to the segment (ax,ay)-(bx,by).
local function segDist(px, py, ax, ay, bx, by)
  local dx, dy = bx - ax, by - ay
  local l2 = (dx * dx) + (dy * dy)
  if l2 < 1e-6 then
    local ex, ey = px - ax, py - ay
    return math.sqrt((ex * ex) + (ey * ey))
  end
  local t = (((px - ax) * dx) + ((py - ay) * dy)) / l2
  if t < 0 then t = 0 elseif t > 1 then t = 1 end
  local qx, qy = ax + (t * dx), ay + (t * dy)
  local ex, ey = px - qx, py - qy
  return math.sqrt((ex * ex) + (ey * ey))
end

-- Is (mx,my) on the axis arrow from (cx,cy) to tip (tx,ty)? Returns a pick "score" (smaller = closer) or nil:
-- a hit near the SHAFT (PICK_AXIS) or near the TIP (PICK_TIP, so the fixed-size head is always easy to grab).
local function axisScore(mx, my, cx, cy, tx, ty)
  local ds = segDist(mx, my, cx, cy, tx, ty)
  local dtx, dty = mx - tx, my - ty
  local dt = math.sqrt((dtx * dtx) + (dty * dty))
  if (ds <= PICK_AXIS) or (dt <= PICK_TIP) then return math.min(ds, dt) end
  return nil
end

-- Project the target centre + the two world-axis tips to screen. Returns a geom table (or nil if the target has
-- no position / the centre is off-screen). Each tip may independently be off-screen (xok/yok false).
local function computeGeom(self)
  local tgt = self.target
  if not tgt then return nil end
  local p = tgt:pos()
  if not p then return nil end
  local c = hafen.player.worldToScreen(p.x, p.y)
  if not c then return nil end
  local len = self.len
  local xt = hafen.player.worldToScreen(p.x + len, p.y)
  local yt = hafen.player.worldToScreen(p.x, p.y + len)
  return {
    cx = c.x, cy = c.y,
    xok = (xt ~= nil), xx = xt and xt.x, xy = xt and xt.y,
    yok = (yt ~= nil), yx = yt and yt.x, yy = yt and yt.y,
  }
end

-- Which handle is under (mx,my)? "xy" (centre) wins first, then the nearest axis within tolerance, else nil.
local function hitGeom(geom, mx, my)
  if not geom then return nil end
  local dcx, dcy = mx - geom.cx, my - geom.cy
  if ((dcx * dcx) + (dcy * dcy)) <= (PICK_CENTER * PICK_CENTER) then return "xy" end
  local best, bestScore = nil, nil
  if geom.xok then
    local s = axisScore(mx, my, geom.cx, geom.cy, geom.xx, geom.xy)
    if s and (not bestScore or (s < bestScore)) then best, bestScore = "x", s end
  end
  if geom.yok then
    local s = axisScore(mx, my, geom.cx, geom.cy, geom.yx, geom.yy)
    if s and (not bestScore or (s < bestScore)) then best, bestScore = "y", s end
  end
  return best
end

-- ---- drawing -----------------------------------------------------------------------------------------------

-- One axis arrow: a shaft line + a FILLED triangle head with its tip at (tx,ty), pointing away from (cx,cy).
local function drawArrow(g, cx, cy, tx, ty, col, hi)
  g:color(col[1], col[2], col[3], 255)
  g:line(cx, cy, tx, ty, hi and SHAFT_W_HI or SHAFT_W)
  local dx, dy = tx - cx, ty - cy
  local len = math.sqrt((dx * dx) + (dy * dy))
  if len < 0.001 then g:color(); return end
  dx, dy = dx / len, dy / len          -- unit direction along the axis
  local px, py = -dy, dx               -- unit perpendicular
  local hl = hi and HEAD_LEN_HI or HEAD_LEN
  local hw = hi and HEAD_W_HI   or HEAD_W
  local bx, by = tx - (dx * hl), ty - (dy * hl)   -- head base centre, hl px back from the tip
  g:poly(tx, ty, bx + (px * hw), by + (py * hw), bx - (px * hw), by - (py * hw))
  g:color()
end

-- Draw the whole gizmo for the current frame: the two arrows + the centre square, highlighting the active axis
-- (during a drag) or the hovered one (otherwise).
local function drawGizmo(self, g)
  local geom = computeGeom(self)
  if not geom then return end
  local hot = self.drag and self.drag.axis
             or ((not self.drag) and self.cursor and hitGeom(geom, self.cursor.x, self.cursor.y))
             or nil
  if geom.xok then drawArrow(g, geom.cx, geom.cy, geom.xx, geom.xy, (hot == "x") and COL.xhi or COL.x, hot == "x") end
  if geom.yok then drawArrow(g, geom.cx, geom.cy, geom.yx, geom.yy, (hot == "y") and COL.yhi or COL.y, hot == "y") end
  local cc = (hot == "xy") and COL.chi or COL.c
  local r = (hot == "xy") and (CENTER_R + 2) or CENTER_R
  g:color(cc[1], cc[2], cc[3], 255)
  g:frect(geom.cx - r, geom.cy - r, r * 2, r * 2)
  g:color()
end

-- ---- drag lifecycle ----------------------------------------------------------------------------------------

-- End the active drag (mouse release, or forced by detach): release the grab, notify onCommit. Idempotent.
local function endDrag(self)
  local d = self.drag
  if not d then return end
  self.drag = nil
  if d.grab then d.grab:release() end
  if self.onCommit and self.target then
    self.onCommit(self.target:pos())
  end
end

-- Start an axis-locked drag: capture the target's start position (the non-dragged coord stays pinned to it),
-- then GRAB the mouse. Each move raycasts the ground, constrains to the axis, snaps to the placegrid, and moves
-- the target. `pending` coalesces so at most one raycast is in flight (the client's own placement cadence).
local function startDrag(self, axis)
  local p = self.target:pos()
  local d = { axis = axis, sx = p.x, sy = p.y, a = p.a, pending = false }
  self.drag = d
  d.grab = hafen.hook.grab{
    move = function(mx, my, mods)
      if (self.drag ~= d) or d.pending then return end
      d.pending = true
      local fine = mods.shift                          -- SHIFT = the fine sub-tile placegrid (D-033)
      hafen.map.screenToWorld(mx, my, function(w)
        if self.drag ~= d then return end              -- released / detached mid-flight
        d.pending = false
        if not w then return end                       -- cursor hit no terrain (sky / off-map)
        local nx, ny
        if axis == "x" then
          local s = hafen.map.snapPlace(w.x, d.sy, fine); nx, ny = s.x, d.sy
        elseif axis == "y" then
          local s = hafen.map.snapPlace(d.sx, w.y, fine); nx, ny = d.sx, s.y
        else
          local s = hafen.map.snapPlace(w.x, w.y, fine); nx, ny = s.x, s.y
        end
        if self.target then self.target:move(nx, ny, d.a) end   -- keep facing; snapped along the axis
        if self.onChange then self.onChange({ x = nx, y = ny, a = d.a }) end
      end)
    end,
    up = function() endDrag(self) end,
  }
end

-- ---- teardown ----------------------------------------------------------------------------------------------

local function detach(self)
  if not self.alive then return end
  self.alive = false
  if self.drag then
    if self.drag.grab then self.drag.grab:release() end
    self.drag = nil
  end
  if self.overlay  then self.overlay:remove();  self.overlay  = nil end
  if self.downHook then self.downHook:remove(); self.downHook = nil end
  if self.moveHook then self.moveHook:remove(); self.moveHook = nil end
end

-- ---- constructor (the single global this module installs) ---------------------------------------------------

-- Loaded before main.lua (manifest files order), so main.lua can call gizmo(target, opts). One global; the addon
-- env is per-addon sandboxed, so this does not leak to other addons.
gizmo = function(target, opts)
  if (type(target) ~= "table") or (type(target.pos) ~= "function") or (type(target.move) ~= "function") then
    error("gizmo(target, opts): target must expose :pos() and :move(x,y[,a]) (e.g. a hafen.ghost handle)", 2)
  end
  opts = opts or {}
  local self = {
    alive    = true,
    target   = target,
    mode     = opts.mode or "move",
    len      = opts.len or AXIS_LEN,
    onChange = opts.onChange,
    onCommit = opts.onCommit,
    drag     = nil,
    cursor   = nil,     -- last mapview cursor pos (for the hover highlight), updated by moveHook
  }

  -- Draw on top of the HUD every frame (re-projects, so the arrows track the ghost + the camera).
  self.overlay = hafen.ui.overlay(function(g, w, h)
    if self.alive then drawGizmo(self, g) end
  end)

  -- Press a handle -> consume the click (no map click / camera / ghost-select) and start the axis drag. On a
  -- MISS we return without preventDefault, so ordinary map clicks (and the ghost's V2 selection) still work.
  self.downHook = hafen.hook.input("mapview", "mousedown", function(ev)
    if (not self.alive) or self.drag then return end
    if ev.button and (ev.button ~= 1) then return end       -- left button only (middle=camera, right=menu pass)
    local axis = hitGeom(computeGeom(self), ev.x, ev.y)
    if not axis then return end
    ev:preventDefault()
    startDrag(self, axis)
  end)

  -- Track the cursor for the hover highlight (never preventDefault -> normal hovering/camera is untouched). The
  -- grab captures moves DURING a drag, so this only feeds the idle hover state.
  self.moveHook = hafen.hook.input("mapview", "mousemove", function(ev)
    if self.alive then self.cursor = { x = ev.x, y = ev.y } end
  end)

  return {
    detach     = function() detach(self) end,
    destroy    = function() detach(self) end,
    setMode    = function(_, m) if m then self.mode = m end end,   -- V5b: only "move" acts; rotate/scale = V6
    isDragging = function() return self.drag ~= nil end,
  }
end

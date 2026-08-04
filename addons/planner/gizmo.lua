-- gizmo.lua -- a bundled Lua TRANSFORM-GIZMO library over the hafen.map / hafen.ghost primitives (spec
-- 16-virtual-entities §4, decision D-031: "Java exposes primitives, the gizmo BEHAVIOUR lives in a bundled Lua
-- library, shipped in the planner example addon"). V5b shipped the MOVE gizmo; V6 adds ROTATE + SCALE + polish.
--
-- WHAT IT LOOKS LIKE. A Unity-style transform gizmo anchored at the target ghost, drawn entirely with the g:draw()
-- surface (NO game resources), on a hafen.ui.overlay so it is always ON TOP of the 3D scene (no depth occlusion --
-- the "draw-on-top" polish for free). Depending on the mode it shows:
--   * MOVE   -- RED = world +X, GREEN = world +Y axis arrows (Unity's axis colours) + a yellow CENTRE square for a
--              free ground-plane move. Each arrow = a shaft line (g:line, foreshortening with the camera so it
--              looks "in the world") + a FILLED triangle head (g:poly). The head/centre are a FIXED SCREEN size so
--              they stay grabbable at any zoom (the "constant screen-size handles" polish).
--   * ROTATE -- a cyan RING (a fixed-screen-radius circle) around the centre. Drag it to spin the ghost; the facing
--              snaps to the client's :placeangle (45° default, SHIFT = the fine grid) via hafen.world.snapAngle.
--   * SCALE  -- a magenta BOX handle sticking up out of the ring. Drag it OUT to grow / IN to shrink the ghost
--              (uniform scale via g:scale -> a scaling Location on the gob, V6). Fixed screen size.
--   * "all"  -- everything at once (the planner default), so one gizmo does full move/rotate/scale.
--
-- HOW YOU DRAG IT. Press a handle -> the gizmo consumes that mousedown (hafen.hook():input + ev:preventDefault, so
-- the map neither clicks nor pans nor V2-selects) and starts a mouse GRAB (hafen.hook():grab -- the camera stays put).
-- MOVE/ROTATE raycast the ground under the cursor each move (hafen.world.screenToWorld, async + coalesced) so they
-- work in true WORLD space (snapping identical to placing a building, D-033); SCALE is pure screen math (drag
-- distance from the centre). Release to drop. The handles re-project every frame, so they track the ghost + camera.
--
-- WHY THIS IS THE "2D-projected" path (spec §4). V2 gives pixel-perfect 3D ghost picking, so the ORIGINAL plan was
-- 3D arrow/ring-mesh handles. The maintainer chose the spec's documented fallback: draw the handles by projecting
-- to screen and hit-test in screen space -- so the gizmo needs no bespoke arrow/ring resource, just the draw surface.
--
-- API (mirrors the api-reference `hafen.ghost.gizmo(target, opts)` shape; here it is a planner-local Lua library,
-- promotable to a shared hafen.ghost.gizmo later with no behaviour change):
--   local gz = gizmo(target, { mode="all", len=<world units>, onChange=fn, onCommit=fn })
--     target   -- anything with :pos() -> {x,y,a,scale} and :move(x,y[,a]); ideally :rotate(a) + :scale(s) too
--                (a hafen.ghost handle qualifies). Rotate falls back to :move(x,y,a); scale needs :scale.
--     mode     -- "move" | "rotate" | "scale" | "all" (default "all").
--     onChange -- called during the drag with {x,y,a,scale}; onCommit -- called on release with the final transform.
--   gz:setMode("move"/"rotate"/"scale"/"all")   -- switch handles live.
--   gz:detach() / gz:destroy()   -- remove the overlay + input hooks + any active grab (idempotent).
--   gz:isDragging() -> bool ; gz:mode() -> string
-- The gizmo is bridge-owned at the engine layer (overlay/hooks/grab all torn down on :reload/disable), but the
-- OWNER (planner) should still detach it when its target changes or is removed (see main.lua).

-- ---- tunables (all in the module scope) --------------------------------------------------------------------
local TILE          = 11.0          -- Haven tilesz (world units per tile); default arrow length is a few tiles
local AXIS_LEN      = TILE * 2.0    -- default world length of each axis arrow (opts.len overrides)
local SHAFT_W       = 3             -- shaft line width (px); highlighted axis is thicker
local SHAFT_W_HI    = 5
local HEAD_LEN      = 15            -- arrow-head length (SCREEN px, so it stays grabbable at any zoom)
local HEAD_W        = 8             -- arrow-head half-width (px)
local HEAD_LEN_HI   = 19
local HEAD_W_HI     = 10
local CENTER_R      = 6             -- centre free-move handle half-size (px)
local RING_R        = 44            -- rotate ring radius (SCREEN px -- constant size at any zoom)
local RING_SEG      = 40            -- ring smoothness (line segments)
local SCALE_OFF     = 60            -- scale handle offset from the centre, straight up (px) = just outside the ring
local SCALE_BOX     = 5             -- scale handle half-size (px)
local PICK_AXIS     = 8             -- click within this many px of a shaft selects that axis
local PICK_TIP      = 16            -- ...or within this many px of the arrow-head tip (generous grab target)
local PICK_CENTER   = 11            -- click within this many px of the centre selects the free (xy) handle
local PICK_RING     = 7             -- click within this many px of the ring LINE selects rotate
local PICK_SCALE    = 12            -- click within this many px of the scale box selects scale
local MIN_SCALE     = 0.1           -- gizmo scale clamp (the Java handle clamps wider, 0.01..100)
local MAX_SCALE     = 8.0

-- Unity axis colours: X red, Y green, centre yellow (move); cyan ring (rotate); magenta box (scale). The "hi"
-- variants light up on hover / during a drag.
local COL = {
  x   = {225,  70,  60}, xhi  = {255, 135, 120},
  y   = { 70, 200,  95}, yhi  = {150, 255, 175},
  c   = {235, 220,  90}, chi  = {255, 245, 150},
  rot = { 70, 190, 220}, rothi= {150, 230, 255},
  sc  = {215, 110, 235}, schi = {240, 165, 250},
}

local MODES = { move = true, rotate = true, scale = true, all = true }

-- ---- pure helpers ------------------------------------------------------------------------------------------

-- atan2 built from math.atan (1-arg) so we never depend on math.atan2 (absent in some Lua 5.1 stdlibs / LuaJ).
local PI = math.pi
local function atan2(y, x)
  if x > 0 then return math.atan(y / x)
  elseif x < 0 then
    if y >= 0 then return math.atan(y / x) + PI else return math.atan(y / x) - PI end
  else
    if y > 0 then return PI / 2 elseif y < 0 then return -PI / 2 else return 0 end
  end
end

local function clampScale(s)
  if s < MIN_SCALE then return MIN_SCALE elseif s > MAX_SCALE then return MAX_SCALE else return s end
end

-- Normalize a requested mode to one of the four, keeping the current one on an unknown value.
local function normMode(m, cur) return (m and MODES[m]) and m or cur end

-- Does `mode` show the `kind` ("move" | "rotate" | "scale") handle group?
local function shows(mode, kind) return (mode == "all") or (mode == kind) end

-- Apply a facing to the target, keeping its position. Prefer :rotate(a); fall back to :move(x,y,a) for a target
-- that only exposes :move (the gizmo contract's minimum).
local function applyRotate(target, sx, sy, a)
  if type(target.rotate) == "function" then target:rotate(a) else target:move(sx, sy, a) end
end

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
-- no position / the centre is off-screen). Each tip may independently be off-screen (xok/yok false). The ring +
-- scale handle derive from the centre at a fixed SCREEN radius, so they need nothing more here.
local function computeGeom(self)
  local tgt = self.target
  if not tgt then return nil end
  local p = tgt:pos()
  if not p then return nil end
  local pl = hafen.player()                     -- the Player object (D-046); worldToScreen is a method now
  local c = pl:worldToScreen(p.x, p.y)
  if not c then return nil end
  local len = self.len
  local xt = pl:worldToScreen(p.x + len, p.y)
  local yt = pl:worldToScreen(p.x, p.y + len)
  return {
    wx = p.x, wy = p.y,                 -- the ghost's world position (the rotation pivot)
    cx = c.x, cy = c.y,
    xok = (xt ~= nil), xx = xt and xt.x, xy = xt and xt.y,
    yok = (yt ~= nil), yx = yt and yt.x, yy = yt and yt.y,
  }
end

-- Which handle is under (mx,my), respecting the active `mode` (+ whether the target can scale)? Priority: centre
-- (free move) -> scale box -> nearest axis -> rotate ring. Returns "xy"/"scale"/"x"/"y"/"rot" or nil.
local function hitGeom(geom, mx, my, mode, canScale)
  if not geom then return nil end
  if shows(mode, "move") then
    local dcx, dcy = mx - geom.cx, my - geom.cy
    if ((dcx * dcx) + (dcy * dcy)) <= (PICK_CENTER * PICK_CENTER) then return "xy" end
  end
  if canScale and shows(mode, "scale") then
    local dx, dy = mx - geom.cx, my - (geom.cy - SCALE_OFF)
    if ((dx * dx) + (dy * dy)) <= (PICK_SCALE * PICK_SCALE) then return "scale" end
  end
  if shows(mode, "move") then
    local best, bestScore = nil, nil
    if geom.xok then
      local s = axisScore(mx, my, geom.cx, geom.cy, geom.xx, geom.xy)
      if s and (not bestScore or (s < bestScore)) then best, bestScore = "x", s end
    end
    if geom.yok then
      local s = axisScore(mx, my, geom.cx, geom.cy, geom.yx, geom.yy)
      if s and (not bestScore or (s < bestScore)) then best, bestScore = "y", s end
    end
    if best then return best end
  end
  if shows(mode, "rotate") then
    local dcx, dcy = mx - geom.cx, my - geom.cy
    local d = math.sqrt((dcx * dcx) + (dcy * dcy))
    if math.abs(d - RING_R) <= PICK_RING then return "rot" end
  end
  return nil
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

-- The rotate ring: a fixed-screen-radius circle around the centre, drawn as a closed polyline.
local function drawRing(g, geom, hi)
  local col = hi and COL.rothi or COL.rot
  local w = hi and 3 or 2
  g:color(col[1], col[2], col[3], hi and 255 or 205)
  local px, py
  for i = 0, RING_SEG do
    local t = (i / RING_SEG) * 2 * PI
    local x = geom.cx + (RING_R * math.cos(t))
    local y = geom.cy + (RING_R * math.sin(t))
    if px then g:line(px, py, x, y, w) end
    px, py = x, y
  end
  g:color()
end

-- The scale handle: a short connector up out of the ring + a filled box, straight up in screen space.
local function drawScale(g, geom, hi)
  local col = hi and COL.schi or COL.sc
  local hx, hy = geom.cx, geom.cy - SCALE_OFF
  g:color(col[1], col[2], col[3], 255)
  g:line(geom.cx, geom.cy - RING_R, hx, hy + SCALE_BOX, hi and 3 or 2)
  local r = hi and (SCALE_BOX + 2) or SCALE_BOX
  g:frect(hx - r, hy - r, r * 2, r * 2)
  g:color()
end

-- Draw the whole gizmo for the current frame per mode, highlighting the active (dragging) or hovered handle. The
-- ring is drawn first (behind), then the scale box, then the move arrows + centre on top (the primary handles).
local function drawGizmo(self, g)
  local geom = computeGeom(self)
  if not geom then return end
  local mode = self.mode
  local hot = (self.drag and self.drag.kind)
             or ((not self.drag) and self.cursor and hitGeom(geom, self.cursor.x, self.cursor.y, mode, self.canScale))
             or nil
  if shows(mode, "rotate") then drawRing(g, geom, hot == "rot") end
  if self.canScale and shows(mode, "scale") then drawScale(g, geom, hot == "scale") end
  if shows(mode, "move") then
    if geom.xok then drawArrow(g, geom.cx, geom.cy, geom.xx, geom.xy, (hot == "x") and COL.xhi or COL.x, hot == "x") end
    if geom.yok then drawArrow(g, geom.cx, geom.cy, geom.yx, geom.yy, (hot == "y") and COL.yhi or COL.y, hot == "y") end
    local cc = (hot == "xy") and COL.chi or COL.c
    local r = (hot == "xy") and (CENTER_R + 2) or CENTER_R
    g:color(cc[1], cc[2], cc[3], 255)
    g:frect(geom.cx - r, geom.cy - r, r * 2, r * 2)
    g:color()
  end
end

-- ---- drag lifecycle ----------------------------------------------------------------------------------------

-- Notify onChange with the target's full current transform ({x,y,a,scale}).
local function fireChange(self)
  if self.onChange and self.target then self.onChange(self.target:pos()) end
end

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

-- Start a drag for the picked handle `kind` (mousedown at screen mx,my). Captures the target's start transform,
-- then GRABs the mouse; each move dispatches by kind: MOVE/ROTATE raycast the ground (async, coalesced by
-- `pending`, at most one in flight -- the client's own placement cadence), SCALE is pure screen distance.
local function startDrag(self, kind, mx, my)
  local p = self.target:pos()
  local d = { kind = kind, sx = p.x, sy = p.y, a = p.a, scale = p.scale or 1, pending = false }
  if kind == "scale" then
    local geom = computeGeom(self)
    local dx, dy = mx - (geom and geom.cx or mx), my - (geom and geom.cy or my)
    d.startDist = math.max(math.sqrt((dx * dx) + (dy * dy)), 8)   -- reference distance (no jump at grab)
  end
  self.drag = d
  d.grab = hafen.hook():grab{
    move = function(mmx, mmy, mods)
      if self.drag ~= d then return end
      local fine = mods.shift                          -- SHIFT = the fine :placegrid / :placeangle (D-033)
      if kind == "scale" then
        local geom = computeGeom(self)
        if not geom then return end
        local dx, dy = mmx - geom.cx, mmy - geom.cy
        local dist = math.sqrt((dx * dx) + (dy * dy))
        local ns = clampScale(d.scale * (dist / d.startDist))
        if self.target and (type(self.target.scale) == "function") then self.target:scale(ns) end
        fireChange(self)
        return
      end
      if d.pending then return end
      d.pending = true
      hafen.world.screenToWorld(mmx, mmy, function(w)
        if self.drag ~= d then return end              -- released / detached mid-flight
        d.pending = false
        if not w then return end                       -- cursor hit no terrain (sky / off-map)
        if kind == "rot" then
          local ex, ey = w.x - d.sx, w.y - d.sy        -- world vector from the pivot to the cursor
          if ((ex * ex) + (ey * ey)) < 1e-4 then return end  -- ~ on the pivot: angle undefined
          local raw = atan2(ey, ex)
          -- RELATIVE rotation: anchor the swept angle to the grab point (offset captured on the first raycast) so
          -- grabbing the ring doesn't snap the ghost to face the cursor -- it rotates BY how far you sweep.
          if not d.rotOffset then d.rotOffset = d.a - raw end
          local na = hafen.world.snapAngle(raw + d.rotOffset, fine)
          applyRotate(self.target, d.sx, d.sy, na)
        else
          local nx, ny
          if kind == "x" then
            local s = hafen.world.snapPlace(w.x, d.sy, fine); nx, ny = s.x, d.sy
          elseif kind == "y" then
            local s = hafen.world.snapPlace(d.sx, w.y, fine); nx, ny = d.sx, s.y
          else
            local s = hafen.world.snapPlace(w.x, w.y, fine); nx, ny = s.x, s.y
          end
          if self.target then self.target:move(nx, ny, d.a) end   -- keep facing; snapped along the axis
        end
        fireChange(self)
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
    mode     = normMode(opts.mode, "all"),
    canScale = (type(target.scale) == "function"),   -- gate the scale handle on the target actually supporting it
    len      = opts.len or AXIS_LEN,
    onChange = opts.onChange,
    onCommit = opts.onCommit,
    drag     = nil,
    cursor   = nil,     -- last mapview cursor pos (for the hover highlight), updated by moveHook
  }

  -- Draw on top of the HUD every frame (re-projects, so the handles track the ghost + the camera).
  self.overlay = hafen.ui.overlay(function(g, w, h)
    if self.alive then drawGizmo(self, g) end
  end)

  -- Press a handle -> consume the click (no map click / camera / ghost-select) and start the drag. On a MISS we
  -- return without preventDefault, so ordinary map clicks (and the ghost's V2 selection) still work.
  self.downHook = hafen.hook():input("mapview", "mousedown", function(ev)
    if (not self.alive) or self.drag then return end
    if ev.button and (ev.button ~= 1) then return end       -- left button only (middle=camera, right=menu pass)
    local kind = hitGeom(computeGeom(self), ev.x, ev.y, self.mode, self.canScale)
    if not kind then return end
    ev:preventDefault()
    startDrag(self, kind, ev.x, ev.y)
  end)

  -- Track the cursor for the hover highlight (never preventDefault -> normal hovering/camera is untouched). The
  -- grab captures moves DURING a drag, so this only feeds the idle hover state.
  self.moveHook = hafen.hook():input("mapview", "mousemove", function(ev)
    if self.alive then self.cursor = { x = ev.x, y = ev.y } end
  end)

  return {
    detach     = function() detach(self) end,
    destroy    = function() detach(self) end,
    setMode    = function(_, m) self.mode = normMode(m, self.mode) end,
    mode       = function() return self.mode end,
    isDragging = function() return self.drag ~= nil end,
  }
end

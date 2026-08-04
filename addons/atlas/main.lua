-- atlas — an EXAMPLE addon (037-map-database): a live minimap panel built from the map DATABASE alone.
--
-- The corner minimap you already have is the client drawing the map file. This is the same picture from the
-- other side: nothing here reads the live world except the one question "where am I", and everything it
-- SHOWS comes out of hafen.map — the recorded ground, and the markers recorded on it.
--
-- Four ordinary doors, one line each:
--   hafen.world.gridPos()        where the player is, as the anchor {gridId, x, y} — the ONLY thing this
--                                addon takes from the live world, and the only thing the two halves share.
--   hafen.map.grid(gridId)       that anchor's grid IN THE DATABASE. The door in.
--   grid:image(lvl)              the recorded ground as an IMAGE HANDLE — nil while it renders, so asking
--                                again next tick IS the retry loop (there is no callback and no ready event).
--   grid:segment():markers()     the pins recorded on that same explored area, in segment tile coords.
--
-- THE PANEL COSTS NOTHING TO PAINT, and that is the claim this addon exists to make. A grid drawing is an
-- ordinary image handle, so it goes into the stylesheet as `bg = {image = …}` and the ENGINE paints it every
-- frame: with the pin layer off, this addon runs ZERO draw callbacks and ZERO widget callbacks while a live
-- map is on the screen. Its whole cost is the 4-per-second timer below that asks "did the picture change?",
-- and a timer is charged to `timers`, never to `draw` — read it yourself in
-- hafen.client:profiling():addons(), or run the 037.5 suite, which asserts exactly that on this addon's row.
--
-- ':atlas pins' is the other half of that measurement, not a decoration: the markers are drawn from Lua, so
-- the panel is rebuilt WITH an onDraw and the same row starts reading one draw callback per frame. A zero
-- only means something beside a number that is not zero.
--
-- A LEVEL IS A SCALE, NOT A SIZE. Every drawing is 100x100 pixels; ':atlas zoom 2' does not make the panel
-- bigger, it makes each pixel four tiles across, so the same square shows 4x4 grids of explored ground. Which is
-- also why the panel only re-skins when it crosses into a new picture — at level 2 that is every four grids.
--
-- SAFE-tier: it declares no permissions. ':atlas mark' writes a marker into the user's own on-disk map
-- database, which is the one ungated write on this page (client-local and reversible by hand) — everything
-- else here reads.
--
-- See docs/addons/api/map.md and docs/addons/api/world.md#saving-a-world-position-across-sessions.

local TITLE = "Atlas"              -- the caption, and how the 037.5 suite finds this panel: window[title=Atlas]
local SIDE  = 100                  -- MCache.cmaps: a grid is 100x100 tiles and every drawing is 100x100 px,
                                   -- so a content area of exactly SIDE draws the picture 1:1 and untiled
local MAXLVL = 8
local RATE  = 0.25                 -- how often the panel asks the database whether its picture changed

local panel                        -- the window, or nil when it is closed
local shown                        -- the image handle currently installed as the panel's background
local here                         -- the Grid the player is standing on, as of the last tick
local lvl   = 0                    -- the zoom level the panel is drawn at
local pins  = false                -- is the (Lua-drawn) marker layer on?
local ticker                       -- the timer handle

-- ---- the picture ------------------------------------------------------------------------------------
-- Which ground a level-`lvl` drawing covers: the zoom grid that contains ours, aligned to 2^lvl grids. The
-- same arithmetic the engine's own alignment test does, and the reason a pin's tile coord can be placed on
-- the picture at all.
local function origin(grid)
  local step = 2 ^ lvl                            -- grids per zoom grid, and tiles per pixel
  local sc = grid:sc()
  return math.floor(sc.x / step) * step * SIDE,   -- the picture's left edge, in SEGMENT tile coords
         math.floor(sc.y / step) * step * SIDE,
         step
end

-- ---- the pin layer (only installed when ':atlas pins' is on) -----------------------------------------
local function drawPins(g, w, h)
  local grid = here
  if not grid then return end
  local ox, oy, step = origin(grid)
  local seg = grid:segment()

  for _, m in ipairs(seg:markers()) do
    local tc = m:tc()
    local px, py = math.floor((tc.x - ox) / step), math.floor((tc.y - oy) / step)
    if (px >= 0) and (px < w) and (py >= 0) and (py < h) then
      local c = m:color()                          -- player markers carry one; system markers do not
      if c then g:color(c.r, c.g, c.b, 255) else g:color(220, 220, 235, 255) end
      g:frect(px - 2, py - 2, 5, 5)
      g:color(0, 0, 0, 255)
      g:rect(px - 2, py - 2, 5, 5)
    end
  end

  -- ...and the player, from the very anchor the panel is centred on
  local gp = hafen.world.gridPos()
  local wt = gp and hafen.world.worldToTile(gp.x, gp.y)     -- the within-grid tile, 0..99
  local sc = grid:sc()
  if wt then
    local px = math.floor((((sc.x * SIDE) + wt.x) - ox) / step)
    local py = math.floor((((sc.y * SIDE) + wt.y) - oy) / step)
    g:color(255, 255, 255, 255)
    g:line(px - 4, py, px + 4, py, 1)
    g:line(px, py - 4, px, py + 4, 1)
  end
  g:color()
end

-- ---- the panel --------------------------------------------------------------------------------------
local close, refresh

local function open(at)
  panel = hafen.ui.window{
    title = TITLE, size = { SIDE, SIDE }, pos = at or { 80, 80 },
    -- NO onDraw unless the pin layer is on. An onDraw that only checks a flag would still be a callback
    -- every frame, and the zero this addon claims would stop being a zero.
    onDraw = pins and drawPins or nil,
    onClose = function() close() end,
  }
  shown = nil
  refresh()
  ticker = hafen.timer.every(RATE, refresh)
end

close = function()
  if ticker then ticker:cancel() ticker = nil end
  if panel and panel:exists() then panel:destroy() end
  panel, shown, here = nil, nil, nil
end

-- The whole per-frame cost of this addon, four times a second: has the picture changed? `grid:image` answers
-- nil while it renders and the SAME handle once it has, so this is both the retry loop and the change test.
refresh = function()
  if not (panel and panel:exists()) then return end
  local gp = hafen.world.gridPos()
  here = gp and hafen.map.grid(gp.gridId)
  local img = here and here:image(lvl)
  if img ~= shown then
    shown = img
    if img then
      panel:skin{ bg = { image = img } }
    else
      panel:skin{ bg = { color = { 18, 18, 20, 230 } } }   -- unexplored, or still rendering
    end
  end
end

-- Rebuild in place, keeping where the user dragged it: the pin layer is a different WINDOW, because it is a
-- different cost.
local function rebuild()
  if not (panel and panel:exists()) then return end
  local p = panel:pos()
  close()
  open{ p.x, p.y }
end

-- ---- the command ------------------------------------------------------------------------------------
hafen.slash.register("atlas", function(args)
  local sub = args and args[1]

  if sub == nil then
    if panel and panel:exists() then
      close()
      hafen.log("atlas: closed")
    else
      open()
      hafen.log(("atlas: the recorded map at zoom %d, pins %s"):format(lvl, pins and "on" or "off"))
    end

  elseif sub == "zoom" then
    local n = tonumber(args[2])
    if (n == nil) or (n < 0) or (n > MAXLVL) or (n ~= math.floor(n)) then
      return hafen.log("atlas: ':atlas zoom <0.." .. MAXLVL .. ">' -- 0 is one grid, each level four times the ground")
    end
    lvl, shown = n, nil
    refresh()
    hafen.log(("atlas: zoom %d -- one pixel is %d tiles, the panel covers %d"):format(n, 2 ^ n, SIDE * (2 ^ n)))

  elseif sub == "pins" then
    pins = not pins
    rebuild()
    hafen.log("atlas: the marker layer is " .. (pins and "ON -- drawn from Lua, one draw callback a frame"
                                                     or "OFF — the panel is painted by the engine alone"))

  elseif sub == "where" then
    local gp = hafen.world.gridPos()
    local g = gp and hafen.map.grid(gp.gridId)
    if not g then return hafen.log("atlas: the map has not streamed in here yet") end
    local sc, mt = g:sc(), g:mtime()
    -- The anchor is the line worth reading twice: gridId is the SERVER's and means the same thing to every
    -- player, while the segment id and the grid coord beside it are this client's own bookkeeping — look at
    -- them, never store them (docs/addons/api/map.md#saving-a-position).
    hafen.log(("atlas: anchor {gridId=%s, x=%.0f, y=%.0f} -- segment %s, grid coord %d,%d, recorded at %s")
              :format(gp.gridId, gp.x, gp.y, g:segment():id(), sc.x, sc.y,
                      mt and ("%.0f"):format(mt) or "unknown"))

  elseif sub == "mark" then
    local p = hafen.player() and hafen.player():gob() and hafen.player():gob():pos()
    if not p then return hafen.log("atlas: no player position yet") end
    local nm = args[2] or "Atlas"
    local m = hafen.map.markers.add(nm, p.x, p.y, { color = { 90, 220, 120 }, onmap = true })
    hafen.log(m and ("atlas: dropped the marker \"" .. nm .. "\" -- ':atlas pins' to see it on the panel")
                or "atlas: the map database is not ready")

  else
    hafen.log("atlas: ':atlas' opens or closes the panel | 'zoom <0..8>' | 'pins' | 'where' | 'mark [name]'")
  end
end)

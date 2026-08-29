-- Gob Cache Map -- the trees and boulders you have walked past, kept, and findable again.
--
-- WHAT IT IS FOR. You want a fir, or a gneiss boulder, and you know you have walked past a dozen of each.
-- This addon writes down every tree and boulder any of your characters loads, so the answer is already on
-- disk by the time you go looking: type a name, pick a row, and the map inside the window goes there.
--
-- WHAT IS NEVER WRITTEN DOWN TWICE. A tree does not move, so a tree IS its place: the SERVER's grid id plus
-- the offset inside that grid -- exactly what Position:info() hands back, and the one anchor no segment
-- merge ever rewrites. The cache is keyed on it, `entries[gridId]["x,y"] = kind`, so seeing the same oak on
-- Monday, on Tuesday, and out of a second character's eyes writes ONE row, with no scan to find that out. A
-- DIFFERENT resource at a place we already hold overwrites it, which is what a mined-out boulder looks like.
--
-- WHERE IT IS KEPT. `savedata/account/gob-cache-map.json` -- one JSON file, written by the engine. The
-- sandbox installs no `io`, so a file inside the addon's own folder is not something an addon can write; a
-- saved variable IS that JSON file. The scope is the account because the recorded map is one database for
-- every character you play in that world, and so is this.
--
-- HOW IT LOOKS. The map panel is the client's own minimap art -- `grid:image(level)` is what the corner map
-- draws -- with a heatmap of whatever the filter matches laid over it and the search hits marked on top. So
-- "where are the firs" is answered as a picture before you have read a single row.
--
--   +- Gob Cache Map ----------------------------------------------+
--   | [fir............] [x] Trees [x] Boulders   [ Here ] [ View ]  |
--   | +--------------+ +---------------------------------------+   |
--   | | 1. Fir   12t | |      recorded ground (grid:image)      |   |
--   | | 2. Fir   40t | |      + heat cells + hit marks          |   |
--   | | 3. Fir   77t | |      + you, and the crosshair          |   |
--   | +--------------+ +---------------------------------------+   |
--   | 8412 cached (7190 trees, 1222 boulders)  34 shown  zoom 0     |
--   +--------------------------------------------------------------+
--
-- WHY THERE IS NO `GobAdded` HANDLER. Subscribing to it makes the client hold EVERY arriving object out of
-- the scene until the handler has run -- a cost paid by the whole client, for immediacy a cache has no use
-- for. A sweep every few seconds writes down the same objects and costs nothing anybody can see.

local NAME = "Gob Cache Map"

local GRID       = 1100    -- world units across one map grid (100 tiles of 11)
local TILE       = 11      -- world units across one tile
local SWEEP      = 3       -- seconds between world sweeps
local PRUNE_R    = 60      -- only forget what the character is standing right next to
local PRUNE_MISS = 3       -- ...and only after this many sweeps in a row have missed it
local FLUSH      = 20      -- seconds between disk flushes while the cache is growing
local MAX_ROWS   = 200     -- hits the list shows at once
local LOC_TTL    = 60      -- seconds a grid's segment coord is trusted (a merge moves them)
local SETTLE     = 2       -- seconds between a heatmap rebuild and between two re-rankings of the list
local MOVED      = 55      -- world units the character walks before the list is worth re-ranking

-- The panel, in design pixels. A heat cell is 25 px at every zoom, which is what the `div` below buys.
local MAPW, MAPH = 430, 372
local LISTW      = 232
local ICON, HALF = 14, 7   -- the box a hit's minimap icon is drawn in, and half of it
local ZOOM       = { { s = 1, div = 4 }, { s = 2, div = 2 }, { s = 4, div = 1 } }

-- A kind is a substring of the resource name, because that is the only thing the client can be asked. Add a
-- row here and the cache, the filter, the list and the heatmap all pick it up; nothing else knows the set.
local KINDS = {
  { key = "tree",    label = "Trees",    match = "terobjs/trees/",    color = {  96, 200, 112 } },
  { key = "boulder", label = "Boulders", match = "terobjs/bumlings/", color = { 210, 192, 150 } },
}

-- ---------------------------------------------------------------- the cache on disk

local db = hafen.store():get("cache")
local ui = hafen.store():get("ui")

if db.v ~= 1 then                                  -- no layout but this one is ever read
  for k in pairs(db) do db[k] = nil end
  db.v = 1
end
db.kinds   = db.kinds   or {}                      -- index -> resource name, so a row costs one number
db.icons   = db.icons   or {}                      -- index -> that kind's minimap icon, by DISPLAY name
db.entries = db.entries or {}                      -- gridId -> { ["x,y"] = kind index }

local kindOf, kindClass, kindLabel = {}, {}, {}    -- rebuilt on load; none of it is stored

local function classify(res)
  for _, k in ipairs(KINDS) do
    if res:find(k.match, 1, true) then return k end
  end
  return nil
end

local function pretty(res)
  local last = res:match("([^/]+)$") or res
  return last:sub(1, 1):upper() .. last:sub(2)
end

local function learn(i)
  local res = db.kinds[i]
  kindOf[res]  = i
  kindClass[i] = classify(res)
  kindLabel[i] = pretty(res)
end

for i = 1, #db.kinds do learn(i) end

local function kindId(res)
  local i = kindOf[res]
  if i then return i end
  db.kinds[#db.kinds + 1] = res
  i = #db.kinds
  learn(i)
  return i
end

-- A hit is drawn as the client's OWN minimap icon for that thing. `gob:icon()` answers the icon's DISPLAY
-- name -- its tooltip, and the very string `hafen.map():icon()` names a category by, out of the same call --
-- so the registry is what turns one into the resource `g:resource` draws. That registry grows as a character
-- sees new types, so a name that does not resolve yet is asked again rather than written off.
local iconRes, iconAt, iconAsks = {}, 0, {}        -- kind index -> resource | false; when; asks made this run
local ICON_ASKS = 10                               -- give up asking a kind after this many sightings

local function refreshIcons()
  local byName = {}
  for _, cat in ipairs(hafen.map():icon():list()) do
    local n = cat:name()
    if n then byName[n] = cat:res() end
  end
  iconRes = {}
  for i = 1, #db.kinds do
    local want = db.icons[i]
    iconRes[i] = (want and byName[want]) or false
  end
  iconAt = os.time()
end

local counts, total = {}, 0

local function recount()
  counts, total = {}, 0
  for _, cells in pairs(db.entries) do
    for _, ki in pairs(cells) do
      local c = kindClass[ki]
      local key = c and c.key or "other"
      counts[key] = (counts[key] or 0) + 1
      total = total + 1
    end
  end
end
recount()

-- ---------------------------------------------------------------- writing one down

local dirty     = true         -- the heatmap no longer matches the cache
local unsaved   = false
local lastFlush = os.time()

local function bump(key, by)
  counts[key] = (counts[key] or 0) + by
end

-- Returns the cell key when the gob is one of ours and has a place, whether or not it was new.
local function remember(gob)
  local res = gob:name()
  if not res then return nil end
  local c = classify(res)
  if not c then return nil end
  local p = gob:position()
  local at = p and p:info()
  if not at then return nil end                    -- ground this character cannot anchor: not ours to keep

  local cell  = math.floor(at.x + 0.5) .. "," .. math.floor(at.y + 0.5)
  local cells = db.entries[at.gridId]
  if not cells then cells = {}; db.entries[at.gridId] = cells end

  local ki, was = kindId(res), cells[cell]
  -- Asked of the live object, because that is the only thing that knows: a resource name says nothing about
  -- whether the game draws an icon for it. It answers nil while the icon's own resource is still loading, so
  -- a kind is asked again on its next sighting -- and only so many times, since most things have none.
  if (db.icons[ki] == nil) and ((iconAsks[ki] or 0) < ICON_ASKS) then
    iconAsks[ki] = (iconAsks[ki] or 0) + 1
    local ic = gob:icon()
    if ic then db.icons[ki], iconAt, unsaved = ic, 0, true end
  end
  if was == ki then return cell end                -- the same thing, in the same grid, at the same spot
  cells[cell] = ki
  if was then
    local old = kindClass[was]
    if old then bump(old.key, -1) end
  else
    total = total + 1
  end
  bump(c.key, 1)
  dirty, unsaved = true, true
  return cell
end

local function forget(gridId, cell)
  local cells = db.entries[gridId]
  local ki = cells and cells[cell]
  if not ki then return end
  cells[cell] = nil
  local c = kindClass[ki]
  if c then bump(c.key, -1) end
  total = total - 1
  dirty, unsaved = true, true
end

-- ---------------------------------------------------------------- where a grid sits, this minute

local locs, locsAt = {}, 0

local function gridLoc(gid)                        -- gridId -> { seg, sx, sy }, or nil
  local now = os.time()
  if (now - locsAt) > LOC_TTL then locs, locsAt = {}, now end
  local l = locs[gid]
  if l ~= nil then return l or nil end             -- `false` is the memo for "the database has not got it"
  local gr  = hafen.map():grid():get(gid)
  local sc  = gr and gr:segmentCoord()
  local seg = gr and gr:segment()
  if not (sc and seg) then locs[gid] = false; return nil end
  l = { seg = seg:id(), sx = sc.x, sy = sc.y }
  locs[gid] = l
  return l
end

local function playerAt(s)                         -- the durable form of where a character stands, or nil
  s = s or hafen.session():current()
  local me = s and s:player() and s:player():gob()
  local p  = me and me:position()
  return p and p:info() or nil
end

-- ---------------------------------------------------------------- the sweep, and forgetting a felled tree

local misses = {}                                  -- "gridId|cell" -> sweeps in a row it was missing

local function prune(s, seen)
  local at = playerAt(s)
  local home = at and gridLoc(at.gridId)
  if not home then return end
  local anchor = hafen.map():grid():get(at.gridId)
  local seg = anchor and anchor:segment()
  if not seg then return end

  local pwx, pwy = home.sx * GRID + at.x, home.sy * GRID + at.y
  for dx = -1, 1 do
    for dy = -1, 1 do
      local gr = seg:grid():get({ x = home.sx + dx, y = home.sy + dy })
      local gid = gr and gr:id()
      local cells = gid and db.entries[gid]
      if cells then
        local ox, oy = (home.sx + dx) * GRID, (home.sy + dy) * GRID
        for cell in pairs(cells) do
          local cx, cy = cell:match("^(-?%d+),(-?%d+)$")
          if cx then
            local wx, wy = ox + tonumber(cx), oy + tonumber(cy)
            if ((wx - pwx) ^ 2 + (wy - pwy) ^ 2) <= (PRUNE_R * PRUNE_R) then
              local k = gid .. "|" .. cell
              if seen[k] then
                misses[k] = nil
              else
                -- Standing right beside a place and not seeing what we wrote there, three sweeps running:
                -- it was felled, mined or built over. Anything further off is simply not streamed in.
                local n = (misses[k] or 0) + 1
                if n >= PRUNE_MISS then
                  misses[k] = nil
                  forget(gid, cell)
                else
                  misses[k] = n
                end
              end
            end
          end
        end
      end
    end
  end
end

local function sweep()
  local seen = {}
  for _, s in ipairs(hafen.session():list()) do
    if s:exists() and s:character() then
      local gobs = s:world():gob()
      for _, k in ipairs(KINDS) do
        for _, gob in ipairs(gobs:list(k.match)) do
          local cell = remember(gob)
          if cell then
            local p = gob:position()
            local at = p and p:info()
            if at then seen[at.gridId .. "|" .. cell] = true end
          end
        end
      end
    end
  end
  for _, s in ipairs(hafen.session():list()) do
    if s:exists() and s:character() then prune(s, seen) end
  end
  if unsaved and ((os.time() - lastFlush) >= FLUSH) then
    hafen.store():flush()
    lastFlush, unsaved = os.time(), false
  end
end

-- ---------------------------------------------------------------- the filter, the hits and the heatmap

local enabled = {}
for _, k in ipairs(KINDS) do enabled[k.key] = (ui[k.key] ~= false) end
local query    = ui.query or ""
local showHeat = (ui.heat ~= false)

local results, rowOf, chosen = {}, {}, nil
local heat, heatMax, heatSeg, heatDiv = {}, 0, nil, 0
local heatAt = 0                                   -- when it was last walked; 0 asks for it now

local function wantedSet()
  local q, w = query:lower(), {}
  for i = 1, #db.kinds do
    local c = kindClass[i]
    w[i] = (c ~= nil) and enabled[c.key]
      and ((q == "")
        or (kindLabel[i]:lower():find(q, 1, true) ~= nil)
        or (db.kinds[i]:lower():find(q, 1, true) ~= nil)
        or (c.key:find(q, 1, true) ~= nil))
  end
  return w
end

local function research()
  local w = wantedSet()
  local at = playerAt()
  local home = at and gridLoc(at.gridId)
  local pwx = home and (home.sx * GRID + at.x) or 0
  local pwy = home and (home.sy * GRID + at.y) or 0

  local grids = {}
  for gid, cells in pairs(db.entries) do
    local any = false
    for _, ki in pairs(cells) do
      if w[ki] then any = true; break end
    end
    if any then
      local l = gridLoc(gid)
      if l then
        local d = -1
        if home and (l.seg == home.seg) then
          local cx, cy = l.sx * GRID + GRID * 0.5, l.sy * GRID + GRID * 0.5
          d = math.sqrt((cx - pwx) ^ 2 + (cy - pwy) ^ 2)
        end
        grids[#grids + 1] = { id = gid, l = l, cells = cells, d = d }
      end
    end
  end
  -- Grids first, hits second: there are hundreds of the one and tens of thousands of the other, so walking
  -- the near grids until the list is full bounds the sort to about what the list can show.
  table.sort(grids, function(a, b)
    if (a.d < 0) ~= (b.d < 0) then return b.d < 0 end
    return a.d < b.d
  end)

  local out = {}
  for _, gh in ipairs(grids) do
    for cell, ki in pairs(gh.cells) do
      if w[ki] then
        local cx, cy = cell:match("^(-?%d+),(-?%d+)$")
        if cx then
          cx, cy = tonumber(cx), tonumber(cy)
          local d = -1
          if home and (gh.l.seg == home.seg) then
            d = math.sqrt((gh.l.sx * GRID + cx - pwx) ^ 2 + (gh.l.sy * GRID + cy - pwy) ^ 2)
          end
          out[#out + 1] = { gridId = gh.id, x = cx, y = cy, ki = ki, dist = d,
                            seg = gh.l.seg, sx = gh.l.sx, sy = gh.l.sy }
        end
      end
    end
    if #out >= MAX_ROWS then break end
  end
  table.sort(out, function(a, b)
    if (a.dist < 0) ~= (b.dist < 0) then return b.dist < 0 end
    if a.dist ~= b.dist then return a.dist < b.dist end
    if a.gridId ~= b.gridId then return a.gridId < b.gridId end
    if a.x ~= b.x then return a.x < b.x end
    return a.y < b.y
  end)
  while #out > MAX_ROWS do out[#out] = nil end
  results = out
end

local function reheat(segId, div)
  heat, heatMax, heatSeg, heatDiv = {}, 0, segId, div
  if not segId then return end
  local w, step = wantedSet(), GRID / div
  for gid, cells in pairs(db.entries) do
    local l = gridLoc(gid)
    if l and (l.seg == segId) then
      local bx0, by0 = l.sx * div, l.sy * div
      for cell, ki in pairs(cells) do
        if w[ki] then
          local cx, cy = cell:match("^(-?%d+),(-?%d+)$")
          if cx then
            local k = (bx0 + math.floor(tonumber(cx) / step)) .. "," ..
                      (by0 + math.floor(tonumber(cy) / step))
            local n = (heat[k] or 0) + 1
            heat[k] = n
            if n > heatMax then heatMax = n end
          end
        end
      end
    end
  end
end

-- ---------------------------------------------------------------- the view

local view = { gridId = ui.gridId, x = ui.x or (GRID * 0.5), y = ui.y or (GRID * 0.5),
               level = math.min(2, math.max(0, ui.level or 0)) }

local function look(gridId, x, y)
  view.gridId, view.x, view.y = gridId, x, y
  ui.gridId, ui.x, ui.y = gridId, x, y
end

local function lookHere()
  local at = playerAt()
  if not at then return false end
  look(at.gridId, at.x, at.y)
  return true
end

-- Everything the map draws and everything a click on it resolves is this one frame: the segment being
-- drawn, how many design pixels a world unit is worth, and where that segment's origin sits on the panel.
local function frame(w, h)
  local anchor = view.gridId and hafen.map():grid():get(view.gridId)
  local sc  = anchor and anchor:segmentCoord()
  local seg = anchor and anchor:segment()
  if not (sc and seg) then return nil end
  local z   = ZOOM[view.level + 1]
  local ppu = 100 / (z.s * GRID)
  return { seg = seg, id = seg:id(), s = z.s, div = z.div, ppu = ppu,
           ox = w * 0.5 - (sc.x * GRID + view.x) * ppu,
           oy = h * 0.5 - (sc.y * GRID + view.y) * ppu }
end

-- Above level 0 one drawing covers a block of grids and every grid in it hands back that same picture, so
-- the block is asked for by its corners: the origin grid alone may be one the database never recorded.
local function blockGrid(seg, bx, by, s)
  local x, y, last = bx * s, by * s, s - 1
  local gr = seg:grid():get({ x = x, y = y })
  if gr or (s == 1) then return gr end
  return seg:grid():get({ x = x + last, y = y })
      or seg:grid():get({ x = x, y = y + last })
      or seg:grid():get({ x = x + last, y = y + last })
end

local RAMP = { {  40,  90, 210,  70 },             -- thin blue: one or two
               {  60, 190, 140, 110 },             -- green
               { 240, 210,  70, 155 },             -- yellow
               { 235,  70,  55, 200 } }            -- red: as thick as this cache gets

local function ramp(t)
  if t < 0 then t = 0 elseif t > 1 then t = 1 end
  local f = t * (#RAMP - 1)
  local i = math.floor(f)
  if i > (#RAMP - 2) then i = #RAMP - 2 end
  local k, a, b = f - i, RAMP[i + 1], RAMP[i + 2]
  return math.floor(a[1] + (b[1] - a[1]) * k), math.floor(a[2] + (b[2] - a[2]) * k),
         math.floor(a[3] + (b[3] - a[3]) * k), math.floor(a[4] + (b[4] - a[4]) * k)
end

-- ---------------------------------------------------------------- the window

local win, entry, list, status, mapw
local checks = {}

local function distText(r)
  if r.dist < 0 then return "far" end
  return math.floor(r.dist / TILE + 0.5) .. "t"
end

local function applyRows()
  local rows = {}
  rowOf = {}
  for i, r in ipairs(results) do
    local t = i .. ". " .. kindLabel[r.ki] .. "   " .. distText(r)
    rows[i] = t                                    -- the index keeps every row string its own value
    rowOf[t] = r
  end
  list:rows(rows)                                  -- this clears the selection, which `chosen` outlives
end

local refreshedAt, refreshedNear = 0, nil

local function refresh()
  research()
  applyRows()
  refreshedAt, refreshedNear = os.time(), playerAt()
end

local function selectRow(r)
  for text, row in pairs(rowOf) do
    if row == r then list:value(text); return end
  end
end

local function statusText()
  local parts = {}
  for _, k in ipairs(KINDS) do
    parts[#parts + 1] = (counts[k.key] or 0) .. " " .. k.key .. "s"
  end
  local t = ZOOM[view.level + 1].s * GRID / 100 / TILE
  return total .. " cached (" .. table.concat(parts, ", ") .. ")   "
      .. #results .. " shown   zoom " .. view.level
      .. " (1 px = " .. t .. ((t == 1) and " tile)" or " tiles)")
end

local function drawMap(ev)
  local g, w, h = ev:g(), ev:w(), ev:h()
  g:color(16, 18, 20)
  g:frect(0, 0, w, h)

  local f = frame(w, h)
  if not f then
    g:color(170, 175, 180)
    g:text(view.gridId and "this ground has not been written down yet"
                        or "nothing to look at yet -- press Here", 8, 8)
    g:color()
    return
  end

  -- WHITE FIRST. An image is drawn THROUGH the draw colour -- Tex.crender multiplies by it -- so the plate
  -- colour above would tint the whole map down to near-black. The map's own colours are the point of it.
  g:color()

  -- the recorded ground: the client's own minimap art, one blit per drawing
  for bx = math.floor(-f.ox / 100), math.floor((w - f.ox) / 100) do
    for by = math.floor(-f.oy / 100), math.floor((h - f.oy) / 100) do
      local gr  = blockGrid(f.seg, bx, by, f.s)
      local img = gr and gr:image(view.level)      -- nil while it renders; the next frame has it
      if img then g:image(img, math.floor(f.ox + bx * 100), math.floor(f.oy + by * 100)) end
    end
  end

  -- the heatmap of everything the filter matches, at a cell of 25 design px whatever the zoom
  if showHeat and (heatSeg == f.id) and (heatMax > 0) then
    local cell = 100 / (f.s * heatDiv)
    for bx = math.floor(-f.ox / cell), math.floor((w - f.ox) / cell) do
      for by = math.floor(-f.oy / cell), math.floor((h - f.oy) / cell) do
        local n = heat[bx .. "," .. by]
        if n then
          local r, gg, b, a = ramp(math.sqrt(n / heatMax))
          g:color(r, gg, b, a)
          g:frect(math.floor(f.ox + bx * cell), math.floor(f.oy + by * cell),
                  math.ceil(cell), math.ceil(cell))
        end
      end
    end
  end

  -- the hits the list is showing, each drawn as the client's own minimap icon for the thing
  for _, r in ipairs(results) do
    if r.seg == f.id then
      local x = math.floor(f.ox + (r.sx * GRID + r.x) * f.ppu)
      local y = math.floor(f.oy + (r.sy * GRID + r.y) * f.ppu)
      if (x > -ICON) and (x < w + ICON) and (y > -ICON) and (y < h + ICON) then
        local res = iconRes[r.ki]
        if res then
          g:color()                                -- untinted: the icon is the client's art, not ours to dye
          g:resource(res, x - HALF, y - HALF, ICON, ICON)
        else
          local c = kindClass[r.ki]                -- nothing the game draws an icon for: a pip in its colour
          g:color(0, 0, 0, 210)
          g:poly(x, y - 4, x + 4, y, x, y + 4, x - 4, y)
          g:color(c.color[1], c.color[2], c.color[3])
          g:poly(x, y - 2, x + 2, y, x, y + 2, x - 2, y)
        end
      end
    end
  end

  -- the one that was picked, named where it stands
  if chosen and (chosen.seg == f.id) then
    local x = math.floor(f.ox + (chosen.sx * GRID + chosen.x) * f.ppu)
    local y = math.floor(f.oy + (chosen.sy * GRID + chosen.y) * f.ppu)
    g:color(255, 240, 120)
    g:rect(x - HALF - 2, y - HALF - 2, ICON + 4, ICON + 4)
    g:text(kindLabel[chosen.ki], x + HALF + 3, y - 7, { color = { 255, 240, 120 } })
  end

  -- the character on screen
  local at = playerAt()
  local pl = at and gridLoc(at.gridId)
  if pl and (pl.seg == f.id) then
    local x = math.floor(f.ox + (pl.sx * GRID + at.x) * f.ppu)
    local y = math.floor(f.oy + (pl.sy * GRID + at.y) * f.ppu)
    g:color(0, 0, 0, 220)
    g:frect(x - 3, y - 3, 7, 7)
    g:color(255, 255, 255)
    g:frect(x - 2, y - 2, 5, 5)
  end

  -- where the panel is pointed, and the edge of it
  g:color(255, 255, 255, 70)
  g:line(w * 0.5 - 7, h * 0.5, w * 0.5 + 7, h * 0.5, 1)
  g:line(w * 0.5, h * 0.5 - 7, w * 0.5, h * 0.5 + 7, 1)
  g:color(74, 84, 74)
  g:rect(0, 0, w, h)
  g:color()
end

local function pick(r)
  if not r then return end
  chosen = { gridId = r.gridId, x = r.x, y = r.y, ki = r.ki, seg = r.seg, sx = r.sx, sy = r.sy }
  look(r.gridId, r.x, r.y)
end

local function mapPressed(ev)
  local f = frame(MAPW, MAPH)
  if not f then ev:preventDefault(); return end

  -- a hit under the pointer is a pick; anywhere else is a place to look at
  local best, bestd
  for _, r in ipairs(results) do
    if r.seg == f.id then
      local dx = (f.ox + (r.sx * GRID + r.x) * f.ppu) - ev:x()
      local dy = (f.oy + (r.sy * GRID + r.y) * f.ppu) - ev:y()
      local d  = dx * dx + dy * dy
      if (d <= 64) and ((not bestd) or (d < bestd)) then best, bestd = r, d end
    end
  end
  if best then
    pick(best)
    selectRow(best)
  else
    -- empty ground: look there, and let the pick go, which is also how the list starts re-ranking again
    local wx = (ev:x() - f.ox) / f.ppu
    local wy = (ev:y() - f.oy) / f.ppu
    local gx, gy = math.floor(wx / GRID), math.floor(wy / GRID)
    local gr = f.seg:grid():get({ x = gx, y = gy })
    if gr then look(gr:id(), wx - gx * GRID, wy - gy * GRID) end
    chosen = nil
  end
  ev:preventDefault()
end

-- The 3D view is aimed from the step: a button's own handler holds the addon layer's tree, and the map view
-- it would be moving stands in the character's.
local function focusWorld()
  local target = chosen
  if not target then hafen.log():write(NAME .. ": pick a row first"); return end
  hafen.timer():after(0, function()
    local s = hafen.session():current()
    if not s then hafen.log():write(NAME .. ": no character on screen"); return end
    local p = s:world():position({ gridId = target.gridId, x = target.x, y = target.y })
    if not (p and p:x()) then
      hafen.log():write(NAME .. ": that place is not in this character's part of the world")
      return
    end
    local ok, err = pcall(function() s:world():focus(p) end)
    if not ok then
      hafen.log():write(NAME .. ": the 3D view only aims under the rts camera -- " .. tostring(err))
    end
  end)
end

local function build()
  if win then return end
  local W = LISTW + 6 + MAPW
  local H = 26 + MAPH + 20

  win = hafen.ui():window():title(NAME):size(W, H):position(120, 80)
  win:remember("window")

  entry = hafen.ui():entry():parent(win):position(0, 0):size(LISTW):value(query)
  entry:tooltip("part of a species or a kind: fir, boulder, gneiss")

  local x = LISTW + 8
  for _, k in ipairs(KINDS) do
    local c = hafen.ui():check():parent(win):position(x, 2):size(76):text(k.label):value(enabled[k.key])
    checks[k.key] = c
    c:on("Changed", function(on)
      enabled[k.key] = on
      ui[k.key] = on
      chosen, dirty, heatAt = nil, true, 0   -- another set of hits: the old pick is not in it
      refresh()
    end)
    x = x + 80
  end

  local heatBox = hafen.ui():check():parent(win):position(x, 2):size(76):text("Heat"):value(showHeat)
  heatBox:tooltip("lay a heatmap of the matching objects over the map")
  heatBox:on("Changed", function(on)
    showHeat = on
    ui.heat = on
    dirty, heatAt = true, 0                  -- switched back on, the picture is rebuilt before it is shown
  end)

  local here = hafen.ui():button():parent(win):position(W - 176, 0):size(84):text("Here")
  here:tooltip("look at the character on screen")
  local aim = hafen.ui():button():parent(win):position(W - 86, 0):size(86):text("View")
  aim:tooltip("aim the 3D view at the picked row (rts camera)")

  list = hafen.ui():listbox():parent(win):position(0, 26):size(LISTW, MAPH):rowHeight(16)
  mapw = hafen.ui():widget():parent(win):position(LISTW + 6, 26):size(MAPW, MAPH):name("map")
  status = hafen.ui():label():parent(win):position(0, 26 + MAPH + 4):text("")

  entry:on("Changed", function(text)
    query = text
    ui.query = text
    chosen, dirty, heatAt = nil, true, 0   -- a new search: the map keeps its place, the pick does not
    refresh()
  end)
  entry:on("Submitted", function()
    if results[1] then
      pick(results[1])
      selectRow(results[1])
    end
  end)
  list:on("Changed", function(row) pick(rowOf[row]) end)
  here:on("Pressed", function()
    if not lookHere() then hafen.log():write(NAME .. ": no character on screen") end
  end)
  aim:on("Pressed", focusWorld)
  mapw:on("Draw", drawMap)
  mapw:on("MouseDown", mapPressed)
  mapw:on("Wheel", function(ev)
    local step = (ev:amount() > 0) and 1 or -1
    view.level = math.max(0, math.min(#ZOOM - 1, view.level + step))
    ui.level = view.level
    ev:preventDefault()
  end)
  win:on("Close", function()
    win:visible(false)
    ui.open = false
  end)
end

local function show(on)
  build()
  win:visible(on)
  ui.open = on
  if on then
    if not view.gridId then lookHere() end
    refresh()
  end
end

-- ---------------------------------------------------------------- the beat

local statusShown = nil

local function moved(a, b)
  if not b then return true end
  if a.gridId ~= b.gridId then return true end
  return ((a.x - b.x) ^ 2 + (a.y - b.y) ^ 2) > (MOVED * MOVED)
end

local function tick()
  if not (win and win:visible()) then return end
  if not view.gridId then lookHere() end           -- left open across a relog: find the character again

  local f = frame(MAPW, MAPH)
  local segId = f and f.id or nil
  local div   = f and f.div or 0
  local now   = os.time()

  -- The heatmap walks the whole cache, so it is rebuilt on a beat of its own rather than once per object
  -- the sweep writes down: a walk through a forest sets `dirty` every few seconds, for hours. A zoom or a
  -- step into another segment is not on that beat -- the picture would be wrong, not merely late.
  if showHeat and ((segId ~= heatSeg) or (div ~= heatDiv) or (dirty and ((now - heatAt) >= SETTLE))) then
    reheat(segId, div)                             -- off the draw pass, which must only draw
    heatAt, dirty = now, false
  end
  if (now - iconAt) > LOC_TTL then refreshIcons() end   -- the registry grows; a kind with no icon yet re-asks

  -- Distances go stale as you walk. Re-ranking under a selection would take the row out from under the
  -- pointer, and rewriting the rows while nobody has moved would fight the scrollbar -- so the list is
  -- re-read only when nothing is picked and the character has walked far enough for the order to matter.
  if (not chosen) and ((now - refreshedAt) >= SETTLE) then
    local at = playerAt()
    if at and moved(at, refreshedNear) then refresh() end
  end

  local text = statusText()
  if text ~= statusShown then
    status:text(text)                              -- a write resizes the label, so only a change is written
    statusShown = text
  end
end

hafen.event():on("Load", function()
  build()
  win:visible(ui.open == true)
  if ui.open then refresh() end
  hafen.log():write(NAME .. ": " .. total .. " object(s) cached -- ':gobcache' opens it")
end)

hafen.event():on("Disable", function()
  if unsaved then pcall(function() hafen.store():flush() end) end
end)

hafen.timer():every(SWEEP, sweep)
hafen.timer():every(0.5, tick)

hafen.client():options():keybindings():on("toggle", function()
  hafen.timer():after(0, function() show(not (win and win:visible())) end)
end)

hafen.console():on("gobcache", function(args)
  local what = args[1]
  -- A console line holds the character's own tree, and everything below touches ours.
  hafen.timer():after(0, function()
    if what == "stats" then
      local parts = {}
      for _, k in ipairs(KINDS) do parts[#parts + 1] = (counts[k.key] or 0) .. " " .. k.key .. "s" end
      local grids = 0
      for _ in pairs(db.entries) do grids = grids + 1 end
      hafen.log():write(NAME .. ": " .. total .. " object(s) (" .. table.concat(parts, ", ")
                        .. ") over " .. grids .. " grid(s), " .. #db.kinds .. " resource(s) known")
    elseif what == "scan" then
      sweep()
      hafen.log():write(NAME .. ": swept -- " .. total .. " object(s) cached")
    elseif what == "clear" then
      if args[2] ~= "yes" then
        hafen.log():write(NAME .. ": ':gobcache clear yes' throws away all " .. total .. " of them")
        return
      end
      db.entries, db.kinds, db.icons = {}, {}, {}
      kindOf, kindClass, kindLabel, misses = {}, {}, {}, {}
      iconRes, iconAt, iconAsks = {}, 0, {}
      recount()
      chosen, dirty, unsaved = nil, true, true
      hafen.store():flush()
      build()
      refresh()
      hafen.log():write(NAME .. ": cache emptied")
    else
      show(not (win and win:visible()))
    end
  end)
end)

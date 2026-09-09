-- Trellis Builder -- three buttons name a block pile, a string pile and a patch of ground, and Start
-- fills the patch with trellises: fetch the materials, walk there, place, feed the site, next slot.
--
-- THE ORDER OF THE LOOP IS NOT A PREFERENCE. While a building ghost is on the cursor the map view
-- sends `place` for ANY mouse button (src/haven/MapView.java), so nothing else on the map can be
-- clicked while one is up -- a pile included. The build action is therefore the LAST thing done before
-- the placement click, and a ghost left over from a refused slot is taken back with a right-click,
-- which is the only way a player takes one back either. One trellis per trip: fetch, walk, raise the
-- ghost, place, feed, repeat.
--
-- WHY THE PATHFINDING IS IN HERE. The client has no path verb: s:player():move(p) is exactly the click
-- a player makes on a patch of ground, and the server walks the character at it until something stops
-- it. So the route is worked out here, over the tile lattice, and handed to that verb one waypoint at a
-- time -- an A* whose three costs are the three things this client can actually read about a tile: what
-- its terrain resource is called (s:world():tile), how high it stands (s:world():height), and whether
-- an object's own footprint (gob:hitbox) crosses it. Everything the search knows, it read.
--
-- IT IS A MOVE THAT FINDS ITS OWN WAY, NOT A TOUR OF TILES. Two doors, `walkTo(s, place, reach)` and
-- `clickGob(s, gob, button)`, are the only ones the machine walks through, and everything else names
-- the THING it is going to -- the slot the trellis goes in, the pile it is drawing from -- never a
-- tile. The lattice is how the way round is worked out; the tile centres it hands back are only ever
-- intermediate, a line-of-sight pass drops every one the character can walk straight past, and the
-- last waypoint is always the destination itself. On open ground that is the only waypoint there is.
--
-- HOW MANY FIT IN A TILE is not a number in this file either. The trellis's footprint is read off the
-- ghost the build action puts on the cursor -- placing:hitbox(), the same rings the finished object
-- wears -- turned back out of the ghost's own facing so it is the object's box rather than the cursor's,
-- and a tile is filled with as many copies of that box as its 11 world units hold.
--
-- WHAT A MATERIAL IS, IS THE AREA IT COMES OUT OF. Nothing here matches an item by name: a pile inside
-- the block area holds blocks, whatever the server calls them, so the resource names are learnt from
-- the piles -- the backpack is counted before a withdrawal and read after it, and whatever arrived is
-- that material from then on. The names are remembered with the area that taught them, so re-picking
-- an area forgets them, and a pile holding two kinds of the same thing teaches both.
--
-- One thing this cannot see, and it is handled by watching rather than by knowing: a footprint is NOT
-- the clearance the server checks before it will let you put one down (docs/addons/api/gob.md -- the
-- `build` box is a different ring and no verb answers it), so a slot the arithmetic likes may still be
-- refused. The bot places, looks for the site that should have appeared, and takes the next slot when
-- none did.

----------------------------------------------------------------------------------------------------
-- What a trellis is made of, and what the parts are called
----------------------------------------------------------------------------------------------------

-- One trellis. Change the counts here if your server's recipe differs; nothing else reads them.
local RECIPE = {
  {kind = "block",  need = 3},
  {kind = "string", need = 1},
}

-- The two roles, and the area each is fetched from. There is no name here on purpose: WHAT a material
-- is, is the area it comes out of. A pile inside the block area holds blocks whatever the server calls
-- them, so the resource names are learnt from the piles themselves (see `learnRes`) and nothing in this
-- addon has to be told them.
local MATERIAL = {
  block  = {label = "Block",  area = "blocks"},
  string = {label = "String", area = "strings"},
}

local BUILD_ACTION = "Trellis"                    -- the action-menu entry that puts the ghost up
local SITE_RES     = "gfx/terobjs/consobj"        -- the stakes and string a placement leaves behind
local TRELLIS_RES  = "gfx/terobjs/plants/trellis" -- the finished thing
local PILE_MATCH   = "stockpile"                  -- gob resource substring for a pile of anything
local BUILD_BUTTON = "Build"                      -- the caption preferred when a window has two buttons
local PILE_TITLE   = "Stockpile"                  -- the one window with a material box that is not a site

local BATCH = 1        -- trellises' worth of material one trip to the piles fetches

----------------------------------------------------------------------------------------------------
-- The lattice, the walk, and the waiting
----------------------------------------------------------------------------------------------------

local TILE       = 11      -- world units across one tile; this file's one magic number
local TICK       = 0.2     -- seconds between two beats of the machine
local REACH      = 8.0     -- how close to stand to a place before acting on it
local STANDOFF   = 4.5     -- world units left between the character's own body and a footprint
local CLICK_NEAR = 33.0    -- close enough to click an object even if the walk got no closer
local STAND_REACH = 4.0    -- how near the standing spot counts as standing on it
local ARRIVE     = 5.0     -- how close to a waypoint counts as having reached it
local GOAL_SLACK = 1       -- tiles: the search may stop this far short of a goal it cannot enter

local CLIFF_STEP = 2.5     -- height difference between two tiles that stops a step
local WATER      = {"water", "deep", "swim", "ocean"}   -- tile-name substrings you do not walk on

local MAX_EXPAND   = 2500  -- most tiles one search opens before it gives up
local MAX_AREA     = 400   -- most tiles a build area may hold
local OBSTACLE_R   = 220   -- world units of gobs a search rasterises around the character
local OBSTACLE_TTL = 4     -- seconds an obstacle set is kept before it is read again
local REPLAN       = 3.0   -- seconds between two route recomputations while walking
local RESEND       = 1.0   -- seconds between two orders to the same waypoint
local STUCK        = 3.0   -- seconds of standing still that earn a fresh route
local NO_PROGRESS  = 12.0  -- seconds without getting nearer the target before the leg is given up
local PROGRESS     = 1.0   -- world units nearer that count as having got somewhere

local WAIT_GHOST = 6.0     -- seconds the ghost is waited for after the action is used
local WAIT_SITE  = 6.0     -- seconds the site is waited for after a placement
local WAIT_BUILT = 20.0    -- seconds the site is given to become a trellis
local BUILD_EVERY = 1.5    -- seconds between two presses of that window's Build button
local WAIT_WND   = 6.0     -- seconds a stockpile window is waited for
local WAIT_XFER  = 3.0     -- seconds one withdrawal is given to land in the backpack

local SLOT_MARGIN = 0.5    -- world units of slack around a footprint when a tile is divided up

----------------------------------------------------------------------------------------------------
-- Small helpers
----------------------------------------------------------------------------------------------------

local function say(text) hafen.log():write("trellis-builder: " .. text) end

-- LuaJ ignores a precision in string.format, so a rounded number is built by hand.
local function num(n)
  if not n then return "?" end
  return tostring(math.floor(n + 0.5))
end

local function cur() return hafen.session():current() end

local function try(fn)
  local ok, v = pcall(fn)
  if ok then return v end
  return nil
end

local function myGob(s) return try(function() return s:player():gob() end) end
local function myPos(s)
  local g = myGob(s)
  return g and g:position() or nil
end

-- A tile key that survives negative coordinates. Session tile coords are relative to where that
-- character logged in, so they stay well inside this range.
local function key(tx, ty) return ((tx + 32768) * 65536) + (ty + 32768) end

local function tileOf(p) return p and try(function() return p:tileCoord() end) or nil end

local function centre(s, tx, ty)
  return try(function() return s:world():position((tx * TILE) + (TILE / 2), (ty * TILE) + (TILE / 2)) end)
end

local function distanceTo(s, p)
  if not p then return nil end
  return try(function() return s:world():distance(p) end)
end

local function lower(v) return (v or ""):lower() end

local function isWaterTile(name)
  local n = lower(name)
  if n == "" then return false end
  for _, w in ipairs(WATER) do
    if n:find(w, 1, true) then return true end
  end
  return false
end

-- The machine's own clock: the tick counts it, so nothing here depends on what os.clock() measures.
local clock = 0

----------------------------------------------------------------------------------------------------
-- The three areas
----------------------------------------------------------------------------------------------------

-- areas.blocks / .strings / .build = {a = Position, b = Position}. A Position goes into the store as it
-- is and comes back a Position, so nothing here converts (docs/addons/api/position.md).
local AREA_NAME = {
  blocks  = "block pile area",
  strings = "string pile area",
  build   = "build area",
}

local function areas() return hafen.store():get("areas") end

local function areaBox(which)
  local a = areas()[which]
  if not (a and a.a and a.b) then return nil end
  local ta, tb = tileOf(a.a), tileOf(a.b)
  if not (ta and tb) then return nil end
  return {
    x0 = math.min(ta.x, tb.x), x1 = math.max(ta.x, tb.x),
    y0 = math.min(ta.y, tb.y), y1 = math.max(ta.y, tb.y),
  }
end

local function areaTiles(which)
  local b = areaBox(which)
  if not b then return nil, false end
  local list, over = {}, false
  for ty = b.y0, b.y1 do
    for tx = b.x0, b.x1 do
      if #list >= MAX_AREA then over = true break end
      list[#list + 1] = {x = tx, y = ty}
    end
    if over then break end
  end
  return list, over
end

local function inBox(b, tx, ty)
  return b and (tx >= b.x0) and (tx <= b.x1) and (ty >= b.y0) and (ty <= b.y1)
end

-- What the piles in one area have turned out to hold. Remembered WITH the area, because that is what
-- makes it true: re-picking an area writes a fresh record and the names are learnt again. A set rather
-- than one name, so a block area holding two kinds of block teaches both.
local function resSet(kind)
  local a = areas()[MATERIAL[kind].area]
  if not a then return {} end
  a.res = a.res or {}
  return a.res
end

local function resKnown(kind) return next(resSet(kind)) ~= nil end

local function learnRes(kind, res)
  if not res then return false end
  local set = resSet(kind)
  if set[res] then return false end
  set[res] = true
  hafen.store():flush()
  say(MATERIAL[kind].label .. ": " .. res)
  return true
end

local function matchesMaterial(item, kind)
  local res = item:res()
  return (res ~= nil) and (resSet(kind)[res] == true)
end

local function areaLabel(which)
  local a = areas()[which]
  if not (a and a.a and a.b) then return "not set" end
  local b = areaBox(which)
  if not b then return "set (off this character's map)" end
  return ((b.x1 - b.x0) + 1) .. " x " .. ((b.y1 - b.y0) + 1) .. " tiles"
end

----------------------------------------------------------------------------------------------------
-- What stands in the way
----------------------------------------------------------------------------------------------------

-- Every gob whose resource carries a footprint has its rings walked edge by edge and the tiles under
-- them marked. The EDGES rather than the filled polygon: a wall is one long thin ring whose bounding
-- box would swallow half a village, and an outline nothing can step over is as good as a fill for a
-- search that also refuses to cut a corner between two blocked neighbours.
local obstacles = {at = -1000, set = nil, session = nil, ignore = nil, standing = nil}

local function markSegment(set, ax, ay, bx, by)
  local dx, dy = bx - ax, by - ay
  local len = math.sqrt((dx * dx) + (dy * dy))
  local steps = math.max(1, math.ceil(len / (TILE / 3)))
  for i = 0, steps do
    local t = i / steps
    local x, y = ax + (dx * t), ay + (dy * t)
    set[key(math.floor(x / TILE), math.floor(y / TILE))] = true
  end
end

-- `ignore` is the object being WALKED TO, and it is left out. Nothing you are going to is in your way:
-- a pile is wider than the tile it stands in, so counting its own footprint would put a wall around
-- every stockpile and leave the route stopping a tile short and picking its way in by tile centres.
local function obstacleSet(s, ignore)
  local me = myGob(s)
  local at = tileOf(myPos(s))
  local standing = at and key(at.x, at.y) or nil
  if obstacles.set and (obstacles.session == s) and (obstacles.ignore == ignore)
     and (obstacles.standing == standing) and ((clock - obstacles.at) < OBSTACLE_TTL) then
    return obstacles.set
  end
  local set = {}
  local list = try(function() return s:world():gob():within(OBSTACLE_R) end) or {}
  for _, g in ipairs(list) do
    if (g ~= me) and (g ~= ignore) then
      local rings = try(function() return g:hitbox() end)
      for _, ring in ipairs(rings or {}) do
        local n = #ring
        if n >= 2 then
          local px, py = nil, nil
          for i = 1, n + 1 do
            local q = ring[((i - 1) % n) + 1]
            local qx, qy = try(function() return q:x() end), try(function() return q:y() end)
            if qx and qy then
              if px then markSegment(set, px, py, qx, qy) end
              px, py = qx, qy
            end
          end
        end
      end
    end
  end
  -- The tile the character is STANDING IN is passable, whatever crosses it: it is standing there, so
  -- the question is settled. Only that one tile -- an object is not excused everywhere else it reaches
  -- for happening to touch this tile, which would drop a wall from the map for standing at one end of
  -- it, or the trellis just built from under the route out of its own tile.
  if standing then set[standing] = nil end
  obstacles.set, obstacles.at, obstacles.session = set, clock, s
  obstacles.ignore, obstacles.standing = ignore, standing
  return set
end

local function dropObstacles() obstacles.set = nil end

----------------------------------------------------------------------------------------------------
-- The search
----------------------------------------------------------------------------------------------------

local passCache, heightCache = {}, {}

local function passable(s, set, tx, ty)
  local k = key(tx, ty)
  if set[k] then return false end
  local c = passCache[k]
  if c ~= nil then return c end
  local p = centre(s, tx, ty)
  local t = p and try(function() return s:world():tile(p) end) or nil
  -- No tile means ground this character is not streaming: a route through it is a route through
  -- something nobody has looked at, so the search stops there and the walk re-plans when it arrives.
  local ok = (t ~= nil) and not isWaterTile(t.name)
  passCache[k] = ok
  return ok
end

local function heightAt(s, tx, ty)
  local k = key(tx, ty)
  local h = heightCache[k]
  if h ~= nil then
    if h == false then return nil end
    return h
  end
  local p = centre(s, tx, ty)
  local v = p and try(function() return s:world():height(p) end) or nil
  heightCache[k] = (v == nil) and false or v
  return v
end

local DIRS = {
  {1, 0}, {-1, 0}, {0, 1}, {0, -1},
  {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
}

local function push(heap, node, f)
  local i = #heap + 1
  heap[i] = {node = node, f = f}
  while i > 1 do
    local p = math.floor(i / 2)
    if heap[p].f <= heap[i].f then break end
    heap[p], heap[i] = heap[i], heap[p]
    i = p
  end
end

local function pop(heap)
  local n = #heap
  if n == 0 then return nil end
  local top = heap[1]
  heap[1] = heap[n]
  heap[n] = nil
  n = n - 1
  local i = 1
  while true do
    local l, r, m = i * 2, (i * 2) + 1, i
    if (l <= n) and (heap[l].f < heap[m].f) then m = l end
    if (r <= n) and (heap[r].f < heap[m].f) then m = r end
    if m == i then break end
    heap[i], heap[m] = heap[m], heap[i]
    i = m
  end
  return top
end

local function hcost(x, y, gx, gy)
  local dx, dy = math.abs(x - gx), math.abs(y - gy)
  return (dx + dy) - (0.5858 * math.min(dx, dy))
end

local function stepOk(s, set, fx, fy, tx, ty)
  if not passable(s, set, tx, ty) then return false end
  if (fx ~= tx) and (fy ~= ty) then
    -- No cutting the corner between two blocked orthogonals.
    if not passable(s, set, tx, fy) then return false end
    if not passable(s, set, fx, ty) then return false end
  end
  local a, b = heightAt(s, fx, fy), heightAt(s, tx, ty)
  if a and b and (math.abs(a - b) > CLIFF_STEP) then return false end
  return true
end

-- A* over the tile lattice. Answers the tile path, first tile included, and the flag saying whether it
-- actually reached the goal -- a partial route toward the closest tile it could open is still worth
-- walking, because the ground ahead streams in as you go and the next re-plan sees further.
local function findPath(s, from, to, slack, ignore)
  local set = obstacleSet(s, ignore)
  passCache, heightCache = {}, {}
  local open, came, gs, closed = {}, {}, {}, {}
  local sk = key(from.x, from.y)
  gs[sk] = 0
  push(open, {x = from.x, y = from.y}, hcost(from.x, from.y, to.x, to.y))
  local expanded, reached = 0, nil
  local best, bestH = {x = from.x, y = from.y}, hcost(from.x, from.y, to.x, to.y)
  while true do
    local top = pop(open)
    if not top then break end
    local n = top.node
    local nk = key(n.x, n.y)
    if not closed[nk] then
      closed[nk] = true
      expanded = expanded + 1
      local h = hcost(n.x, n.y, to.x, to.y)
      if h < bestH then best, bestH = n, h end
      if math.max(math.abs(n.x - to.x), math.abs(n.y - to.y)) <= slack then reached = n break end
      if expanded >= MAX_EXPAND then break end
      for i = 1, 8 do
        local mx, my = n.x + DIRS[i][1], n.y + DIRS[i][2]
        local mk = key(mx, my)
        if not closed[mk] and stepOk(s, set, n.x, n.y, mx, my) then
          local ng = gs[nk] + (((DIRS[i][1] ~= 0) and (DIRS[i][2] ~= 0)) and 1.4142 or 1)
          if (gs[mk] == nil) or (ng < gs[mk]) then
            gs[mk] = ng
            came[mk] = n
            push(open, {x = mx, y = my}, ng + hcost(mx, my, to.x, to.y))
          end
        end
      end
    end
  end
  local endNode = reached or best
  if (endNode.x == from.x) and (endNode.y == from.y) then return nil, (reached ~= nil) end
  local back, n = {}, endNode
  while n do
    back[#back + 1] = n
    n = came[key(n.x, n.y)]
  end
  local tiles = {}
  for i = #back, 1, -1 do tiles[#tiles + 1] = back[i] end
  return tiles, (reached ~= nil)
end

-- Is the straight line between two PLACES clear? Sampled thrice a tile, which is finer than the
-- lattice the answer is read off.
--
-- THE TWO END TILES DO NOT COUNT. The question is whether the ground BETWEEN two places can be walked,
-- and something standing at either end is not an answer to it: the character is already in the one and
-- is walking to the other, and the server does that last step itself. Counting them would make every
-- slot in a tile that already holds a trellis unreachable in a straight line -- so the route would be
-- broken into tile centres for no reason, and the character would walk to the middle of the tile
-- instead of to the spot it is building on.
local function clearBetween(s, set, ax, ay, bx, by)
  local atx, aty = math.floor(ax / TILE), math.floor(ay / TILE)
  local btx, bty = math.floor(bx / TILE), math.floor(by / TILE)
  local dx, dy = bx - ax, by - ay
  local steps = math.max(1, math.ceil(math.sqrt((dx * dx) + (dy * dy)) / (TILE / 3)))
  for i = 0, steps do
    local t = i / steps
    local tx = math.floor((ax + (dx * t)) / TILE)
    local ty = math.floor((ay + (dy * t)) / TILE)
    local atEnd = ((tx == atx) and (ty == aty)) or ((tx == btx) and (ty == bty))
    if not atEnd and not passable(s, set, tx, ty) then return false end
  end
  return true
end

----------------------------------------------------------------------------------------------------
-- The walk
----------------------------------------------------------------------------------------------------

local walk = {target = nil, points = nil, i = 0, planned = -1000, sent = -1000,
              lastX = nil, lastY = nil, still = 0, best = nil, bestAt = 0, partial = false}

-- Has the walk stopped getting nearer? The walker's own progress clock, asked from outside.
local function walkStalled() return (clock - walk.bestAt) > STUCK end

local function walkStop()
  walk.target, walk.points, walk.i = nil, nil, 0
  walk.lastX, walk.lastY, walk.still = nil, nil, 0
  walk.best, walk.bestAt = nil, clock
end

-- The route to a place, as a list of places. The lattice is how the way round is worked out; it is
-- NOT where the character is asked to stand -- so the tile centres the search hands back are only
-- ever intermediate, the last waypoint is the target itself, and a line-of-sight pass over the whole
-- list drops every centre the character can simply walk past. On open ground that leaves one
-- waypoint: the target.
local function plan(s, target, ignore)
  local here = myPos(s)
  local from, to = tileOf(here), tileOf(target)
  if not (from and to) then return false end
  local hx, hy = try(function() return here:x() end), try(function() return here:y() end)
  local tx, ty = try(function() return target:x() end), try(function() return target:y() end)
  if not (hx and hy and tx and ty) then return false end

  local tiles, reached = findPath(s, from, to, GOAL_SLACK, ignore)
  local set = obstacleSet(s, ignore)

  local pts = {{x = hx, y = hy}}                       -- the anchor the smoothing measures from
  for i = 2, #(tiles or {}) do
    local t = tiles[i]
    pts[#pts + 1] = {x = (t.x * TILE) + (TILE / 2), y = (t.y * TILE) + (TILE / 2)}
  end
  pts[#pts + 1] = {x = tx, y = ty}

  local keep, i = {}, 1
  while i < #pts do
    local j, k = i + 1, i + 2
    while (k <= #pts) and clearBetween(s, set, pts[i].x, pts[i].y, pts[k].x, pts[k].y) do
      j = k
      k = k + 1
    end
    keep[#keep + 1] = j
    i = j
  end

  local points = {}
  for n, at in ipairs(keep) do
    if at == #pts then
      points[#points + 1] = target                     -- the target object itself, not a copy of it
    else
      local q = pts[at]
      local pl = try(function() return s:world():position(q.x, q.y) end)
      if pl then points[#points + 1] = pl end
    end
  end
  walk.points, walk.i, walk.planned = points, 1, clock
  walk.sent, walk.partial = -1000, not reached
  return true
end

-- A STEP ACROSS THE WAY OUT, for a character that will not move. Nothing on the lattice explains one:
-- a tile is 11 units of "clear" or "blocked", and the thing actually in the way is INSIDE the tile
-- being stood in -- the trellis just built four units to the south, the stump beside the door. Another
-- route down the same line would be the same line. So the answer is to move aside first and carry on
-- from there, which is what a player does. Across the way out before back down it: perpendicular to
-- the target is the direction least likely to hold whatever is not being seen.
local function sidestep(s, target)
  local p, set = myPos(s), obstacleSet(s)
  local px = p and try(function() return p:x() end) or nil
  local py = p and try(function() return p:y() end) or nil
  local tx = try(function() return target:x() end)
  local ty = try(function() return target:y() end)
  if not (px and py and tx and ty) then return nil end
  local dx, dy = tx - px, ty - py
  local len = math.sqrt((dx * dx) + (dy * dy))
  if len < 0.01 then return nil end
  dx, dy = dx / len, dy / len
  local ways = {{-dy, dx}, {dy, -dx}, {-dx, -dy}}
  for _, v in ipairs(ways) do
    local qx, qy = px + (v[1] * TILE * 1.5), py + (v[2] * TILE * 1.5)
    if passable(s, set, math.floor(qx / TILE), math.floor(qy / TILE)) then
      local q = try(function() return s:world():position(qx, qy) end)
      if q then return q end
    end
  end
  return nil
end

-- ONE BEAT OF A MOVE THAT FINDS ITS OWN WAY THERE. This and `clickGob` below are the only two doors
-- the machine walks through: everything else names a destination and never a route. Answers
-- "arrived", "walking" or "blocked".
local function walkTo(s, target, reach, ignore)
  local d = distanceTo(s, target)
  if d and (d <= reach) then
    walkStop()
    return "arrived"
  end
  if (walk.target ~= target) then
    walkStop()
    walk.target = target
    if not plan(s, target, ignore) then return "walking" end
  elseif (clock - walk.planned) > REPLAN then
    dropObstacles()
    plan(s, target, ignore)
  end

  -- GIVING UP IS ABOUT PROGRESS, NOT ABOUT MOVEMENT. A goal behind a wall with open ground in front of
  -- it leaves the character walking the whole time -- shuffling between two spots along the wall as
  -- each fresh route picks a different closest-reachable tile -- so a test for standing still would
  -- never fire and the leg would run for ever. What is watched is the distance to the target itself.
  if d and ((walk.best == nil) or (d < (walk.best - PROGRESS))) then
    walk.best, walk.bestAt = d, clock
  end
  if (clock - walk.bestAt) > NO_PROGRESS then
    walkStop()
    return "blocked"
  end

  local p = myPos(s)
  if not p then return "walking" end
  local x, y = try(function() return p:x() end), try(function() return p:y() end)
  if x and y then
    if walk.lastX and (math.abs(x - walk.lastX) + math.abs(y - walk.lastY) < 1.0) then
      walk.still = walk.still + TICK
    else
      walk.still = 0
    end
    walk.lastX, walk.lastY = x, y
  end
  -- Standing still is not a verdict, only a reason to try something else. A step aside goes in front
  -- of the waypoint being walked to, so the ordinary machinery drives it and the route picks up where
  -- it left off; if there is nowhere to step, a fresh route is all there is to try.
  if walk.still > STUCK then
    walk.still = 0
    dropObstacles()
    local aside = walk.points and sidestep(s, target) or nil
    if aside then
      table.insert(walk.points, walk.i, aside)
      walk.sent = -1000
    else
      plan(s, target, ignore)
    end
  end

  local points = walk.points or {}
  while (walk.i <= #points) do
    local wp = points[walk.i]
    local wd = distanceTo(s, wp)
    if wd and (wd <= ARRIVE) and (walk.i < #points) then
      walk.i = walk.i + 1
      walk.sent = -1000
    else
      break
    end
  end
  if walk.i > #points then
    walkStop()
    return "arrived"
  end
  if (clock - walk.sent) >= RESEND then
    walk.sent = clock
    local wp = points[walk.i]
    local ok = pcall(function() s:player():move(wp) end)
    if not ok then
      -- A waypoint that character cannot locate: drop it and take the next one.
      walk.i = walk.i + 1
      walk.sent = -1000
    end
  end
  return "walking"
end

-- HOW FAR AN OBJECT REACHES FROM ITS OWN MIDDLE, read off its rings. An object is not a point: a
-- stockpile is a whole tile across, so a fixed distance to its middle is a distance the character can
-- never get to -- it collides with the pile at its edge, plus its own body, and stands there ordering
-- itself forward until the leg is given up. The footprint is the only honest source for that number,
-- and STANDOFF is what the character's own body needs on top.
local function gobRadius(gob)
  local c = try(function() return gob:position() end)
  local cx = c and try(function() return c:x() end) or nil
  local cy = c and try(function() return c:y() end) or nil
  local rings = try(function() return gob:hitbox() end)
  if not (cx and cy and rings) then return nil end
  local far = 0
  for _, ring in ipairs(rings) do
    for _, q in ipairs(ring) do
      local qx, qy = try(function() return q:x() end), try(function() return q:y() end)
      if qx and qy then
        local d = math.sqrt(((qx - cx) ^ 2) + ((qy - cy) ^ 2))
        if d > far then far = d end
      end
    end
  end
  return far
end

-- THE SAME MOVE, ENDING IN A CLICK ON THE OBJECT. Answers "clicked" once the click has gone out,
-- "walking", "blocked", "gone" for an object that left, and "failed" when the click was refused.
--
-- The gob's place is read ONCE and kept: gob:position() mints a new Position on every call and two
-- Positions are never equal, so a walk aimed at a fresh one would recompute its whole route every
-- beat. The click goes to the OBJECT, not to the ground the character happens to be standing on.
local reached = {gob = nil, at = nil}

local function clickGob(s, gob, button, reach)
  if not (gob and gob:exists()) then return "gone" end
  if reached.gob ~= gob then
    reached.gob, reached.at = gob, gob:position()
    reached.reach = math.max(reach or REACH, (gobRadius(gob) or 0) + STANDOFF)
    walkStop()
  end
  if not reached.at then return "gone" end
  local how = walkTo(s, reached.at, reached.reach, gob)
  if how ~= "arrived" then
    -- NEAR ENOUGH AND NOT GETTING NEARER IS NEAR ENOUGH. A click on something out of arm's reach is
    -- one the server answers by walking the character the last step itself -- which is what a player's
    -- click across the yard does too -- so being wedged among a pile's neighbours is not a failure
    -- worth a dozen seconds of shuffling. Only a walk that stalled FAR from the thing has failed.
    local d = distanceTo(s, reached.at)
    if not (d and (d <= CLICK_NEAR) and walkStalled()) then return how end
    walkStop()
  end
  if not pcall(function() s:world():click(gob, button or 1) end) then return "failed" end
  return "clicked"
end

----------------------------------------------------------------------------------------------------
-- The backpack, and the piles
----------------------------------------------------------------------------------------------------

local function inventory(s) return try(function() return s:ui():inventory() end) end

local function countMaterial(s, kind)
  local inv = inventory(s)
  if not inv then return 0 end
  local items = try(function() return inv:items():list() end) or {}
  local n = 0
  for _, it in ipairs(items) do
    if matchesMaterial(it, kind) then n = n + (it:quantity() or 1) end
  end
  return n
end

-- The nearest pile standing inside one of the named areas.
local function pileIn(s, which)
  local b = areaBox(which)
  if not b then return nil end
  local list = try(function() return s:world():gob():list(PILE_MATCH) end) or {}
  local best, bestD = nil, nil
  for _, g in ipairs(list) do
    local t = tileOf(g:position())
    if t and inBox(b, t.x, t.y) then
      local d = try(function() return g:distance() end)
      if d and ((bestD == nil) or (d < bestD)) then best, bestD = g, d end
    end
  end
  return best
end

-- THE SITE'S OWN WINDOW, AND ITS BUILD BUTTON. Nothing is carried into it and nothing is dropped in
-- its boxes: the boxes state what the site wants, and pressing Build is what takes the materials out
-- of the backpack. So the whole of building is finding that button and sending it the message its own
-- click sends -- which is why the materials are fetched BEFORE walking over, and why the backpack is
-- all that has to be right when the press goes out.
-- The window a material box stands in. Walked up from the box rather than looked up by a caption,
-- because the caption is a word the server chose and this addon must not depend on knowing it.
local function windowOf(w)
  local up = w
  for _ = 1, 12 do
    if not up then return nil end
    if try(function() return up:is("window") end) then return up end
    up = try(function() return up:parent() end)
  end
  return nil
end

-- THE SITE'S OWN WINDOW: the one with a material box in it that is not a Stockpile's, which carries a
-- box too. Answers the window and its caption.
local function siteWindow(s)
  for _, box in ipairs(try(function() return s:ui():matchAll("@ISBox") end) or {}) do
    local win = windowOf(box)
    local title = win and try(function() return win:title() end) or nil
    if win and (title ~= PILE_TITLE) then return win, title end
  end
  return nil, nil
end

-- EVERY BUTTON UNDER THAT WINDOW, asked of the session's whole tree in the chained form the selectors
-- exist for: the space is the descendant combinator, so this is "a button with that window above it".
-- Asking a window handle to search inside itself would be a second mechanism to get wrong.
local function buildButtons(s, win, title)
  local list = {}
  if title then
    list = try(function() return s:ui():matchAll("window[title=" .. title .. "] button") end) or {}
  end
  if (#list == 0) and win then
    list = try(function() return win:matchAll("button") end) or {}
  end
  return list
end

-- The one to press: the captioned one where there is a choice, and otherwise the only one there is.
local function buildButton(s, win, title)
  local list = buildButtons(s, win, title)
  if #list == 0 then return nil end
  for _, b in ipairs(list) do
    if try(function() return b:text() end) == BUILD_BUTTON then return b, #list end
  end
  return list[1], #list
end

-- What that window actually holds, said out loud. Every round trip about a window the client draws and
-- this addon has to recognise is one command instead of a guess.
local function describeWindow(s, win, title)
  if not win then say("no build window is open"); return end
  say("window [" .. (title or "?") .. "] " .. (try(function() return win:type() end) or "?"))
  for i, b in ipairs(try(function() return win:matchAll("@ISBox") end) or {}) do
    say("  box " .. i .. ": " .. (try(function() return b:text() end) or "?")
        .. "  " .. (try(function() return b:res() end) or "no res yet"))
  end
  local buttons = buildButtons(s, win, title)
  if #buttons == 0 then say("  no button under it at all") end
  for i, b in ipairs(buttons) do
    say("  button " .. i .. ": [" .. (try(function() return b:text() end) or "?")
        .. "] " .. (try(function() return b:type() end) or "?")
        .. " id=" .. tostring(try(function() return b:id() end)))
  end
end

local function stockpileBox(s)
  return try(function() return s:ui():match("window[title=" .. PILE_TITLE .. "] @ISBox") end)
end

----------------------------------------------------------------------------------------------------
-- The trellis's own footprint, and the slots it cuts a tile into
----------------------------------------------------------------------------------------------------

-- {w, h, ox, oy} in world units: the box the object stands in, and where that box sits relative to the
-- point a placement is anchored at.
local footprint = nil

local function learnFootprint(pl)
  if footprint then return true end
  local rings = try(function() return pl:hitbox() end)
  if not rings or (#rings == 0) then return false end
  local c = try(function() return pl:position() end)
  local cx = c and try(function() return c:x() end) or nil
  local cy = c and try(function() return c:y() end) or nil
  if not (cx and cy) then return false end
  local a = try(function() return pl:facing() end) or 0
  local ca, sa = math.cos(-a), math.sin(-a)
  local minx, miny, maxx, maxy
  for _, ring in ipairs(rings) do
    for _, q in ipairs(ring) do
      local qx, qy = try(function() return q:x() end), try(function() return q:y() end)
      if qx and qy then
        local dx, dy = qx - cx, qy - cy
        local lx, ly = (dx * ca) - (dy * sa), (dx * sa) + (dy * ca)
        minx = (minx == nil) and lx or math.min(minx, lx)
        maxx = (maxx == nil) and lx or math.max(maxx, lx)
        miny = (miny == nil) and ly or math.min(miny, ly)
        maxy = (maxy == nil) and ly or math.max(maxy, ly)
      end
    end
  end
  if not minx then return false end
  footprint = {w = maxx - minx, h = maxy - miny, ox = (minx + maxx) / 2, oy = (miny + maxy) / 2}
  say("trellis footprint " .. num(footprint.w) .. " x " .. num(footprint.h) .. " world units")
  return true
end

-- Every place inside one tile a trellis can be anchored at. With no footprint read yet the tile takes
-- one, at its centre -- the first placement is what teaches the box, and the tile is redivided then.
local function slotsFor(s, tile)
  local w = (footprint and footprint.w or TILE) + SLOT_MARGIN
  local h = (footprint and footprint.h or TILE) + SLOT_MARGIN
  local cols = math.max(1, math.floor(TILE / w))
  local rows = math.max(1, math.floor(TILE / h))
  local ox = footprint and footprint.ox or 0
  local oy = footprint and footprint.oy or 0
  local x0, y0 = tile.x * TILE, tile.y * TILE
  local list = {}
  for r = 1, rows do
    for c = 1, cols do
      local cx = x0 + ((c - 0.5) * (TILE / cols))
      local cy = y0 + ((r - 0.5) * (TILE / rows))
      -- The anchor is the object's own origin, and the box need not be centred on it.
      local p = try(function() return s:world():position(cx - ox, cy - oy) end)
      if p then list[#list + 1] = p end
    end
  end

  return list
end

-- How close something has to stand to a slot to be standing IN it. Half the footprint's narrow side:
-- a constant cannot serve, because the slots of a long thin object are cut closer together than the
-- object is long, and a radius wide enough to notice one sitting in the slot would refuse its neighbour.
local function slotClearance()
  local w = footprint and footprint.w or TILE
  local h = footprint and footprint.h or TILE
  return math.max(1.0, math.min(w, h) / 2)
end

-- THE SUPPLY: the middle of the area the materials come out of. It is the one FIXED point in the job,
-- and both the fill order and the standing spot are measured from it -- because the character comes
-- back from there before every single trellis, so "the far side of the tile" means the far side from
-- there and nothing else. Measuring from the CHARACTER cannot serve: it ends every trellis standing
-- beside the one it just built, so "farthest from me" flips to the other end of the tile each time and
-- walks it back and forth across its own work.
local function supplyPoint(s)
  local b = areaBox(MATERIAL.block.area)
  if not b then return nil end
  return try(function() return s:world():position(((b.x0 + b.x1 + 1) / 2) * TILE,
                                                  ((b.y0 + b.y1 + 1) / 2) * TILE) end)
end

-- HOW FAR THE TRELLIS ITSELF REACHES from the point it is anchored at.
local function slotRadius()
  if not footprint then return TILE / 2 end
  return math.sqrt((((footprint.w / 2) ^ 2)) + (((footprint.h / 2) ^ 2)))
end

-- WHERE TO STAND TO PUT ONE DOWN -- which is NOT the spot it goes on. The character's own body
-- occupies the ground it is standing on, and the server refuses a placement whose ground is occupied:
-- walk onto the slot and every placement there is refused, which is the one failure that looks exactly
-- like a bad footprint.
--
-- So it stands a tile clear, on the side of the TILE the character is coming from -- the side rather
-- than the slot, so that filling one tile is not a shuffle around it, and the same spot serves every
-- slot in it. The placement click then does the last step itself, exactly as it does for a player who
-- places a building from where they happen to be standing.
local function standFor(s, slot, tileAt)
  local sx = try(function() return slot:x() end)
  local sy = try(function() return slot:y() end)
  if not (sx and sy) then return slot end
  -- The side the supply is on. The character stands between the supply and the spot, so everything it
  -- has already built is BEYOND the spot rather than behind its back, and the next slot -- always
  -- nearer the supply than the last -- is never on the far side of something standing.
  local from = supplyPoint(s) or myPos(s)
  local fx = (from and try(function() return from:x() end)) or sx
  local fy = (from and try(function() return from:y() end)) or (sy - 1)
  local dx, dy = fx - sx, fy - sy
  local len = math.sqrt((dx * dx) + (dy * dy))
  if len < 0.01 then dx, dy, len = 0, -1, 1 end
  dx, dy = dx / len, dy / len

  local away = TILE + slotRadius()
  local set = obstacleSet(s)
  local ways = {{dx, dy}, {-dy, dx}, {dy, -dx}, {-dx, -dy}}
  local first = nil
  for _, v in ipairs(ways) do
    local qx, qy = sx + (v[1] * away), sy + (v[2] * away)
    local q = try(function() return s:world():position(qx, qy) end)
    if q then
      first = first or q
      if passable(s, set, math.floor(qx / TILE), math.floor(qy / TILE)) then return q end
    end
  end
  return first or slot
end

local function slotTaken(s, p)
  local clearance = slotClearance()
  local near = try(function() return s:world():gob():list(function(g)
    local n = g:name()
    if not n then return false end
    return (n == TRELLIS_RES) or (n == SITE_RES)
  end) end) or {}
  for _, g in ipairs(near) do
    local q = g:position()
    local d = q and try(function() return q:distance(p) end) or nil
    if d and (d < clearance) then return true end
  end
  return false
end

local function siteNear(s, p)
  local list = try(function() return s:world():gob():list(SITE_RES) end) or {}
  local best, bestD = nil, nil
  for _, g in ipairs(list) do
    local q = g:position()
    local d = q and try(function() return q:distance(p) end) or nil
    if d and (d < TILE) and ((bestD == nil) or (d < bestD)) then best, bestD = g, d end
  end
  return best
end

----------------------------------------------------------------------------------------------------
-- The job
----------------------------------------------------------------------------------------------------

local job = {
  running = false,
  phase = "idle",
  session = nil,
  status = "idle",
  tiles = nil, ti = 0,
  tileAt = nil,     -- the tile being filled, as a place to walk to before any slot is known
  slots = nil, si = 0, done = nil,
  slot = nil,
  standAt = nil,    -- where to stand to place at that slot -- never the slot itself
  siteAt = nil, siteReach = nil,
  site = nil,       -- the building site being fed
  pile = nil,       -- the stockpile being drawn from
  want = nil,       -- {kind, count} being fetched from a pile
  before = nil,     -- {have, total} when a withdrawal was sent
  seen = nil,       -- the items the backpack held then, so the one that arrives can be identified
  win = nil, winTitle = nil,   -- the site's own window, and the caption its buttons hang under
  said = false,     -- has the button that gets pressed been named once this run?
  pressed = nil,    -- when Build was last pressed, so it is not pressed every beat
  since = 0,        -- when the current phase started
  acted = false,    -- has this phase already fired its one write?
  built = 0, skipped = 0,
}

-- THE FARTHEST SLOT OF THIS TILE STILL TO FILL, measured from where the character is NOW. Farthest
-- first is the whole point: everything already standing is then BEHIND the next spot rather than
-- between the character and it, so nothing has to be reached over and no trellis is left standing
-- exactly where the character has to be for the next one.
--
-- Measured from the supply (see `supplyPoint`), which is fixed, so the tile is filled in one direction
-- from end to end rather than back and forth.
local function takeSlot(s)
  if not job.slots then return nil end
  local from = supplyPoint(s)
  local best, far = nil, nil
  for i, p in ipairs(job.slots) do
    if not job.done[i] then
      local d = (from and try(function() return p:distance(from) end)) or distanceTo(s, p) or 0
      if (far == nil) or (d > far) then best, far = i, d end
    end
  end
  if not best then return nil end
  job.done[best], job.si, job.slot = true, best, job.slots[best]
  return job.slot
end

-- Take back the ghost on the cursor. There is no cancel verb because the client has no cancel: while
-- a placement is up every button press on the map view is sent as `place`, and the SERVER answers a
-- right-click with `unplace` (src/haven/MapView.java). So this is that right-click, exactly.
local function dropGhost()
  local s = cur()
  local pl = s and try(function() return s:world():placing() end) or nil
  if not (pl and pl:exists()) then return false end
  local at = try(function() return pl:position() end) or myPos(s)
  if not at then return false end
  pcall(function() s:world():place(at, 0, 3) end)
  return true
end

local refresh                     -- forward: the window's own repaint
local function status(text)
  job.status = text
  if refresh then refresh() end
end

-- A phase that acts does so ONCE, on its first beat, and then waits for the world to answer. The flag
-- says which of the two this beat is: comparing the clock cannot, because the phase is entered at the
-- end of the previous beat and the clock has already moved on by the time the phase first runs.
local function phase(name)
  job.phase = name
  job.since = clock
  job.acted = false
end

local function waited() return clock - job.since end

local function stop(text)
  job.running = false
  walkStop()
  phase("idle")
  job.site, job.pile = nil, nil
  job.win, job.winTitle, job.pressed, job.want, job.before, job.seen = nil, nil, nil, nil, nil, nil
  status(text or "stopped")
  say(text or "stopped")
  -- Whatever was on the cursor is not left there for the user to discover on their next right-click.
  hafen.timer():after(0.3, dropGhost)
end

local function done()
  stop("done: " .. job.built .. " built, " .. job.skipped .. " slot(s) skipped")
end

-- How much of one material one trellis still wants, given what is in the backpack.
local function shortOf(s)
  for _, r in ipairs(RECIPE) do
    if countMaterial(s, r.kind) < r.need then return r.kind end
  end
  return nil
end

local function start()
  local s = cur()
  if not s then say("no character on screen"); return end
  if not areaBox("build") then say("the build area is not set"); return end
  for _, r in ipairs(RECIPE) do
    local which = MATERIAL[r.kind].area
    if not areaBox(which) then say("the " .. AREA_NAME[which] .. " is not set"); return end
  end
  local tiles, over = areaTiles("build")
  if not tiles or (#tiles == 0) then say("the build area holds no tiles this character can locate"); return end
  if over then say("the build area is bigger than " .. MAX_AREA .. " tiles -- only the first " .. MAX_AREA .. " are taken") end
  -- THE TILES GO IN THE SAME ORDER THE SLOTS DO: farthest from the supply first, so the finished field
  -- is always BEHIND the character and never between it and the piles. A filled tile is a wall -- four
  -- trellises leave gaps narrower than a body -- so working the other way round walls the character in
  -- among its own work, which is the one failure it cannot walk out of.
  local from = supplyPoint(s)
  if from then
    local far = {}
    for _, t in ipairs(tiles) do
      local c = centre(s, t.x, t.y)
      far[t] = (c and try(function() return c:distance(from) end)) or 0
    end
    table.sort(tiles, function(a, b) return far[a] > far[b] end)
  end
  job.session, job.tiles, job.ti = s, tiles, 0
  job.slots, job.done, job.si, job.slot = nil, nil, 0, nil
  job.tileAt, job.standAt = nil, nil
  job.siteAt, job.siteReach = nil, nil
  job.site, job.pile = nil, nil
  job.win, job.winTitle, job.pressed, job.want, job.before, job.seen = nil, nil, nil, nil, nil, nil
  job.built, job.skipped, job.said = 0, 0, false
  job.running = true
  walkStop()
  dropObstacles()
  phase("tile")
  status("running")
  say("building over " .. #tiles .. " tile(s)")
end

-- A resolved trellis ghost on the cursor: "ready", "waiting" while one is on its way, "failed" once
-- the job has been stopped. The action is used once per phase, on that phase's first beat, and the
-- ghost survives a walk -- so the one raised to read the footprint is the one that gets placed.
local function ghostReady(s)
  local pl = try(function() return s:world():placing() end)
  if pl and pl:exists() and pl:name() then
    learnFootprint(pl)
    if not footprint then
      -- Resolved and carrying no rings at all: nothing to divide a tile by, so one per tile.
      footprint = {w = TILE, h = TILE, ox = 0, oy = 0}
    end
    return "ready"
  end
  if not job.acted then
    job.acted = true
    local pag = try(function() return s:menugrid():get(BUILD_ACTION) end)
                or try(function() return s:menugrid():find(BUILD_ACTION) end)
    if not pag then
      stop("this character's action menu has no " .. BUILD_ACTION .. " entry")
      return "failed"
    end
    if not pcall(function() pag:use() end) then
      stop("the " .. BUILD_ACTION .. " action could not be used")
      return "failed"
    end
    return "waiting"
  end
  if waited() > WAIT_GHOST then
    stop("the " .. BUILD_ACTION .. " action put nothing on the cursor")
    return "failed"
  end
  return "waiting"
end

----------------------------------------------------------------------------------------------------
-- One beat of the machine. Every branch issues at most ONE protected write and returns: the client
-- takes one per frame, and a beat is a whole frame apart from the next.
----------------------------------------------------------------------------------------------------

local function beat()
  local s = job.session
  if not (s and s:exists()) then stop("the character this job started on is gone"); return end
  if cur() ~= s then status("waiting: that character is not the one on screen"); return end

  -- next tile ------------------------------------------------------------------------------------
  -- Cut into slots HERE, so that the walk which follows aims at the slot the trellis is going in and
  -- not at the middle of the tile. It needs the footprint, which is known from the first ghost of the
  -- run onwards -- so only the very first tile after a client start is walked to before its slots can
  -- be worked out, there being nothing else to aim at yet.
  if job.phase == "tile" then
    job.ti = job.ti + 1
    if job.ti > #job.tiles then done(); return end
    local t = job.tiles[job.ti]
    job.tileAt = centre(s, t.x, t.y)
    job.slots = footprint and slotsFor(s, t) or nil
    job.done, job.si, job.slot = {}, 0, nil
    status("tile " .. job.ti .. " of " .. #job.tiles .. (job.slots and (": " .. #job.slots .. " slot(s)") or ""))
    phase("slot")
    return
  end

  -- next slot ------------------------------------------------------------------------------------
  if job.phase == "slot" then
    job.site, job.win, job.winTitle, job.pressed = nil, nil, nil, nil
    if job.slots then
      if not takeSlot(s) then phase("tile"); return end
      if slotTaken(s, job.slot) then
        status("tile " .. job.ti .. ", slot " .. job.si .. ": already taken")
        return
      end
    else
      job.slot = nil                 -- no slots yet: walk to the tile and cut it up on arrival
    end
    phase("stock")
    return
  end

  -- enough in the backpack for one? ---------------------------------------------------------------
  if job.phase == "stock" then
    local kind = shortOf(s)
    if not kind then
      -- Worked out here rather than when the slot was chosen: the character has just come back from
      -- the piles, so this is the side it is actually approaching from.
      job.standAt = job.slot and standFor(s, job.slot, job.tileAt) or nil
      walkStop()
      phase("travel")
      return
    end
    local r
    for _, one in ipairs(RECIPE) do if one.kind == kind then r = one end end
    job.want = {kind = kind, count = r.need * BATCH}
    -- A new material is a new pile: the handle from the last trip still exists and still stands where
    -- it stood, and walking back to it would fetch the same thing again.
    job.pile, job.before, job.seen = nil, nil, nil
    walkStop()
    status("fetching " .. MATERIAL[kind].label)
    phase("unplace")
    return
  end

  -- take back anything on the cursor before touching a pile ---------------------------------------
  if job.phase == "unplace" then
    local pl = try(function() return s:world():placing() end)
    if not (pl and pl:exists()) then phase("pileGo"); return end
    if not job.acted then
      job.acted = true
      dropGhost()
      return
    end
    if waited() > WAIT_GHOST then
      stop("a building ghost would not come off the cursor -- right-click to clear it and start again")
    end
    return
  end

  -- walk to a pile and right-click it -------------------------------------------------------------
  if job.phase == "pileGo" then
    local which = MATERIAL[job.want.kind].area
    if not (job.pile and job.pile:exists()) then
      job.pile = pileIn(s, which)
      walkStop()
    end
    if not job.pile then
      stop("no stockpile stands in the " .. AREA_NAME[which])
      return
    end
    local how = clickGob(s, job.pile, 3)
    if how == "blocked" then stop("cannot reach the " .. AREA_NAME[which]); return end
    if how == "failed" then stop("that pile could not be clicked"); return end
    if how == "gone" then job.pile = nil; return end       -- it emptied: another one, next beat
    if how == "clicked" then phase("pileOpen") end
    return
  end

  -- wait for its window -----------------------------------------------------------------------------
  if job.phase == "pileOpen" then
    if stockpileBox(s) then phase("pileTake"); return end
    if waited() > WAIT_WND then
      stop("no Stockpile window opened for that pile")
    end
    return
  end

  -- take from it ----------------------------------------------------------------------------------
  if job.phase == "pileTake" then
    local inv = inventory(s)
    local items = inv and try(function() return inv:items():list() end) or {}
    local have, total = 0, #items
    for _, it in ipairs(items) do
      if matchesMaterial(it, job.want.kind) then have = have + (it:quantity() or 1) end
    end

    -- a withdrawal in flight. It has landed when the backpack grew -- which is the only test available
    -- before this material's name is known, and stays the right one after.
    if job.before then
      if (total > job.before.total) or (have > job.before.have) then
        for _, it in ipairs(items) do
          if not job.seen[it] then learnRes(job.want.kind, it:res()) end
        end
        if resKnown(job.want.kind) then job.before, job.seen = nil, nil end
        return                                  -- count again next beat, with the set as it now is
      end
      if waited() > WAIT_XFER then
        -- The pile gave nothing: it is empty, or the backpack is full. What the character already
        -- carries decides whether that is a stop or merely a short trip.
        local kind, need = job.want.kind, 0
        for _, r in ipairs(RECIPE) do if r.kind == kind then need = r.need end end
        job.want, job.before, job.seen = nil, nil, nil
        if have < need then
          stop("that pile gave no " .. MATERIAL[kind].label)
        else
          status("took what the pile had")
          phase("stock")
        end
      end
      return
    end

    if have >= job.want.count then
      job.want = nil
      phase("stock")
      return
    end
    local box = stockpileBox(s)
    if not box then stop("the Stockpile window closed"); return end
    job.seen = {}
    for _, it in ipairs(items) do job.seen[it] = true end
    job.before = {have = have, total = total}
    job.since = clock
    local ok = pcall(function() box:send("xfer") end)
    if not ok then stop("that pile refused a withdrawal"); return end
    status("taking " .. MATERIAL[job.want.kind].label .. " (" .. have .. "/" .. job.want.count .. ")")
    return
  end

  -- walk to the slot -------------------------------------------------------------------------------
  if job.phase == "travel" then
    local how = walkTo(s, job.standAt or job.tileAt, job.standAt and STAND_REACH or REACH)
    if how == "blocked" then
      job.skipped = job.skipped + 1
      status("cannot reach tile " .. job.ti .. ", slot " .. job.si)
      phase("slot")
      return
    end
    if how == "arrived" then phase("ghost") end
    return
  end

  -- get the ghost onto the cursor, and cut the tile up ---------------------------------------------
  if job.phase == "ghost" then
    if ghostReady(s) ~= "ready" then return end
    if not job.slots then
      -- The footprint is known now, so this is where the tile learns how many it holds.
      job.slots, job.done = slotsFor(s, job.tiles[job.ti]), {}
      status("tile " .. job.ti .. " of " .. #job.tiles .. ": " .. #job.slots .. " slot(s)")
    end
    if not job.slot then
      -- The tile was walked to before it had slots, so the character is standing in the middle of it,
      -- possibly on the very spot. Pick from here -- which is now the true approach side -- and go and
      -- stand clear of it. The ghost waits on the cursor; the next pass reuses it.
      if not takeSlot(s) then phase("tile"); return end
      if slotTaken(s, job.slot) then phase("slot"); return end
      job.standAt = standFor(s, job.slot, job.tileAt)
      walkStop()
      phase("travel")
      return
    end
    -- A taken slot goes back to `slot`, which takes the next one. The ghost stays on the cursor.
    if slotTaken(s, job.slot) then phase("slot"); return end
    phase("place")
    return
  end

  -- put it down -------------------------------------------------------------------------------------
  if job.phase == "place" then
    local ok = pcall(function() s:world():place(job.slot, 0) end)
    if not ok then stop("that slot could not be placed on"); return end
    phase("site")
    return
  end

  -- did a site appear? -------------------------------------------------------------------------------
  if job.phase == "site" then
    local site = siteNear(s, job.slot)
    if site then
      job.site, job.win, job.winTitle, job.pressed = site, nil, nil, nil
      job.siteAt = site:position()
      job.siteReach = math.max(REACH, (gobRadius(site) or 0) + STANDOFF)
      walkStop()
      phase("siteGo")
      return
    end
    if waited() > WAIT_SITE then
      job.skipped = job.skipped + 1
      status("tile " .. job.ti .. ", slot " .. job.si .. ": the server refused that spot")
      phase("slot")
      return
    end
    return
  end

  -- walk back to what was just placed ------------------------------------------------------------
  if job.phase == "siteGo" then
    if not (job.site and job.site:exists()) then
      job.built = job.built + 1
      status("built " .. job.built)
      phase("slot")
      return
    end
    if not job.siteAt then phase("siteOpen"); return end
    local how = walkTo(s, job.siteAt, job.siteReach, job.site)
    if (how == "arrived") or (how == "blocked") then phase("siteOpen") end
    return
  end

  -- open the site's own window ---------------------------------------------------------------------
  if job.phase == "siteOpen" then
    if not (job.site and job.site:exists()) then
      job.built = job.built + 1
      status("built " .. job.built)
      phase("slot")
      return
    end
    local win, title = siteWindow(s)
    if win then
      job.win, job.winTitle = win, title
      phase("build")
      return
    end
    if not job.acted then
      job.acted = true
      pcall(function() s:world():click(job.site, 3) end)
      return
    end
    if waited() > WAIT_WND then
      job.skipped = job.skipped + 1
      status("no build window for the site at tile " .. job.ti .. ", slot " .. job.si)
      phase("slot")
    end
    return
  end

  -- press Build until the site is a trellis ---------------------------------------------------------
  if job.phase == "build" then
    if not (job.site and job.site:exists()) then
      job.built = job.built + 1
      status("built " .. job.built)
      phase("slot")
      return
    end
    if waited() > WAIT_BUILT then
      job.skipped = job.skipped + 1
      describeWindow(s, job.win, job.winTitle)
      status("the site at tile " .. job.ti .. ", slot " .. job.si .. " did not finish")
      phase("slot")
      return
    end
    -- Pressed again while it stands: a building that takes its materials in stages wants one press per
    -- stage, and a press the server did nothing with costs a press and nothing else.
    if (clock - (job.pressed or -1000)) < BUILD_EVERY then return end
    local win, title = job.win, job.winTitle
    if not (win and win:exists()) then
      win, title = siteWindow(s)
      job.win, job.winTitle = win, title
    end
    if not win then phase("siteOpen"); return end
    local go, howMany = buildButton(s, win, title)
    if not go then
      describeWindow(s, win, title)
      stop("no button under that build window -- see the lines above")
      return
    end
    job.pressed = clock
    local sent, why = pcall(function() go:send("activate") end)
    if not sent then
      describeWindow(s, win, title)
      stop("that button could not be pressed: " .. tostring(why))
      return
    end
    if not job.said then
      job.said = true
      say("pressed [" .. (try(function() return go:text() end) or "?") .. "] of "
          .. howMany .. " button(s) under window [" .. (title or "?") .. "]")
    end
    status("pressed " .. BUILD_BUTTON .. " at tile " .. job.ti .. ", slot " .. job.si)
    return
  end

end

----------------------------------------------------------------------------------------------------
-- Naming an area: two clicks on the ground
----------------------------------------------------------------------------------------------------

local pick = nil          -- {which = , first = Position}
local pickPoint           -- forward

local function endPick(text)
  pick = nil
  pcall(function() hafen.ui():mouse():cursor(nil) end)
  if text then say(text) end
  if refresh then refresh() end
end

local function grabOnce()
  local g = try(function() return hafen.ui():mouse():grab() end)
  if not g then endPick("the pointer could not be taken"); return end
  g:on("Up", function(ev)
    local x, y, b = ev:x(), ev:y(), ev:button()
    if b ~= 1 then
      hafen.timer():after(0, function() endPick("area pick cancelled") end)
      return
    end
    local s = cur()
    if not s then
      hafen.timer():after(0, function() endPick("no character on screen") end)
      return
    end
    -- The answer comes back a frame later, inside the scene's own pass, so the work is handed to the
    -- step: this addon's own window is a second tree (docs/addons/api/threading.md).
    s:world():screenToWorld({x = x, y = y}, function(p)
      hafen.timer():after(0, function() pickPoint(p) end)
    end)
  end)
end

pickPoint = function(p)
  if not pick then return end
  if not p then
    say("that click hit no ground -- click the ground again")
    grabOnce()
    return
  end
  if not p:durable() then
    endPick("that place cannot be saved: walk that ground once, then pick it again")
    return
  end
  if not pick.first then
    pick.first = p
    say("first corner taken -- click the opposite corner (right-click cancels)")
    grabOnce()
    return
  end
  areas()[pick.which] = {a = pick.first, b = p}
  hafen.store():flush()
  local which = pick.which
  endPick(AREA_NAME[which] .. ": " .. areaLabel(which))
end

local function beginPick(which)
  if job.running then say("stop the job before picking an area"); return end
  if pick then endPick(nil) end
  pick = {which = which, first = nil}
  pcall(function() hafen.ui():mouse():cursor("hand") end)
  say("click one corner of the " .. AREA_NAME[which] .. ", then the other (right-click cancels)")
  if refresh then refresh() end
  grabOnce()
end

----------------------------------------------------------------------------------------------------
-- The window
----------------------------------------------------------------------------------------------------

local GAP, WIDE, BTN = 4, 268, 150

local win = hafen.ui():window():title("Trellis Builder"):position(60, 60)
local rows, ui = {}, {}

local function press(fn)
  -- A control's own notification holds this addon's layer; everything that touches the game or the
  -- store is handed to the step (docs/addons/api/threading.md).
  return function() hafen.timer():after(0, fn) end
end

local y = 0
for _, which in ipairs({"blocks", "strings", "build"}) do
  local b = hafen.ui():button():parent(win):position(0, y):size(BTN)
    :text(({blocks = "Block pile area", strings = "String pile area", build = "Trellis build area"})[which])
    :tooltip("click one corner of the " .. AREA_NAME[which] .. " on the ground, then the other")
  local l = hafen.ui():label():parent(win):position(BTN + GAP, y + 4):text("not set")
  b:on("Pressed", press(function() beginPick(which) end))
  rows[which] = l
  y = y + b:size().h + GAP
end

ui.start = hafen.ui():button():parent(win):position(0, y):size((BTN - GAP) / 2)
  :text("Start"):tooltip("fill the build area with trellises")
ui.stop = hafen.ui():button():parent(win):position((BTN + GAP) / 2, y):size((BTN - GAP) / 2)
  :text("Stop"):tooltip("stop after this beat")
ui.start:on("Pressed", press(function() if not job.running then start() end end))
ui.stop:on("Pressed", press(function()
  if pick then endPick("area pick cancelled") end
  if job.running then stop("stopped") end
end))
y = y + ui.start:size().h + GAP

ui.status = hafen.ui():label():parent(win):position(0, y):text("idle")
y = y + ui.status:size().h

hafen.ui():widget():parent(win):position(WIDE, 0):size(1, 1)   -- holds the window's width open
win:pack()

refresh = function()
  for which, label in pairs(rows) do
    local text = areaLabel(which)
    if pick and (pick.which == which) then
      text = pick.first and "click the other corner" or "click one corner"
    end
    pcall(function() label:text(text) end)
  end
  local line = job.status
  if pick then line = "picking the " .. AREA_NAME[pick.which]
  elseif job.running then line = job.status .. "  [" .. job.built .. " built]" end
  pcall(function() ui.status:text(line) end)
end

win:on("Close", function() win:visible(false) end)
refresh()

----------------------------------------------------------------------------------------------------
-- The beat, and the console
----------------------------------------------------------------------------------------------------

hafen.timer():every(TICK, function()
  clock = clock + TICK
  if not job.running then return end
  local ok, err = pcall(beat)
  if not ok then stop("stopped on an error: " .. tostring(err)) end
end)

hafen.console():on("trellis", function(args)
  local what = (args[1] or "show"):lower()
  hafen.timer():after(0, function()
    if what == "start" then
      start()
    elseif what == "stop" then
      if pick then endPick("area pick cancelled") end
      stop("stopped")
    elseif what == "clear" then
      for which in pairs(AREA_NAME) do areas()[which] = nil end
      hafen.store():flush()
      say("every area forgotten")
      refresh()
    elseif what == "site" then
      local s = cur()
      local cs = cur()
      if cs then
        local w, t = siteWindow(cs)
        describeWindow(cs, w, t)
      else
        say("no character on screen")
      end
    elseif what == "status" then
      for _, which in ipairs({"blocks", "strings", "build"}) do
        say(AREA_NAME[which] .. ": " .. areaLabel(which))
      end
      for _, kind in ipairs({"block", "string"}) do
        local names = {}
        for res in pairs(resSet(kind)) do names[#names + 1] = res end
        say(MATERIAL[kind].label .. ": " .. ((#names > 0) and table.concat(names, ", ") or "not learnt yet"))
      end
      say(job.running and (job.status .. " -- " .. job.built .. " built, " .. job.skipped .. " skipped")
                       or "idle")
      if footprint then
        say("trellis footprint " .. num(footprint.w) .. " x " .. num(footprint.h) .. " world units")
      end
    else
      win:visible(true)
      refresh()
    end
  end)
end)

hafen.event():on("Disable", function()
  if job.running then job.running = false end
end)

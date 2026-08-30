-- Hitboxes -- one key cycles the footprints of everything in view through three modes: off, laid on the
-- ground, and drawn over every object. Assign the key in Options > Keybindings > Hitboxes.
--
-- The shape is gob:hitbox() in all three, the rings the object's own resource carries. What changes is
-- where they are drawn, and the two ways are genuinely different pictures rather than a setting:
--
--   ground  hafen.virtual():patch(), which lies ON the terrain. It follows a slope with no float and no
--           gap, and whatever stands there occludes it -- so a box behind a wall is behind the wall, and
--           you read the boxes as part of the world. A patch anchored to a Gob also moves and ends with it,
--           so this mode keeps almost no bookkeeping of its own.
--   over    a painter hung on the MAP VIEW, projecting the same rings to the screen every frame. Nothing
--           in the world occludes it, so a box inside a house or behind a hill is drawn whole and on top --
--           which is the point of the mode, and the reason it cannot be a patch: a patch that ignored the
--           ground would not be one.
--
-- `over` hangs on the map view rather than on hafen.ui(), and that is the whole difference between drawing
-- over the WORLD and drawing over the SCREEN. A widget's overlay is painted straight after that widget's
-- own draw and before its siblings', so the boxes land on top of every object and the windows land on top
-- of them -- where a hafen.ui() painter is over the finished HUD and would cover your inventory. It also
-- means the painter is handed coordinates local to the map view and is clipped to it, so what
-- worldToScreen answers in ROOT pixels is moved by the view's own corner before it is drawn.
--
-- The building you are PLACING wears a box in both. That one is not a game object -- it is the client's own
-- ghost on the cursor, in no session's object cache -- so it is read from the world rather than found among
-- the gobs: s:world():placing() is the handle, and placing:hitbox() is the same rings in the same units.
--
-- gob:hitbox() answers nil for a resource that carries no shape at all -- a decoration, most flooring --
-- and those are simply not drawn: there is no hitbox to show. Nor does a shape coming back mean the object
-- blocks movement; a felled log has a click-box and no collision. This addon draws what the verb answers.

local FILL   = {60, 140, 255, 70}   -- ground: the blue laid over the terrain; on a patch the 4th component
                                    -- IS the fill's own opacity, so the ground reads through it
local EDGE   = {120, 190, 255}      -- ground: the rim, solid blue
local WIDTH  = 0.35                 -- ground: how thick that rim is, in WORLD units -- a tile is 11

local OVER_FILL  = {60, 140, 255, 60}    -- over: the same blue, a little thinner because boxes drawn over
                                         -- everything stack up where the ground would have separated them
local OVER_EDGE  = {120, 190, 255, 235}  -- over: the rim
local OVER_WIDTH = 1.5                   -- over: its thickness in DESIGN pixels, not world units

local RESCAN = 2                    -- seconds between full re-reads
local TURN   = 0.2                  -- seconds between facing corrections -- ground only, see spin()

local MODES = {"off", "ground", "over"}
local mode  = 1                     -- an index into MODES: the key steps it round

local laid = {}    -- [Gob] = {name = <resource when read>, plus the mode's own half:
                   --          ground: own = {patch, ...}, base = <facing when read>, turn = <last written>
                   --          over:   rings = {ring, ...}, base = <facing when read>, moving = <last seen>}
local ghost        -- the same, for the ghost on the cursor
local ticker       -- the re-read; both drawing modes have one
local turner       -- the facing correction; ground only
local painter      -- the map view's overlay; over only
local view         -- the map view it is hanging on, so a change of character is noticed
local attach       -- forward: the sweep re-hangs the painter, and is written above where it is defined

local function ground() return MODES[mode] == "ground" end
local function over()   return MODES[mode] == "over" end
local function off()    return MODES[mode] == "off" end

local function patches()
  return hafen.virtual():patch()    -- the same object every call
end

-- ---------------------------------------------------------------- reading a footprint

-- A ring the patch collection refuses is a shape this addon cannot lay, not an error worth spilling: an
-- obst layer is whatever the resource's author drew, so a concave one or one past the 32-edge limit is a
-- real shape to meet. Each ring goes on its own, so one bad ring in a set does not cost the others.
-- (Only `ground` goes through here; the painter draws any ring it can project.)
local function put(anchor, ring, own)
  local ok, patch = pcall(function()
    return patches():add(ring, anchor):tint(FILL):border(EDGE, WIDTH)
  end)
  if ok and patch then own[#own + 1] = patch end
end

-- Read one object's footprint and keep whatever the mode in force needs of it. Nothing is recorded for an
-- object with no shape, so the sweep comes back for it -- which is what picks up a resource that had not
-- resolved yet.
local function read(g, name)
  local box = g:hitbox()
  if not box then return end
  local mine = {name = name, base = g:facing() or 0}
  if ground() then
    -- The patches ARE the drawing and they hold the shape, so the rings are not kept: a patch anchored to
    -- the Gob follows it, and `base`/`turn` are all that is left to correct.
    local own = {}
    for _, ring in ipairs(box) do put(g, ring, own) end
    if #own == 0 then return end
    mine.own, mine.turn = own, 0
  else
    -- Nothing is laid, so the rings themselves are the drawing, kept in world places and projected fresh
    -- every frame by the painter.
    mine.rings, mine.moving = box, g:moving() or false
  end
  laid[g] = mine
end

local function drop(mine)
  if not mine.own then return end   -- an `over` entry owns no patches: its rings were only ever data
  for _, patch in ipairs(mine.own) do
    if patch:exists() then patches():remove(patch) end
  end
end

local function forget(g)
  local mine = laid[g]
  if not mine then return end
  laid[g] = nil
  drop(mine)
end

-- Re-read, never remembered: a felled tree keeps its id and becomes a log, so the box read for the tree is
-- the wrong box a moment later. The resource name is what says so, and it is a cheap read.
local function consider(g)
  local name = g:name()
  if name == nil then return end    -- not resolved yet; nothing to key a verdict on
  local mine = laid[g]
  if mine then
    if mine.name == name then return end
    forget(g)
  end
  read(g, name)
end

local function sweep()
  if over() then attach() end
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then
      for _, g in ipairs(s:world():gob():list()) do consider(g) end
    end
  end
end

-- ---------------------------------------------------------------- ground: keeping a laid box straight

-- gob:hitbox() hands back rings ALREADY turned by the object's facing, and a patch keeps them as offsets
-- from its anchor that the object's own turning does not turn. So a boar that comes about would wear its
-- box sideways -- unless the patch is turned by as much as the object has since its rings were read, which
-- is what this is. It is its own timer, and a fast one, because a running creature turns between sweeps;
-- it is affordable there because turning a patch re-carves the ground it already covers and rebuilds no
-- mesh, where taking a box up and laying it again re-cuts every tile under it.
local function spin()
  for g, mine in pairs(laid) do
    local now = g:facing()
    if now then
      local turn = now - mine.base              -- absolute: the patch was laid at a rotation of 0
      if turn ~= mine.turn then
        mine.turn = turn
        for _, patch in ipairs(mine.own) do
          if patch:exists() then patch:rotate(turn) end
        end
      end
    end
  end
end

-- ---------------------------------------------------------------- over: keeping the rings current

-- A ring kept for the painter is a set of world PLACES, so it goes stale the moment its object moves or
-- turns -- and there is no anchor here to follow it, the way a patch follows its Gob. Re-reading every ring
-- every frame is what that would cost if you let it, so only the objects that actually changed are re-read:
-- one that is moving, one that has stopped since the last frame, and one that has turned in place. Nearly
-- everything in view -- the trees, the walls, the houses -- is read once by the sweep and never again.
local function refresh()
  for g, mine in pairs(laid) do
    local moving = g:moving() or false
    local face = g:facing()
    if moving or mine.moving or (face and (face ~= mine.base)) then
      mine.moving = moving
      local box = g:hitbox()
      if box then
        mine.rings = box
        mine.base = face or mine.base
      end
    end
  end
end

local scratch = {}   -- reused by every ring: a painter that allocates per frame is one that stutters

-- One ring, projected and drawn. The whole ring is projected BEFORE anything is drawn, and one corner with
-- no pixel drops the shape entire: a place behind the camera projects to a plausible pixel mirrored through
-- the middle of the view, so half a ring placed and half of it mirrored is exactly what this avoids.
local function trace(gr, world, ring, ox, oy)
  local n = #ring
  if n < 3 then return end
  for i = 1, n do
    local pt = world:worldToScreen(ring[i])
    if not pt then return end
    scratch[(i * 2) - 1], scratch[i * 2] = pt.x - ox, pt.y - oy   -- root pixels into the view's own box
  end
  gr:color(OVER_FILL[1], OVER_FILL[2], OVER_FILL[3], OVER_FILL[4])
  gr:poly(table.unpack(scratch, 1, n * 2))
  gr:color(OVER_EDGE[1], OVER_EDGE[2], OVER_EDGE[3], OVER_EDGE[4])
  for i = 1, n do
    local j = (i % n) + 1
    gr:line(scratch[(i * 2) - 1], scratch[i * 2], scratch[(j * 2) - 1], scratch[j * 2], OVER_WIDTH)
  end
end

-- The painter draws and reads nothing else: what to draw was decided by the sweep and by refresh(), on the
-- beat each of those runs on. Everything is projected through the DRAWN character, because a pixel is a
-- point on the screen and there is one screen -- an object only an alt can see has no pixel here, and drops
-- out by the same line a corner behind the camera does.
local function paint(gr, w, h)
  local s = hafen.session():current()
  if not s then return end
  -- worldToScreen answers ROOT pixels and this painter draws in the map view's own box, so every point is
  -- moved by the view's corner. nil while the view has no place on screen yet: nothing to draw against.
  local at = view and view:exists() and view:rootPos()
  if not at then return end
  local world, ox, oy = s:world(), at.x, at.y
  for _, mine in pairs(laid) do
    if mine.rings then                          -- a painter is the worst place in the file for a raise
      for _, ring in ipairs(mine.rings) do trace(gr, world, ring, ox, oy) end
    end
  end
  if ghost and ghost.rings then
    for _, ring in ipairs(ghost.rings) do trace(gr, world, ring, ox, oy) end
  end
end

-- The map view of the character on screen, or nil before its HUD is up. Widgets are interned per addon, so
-- `==` is what says whether the one the painter hangs on is still the right one.
local function mapview()
  local s = hafen.session():current()
  local ui = s and s:ui()
  if not ui then return nil end
  local ok, mv = pcall(function() return ui:match("@MapView") end)
  return ok and mv or nil
end

local function detach()
  if view and painter then
    pcall(function() view:overlay():remove("hitboxes") end)   -- a widget already gone takes its own
  end
  view, painter = nil, nil
end

-- Hang the painter on the map view in force, and move it when that is a different one. A character switch
-- and a relogin both build a new view, and a painter left on the old one draws nowhere -- so this is asked
-- again on every sweep rather than once when the mode was entered.
function attach()
  local mv = mapview()
  if (mv ~= nil) and (mv == view) then return end
  detach()
  if not mv then return end
  view = mv
  painter = mv:overlay():add("hitboxes"):draw(paint)
end

-- ---------------------------------------------------------------- the ghost on the cursor

local function unlayGhost()
  if not ghost then return end
  local mine = ghost
  ghost = nil
  drop(mine)
end

-- The ghost is none of the above: it is not among the gobs, so no sweep finds it, and it has no Gob for a
-- patch to follow. So `ground` lays its box at a PLACE and moves it from here, and `over` simply re-reads
-- its rings -- both every frame, because that is how often a cursor moves, and both affordable because it
-- is one object.
--
-- Only the DRAWN character's ghost is read. A Plob is drawn in its own session's view, so another login's
-- is not on your screen to want a box round; tab away mid-placement and the read goes nil, which takes the
-- box up by the same line a click does.
local function ghostFollow()
  local s = hafen.session():current()
  local pl = s and s:world():placing()
  if not pl then return unlayGhost() end
  local name = pl:name()
  if name == nil then return end                -- the resource the server named has not resolved yet
  if ghost and (ghost.name ~= name) then
    unlayGhost()                                -- something else is on the cursor now: read it again
  end
  if over() then
    local box = pl:hitbox()
    ghost = box and {name = name, rings = box} or nil
    return
  end
  local at = pl:position()
  if not at then return end
  if not ghost then
    local box = pl:hitbox()
    if not box then return end                  -- this one leaves no footprint: nothing to draw
    local own = {}
    for _, ring in ipairs(box) do put(at, ring, own) end
    if #own > 0 then
      ghost = {own = own, name = name, base = pl:facing() or 0, turn = 0, x = at:x(), y = at:y()}
    end
    return
  end
  local x, y = at:x(), at:y()
  if x and y and ((x ~= ghost.x) or (y ~= ghost.y)) then
    ghost.x, ghost.y = x, y
    -- A place a patch may stand at has to be one that can be KEPT, and the collection refuses one that
    -- cannot be. Under the cursor that is ground you are looking at, so it is durable -- but this runs every
    -- frame, which is the worst place in the file for a raise, so a refusal takes the box up instead and the
    -- next frame lays it again through the guarded path above.
    local ok = pcall(function()
      for _, patch in ipairs(ghost.own) do
        if patch:exists() then patch:position(at) end
      end
    end)
    if not ok then return unlayGhost() end
  end
  local now = pl:facing()
  if now then
    local turn = now - ghost.base
    if turn ~= ghost.turn then
      ghost.turn = turn
      for _, patch in ipairs(ghost.own) do
        if patch:exists() then patch:rotate(turn) end
      end
    end
  end
end

-- ---------------------------------------------------------------- the cycle

-- Every mode is left whole before the next is entered, rather than one being converted into the other: the
-- two keep different things (patches against rings) and a half-converted set is a class of bug this addon
-- has no reason to own. It costs one sweep on a keypress.
local function leave()
  if ticker then ticker:cancel() end
  if turner then turner:cancel() end
  ticker, turner = nil, nil
  detach()
  unlayGhost()
  local was = laid
  laid = {}
  for _, mine in pairs(was) do drop(mine) end
end

local function enter()
  if off() then return end
  sweep()
  ghostFollow()
  ticker = hafen.timer():every(RESCAN, sweep)
  if ground() then
    turner = hafen.timer():every(TURN, spin)
  else
    attach()
  end
end

-- The hotkey starts unbound: an addon names an action and the user assigns the key, in
-- Options > Game > Keybindings > Hitboxes.
hafen.client():options():keybindings():on("cycle", function()
  leave()
  mode = (mode % #MODES) + 1
  enter()
  hafen.log():write("hitboxes: " .. MODES[mode])
end)

-- GobAdded runs before the object's first drawn frame, so one that arrives with its resource already in
-- hand is boxed from that frame rather than at the next sweep.
hafen.event():on("GobAdded", function(g)
  if not off() then consider(g) end
end)

-- In `ground` the patches went with it: one anchored to a Gob ends with that Gob, so there is nothing to
-- take up. In `over` there was never anything to take up. Either way the entry goes.
hafen.event():on("GobRemoved", function(g)
  laid[g] = nil
end)

-- The per-frame beat. The cursor is read here because that is how often it moves, and `over` brings its
-- rings up to date here rather than in the painter, so the painter only ever draws. Nothing at all runs
-- while the boxes are off.
hafen.event():on("Update", function()
  if off() then return end
  ghostFollow()
  if over() then refresh() end
end)

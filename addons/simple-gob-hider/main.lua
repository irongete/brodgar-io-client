-- Simple Gob Hider -- one key takes the objects you named out of sight and paints the ground each of
-- them stands on yellow, so you still see what is there without looking at it.
--
-- Suggested key: Ctrl+H -- assign it in Options > Keybindings > Simple Gob Hider. An addon hotkey
-- starts unbound, because the client gives one key to exactly one action and a default we picked would
-- lose every collision silently.

-- ---------------------------------------------------------------- the list, which is yours to edit

-- Each entry is matched as a PLAIN SUBSTRING of gob:name() -- the resource identity the server sent,
-- never a display name -- so "gfx/terobjs/trees/" takes every tree and "trees/fir" takes only firs.
-- To learn a name, stand next to the thing and type:
--   :lua hafen.log():write(tostring(hafen.session():current():world():gob():nearest():name()))
local HIDDEN = {
  "gfx/terobjs/trees/",
  "gfx/terobjs/bushes/",
  "gfx/terobjs/log",             -- the felled trunk; it lives beside the trees, not under them
}

-- ---------------------------------------------------------------- what it looks like

local FILL       = {245, 215, 60, 120}   -- the yellow poured inside the footprint
local OUTLINE    = {245, 215, 60, 230}   -- and the edge drawn round it
local LINE_WIDTH = 1                     -- design pixels

-- Half the side of the square drawn for an object whose resource carries no footprint, in WORLD units
-- (a tile is 11), so it sits on the ground and grows and shrinks with the camera like a real one.
local FALLBACK_RADIUS = 5.5

-- ---------------------------------------------------------------- how it hides

-- There is no verb that takes a game object out of the scene: gob:scale(k) is the whole of what this API
-- writes on how one is DRAWN, and it refuses 0 outright ("a point"), so hiding here means drawing the
-- object small enough to be no pixel at all. It is client-local and purely visual -- the object's own
-- footprint, what it collides with and what a click sends are the game's and are untouched -- and the
-- engine puts every gob back the moment this addon reloads or is disabled, so nothing is left distorted.
--
-- The pick follows the drawn size, so a hidden object is also, in practice, unclickable. The yellow patch
-- is what tells you it is still standing there.
local SHRINK = 0.001

local SCAN_INTERVAL   = 0.1      -- how often the objects awaiting a name are looked at again
local RESCAN_INTERVAL = 2        -- how often the whole view is offered again -- see recheck() below
local NAME_GRACE      = 5        -- seconds one of them may take to resolve its name before we give up

-- ---------------------------------------------------------------- state

local active  = false            -- the toggle: is the list hidden right now?
local hidden  = {}               -- the set of objects we are hiding; a Gob is interned, so it keys
local pending = {}               -- Gob -> seconds of grace left to resolve its resource name

local function matches(name)
  if name == nil then return false end
  for _, entry in ipairs(HIDDEN) do
    if string.find(name, entry, 1, true) then return true end
  end
  return false
end

local function hide(g)
  if hidden[g] then return end
  hidden[g] = true
  g:scale(SHRINK)
end

local function unhide(g)
  hidden[g] = nil
  g:scale(1)                     -- the original size; inert on a gob that has since gone
end

-- A gob is offered rather than judged: gob:name() is nil until its resource resolves, and a GobAdded
-- handler is often earlier than that -- so the answer is waited for instead of being taken as "no".
local function offer(g)
  if hidden[g] or pending[g] then return end
  pending[g] = NAME_GRACE
end

local function drain(dt)
  for g, left in pairs(pending) do
    if not g:exists() then
      pending[g] = nil
    else
      local name = g:name()
      if name ~= nil then
        pending[g] = nil
        if matches(name) then hide(g) end
      else
        left = left - dt
        pending[g] = (left > 0) and left or nil
      end
    end
  end
end

-- AN OBJECT'S RESOURCE IS NOT FIXED, and nothing announces that it changed. A tree that is felled keeps
-- its id and becomes a log -- one GobAdded, then a different thing standing there -- so a verdict reached
-- once and remembered is a verdict that goes stale. It is re-read instead: an object we hide that has
-- stopped being something the list names goes back to its own size here, and the periodic re-offer below
-- is the other direction, for one that has only just become a match.
local function recheck()
  for g in pairs(hidden) do
    if not g:exists() then
      hidden[g] = nil
    else
      local name = g:name()
      if (name ~= nil) and not matches(name) then unhide(g) end
    end
  end
end

-- ---------------------------------------------------------------- the toggle

-- Every character's view, not the drawn one's alone: gob:scale(k) is written on the OBJECT and reaches
-- every character that can see it, so the sweep that decides what to write has the same reach.
local function offerEverything()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then
      for _, g in ipairs(s:world():gob():list()) do offer(g) end
    end
  end
end

local function enable()
  active = true
  offerEverything()
  drain(0)                       -- whatever already knows its name goes now, not a tenth of a second later
end

local function disable()
  active = false
  for g in pairs(hidden) do g:scale(1) end
  hidden, pending = {}, {}
end

hafen.client():options():keybindings():on("toggle", function()
  if active then disable() else enable() end
end)

-- ---------------------------------------------------------------- keeping up with the world

hafen.event():on("GobAdded", function(g)
  if active then offer(g) end    -- hidden the moment it arrives, for as long as the toggle is on
end)

hafen.event():on("GobRemoved", function(g)
  -- The size died with the object and so did our record of it: a gob that comes back is a new one, and
  -- it will be offered again by the GobAdded above.
  hidden[g], pending[g] = nil, nil
end)

local sinceRescan = 0

hafen.timer():every(SCAN_INTERVAL, function()
  if not active then return end
  drain(SCAN_INTERVAL)
  recheck()
  sinceRescan = sinceRescan + SCAN_INTERVAL
  if sinceRescan >= RESCAN_INTERVAL then
    sinceRescan = 0
    offerEverything()            -- an object that BECAME a match is caught here; offer() skips the rest
  end
end)

-- ---------------------------------------------------------------- the yellow, drawn

-- One ring of a footprint, projected and filled. A corner the drawn character cannot locate drops the
-- whole ring rather than half of it: three of four corners would be a triangle claiming to be a square.
local function drawRing(g, world, ring, ox, oy)
  local n = #ring
  if n < 3 then return end
  local pts = {}
  for i = 1, n do
    -- worldToScreen answers ROOT design pixels; a widget's painter is handed a g already translated to
    -- that widget's top-left, so the map's own root position stands between the two.
    local sp = world:worldToScreen(ring[i])
    if sp == nil then return end
    pts[#pts + 1] = sp.x - ox
    pts[#pts + 1] = sp.y - oy
  end
  g:color(FILL[1], FILL[2], FILL[3], FILL[4])
  g:poly(table.unpack(pts))
  g:color(OUTLINE[1], OUTLINE[2], OUTLINE[3], OUTLINE[4])
  for i = 1, n do
    local a, b = (i - 1) * 2, (i % n) * 2        -- and the last edge closes the ring
    g:line(pts[a + 1], pts[a + 2], pts[b + 1], pts[b + 2], LINE_WIDTH)
  end
end

-- A square on the ground, for an object whose resource carries no footprint at all -- gfx/terobjs/log,
-- the felled trunk, is one: its resource has a mesh and nothing to collide with, so gob:hitbox() is nil
-- and there is no shape to trace. A tile-sized patch where it stands is the honest stand-in, and it is
-- drawn through the very same path as a real footprint so the two read alike.
local function groundSquare(p, r)
  local a, b = p:offset(-r, -r), p:offset(r, -r)
  local c, d = p:offset(r, r), p:offset(-r, r)
  if a and b and c and d then return {a, b, c, d} end
  return nil
end

local function drawPatches(g, ox, oy)
  local s = hafen.session():current()
  local world = s and s:world()
  if not world then return end

  for gob in pairs(hidden) do
    -- READ EVERY FRAME, cached nowhere. A footprint is the resource's own polygons turned by the
    -- object's facing and placed where it stands, so it moves when the object turns or is put somewhere
    -- else, and it is replaced outright when the resource is -- the felled tree again. What that costs
    -- is a handful of points per hidden object per frame, which is what the projection below costs
    -- anyway; what caching it cost was a yellow patch lying where the thing used to be.
    local box = gob:hitbox()
    if box then
      for _, ring in ipairs(box) do drawRing(g, world, ring, ox, oy) end
    else
      local p = gob:position()
      local ring = p and groundSquare(p, FALLBACK_RADIUS)
      if ring then drawRing(g, world, ring, ox, oy) end
    end
  end

  g:color()                      -- back to white for whoever draws after us
end

-- THE RECEIVER PICKS THE LAYER, and there is no switch to set: hafen.ui():overlay() paints after the
-- whole root has drawn and would cover every window, while a painter hung on a WIDGET runs in that
-- widget's own draw slot. Ours hangs on the MapView, so the patches sit over the world and under the
-- client's own windows, clipped to the map's box.
--
-- An overlay on a widget dies with the widget, so it is hung from the arrival of the MapView rather than
-- once at load. The session events report CHANGES, so the sessions the client already holds are walked
-- too, and `watched` keeps a session that fires SessionEnteredWorld twice from subscribing twice.
local watched = {}

local function watch(s)
  if watched[s] then return end
  watched[s] = true
  s:ui():on("@MapView", "Added", function(mv)
    mv:overlay():add("hitboxes"):draw(function(g, w, h)
      if not active then return end
      local o = mv:rootPos()
      if o then drawPatches(g, o.x, o.y) end
    end)
  end)
end

hafen.event():on("SessionEnteredWorld", watch)

hafen.event():on("Load", function()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then watch(s) end
  end
end)

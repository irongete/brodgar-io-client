-- Simple Gob Hider -- one key takes the objects you named out of the scene and paints the ground each
-- of them stands on yellow. Assign the key in Options > Keybindings > Simple Gob Hider.
--
-- The toggle, the hidden set and the yellow patches are all kept PER SESSION: the client can hold
-- several logins at once, and a Gob's own position resolves only in the session that is actually
-- drawn (docs/addons/api/world.md -- worldToScreen is "the drawn session's only"). A single shared
-- set mixed gobs from every login together, so switching to a session that did not do the hiding
-- left its trees correctly hidden -- g:visible(false) is a write on the object, and sticks -- but
-- with no patch, because that gob's ground could never be projected through another login's world.

-- Each entry matches as a plain SUBSTRING of gob:name(), the resource name the server sent, so
-- "gfx/terobjs/trees/" takes every tree and "trees/fir" only firs. To learn a name, stand next to it:
--   :lua hafen.log():write(tostring(hafen.session():current():world():gob():nearest():name()))
local HIDDEN = {
  "gfx/terobjs/trees/",
  "gfx/terobjs/bushes/",
  "gfx/terobjs/log",
}

local FILL       = {245, 215, 60, 120}
local OUTLINE    = {245, 215, 60, 230}
local LINE_WIDTH = 1
local PATCH      = 5.5   -- half-side of the square drawn for an object with no footprint, world units
local RESCAN     = 2     -- seconds between full re-reads

local state = {}   -- [session] = {active = bool, hidden = {[Gob] = true, ...}}; a session's own share

local function stateFor(s)
  local st = state[s]
  if not st then
    st = {active = false, hidden = {}}
    state[s] = st
  end
  return st
end

local function matches(name)
  for _, entry in ipairs(HIDDEN) do
    if name:find(entry, 1, true) then return true end
  end
  return false
end

-- Re-read, never remembered: a felled tree keeps its id and becomes a log, so a verdict reached once
-- goes stale. A nil name is "not yet" -- a player or an animal resolves its own after it arrives.
local function consider(st, g)
  local name = g:name()
  if name == nil then return end
  if matches(name) then
    if not st.hidden[g] then
      st.hidden[g] = true
      g:visible(false)
    end
  elseif st.hidden[g] then
    st.hidden[g] = nil
    g:visible(true)
  end
end

local function sweep(s, st)
  if not s:character() then return end
  for _, g in ipairs(s:world():gob():list()) do consider(st, g) end
end

hafen.client():options():keybindings():on("toggle", function()
  local s = hafen.session():current()
  if not s then return end
  local st = stateFor(s)
  if st.active then
    for g in pairs(st.hidden) do g:visible(true) end
    st.hidden, st.active = {}, false
  else
    st.active = true
    sweep(s, st)
  end
end)

-- GobAdded runs before the object's first drawn frame, so a match never appears at all. It fires once
-- per object, whichever of your sessions can see it, so it is offered to each in turn.
hafen.event():on("GobAdded", function(g)
  for _, s in ipairs(g:sessions():list()) do
    local st = state[s]
    if st and st.active then consider(st, g) end
  end
end)

-- The gob is already gone, so g:sessions() answers empty here -- drop it from every session's own set
-- rather than trying to name the one it belonged to.
hafen.event():on("GobRemoved", function(g)
  for _, st in pairs(state) do st.hidden[g] = nil end
end)

hafen.event():on("SessionRemoved", function(s) state[s] = nil end)

hafen.timer():every(RESCAN, function()
  for s, st in pairs(state) do
    if st.active then sweep(s, st) end
  end
end)

-- ---------------------------------------------------------------- the yellow, drawn

local function drawRing(g, world, ring, ox, oy)
  local n = #ring
  if n < 3 then return end
  local pts = {}
  for i = 1, n do
    -- worldToScreen answers ROOT design pixels; a painter is already translated to its widget.
    local sp = world:worldToScreen(ring[i])
    if sp == nil then return end          -- a corner we cannot place drops the whole ring
    pts[#pts + 1] = sp.x - ox
    pts[#pts + 1] = sp.y - oy
  end
  g:color(table.unpack(FILL))
  g:poly(table.unpack(pts))
  g:color(table.unpack(OUTLINE))
  for i = 1, n do
    local a, b = (i - 1) * 2, (i % n) * 2  -- and the last edge closes the ring
    g:line(pts[a + 1], pts[a + 2], pts[b + 1], pts[b + 2], LINE_WIDTH)
  end
end

-- gfx/terobjs/log has a mesh and no obst layer, so gob:hitbox() is nil and there is no shape to trace.
local function square(p, r)
  local a, b = p:offset(-r, -r), p:offset(r, -r)
  local c, d = p:offset(r, r), p:offset(-r, r)
  if a and b and c and d then return {a, b, c, d} end
end

-- s is the session whose OWN MapView is drawing: its world is the only one that can place its own
-- hidden set's positions on screen, so both the toggle and the patches stay that session's alone.
local function drawPatches(g, s, ox, oy)
  local st = state[s]
  if not (st and st.active) then return end
  local world = s:world()
  if not world then return end
  for gob in pairs(st.hidden) do
    -- Read every frame: a footprint turns with the object and is replaced when its resource is.
    local box = gob:hitbox()
    if box then
      for _, ring in ipairs(box) do drawRing(g, world, ring, ox, oy) end
    else
      local p = gob:position()
      local ring = p and square(p, PATCH)
      if ring then drawRing(g, world, ring, ox, oy) end
    end
  end
  g:color()
end

-- On the MapView rather than hafen.ui():overlay(): a painter hung on a widget draws in that widget's
-- own slot, so the patches sit over the world and under the client's windows. It dies with the widget,
-- so it is hung from the MapView's arrival, and `watched` keeps a session from subscribing twice.
local watched = {}

local function watch(s)
  if watched[s] then return end
  watched[s] = true
  s:ui():on("@MapView", "Added", function(mv)
    mv:overlay():add("patches"):draw(function(g, w, h)
      local o = mv:rootPos()
      if o then drawPatches(g, s, o.x, o.y) end
    end)
  end)
end

hafen.event():on("SessionEnteredWorld", watch)

hafen.event():on("Load", function()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then watch(s) end
  end
end)

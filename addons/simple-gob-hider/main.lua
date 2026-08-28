-- Simple Gob Hider -- one key takes the objects you named out of the scene and lays a yellow patch on
-- the ground each of them stands on. Assign the key in Options > Keybindings > Simple Gob Hider.
--
-- The patch is a hafen.virtual():patch(), anchored to the object itself. That is what makes the ground mark
-- the object's own footprint rather than a flat shape drawn over it: a patch lies ON the terrain, so it
-- follows a slope, and whatever stands there occludes it.
--
-- Two things the API carries, so this file does not. A patch anchored to a Gob ENDS with that Gob, so a
-- felled tree takes its own mark down. And a thing standing in the world belongs to the world rather than
-- to the character who put it there, so the mark is visible from every login that can see the object --
-- where a screen-space painter can only place ground through the session being drawn, and leaves another
-- login's trees hidden but unmarked.

-- Each entry matches as a plain SUBSTRING of gob:name(), the resource name the server sent, so
-- "gfx/terobjs/trees/" takes every tree and "trees/fir" only firs. To learn a name, stand next to it:
--   :lua hafen.log():write(tostring(hafen.session():current():world():gob():nearest():name()))
local HIDDEN = {
  "gfx/terobjs/trees/",
  "gfx/terobjs/bushes/",
  "gfx/terobjs/log",
}

local TINT   = {245, 215, 60}   -- the yellow laid over the ground
local ALPHA  = 0.5              -- the fill, so the ground still reads through it
local EDGE   = 0.9              -- the rim, nearly solid
local BORDER = 0.6              -- how wide that rim is in WORLD units -- 0 lays no border at all
local PATCH  = 5.5              -- half-side of the square laid for an object with no footprint, world units
local RESCAN = 2                -- seconds between full re-reads

local state = {}   -- [session] = {active = bool, hidden = {[Gob] = true, ...}}; a session's own share

-- The marks are NOT per session, because a patch is not: one is laid per object, by whichever login
-- hid it first, and taken up when a login unhides it. That is the same last-writer-wins the visibility
-- write beside it has always had, and two logins hiding one tree now agree instead of drawing it twice.
local laid = {}    -- [Gob] = {patch, ...}

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

-- gfx/terobjs/log has a mesh and no obst layer, so gob:hitbox() is nil and there is no shape to trace.
local function square(p)
  local a, b = p:offset(-PATCH, -PATCH), p:offset(PATCH, -PATCH)
  local c, d = p:offset(PATCH, PATCH), p:offset(-PATCH, PATCH)
  if a and b and c and d then return {{a, b, c, d}} end
end

-- The rings to lay for one object, and whether they are the real footprint. hitbox() reads nil until the
-- object's resource resolves, which is why the sweep below comes back for the ones that fell back.
local function rings(g)
  local box = g:hitbox()
  if box and (#box > 0) then return box, true end
  local p = g:position()
  return p and square(p), false
end

local function patches()
  return hafen.virtual():patch()          -- the same object every call
end

local function unlay(g)
  local mine = laid[g]
  if not mine then return end
  laid[g] = nil
  for _, patch in ipairs(mine.own) do
    if patch:exists() then patches():remove(patch) end
  end
end

-- A ring the API refuses is a ring this addon cannot draw, not an error worth spilling: an obst layer is
-- whatever the resource's author drew, so a concave one or one past the edge limit is a real shape to
-- meet. Each ring goes on its own, so one bad ring in a set does not cost the others.
local function put(g, ring, alpha, own)
  local ok, patch = pcall(function()
    return patches():add(ring, g):tint(TINT):alpha(alpha)
  end)
  if ok and patch then own[#own + 1] = patch end
end

-- The rim is its own geometry, one thin quad per edge, because a patch has no outline verb and stacking
-- a bigger one underneath would not work: two overlays sort by MapMesh.OLOrder, which compares by object
-- identity, so which of the two lands on top is not something an addon can choose. A strip is extended by
-- half its width at each end so the corners close instead of leaving a notch.
local function border(g, ring, own)
  if BORDER <= 0 then return end
  local n = #ring
  for i = 1, n do
    local a, b = ring[i], ring[(i % n) + 1]
    local ax, ay, bx, by = a:x(), a:y(), b:x(), b:y()
    if ax and ay and bx and by then                  -- a place this character cannot locate has no edge
      local dx, dy = bx - ax, by - ay
      local len = math.sqrt((dx * dx) + (dy * dy))
      if len > 0 then
        local h = BORDER / 2
        local nx, ny = (-dy / len) * h, (dx / len) * h   -- across the edge
        local ex, ey = (dx / len) * h, (dy / len) * h    -- and along it, to close the corner
        put(g, {a:offset(nx - ex, ny - ey), b:offset(nx + ex, ny + ey),
                b:offset(-nx + ex, -ny + ey), a:offset(-nx - ex, -ny - ey)}, EDGE, own)
      end
    end
  end
end

local function lay(g)
  if laid[g] then return end
  local set, real = rings(g)
  if not set then return end
  local own = {}
  for _, ring in ipairs(set) do
    put(g, ring, ALPHA, own)
    border(g, ring, own)
  end
  if #own > 0 then laid[g] = {own = own, real = real} end
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
    lay(g)
  elseif st.hidden[g] then
    st.hidden[g] = nil
    g:visible(true)
    unlay(g)
  end
end

local function sweep(s, st)
  if not s:character() then return end
  for _, g in ipairs(s:world():gob():list()) do
    consider(st, g)
    -- An object hidden before its resource arrived wears the square. Come back for its real footprint
    -- once, rather than re-laying every sweep: taking a patch up and putting it down re-cuts the tiles
    -- under it, and a hundred trees doing that every two seconds is the one thing a patch is not free at.
    local mine = laid[g]
    if mine and not mine.real and g:hitbox() then
      unlay(g)
      lay(g)
    end
  end
end

hafen.timer():every(RESCAN, function()
  for s, st in pairs(state) do
    if st.active then sweep(s, st) end
  end
end)

hafen.client():options():keybindings():on("toggle", function()
  local s = hafen.session():current()
  if not s then return end
  local st = stateFor(s)
  if st.active then
    for g in pairs(st.hidden) do
      g:visible(true)
      unlay(g)
    end
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
-- rather than trying to name the one it belonged to. Its patches went with it: one anchored to a Gob
-- ends with that Gob, so there is nothing here to take up.
hafen.event():on("GobRemoved", function(g)
  laid[g] = nil
  for _, st in pairs(state) do st.hidden[g] = nil end
end)

hafen.event():on("SessionRemoved", function(s) state[s] = nil end)

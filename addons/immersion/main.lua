-- Immersion -- labels the nearest game object within 33 world units (three tiles), inside a 180-degree
-- cone in front of the character on screen, with its resource name floating over it. The label follows
-- as you walk or turn: onto whichever object is now nearest and ahead of you, or off entirely once
-- nothing qualifies. A hotkey right-clicks whatever is labelled.
--
-- Read against ONE session, hafen.session():current() -- the character being drawn -- like the
-- getting-started tutorial. A second login's own nearest object is not tracked.

local KEY        = "immersion"
local RADIUS     = 33               -- world units; three tiles
local HALF_CONE  = math.pi / 2      -- 90 degrees either side of facing = a 180-degree cone
local PERIOD     = 0.1

local current   -- the Gob currently carrying the label, or nil

-- The signed way round from `b` to `a`, in (-pi, pi] -- same shape as wasd-movement's `turned`, needed
-- here because facing and bearing both wrap at +-pi and a plain subtraction gets that wrong right at
-- the seam (an object almost dead behind you must not read as just over the 90-degree edge of the cone
-- when it is really a hair inside it, on the other side of the wrap).
local function turned(a, b)
  local d = (a - b) % (2 * math.pi)
  if d > math.pi then d = d - 2 * math.pi end
  return d
end

hafen.timer():every(PERIOD, function()
  local s      = hafen.session():current()
  local w      = s and s:world()
  local me     = s and s:player():gob()
  local mp     = me and me:position()
  local facing = me and me:facing()

  local g
  if w and mp and mp:x() and facing then
    local mx, my = mp:x(), mp:y()

    -- Three kinds of object are not what this is for, and each is ruled out by its own test.
    -- gob:player() is true for every player body, YOURS INCLUDED, so it tells other players from
    -- creatures and clutter but not you from them -- the identity check against `me` is what rules
    -- out your own. A NEGATIVE id is a client-only object OCache never got from the server:
    -- footstep-dust and other animation-triggered FX sprites (haven Skeleton.FxTrack.SpawnSprite)
    -- land in the very collection this reads, and they spawn at your own feet while you walk.
    -- A gob with NO NAME is one the server placed but never sent a resource for -- it draws nothing,
    -- so a label on one is a label on empty ground.
    g = w:gob():nearest(function(cand)
      if cand:id() < 0 then return false end
      if cand == me then return false end
      if cand:player() then return false end
      if not cand:name() then return false end
      local cp = cand:position()
      local cx, cy = cp and cp:x(), cp and cp:y()
      if not (cx and cy) then return false end
      if (cx == mx) and (cy == my) then return true end   -- standing on it: no bearing to check
      local bearing = math.atan2(cy - my, cx - mx)
      return math.abs(turned(bearing, facing)) <= HALF_CONE
    end)
    if g and (not g:distance() or g:distance() > RADIUS) then g = nil end
  end

  if g == current then return end

  -- Safe even once `current` has left view: overlay():remove is inert on a gone gob, and its own
  -- label already died with it -- see docs/addons/api/overlay.md#the-overlay-object.
  if current then current:overlay():remove(KEY) end
  if g then g:overlay():add(KEY):text(g:name()) end   -- the resource name, e.g. "gfx/terobjs/tree"
  current = g
end)

-- Starts unbound: assign a key in Options > Keybindings > Immersion. Right-clicks whatever currently
-- carries the label, exactly as a real right-click on it would (gob.click).
hafen.client():options():keybindings():on("click", function()
  local s = hafen.session():current()
  if s and current then s:world():click(current, 3) end
end)

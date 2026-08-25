-- WASD Movement -- four keys that walk the character the way the CAMERA is looking, as an MMO does.
--
-- One idea, and the whole addon is it: the keys that are down give a direction on the screen, and the walk
-- is one move sent far along it. The server walks the rest of the way, so a key held costs one order rather
-- than one a frame. What stops it is the last key coming up, and what re-aims it is the camera turning.
--
-- Every tick asks the four bindings which are DOWN, rather than counting hotkey fires. The hotkey is an
-- edge and this needs the level: the desktop repeats only the key pressed last, so W held while D is tapped
-- stops repeating and never starts again -- which is to say a diagonal is invisible to it.

local WALK_TILES = 100           -- how far ahead the walk aims. Far enough that one order outlasts any hold
local TILE = 11                  -- world units to the tile, the client's own constant
local REACH = WALK_TILES * TILE
local PROBE = TILE               -- how far the camera probes reach: one tile either way

local TICK = 0.05                -- how often the keys and the camera are read, while anything is held
local TURN_EPS = math.pi / 45    -- four degrees: how far things have to come round to be worth re-aiming

-- ---------------------------------------------------------------- the keys

local keys = hafen.client():options():keybindings()
local saved = hafen.store():get("settings")

-- The four keys, as a direction ON THE SCREEN: `up` is towards the top of it and `right` towards the right.
-- Two of them at once add, which is the whole of the diagonals -- W and D give (1, 1), which is forty-five
-- degrees between them -- and two opposite ones cancel to nothing, which is standing still.
--   The name is the row the user sees in Options > Keybindings, which is why it names the key it is for.
local DIRS = {
  {name = "Forward (W)", up =  1, right =  0},
  {name = "Left (A)",    up =  0, right = -1},
  {name = "Back (S)",    up = -1, right =  0},
  {name = "Right (D)",   up =  0, right =  1},
}

local bindings = {}                       -- the Binding object behind each row, for :down()
local moving = nil                        -- while walking: session, cam, turn -- the last thing SENT
local watch = nil                         -- the tick; alive only while a key is down
local enabled = saved.enabled ~= false    -- on unless the user turned it off; :wasd toggles it

-- ---------------------------------------------------------------- where the camera is looking

-- The world bearing that goes UP THE SCREEN: the camera's own forward, flat on the ground. It is what W
-- means, and what every other combination is measured off.
--
-- The client's camera has an angle of its own and nothing hands it to an addon, so it is read off the
-- projection instead. Project the character, then a point one tile east of it and one tile south: three
-- screen points give the little 2x2 map from a step in the world to a step on the screen. Invert it, and
-- ask it which world step draws straight up the screen. That answers for every camera the client has --
-- follow, worse, bad, ortho, rts -- at any rotation, elevation and zoom, and it never has to know which of
-- them is on.
local function screenUp(s, from)
  local w = s:world()
  local o = w:worldToScreen(from)
  local e = w:worldToScreen(from:offset(PROBE, 0))          -- one probe east...
  local n = w:worldToScreen(from:offset(0, PROBE))          -- ...and one south
  if not (o and e and n) then return nil end

  local ax, ay = e.x - o.x, e.y - o.y                       -- what a step east does on the screen
  local bx, by = n.x - o.x, n.y - o.y                       -- and what a step south does
  local det = ax * by - bx * ay
  if math.abs(det) < 1e-6 then return nil end               -- edge on: the ground projects to a line

  -- The world step whose screen step is (0, -1). Screen y counts downwards, so up the screen is negative.
  return math.atan2(-ax / det, bx / det)
end

-- The signed way round from `b` to `a`, in (-pi, pi]. Two bearings a hair either side of due east are a
-- hair apart, and only this says so.
local function turned(a, b)
  local d = (a - b) % (2 * math.pi)
  if d > math.pi then d = d - 2 * math.pi end
  return d
end

-- Which way the keys that are down are pointing, as a turn off the camera's bearing -- or nil for none.
-- atan2 of the two screen components is the whole of it: (1,0) is straight on, (1,1) is forty-five degrees
-- to the right of it, (0,-1) is a quarter turn to the left.
local function pressed()
  local up, right = 0, 0
  for _, d in ipairs(DIRS) do
    if bindings[d.name]:down() then
      up = up + d.up
      right = right + d.right
    end
  end
  if (up == 0) and (right == 0) then return nil end         -- nothing down, or two that cancel
  return math.atan2(right, up)
end

-- ---------------------------------------------------------------- walking, and stopping

-- Stop where the character stands, by sending exactly what ESCAPE sends. The stop is not a widget's
-- "cancel" and not a click: it is a GLOBAL KEY forwarded to the server. A key no widget claimed reaches
-- RootWidget.globtype, which puts `gk` on the wire with the character code and the modifiers, and the
-- server reads 27 -- escape -- as "drop whatever that character is doing", the walk in flight included.
-- So the root widget is the receiver, `gk` is the message, and 27 and 0 are the key and no modifiers.
--   A move to the character's own feet is NOT a stop, however much it reads like one. The order carries
-- the point the gob was AT when it was read, the character keeps walking while the order travels, and it
-- lands as an order to come BACK the step taken in between -- a halt that visibly rocks backwards.
local ESC = 27                    -- what the client reads off an Escape keypress, and what `gk` carries

local function halt(s)
  local root = s and s:exists() and s:ui():match("@RootWidget")
  if root then root:send("gk", ESC, 0) end
end

local function unwatch()
  if watch then watch:cancel() end
  watch = nil
end

local function release()
  unwatch()
  if moving then
    local s = moving.session
    moving = nil
    halt(s)
  end
end

-- Aim from where the character is NOW, which on a re-aim is not where it set off from.
local function walk(s, me, angle)
  local from = me:position()
  local to = from and from:offset(math.cos(angle) * REACH, math.sin(angle) * REACH)
  if not to then return false end        -- a character with no coordinate here has nowhere to be sent
  s:player():move(to)
  return true
end

-- One beat: read the keys, read the camera, and send an order only if either has moved. An order is not
-- free, so a bearing that has come round by less than TURN_EPS is not worth one -- which is what keeps a
-- camera drifting a hair, or a key held dead still, at nought orders a second.
local function beat()
  if not enabled then release() ; return end

  local turn = pressed()
  if not turn then release() ; return end                   -- the last key came up

  local s = hafen.session():current()
  local me = s and s:player():gob()
  local at = me and me:position()
  if not at then return end                                 -- no character on screen to walk

  local cam = screenUp(s, at)
  if not cam then return end

  if moving and (moving.session ~= s) then
    halt(moving.session)                                    -- never leave an alt walking
    moving = nil
  end
  if moving and (math.abs(turned(cam, moving.cam)) < TURN_EPS)
     and (math.abs(turned(turn, moving.turn)) < TURN_EPS) then
    return                                                  -- same keys, same camera: nothing to say
  end
  if walk(s, me, cam + turn) then
    moving = {session = s, cam = cam, turn = turn}
  end
end

-- The hotkey's job is to declare the row in Options > Keybindings and to WAKE the tick; the tick does the
-- rest and stops itself when nothing is held. So an addon whose keys are not being touched costs nothing,
-- and a key pressed into a text field starts nothing -- a hotkey does not fire while one has the focus.
for _, d in ipairs(DIRS) do
  keys:on(d.name, function()
    if not enabled then return end
    if not watch then watch = hafen.timer():every(TICK, beat) end
    beat()                               -- answer the first press on the press, not a tick later
  end)
  bindings[d.name] = keys:binding():get(d.name)
end

-- ---------------------------------------------------------------- turning it off

local function report()
  local unbound = {}
  for _, d in ipairs(DIRS) do
    if not bindings[d.name]:key() then unbound[#unbound + 1] = d.name end
  end
  hafen.log():write("wasd: " .. (enabled and "on" or "off") .. ", "
                    .. (#DIRS - #unbound) .. " of " .. #DIRS .. " keys bound")
  if enabled and #unbound > 0 then
    hafen.log():write("wasd: bind " .. table.concat(unbound, ", ")
                      .. " in Options > Keybindings > WASD Movement")
  end
end

hafen.console():on("wasd", function(args)
  local a = args[1]
  if a == nil then enabled = not enabled
  elseif a == "on" then enabled = true
  elseif a == "off" then enabled = false
  else
    hafen.log():write("wasd: ':wasd' toggles, ':wasd on' and ':wasd off' say which")
    return
  end
  saved.enabled = enabled
  if not enabled then release() end
  report()
end)

-- A reload mid-stride must not leave the character walking to a point 100 tiles away with nothing left
-- running to stop it. Disable is the last moment a write still reaches the server.
hafen.event():on("Disable", release)

hafen.event():on("SessionRemoved", function(s)
  if moving and (moving.session == s) then unwatch(); moving = nil end   -- nothing to halt: it is gone
end)

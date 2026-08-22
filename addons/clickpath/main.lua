local ICON_PATH = "arrow.png"    -- the click marker
local FLAG_PATH = "flag.png"     -- the waypoint flag
local MARKER_LIFE = 0.5          -- seconds the click marker stays visible
local FLAG_SCALE = 1.2           -- waypoint flag size, in tiles
local ARRIVE_DIST = 6            -- world units considered "arrived" at a waypoint
local POLL_INTERVAL = 0.2        -- how often we check for arrival
local STUCK_POLLS = 5            -- polls stopped-but-not-arrived before the path is dropped
local LINE_WIDTH = 2             -- design pixels

local LEG_COLOR  = {60, 230, 90, 220}    -- green: where we are walking right now
local PATH_COLOR = {245, 215, 60, 200}   -- yellow: the queued legs after it

local DEBUG = false              -- one log line per map click

local icon, flagIcon             -- the images, loaded once
local current                    -- the waypoint we are walking to, or nil when idle
local queue = {}                 -- waypoints after `current`, in walk order
local markers = {}               -- live click markers: {sprite = , age = }
local autoMoving = false         -- true while we issue a queued move ourselves
local stopped = 0                -- consecutive polls stopped short of `current`

-- The character on screen, as a Player object, or nil on the login screen. Read inside every handler
-- rather than kept: hafen.session():current() is whoever holds the screen at the moment you ask, and
-- a left click on the ground is always the drawn character's.
local function me()
  local s = hafen.session():current()
  return s and s:player()
end

hafen.event():on("Load", function()
  icon = hafen.asset():get(ICON_PATH)
  flagIcon = hafen.asset():get(FLAG_PATH)
  if not icon then hafen.log():write("clickpath: '" .. ICON_PATH .. "' did not load") end
  if not flagIcon then hafen.log():write("clickpath: '" .. FLAG_PATH .. "' did not load") end
end)

-- ---------------------------------------------------------------- waypoints and their flags

-- A waypoint is its place plus the flag standing on it, so the two can never drift apart: the flag
-- is planted when the point is queued and struck when it is reached, dropped or replaced.
local function waypoint(p, flagged)
  local w = {pos = p}
  if flagged and flagIcon then
    w.flag = hafen.vr():sprite():add(flagIcon, p):facing("fixed"):scale(FLAG_SCALE)
  end
  return w
end

local function strike(w)
  if w and w.flag then hafen.vr():sprite():remove(w.flag) end
end

local function clearPath()
  strike(current)
  for _, w in ipairs(queue) do strike(w) end
  current, queue, stopped = nil, {}, 0
end

-- ---------------------------------------------------------------- the click marker

local function showMarker(p)
  if not icon then return end
  local s = hafen.vr():sprite():add(icon, p):facing("fixed"):scale(1.5):alpha(1)
  table.insert(markers, {sprite = s, age = 0})
end

-- One handler for every marker: smooth, and a fast clicker does not pile up timers.
hafen.event():on("Update", function(dt)
  for i = #markers, 1, -1 do
    local m = markers[i]
    m.age = m.age + dt
    local t = m.age / MARKER_LIFE
    if t >= 1 then
      hafen.vr():sprite():remove(m.sprite)
      table.remove(markers, i)
    else
      m.sprite:scale(1.5 - 0.8 * t):alpha(1 - t)   -- settles onto the spot and fades
    end
  end
end)

-- ---------------------------------------------------------------- the path, drawn

-- hafen.vr() has no line primitive -- its four collections are props, images, glTF meshes and
-- widgets, and :scale is uniform, so a sprite cannot be stretched into a segment. The path is
-- therefore drawn over the HUD from the projected world points, which is what worldToScreen is for.
-- It draws on top of the scene: a line does not disappear behind a hill the way a sprite does.

-- The two spaces line up on their own: player:worldToScreen answers ROOT DESIGN pixels, which
-- is the space a HUD overlay's g draws in, so a projected point goes straight into g:line.
hafen.ui():overlay():add("path"):draw(function(g, w, h)
  if current == nil then return end
  local pl = me()
  local mine = pl and pl:gob()
  local from = mine and mine:position()
  if not from then return end

  -- The whole path in order, each point projected exactly once.
  local pts = {from, current.pos}
  for _, wp in ipairs(queue) do pts[#pts + 1] = wp.pos end

  local scr = {}
  for i, p in ipairs(pts) do
    scr[i] = pl:worldToScreen(p)
  end

  for i = 1, #pts - 1 do          -- over pts, not scr: an unprojectable point leaves a hole
    local a, b = scr[i], scr[i + 1]
    if a and b then                       -- a leg with an endpoint off screen is simply not drawn
      local c = (i == 1) and LEG_COLOR or PATH_COLOR
      g:color(c[1], c[2], c[3], c[4])
      g:line(a.x, a.y, b.x, b.y, LINE_WIDTH)
    end
  end
  g:color()                               -- reset for whoever draws after us
end)

-- ---------------------------------------------------------------- walking it

local function moveTo(w)
  local pl = me()
  if not pl then return end
  autoMoving = true                -- a move sent from the timer would re-enter the handler below
  pl:move(w.pos)
  autoMoving = false
  stopped = 0
end

hafen.event():action():on("click", function(ev)
  if autoMoving then return end

  -- "click" is not the map's alone: Avaview sends wdgmsg("click", button), ISBox sends it bare.
  -- Only the MapView's carries a destination, so everything else must fall straight through.
  local sender = ev:widget()
  if not sender or sender:type() ~= "MapView" then return end

  local a = ev:args()
  -- args are {pc, mc, button, modflags}; a hit object appends its own from index 5, and clicking
  -- one is an interaction rather than a walk, so it is none of our business.
  if a[5] ~= nil then return end

  -- Argument 1 is the SCREEN pixel and argument 2 the world point, the same {x=, y=} shape in two
  -- different spaces. :position(2) names the one we mean, so the wire scale is the client's problem.
  local p = ev:position(2)

  if DEBUG then
    hafen.log():write(string.format("clickpath: dest=(%d,%d) dist=%d durable=%s",
      math.floor(p:x() or 0), math.floor(p:y() or 0),
      math.floor(p:distance() or 0), tostring(p:durable())))
  end

  showMarker(p)

  if hafen.ui():mouse():alt() then
    ev:preventDefault()            -- the queue owns this click
    local w = waypoint(p, true)    -- a queued point carries a flag until it is reached
    if current == nil then
      current = w
      moveTo(w)
    else
      table.insert(queue, w)       -- walk here once the ones before it are done
    end
  else
    clearPath()                    -- a plain click cancels the path and its flags
    current = waypoint(p, false)   -- the client sends this move itself; we only track it
  end
end)

hafen.timer():every(POLL_INTERVAL, function()
  if current == nil then return end

  local pl = me()
  local mine = pl and pl:gob()
  if not mine then return end

  local d = current.pos:distance()
  if d and d < ARRIVE_DIST then
    strike(current)                          -- reached: the flag comes down
    current = table.remove(queue, 1)
    if current then moveTo(current) end
    return
  end

  -- Blocked, or the server refused the walk: drop the path rather than leave it hanging forever.
  if mine:moving() then
    stopped = 0
  else
    stopped = stopped + 1
    if stopped >= STUCK_POLLS then clearPath() end
  end
end)

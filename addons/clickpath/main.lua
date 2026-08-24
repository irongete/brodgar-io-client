local FLAG_PATH = "flag.png"     -- the waypoint flag
local FLAG_SCALE = 1.2           -- waypoint flag size, in tiles
local ARRIVE_DIST = 6            -- world units considered "arrived" at a waypoint
local POLL_INTERVAL = 0.2        -- how often we check for arrival
local STUCK_POLLS = 5            -- polls stopped-but-not-arrived before the path is dropped
local LINE_WIDTH = 2             -- design pixels
local OFFSCREEN_ALPHA = 0.4      -- a path belonging to a character you are not looking at, dimmed

local LEG_COLOR  = {60, 230, 90, 220}    -- green: where that character is walking right now
local PATH_COLOR = {245, 215, 60, 200}   -- yellow: the queued legs after it

local DEBUG = false              -- one log line per map click

local flagIcon                   -- the flag image, loaded once

-- One path PER CHARACTER, keyed by the Session -- interned per addon, so it is a valid table key and
-- `==` is the identity test. Each holds {current = the waypoint being walked to, queue = the ones after
-- it, stopped = consecutive polls short of it}. A character with no path has no entry at all.
local paths = {}
local autoMoving = false         -- true while we issue a queued move ourselves

local function pathOf(s)
  local p = paths[s]
  if p == nil then
    p = {queue = {}, stopped = 0}
    paths[s] = p
  end
  return p
end

hafen.event():on("Load", function()
  flagIcon = hafen.asset():get(FLAG_PATH)
  if not flagIcon then hafen.log():write("clickpath: '" .. FLAG_PATH .. "' did not load") end
end)

-- ---------------------------------------------------------------- waypoints and their flags

-- A waypoint is its place plus the flag standing on it, so the two can never drift apart: the flag
-- is planted when the point is queued and struck when it is reached, dropped or replaced.
--
-- The flag needs no session: hafen.vr() takes a Position, and a Position carries no session, so one
-- planted by any character stands in whichever world is drawn. Ground the drawn character cannot locate
-- is a legal place to stand one -- the entity exists and simply is not drawn -- which is the same answer
-- the lines below give for that place, by construction rather than by agreement.
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

local function clearPath(s)
  local p = paths[s]
  if p == nil then return end
  strike(p.current)
  for _, w in ipairs(p.queue) do strike(w) end
  paths[s] = nil               -- no entry means no path: the census and the drawing are one list
end

hafen.event():on("SessionRemoved", function(s)
  clearPath(s)                 -- the flags of a character that logged out come down with it
end)

-- ---------------------------------------------------------------- the paths, drawn

-- hafen.vr() has no line primitive -- its four collections are props, images, glTF meshes and
-- widgets, and :scale is uniform, so a sprite cannot be stretched into a segment. The path is
-- therefore drawn from the projected world points, which is what worldToScreen is for.

-- The RECEIVER picks the layer, and there is no switch to set: hafen.ui():overlay() paints after the
-- whole root has drawn, so it covers every window; a painter hung on a WIDGET runs in that widget's
-- own draw slot instead. Ours hangs on the MapView, so the client's windows -- inventory, chat, belt,
-- the action menu -- all draw after it and cover the lines, and the lines are clipped to the map's box.

-- EVERY character's path is drawn, not the screen's alone. Only one UI is drawn at a time, so this is
-- one screen showing several characters' paths rather than several screens: the projection is the DRAWN
-- session's -- worldToScreen answers nil for any other -- while the places it projects may belong to anyone,
-- since a Position carries no session. An alt walking ground this character cannot locate projects nil
-- and its legs are simply not drawn, which is the honest answer rather than a hole.
local function drawPath(g, ox, oy)
  local cur = hafen.session():current()
  local world = cur and cur:world()
  if not world then return end

  for s, path in pairs(paths) do
    local pl = path.current and s:player()
    local mine = pl and pl:gob()
    local from = mine and mine:position()
    if from then
      -- The whole path in order, each point projected exactly once.
      local pts = {from, path.current.pos}
      for _, wp in ipairs(path.queue) do pts[#pts + 1] = wp.pos end

      local scr = {}
      for i, p in ipairs(pts) do
        scr[i] = world:worldToScreen(p)              -- the DRAWN character's world, whoever owns the place
      end

      local fade = (s == cur) and 1 or OFFSCREEN_ALPHA
      for i = 1, #pts - 1 do        -- over pts, not scr: an unprojectable point leaves a hole
        local a, b = scr[i], scr[i + 1]
        if a and b then                     -- a leg with an endpoint off screen is simply not drawn
          local c = (i == 1) and LEG_COLOR or PATH_COLOR
          g:color(c[1], c[2], c[3], math.floor(c[4] * fade))
          -- worldToScreen answers ROOT design pixels; a widget's painter is handed a g already translated
          -- to that widget's top-left, so the map's own root position is what stands between the two.
          g:line(a.x - ox, a.y - oy, b.x - ox, b.y - oy, LINE_WIDTH)
        end
      end
    end
  end
  g:color()                                 -- reset for whoever draws after us
end

-- An overlay on a widget dies with the widget, so the painter is hung from the arrival of the MapView
-- rather than once at load. The session events report CHANGES, not the state -- a session already in the
-- world when we load announces nothing -- so the sessions the client already holds are walked as well,
-- and `watched` keeps the pair from subscribing twice over one of them: picking another character on the
-- same account fires SessionEnteredWorld again, with the session alive throughout.
local watched = {}                 -- keyed by the Session, which is interned per addon

local function watch(s)
  if watched[s] then return end
  watched[s] = true
  s:ui():on("@MapView", "Added", function(mv)
    mv:overlay():add("path"):draw(function(g, w, h)
      local o = mv:rootPos()
      if o then drawPath(g, o.x, o.y) end
    end)
  end)
end

hafen.event():on("SessionEnteredWorld", watch)

hafen.event():on("Load", function()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then watch(s) end          -- nil until its HUD is up, which is what we are after
  end
end)

-- ---------------------------------------------------------------- walking them

local function moveTo(s, w)
  local pl = s:player()
  if not pl then return end
  autoMoving = true                -- a move sent from the timer would re-enter the handler below
  pl:move(w.pos)                   -- walking is what a character you are not looking at will take
  autoMoving = false
end

hafen.event():action():on("click", function(ev)
  if autoMoving then return end

  -- "click" is not the map's alone: Avaview sends wdgmsg("click", button), ISBox sends it bare.
  -- Only the MapView's carries a destination, so everything else must fall straight through.
  local sender = ev:widget()
  if not sender or sender:type() ~= "MapView" then return end

  -- Whose map was clicked. A widget belongs to one character's tree, so the click names its owner
  -- outright -- there is nothing to infer from whoever happens to hold the screen.
  local s = sender:session()
  if not s then return end

  local a = ev:args()
  -- args are {pc, mc, button, modflags}; a hit object appends its own from index 5, and clicking
  -- one is an interaction rather than a walk, so it is none of our business.
  if a[5] ~= nil then return end

  -- Argument 1 is the SCREEN pixel and argument 2 the world point, the same {x=, y=} shape in two
  -- different spaces. :position(2) names the one we mean, so the wire scale is the client's problem.
  local p = ev:position(2)

  if DEBUG then
    hafen.log():write(string.format("clickpath: %s dest=(%d,%d) dist=%d durable=%s",
      s:character() or s:user(),
      math.floor(p:x() or 0), math.floor(p:y() or 0),
      math.floor(s:world():distance(p) or 0), tostring(p:durable())))
  end

  if hafen.ui():mouse():alt() then
    ev:preventDefault()            -- the queue owns this click
    local path = pathOf(s)
    local w = waypoint(p, true)    -- a queued point carries a flag until it is reached
    if path.current == nil then
      path.current = w
      path.stopped = 0
      moveTo(s, w)
    else
      table.insert(path.queue, w)  -- walk here once the ones before it are done
    end
  else
    clearPath(s)                   -- a plain click cancels THAT character's path and its flags
    pathOf(s).current = waypoint(p, false)   -- the client sends this move itself; we only track it
  end
end)

hafen.timer():every(POLL_INTERVAL, function()
  for s, path in pairs(paths) do   -- pairs() allows clearing the key it stands on, which is all we do
    local pl = path.current and s:exists() and s:player()
    local mine = pl and pl:gob()
    if mine then
      -- ADDRESSED, not bare: p:distance() measures from the character ON SCREEN, so an alt would "arrive"
      -- the moment you walked up to its waypoint yourself. s:world():distance(p) is that question, kept.
      local d = s:world():distance(path.current.pos)
      if d and d < ARRIVE_DIST then
        strike(path.current)                       -- reached: the flag comes down
        path.current = table.remove(path.queue, 1)
        path.stopped = 0
        if path.current then
          moveTo(s, path.current)
        else
          paths[s] = nil                           -- that character is done: no entry, no path
        end
      elseif mine:moving() then
        path.stopped = 0
      else
        -- Blocked, or the server refused the walk: drop the path rather than leave it hanging forever.
        path.stopped = path.stopped + 1
        if path.stopped >= STUCK_POLLS then clearPath(s) end
      end
    end
  end
end)

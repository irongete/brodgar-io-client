-- Session Manager -- one row per login the client holds.
--
-- The window stands in the addon LAYER, above every session, so the screen moving does not touch it:
-- a switch relabels the rows it already has instead of building new ones, and only a login arriving
-- or leaving adds or removes one. That is the whole reason this is an addon and not a window on a
-- character's own HUD, which is rebuilt every time the anchor moves.
--
-- The place the user drags it to is the ACCOUNT's saved variable, not the character's: a switcher
-- belongs to the client rather than to whichever character happened to be on screen when it moved.

local PAD     = 6        -- design px between the content edge and the rows
local GAP     = 2        -- design px between two rows, and between a row's two buttons
local NAME_W  = 150      -- the go-to button
local CLOSE_W = 24       -- the X beside it: a Button's own art is 24 wide, and narrower clips it
local ROW_W   = NAME_W + GAP + CLOSE_W   -- one row across, and the width of the button under them all
local DEF_X, DEF_Y = 60, 60          -- where the window stands before the user has moved it
local SAVE_EVERY = 2                 -- seconds between reads of where the user put it

local win                            -- the window, or nil once the user has closed it
local rows = {}                      -- [account] = {go = <button>, close = <button>}
local newbtn                         -- the button under them: go to the login screen
local place = hafen.store():get("window")   -- the account's own table; filled before this file runs

-- The character where there is one, the account before there is: a session that has connected but not
-- reached the world is playing nobody yet, and the account is the whole of its name.
local function label(s)
  return s:character() or s:user()
end

-- Both buttons address by ACCOUNT and resolve at the press. The account is what a Session is, so the
-- handle survives everything that happens to the login behind it -- and :exists() is what says whether
-- there is still anything to do to it.
local function goTo(user)
  local s = hafen.session():get(user)
  if not s:exists() then return end
  -- :exists() says the login is there; it does not say it has a screen of its own yet, and asking for one
  -- it has not got is a refusal rather than a no-op. Pressing the row of a character still arriving is an
  -- ordinary thing to do, so the refusal is reported and nothing else happens.
  local ok, err = pcall(function() hafen.session():current(s) end)
  if not ok then hafen.log():write(err) end
end

local function shut(user)
  local s = hafen.session():get(user)
  if s:exists() then s:close() end
end

local function makeRow(user)
  local go = hafen.ui():button():parent(win):position(PAD, PAD):size(NAME_W):text(user)
  go:on("Pressed", function() goTo(user) end)

  local x = hafen.ui():button():parent(win):position(PAD, PAD):size(CLOSE_W):text("X")
    :tooltip("log this character out")
  x:on("Pressed", function() shut(user) end)

  return {go = go, close = x}
end

-- One pass over the sessions the client holds: a row per account, reused where it already exists.
-- Nothing is destroyed and rebuilt for a relabel, which is what keeps a switch from flickering.
local function refresh()
  if not (win and win:exists()) then return end

  local list = hafen.session():list()
  local cur = hafen.session():current()
  local seen, y = {}, PAD

  for _, s in ipairs(list) do
    local user = s:user()
    seen[user] = true

    local r = rows[user]
    if not r then
      r = makeRow(user)
      rows[user] = r
    end

    local name = label(s)
    r.go:text((s == cur) and ("* " .. name) or name)    -- the row on screen is the marked one
    r.go:position(PAD, y)
    r.close:position(PAD + NAME_W + GAP, y)
    y = y + r.go:size().h + GAP
  end

  for user, r in pairs(rows) do                         -- the logins that have gone
    if not seen[user] then
      r.go:destroy()
      r.close:destroy()
      rows[user] = nil
    end
  end

  -- Under the rows, and moved rather than rebuilt like them: it is the one button that is about no
  -- particular login, so it does not come and go with them.
  if newbtn and newbtn:exists() then
    newbtn:position(PAD, y)
    y = y + newbtn:size().h + GAP
  end

  win:size(PAD + ROW_W + PAD, y - GAP + PAD)
  win:visible(#list > 0)      -- with no session there is nothing to switch between, and the login screen
end                           -- is already what you are looking at

-- A NEW LOGIN. The client's own login screen is live behind every session -- it is what the client goes
-- back to waiting on the moment it hands one over -- so giving it the screen is the whole of "log another
-- account in": whatever is logged in there arrives as a session like any other, and takes the screen
-- because nothing else is holding it. It is also the only door for an account whose token this client has
-- not saved yet, which `:session add` cannot reach at all.
--
-- The sessions behind it go on running: nothing is dropped, nothing is disconnected, and a row of this
-- window is what comes back to one. Refreshed by hand afterwards because going to the login screen is
-- nobody being SELECTED -- no session was picked, so no SessionSelected is fired -- and the `*` would
-- otherwise stay on the character that has just stopped being on screen.
local function login()
  hafen.session():current(nil)
  refresh()
end

local function build()
  if win and win:exists() then return end
  rows = {}
  win = hafen.ui():window():title("Sessions")
    :position(place.x or DEF_X, place.y or DEF_Y)
    :size(PAD + ROW_W + PAD, PAD * 2)

  newbtn = hafen.ui():button():parent(win):position(PAD, PAD):size(ROW_W):text("New session")
    :tooltip("go to the login screen -- your characters stay logged in")
  newbtn:on("Pressed", login)

  -- The chrome's close button destroys the window, so there is nothing left to hide: what this addon
  -- keeps afterwards is "there is no window", and :sessions builds a new one at the saved place.
  win:on("Close", function()
    win, rows, newbtn = nil, {}, nil
  end)

  refresh()
end

-- The four session events are the whole of what changes a row: one connects, one reaches the world and
-- gains a character name, the screen moves, one ends.
for _, key in ipairs({"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved"}) do
  hafen.event():on(key, refresh)
end

-- ---------------------------------------------------------------- the base under each character
--
-- The client draws none of this. It knows which character is on screen and holds the selection, and it
-- keeps both to itself: what that looks like is here, in a file the user can edit, and an addon that
-- does not want a marker at all simply does not draw one.
--
-- It is drawn AT THE OBJECT, not over the map. gob:overlay():add(key):draw(fn) paints from the gob's own
-- render pass, so the point it hands the callback is where the client put that model THIS frame --
-- interpolated, and exactly on it. gob:position() is the last place the SERVER named, and a walking
-- character left that a whole move step ago: a base projected from it trails the feet it is drawn under,
-- which is the one thing a base must not do. The overlay hangs on the OBJECT rather than on a character,
-- so one attach draws in whichever session has the screen -- including the one drawing another login's
-- ground into its own scene -- and it dies with the object it is on.

local MARK_ON  = {64, 255, 64}          -- the character on screen: the one taking your clicks
local MARK_OFF = {255, 255, 255}        -- the others, standing where they stand
local RING_ON,  RING_OFF  = 255, 110    -- how solid the rim is, one per kind
local WIDTH_ON, WIDTH_OFF = 2, 1.5      -- how thick it is, in design pixels
local R_ON, R_OFF = 5, 4                -- the base's radius, in WORLD units: a tile is 11 across, so
                                        -- these are about a character wide, and they hold their size on
                                        -- the ground as you zoom rather than their size on the screen
local FILL  = 0.3                       -- the disc inside the rim, as a share of the rim's own alpha
local STEPS = 20                        -- segments the circle is drawn in
local PROBE = 4                         -- world units the camera is measured over
local FRESH = 0.25                      -- seconds a painted base is still worth hit-testing

-- The height gob:overlay() projects at. Its point sits just above the head, which is where a LABEL wants
-- to be and a base does not, and there is no verb that reads it back -- so it is written here, the one
-- number this file borrows from the other side of the API (docs/addons/api/overlay.md says it).
local ANCHOR_Z = 15

-- THE CAMERA, read off the projection rather than asked for -- there is no verb for its angle, and this
-- needs no angle. worldToScreen one probe step east and one south of a place answers what a world unit of
-- each is worth ON SCREEN, and those two vectors are the whole of what a circle lying on the ground needs:
-- they carry the turn of the view and its tilt together, so the disc squashes and swings with it without a
-- single angle being named. On a hillside the probes carry the slope too and the disc leans into it, which
-- is what "on the ground" means there.
--
-- The vertical falls out of the same two. On screen they are k*(-sin f, cos f * sin t) and
-- k*(cos f, sin f * sin t) for a view turned f and tilted t, so squaring and adding the first components
-- gives k^2 and the second ones k^2 * sin^2 t; what is left over is (k * cos t)^2 -- the screen length of
-- one world unit of HEIGHT, straight up. That is the number that turns the overlay's point over the head
-- into the ground under the feet. It reads a little short on steep ground, where the slope has already
-- been counted into the tilt: a pixel or two there, and nothing at all on the flat.
local basis      -- the last camera measured. A character standing on ground the drawn one has not
                 -- streamed in has no probes of its own -- worldToScreen answers nil there -- while the
                 -- object is still drawn, through that session's own patch of the scene, so it borrows
                 -- this rather than going without a base.

local function measure(w, p)
  local o = p and w:worldToScreen(p)
  if not o then return nil end
  local pe, ps = p:offset(PROBE, 0), p:offset(0, PROBE)
  local e = pe and w:worldToScreen(pe)
  local s = ps and w:worldToScreen(ps)
  if not (e and s) then return nil end
  local ex = {x = (e.x - o.x) / PROBE, y = (e.y - o.y) / PROBE}   -- one world unit east, on screen
  local ey = {x = (s.x - o.x) / PROBE, y = (s.y - o.y) / PROBE}   -- one world unit south
  local across = (ex.x * ex.x) + (ey.x * ey.x)                    -- k^2
  local down   = (ex.y * ex.y) + (ey.y * ey.y)                    -- k^2 * sin^2 t
  return {ex = ex, ey = ey, up = math.sqrt(math.max(0, across - down))}
end

-- What each base was last painted as, in the MAP VIEW's own design pixels: the centre and the ellipse's
-- two half-axes. ONE record behind both the drawing and the hit test, written by the very frame that drew
-- it, so what you can press is exactly what you can see -- a second copy of this arithmetic is the copy
-- that drifts a pixel and makes a base that cannot be pressed.
local bases = {}                        -- [session] = {at, cx, cy, ax, ay}
local now = 0                           -- seconds of drawing: the only clock "painted this frame" needs

local function paint(g, s, gob, sx, sy)
  local cur = hafen.session():current()
  local w = cur and cur:world()
  if not w then return end
  local b = measure(w, gob:position()) or basis
  if not b then return end
  basis = b

  local mine = (s == cur)
  local col  = mine and MARK_ON or MARK_OFF
  local rim  = mine and RING_ON or RING_OFF
  local r    = mine and R_ON or R_OFF
  local cx, cy = sx, sy + (ANCHOR_Z * b.up)      -- down the screen, from over the head to on the ground
  local ax = {x = b.ex.x * r, y = b.ex.y * r}    -- the ellipse's two half-axes, in design pixels
  local ay = {x = b.ey.x * r, y = b.ey.y * r}

  local px, py, pts = {}, {}, {}
  for i = 1, STEPS do
    local a = ((i - 1) / STEPS) * math.pi * 2
    local c, n = math.cos(a), math.sin(a)
    px[i] = cx + (ax.x * c) + (ay.x * n)
    py[i] = cy + (ax.y * c) + (ay.y * n)
    pts[#pts + 1] = px[i]
    pts[#pts + 1] = py[i]
  end

  g:color(col[1], col[2], col[3], math.floor(rim * FILL))
  g:poly(table.unpack(pts))                      -- the disc: one convex fan
  g:color(col[1], col[2], col[3], rim)
  local width = mine and WIDTH_ON or WIDTH_OFF
  for i = 1, STEPS do                            -- and the rim around it
    local j = (i % STEPS) + 1
    g:line(px[i], py[i], px[j], py[j], width)
  end
  g:color()

  bases[s] = {at = now, cx = cx, cy = cy, ax = ax, ay = ay}
end

-- The session whose base covers a point in the MAP VIEW's own pixels, or nil. The test is the ellipse's
-- own: solving the point against the two half-axes answers where it falls on the circle those axes are the
-- picture of, so inside is inside at every camera angle. A base the last frame did not paint is not there
-- to be pressed, whatever the record still says.
local function markAt(x, y)
  for s, m in pairs(bases) do
    local det = (m.ax.x * m.ay.y) - (m.ax.y * m.ay.x)
    if ((now - m.at) <= FRESH) and (math.abs(det) > 0.0001) then
      local dx, dy = x - m.cx, y - m.cy
      local u = ((dx * m.ay.y) - (dy * m.ay.x)) / det
      local v = ((m.ax.x * dy) - (m.ax.y * dx)) / det
      if ((u * u) + (v * v)) <= 1 then return s end
    end
  end
  return nil
end

-- One base per login, put on and taken off with the logins rather than rebuilt every frame. An object the
-- client cannot draw yet refuses the attach, and a character that has just entered the world is one for a
-- frame or two, so what did not land is retried from the frame that follows -- and the retry ends the
-- moment it does, which is why the frame event is the right place for it and a timer of its own is not.
local KEY = "session-base"
local marked = {}                       -- [session] = the gob id its base is attached to
local pending = false

local function unmark(s)
  local id = marked[s]
  if not id then return end
  marked[s], bases[s] = nil, nil
  local pl = s:exists() and s:player()
  local gob = pl and pl:gob()
  if gob and (gob:id() == id) then gob:overlay():remove(KEY) end
end

local function syncMarks()
  local list = hafen.session():list()
  local tell = (#list > 1)              -- with one login there is nobody to tell apart
  local seen = {}
  pending = false

  for _, s in ipairs(list) do
    local pl = tell and s:character() and s:player()
    local gob = pl and pl:gob()
    local id = gob and gob:id()
    if id and (marked[s] == id) then
      seen[s] = true
    elseif id then
      unmark(s)                         -- a character that came back is a new body under the same login
      local ok = pcall(function()
        gob:overlay():add(KEY):draw(function(g, gb, sx, sy) paint(g, s, gb, sx, sy) end)
      end)
      if ok then
        marked[s], seen[s] = id, true
      else
        pending = true                  -- loaded, and not renderable yet
      end
    elseif tell and s:character() then
      pending = true                    -- in the world, and its body has not reached this client yet
    end
  end

  for s in pairs(marked) do             -- the logins that have gone, and every base when one is left
    if not seen[s] then unmark(s) end
  end
end

hafen.event():on("Update", function(dt)
  now = now + dt
  if pending then syncMarks() end
end)

for _, key in ipairs({"SessionAdded", "SessionEnteredWorld", "SessionRemoved"}) do
  hafen.event():on(key, syncMarks)
end

syncMarks()                             -- and the logins the client already holds

-- ---------------------------------------------------------------- picking a character with the mouse
--
-- The client owns no gesture for this any more. The hotkey ARMS a pick: the pointer becomes a hand, and
-- the next click on the map names whoever it landed on. Two things count as naming somebody, and the
-- order matters -- the base is checked first, because it is drawn ON TOP of the ground and a press inside
-- it is a press the user aimed at the marker.
--
--   the character's own model  -- ev:gob() is the client's own pick pass, so it is the model that is hit
--   the base under it          -- markAt(), the very ellipse the last frame painted
--
-- Whatever it names, the click is CONSUMED: an armed pick that also walked your character somewhere is a
-- pick nobody would use. A click that names nobody disarms and is let through, so a miss costs one click
-- and not a stuck mode.

local picking = false                   -- is a pick armed right now?
local mouse = hafen.ui():mouse()

local function disarm()
  picking = false
  mouse:cursor(nil)                     -- ours only: it puts back what WE forced, and nothing else
end

local function arm()
  if picking then
    disarm()                            -- the same key again is "never mind"
    return
  end
  if hafen.session():count() < 2 then return end   -- nothing to pick between
  picking = true
  mouse:cursor("hand")
end

-- The session a click names, or nil. mv is the map view the click arrived at: a base is painted in that
-- view's own pixels and the mouse is read in the root's, so the view's corner stands between the two.
local function named(ev, mv)
  local o = mv:rootPos()
  local mx, my = mouse:x(), mouse:y()
  local s = (o and mx and my) and markAt(mx - o.x, my - o.y)   -- nil off-window, where there is no base
  if s then return s end
  local g = ev:gob()
  if not g then return nil end
  local id = g:id()
  for _, s2 in ipairs(hafen.session():list()) do
    local pl = s2:player()
    local own = pl and pl:gob()
    if own and (own:id() == id) then return s2 end
  end
  return nil
end

hafen.event():action():on("click", function(ev)
  if not picking then return end
  local sender = ev:widget()
  if not sender or sender:type() ~= "MapView" then return end

  local s = named(ev, sender)
  disarm()                              -- one click either way: armed is a moment, not a mode
  if not s then return end              -- it named nobody; the click is the client's, untouched
  ev:preventDefault()

  -- NEXT TICK, not here. We are inside the click's own dispatch: the map view is mid-message and the
  -- session that owns it is the one about to lose the screen, so switching from this line hands the
  -- screen away underneath the code still unwinding through it. A tick later there is no dispatch to be
  -- inside, and the switch is the SAME act the window's row performs -- which is the whole requirement:
  -- one way to go to a character, reached from two places.
  local user = s:user()
  hafen.timer():after(0, function() goTo(user) end)
end)

hafen.client():options():keybindings():on("Select character", arm)

-- ---------------------------------------------------------------- centring the view
--
-- Only the `rts` camera has a centre of its own to move -- every other one is bolted to the character --
-- so s:world():focus(p) refuses under the rest, and the refusal is worth showing rather than swallowing:
-- a key that silently does nothing is the one reported as broken.
hafen.client():options():keybindings():on("Focus selection", function()
  local cur = hafen.session():current()
  local pl = cur and cur:player()
  local gob = pl and pl:gob()
  local p = gob and gob:position()
  if not p then return end
  local ok, err = pcall(function() cur:world():focus(p) end)
  if not ok then hafen.log():write(err) end
end)

-- ---------------------------------------------------------------- the cycle hotkey
--
-- Forward through the sessions in the order they joined, and round. With nothing on screen the lap
-- starts at the first login, which is where the login screen leaves you.
--
-- A session that is still arriving has no screen to be given yet, and asking for one it has not got is a
-- refusal -- so the step is attempted and the cycle walks past that login rather than making the user
-- press the key twice for nothing. Bounded by the list: with nothing reachable it gives up.
local function cycle()
  local list = hafen.session():list()
  if #list == 0 then return end

  local cur, at = hafen.session():current(), 0
  for i, s in ipairs(list) do
    if s == cur then at = i end
  end

  for step = 1, #list do
    local want = list[((at + step - 1) % #list) + 1]
    if pcall(function() hafen.session():current(want) end) then return end
  end
end

-- The name is the label: Options ▸ Keybindings lists an addon hotkey under the name it was declared
-- with, so this is what the user reads there.
hafen.client():options():keybindings():on("Select next session", cycle)

hafen.console():on("sessions", function()
  if win and win:exists() then
    win:destroy()
    win, rows, newbtn = nil, {}, nil
  else
    build()
  end
end)

-- Where the user put it. A window you built is dragged by its own title bar, which reports nothing, so
-- the place is read on a slow timer rather than written from a gesture.
hafen.timer():every(SAVE_EVERY, function()
  if win and win:exists() then
    local p = win:position()
    if p then place.x, place.y = p.x, p.y end
  end
end)

build()

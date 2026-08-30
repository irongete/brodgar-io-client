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
-- The base is a PATCH -- hafen.virtual():patch(), a convex ring lying flat on the terrain -- anchored to
-- the character's own body. So the GROUND is what is coloured, rather than an ellipse drawn over it: it
-- follows the relief over a slope, a ridge and a tile boundary with no gap and no shimmer, whatever stands
-- on it occludes it, and its size is in world units at every zoom. A patch that follows an object is
-- re-laid where that object moved, so it is under the feet the whole way and it ends with the body.
--
-- None of that names a camera, and that is the point: an ellipse painted in screen pixels had to
-- reconstruct the view's turn, its tilt and the height of its own anchor out of probe points, and could
-- only approximate a hillside it was drawn flat across. The ground has all three already.
--
-- The base wears its own EDGE. A patch's border is carved out of the very distance its silhouette is
-- carved from, so a line all the way round the disc is not a second shape lying on the first -- which is
-- what an annulus would have had to be, and an annulus is not convex, and convex is the whole of what a
-- ring may be.
--
-- The line is what makes the ground read as a PLINTH rather than as a smudge, and it needs the fill and
-- the edge to carry their own opacities: the fill's is the fourth component of its tint, the line's is its
-- own colour's, and each is written once. :alpha() is not used here at all -- it multiplies the whole
-- patch, so it would fade the line along with the ground it stands round, which is the one thing the base
-- wants not to happen.

local MARK_ON  = {64, 255, 64}          -- the character on screen: the one taking your clicks
local MARK_OFF = {255, 255, 255}        -- the others, standing where they stand
local FILL_ON, FILL_OFF = 115, 56       -- how solid the GROUND under each one is, 0..255. The line round it
                                        -- is drawn at the colour's own default, which is solid.
local BORDER_W = 0.3                    -- the line's thickness in WORLD units, as the radius is. Thinner
                                        -- than a screen pixel it cannot be drawn, so it is still there
                                        -- zoomed all the way out.
local R_ON, R_OFF = 5, 4                -- the base's radius, in WORLD units: a tile is 11 across, so these
                                        -- are about a character wide. The ring is laid at R_ON and the
                                        -- faint one is SCALED down to R_OFF, because a patch's ring is what
                                        -- it was made from and cannot be changed afterwards -- and a scale
                                        -- re-carves the silhouette without rebuilding a thing.
local STEPS = 20                        -- points around the circle: a patch carries at most 32 edges
local RETRY = 0.25                      -- seconds between tries for a base that could not be laid yet

-- The pick at the end of this file is what makes a base CLICKABLE, and it is armed by a hotkey rather than
-- standing open: a clickable patch consumes the press it answers, so one that took clicks all the time
-- would eat every click on the ground a character is standing on. Laying a base has to ask whether a pick
-- is armed right now, and a base answering one has to end it, so both are named here and written there.
local picking = false
local disarm

-- One base per login, laid and taken up with the logins. [session] = {id = <the body it is under>, patch}
local marked = {}
local pending = false                   -- is there a base that wants laying and could not be?

-- The ring: a circle of real places around one. A patch keeps it as offsets from its anchor, so this is
-- measured once and means the same shape wherever the body walks to.
local function ringOf(p)
  local ring = {}
  for i = 1, STEPS do
    local a = ((i - 1) / STEPS) * math.pi * 2
    local q = p:offset(R_ON * math.cos(a), R_ON * math.sin(a))
    if not q then return nil end        -- ground the character on screen cannot locate
    ring[i] = q
  end
  return ring
end

-- Can the character ON SCREEN see that body? A base is laid only where the answer is yes, and that is not
-- a nicety. A patch keeps its ring as the displacement between the ring's points and its anchor, and a
-- world coordinate is one character's own frame -- so a ring measured in one character's numbers against a
-- body read in another's is a shape of the wrong size, quietly. It is also the only moment a base could be
-- drawn at all: a patch following an object is on the ground while the drawn character holds that object
-- and waits while it does not, so nothing is lost by waiting for the frame that can measure it.
local function drawnSees(gob, cur)
  return (cur ~= nil) and (gob:sessions():find(function(o) return o == cur end) ~= nil)
end

local function lay(s, gob)
  local p = gob:position()
  local ring = p and ringOf(p)
  if not ring then return nil end
  -- :add refuses a client that is not in the world yet, and a ring that came out as no shape at all.
  -- Either is a frame too early rather than a mistake, so it is retried instead of reported.
  local ok, patch = pcall(function() return hafen.virtual():patch():add(ring, gob) end)
  if not ok then return nil end

  patch:clickable(picking)
  patch:onClick(function()
    if not picking then return end      -- it is not clickable unless one is armed; belt and braces
    disarm()                            -- one click either way: armed is a moment, not a mode

    -- NEXT TICK, not here. We are inside the press's own dispatch: the map view is mid-message and the
    -- session that owns it is the one about to lose the screen, so switching from this line hands the
    -- screen away underneath the code still unwinding through it. A tick later there is no dispatch to be
    -- inside, and the switch is the SAME act the window's row performs -- which is the whole requirement:
    -- one way to go to a character, reached from three places.
    local user = s:user()
    hafen.timer():after(0, function() goTo(user) end)
  end)
  return {id = gob:id(), patch = patch}
end

-- Which of the two a base is. Both are writes on the patch rather than a decision taken again every frame,
-- so a switch of the screen is what re-reads them -- and neither costs the ground any work.
local function look(m, mine)
  local c = mine and MARK_ON or MARK_OFF
  m.patch:tint({c[1], c[2], c[3], mine and FILL_ON or FILL_OFF})
    :border(c, BORDER_W)
    :scale(mine and 1 or (R_OFF / R_ON))
end

local function unmark(s)
  local m = marked[s]
  if not m then return end
  marked[s] = nil
  if m.patch:exists() then hafen.virtual():patch():remove(m.patch) end
end

local function syncMarks()
  local list = hafen.session():list()
  local cur = hafen.session():current()
  local tell = (#list > 1)              -- with one login there is nobody to tell apart
  local seen = {}
  pending = false

  for _, s in ipairs(list) do
    local pl = tell and s:character() and s:player()
    local gob = pl and pl:gob()
    local id = gob and gob:id()
    local m = marked[s]
    if id and m and (m.id == id) and m.patch:exists() then
      seen[s] = true
      look(m, s == cur)                 -- the screen may have moved since it was laid
    elseif id then
      unmark(s)                         -- a character that came back is a new body under the same login,
                                        -- and a patch ends with the body it followed
      local new = drawnSees(gob, cur) and lay(s, gob) or nil
      if new then
        marked[s], seen[s] = new, true
        look(new, s == cur)
      else
        pending = true                  -- out of the drawn character's sight, or a frame too early
      end
    elseif tell and s:character() then
      pending = true                    -- in the world, and its body has not reached this client yet
    end
  end

  for s in pairs(marked) do             -- the logins that have gone, and every base when one is left
    if not seen[s] then unmark(s) end
  end
end

-- What could not be laid is tried again on a slow timer of its own. A base waiting on a body that has not
-- arrived is a frame or two; one waiting on a character the drawn one cannot see is however long that
-- takes, and neither is worth a pass every frame -- the client has nothing to draw for it meanwhile.
hafen.timer():every(RETRY, function()
  if pending then syncMarks() end
end)

-- The four session events are the whole of what changes a base: one connects, one reaches the world, the
-- screen moves -- which is both a relabel of every base and the frame that can at last measure one -- and
-- one ends.
for _, key in ipairs({"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved"}) do
  hafen.event():on(key, syncMarks)
end

syncMarks()                             -- and the logins the client already holds

-- ---------------------------------------------------------------- picking a character with the mouse
--
-- The client owns no gesture for this any more. The hotkey ARMS a pick: the pointer becomes a hand, and
-- the next click on the map names whoever it landed on. Two things count as naming somebody, and the order
-- is the client's own rather than something arranged here -- a clickable patch is hit-tested against its
-- own ring INSIDE the press, before the map view starts the pick pass an ordinary click rides on:
--
--   the base under the character  -- patch:onClick, the very ring the ground is coloured in
--   the character's own model     -- ev:gob() below, the client's own pick pass
--
-- Whatever it names, the click is CONSUMED -- a patch consumes the press it answered, and the handler
-- below calls preventDefault -- because an armed pick that also walked your character somewhere is a pick
-- nobody would use. A click that names nobody disarms and is let through, so a miss costs one click and
-- not a stuck mode.

local mouse = hafen.ui():mouse()

-- Every base takes clicks, or none does. This is the switch the base section is written around.
local function clicky(on)
  for _, m in pairs(marked) do
    if m.patch:exists() then m.patch:clickable(on) end
  end
end

disarm = function()
  picking = false
  clicky(false)
  mouse:cursor(nil)                     -- ours only: it puts back what WE forced, and nothing else
end

local function arm()
  if picking then
    disarm()                            -- the same key again is "never mind"
    return
  end
  if hafen.session():count() < 2 then return end   -- nothing to pick between
  picking = true
  clicky(true)
  mouse:cursor("hand")
end

-- The session a click names, or nil. The base is not asked here: a click inside one never reaches this
-- handler at all, having been answered and consumed by the patch itself.
local function named(ev)
  local g = ev:gob()
  if not g then return nil end
  local id = g:id()
  for _, s in ipairs(hafen.session():list()) do
    local pl = s:player()
    local own = pl and pl:gob()
    if own and (own:id() == id) then return s end
  end
  return nil
end

hafen.event():action():on("click", function(ev)
  if not picking then return end
  local sender = ev:widget()
  if not sender or sender:type() ~= "MapView" then return end

  local s = named(ev)
  disarm()                              -- one click either way: armed is a moment, not a mode
  if not s then return end              -- it named nobody; the click is the client's, untouched
  ev:preventDefault()

  -- NEXT TICK, for the reason the base's own handler states: we are inside the click's own dispatch.
  local user = s:user()
  hafen.timer():after(0, function() goTo(user) end)
end)

hafen.client():options():keybindings():on("Select character", arm)

-- ---------------------------------------------------------------- centring the view
--
-- Only the `rts` camera has a centre of its own to move. Every other one is bolted to the character, so the
-- view is ALREADY on what this key would centre and there is nothing left for it to do -- which is exactly
-- why s:world():focus(p) refuses there: it is handed a centre and has none to write. So the camera in force
-- is read first and the key stands down under the rest, rather than asking for a move and reporting the no.
hafen.client():options():keybindings():on("Focus selection", function()
  if hafen.client():options():camera():mode() ~= "rts" then return end
  local cur = hafen.session():current()
  local pl = cur and cur:player()
  local gob = pl and pl:gob()
  local p = gob and gob:position()
  if not p then return end
  cur:world():focus(p)
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

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
local DEF_X, DEF_Y = 60, 60          -- where the window stands before the user has moved it
local SAVE_EVERY = 2                 -- seconds between reads of where the user put it

local win                            -- the window, or nil once the user has closed it
local rows = {}                      -- [account] = {go = <button>, close = <button>}
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
  if s:exists() then hafen.session():current(s) end
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
    y = y + r.go:size().y + GAP
  end

  for user, r in pairs(rows) do                         -- the logins that have gone
    if not seen[user] then
      r.go:destroy()
      r.close:destroy()
      rows[user] = nil
    end
  end

  win:size(PAD + NAME_W + GAP + CLOSE_W + PAD, (#list > 0) and (y - GAP + PAD) or (PAD * 2))
  win:visible(#list > 0)                                -- nothing to switch between on the login screen
end

local function build()
  if win and win:exists() then return end
  rows = {}
  win = hafen.ui():window():title("Sessions")
    :position(place.x or DEF_X, place.y or DEF_Y)
    :size(PAD + NAME_W + GAP + CLOSE_W + PAD, PAD * 2)

  -- The chrome's close button destroys the window, so there is nothing left to hide: what this addon
  -- keeps afterwards is "there is no window", and :sessions builds a new one at the saved place.
  win:on("Close", function()
    win, rows = nil, {}
  end)

  refresh()
end

-- The four session events are the whole of what changes a row: one connects, one reaches the world and
-- gains a character name, the screen moves, one ends.
for _, key in ipairs({"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed"}) do
  hafen.event():on(key, refresh)
end

-- Forward through the sessions in the order they joined, and round. With nothing on screen the lap
-- starts at the first login, which is where the login screen leaves you.
--
-- A session that is still connecting has no screen to be given yet, and writing it leaves the screen
-- where it was -- so the step is checked and the cycle walks past that login rather than making the
-- user press the key twice for nothing. Bounded by the list: with nothing reachable it gives up.
local function cycle()
  local list = hafen.session():list()
  if #list == 0 then return end

  local cur, at = hafen.session():current(), 0
  for i, s in ipairs(list) do
    if s == cur then at = i end
  end

  for step = 1, #list do
    local want = list[((at + step - 1) % #list) + 1]
    hafen.session():current(want)
    if hafen.session():current() == want then return end
  end
end

hafen.client():options():keybindings():register("next", cycle)

hafen.slash():register("sessions", function()
  if win and win:exists() then
    win:destroy()
    win, rows = nil, {}
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
